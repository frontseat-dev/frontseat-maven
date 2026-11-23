package dev.frontseat.mojoio.extension;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * At the end of the session: writes the task graph (dry run), and checks that a build restricted with
 * {@code mojoio.only} really built the projects it names.
 */
@Named("mojo-io")
@Singleton
public class MojoIoLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    /** In the project's build directory when a selected project was not built (see below). */
    static final String NOT_BUILT_MARKER = "mojo-io/not-built";
    static final String NOT_BUILT_MESSAGE = "[mojo-io] NOT BUILT";

    private final TaskGraphCollector graph;
    private final Audit audit;
    private final MojoIoExecutionStrategy strategy;

    @Inject
    public MojoIoLifecycleParticipant(TaskGraphCollector graph, MojoIoExecutionStrategy strategy, Audit audit) {
        this.graph = graph;
        this.audit = audit;
        this.strategy = strategy;
    }

    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
        StateFiles.reset();
        String auditFailure = audit.finish(session);
        if (auditFailure != null) {
            throw new MavenExecutionException("[mojo-io] AUDIT " + auditFailure, (java.io.File) null);
        }
        MojoIoConfig config = MojoIoConfig.from(session);
        if (!config.enabled()) {
            return;
        }
        if (config.graphOutput() != null) {
            graph.write(session, config.graphOutput(), config.granularity());
            return;
        }
        // A build system runs one task per invocation and caches what it leaves behind. If the build
        // ended without failing and without reaching a selected project, an empty result must not look
        // like success. Seen under mvnd: its builder has one cancel flag per daemon, set when a client
        // connection drops; a build that starts while the previous connection is being torn down can
        // see it, skip every project and report success.
        if (config.only().isEmpty() || session.getResult() == null || session.getResult().hasExceptions()
                || session.getProjects() == null) {
            return;
        }
        MavenProject selected = null;
        for (MavenProject project : session.getProjects()) {
            if (config.runs(project)) {
                selected = project;
            }
        }
        if (selected == null) {
            throw new MavenExecutionException("[mojo-io] mojoio.only=" + config.only() + " matches no project of this "
                    + "build: nothing was run.", session.getTopLevelProject() == null ? null
                    : session.getTopLevelProject().getFile());
        }
        // Not called at all: the build stopped before any project. (Called, but not for the selected
        // project, is legitimate: an aggregator goal runs once, for the top-level project.)
        if (!strategy.calledIn(session) && !strategy.started(session, selected)
                && session.getResult().getBuildSummary(selected) == null) {
            // A marker for the caller: this failure, unlike a failing mojo, is worth one retry.
            try {
                java.nio.file.Path marker = java.nio.file.Path.of(selected.getBuild().getDirectory())
                        .resolve(NOT_BUILT_MARKER);
                java.nio.file.Files.createDirectories(marker.getParent());
                java.nio.file.Files.writeString(marker, "");
            } catch (java.io.IOException | RuntimeException ignored) {
                // the failure below is what matters
            }
            throw new MavenExecutionException(NOT_BUILT_MESSAGE + ": " + selected.getGroupId() + ":"
                    + selected.getArtifactId() + " is selected by mojoio.only, but the build ended without "
                    + "running anything. Run the task again.", selected.getFile());
        }
    }
}
