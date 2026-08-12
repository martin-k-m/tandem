package io.github.martinkm.tandem;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One run a store still holds, classified, and carrying what it takes to
 * continue it.
 *
 * <p>Continuing a run needs three things: its id, the definition it was running,
 * and the input it started with. A crash keeps the id and the log in the store
 * and takes the other two with the process. The definition comes back from your
 * own code, matched by the name in the log; the input comes back only if the
 * definition said how to record it, with
 * {@link WorkflowBuilder#input(Codec) input(Codec)}. This type is the three of
 * them together with {@link #state()}, which says whether continuing is the
 * right thing to do at all.
 *
 * @param <I> the workflow's input type
 * @param <O> the workflow's output type
 */
public final class RecoverableRun<I, O> {

    private final Workflow<I, O> workflow;
    private final String runId;
    private final RunState state;
    private final String stepInDoubt;
    private final String encodedInput;
    private final List<WorkflowEvent> events;

    private RecoverableRun(
            Workflow<I, O> workflow,
            String runId,
            RunState state,
            String stepInDoubt,
            String encodedInput,
            List<WorkflowEvent> events) {
        this.workflow = workflow;
        this.runId = runId;
        this.state = state;
        this.stepInDoubt = stepInDoubt;
        this.encodedInput = encodedInput;
        this.events = events;
    }

    /**
     * Reads one run's log the way a resume would read it.
     *
     * <p>A completed run is answered without touching the recorded outputs at
     * all: it succeeded, so no step is outstanding, and a scan over a store full
     * of finished runs should not pay to prove that.
     */
    static <I, O> RecoverableRun<I, O> classify(
            WorkflowStore store, Workflow<I, O> workflow, String runId, RunLog log) {

        boolean succeeded = log.ending() == EventType.RUN_SUCCEEDED;
        String doubt = succeeded ? null : firstStepInDoubt(store, workflow, runId, log);
        RunState state = decide(log.ending(), doubt != null);

        // Only worth a read when there is a codec that could decode it.
        String encodedInput =
                workflow.inputCodec() == null ? null : store.loadInput(runId).orElse(null);

        return new RecoverableRun<>(
                workflow, runId, state, doubt, encodedInput, log.events());
    }

    /**
     * Turns how a run's latest attempt ended, plus whether a step is holding it
     * in doubt, into one of the four states.
     *
     * <p>The one place the mapping lives, so a recovery scan and a read-only
     * inspection cannot drift into two different ideas of what a log means. What
     * counts as a doubt is the caller's to decide, since that is the part that
     * needs the workflow definition; {@link WorkflowInspector} answers it from
     * the log alone and this rule stays the same.
     *
     * @param ending  how the latest attempt ended, or null if it has not
     * @param inDoubt whether a step is holding the run in doubt
     * @return the classified state
     */
    static RunState decide(EventType ending, boolean inDoubt) {
        if (ending == EventType.RUN_SUCCEEDED) {
            return RunState.COMPLETED;
        }
        if (inDoubt) {
            return RunState.IN_DOUBT;
        }
        if (ending == EventType.RUN_FAILED) {
            return RunState.FAILED;
        }
        return RunState.RESUMABLE;
    }

    /**
     * The first step a resume would stop at, or null if it would not stop.
     *
     * <p>The same three questions the engine asks, in the same order, so a
     * classification cannot promise a resume that then refuses. Steps that will
     * simply run again are stepped over rather than ending the scan, because a
     * step further along can still be the one holding the run up.
     */
    private static String firstStepInDoubt(
            WorkflowStore store, Workflow<?, ?> workflow, String runId, RunLog log) {

        for (StepDefinition definition : workflow.steps()) {
            if (!definition.replayable()) {
                // A step without a codec was declared safe to repeat, so nothing
                // about it is ever in doubt.
                continue;
            }
            StepStanding standing = log.standingOf(definition.name);
            if (standing == StepStanding.NOT_DONE) {
                continue;
            }
            if (standing.settledByARecordedOutput()
                    && store.loadOutput(runId, definition.name).isPresent()) {
                continue;
            }
            return definition.name;
        }
        return null;
    }

    public String runId() {
        return runId;
    }

    /** The definition this run was matched against, and the one a resume uses. */
    public Workflow<I, O> workflow() {
        return workflow;
    }

    public RunState state() {
        return state;
    }

    /** The step holding the run up, present exactly when the state is {@link RunState#IN_DOUBT}. */
    public Optional<String> stepInDoubt() {
        return Optional.ofNullable(stepInDoubt);
    }

    /**
     * The input the run started with, empty unless the workflow declares an
     * input codec and the run got as far as recording one.
     *
     * <p>Decoded on each call rather than when the run was classified, so a
     * codec that can no longer read what an old run recorded fails at the run it
     * cannot read instead of failing a whole sweep.
     */
    public Optional<I> input() {
        Codec<I> codec = workflow.inputCodec();
        if (encodedInput == null || codec == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.decode(encodedInput));
        } catch (RuntimeException thrown) {
            throw new TandemException(
                    "could not decode the recorded input of run " + runId, thrown);
        }
    }

    /** When the run first started, which is the first thing anything wrote for it. */
    public Instant startedAt() {
        return events.get(0).at();
    }

    /** When anything last happened, which is how long a stuck run has been stuck. */
    public Instant lastEventAt() {
        return events.get(events.size() - 1).at();
    }

    /** Everything recorded for this run, oldest first, across every attempt. */
    public List<WorkflowEvent> events() {
        return events;
    }

    @Override
    public String toString() {
        return "RecoverableRun["
                + runId
                + ", "
                + state
                + (stepInDoubt == null ? "" : " at " + stepInDoubt)
                + "]";
    }
}
