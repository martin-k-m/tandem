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
    /**
     * A compensation threw, so whether the step it was undoing is still undone
     * is not knowable. Its own type rather than a note on
     * {@link #STEP_COMPENSATED}, because a resume has to tell "undone" from
     * "nobody knows", and that is not a decision to take on a string.
     */
    STEP_COMPENSATION_FAILED,
    RUN_SUCCEEDED,
    RUN_FAILED
}
