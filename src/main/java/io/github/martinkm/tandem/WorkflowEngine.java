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
        boolean resuming = !store.eventsFor(runId).isEmpty();

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

            if (definition.replayable()) {
                store.saveOutput(runId, definition.name, definition.codec.encode(attempted.output));
            }
            current = attempted.output;
            completed.add(new Completed(definition, current));
        }

        emit(collected, event(runId, workflow, "", EventType.RUN_SUCCEEDED, 0, ""));

        @SuppressWarnings("unchecked")
        O output = (O) current;
        return RunResult.succeeded(runId, output, collected);
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

            try {
                Object output = definition.step.run(input, context);
                emit(
                        collected,
                        event(runId, workflow, definition.name, EventType.STEP_SUCCEEDED, attempt, ""));
                return Attempted.succeeded(output);
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
            }
        }

        return Attempted.failed(last);
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
     * Records an event and tells the listeners.
     *
     * <p>A store failure propagates: durability is why a store was chosen, so
     * losing it silently would be worse than failing loudly. A listener failure
     * does not: observability breaking must not break the process it watches.
     */
    private void emit(List<WorkflowEvent> collected, WorkflowEvent event) {
        collected.add(event);
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
