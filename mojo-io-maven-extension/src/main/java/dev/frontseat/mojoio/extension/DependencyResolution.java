package dev.frontseat.mojoio.extension;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.CumulativeScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.lifecycle.internal.LifecycleDependencyResolver;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;

/**
 * Resolves the project dependencies a mojo requires <em>before</em> it runs. Maven normally does this
 * inside the mojo runner, but the cache key needs the classpath, and on a hit the runner is never
 * called. Mirrors the scope mapping of Maven's {@code MojoExecutor}.
 */
@Named
@Singleton
public class DependencyResolution {

    private final LifecycleDependencyResolver resolver;

    @Inject
    public DependencyResolution(LifecycleDependencyResolver resolver) {
        this.resolver = resolver;
    }

    public void resolveFor(MojoExecution execution, MavenProject project, MavenSession session)
            throws LifecycleExecutionException {
        MojoDescriptor descriptor = execution.getMojoDescriptor();
        Collection<String> toResolve = toScopes(descriptor.getDependencyResolutionRequired());
        Collection<String> toCollect = toScopes(descriptor.getDependencyCollectionRequired());
        if (toResolve.isEmpty() && toCollect.isEmpty()) {
            return;
        }
        resolver.resolveProjectDependencies(project, toCollect, toResolve, session, descriptor.isAggregator(),
                Collections.<Artifact>emptySet());
        // Like MojoExecutor: without a filter, MavenProject#getArtifacts() (and therefore every
        // *ClasspathElements expression) stays empty even though the artifacts were resolved.
        project.setArtifactFilter(new CumulativeScopeArtifactFilter(toResolve));
    }

    /**
     * The project's test classpath, resolved here and now, leaving the project as it was.
     *
     * <p>{@code MavenProject#getTestClasspathElements()} alone is not an input: it reflects the
     * dependencies resolved for whichever mojo ran last (none, for a mojo that asks for no resolution
     * after others that ran; the test scope, after others that were restored). The project's resolved
     * artifacts and filter are put back afterwards, so the mojo that runs next sees what it would have
     * seen without this call.
     */
    public ExecutionContext.Classpath testClasspath(MavenProject project, MavenSession session)
            throws LifecycleExecutionException {
        Collection<String> test = toScopes(Artifact.SCOPE_TEST);
        Saved saved = save(project);
        try {
            resolver.resolveProjectDependencies(project, test, test, session, false, Collections.<Artifact>emptySet());
            project.setArtifactFilter(new CumulativeScopeArtifactFilter(test));
            List<java.nio.file.Path> paths = new java.util.ArrayList<>();
            for (String element : project.getTestClasspathElements()) {
                paths.add(java.nio.file.Path.of(element));
            }
            return byVersion(project, session, paths);
        } catch (org.apache.maven.artifact.DependencyResolutionRequiredException e) {
            throw new LifecycleExecutionException("cannot compute the test classpath: " + e.getMessage(), e);
        } finally {
            saved.restore();
        }
    }

    /**
     * Eclipse Tycho resolves bundles from p2 repositories and adds them to the project as system-scoped
     * dependencies in groups {@code p2.*}, with fully qualified versions
     * ({@code org.eclipse.swt:3.130.0.v20250528-1759}). Their files are materialised lazily (until then a
     * placeholder in the temporary directory), so the same build can see a placeholder once and the real
     * file the next time. Such a bundle is identified by its version, which p2 never reuses for other
     * content; a bundle built in this reactor is hashed by content like any project output.
     */
    public static ExecutionContext.Classpath byVersionOf(MavenProject project, MavenSession session,
                                                         List<java.nio.file.Path> elements) {
        return byVersion(project, session, elements);
    }

    static ExecutionContext.Classpath byVersion(MavenProject project, MavenSession session,
                                                List<java.nio.file.Path> elements) {
        List<java.nio.file.Path> reactor = new java.util.ArrayList<>();
        for (MavenProject p : session.getAllProjects()) {
            if (p.getBasedir() != null) {
                reactor.add(p.getBasedir().toPath().toAbsolutePath().normalize());
            }
        }
        java.util.Set<java.nio.file.Path> versionedFiles = new java.util.HashSet<>();
        java.util.TreeSet<String> versioned = new java.util.TreeSet<>();
        for (Artifact a : project.getArtifacts()) {
            if (a.getGroupId() == null || !a.getGroupId().startsWith("p2.")) {
                continue;
            }
            java.nio.file.Path file = a.getFile() == null ? null : a.getFile().toPath().toAbsolutePath().normalize();
            java.nio.file.Path f = file;
            if (f != null && reactor.stream().anyMatch(f::startsWith)) {
                continue; // built in this reactor: hashed by content
            }
            versioned.add(a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getType() + ":" + a.getVersion());
            if (file != null) {
                versionedFiles.add(file);
            }
        }
        List<java.nio.file.Path> paths = new java.util.ArrayList<>();
        for (java.nio.file.Path e : elements) {
            if (!versionedFiles.contains(e.toAbsolutePath().normalize())) {
                paths.add(e);
            }
        }
        return new ExecutionContext.Classpath(paths, List.copyOf(versioned));
    }

    /**
     * The project's dependency state (resolved artifacts, filter and the views derived from them), to
     * put back after resolving for a cache key. Maven resolves a project's dependencies once, for all
     * the scopes its build plan needs, and afterwards only switches the filter per mojo. A narrower
     * resolution left in place here would be what every later mojo gets, declared or not.
     */
    public static final class Saved {
        private static final List<String> FIELDS = List.of("resolvedArtifacts", "artifactFilter", "artifacts", "artifactMap");
        private final MavenProject project;
        private final java.util.Map<java.lang.reflect.Field, Object> values = new java.util.LinkedHashMap<>();
        private boolean complete = true;

        private Saved(MavenProject project) {
            this.project = project;
            for (String name : FIELDS) {
                try {
                    java.lang.reflect.Field f = MavenProject.class.getDeclaredField(name);
                    f.setAccessible(true);
                    values.put(f, f.get(project));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    complete = false; // another MavenProject layout: leave the project as resolution leaves it
                }
            }
        }

        public void restore() {
            if (!complete) {
                return;
            }
            for (java.util.Map.Entry<java.lang.reflect.Field, Object> e : values.entrySet()) {
                try {
                    e.getKey().set(project, e.getValue());
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // nothing better to do
                }
            }
        }
    }

    public static Saved save(MavenProject project) {
        return new Saved(project);
    }

    /**
     * The project's external (non-reactor) dependencies with their scopes, collected without
     * downloading or resolving any file, as {@code groupId:artifactId:type[:classifier]:version:scope}.
     * Used by the task graph so a build system can hash the classpath without resolving it.
     */
    public List<String> externalDependencies(MavenProject project, MavenSession session) {
        Collection<String> test = toScopes(Artifact.SCOPE_TEST);
        try {
            resolver.resolveProjectDependencies(project, test, Collections.emptyList(), session, false,
                    Collections.<Artifact>emptySet());
        } catch (LifecycleExecutionException | RuntimeException e) {
            return List.of("<unresolved: " + e.getMessage() + ">");
        }
        project.setArtifactFilter(new CumulativeScopeArtifactFilter(test));
        java.util.Set<String> reactor = new java.util.HashSet<>();
        for (MavenProject p : session.getProjects()) {
            reactor.add(p.getGroupId() + ":" + p.getArtifactId());
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (Artifact a : project.getArtifacts()) {
            if (reactor.contains(a.getGroupId() + ":" + a.getArtifactId())) {
                continue;
            }
            result.add(a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getType()
                    + (a.getClassifier() == null || a.getClassifier().isEmpty() ? "" : ":" + a.getClassifier())
                    + ":" + a.getVersion() + ":" + a.getScope());
        }
        java.util.Collections.sort(result);
        return result;
    }

    static Collection<String> toScopes(String scope) {
        if (scope == null || scope.isEmpty()) {
            return Collections.emptyList();
        }
        return switch (scope) {
            case Artifact.SCOPE_COMPILE -> List.of(Artifact.SCOPE_COMPILE, Artifact.SCOPE_SYSTEM, Artifact.SCOPE_PROVIDED);
            case Artifact.SCOPE_RUNTIME -> List.of(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME);
            case Artifact.SCOPE_COMPILE_PLUS_RUNTIME -> List.of(Artifact.SCOPE_COMPILE, Artifact.SCOPE_SYSTEM,
                    Artifact.SCOPE_PROVIDED, Artifact.SCOPE_RUNTIME);
            case Artifact.SCOPE_RUNTIME_PLUS_SYSTEM -> List.of(Artifact.SCOPE_COMPILE, Artifact.SCOPE_SYSTEM,
                    Artifact.SCOPE_RUNTIME);
            case Artifact.SCOPE_TEST -> List.of(Artifact.SCOPE_COMPILE, Artifact.SCOPE_SYSTEM,
                    Artifact.SCOPE_PROVIDED, Artifact.SCOPE_RUNTIME, Artifact.SCOPE_TEST);
            default -> Collections.emptyList();
        };
    }
}
