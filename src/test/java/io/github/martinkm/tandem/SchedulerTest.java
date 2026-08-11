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

    @Test
    void closingAnOwnedSchedulerStopsFurtherRuns() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Workflow<String, String> workflow =
                Workflow.<String>named("counted")
                        .step("tick", (String in, StepContext ctx) -> {
                            runs.incrementAndGet();
                            return in;
                        })
                        .build();

        Scheduler scheduler = new Scheduler(engine());
        scheduler.every(workflow, "go", Duration.ofMillis(5));
        Thread.sleep(30);
        scheduler.close();
        int afterClose = runs.get();
        Thread.sleep(50);

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
