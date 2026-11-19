package dev.frontseat.maven.extension.descriptor;

import dev.frontseat.maven.cache.AntPattern;
import dev.frontseat.maven.cache.FileTrees;
import dev.frontseat.maven.cache.Hashing;
import dev.frontseat.maven.extension.ExecutionContext;
import dev.frontseat.maven.extension.NotCacheableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;

/** Built-in input kinds. */
public final class Inputs {

    /**
     * {@code -D} properties that configure the build tooling rather than the build, so they never
     * reach a filtered resource. Leaving them in would make e.g. {@code -Dmaven.repo.local} a cache miss.
     */
    static final Set<String> PLUMBING_PROPERTIES = Set.of(
            "maven.repo.local", "maven.ext.class.path", "maven.multiModuleProjectDirectory", "maven.home",
            "maven.conf", "classworlds.conf", "library.jansi.path", "style.color");

    private Inputs() {}

    /**
     * Content of the files or directory trees named by a parameter (a path, or a list of paths).
     * Order is kept; each path is recorded relative to the project or root, plus its content hash.
     */
    public static InputSpec files(String parameter) {
        return (ctx, key) -> {
            int i = 0;
            for (Path p : ctx.parameterPaths(parameter)) {
                key.add("input." + parameter + "[" + i++ + "]",
                        ctx.normalizer().normalize(p.toString()) + " " + FileTrees.hash(p));
            }
        };
    }

    /**
     * A classpath parameter. Elements are hashed by content only (not location), in order, so a
     * reactor module's {@code target/classes} and the same classes elsewhere hash alike.
     *
     * <p>The mojo's own output directory is skipped: Maven puts it on the classpath (javac's
     * {@code compilePath} starts with {@code target/classes}), which would make the key depend on
     * the previous run's outputs.
     */
    public static InputSpec classpath(String parameter, String ownOutputParameter) {
        return (ctx, key) -> {
            List<Path> own = ownOutputParameter == null ? List.of() : ctx.parameterPaths(ownOutputParameter);
            List<String> hashes = new ArrayList<>();
            for (Path p : ctx.parameterPaths(parameter)) {
                if (own.contains(p)) {
                    hashes.add("self");
                    continue;
                }
                hashes.add((Files.isDirectory(p) ? "dir:" : "file:") + FileTrees.hash(p));
            }
            key.add("input." + parameter, String.join(",", hashes));
        };
    }

    /** The project's test classpath, for mojos that build it internally (surefire). */
    public static InputSpec testClasspath() {
        return (ctx, key) -> {
            List<String> hashes = new ArrayList<>();
            try {
                for (String element : ctx.project().getTestClasspathElements()) {
                    Path p = Path.of(element);
                    hashes.add((Files.isDirectory(p) ? "dir:" : "file:") + FileTrees.hash(p));
                }
            } catch (Exception e) {
                throw new NotCacheableException("cannot compute test classpath: " + e.getMessage(), e);
            }
            key.add("input.testClasspath", String.join(",", hashes));
        };
    }

    /**
     * The current value of a project property whose name is given by a parameter (falling back to
     * {@code defaultName}). Used for mojos that read-modify-write a property, like
     * {@code jacoco:prepare-agent} prepending to {@code argLine}.
     */
    public static InputSpec projectPropertyNamedBy(String parameter, String defaultName) {
        return (ctx, key) -> {
            Object configured = parameter == null ? null : ctx.parameter(parameter);
            String name = configured == null || configured.toString().isBlank() ? defaultName : configured.toString();
            String value = ctx.project().getProperties().getProperty(name);
            key.add("input.property." + name, ctx.normalizer().normalize(value));
        };
    }

    /**
     * Resource sets (a {@code List<Resource>} parameter): for each resource, its settings and the
     * content of the files it selects. If any resource is filtered, the values filtering can read are
     * inputs too: the project's coordinates, its properties and the {@code -D} user properties.
     * System properties are deliberately not hashed (they would make every machine miss).
     */
    public static InputSpec resources(String parameter) {
        return (ctx, key) -> {
            List<Resource> resources = resourceList(ctx, parameter);
            boolean filtering = false;
            int i = 0;
            for (Resource r : resources) {
                Path dir = Path.of(r.getDirectory());
                StringBuilder files = new StringBuilder();
                for (Path f : selectedFiles(r, true)) {
                    files.append(FileTrees.relative(dir, f)).append('=').append(Hashing.sha256(f)).append(';');
                }
                key.add("input." + parameter + "[" + i++ + "]",
                        ctx.normalizer().normalize(r.getDirectory())
                                + " target=" + r.getTargetPath()
                                + " filtering=" + r.isFiltering()
                                + " includes=" + r.getIncludes() + " excludes=" + r.getExcludes()
                                + " files=" + Hashing.sha256(files.toString()));
                filtering |= r.isFiltering();
            }
            if (filtering) {
                MavenProject p = ctx.project();
                key.add("input.filtering.project",
                        p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion() + " name=" + p.getName()
                                + " description=" + p.getDescription());
                key.add("input.filtering.properties", sortedNormalized(ctx, p.getProperties()));
                Map<Object, Object> user = new TreeMap<>();
                ctx.session().getUserProperties().forEach((k, v) -> {
                    String name = String.valueOf(k);
                    if (!name.startsWith("frontseat.") && !PLUMBING_PROPERTIES.contains(name)) {
                        user.put(name, v);
                    }
                });
                key.add("input.filtering.userProperties", sortedNormalized(ctx, user));
            }
        };
    }

    @SuppressWarnings("unchecked")
    static List<Resource> resourceList(ExecutionContext ctx, String parameter) throws NotCacheableException {
        Object value = ctx.parameter(parameter);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(o -> o instanceof Resource)) {
            throw new NotCacheableException("parameter '" + parameter + "' is not a list of resources");
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

    private static String sortedNormalized(ExecutionContext ctx, Map<Object, Object> props) {
        TreeMap<String, String> sorted = new TreeMap<>();
        props.forEach((k, v) -> sorted.put(String.valueOf(k), ctx.normalizer().normalize(String.valueOf(v))));
        return Hashing.sha256(sorted.toString());
    }
}
