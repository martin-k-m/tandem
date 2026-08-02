package io.github.martinkm.tandem;

/** What happened, as recorded in the run log. */
public enum EventType {
    RUN_STARTED,
    RUN_RESUMED,
    STEP_STARTED,
    STEP_SUCCEEDED,
    /** A step's recorded output was reused instead of running it again. */
    STEP_REPLAYED,
    STEP_FAILED,
    STEP_RETRYING,
    /**
     * A resume found a recorded step that an earlier run started and never
     * finished recording, so the run stopped rather than guessing.
     */
    STEP_IN_DOUBT,
    STEP_COMPENSATED,
    RUN_SUCCEEDED,
    RUN_FAILED
}
