package dev.frontseat.mojoio.extension.descriptor;

import dev.frontseat.mojoio.cache.AntPattern;
import dev.frontseat.mojoio.cache.FileTrees;
import dev.frontseat.mojoio.extension.ExecutionContext;
import dev.frontseat.mojoio.extension.NotCacheableException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.model.Resource;

/** Built-in input kinds (see docs/plugin-io.md). */
public final class Inputs {

    private Inputs() {}

    /** Files or directory trees named by a parameter (a path, or a list of paths). */
    public static InputSpec files(String parameter) {
        return ctx -> List.of(ResolvedInput.files(parameter, ctx.parameterPaths(parameter)));
    }

    /** Files or directory trees a Maven expression evaluates to, e.g. {@code ${project.file}}. */
    public static InputSpec filesAt(String expression) {
        return ctx -> List.of(ResolvedInput.files(expression, ctx.paths(ctx.evaluateExpression(expression))));
    }

    /**
     * A classpath parameter, compared by content in order. The mojo's own output directory is skipped:
     * Maven puts it on the classpath (javac's {@code compilePath} starts with {@code target/classes}),
     * which would make the key depend on the previous run's outputs.
     */
    public static InputSpec classpath(String parameter, String ownOutputParameter) {
        return ctx -> {
            String scope = ctx.execution().getMojoDescriptor() == null ? null
                    : ctx.execution().getMojoDescriptor().getDependencyResolutionRequired();
            if (ctx.analysisOnly()) {
                return List.of(ResolvedInput.classpath(parameter, List.of(), scope));
            }
            List<Path> own = ownOutputParameter == null ? List.of() : ctx.parameterPaths(ownOutputParameter);
            List<Path> paths = new ArrayList<>(ctx.parameterPaths(parameter));
            paths.removeAll(own);
            return List.of(ResolvedInput.classpath(parameter, paths, scope));
        };
    }

    /** The project's test classpath, for mojos that build it internally (surefire). */
    public static InputSpec testClasspath() {
        return ctx -> {
            if (ctx.analysisOnly()) {
                return List.of(ResolvedInput.classpath("testClasspath", List.of(), "test"));
            }
            List<Path> paths = new ArrayList<>();
            try {
                for (String element : ctx.project().getTestClasspathElements()) {
                    paths.add(Path.of(element));
                }
            } catch (Exception e) {
                throw new NotCacheableException("cannot compute test classpath: " + e.getMessage(), e);
            }
            return List.of(ResolvedInput.classpath("testClasspath", paths, "test"));
        };
    }

    /**
     * The current value of a project property whose name is given by a parameter (falling back to
     * {@code defaultName}). For mojos that read-modify-write a property, like
     * {@code jacoco:prepare-agent} prepending to {@code argLine}.
     */
    public static InputSpec projectPropertyNamedBy(String parameter, String defaultName) {
        return ctx -> {
            Object configured = parameter == null ? null : ctx.parameter(parameter);
            String name = configured == null || configured.toString().isBlank() ? defaultName : configured.toString();
            String value = ctx.project().getProperties().getProperty(name);
            return List.of(ResolvedInput.value("property." + name, ctx.normalizer().normalize(value)));
        };
    }

    /** Expressions resource filtering substitutes, with the default delimiters {@code ${...}} and {@code @...@}. */
    private static final java.util.regex.Pattern FILTER_EXPRESSION =
            java.util.regex.Pattern.compile("\\$\\{([^}\\s]+)}|@([A-Za-z0-9_.\\-]+)@");

    /**
     * Resource sets (a {@code List<Resource>} parameter): for each resource, its settings and the files it
     * selects. For filtered resources, every expression the files actually reference is an input too,
     * evaluated the way filtering evaluates it (project model, properties, user and system properties).
     * Hashing only referenced expressions keeps keys stable across Maven-internal properties.
     */
    public static InputSpec resources(String parameter) {
        return resources(parameter, null);
    }

    /** Resources from a Maven expression instead of a parameter, e.g. {@code ${project.resources}}. */
    public static InputSpec resourcesAt(String expression) {
        return resources(expression, expression);
    }

    private static InputSpec resources(String parameter, String sourceExpression) {
        return ctx -> {
            List<ResolvedInput> result = new ArrayList<>();
            java.util.TreeSet<String> expressions = new java.util.TreeSet<>();
            int i = 0;
            List<Resource> list = sourceExpression == null ? resourceList(ctx, parameter)
                    : asResources(ctx.evaluateExpression(sourceExpression), sourceExpression);
            for (Resource r : list) {
                List<String> excludes = new ArrayList<>(r.getExcludes());
                excludes.addAll(AntPattern.DEFAULT_EXCLUDES);
                String name = parameter + "[" + i++ + "]";
                result.add(ResolvedInput.files(name, Path.of(r.getDirectory()), r.getIncludes(), excludes));
                result.add(ResolvedInput.value(name + ".settings", "target=" + r.getTargetPath()
                        + " filtering=" + r.isFiltering()));
                if (r.isFiltering()) {
                    for (Path f : selectedFiles(r, true)) {
                        expressions.addAll(referencedExpressions(f));
                    }
                }
            }
            for (String expression : expressions) {
                Object value;
                try {
                    value = ctx.evaluateExpression("${" + expression + "}");
                } catch (NotCacheableException e) {
                    value = "<unresolved>";
                }
                result.add(ResolvedInput.value("filtering.${" + expression + "}",
                        value == null ? null : ctx.normalizer().normalize(String.valueOf(value))));
            }
            return result;
        };
    }

    static java.util.Set<String> referencedExpressions(Path file) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        String text;
        try {
            if (java.nio.file.Files.size(file) > 4 * 1024 * 1024) {
                return found; // not a text resource anyone filters
            }
            text = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        java.util.regex.Matcher m = FILTER_EXPRESSION.matcher(text);
        while (m.find()) {
            found.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return found;
    }

    static List<Resource> resourceList(ExecutionContext ctx, String parameter) throws NotCacheableException {
        return asResources(ctx.parameter(parameter), "parameter '" + parameter + "'");
    }

    @SuppressWarnings("unchecked")
    private static List<Resource> asResources(Object value, String what) throws NotCacheableException {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(o -> o instanceof Resource)) {
            throw new NotCacheableException(what + " is not a list of resources");
        }
        return (List<Resource>) list;
    }

    /** Files a resource selects, honouring includes, excludes and (optionally) Maven's default excludes. */
    static List<Path> selectedFiles(Resource r, boolean defaultExcludes) {
        Path dir = Path.of(r.getDirectory());
        List<String> excludes = new ArrayList<>(r.getExcludes());
        if (defaultExcludes) {
            excludes.addAll(AntPattern.DEFAULT_EXCLUDES);
        }
        List<Path> selected = new ArrayList<>();
        for (Path f : FileTrees.listFiles(dir)) {
            if (AntPattern.selected(FileTrees.relative(dir, f), r.getIncludes(), excludes)) {
                selected.add(f);
            }
        }
        return selected;
    }
}
