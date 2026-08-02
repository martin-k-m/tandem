package me.blinkdev.tandem;

/**
 * Observes a run as it happens.
 *
 * <p>Every event also reaches the {@link WorkflowStore}, so a listener is not
 * how you get durability. It is how you get metrics, tracing spans, or a log
 * line, without Tandem picking a logging framework on your behalf.
 *
 * <p>Listeners are called on the thread running the step, so a slow one slows
 * the workflow. An exception thrown by a listener is swallowed by the engine:
 * observability failing must not fail the business process it is watching.
 */
@FunctionalInterface
public interface WorkflowListener {

    void onEvent(WorkflowEvent event);

    /** Writes one line per event to standard error. */
    static WorkflowListener logging() {
        return event -> System.err.printf(
                "[tandem] %s %s %s%s%s%n",
                event.at(),
                event.workflowName(),
                event.type(),
                event.stepName().isEmpty() ? "" : " " + event.stepName(),
                event.detail().isEmpty() ? "" : " (" + event.detail() + ")");
    }
}
