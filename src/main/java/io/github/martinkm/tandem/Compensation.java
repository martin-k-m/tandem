package io.github.martinkm.tandem;

/**
 * Undoes a step that already succeeded, when a later step fails.
 *
 * <p>This is the saga pattern: a workflow that charged a card and then failed to
 * reserve stock has to refund the card, because there is no transaction spanning
 * both. Compensations run in reverse order, most recent first.
 *
 * <p>A compensation that itself throws is recorded and the remaining
 * compensations still run. Stopping there would leave more undone than
 * continuing does.
 */
@FunctionalInterface
public interface Compensation {

    /**
     * @param output  what the step being undone produced
     * @param context the step's context, with {@code attempt} set to 1
     */
    void undo(Object output, StepContext context) throws Exception;
}
