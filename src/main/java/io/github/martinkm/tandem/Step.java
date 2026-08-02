package io.github.martinkm.tandem;

/**
 * One unit of work in a workflow.
 *
 * <p>Declares {@code throws Exception} so a step can call ordinary blocking code
 * without wrapping every checked exception. The engine decides what a thrown
 * exception means by consulting the step's {@link RetryPolicy}.
 *
 * @param <I> what this step receives, the previous step's output
 * @param <O> what it produces, the next step's input
 */
@FunctionalInterface
public interface Step<I, O> {

    O run(I input, StepContext context) throws Exception;
}
