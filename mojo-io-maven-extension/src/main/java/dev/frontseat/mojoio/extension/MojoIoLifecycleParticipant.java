package dev.frontseat.mojoio.extension;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;

/** Writes the task graph once the (dry-run) session is over. */
@Named("mojo-io")
@Singleton
public class MojoIoLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    private final TaskGraphCollector graph;

    @Inject
    public MojoIoLifecycleParticipant(TaskGraphCollector graph) {
        this.graph = graph;
    }

    @Override
    public void afterSessionEnd(MavenSession session) {
        MojoIoConfig config = MojoIoConfig.from(session);
        if (config.enabled() && config.graphOutput() != null) {
            graph.write(session, config.graphOutput());
        }
    }
}
