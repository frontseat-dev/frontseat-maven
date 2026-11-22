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
        return testClasspath(true);
    }

    /**
     * @param ownOutputs false leaves out the project's own classes and test-classes directories: for
     *                   mojos that only need the dependencies, or that run before those directories are
     *                   built (their content would be whatever an earlier build left there)
     */
    public static InputSpec testClasspath(boolean ownOutputs) {
        return ctx -> {
            if (ctx.analysisOnly()) {
                return List.of(ResolvedInput.classpath("testClasspath", List.of(), "test"));
            }
            List<Path> paths = new ArrayList<>();
            try {
                Path classes = Path.of(ctx.project().getBuild().getOutputDirectory()).toAbsolutePath().normalize();
                Path testClasses = Path.of(ctx.project().getBuild().getTestOutputDirectory()).toAbsolutePath().normalize();
                List<Path> elements = ctx.testClasspath();
                if (elements == null) {
                    elements = new ArrayList<>();
                    for (String element : ctx.project().getTestClasspathElements()) {
                        elements.add(Path.of(element));
                    }
                }
                for (Path element : elements) {
                    Path path = element.toAbsolutePath().normalize();
                    if (ownOutputs || !(path.equals(classes) || path.equals(testClasses))) {
                        paths.add(element);
                    }
                }
            } catch (NotCacheableException e) {
                throw e;
            } catch (Exception e) {
                throw new NotCacheableException("cannot compute test classpath: " + e.getMessage(), e);
            }
            return List.of(ResolvedInput.classpath("testClasspath", paths, "test"));
        };
    }

    /**
     * The project's own files: everything in its directory except the build directory, the directories
     * of other reactor projects (nested modules) and VCS data. For mojos that scan the project, such as
     * license or format checks.
     */
    public static InputSpec projectFiles(List<String> excludes) {
        return ctx -> {
            Path basedir = ctx.project().getBasedir().toPath().toAbsolutePath().normalize();
            // .mvn/target: where Maven 4 keeps its project-local repository in some layouts.
            List<String> all = new ArrayList<>(List.of(".git/**", ".mvn/target/**"));
            Path build = Path.of(ctx.project().getBuild().getDirectory()).toAbsolutePath().normalize();
            if (build.startsWith(basedir) && !build.equals(basedir)) {
                all.add(FileTrees.relative(basedir, build) + "/**");
            }
            List<String> modules = new ArrayList<>();
            for (org.apache.maven.project.MavenProject other : ctx.session().getAllProjects()) {
                if (other.getBasedir() == null) {
                    continue;
                }
                Path dir = other.getBasedir().toPath().toAbsolutePath().normalize();
                if (dir.startsWith(basedir) && !dir.equals(basedir)) {
                    modules.add(FileTrees.relative(basedir, dir) + "/**");
                }
            }
            java.util.Collections.sort(modules);
            all.addAll(modules);
            all.addAll(excludes);
            return List.of(ResolvedInput.files("projectFiles", basedir, List.of(), all));
        };
    }

    /**
     * The project's effective model (its pom with parents, profiles and properties applied), for mojos
     * that read the project rather than their own configuration, such as rule checks.
     */
    public static InputSpec projectModel() {
        return ctx -> {
            return List.of(ResolvedInput.value("projectModel", modelHash(ctx, ctx.project(), true)));
        };
    }

    /**
     * The effective models of every project of the build, for aggregator mojos (one execution that reads
     * the whole reactor, such as an aggregate bill of materials).
     */
    public static InputSpec reactorModels() {
        return ctx -> {
            java.util.TreeMap<String, String> models = new java.util.TreeMap<>();
            for (org.apache.maven.project.MavenProject p : ctx.session().getProjects()) {
                // Without properties: other projects' mojos set them while this one is being built (in
                // parallel builds, at no fixed moment), and the model's values are already interpolated.
                models.put(p.getGroupId() + ":" + p.getArtifactId(), modelHash(ctx, p, false));
            }
            // An aggregator does its work in the project the build was started from.
            return List.of(ResolvedInput.value("reactorModels", dev.frontseat.mojoio.cache.Hashing.sha256(
                    models + " executionRoot=" + ctx.project().isExecutionRoot())));
        };
    }

    /**
     * The goals and phases the build was started with, for mojos whose result depends on them (a check
     * of the build plan).
     */
    public static InputSpec sessionGoals() {
        return ctx -> List.of(ResolvedInput.value("sessionGoals", String.valueOf(ctx.session().getGoals())));
    }

    private static String modelHash(ExecutionContext ctx, org.apache.maven.project.MavenProject project,
                                    boolean withProperties) throws NotCacheableException {
        // Properties are written apart and sorted: their order in the model is that of a hash table,
        // which depends on how the table grew, not on its content.
        org.apache.maven.model.Model model = project.getModel().clone();
        java.util.TreeMap<String, String> properties = new java.util.TreeMap<>();
        if (withProperties) {
            model.getProperties().stringPropertyNames().forEach(k -> properties.put(k, model.getProperties().getProperty(k)));
        }
        model.setProperties(new java.util.Properties());
        java.io.StringWriter out = new java.io.StringWriter();
        try {
            new org.apache.maven.model.io.xpp3.MavenXpp3Writer().write(out, model);
        } catch (java.io.IOException e) {
            throw new NotCacheableException("cannot serialize the project model: " + e.getMessage(), e);
        }
        return dev.frontseat.mojoio.cache.Hashing.sha256(ctx.normalizer().normalize(out + "\nproperties=" + properties));
    }

    /** The Maven version, for mojos whose result depends on it (a rule requiring a Maven version). */
    public static InputSpec mavenVersion() {
        return ctx -> List.of(ResolvedInput.value("mavenVersion",
                ctx.session().getSystemProperties().getProperty("maven.version", "unknown")));
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
        Object value = ctx.parameter(parameter);
        org.codehaus.plexus.util.xml.Xpp3Dom node = ctx.parameterNode(parameter);
        if (node != null && node.getChildren("resource").length > 0
                && !(value instanceof List<?> l && l.stream().allMatch(o -> o instanceof Resource))) {
            return inlineResources(ctx, node);
        }
        return asResources(value, "parameter '" + parameter + "'");
    }

    /** Resources written out in the plugin configuration ({@code <resources><resource>…}). */
    private static List<Resource> inlineResources(ExecutionContext ctx, org.codehaus.plexus.util.xml.Xpp3Dom node)
            throws NotCacheableException {
        List<Resource> resources = new ArrayList<>();
        for (org.codehaus.plexus.util.xml.Xpp3Dom r : node.getChildren("resource")) {
            Resource resource = new Resource();
            for (org.codehaus.plexus.util.xml.Xpp3Dom field : r.getChildren()) {
                switch (field.getName()) {
                    case "directory" -> {
                        Path dir = Path.of(String.valueOf(ctx.evaluateLeaf(field)));
                        resource.setDirectory((dir.isAbsolute() ? dir
                                : ctx.project().getBasedir().toPath().resolve(dir)).normalize().toString());
                    }
                    case "targetPath" -> resource.setTargetPath(String.valueOf(ctx.evaluateLeaf(field)));
                    case "filtering" -> resource.setFiltering(String.valueOf(ctx.evaluateLeaf(field)));
                    case "includes" -> {
                        for (org.codehaus.plexus.util.xml.Xpp3Dom i : field.getChildren()) {
                            resource.addInclude(String.valueOf(ctx.evaluateLeaf(i)));
                        }
                    }
                    case "excludes" -> {
                        for (org.codehaus.plexus.util.xml.Xpp3Dom e : field.getChildren()) {
                            resource.addExclude(String.valueOf(ctx.evaluateLeaf(e)));
                        }
                    }
                    default -> throw new NotCacheableException("resource setting <" + field.getName() + "> is not supported");
                }
            }
            if (resource.getDirectory() == null) {
                throw new NotCacheableException("a configured resource has no directory");
            }
            resources.add(resource);
        }
        return resources;
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
