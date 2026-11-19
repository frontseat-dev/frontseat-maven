package dev.frontseat.mojoio.extension;

import dev.frontseat.mojoio.extension.descriptor.DescriptorRegistry;
import dev.frontseat.mojoio.extension.descriptor.MojoCacheDescriptor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.DefaultLifecycles;
import org.apache.maven.lifecycle.Lifecycle;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.lifecycle.internal.LifecycleTask;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

/**
 * Gives directly-invoked goals their prerequisites, task-graph style. {@code mvn surefire:test} on a
 * fresh checkout would normally fail (nothing compiled); with this, the lifecycle up to the goal's
 * prerequisite phase runs first, and with a warm cache every one of those executions is a restore.
 *
 * <p>Only applies when every execution in the list came from the command line. Mixed invocations
 * like {@code mvn test surefire:test} are left alone.
 */
@Named
@Singleton
public class PrerequisiteExpander {

    private final LifecycleExecutionPlanCalculator planCalculator;
    private final DefaultLifecycles lifecycles;
    private final DescriptorRegistry registry;

    @Inject
    public PrerequisiteExpander(LifecycleExecutionPlanCalculator planCalculator, DefaultLifecycles lifecycles,
                                DescriptorRegistry registry) {
        this.planCalculator = planCalculator;
        this.lifecycles = lifecycles;
        this.registry = registry;
    }

    public List<MojoExecution> expand(List<MojoExecution> executions, MavenSession session, MavenProject project)
            throws LifecycleExecutionException {
        if (executions.isEmpty()
                || executions.stream().anyMatch(m -> m.getSource() != MojoExecution.Source.CLI)) {
            return executions;
        }
        String phase = deepestPrerequisite(executions, session);
        if (phase == null) {
            return executions;
        }
        List<MojoExecution> plan;
        try {
            plan = planCalculator
                    .calculateExecutionPlan(session, project, List.<Object>of(new LifecycleTask(phase)))
                    .getMojoExecutions();
        } catch (Exception e) {
            throw new LifecycleExecutionException("mojo-io: cannot compute prerequisites up to phase " + phase,
                    e);
        }
        Set<String> requested = new HashSet<>();
        for (MojoExecution m : executions) {
            requested.add(m.getGroupId() + ":" + m.getArtifactId() + ":" + m.getGoal());
        }
        List<MojoExecution> expanded = new ArrayList<>();
        for (MojoExecution m : plan) {
            if (!requested.contains(m.getGroupId() + ":" + m.getArtifactId() + ":" + m.getGoal())) {
                expanded.add(m);
            }
        }
        expanded.addAll(executions);
        return expanded;
    }

    private String deepestPrerequisite(List<MojoExecution> executions, MavenSession session) {
        Lifecycle defaultLifecycle = lifecycles.get("compile");
        List<String> phases = defaultLifecycle == null ? List.of() : defaultLifecycle.getPhases();
        String deepest = null;
        for (MojoExecution m : executions) {
            String phase = registry.find(m, session).map(MojoCacheDescriptor::prerequisitePhase).orElse(null);
            if (phase != null && (deepest == null || phases.indexOf(phase) > phases.indexOf(deepest))) {
                deepest = phase;
            }
        }
        return deepest;
    }
}
