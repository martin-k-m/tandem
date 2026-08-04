package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A step that hangs must not hang the run. */
class StepTimeoutTest {

    /** Blocks until released, or until interrupted. */
    private static final class Gate {
        private final CountDownLatch latch = new CountDownLatch(1);

        void await() throws InterruptedException {
            latch.await();
        }

        void release() {
            latch.countDown();
        }
    }

    @Test
    void aStepThatHangsFailsTheRunRatherThanBlockingIt() {
        Gate gate = new Gate();
        try (WorkflowEngine engine = new WorkflowEngine()) {
            Workflow<String, String> workflow =
                    Workflow.<String>named("hangs")
                            .timeout(Duration.ofMillis(120))
                            .step(
                                    "stuck",
                                    (String in, StepContext ctx) -> {
                                        gate.await();
                                        return "never";
                                    })
                            .build();

            RunResult<String> result = engine.run(workflow, "go");

            assertFalse(result.succeeded());
            assertInstanceOf(StepTimedOutException.class, result.failure());
        } finally {
            gate.release();
        }
    }

    @Test
    void theTimedOutStepIsNamedAlongWithTheBudgetItMissed() {
        Gate gate = new Gate();
        try (WorkflowEngine engine = new WorkflowEngine()) {
            Workflow<String, String> workflow =
                    Workflow.<String>named("named")
                            .timeout(Duration.ofMillis(90))
                            .step(
                                    "slow-call",
                                    (String in, StepContext ctx) -> {
                                        gate.await();
                                        return "never";
                                    })
                            .build();

            StepTimedOutException timedOut =
                    assertInstanceOf(
                            StepTimedOutException.class,
                            engine.run(workflow, "go").failure());

            assertEquals("slow-call", timedOut.stepName());
            assertEquals(Duration.ofMillis(90), timedOut.timeout());
        } finally {
            gate.release();
        }
    }

    @Test
    void aTimeoutIsRetriedLikeAnyOtherFailure() {
        AtomicInteger attempts = new AtomicInteger();
        try (WorkflowEngine engine = new WorkflowEngine()) {
            Workflow<String, String> workflow =
                    Workflow.<String>named("eventually")
                            .retry(RetryPolicy.fixed(3, Duration.ZERO))
                            .timeout(Duration.ofMillis(150))
                            .step(
                                    "flaky",
                                    (String in, StepContext ctx) -> {
                                        // Hangs the first two times, answers the third.
                                        if (attempts.incrementAndGet() < 3) {
                                            Thread.sleep(10_000);
                                        }
                                        return "done";
                                    })
                            .build();

            RunResult<String> result = engine.run(workflow, "go");

            assertTrue(result.succeeded());
            assertEquals("done", result.outputOrThrow());
            assertEquals(3, attempts.get());
        }
    }

    @Test
    void theAbandonedStepIsInterrupted() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch noticed = new CountDownLatch(1);

        try (WorkflowEngine engine = new WorkflowEngine()) {
            Workflow<String, String> workflow =
                    Workflow.<String>named("interrupts")
                            .timeout(Duration.ofMillis(80))
                            .step(
                                    "stuck",
                                    (String in, StepContext ctx) -> {
                                        try {
                                            Thread.sleep(10_000);
                                        } catch (InterruptedException e) {
                                            interrupted.set(true);
                                            noticed.countDown();
                                            throw e;
                                        }
                                        return "never";
                                    })
                            .build();

            engine.run(workflow, "go");

            // Interruption is a request, so it is asserted by observing the step
            // notice it rather than by assuming it stopped.
            assertTrue(noticed.await(2, TimeUnit.SECONDS), "step was never interrupted");
            assertTrue(interrupted.get());
        }
    }

    @Test
    void aStepInsideItsBudgetIsUntouched() {
        try (WorkflowEngine engine = new WorkflowEngine()) {
            Workflow<String, String> workflow =
                    Workflow.<String>named("quick")
                            .timeout(Duration.ofSeconds(5))
                            .step("fast", (String in, StepContext ctx) -> in + "!")
                            .build();

            RunResult<String> result = engine.run(workflow, "go");

            assertTrue(result.succeeded());
            assertEquals("go!", result.outputOrThrow());
        }
    }

    @Test
    void aStepsOwnExceptionIsNotWrappedByTheTimeoutMachinery() {
        try (WorkflowEngine engine = new WorkflowEngine()) {
            Workflow<String, String> workflow =
                    Workflow.<String>named("throws")
                            .timeout(Duration.ofSeconds(5))
                            // Explicit witness: a lambda whose body only throws
                            // gives javac nothing to infer the output type from,
                            // so it resolves to Object.
                            .<String>step(
                                    "boom",
                                    (String in, StepContext ctx) -> {
                                        throw new IllegalStateException("mine");
                                    })
                            .build();

            Throwable failure = engine.run(workflow, "go").failure();

            // Running the step on another thread wraps anything it throws in an
            // ExecutionException. Letting that reach the caller would mean a
            // catch written against the step's own exception stops matching the
            // moment a timeout is added to it.
            assertInstanceOf(IllegalStateException.class, failure);
            assertEquals("mine", failure.getMessage());
        }
    }

    @Test
    void aTimeoutAppliesOnlyToStepsAddedAfterIt() {
        Gate gate = new Gate();
        List<String> ran = new ArrayList<>();
        try (WorkflowEngine engine = new WorkflowEngine()) {
            Workflow<String, String> workflow =
                    Workflow.<String>named("forward")
                            .step(
                                    "unbounded",
                                    (String in, StepContext ctx) -> {
                                        ran.add("unbounded");
                                        return in;
                                    })
                            .timeout(Duration.ofMillis(80))
                            .step(
                                    "bounded",
                                    (String in, StepContext ctx) -> {
                                        ran.add("bounded");
                                        gate.await();
                                        return "never";
                                    })
                            .build();

            RunResult<String> result = engine.run(workflow, "go");

            assertFalse(result.succeeded());
            assertEquals(List.of("unbounded", "bounded"), ran);
        } finally {
            gate.release();
        }
    }

    @Test
    void compensationStillRunsForStepsBeforeATimeout() {
        Gate gate = new Gate();
        List<String> undone = new ArrayList<>();
        try (WorkflowEngine engine = new WorkflowEngine()) {
            Workflow<String, String> workflow =
                    Workflow.<String>named("undo")
                            .step("charge", (String in, StepContext ctx) -> "charge-1")
                            .compensate((output, ctx) -> undone.add(String.valueOf(output)))
                            .timeout(Duration.ofMillis(80))
                            .step(
                                    "reserve",
                                    (String in, StepContext ctx) -> {
                                        gate.await();
                                        return "never";
                                    })
                            .build();

            engine.run(workflow, "go");

            // The whole reason a bound is safe on a step with an undo: the run
            // gives up, and what came before it is rolled back.
            assertEquals(List.of("charge-1"), undone);
        } finally {
            gate.release();
        }
    }

    @Test
    void aNonPositiveTimeoutIsRejectedAtBuildTime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Workflow.<String>named("bad").timeout(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> Workflow.<String>named("bad").timeout(Duration.ofSeconds(-1)));
    }

    @Test
    void passingNullRemovesTheBoundAgain() {
        try (WorkflowEngine engine = new WorkflowEngine()) {
            Workflow<String, String> workflow =
                    Workflow.<String>named("cleared")
                            .timeout(Duration.ofMillis(50))
                            .timeout(null)
                            .step(
                                    "slow-but-allowed",
                                    (String in, StepContext ctx) -> {
                                        Thread.sleep(180);
                                        return "done";
                                    })
                            .build();

            assertTrue(engine.run(workflow, "go").succeeded());
        }
    }
}
