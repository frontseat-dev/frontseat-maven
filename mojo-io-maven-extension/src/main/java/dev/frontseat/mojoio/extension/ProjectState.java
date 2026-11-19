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

/**
 * The parts of {@link MavenProject} that mojos commonly mutate and that later mojos read. A snapshot
 * is taken before a cache-miss execution and diffed afterwards; the diff is stored with the outputs
 * and replayed on a hit.
 *
 * <p>Changes this class cannot replay (removed source roots, changed resources, attached artifacts)
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

    private final List<String> compileSourceRoots;
    private final List<String> testCompileSourceRoots;
    private final List<String> resources;
    private final List<String> testResources;
    private final Map<String, String> properties;
    private final String artifactFile;
    private final List<String> attachedArtifacts;

    private ProjectState(MavenProject project) {
        compileSourceRoots = new ArrayList<>(project.getCompileSourceRoots());
        testCompileSourceRoots = new ArrayList<>(project.getTestCompileSourceRoots());
        resources = describe(project.getResources());
        testResources = describe(project.getTestResources());
        properties = new TreeMap<>();
        project.getProperties().forEach((k, v) -> properties.put(String.valueOf(k), String.valueOf(v)));
        File file = project.getArtifact() == null ? null : project.getArtifact().getFile();
        artifactFile = file == null ? null : file.getAbsolutePath();
        attachedArtifacts = new ArrayList<>();
        for (Artifact a : project.getAttachedArtifacts()) {
            attachedArtifacts.add(a.getId() + "@" + a.getFile());
        }
    }

    static ProjectState capture(MavenProject project) {
        return new ProjectState(project);
    }

    /** Normalized records that turn {@code this} into {@code after}. */
    List<StateRecord> diff(ProjectState after, PathNormalizer n) throws NotCacheableException {
        List<StateRecord> records = new ArrayList<>();
        diffRoots(COMPILE_SOURCE_ROOT, compileSourceRoots, after.compileSourceRoots, records, n);
        diffRoots(TEST_COMPILE_SOURCE_ROOT, testCompileSourceRoots, after.testCompileSourceRoots, records, n);
        if (!resources.equals(after.resources) || !testResources.equals(after.testResources)) {
            throw new NotCacheableException("execution changed project resources");
        }
        if (!attachedArtifacts.equals(after.attachedArtifacts)) {
            throw new NotCacheableException("execution attached artifacts");
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
    static void apply(List<StateRecord> records, MavenProject project, PathNormalizer n) {
        for (StateRecord r : records) {
            String value = n.denormalize(r.value());
            switch (r.kind()) {
                case COMPILE_SOURCE_ROOT -> project.addCompileSourceRoot(value);
                case TEST_COMPILE_SOURCE_ROOT -> project.addTestCompileSourceRoot(value);
                case PROPERTY -> project.getProperties().setProperty(r.name(), value);
                case PROPERTY_REMOVED -> project.getProperties().remove(r.name());
                case ARTIFACT_FILE -> project.getArtifact().setFile(value == null ? null : new File(value));
                default -> throw new IllegalStateException("Unknown state record kind " + r.kind());
            }
        }
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
