package io.github.martinkm.tandem;

import java.time.Duration;
import java.util.Objects;

/**
 * How many times a step is retried, and how long to wait between attempts.
 *
 * <p>Immutable. The {@code with*} methods return a new policy, so a shared
 * default can be adapted per step without anybody mutating it underneath you.
 *
 * <p>Attempt numbers are 1-based: attempt 1 is the first try, not the first
 * retry. A policy with {@code maxAttempts == 1} never retries.
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialDelay;
    private final double multiplier;
    private final Duration maxDelay;
    private final double jitter;

    private RetryPolicy(
            int maxAttempts,
            Duration initialDelay,
            double multiplier,
            Duration maxDelay,
            double jitter) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        }
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay cannot be negative");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0, got " + multiplier);
        }
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter must be between 0.0 and 1.0, got " + jitter);
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.multiplier = multiplier;
        this.maxDelay = maxDelay;
        this.jitter = jitter;
    }

    /** Fail on the first error. */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO, 0.0);
    }

    /** The same delay between every attempt. */
    public static RetryPolicy fixed(int maxAttempts, Duration delay) {
        Objects.requireNonNull(delay, "delay");
        return new RetryPolicy(maxAttempts, delay, 1.0, delay, 0.0);
    }

    /**
     * Doubling backoff, capped at one minute.
     *
     * <p>Capped because an uncapped exponential reaches absurd delays quickly:
     * ten attempts from one second is over eight minutes for the last wait
     * alone, which is rarely what anyone meant.
     */
    public static RetryPolicy exponential(int maxAttempts, Duration initialDelay) {
        Objects.requireNonNull(initialDelay, "initialDelay");
        return new RetryPolicy(maxAttempts, initialDelay, 2.0, Duration.ofMinutes(1), 0.0);
    }

    public RetryPolicy withMultiplier(double multiplier) {
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, jitter);
    }

    public RetryPolicy withMaxDelay(Duration maxDelay) {
        Objects.requireNonNull(maxDelay, "maxDelay");
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, jitter);
    }

    /**
     * Randomise each delay by up to {@code fraction} of itself, downward.
     *
     * <p>Worth setting when many workers retry the same failing dependency: with
     * no jitter they all wake at the same instant and hit it together.
     */
    public RetryPolicy withJitter(double fraction) {
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, fraction);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public double jitter() {
        return jitter;
    }

    /**
     * How long to wait before {@code attempt}, ignoring jitter.
     *
     * @param attempt the 1-based attempt about to be made; the delay before
     *                attempt 1 is always zero
     */
    public Duration baseDelayBefore(int attempt) {
        if (attempt <= 1) {
            return Duration.ZERO;
        }
        double millis = initialDelay.toMillis();
        for (int i = 2; i < attempt; i++) {
            millis *= multiplier;
            if (millis >= maxDelay.toMillis()) {
                return maxDelay;
            }
        }
        long capped = Math.min((long) millis, maxDelay.toMillis());
        return Duration.ofMillis(Math.max(0L, capped));
    }

    /**
     * The delay actually used, jitter included.
     *
     * @param random a source of randomness, so callers that need reproducible
     *               runs can supply a seeded one
     */
    public Duration delayBefore(int attempt, java.util.Random random) {
        Duration base = baseDelayBefore(attempt);
        if (jitter == 0.0 || base.isZero()) {
            return base;
        }
        long millis = base.toMillis();
        long reduction = (long) (millis * jitter * random.nextDouble());
        return Duration.ofMillis(millis - reduction);
    }

    @Override
    public String toString() {
        return "RetryPolicy[maxAttempts="
                + maxAttempts
                + ", initialDelay="
                + initialDelay
                + ", multiplier="
                + multiplier
                + ", maxDelay="
                + maxDelay
                + ", jitter="
                + jitter
                + "]";
    }
}
