package io.github.martinkm.tandem;

import java.time.Duration;

/**
 * The failure of a step that did not finish inside its timeout.
 *
 * <p>A retry policy answers "what if this fails". It has nothing to say about a
 * step that neither succeeds nor fails: an HTTP call with no read timeout, a
 * lock that is never granted, a query behind a table lock. Without a bound on
 * duration those hang the run for as long as the process lives, and no number of
 * configured attempts is ever reached.
 *
 * <p><strong>A timed-out step may still be running.</strong> Java cannot stop a
 * thread; interrupting one only unblocks calls that watch for it, and a step
 * sitting in a socket read that ignores interruption keeps going, and may still
 * commit its side effect after the engine has given up on it. That is the same
 * uncertainty {@link StepInDoubtException} describes, arrived at from the other
 * direction, and it is why a timeout belongs on a step that is safe to run
 * again: one that is idempotent, or that has a {@link Compensation} to undo
 * whichever copy of it lands.
 */
public final class StepTimedOutException extends TandemException {

    private static final long serialVersionUID = 1L;

    private final String stepName;
    private final Duration timeout;

    StepTimedOutException(String stepName, Duration timeout) {
        super(
                "step \""
                        + stepName
                        + "\" did not finish within "
                        + timeout
                        + "; it may still be running, so treat its side effect as unconfirmed");
        this.stepName = stepName;
        this.timeout = timeout;
    }

    /** The step that ran out of time. */
    public String stepName() {
        return stepName;
    }

    /** The budget it exceeded. */
    public Duration timeout() {
        return timeout;
    }
}
