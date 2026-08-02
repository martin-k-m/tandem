package io.github.martinkm.tandem;

/**
 * A step with its generics erased, which is how the engine stores a chain whose
 * types differ at every position. The public builder keeps the type safety; this
 * is the flattened form it produces.
 */
final class StepDefinition {

    final String name;
    final Step<Object, Object> step;
    final RetryPolicy retryPolicy;
    /** Null when the step is not replayable. */
    final Codec<Object> codec;
    /** Null when the step has nothing to undo. */
    Compensation compensation;

    StepDefinition(
            String name, Step<Object, Object> step, RetryPolicy retryPolicy, Codec<Object> codec) {
        this.name = name;
        this.step = step;
        this.retryPolicy = retryPolicy;
        this.codec = codec;
    }

    boolean replayable() {
        return codec != null;
    }
}
