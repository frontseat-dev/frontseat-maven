package dev.frontseat.mojoio.spec;

/**
 * Constants of the task-graph format. The schema lives at {@link #SCHEMA_RESOURCE}; see
 * docs/task-graph.md for the prose specification.
 */
public final class TaskGraphFormat {

    public static final String FORMAT = "mojo-io-task-graph";
    public static final int VERSION = 1;
    public static final String SCHEMA_RESOURCE = "/dev/frontseat/mojoio/spec/task-graph-1.schema.json";

    /** Placeholders used for paths in the graph; consumers substitute their own values. */
    public static final String PROJECT_ROOT = "{projectRoot}";
    public static final String WORKSPACE_ROOT = "{workspaceRoot}";
    public static final String LOCAL_REPOSITORY = "{localRepository}";

    private TaskGraphFormat() {}
}
