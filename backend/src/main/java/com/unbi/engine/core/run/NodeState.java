package com.unbi.engine.core.run;

/** Lifecycle of a single node within one run. Drives the badge and glow in the editor. */
public enum NodeState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    /** An upstream node failed, so this one was never attempted. */
    SKIPPED,
    CANCELLED
}
