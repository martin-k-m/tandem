package io.github.martinkm.tandem;

/**
 * The failure of a resume that reached a recorded step whose outcome is not
 * knowable from the log.
 *
 * <p>An earlier run wrote {@link EventType#STEP_STARTED} to the store, then
 * stopped without writing either an outcome or the step's output. The side
 * effect may have happened or may not, and the log cannot tell you which.
 * Running the step again risks charging a card twice; treating it as done risks
 * never charging it at all. Tandem does neither, and fails the run with this
 * instead.
 *
 * <p>Resolving it means looking at the system the step talked to and then
 * telling the engine what you found, with
 * {@link WorkflowEngine#confirmCompleted} or
 * {@link WorkflowEngine#confirmNotCompleted}. Until then, resuming the run
 * fails here again.
 */
public final class StepInDoubtException extends TandemException {

    private static final long serialVersionUID = 1L;

    private final String runId;
    private final String stepName;

    StepInDoubtException(String runId, String stepName) {
        super(
                "step \""
                        + stepName
                        + "\" of run "
                        + runId
                        + " was started and never recorded, so whether it ran is unknown;"
                        + " confirm it with WorkflowEngine.confirmCompleted or"
                        + " confirmNotCompleted before resuming");
        this.runId = runId;
        this.stepName = stepName;
    }

    public String runId() {
        return runId;
    }

    /** The step that needs a decision before the run can go on. */
    public String stepName() {
        return stepName;
    }
}
