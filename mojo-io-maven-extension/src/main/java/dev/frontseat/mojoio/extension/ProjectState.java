package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.cache.CacheEntry.StateRecord;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

/**
 * The parts of {@link MavenProject} that mojos commonly mutate and that later mojos read. A snapshot
 * is taken before a cache-miss execution and diffed afterwards; the diff is stored with the outputs
 * and replayed on a hit.
 *
 * <p>Changes this class cannot replay (removed source roots, changed resources, replaced attachments)
 * make the execution non-cacheable rather than being silently dropped. Changes it cannot see at all
 * (static fields, system properties, the session) are why descriptors exist: mojos known to make
 * them are marked non-cacheable.
 */
final class ProjectState {

    static final String COMPILE_SOURCE_ROOT = "compileSourceRoot";
    static final String TEST_COMPILE_SOURCE_ROOT = "testCompileSourceRoot";
    static final String PROPERTY = "property";
    static final String PROPERTY_REMOVED = "propertyRemoved";
    static final String ARTIFACT_FILE = "artifactFile";
    static final String ATTACHED_ARTIFACT = "attachedArtifact";
    static final String SYSTEM_PROPERTY = "systemProperty";
    static final String SYSTEM_PROPERTY_REMOVED = "systemPropertyRemoved";
    /** Not a project change: a file the execution wrote that an observed output covers (see Outputs#observed). */
    static final String OUTPUT = "output";

    /** JVM and Maven runtime properties: they change for reasons unrelated to the mojo. */
    private static final java.util.List<String> JVM_PREFIXES = java.util.List.of(
            "java.", "jdk.", "sun.", "os.", "user.", "file.", "line.", "path.", "awt.", "javax.", "jna.",
            "maven.", "classworlds.", "library.", "native.", "stdout.", "stderr.", "socksProxy", "http.", "https.");

    private final List<String> compileSourceRoots;
    private final List<String> testCompileSourceRoots;
    private final List<String> resources;
    private final List<String> testResources;
    private final Map<String, String> properties;
    private final String artifactFile;
    private final Map<String, String> systemProperties;
    /** type:classifier -> file, in attachment order. */
    private final Map<String, String> attachedArtifacts;

    private ProjectState(MavenProject project, boolean trackSystemProperties) {
        compileSourceRoots = new ArrayList<>(project.getCompileSourceRoots());
        testCompileSourceRoots = new ArrayList<>(project.getTestCompileSourceRoots());
        resources = describe(project.getResources());
        testResources = describe(project.getTestResources());
        properties = new TreeMap<>();
        project.getProperties().forEach((k, v) -> properties.put(String.valueOf(k), String.valueOf(v)));
        File file = project.getArtifact() == null ? null : project.getArtifact().getFile();
        artifactFile = file == null ? null : file.getAbsolutePath();
        systemProperties = new TreeMap<>();
        (trackSystemProperties ? System.getProperties() : new java.util.Properties()).forEach((k, v) -> {
            String name = String.valueOf(k);
            if (JVM_PREFIXES.stream().noneMatch(name::startsWith)) {
                systemProperties.put(name, String.valueOf(v));
            }
        });
        attachedArtifacts = new LinkedHashMap<>();
        for (Artifact a : project.getAttachedArtifacts()) {
            attachedArtifacts.put(attachmentId(a.getType(), a.getClassifier()),
                    a.getFile() == null ? null : a.getFile().getAbsolutePath());
        }
    }

    static ProjectState capture(MavenProject project) {
        return new ProjectState(project, true);
    }

    static ProjectState capture(MavenProject project, boolean systemProperties) {
        return new ProjectState(project, systemProperties);
    }

    /** Normalized records that turn {@code this} into {@code after}. */
    List<StateRecord> diff(ProjectState after, PathNormalizer n) throws NotCacheableException {
        List<StateRecord> records = new ArrayList<>();
        diffRoots(COMPILE_SOURCE_ROOT, compileSourceRoots, after.compileSourceRoots, records, n);
        diffRoots(TEST_COMPILE_SOURCE_ROOT, testCompileSourceRoots, after.testCompileSourceRoots, records, n);
        if (!resources.equals(after.resources) || !testResources.equals(after.testResources)) {
            throw new NotCacheableException("execution changed project resources");
        }
        for (Map.Entry<String, String> e : attachedArtifacts.entrySet()) {
            if (!Objects.equals(e.getValue(), after.attachedArtifacts.get(e.getKey()))) {
                throw new NotCacheableException("execution removed or replaced attached artifact " + e.getKey());
            }
        }
        Map<String, String> changed = new LinkedHashMap<>();
        after.properties.forEach((k, v) -> {
            if (!Objects.equals(properties.get(k), v)) {
                changed.put(k, v);
            }
        });
        changed.forEach((k, v) -> records.add(new StateRecord(PROPERTY, k, n.normalize(v))));
        for (String k : properties.keySet()) {
            if (!after.properties.containsKey(k)) {
                records.add(new StateRecord(PROPERTY_REMOVED, k, null));
            }
        }
        if (!Objects.equals(artifactFile, after.artifactFile)) {
            records.add(new StateRecord(ARTIFACT_FILE, "", n.normalize(after.artifactFile)));
        }
        after.systemProperties.forEach((k, v) -> {
            if (!Objects.equals(systemProperties.get(k), v)) {
                records.add(new StateRecord(SYSTEM_PROPERTY, k, n.normalize(v)));
            }
        });
        for (String k : systemProperties.keySet()) {
            if (!after.systemProperties.containsKey(k)) {
                records.add(new StateRecord(SYSTEM_PROPERTY_REMOVED, k, null));
            }
        }
        for (Map.Entry<String, String> e : after.attachedArtifacts.entrySet()) {
            if (!attachedArtifacts.containsKey(e.getKey())) {
                records.add(new StateRecord(ATTACHED_ARTIFACT, e.getKey(), n.normalize(e.getValue())));
            }
        }
        return records;
    }

    private static void diffRoots(String kind, List<String> before, List<String> after, List<StateRecord> out,
                                  PathNormalizer n) throws NotCacheableException {
        if (!after.containsAll(before)) {
            throw new NotCacheableException("execution removed a " + kind);
        }
        for (String root : after) {
            if (!before.contains(root)) {
                out.add(new StateRecord(kind, "", n.normalize(root)));
            }
        }
    }

    /** Replays normalized records onto {@code project}. */
    static void apply(List<StateRecord> records, MavenProject project, PathNormalizer n, MavenProjectHelper helper) {
        for (StateRecord r : records) {
            String value = n.denormalize(r.value());
            switch (r.kind()) {
                case COMPILE_SOURCE_ROOT -> project.addCompileSourceRoot(value);
                case TEST_COMPILE_SOURCE_ROOT -> project.addTestCompileSourceRoot(value);
                case PROPERTY -> project.getProperties().setProperty(r.name(), value);
                case PROPERTY_REMOVED -> project.getProperties().remove(r.name());
                case ARTIFACT_FILE -> project.getArtifact().setFile(value == null ? null : new File(value));
                case ATTACHED_ARTIFACT -> attach(project, r.name(), value, helper);
                case SYSTEM_PROPERTY -> System.setProperty(r.name(), value);
                case SYSTEM_PROPERTY_REMOVED -> System.clearProperty(r.name());
                case OUTPUT -> { } // informational, for external caches
                default -> throw new IllegalStateException("Unknown state record kind " + r.kind());
            }
        }
    }

    /** Whether {@code r}'s effect is (still) visible on {@code project}. */
    static boolean holds(StateRecord r, MavenProject project, PathNormalizer n) {
        String value = n.denormalize(r.value());
        return switch (r.kind()) {
            case COMPILE_SOURCE_ROOT -> project.getCompileSourceRoots().contains(value);
            case TEST_COMPILE_SOURCE_ROOT -> project.getTestCompileSourceRoots().contains(value);
            case PROPERTY -> Objects.equals(project.getProperties().getProperty(r.name()), value);
            case PROPERTY_REMOVED -> !project.getProperties().containsKey(r.name());
            case ARTIFACT_FILE -> project.getArtifact() != null && project.getArtifact().getFile() != null
                    && project.getArtifact().getFile().getAbsolutePath().equals(value);
            case ATTACHED_ARTIFACT -> project.getAttachedArtifacts().stream().anyMatch(a ->
                    attachmentId(a.getType(), a.getClassifier()).equals(r.name()));
            case SYSTEM_PROPERTY -> Objects.equals(System.getProperty(r.name()), value);
            case SYSTEM_PROPERTY_REMOVED -> System.getProperty(r.name()) == null;
            default -> false;
        };
    }

    static String attachmentId(String type, String classifier) {
        return type + ":" + (classifier == null ? "" : classifier);
    }

    private static void attach(MavenProject project, String id, String file, MavenProjectHelper helper) {
        String[] tc = id.split(":", 2);
        String classifier = tc.length > 1 && !tc[1].isEmpty() ? tc[1] : null;
        for (Artifact a : project.getAttachedArtifacts()) {
            if (attachmentId(a.getType(), a.getClassifier()).equals(id)) {
                a.setFile(new File(file)); // already attached (e.g. replayed twice): just point at the file
                return;
            }
        }
        helper.attachArtifact(project, tc[0], classifier, new File(file));
    }

    private static List<String> describe(List<Resource> resources) {
        List<String> out = new ArrayList<>();
        for (Resource r : resources) {
            out.add(r.getDirectory() + "|" + r.getTargetPath() + "|" + r.getFiltering() + "|" + r.getIncludes()
                    + "|" + r.getExcludes());
        }
        return out;
    }
}
