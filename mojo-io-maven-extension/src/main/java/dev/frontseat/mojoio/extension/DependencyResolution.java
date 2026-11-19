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
