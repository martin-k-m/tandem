package me.blinkdev.tandem;

import java.time.Duration;

/**
 * How the engine waits between retries.
 *
 * <p>Injectable so a test of backoff behaviour does not have to actually spend
 * the backoff. A suite that verifies five retries with exponential delays would
 * otherwise take half a minute to tell you what {@link #none()} tells you
 * instantly.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    /** Really waits. The default. */
    static Sleeper real() {
        return duration -> {
            if (!duration.isZero() && !duration.isNegative()) {
                Thread.sleep(duration.toMillis());
            }
        };
    }

    /** Returns immediately. For tests. */
    static Sleeper none() {
        return duration -> {};
    }
}
