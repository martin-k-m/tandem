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
        List<WorkflowEvent> history = store.eventsFor(runId);
        boolean resuming = !history.isEmpty();

        emit(
                collected,
                event(runId, workflow, "", resuming ? EventType.RUN_RESUMED : EventType.RUN_STARTED, 0, ""));

        Object current = input;
        List<Completed> completed = new ArrayList<>();

        for (StepDefinition definition : workflow.steps()) {
            if (resuming && definition.replayable()) {
                Optional<String> recorded = store.loadOutput(runId, definition.name);
                if (recorded.isPresent()) {
                    current = definition.codec.decode(recorded.get());
                    emit(collected, event(runId, workflow, definition.name, EventType.STEP_REPLAYED, 0, ""));
                    completed.add(new Completed(definition, current));
                    continue;
                }
                // No output, but the log says an earlier run was inside this
                // step when it stopped. Only steps the caller declared
                // replayable get this treatment: a step without a codec was
                // declared safe to repeat.
                if (startedAndUnsettled(history, definition.name)) {
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
     * Whether an earlier run wrote a started event for this step and nothing
     * that says how it ended.
     *
     * <p>Every attempt writes {@link EventType#STEP_STARTED} before running the
     * step and exactly one of succeeded, retrying or failed after it. A started
     * event with no partner means the process stopped inside the step body, so
     * whether the side effect happened is not knowable from the log. Events that
     * say nothing about an attempt's outcome, such as a previous refusal, are
     * ignored, or a run would talk itself out of its own doubt.
     */
    private static boolean startedAndUnsettled(List<WorkflowEvent> history, String stepName) {
        int started = 0;
        int settled = 0;
        for (WorkflowEvent past : history) {
            if (!past.stepName().equals(stepName)) {
                continue;
            }
            switch (past.type()) {
                case STEP_STARTED -> started++;
                case STEP_SUCCEEDED, STEP_RETRYING, STEP_FAILED -> settled++;
                default -> { }
            }
        }
        return started > settled;
    }

    /**
     * Ends a resume at a step that may or may not have run.
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
                emit(
                        collected,
                        event(
                                runId,
                                workflow,
                                done.definition.name,
                                EventType.STEP_COMPENSATED,
                                1,
                                "compensation failed: " + describe(thrown)));
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
