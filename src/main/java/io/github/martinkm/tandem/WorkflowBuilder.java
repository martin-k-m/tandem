package io.github.martinkm.tandem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a {@link Workflow}, carrying the current output type forward so each
 * step is checked against the one before it at compile time.
 *
 * <p>{@code C} is the type the next step will receive. Adding a step returns a
 * builder with a new {@code C}, which is why the result of {@link #step} must be
 * used rather than discarded.
 *
 * @param <I> the workflow's input type
 * @param <C> the current output type, and the next step's input
 */
public final class WorkflowBuilder<I, C> {

    private final String name;
    private final List<StepDefinition> steps = new ArrayList<>();
    private RetryPolicy defaultRetry = RetryPolicy.none();

    WorkflowBuilder(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a workflow needs a name");
        }
        this.name = name;
    }

    /**
     * The retry policy for steps added after this call. Steps already added keep
     * whatever policy they had, so ordering is not a trap: this reads forward,
     * like the rest of the builder.
     */
    public WorkflowBuilder<I, C> retry(RetryPolicy policy) {
        this.defaultRetry = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /** A step that is re-executed if the run is resumed. */
    public <N> WorkflowBuilder<I, N> step(String stepName, Step<C, N> step) {
        return add(stepName, step, defaultRetry, null);
    }

    /**
     * A step whose output is recorded, so a resumed run reuses it instead of
     * running the step again. Use this for anything with a side effect.
     */
    public <N> WorkflowBuilder<I, N> step(String stepName, Step<C, N> step, Codec<N> codec) {
        return add(stepName, step, defaultRetry, Objects.requireNonNull(codec, "codec"));
    }

    /** A step with its own retry policy, overriding the current default. */
    public <N> WorkflowBuilder<I, N> step(String stepName, Step<C, N> step, RetryPolicy policy) {
        return add(stepName, step, Objects.requireNonNull(policy, "policy"), null);
    }

    /** A step with its own retry policy that is also replayable. */
    public <N> WorkflowBuilder<I, N> step(
            String stepName, Step<C, N> step, RetryPolicy policy, Codec<N> codec) {
        return add(
                stepName,
                step,
                Objects.requireNonNull(policy, "policy"),
                Objects.requireNonNull(codec, "codec"));
    }

    /**
     * Attach a compensation to the step just added. Called if a later step
     * fails, in reverse order.
     */
    public WorkflowBuilder<I, C> compensate(Compensation compensation) {
        if (steps.isEmpty()) {
            throw new IllegalStateException("compensate() must follow a step");
        }
        steps.get(steps.size() - 1).compensation =
                Objects.requireNonNull(compensation, "compensation");
        return this;
    }

    public Workflow<I, C> build() {
        if (steps.isEmpty()) {
            throw new IllegalStateException("a workflow needs at least one step");
        }
        return new Workflow<>(name, steps);
    }

    @SuppressWarnings("unchecked")
    private <N> WorkflowBuilder<I, N> add(
            String stepName, Step<C, N> step, RetryPolicy policy, Codec<N> codec) {
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("a step needs a name");
        }
        Objects.requireNonNull(step, "step");
        for (StepDefinition existing : steps) {
            if (existing.name.equals(stepName)) {
                // Names key the recorded outputs, so a duplicate would make one
                // step replay another's result.
                throw new IllegalArgumentException("duplicate step name: " + stepName);
            }
        }

        steps.add(
                new StepDefinition(
                        stepName,
                        (Step<Object, Object>) (Step<?, ?>) step,
                        policy,
                        (Codec<Object>) (Codec<?>) codec));

        return (WorkflowBuilder<I, N>) (WorkflowBuilder<I, ?>) this;
    }
}
