package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The scheduler runs on real threads, so these assert on outcomes reached
 * through a latch rather than on wall-clock timing: a run counts down when it
 * happens, and the test waits for the count rather than for a fixed interval.
 */
class SchedulerTest {

    private WorkflowEngine engine() {
        return new WorkflowEngine(new InMemoryStore(), Sleeper.none(), new Random(1));
    }

    /** A one-step workflow that counts down a latch each time it runs. */
    private Workflow<String, String> counting(CountDownLatch ran) {
        return Workflow.<String>named("counted")
                .step(
                        "tick",
                        (String in, StepContext ctx) -> {
                            ran.countDown();
                            return in;
                        })
                .build();
    }

    @Test
    void afterRunsTheWorkflowOnce() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        try (Scheduler scheduler = new Scheduler(engine())) {
            ScheduledFuture<RunResult<String>> future =
                    scheduler.after(counting(ran), "go", Duration.ofMillis(10));

            assertTrue(ran.await(2, TimeUnit.SECONDS), "the scheduled run never happened");
            RunResult<String> result = future.get(2, TimeUnit.SECONDS);
            assertTrue(result.succeeded());
            assertEquals("go", result.outputOrThrow());
        }
    }

    @Test
    void everyRepeatsTheWorkflow() throws Exception {
        CountDownLatch ran = new CountDownLatch(3);
        try (Scheduler scheduler = new Scheduler(engine())) {
            ScheduledFuture<?> handle =
                    scheduler.every(counting(ran), "go", Duration.ofMillis(5));

            assertTrue(ran.await(2, TimeUnit.SECONDS), "the schedule did not fire enough times");
            handle.cancel(false);
        }
    }

    @Test
    void aRunThatThrowsDoesNotCancelTheSchedule() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch succeededTwice = new CountDownLatch(2);
        // Throws on the first firing and succeeds afterwards. If an escaping
        // exception cancelled the schedule, the later firings would never come.
        Workflow<String, String> flaky =
                Workflow.<String>named("flaky-schedule")
                        .step(
                                "run",
                                (String in, StepContext ctx) -> {
                                    if (attempts.incrementAndGet() == 1) {
                                        throw new IllegalStateException("first firing fails");
                                    }
                                    succeededTwice.countDown();
                                    return in;
                                })
                        .build();

        try (Scheduler scheduler = new Scheduler(engine())) {
            ScheduledFuture<?> handle = scheduler.every(flaky, "go", Duration.ofMillis(5));
            assertTrue(
                    succeededTwice.await(2, TimeUnit.SECONDS),
                    "a failing firing stopped the schedule");
            handle.cancel(false);
        }
    }

    /**
     * The property is that {@code close} stops <em>further</em> runs, which is
     * not the same as stopping instantly: {@code close} calls
     * {@code shutdownNow}, and that interrupts a firing already in flight rather
     * than waiting for it. So the count is sampled after a settling pause, and
     * what is asserted is that it does not move again.
     *
     * <p>This used to sleep 30ms, close, and sample immediately. Both halves
     * were wrong on a machine under load. The schedule had often not fired at
     * all in 30ms, so the test proved nothing; and the sample taken the instant
     * close returned could still be overtaken by the firing that was already
     * running, which is the assertion that actually failed:
     * {@code expected: <0> but was: <1>}. Waiting on a latch for proof that the
     * schedule is live, which is what every other test in this class does,
     * removes both.
     */
    @Test
    void closingAnOwnedSchedulerStopsFurtherRuns() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firedTwice = new CountDownLatch(2);
        Workflow<String, String> workflow =
                Workflow.<String>named("counted")
                        .step("tick", (String in, StepContext ctx) -> {
                            runs.incrementAndGet();
                            firedTwice.countDown();
                            return in;
                        })
                        .build();

        Scheduler scheduler = new Scheduler(engine());
        scheduler.every(workflow, "go", Duration.ofMillis(5));

        assertTrue(
                firedTwice.await(10, TimeUnit.SECONDS),
                "the schedule never started firing, so close() was not tested");

        scheduler.close();
        // Long enough for a firing interrupted by shutdownNow to finish
        // incrementing, and many times the 5ms period, so a schedule that was
        // still live would be caught here rather than after the sample.
        Thread.sleep(200);
        int afterClose = runs.get();
        Thread.sleep(200);

        assertEquals(afterClose, runs.get(), "the schedule kept firing after close()");
    }

    @Test
    void aBorrowedExecutorIsNotShutDownByClose() {
        ScheduledExecutorService borrowed = Executors.newSingleThreadScheduledExecutor();
        try {
            Scheduler scheduler = new Scheduler(engine(), borrowed);
            scheduler.close();
            assertFalse(borrowed.isShutdown(), "close() shut down an executor it does not own");
        } finally {
            borrowed.shutdownNow();
        }
    }
}
