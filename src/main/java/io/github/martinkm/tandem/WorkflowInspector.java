package io.github.martinkm.tandem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads what a store holds without running anything.
 *
 * <p>The recovery path on {@link WorkflowEngine} needs the workflow definition,
 * because continuing a run needs it. Looking is a smaller question than
 * continuing, and this answers it from the store alone: which runs are there,
 * what state each is in, and how far each got. Nothing here executes a step,
 * resumes a run or writes to the store.
 *
 * <h2>What the definition would have told it</h2>
 *
 * <p>Working from the store alone costs one thing. The engine knows which steps
 * carry a {@link Codec} and which were declared safe to repeat, and it uses that
 * to tell a run that died inside a recorded step, which is {@link RunState#IN_DOUBT},
 * from one that died inside a repeat-safe step, which is {@link RunState#RESUMABLE}.
 * The inspector cannot see codecs, so it reports both as {@code IN_DOUBT}: a step
 * that was entered and never came back, with no recorded output to settle it, is
 * a doubt as far as the log can say. The classification is otherwise the same
 * one the engine uses, through {@link RecoverableRun#decide}. Since the inspector
 * never resumes, the difference is in the label and never in what happens to the
 * run.
 *
 * <p>Outputs come back in the encoded form the store keeps, not decoded. Decoding
 * needs the step's codec, which lives in the definition. For a string codec the
 * two are the same text; for anything else this is the recorded string.
 */
public final class WorkflowInspector {

    private final WorkflowStore store;

    /**
     * @param store the store to read, which is never written to
     */
    public WorkflowInspector(WorkflowStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The ids of every run the store holds a log for, in the store's own order. */
    public List<String> runIds() {
        return store.listRuns();
    }

    /** Every run the store holds, each with its classified state. */
    public List<RunSummary> list() {
        List<RunSummary> summaries = new ArrayList<>();
        for (String runId : store.listRuns()) {
            RunLog log = new RunLog(store.eventsFor(runId));
            if (log.isEmpty()) {
                continue;
            }
            summaries.add(new RunSummary(runId, statusOf(runId, log)));
        }
        return List.copyOf(summaries);
    }

    /**
     * One run in full, or empty when the store holds no log for the id.
     *
     * @param runId the run to describe
     * @return the run's workflow name, state, steps and any doubt, or empty
     */
    public Optional<RunDescription> describe(String runId) {
        Objects.requireNonNull(runId, "runId");
        RunLog log = new RunLog(store.eventsFor(runId));
        if (log.isEmpty()) {
            return Optional.empty();
        }

        List<StepView> steps = new ArrayList<>();
        for (String stepName : log.stepsInOrder()) {
            steps.add(
                    new StepView(
                            stepName,
                            log.standingOf(stepName),
                            store.loadOutput(runId, stepName)));
        }

        String doubt = doubtStep(runId, log);
        RunState status = RecoverableRun.decide(log.ending(), doubt != null);
        String name = log.workflowName();

        return Optional.of(
                new RunDescription(
                        runId,
                        name.isEmpty() ? Optional.empty() : Optional.of(name),
                        status,
                        status == RunState.IN_DOUBT ? Optional.of(doubt) : Optional.empty(),
                        List.copyOf(steps)));
    }

    private RunState statusOf(String runId, RunLog log) {
        return RecoverableRun.decide(log.ending(), doubtStep(runId, log) != null);
    }

    /**
     * The first step the log cannot settle, or null if none.
     *
     * <p>The same shape as the engine's own scan, minus the one thing the store
     * cannot show, which is whether a step carried a codec. A step that succeeded
     * is settled by its {@code STEP_SUCCEEDED}, so a {@link StepStanding#DONE} is
     * never a doubt here. A step that was entered and never came back is a doubt
     * unless the store holds its output, and a compensation that threw is always
     * a doubt.
     */
    private String doubtStep(String runId, RunLog log) {
        if (log.ending() == EventType.RUN_SUCCEEDED) {
            return null;
        }
        for (String stepName : log.stepsInOrder()) {
            StepStanding standing = log.standingOf(stepName);
            if (standing == StepStanding.NOT_DONE || standing == StepStanding.DONE) {
                continue;
            }
            if (standing == StepStanding.ENTERED
                    && store.loadOutput(runId, stepName).isPresent()) {
                continue;
            }
            return stepName;
        }
        return null;
    }

    /**
     * A run's id and the state it was read to be in.
     *
     * @param runId  the run
     * @param status its classified state
     */
    public record RunSummary(String runId, RunState status) {}

    /**
     * One step, as the log records it.
     *
     * @param name           the step name
     * @param outcome        what the log says about the step's effect
     * @param recordedOutput the encoded output the store holds, present only when
     *                       the step recorded one
     */
    public record StepView(String name, StepStanding outcome, Optional<String> recordedOutput) {}

    /**
     * One run in full.
     *
     * @param runId        the run
     * @param workflowName the workflow the log names, empty for a run with no
     *                     named events
     * @param status       its classified state
     * @param stepInDoubt  the step holding the run up, present only when the
     *                     status is {@link RunState#IN_DOUBT}
     * @param steps        the run's steps, in the order they first ran
     */
    public record RunDescription(
            String runId,
            Optional<String> workflowName,
            RunState status,
            Optional<String> stepInDoubt,
            List<StepView> steps) {}
}
