package io.github.martinkm.tandem;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs workflows.
 *
 * <p>One engine is safe to share: it holds no per-run state, so many threads can
 * call {@link #run} at once. A run is executed on the calling thread, which
 * keeps stack traces honest and lets the caller decide the concurrency model.
 *
 * <h2>Resuming</h2>
 *
 * <p>Passing a run id that already has history resumes that run. Steps with a
 * {@link Codec} whose output was recorded are not executed again; their result
 * is decoded from the store. Steps without one are executed again, because
 * Tandem cannot know whether repeating them is safe. That distinction is the
 * whole durability model, and it is deliberately explicit rather than inferred.
 *
 * <h2>Steps left in doubt</h2>
 *
 * <p>{@link EventType#STEP_STARTED} is written to the store before the step
 * runs, so it is an intent record: the log says a step was about to happen even
 * if the process dies inside it. A resume that finds a recorded step with a
 * started event, no outcome event and no saved output cannot tell whether the
 * side effect happened. It stops there with a {@link StepInDoubtException}
 * rather than repeating the step, and waits for {@link #confirmCompleted} or
 * {@link #confirmNotCompleted}.
 *
 * <p>This narrows the window rather than closing it. Recording an intent and
 * doing the work are two writes to two systems, and without a transaction
 * spanning both there is no instant at which they happen together. What Tandem
 * guarantees is that the ambiguity is detected and surfaced instead of being
 * resolved by silently running the step a second time.
 *
 * <h2>Finding runs after a crash</h2>
 *
 * <p>Resuming needs a run id, a definition and the input the run started with,
 * and a crash keeps only the first of those. {@link #recoverable(Workflow)} asks
 * the store what it is holding, matches each run against the definition you
 * pass, and classifies it, so a restart can find what it left behind rather than
 * having to already know. {@link #resume(RecoverableRun)} continues one from
 * there.
 */
public final class WorkflowEngine {

    private final WorkflowStore store;
    private final Sleeper sleeper;
    private final Random random;
    private final List<WorkflowListener> listeners = new CopyOnWriteArrayList<>();

    /** An engine backed by an {@link InMemoryStore}. */
    public WorkflowEngine() {
        this(new InMemoryStore());
    }

    public WorkflowEngine(WorkflowStore store) {
        this(store, Sleeper.real(), new Random());
    }

    public WorkflowEngine(WorkflowStore store, Sleeper sleeper, Random random) {
        this.store = Objects.requireNonNull(store, "store");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.random = Objects.requireNonNull(random, "random");
    }

    public WorkflowEngine listener(WorkflowListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public WorkflowStore store() {
        return store;
    }

    /** Run with a fresh run id. */
    public <I, O> RunResult<O> run(Workflow<I, O> workflow, I input) {
        return run(workflow, input, UUID.randomUUID().toString());
    }

    /** Run, or resume if {@code runId} already has history. */
    public <I, O> RunResult<O> run(Workflow<I, O> workflow, I input, String runId) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(runId, "runId");

        List<WorkflowEvent> collected = new ArrayList<>();
        // Read once and keep it. The events this run is about to write must not
        // count as history, or a step would look started by an earlier run the
        // moment it starts in this one.
        RunLog history = new RunLog(store.eventsFor(runId));
        boolean resuming = !history.isEmpty();

        emit(
                collected,
                event(runId, workflow, "", resuming ? EventType.RUN_RESUMED : EventType.RUN_STARTED, 0, ""));

        if (!resuming) {
            recordInput(workflow, input, runId);
        }

        Object current = input;
        List<Completed> completed = new ArrayList<>();

        for (StepDefinition definition : workflow.steps()) {
            // Only steps the caller declared replayable are read out of the log
            // at all: a step without a codec was declared safe to repeat.
            if (resuming && definition.replayable()) {
                StepStanding standing = history.standingOf(definition.name);
                if (standing.settledByARecordedOutput()) {
                    Optional<String> recorded = store.loadOutput(runId, definition.name);
                    if (recorded.isPresent()) {
                        current = definition.codec.decode(recorded.get());
                        emit(
                                collected,
                                event(runId, workflow, definition.name, EventType.STEP_REPLAYED, 0, ""));
                        completed.add(new Completed(definition, current));
                        continue;
                    }
                }
                // Anything other than NOT_DONE that a recorded output did not
                // resolve is a question the log cannot answer: a step entered
                // and never accounted for, a compensation that threw, or an
                // output the store no longer has. Repeating the step would
                // answer it by guessing.
                if (standing != StepStanding.NOT_DONE) {
                    return refuse(collected, workflow, runId, definition.name);
                }
            }

            Attempted attempted = attempt(collected, workflow, definition, current, runId, resuming);
            if (!attempted.succeeded) {
                compensate(collected, workflow, runId, completed);
                emit(
                        collected,
                        event(
                                runId,
                                workflow,
                                definition.name,
                                EventType.RUN_FAILED,
                                0,
                                String.valueOf(attempted.failure)));
                return RunResult.failed(runId, attempted.failure, collected);
            }

            current = attempted.output;
            completed.add(new Completed(definition, current));
        }

        emit(collected, event(runId, workflow, "", EventType.RUN_SUCCEEDED, 0, ""));

        @SuppressWarnings("unchecked")
        O output = (O) current;
        return RunResult.succeeded(runId, output, collected);
    }

    /**
     * Every run of {@code workflow} the store still holds, classified.
     *
     * <p>This is the entry point for a restart: the store knows which runs
     * exist, your code knows the definition, and matching the two by name gives
     * back runs that can be inspected and continued. Runs belonging to other
     * workflows in the same store are left out, since nothing could be done with
     * them here anyway.
     *
     * <p>Every run's log is read, so the cost is proportional to what the store
     * holds rather than to what needs recovering. Prune or archive finished runs
     * if that becomes a problem; Tandem does not delete anything on your behalf.
     *
     * <p>A run another process is executing at this moment is indistinguishable
     * from one whose process died, because nothing takes a lease on a run. See
     * {@link RunState}.
     *
     * @param <I> the workflow's input type
     * @param <O> the workflow's output type
     */
    public <I, O> List<RecoverableRun<I, O>> recoverable(Workflow<I, O> workflow) {
        Objects.requireNonNull(workflow, "workflow");

        List<RecoverableRun<I, O>> found = new ArrayList<>();
        for (String runId : store.listRuns()) {
            RunLog log = new RunLog(store.eventsFor(runId));
            if (log.isEmpty() || !workflow.name().equals(log.workflowName())) {
                continue;
            }
            found.add(RecoverableRun.classify(store, workflow, runId, log));
        }
        return List.copyOf(found);
    }

    /**
     * One run, when you already know its id, or empty if the store holds no
     * history for it under this workflow.
     *
     * @param <I> the workflow's input type
     * @param <O> the workflow's output type
     */
    public <I, O> Optional<RecoverableRun<I, O>> recoverable(
            Workflow<I, O> workflow, String runId) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(runId, "runId");

        RunLog log = new RunLog(store.eventsFor(runId));
        if (log.isEmpty() || !workflow.name().equals(log.workflowName())) {
            return Optional.empty();
        }
        return Optional.of(RecoverableRun.classify(store, workflow, runId, log));
    }

    /**
     * Continue a run from its classification, with the input it started with.
     *
     * <p>Equivalent to calling {@link #run(Workflow, Object, String)} with the
     * three things the run needs, which is the point: after a restart you have
     * none of them to hand.
     *
     * <p>A run {@link RunState#IN_DOUBT} is not refused here. It runs, and fails
     * the way it would have failed anyway, with a {@link StepInDoubtException}
     * naming the step to settle. A run that already succeeded is refused,
     * because continuing it would repeat every step that has no codec.
     *
     * @param <I> the workflow's input type
     * @param <O> the workflow's output type
     * @throws TandemException if the run has already completed, or if no input
     *                         was recorded for it
     */
    public <I, O> RunResult<O> resume(RecoverableRun<I, O> run) {
        Objects.requireNonNull(run, "run");
        refuseCompleted(run);

        I input =
                run.input()
                        .orElseThrow(
                                () ->
                                        new TandemException(
                                                "run "
                                                        + run.runId()
                                                        + " recorded no input, so it cannot be"
                                                        + " resumed on its own; declare one with"
                                                        + " WorkflowBuilder.input(Codec) so later"
                                                        + " runs record theirs, and pass this"
                                                        + " run's input to resume(run, input)"));
        return run(run.workflow(), input, run.runId());
    }

    /**
     * Continue a run with an input you supply, for a workflow that does not
     * record one or a run that stopped before it could.
     *
     * <p>The input is recorded as part of resuming, when the definition says how,
     * so the next crash does not have to ask again.
     *
     * @param <I> the workflow's input type
     * @param <O> the workflow's output type
     * @throws TandemException if the run has already completed
     */
    public <I, O> RunResult<O> resume(RecoverableRun<I, O> run, I input) {
        Objects.requireNonNull(run, "run");
        refuseCompleted(run);

        recordInput(run.workflow(), input, run.runId());
        return run(run.workflow(), input, run.runId());
    }

    private static void refuseCompleted(RecoverableRun<?, ?> run) {
        if (run.state() == RunState.COMPLETED) {
            throw new TandemException(
                    "run "
                            + run.runId()
                            + " already succeeded, so resuming it would run every step without a"
                            + " codec a second time");
        }
    }

    /**
     * Records the input a run started with, when the definition says how to
     * encode it.
     *
     * <p>After {@code RUN_STARTED} rather than before it, because the log is
     * what says a run exists: an input in the store with no history behind it
     * would be a run nothing could list. The cost is a window where a run is
     * listed with no input, which {@link #resume(RecoverableRun, Object)} exists
     * to cover.
     *
     * <p>A null input is not recorded. There is nothing for a codec to encode,
     * and passing null back in is something the caller can do without help.
     */
    private <I> void recordInput(Workflow<I, ?> workflow, I input, String runId) {
        Codec<I> codec = workflow.inputCodec();
        if (codec != null && input != null) {
            store.saveInput(runId, codec.encode(input));
        }
    }

    /**
     * Settle a step left in doubt by saying its side effect did happen, and
     * recording {@code output} as what it produced. The next resume replays that
     * value rather than running the step.
     *
     * <p>Call it once you have looked at the system the step talked to and found
     * the work done: the charge on the account, the message on the queue. Tandem
     * cannot look for you, which is the whole reason the run stopped.
     *
     * @param <T> the step's output type
     */
    public <T> void confirmCompleted(String runId, String stepName, T output, Codec<T> codec) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepName, "stepName");
        Objects.requireNonNull(codec, "codec");

        // Output first, then the event that says it is safe to trust, for the
        // same reason a step attempt does it in that order.
        store.saveOutput(runId, stepName, codec.encode(output));
        emit(settlement(runId, stepName, EventType.STEP_SUCCEEDED));
    }

    /**
     * Settle a step left in doubt by saying its side effect did not happen, so
     * the next resume runs the step again.
     *
     * <p>Recorded as a failed step, because that is what it was: it started and
     * did not complete.
     */
    public void confirmNotCompleted(String runId, String stepName) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepName, "stepName");

        emit(settlement(runId, stepName, EventType.STEP_FAILED));
    }

    /** Runs one step, retrying per its policy. */
    private Attempted attempt(
            List<WorkflowEvent> collected,
            Workflow<?, ?> workflow,
            StepDefinition definition,
            Object input,
            String runId,
            boolean resuming) {

        Throwable last = null;
        int maxAttempts = definition.retryPolicy.maxAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration wait = definition.retryPolicy.delayBefore(attempt, random);
            if (!wait.isZero()) {
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException interrupted) {
                    // Restore the flag: swallowing it strands whoever asked the
                    // thread to stop.
                    Thread.currentThread().interrupt();
                    return Attempted.failed(interrupted);
                }
            }

            StepContext context =
                    new StepContext(runId, workflow.name(), definition.name, attempt, resuming);
            emit(collected, event(runId, workflow, definition.name, EventType.STEP_STARTED, attempt, ""));

            Object output;
            try {
                output = definition.step.run(input, context);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                emit(
                        collected,
                        event(
                                runId,
                                workflow,
                                definition.name,
                                EventType.STEP_FAILED,
                                attempt,
                                "interrupted"));
                return Attempted.failed(interrupted);
            } catch (Exception thrown) {
                last = thrown;
                boolean willRetry = attempt < maxAttempts;
                emit(
                        collected,
                        event(
                                runId,
                                workflow,
                                definition.name,
                                willRetry ? EventType.STEP_RETRYING : EventType.STEP_FAILED,
                                attempt,
                                describe(thrown)));
                continue;
            }

            // The output is recorded before the step is called succeeded, and
            // outside the catch above on purpose. Before, because a crash
            // between the two must leave the run replayable rather than in
            // doubt. Outside, because a store failure here is not a step
            // failure: retrying it would run the side effect a second time.
            if (definition.replayable()) {
                store.saveOutput(runId, definition.name, definition.codec.encode(output));
            }
            emit(
                    collected,
                    event(runId, workflow, definition.name, EventType.STEP_SUCCEEDED, attempt, ""));
            return Attempted.succeeded(output);
        }

        return Attempted.failed(last);
    }

    /**
     * Ends a resume at a step whose standing the log cannot settle.
     *
     * <p>Compensations deliberately do not run. They would undo steps whose
     * recorded outputs stay in the store, so a later resume would replay values
     * that no longer stand. A run stopped here is waiting for a decision, not
     * being abandoned.
     */
    private <O> RunResult<O> refuse(
            List<WorkflowEvent> collected, Workflow<?, ?> workflow, String runId, String stepName) {

        StepInDoubtException failure = new StepInDoubtException(runId, stepName);
        emit(collected, event(runId, workflow, stepName, EventType.STEP_IN_DOUBT, 0, failure.getMessage()));
        emit(collected, event(runId, workflow, stepName, EventType.RUN_FAILED, 0, String.valueOf(failure)));
        return RunResult.failed(runId, failure, collected);
    }

    /** Undoes completed steps, most recent first. */
    private void compensate(
            List<WorkflowEvent> collected,
            Workflow<?, ?> workflow,
            String runId,
            List<Completed> completed) {

        for (int i = completed.size() - 1; i >= 0; i--) {
            Completed done = completed.get(i);
            if (done.definition.compensation == null) {
                continue;
            }
            StepContext context =
                    new StepContext(runId, workflow.name(), done.definition.name, 1, false);
            try {
                done.definition.compensation.undo(done.output, context);
                emit(
                        collected,
                        event(
                                runId,
                                workflow,
                                done.definition.name,
                                EventType.STEP_COMPENSATED,
                                1,
                                ""));
            } catch (Exception thrown) {
                // Keep going. Stopping here leaves more undone than continuing.
                //
                // Its own event type, not a note on the compensated event: a
                // later resume has to tell a step that was undone from one that
                // may or may not have been, and reading that off a message
                // string is not a decision worth taking twice.
                emit(
                        collected,
                        event(
                                runId,
                                workflow,
                                done.definition.name,
                                EventType.STEP_COMPENSATION_FAILED,
                                1,
                                describe(thrown)));
            }
        }
    }

    private WorkflowEvent event(
            String runId,
            Workflow<?, ?> workflow,
            String stepName,
            EventType type,
            int attempt,
            String detail) {
        return new WorkflowEvent(runId, workflow.name(), stepName, type, attempt, Instant.now(), detail);
    }

    /**
     * The outcome event a confirmation writes. It belongs to no run of the
     * engine, so there is no workflow name or attempt to put on it.
     */
    private static WorkflowEvent settlement(String runId, String stepName, EventType type) {
        return new WorkflowEvent(
                runId, "", stepName, type, 0, Instant.now(), "confirmed out of band");
    }

    /**
     * Records an event and tells the listeners.
     *
     * <p>A store failure propagates: durability is why a store was chosen, so
     * losing it silently would be worse than failing loudly. A listener failure
     * does not: observability breaking must not break the process it watches.
     */
    private void emit(List<WorkflowEvent> collected, WorkflowEvent event) {
        collected.add(event);
        emit(event);
    }

    /** The same, for events that belong to no run in progress. */
    private void emit(WorkflowEvent event) {
        store.append(event);
        for (WorkflowListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ignored) {
                // Deliberately swallowed, see above.
            }
        }
    }

    private static String describe(Throwable thrown) {
        String message = thrown.getMessage();
        return thrown.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private record Completed(StepDefinition definition, Object output) {}

    private static final class Attempted {
        final boolean succeeded;
        final Object output;
        final Throwable failure;

        private Attempted(boolean succeeded, Object output, Throwable failure) {
            this.succeeded = succeeded;
            this.output = output;
            this.failure = failure;
        }

        static Attempted succeeded(Object output) {
            return new Attempted(true, output, null);
        }

        static Attempted failed(Throwable failure) {
            return new Attempted(false, null, failure);
        }
    }
}
