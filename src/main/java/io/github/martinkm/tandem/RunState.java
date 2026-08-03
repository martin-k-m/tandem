package io.github.martinkm.tandem;

/**
 * Where a run got to, as far as its log can say.
 *
 * <p>This is a reading of what was recorded, not a report from a live process.
 * Nothing leases a run, so a run that is executing right now in another thread
 * or another machine looks exactly like one whose process died: both are
 * {@link #RESUMABLE}. Sweeping a store and resuming everything in that state is
 * therefore only safe while one process owns it.
 */
public enum RunState {

    /**
     * The run reached its last step and the log holds {@code RUN_SUCCEEDED}.
     * There is nothing to continue, and
     * {@link WorkflowEngine#resume(RecoverableRun)} refuses it rather than
     * repeating every step that has no codec.
     */
    COMPLETED,

    /**
     * The run ended in {@code RUN_FAILED}: a step exhausted its retries and the
     * compensations ran. Resuming is allowed and is a retry rather than a
     * continuation, since the steps that were undone will run again.
     */
    FAILED,

    /**
     * A recorded step was entered and never accounted for, or a compensation for
     * one threw. Resuming fails with {@link StepInDoubtException} until
     * {@link WorkflowEngine#confirmCompleted} or
     * {@link WorkflowEngine#confirmNotCompleted} settles the step named by
     * {@link RecoverableRun#stepInDoubt()}.
     */
    IN_DOUBT,

    /**
     * The latest attempt wrote no ending at all, which is what a process dying
     * mid-run leaves behind. This is the state recovery exists for: resuming
     * replays what was recorded and carries on from there.
     */
    RESUMABLE
}
