package io.github.martinkm.tandem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A named sequence of steps, where each one's output is the next one's input.
 *
 * <p>Definitions are immutable and hold no run state, so one definition is
 * safely shared across threads and reused for as many runs as you like.
 *
 * <pre>{@code
 * Workflow<String, Integer> parse = Workflow.<String>named("parse")
 *         .retry(RetryPolicy.exponential(3, Duration.ofMillis(100)))
 *         .step("trim", (String raw, StepContext ctx) -> raw.trim(), Codec.ofString())
 *         .step("toNumber", (String text, StepContext ctx) -> Integer.parseInt(text))
 *         .build();
 * }</pre>
 *
 * @param <I> the workflow's input
 * @param <O> the last step's output
 */
public final class Workflow<I, O> {

    private final String name;
    private final List<StepDefinition> steps;

    Workflow(String name, List<StepDefinition> steps) {
        this.name = Objects.requireNonNull(name, "name");
        this.steps = List.copyOf(steps);
    }

    /**
     * Start building. The type witness fixes the input type:
     * {@code Workflow.<String>named("orders")}.
     */
    public static <T> WorkflowBuilder<T, T> named(String name) {
        return new WorkflowBuilder<>(name);
    }

    public String name() {
        return name;
    }

    public List<String> stepNames() {
        List<String> names = new ArrayList<>(steps.size());
        for (StepDefinition definition : steps) {
            names.add(definition.name);
        }
        return List.copyOf(names);
    }

    public int size() {
        return steps.size();
    }

    List<StepDefinition> steps() {
        return steps;
    }

    @Override
    public String toString() {
        return "Workflow[" + name + ", " + steps.size() + " steps]";
    }
}
