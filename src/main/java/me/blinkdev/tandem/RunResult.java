package me.blinkdev.tandem;

import java.util.List;
import java.util.Optional;

/**
 * What a run produced, and everything that happened along the way.
 *
 * <p>A failed run is a value, not a thrown exception. A workflow failing after
 * its compensations ran is an outcome the caller usually wants to inspect,
 * branch on and record, and forcing a try/catch for the common case makes that
 * awkward. {@link #outputOrThrow()} is there when you do want the throw.
 *
 * @param <O> the workflow's output type
 */
public final class RunResult<O> {

    private final String runId;
    private final O output;
    private final Throwable failure;
    private final List<WorkflowEvent> events;

    private RunResult(String runId, O output, Throwable failure, List<WorkflowEvent> events) {
        this.runId = runId;
        this.output = output;
        this.failure = failure;
        this.events = List.copyOf(events);
    }

    static <O> RunResult<O> succeeded(String runId, O output, List<WorkflowEvent> events) {
        return new RunResult<>(runId, output, null, events);
    }

    static <O> RunResult<O> failed(String runId, Throwable failure, List<WorkflowEvent> events) {
        return new RunResult<>(runId, null, failure, events);
    }

    public String runId() {
        return runId;
    }

    public boolean succeeded() {
        return failure == null;
    }

    public boolean failed() {
        return failure != null;
    }

    /** The output, or empty if the run failed. */
    public Optional<O> output() {
        return Optional.ofNullable(output);
    }

    /** The output, or a {@link TandemException} wrapping the failure. */
    public O outputOrThrow() {
        if (failure != null) {
            throw new TandemException("workflow run " + runId + " failed", failure);
        }
        return output;
    }

    /** What went wrong, or null if the run succeeded. */
    public Throwable failure() {
        return failure;
    }

    /** Every event, oldest first. */
    public List<WorkflowEvent> events() {
        return events;
    }

    /** The events of one type, which is usually how you assert on a run. */
    public List<WorkflowEvent> eventsOfType(EventType type) {
        return events.stream().filter(event -> event.type() == type).toList();
    }

    @Override
    public String toString() {
        return "RunResult["
                + runId
                + ", "
                + (succeeded() ? "succeeded" : "failed: " + failure)
                + "]";
    }
}
