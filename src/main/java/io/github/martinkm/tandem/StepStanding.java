package io.github.martinkm.tandem;

/**
 * What a run's log says about one step's effect on the world.
 *
 * <p>Not "did the step succeed", which is a fact about one attempt. The question
 * a resume actually has is whether the thing the step did is still in place, and
 * that survives a retry, changes when a compensation undoes it, and stops being
 * knowable when the process dies at the wrong moment.
 */
public enum StepStanding {

    /**
     * Nothing the log holds says the effect is in place: the step never ran, its
     * last attempt failed, or a compensation undid it. Run it.
     */
    NOT_DONE,

    /** The effect is in place. Replay the recorded output rather than repeating it. */
    DONE,

    /**
     * An attempt was entered and never came back. The side effect may or may not
     * have happened, and only a recorded output can say: it is written after the
     * step returns, so its presence means the step got past its side effect.
     */
    ENTERED,

    /**
     * A compensation for the step threw. Whether the effect was undone is
     * unknown, and a recorded output cannot settle it, because the output was
     * written long before the compensation ran.
     */
    UNDO_IN_DOUBT;

    /**
     * Whether a recorded output resolves this standing.
     *
     * <p>Both the engine's resume and a recovery classification ask exactly this,
     * so that a classification cannot promise something the resume then refuses.
     */
    boolean settledByARecordedOutput() {
        return this == DONE || this == ENTERED;
    }
}
