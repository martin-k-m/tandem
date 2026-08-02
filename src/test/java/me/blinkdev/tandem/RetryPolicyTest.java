package me.blinkdev.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void noneMeansOneAttemptAndNoWaiting() {
        RetryPolicy policy = RetryPolicy.none();
        assertEquals(1, policy.maxAttempts());
        assertEquals(Duration.ZERO, policy.baseDelayBefore(1));
    }

    @Test
    void theFirstAttemptNeverWaits() {
        RetryPolicy policy = RetryPolicy.exponential(5, Duration.ofSeconds(1));
        assertEquals(Duration.ZERO, policy.baseDelayBefore(1));
        assertEquals(Duration.ZERO, policy.baseDelayBefore(0));
    }

    @Test
    void fixedUsesTheSameDelayEveryTime() {
        RetryPolicy policy = RetryPolicy.fixed(4, Duration.ofMillis(250));
        assertEquals(Duration.ofMillis(250), policy.baseDelayBefore(2));
        assertEquals(Duration.ofMillis(250), policy.baseDelayBefore(3));
        assertEquals(Duration.ofMillis(250), policy.baseDelayBefore(4));
    }

    @Test
    void exponentialDoublesUntilTheCap() {
        RetryPolicy policy = RetryPolicy.exponential(10, Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(1), policy.baseDelayBefore(2));
        assertEquals(Duration.ofSeconds(2), policy.baseDelayBefore(3));
        assertEquals(Duration.ofSeconds(4), policy.baseDelayBefore(4));
        // The default cap is one minute, so it stops climbing rather than
        // reaching the eight-plus minutes an uncapped doubling would.
        assertEquals(Duration.ofMinutes(1), policy.baseDelayBefore(10));
    }

    @Test
    void maxDelayCapsTheClimb() {
        RetryPolicy policy =
                RetryPolicy.exponential(10, Duration.ofSeconds(1)).withMaxDelay(Duration.ofSeconds(3));
        assertEquals(Duration.ofSeconds(1), policy.baseDelayBefore(2));
        assertEquals(Duration.ofSeconds(2), policy.baseDelayBefore(3));
        assertEquals(Duration.ofSeconds(3), policy.baseDelayBefore(4));
        assertEquals(Duration.ofSeconds(3), policy.baseDelayBefore(9));
    }

    @Test
    void jitterOnlyEverReducesTheDelay() {
        RetryPolicy policy = RetryPolicy.fixed(5, Duration.ofSeconds(10)).withJitter(0.5);
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            Duration delay = policy.delayBefore(3, random);
            assertTrue(delay.toMillis() <= 10_000, "jitter must not extend the delay");
            assertTrue(delay.toMillis() >= 5_000, "jitter must not exceed the configured fraction");
        }
    }

    @Test
    void withoutJitterTheDelayIsDeterministic() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(2));
        assertEquals(policy.baseDelayBefore(2), policy.delayBefore(2, new Random()));
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThrows(
                IllegalArgumentException.class, () -> RetryPolicy.fixed(0, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> RetryPolicy.fixed(2, Duration.ofSeconds(1)).withMultiplier(0.5));
        assertThrows(
                IllegalArgumentException.class,
                () -> RetryPolicy.fixed(2, Duration.ofSeconds(1)).withJitter(1.5));
    }
}
