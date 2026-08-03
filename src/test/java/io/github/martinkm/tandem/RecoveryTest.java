package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The recovery path, exercised the way an application uses it: crash a run, then
 * come back holding nothing but the store directory and the definition.
 */
class RecoveryTest {

    private WorkflowEngine engine(WorkflowStore store) {
        return new WorkflowEngine(store, Sleeper.none(), new Random(42));
    }

    /**
     * The whole point, end to end. The first engine dies mid-run and everything
     * it knew goes with it; the second is given the same directory and the same
     * definition, and finds the run, its state and its input by itself.
     *
     * <p>The run id is deliberately one that cannot be a directory name. It has
     * to come back out of the store as it went in, or the resume would read an
     * empty history and charge the card again.
     */
    @Test
    void findsAndResumesWhatACrashLeftBehind(@TempDir Path root) {
        AtomicInteger charges = new AtomicInteger();
        AtomicInteger shipments = new AtomicInteger();
        Workflow<String, String> checkout = checkout(charges, shipments);

        assertThrows(
                Crash.class,
                () -> engine(new FileStore(root)).run(checkout, "order-4417", "order/4417 #2"));

        // The restart. Nothing carries over but the directory.
        WorkflowEngine restarted = engine(new FileStore(root));
        List<RecoverableRun<String, String>> found = restarted.recoverable(checkout);

        assertEquals(1, found.size());
        RecoverableRun<String, String> run = found.get(0);
        assertEquals("order/4417 #2", run.runId(), "the run id did not survive the round trip");
        assertEquals(RunState.RESUMABLE, run.state());
        assertEquals("order-4417", run.input().orElseThrow(), "the input was not recovered");

        RunResult<String> resumed = restarted.resume(run);

        assertTrue(resumed.succeeded());
        assertEquals("order-4417-charged-shipped", resumed.outputOrThrow());
        assertEquals(1, charges.get(), "the recorded charge was repeated rather than replayed");
        assertEquals(2, shipments.get(), "the step without a codec should have run again");
    }

    @Test
    void classifiesARunThatDiedInsideARecordedStepAsInDoubt() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger charges = new AtomicInteger();
        Workflow<String, String> checkout = crashingCharge(charges);

        assertThrows(Crash.class, () -> engine(store).run(checkout, "order-4417", "run-1"));

        WorkflowEngine engine = engine(store);
        RecoverableRun<String, String> run = engine.recoverable(checkout, "run-1").orElseThrow();

        assertEquals(RunState.IN_DOUBT, run.state());
        assertEquals("charge", run.stepInDoubt().orElseThrow());

        RunResult<String> refused = engine.resume(run);

        assertTrue(refused.failed());
        assertInstanceOf(StepInDoubtException.class, refused.failure());
        assertEquals(1, charges.get(), "a run in doubt must not be resolved by running the step");
    }

    @Test
    void settlingTheDoubtMakesTheRunResumableAgain() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger charges = new AtomicInteger();
        Workflow<String, String> checkout = crashingCharge(charges);

        assertThrows(Crash.class, () -> engine(store).run(checkout, "order-4417", "run-1"));

        // The operator looked at the payment provider and found the charge.
        WorkflowEngine engine = engine(store);
        engine.confirmCompleted("run-1", "charge", "order-4417-charged", Codec.ofString());

        RecoverableRun<String, String> settled =
                engine.recoverable(checkout, "run-1").orElseThrow();
        assertEquals(RunState.RESUMABLE, settled.state());
        assertTrue(settled.stepInDoubt().isEmpty());

        RunResult<String> resumed = engine.resume(settled);

        assertTrue(resumed.succeeded());
        assertEquals("order-4417-charged-receipt", resumed.outputOrThrow());
        assertEquals(1, charges.get());
    }

    @Test
    void aCompletedRunIsClassifiedAsSuchAndNotResumed() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger receipts = new AtomicInteger();
        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .step("charge", (String order, StepContext ctx) -> order, Codec.ofString())
                        .step(
                                "receipt",
                                (String charged, StepContext ctx) -> {
                                    receipts.incrementAndGet();
                                    return charged + "-receipt";
                                })
                        .build();

        WorkflowEngine engine = engine(store);
        assertTrue(engine.run(checkout, "order-4417", "run-1").succeeded());

        RecoverableRun<String, String> run = engine.recoverable(checkout, "run-1").orElseThrow();
        assertEquals(RunState.COMPLETED, run.state());

        TandemException refused = assertThrows(TandemException.class, () -> engine.resume(run));

        assertTrue(refused.getMessage().contains("already succeeded"));
        assertEquals(1, receipts.get(), "resuming a finished run repeated a step");
    }

    /**
     * The reviewer's case. A failed run has already had its compensations run, so
     * the recorded output of a compensated step describes something that no
     * longer exists. Replaying it would carry a refunded charge forward into a
     * retry, and the log says plainly that it was undone.
     */
    @Test
    void aResumeDoesNotReplayAStepWhoseOutputWasCompensated() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger charges = new AtomicInteger();
        AtomicInteger refunds = new AtomicInteger();
        AtomicInteger shipments = new AtomicInteger();

        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    charges.incrementAndGet();
                                    return order + "-charged";
                                },
                                Codec.ofString())
                        .compensate((output, ctx) -> refunds.incrementAndGet())
                        .step(
                                "ship",
                                (String charged, StepContext ctx) -> {
                                    if (shipments.incrementAndGet() == 1) {
                                        throw new IllegalStateException("out of stock");
                                    }
                                    return charged + "-shipped";
                                })
                        .build();

        WorkflowEngine engine = engine(store);
        assertTrue(engine.run(checkout, "order-4417", "run-1").failed());
        assertEquals(1, charges.get());
        assertEquals(1, refunds.get());

        RecoverableRun<String, String> run = engine.recoverable(checkout, "run-1").orElseThrow();
        assertEquals(RunState.FAILED, run.state());

        RunResult<String> resumed = engine.resume(run);

        assertTrue(resumed.succeeded());
        assertEquals("order-4417-charged-shipped", resumed.outputOrThrow());
        assertEquals(2, charges.get(), "the refunded charge was replayed instead of remade");
        assertEquals(1, refunds.get());
        assertTrue(resumed.eventsOfType(EventType.STEP_REPLAYED).isEmpty());
    }

    /**
     * A compensation that threw leaves nobody able to say whether the step it was
     * undoing still stands, and the recorded output cannot settle it because it
     * was written before the compensation ever ran.
     */
    @Test
    void aCompensationThatThrewLeavesTheStepInDoubt() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger charges = new AtomicInteger();
        AtomicInteger shipments = new AtomicInteger();

        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    charges.incrementAndGet();
                                    return order + "-charged";
                                },
                                Codec.ofString())
                        .compensate(
                                (output, ctx) -> {
                                    throw new IllegalStateException("the refund API is down");
                                })
                        .step(
                                "ship",
                                (String charged, StepContext ctx) -> {
                                    if (shipments.incrementAndGet() == 1) {
                                        throw new IllegalStateException("out of stock");
                                    }
                                    return charged + "-shipped";
                                })
                        .build();

        WorkflowEngine engine = engine(store);
        RunResult<String> first = engine.run(checkout, "order-4417", "run-1");

        assertTrue(first.failed());
        assertEquals(1, first.eventsOfType(EventType.STEP_COMPENSATION_FAILED).size());

        RecoverableRun<String, String> run = engine.recoverable(checkout, "run-1").orElseThrow();
        assertEquals(RunState.IN_DOUBT, run.state());
        assertEquals("charge", run.stepInDoubt().orElseThrow());

        RunResult<String> refused = engine.resume(run);
        assertInstanceOf(StepInDoubtException.class, refused.failure());
        assertEquals(1, charges.get());

        // The operator checked: the refund did go through after all.
        engine.confirmNotCompleted("run-1", "charge");
        RunResult<String> resumed =
                engine.resume(engine.recoverable(checkout, "run-1").orElseThrow());

        assertTrue(resumed.succeeded());
        assertEquals(2, charges.get(), "a step confirmed undone has to run again");
    }

    @Test
    void aWorkflowThatDoesNotRecordItsInputIsResumedWithOneYouSupply(@TempDir Path root) {
        AtomicInteger charges = new AtomicInteger();
        AtomicInteger shipments = new AtomicInteger();
        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    charges.incrementAndGet();
                                    return order + "-charged";
                                },
                                Codec.ofString())
                        .step(
                                "ship",
                                (String charged, StepContext ctx) -> {
                                    if (shipments.incrementAndGet() == 1) {
                                        throw new Crash();
                                    }
                                    return charged + "-shipped";
                                })
                        .build();

        assertThrows(Crash.class, () -> engine(new FileStore(root)).run(checkout, "order-4417", "run-1"));

        WorkflowEngine restarted = engine(new FileStore(root));
        RecoverableRun<String, String> run = restarted.recoverable(checkout, "run-1").orElseThrow();

        assertEquals(RunState.RESUMABLE, run.state());
        assertTrue(run.input().isEmpty(), "no codec was declared, so nothing should be recorded");

        TandemException refused = assertThrows(TandemException.class, () -> restarted.resume(run));
        assertTrue(refused.getMessage().contains("recorded no input"));

        RunResult<String> resumed = restarted.resume(run, "order-4417");

        assertTrue(resumed.succeeded());
        assertEquals("order-4417-charged-shipped", resumed.outputOrThrow());
        assertEquals(1, charges.get());
    }

    /**
     * The window the input write leaves open: {@code RUN_STARTED} is in the log
     * and the input is not, because the store stopped accepting between the two.
     * The run is still found and still resumable, by being handed the input once.
     */
    @Test
    void anInputThatNeverReachedTheStoreIsRecordedWhenItIsSuppliedToAResume() {
        RefusingInputStore store = new RefusingInputStore();
        AtomicInteger charges = new AtomicInteger();
        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    charges.incrementAndGet();
                                    return order + "-charged";
                                },
                                Codec.ofString())
                        .build();

        WorkflowEngine engine = engine(store);
        assertThrows(
                IllegalStateException.class, () -> engine.run(checkout, "order-4417", "run-1"));
        assertEquals(0, charges.get(), "the store failed before any step ran");

        store.acceptInputs();
        RecoverableRun<String, String> run = engine.recoverable(checkout, "run-1").orElseThrow();
        assertTrue(run.input().isEmpty());

        RunResult<String> resumed = engine.resume(run, "order-4417");

        assertTrue(resumed.succeeded());
        assertEquals("order-4417", store.loadInput("run-1").orElseThrow(), "the supplied input was not kept");
    }

    @Test
    void theInputComesBackThroughTheCodecTheWorkflowDeclared(@TempDir Path root) {
        Codec<Order> orders =
                new Codec<>() {
                    @Override
                    public String encode(Order order) {
                        return order.id() + ":" + order.total();
                    }

                    @Override
                    public Order decode(String text) {
                        String[] parts = text.split(":", 2);
                        return new Order(parts[0], Integer.parseInt(parts[1]));
                    }
                };

        Workflow<Order, String> checkout =
                Workflow.<Order>named("checkout")
                        .input(orders)
                        .<String>step(
                                "charge",
                                (Order order, StepContext ctx) -> {
                                    throw new Crash();
                                },
                                Codec.ofString())
                        .build();

        assertThrows(
                Crash.class,
                () -> engine(new FileStore(root)).run(checkout, new Order("4417", 900), "run-1"));

        RecoverableRun<Order, String> run =
                engine(new FileStore(root)).recoverable(checkout, "run-1").orElseThrow();

        assertEquals(new Order("4417", 900), run.input().orElseThrow());
    }

    @Test
    void onlyRunsOfTheWorkflowYouAskAboutComeBack() {
        InMemoryStore store = new InMemoryStore();
        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .step("charge", (String order, StepContext ctx) -> order, Codec.ofString())
                        .build();
        Workflow<String, String> refund =
                Workflow.<String>named("refund")
                        .input(Codec.ofString())
                        .step("credit", (String order, StepContext ctx) -> order, Codec.ofString())
                        .build();

        WorkflowEngine engine = engine(store);
        engine.run(checkout, "order-4417", "checkout-1");
        engine.run(refund, "order-9001", "refund-1");

        assertEquals(List.of("checkout-1"), idsOf(engine.recoverable(checkout)));
        assertEquals(List.of("refund-1"), idsOf(engine.recoverable(refund)));
        // Asking about one workflow's run through another definition is a miss,
        // not somebody else's run wearing the wrong shape.
        assertTrue(engine.recoverable(checkout, "refund-1").isEmpty());
        assertTrue(engine.recoverable(checkout, "never-ran").isEmpty());
    }

    @Test
    void aRunReportsWhenItStartedAndWhenItLastMoved() {
        InMemoryStore store = new InMemoryStore();
        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .step("charge", (String order, StepContext ctx) -> order, Codec.ofString())
                        .build();

        WorkflowEngine engine = engine(store);
        engine.run(checkout, "order-4417", "run-1");

        RecoverableRun<String, String> run = engine.recoverable(checkout, "run-1").orElseThrow();

        assertEquals(store.eventsFor("run-1").get(0).at(), run.startedAt());
        assertEquals(
                store.eventsFor("run-1").get(store.eventsFor("run-1").size() - 1).at(),
                run.lastEventAt());
        assertEquals(store.eventsFor("run-1"), run.events());
    }

    private Workflow<String, String> checkout(AtomicInteger charges, AtomicInteger shipments) {
        return Workflow.<String>named("checkout")
                .input(Codec.ofString())
                .step(
                        "charge",
                        (String order, StepContext ctx) -> {
                            charges.incrementAndGet();
                            return order + "-charged";
                        },
                        Codec.ofString())
                .step(
                        "ship",
                        (String charged, StepContext ctx) -> {
                            if (shipments.incrementAndGet() == 1) {
                                throw new Crash();
                            }
                            return charged + "-shipped";
                        })
                .build();
    }

    /** A workflow whose recorded step dies the first time it is called. */
    private Workflow<String, String> crashingCharge(AtomicInteger charges) {
        return Workflow.<String>named("checkout")
                .input(Codec.ofString())
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
    }

    private static List<String> idsOf(List<? extends RecoverableRun<?, ?>> runs) {
        return runs.stream().map(RecoverableRun::runId).toList();
    }

    private record Order(String id, int total) {}

    /** A dying process, as far as the engine can tell: not an Exception, so nothing catches it. */
    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** A store that cannot write inputs until told it can. */
    private static final class RefusingInputStore implements WorkflowStore {

        private final InMemoryStore delegate = new InMemoryStore();
        private boolean accepting;

        void acceptInputs() {
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
            if (!accepting) {
                throw new IllegalStateException("the store is down");
            }
            delegate.saveInput(runId, encoded);
        }

        @Override
        public Optional<String> loadInput(String runId) {
            return delegate.loadInput(runId);
        }

        @Override
        public void saveOutput(String runId, String stepName, String encoded) {
            delegate.saveOutput(runId, stepName, encoded);
        }

        @Override
        public Optional<String> loadOutput(String runId, String stepName) {
            return delegate.loadOutput(runId, stepName);
        }
    }
}
