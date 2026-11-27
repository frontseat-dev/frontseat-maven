package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.spec.StateChange;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The parts of {@link MavenProject} that mojos commonly mutate and that later mojos read. A snapshot
 * is taken before an execution and diffed afterwards; the diff is recorded in the execution's state
 * file and replayed when the execution does not run.
 *
 * <p>Changes this class cannot replay (removed source roots, changed resources, replaced attachments)
 * make the recorded state incomplete rather than being silently dropped. Changes it cannot see at all
 * (static fields, system properties, the session) are why descriptors exist: mojos known to make
 * them are marked non-cacheable.
 */
final class ProjectState {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectState.class);

    static final String COMPILE_SOURCE_ROOT = "compileSourceRoot";
    static final String TEST_COMPILE_SOURCE_ROOT = "testCompileSourceRoot";
    static final String PROPERTY = "property";
    static final String PROPERTY_REMOVED = "propertyRemoved";
    static final String ARTIFACT_FILE = "artifactFile";
    static final String ATTACHED_ARTIFACT = "attachedArtifact";
    static final String SYSTEM_PROPERTY = "systemProperty";
    static final String SYSTEM_PROPERTY_REMOVED = "systemPropertyRemoved";
    /**
     * A text value in the project context ({@code MavenProject#setContextValue}), or, named
     * {@code @<class>#<key>}, in the context of the object of that class kept there: Eclipse Tycho's
     * ReactorProject, where its mojos hand values to later ones (the Service-Component header).
     */
    static final String CONTEXT_VALUE = "contextValue";

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
    /** Text values of the project context, and of the contexts of objects kept in it. */
    private final Map<String, String> contextValues;
    /** The other values, by identity: replay cannot recreate them. */
    private final Map<String, Object> contextObjects;
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
        contextValues = new TreeMap<>();
        contextObjects = new TreeMap<>();
        context(project).forEach((k, v) -> {
            if (v instanceof String text) {
                contextValues.put(k, text);
            } else if (v != null) {
                contextObjects.put(k, v);
                // Named by class: Tycho's key holds an identity hash, different in every JVM.
                String owner = "@" + v.getClass().getName() + "#";
                context(v).forEach((nk, nv) -> {
                    if (nv instanceof String text) {
                        contextValues.put(owner + nk, text);
                    } else if (nv != null) {
                        contextObjects.put(owner + nk, nv);
                    }
                });
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
    List<StateChange> diff(ProjectState after, PathNormalizer n) throws NotCacheableException {
        List<StateChange> records = new ArrayList<>();
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
        changed.forEach((k, v) -> records.add(new StateChange(PROPERTY, k, n.normalize(v))));
        for (String k : properties.keySet()) {
            if (!after.properties.containsKey(k)) {
                records.add(new StateChange(PROPERTY_REMOVED, k, null));
            }
        }
        after.contextValues.forEach((k, v) -> {
            if (!Objects.equals(contextValues.get(k), v)) {
                records.add(new StateChange(CONTEXT_VALUE, k, n.normalize(v)));
            }
        });
        after.contextObjects.forEach((k, v) -> {
            if (contextObjects.get(k) != v) {
                LOG.debug("[mojo-io] execution set context value {} ({}): not recorded", k, v.getClass().getName());
            }
        });
        if (!Objects.equals(artifactFile, after.artifactFile)) {
            records.add(new StateChange(ARTIFACT_FILE, "", n.normalize(after.artifactFile)));
        }
        after.systemProperties.forEach((k, v) -> {
            if (!Objects.equals(systemProperties.get(k), v)) {
                records.add(new StateChange(SYSTEM_PROPERTY, k, n.normalize(v)));
            }
        });
        for (String k : systemProperties.keySet()) {
            if (!after.systemProperties.containsKey(k)) {
                records.add(new StateChange(SYSTEM_PROPERTY_REMOVED, k, null));
            }
        }
        for (Map.Entry<String, String> e : after.attachedArtifacts.entrySet()) {
            if (!attachedArtifacts.containsKey(e.getKey())) {
                records.add(new StateChange(ATTACHED_ARTIFACT, e.getKey(), n.normalize(e.getValue())));
            }
        }
        return records;
    }

    private static void diffRoots(String kind, List<String> before, List<String> after, List<StateChange> out,
                                  PathNormalizer n) throws NotCacheableException {
        if (!after.containsAll(before)) {
            throw new NotCacheableException("execution removed a " + kind);
        }
        for (String root : after) {
            if (!before.contains(root)) {
                out.add(new StateChange(kind, "", n.normalize(root)));
            }
        }
    }

    /** Replays normalized records onto {@code project}. */
    static void apply(List<StateChange> records, MavenProject project, PathNormalizer n, MavenProjectHelper helper) {
        for (StateChange r : records) {
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
                case CONTEXT_VALUE -> setContextValue(project, r.name(), value);
                default -> throw new IllegalStateException("Unknown state record kind " + r.kind());
            }
        }
    }

    /** Whether {@code r}'s effect is (still) visible on {@code project}. */
    static boolean holds(StateChange r, MavenProject project, PathNormalizer n) {
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
            case CONTEXT_VALUE -> Objects.equals(contextValue(project, r.name()), value);
            default -> false;
        };
    }

    /**
     * The context of {@code owner} (a MavenProject, or an object kept in its context with one of its
     * own): neither has an API to list it. Empty if there is no readable {@code context} map.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> context(Object owner) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            java.lang.reflect.Field f;
            try {
                f = c.getDeclaredField("context");
            } catch (NoSuchFieldException e) {
                continue;
            }
            if (!Map.class.isAssignableFrom(f.getType())) {
                return Map.of();
            }
            try {
                f.setAccessible(true);
                Map<String, Object> context = (Map<String, Object>) f.get(owner);
                return context == null ? Map.of() : new LinkedHashMap<>(context);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return Map.of();
            }
        }
        return Map.of();
    }

    /** The object of class {@code className} in the project context, or {@code null}. */
    private static Object contextOwner(MavenProject project, String className) {
        for (Object v : context(project).values()) {
            if (v != null && v.getClass().getName().equals(className)) {
                return v;
            }
        }
        return null;
    }

    private static Object contextValue(MavenProject project, String name) {
        if (!name.startsWith("@")) {
            return project.getContextValue(name);
        }
        int i = name.indexOf('#');
        Object owner = contextOwner(project, name.substring(1, i));
        return owner == null ? null : context(owner).get(name.substring(i + 1));
    }

    private static void setContextValue(MavenProject project, String name, String value) {
        if (!name.startsWith("@")) {
            project.setContextValue(name, value);
            return;
        }
        int i = name.indexOf('#');
        Object owner = contextOwner(project, name.substring(1, i));
        if (owner == null) {
            throw new IllegalStateException("cannot replay context value " + name + ": no "
                    + name.substring(1, i) + " in the context of " + project.getId());
        }
        try {
            owner.getClass().getMethod("setContextValue", String.class, Object.class)
                    .invoke(owner, name.substring(i + 1), value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot replay context value " + name, e);
        }
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
