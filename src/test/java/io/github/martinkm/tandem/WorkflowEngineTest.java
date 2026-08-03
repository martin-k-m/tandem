package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowEngineTest {

    /** No real waiting, and a fixed seed so a jittered delay is reproducible. */
    private WorkflowEngine engine(WorkflowStore store) {
        return new WorkflowEngine(store, Sleeper.none(), new Random(42));
    }

    @Test
    void runsStepsInOrderPassingOutputAlong() {
        Workflow<String, Integer> workflow =
                Workflow.<String>named("parse")
                        .step("trim", (String raw, StepContext ctx) -> raw.trim())
                        .step("length", (String text, StepContext ctx) -> text.length())
                        .build();

        RunResult<Integer> result = engine(new InMemoryStore()).run(workflow, "  hello  ");

        assertTrue(result.succeeded());
        assertEquals(5, result.outputOrThrow());
        assertEquals(List.of("trim", "length"), workflow.stepNames());
    }

    @Test
    void retriesUntilTheStepSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        Workflow<String, String> workflow =
                Workflow.<String>named("flaky")
                        .step(
                                "unreliable",
                                (String input, StepContext ctx) -> {
                                    if (calls.incrementAndGet() < 3) {
                                        throw new IllegalStateException("not yet");
                                    }
                                    return input + "!";
                                },
                                RetryPolicy.exponential(5, Duration.ofMillis(10)))
                        .build();

        RunResult<String> result = engine(new InMemoryStore()).run(workflow, "ok");

        assertTrue(result.succeeded());
        assertEquals("ok!", result.outputOrThrow());
        assertEquals(3, calls.get());
        assertEquals(2, result.eventsOfType(EventType.STEP_RETRYING).size());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        Workflow<String, String> workflow =
                Workflow.<String>named("doomed")
                        // The lambda only throws, so there is no return value for
                        // javac to infer the step's output type from.
                        .<String>step(
                                "always-fails",
                                (String input, StepContext ctx) -> {
                                    calls.incrementAndGet();
                                    throw new IllegalStateException("nope");
                                },
                                RetryPolicy.fixed(3, Duration.ofMillis(1)))
                        .build();

        RunResult<String> result = engine(new InMemoryStore()).run(workflow, "x");

        assertTrue(result.failed());
        assertEquals(3, calls.get());
        assertInstanceOf(IllegalStateException.class, result.failure());
        assertTrue(result.output().isEmpty());
        assertEquals(1, result.eventsOfType(EventType.STEP_FAILED).size());
        assertEquals(1, result.eventsOfType(EventType.RUN_FAILED).size());
    }

    @Test
    void doesNotRetryWhenThePolicyIsNone() {
        AtomicInteger calls = new AtomicInteger();
        Workflow<String, String> workflow =
                Workflow.<String>named("strict")
                        .<String>step(
                                "once",
                                (String input, StepContext ctx) -> {
                                    calls.incrementAndGet();
                                    throw new IllegalStateException("nope");
                                })
                        .build();

        assertTrue(engine(new InMemoryStore()).run(workflow, "x").failed());
        assertEquals(1, calls.get());
    }

    @Test
    void compensatesCompletedStepsInReverseOrder() {
        List<String> undone = new ArrayList<>();

        Workflow<String, String> workflow =
                Workflow.<String>named("saga")
                        .step("charge", (String input, StepContext ctx) -> input + "-charged")
                        .compensate((output, ctx) -> undone.add("charge"))
                        .step("reserve", (String input, StepContext ctx) -> input + "-reserved")
                        .compensate((output, ctx) -> undone.add("reserve"))
                        .<String>step(
                                "ship",
                                (String input, StepContext ctx) -> {
                                    throw new IllegalStateException("out of stock");
                                })
                        .build();

        RunResult<String> result = engine(new InMemoryStore()).run(workflow, "order");

        assertTrue(result.failed());
        assertEquals(List.of("reserve", "charge"), undone);
        assertEquals(2, result.eventsOfType(EventType.STEP_COMPENSATED).size());
    }

    @Test
    void aFailingCompensationDoesNotStopTheRest() {
        List<String> undone = new ArrayList<>();

        Workflow<String, String> workflow =
                Workflow.<String>named("saga")
                        .step("first", (String input, StepContext ctx) -> input)
                        .compensate((output, ctx) -> undone.add("first"))
                        .step("second", (String input, StepContext ctx) -> input)
                        .compensate(
                                (output, ctx) -> {
                                    throw new IllegalStateException("undo failed");
                                })
                        .<String>step(
                                "third",
                                (String input, StepContext ctx) -> {
                                    throw new IllegalStateException("boom");
                                })
                        .build();

        engine(new InMemoryStore()).run(workflow, "x");

        // "second" threw while undoing, but "first" was still undone.
        assertEquals(List.of("first"), undone);
    }

    @Test
    void resumeReplaysRecordedStepsAndReRunsTheRest() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger sideEffects = new AtomicInteger();
        AtomicInteger pureCalls = new AtomicInteger();
        AtomicInteger failLater = new AtomicInteger();

        Workflow<String, String> workflow =
                Workflow.<String>named("resumable")
                        .step(
                                "side-effect",
                                (String input, StepContext ctx) -> {
                                    sideEffects.incrementAndGet();
                                    return input + "-done";
                                },
                                Codec.ofString())
                        .step(
                                "pure",
                                (String input, StepContext ctx) -> {
                                    pureCalls.incrementAndGet();
                                    return input;
                                })
                        .step(
                                "unstable",
                                (String input, StepContext ctx) -> {
                                    if (failLater.incrementAndGet() == 1) {
                                        throw new IllegalStateException("first time fails");
                                    }
                                    return input + "-finished";
                                })
                        .build();

        RunResult<String> first = engine(store).run(workflow, "job", "run-1");
        assertTrue(first.failed());
        assertEquals(1, sideEffects.get());

        RunResult<String> second = engine(store).run(workflow, "job", "run-1");

        assertTrue(second.succeeded());
        assertEquals("job-done-finished", second.outputOrThrow());
        // The recorded step was replayed rather than repeated.
        assertEquals(1, sideEffects.get());
        assertEquals(1, second.eventsOfType(EventType.STEP_REPLAYED).size());
        // The step without a codec ran again, which is the documented behaviour.
        assertEquals(2, pureCalls.get());
        assertEquals(1, second.eventsOfType(EventType.RUN_RESUMED).size());
    }

    /**
     * The process stops inside a recorded step, after the side effect and before
     * anything about it reaches the store. The log holds the started event and
     * nothing else, which is exactly the case the engine must not resolve by
     * running the step again.
     */
    @Test
    void aResumeRefusesToRepeatARecordedStepThatCrashedMidway() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger charges = new AtomicInteger();

        Workflow<String, String> workflow =
                Workflow.<String>named("checkout")
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    if (charges.incrementAndGet() == 1) {
                                        // An Error escapes the retry loop the
                                        // way a dying process escapes it.
                                        throw new Crash();
                                    }
                                    return order + "-charged";
                                },
                                Codec.ofString())
                        .build();

        assertThrows(Crash.class, () -> engine(store).run(workflow, "order", "run-1"));
        assertEquals(1, charges.get());
        assertTrue(store.loadOutput("run-1", "charge").isEmpty());

        RunResult<String> resumed = engine(store).run(workflow, "order", "run-1");

        assertEquals(1, charges.get(), "the resume charged the card a second time");
        assertTrue(resumed.failed());
        assertInstanceOf(StepInDoubtException.class, resumed.failure());
        assertEquals("charge", ((StepInDoubtException) resumed.failure()).stepName());
        assertEquals(1, resumed.eventsOfType(EventType.STEP_IN_DOUBT).size());
    }

    /**
     * The same thing across a real restart: two engines, two stores, one
     * directory, and nothing shared in memory but the count of side effects.
     */
    @Test
    void theIntentRecordSurvivesARestart(@TempDir Path root) {
        AtomicInteger charges = new AtomicInteger();

        Workflow<String, String> workflow =
                Workflow.<String>named("checkout")
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    if (charges.incrementAndGet() == 1) {
                                        throw new Crash();
                                    }
                                    return order + "-charged";
                                },
                                Codec.ofString())
                        .build();

        assertThrows(
                Crash.class, () -> engine(new FileStore(root)).run(workflow, "order", "run-1"));

        RunResult<String> resumed = engine(new FileStore(root)).run(workflow, "order", "run-1");

        assertEquals(1, charges.get(), "the resume charged the card a second time");
        assertInstanceOf(StepInDoubtException.class, resumed.failure());
    }

    /**
     * The narrower window: the step returned, and the store could not record it.
     * The run must not leave a succeeded event behind, or the next resume would
     * read the log as settled and run the step again.
     */
    @Test
    void aStepWhoseOutputCannotBeRecordedIsNotLoggedAsSucceeded() {
        RefusingOutputStore store = new RefusingOutputStore();
        AtomicInteger charges = new AtomicInteger();

        Workflow<String, String> workflow =
                Workflow.<String>named("checkout")
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    charges.incrementAndGet();
                                    return order + "-charged";
                                },
                                Codec.ofString())
                        .build();

        assertThrows(
                IllegalStateException.class, () -> engine(store).run(workflow, "order", "run-1"));
        assertEquals(1, charges.get());
        assertTrue(
                store.eventsFor("run-1").stream()
                        .noneMatch(event -> event.type() == EventType.STEP_SUCCEEDED),
                "a step whose output was never written must not look succeeded");

        store.acceptOutputs();
        RunResult<String> resumed = engine(store).run(workflow, "order", "run-1");

        assertEquals(1, charges.get(), "the resume charged the card a second time");
        assertInstanceOf(StepInDoubtException.class, resumed.failure());
    }

    @Test
    void confirmingCompletionLetsTheRunCarryOnWithoutRepeatingTheStep() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger charges = new AtomicInteger();

        Workflow<String, String> workflow =
                Workflow.<String>named("checkout")
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    if (charges.incrementAndGet() == 1) {
                                        throw new Crash();
                                    }
                                    return order + "-charged";
                                },
                                Codec.ofString())
                        .step("receipt", (String charged, StepContext ctx) -> charged + "-receipt")
                        .build();

        assertThrows(Crash.class, () -> engine(store).run(workflow, "order", "run-1"));

        // The operator looked at the payment provider and found the charge.
        WorkflowEngine engine = engine(store);
        engine.confirmCompleted("run-1", "charge", "order-charged", Codec.ofString());
        RunResult<String> resumed = engine.run(workflow, "order", "run-1");

        assertTrue(resumed.succeeded());
        assertEquals("order-charged-receipt", resumed.outputOrThrow());
        assertEquals(1, charges.get());
        assertEquals(1, resumed.eventsOfType(EventType.STEP_REPLAYED).size());
    }

    @Test
    void confirmingNonCompletionLetsTheStepRunAgain() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger charges = new AtomicInteger();

        Workflow<String, String> workflow =
                Workflow.<String>named("checkout")
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    if (charges.incrementAndGet() == 1) {
                                        throw new Crash();
                                    }
                                    return order + "-charged";
                                },
                                Codec.ofString())
                        .build();

        assertThrows(Crash.class, () -> engine(store).run(workflow, "order", "run-1"));

        // The operator looked and found no charge at all.
        WorkflowEngine engine = engine(store);
        engine.confirmNotCompleted("run-1", "charge");
        RunResult<String> resumed = engine.run(workflow, "order", "run-1");

        assertTrue(resumed.succeeded());
        assertEquals("order-charged", resumed.outputOrThrow());
        assertEquals(2, charges.get());
        assertEquals("order-charged", store.loadOutput("run-1", "charge").orElseThrow());
    }

    /**
     * The other side of the check. A step whose attempts all threw is settled in
     * the log, so there is no doubt to raise and the resume must still run it,
     * exactly as a retry within one run would.
     */
    @Test
    void aRecordedStepThatFailedCleanlyStillRunsAgainOnResume() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger calls = new AtomicInteger();

        Workflow<String, String> workflow =
                Workflow.<String>named("checkout")
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    if (calls.incrementAndGet() == 1) {
                                        throw new IllegalStateException("card declined");
                                    }
                                    return order + "-charged";
                                },
                                Codec.ofString())
                        .build();

        assertTrue(engine(store).run(workflow, "order", "run-1").failed());

        RunResult<String> resumed = engine(store).run(workflow, "order", "run-1");

        assertTrue(resumed.succeeded());
        assertEquals("order-charged", resumed.outputOrThrow());
        assertEquals(2, calls.get());
        assertTrue(resumed.eventsOfType(EventType.STEP_IN_DOUBT).isEmpty());
    }

    /**
     * The doubt only applies to steps declared replayable. A step without a codec
     * was declared safe to repeat, so a crash inside one is not a question.
     */
    @Test
    void aStepWithoutACodecIsStillRepeatedAfterACrash() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger calls = new AtomicInteger();

        Workflow<String, String> workflow =
                Workflow.<String>named("pure")
                        .step(
                                "format",
                                (String input, StepContext ctx) -> {
                                    if (calls.incrementAndGet() == 1) {
                                        throw new Crash();
                                    }
                                    return "receipt-" + input;
                                })
                        .build();

        assertThrows(Crash.class, () -> engine(store).run(workflow, "4417", "run-1"));

        RunResult<String> resumed = engine(store).run(workflow, "4417", "run-1");

        assertTrue(resumed.succeeded());
        assertEquals("receipt-4417", resumed.outputOrThrow());
        assertEquals(2, calls.get());
    }

    @Test
    void stepContextReportsTheAttemptNumber() {
        List<Integer> attempts = new ArrayList<>();
        Workflow<String, String> workflow =
                Workflow.<String>named("counting")
                        .step(
                                "watch",
                                (String input, StepContext ctx) -> {
                                    attempts.add(ctx.attempt());
                                    if (ctx.attempt() < 3) {
                                        throw new IllegalStateException("again");
                                    }
                                    return input;
                                },
                                RetryPolicy.fixed(5, Duration.ofMillis(1)))
                        .build();

        assertTrue(engine(new InMemoryStore()).run(workflow, "x").succeeded());
        assertEquals(List.of(1, 2, 3), attempts);
    }

    @Test
    void listenersSeeEventsAndCannotBreakTheRun() {
        List<EventType> seen = new ArrayList<>();
        Workflow<String, String> workflow =
                Workflow.<String>named("observed")
                        .step("noop", (String input, StepContext ctx) -> input)
                        .build();

        RunResult<String> result =
                engine(new InMemoryStore())
                        .listener(event -> seen.add(event.type()))
                        .listener(
                                event -> {
                                    throw new IllegalStateException("listener is broken");
                                })
                        .run(workflow, "x");

        assertTrue(result.succeeded());
        assertTrue(seen.contains(EventType.RUN_STARTED));
        assertTrue(seen.contains(EventType.STEP_SUCCEEDED));
        assertTrue(seen.contains(EventType.RUN_SUCCEEDED));
    }

    @Test
    void aWorkflowNeedsAtLeastOneStep() {
        assertThrows(IllegalStateException.class, () -> Workflow.<String>named("empty").build());
    }

    @Test
    void duplicateStepNamesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Workflow.<String>named("dupes")
                                .step("same", (String input, StepContext ctx) -> input)
                                .step("same", (String input, StepContext ctx) -> input));
    }

    @Test
    void compensateWithoutAStepIsRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> Workflow.<String>named("bad").compensate((output, ctx) -> {}));
    }

    @Test
    void eachRunGetsItsOwnId() {
        Workflow<String, String> workflow =
                Workflow.<String>named("ids")
                        .step("noop", (String input, StepContext ctx) -> input)
                        .build();
        WorkflowEngine engine = engine(new InMemoryStore());

        assertFalse(engine.run(workflow, "a").runId().equals(engine.run(workflow, "b").runId()));
    }

    /** A dying process, as far as the engine can tell: not an Exception, so nothing catches it. */
    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** A store that cannot write outputs until told it can. */
    private static final class RefusingOutputStore implements WorkflowStore {

        private final InMemoryStore delegate = new InMemoryStore();
        private boolean accepting;

        void acceptOutputs() {
            accepting = true;
        }

        @Override
        public void append(WorkflowEvent event) {
            delegate.append(event);
        }

        @Override
        public List<WorkflowEvent> eventsFor(String runId) {
            return delegate.eventsFor(runId);
        }

        @Override
        public List<String> listRuns() {
            return delegate.listRuns();
        }

        @Override
        public void saveInput(String runId, String encoded) {
            delegate.saveInput(runId, encoded);
        }

        @Override
        public Optional<String> loadInput(String runId) {
            return delegate.loadInput(runId);
        }

        @Override
        public void saveOutput(String runId, String stepName, String encoded) {
            if (!accepting) {
                throw new IllegalStateException("the store is down");
            }
            delegate.saveOutput(runId, stepName, encoded);
        }

        @Override
        public Optional<String> loadOutput(String runId, String stepName) {
            return delegate.loadOutput(runId, stepName);
        }
    }
}
