package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Reading a store the way an operator would: build it up by running some
 * workflows, then look at what it holds without touching the definitions again.
 */
class InspectorTest {

    private WorkflowEngine engine(WorkflowStore store) {
        return new WorkflowEngine(store, Sleeper.none(), new Random(42));
    }

    /**
     * A store carrying one of each interesting shape, listed and classified by
     * the same rule the engine's recovery scan uses.
     */
    @Test
    void listsAndClassifiesEveryRunAStoreHolds() {
        InMemoryStore store = new InMemoryStore();
        seedCompleted(store, "completed-1");
        seedFailed(store, "failed-1");
        seedInDoubt(store, "indoubt-1");
        seedResumable(store, "resumable-1");

        WorkflowInspector inspector = new WorkflowInspector(store);

        assertEquals(
                List.of("completed-1", "failed-1", "indoubt-1", "resumable-1"),
                inspector.runIds());

        Map<String, RunState> byId =
                inspector.list().stream()
                        .collect(
                                Collectors.toMap(
                                        WorkflowInspector.RunSummary::runId,
                                        WorkflowInspector.RunSummary::status));

        assertEquals(
                Map.of(
                        "completed-1", RunState.COMPLETED,
                        "failed-1", RunState.FAILED,
                        "indoubt-1", RunState.IN_DOUBT,
                        "resumable-1", RunState.RESUMABLE),
                byId);
    }

    @Test
    void describesACompletedRunWithItsStepsAndRecordedOutput() {
        InMemoryStore store = new InMemoryStore();
        seedCompleted(store, "completed-1");

        WorkflowInspector.RunDescription run =
                new WorkflowInspector(store).describe("completed-1").orElseThrow();

        assertEquals("completed-1", run.runId());
        assertEquals("checkout", run.workflowName().orElseThrow());
        assertEquals(RunState.COMPLETED, run.status());
        assertTrue(run.stepInDoubt().isEmpty());

        assertEquals(List.of("charge", "receipt"), stepNames(run));

        WorkflowInspector.StepView charge = stepNamed(run, "charge");
        assertEquals(StepStanding.DONE, charge.outcome());
        // The charge declared Codec.ofString(), so its output is recorded, and
        // for a string codec the encoded form is the value itself.
        assertEquals("order-4417-charged", charge.recordedOutput().orElseThrow());

        WorkflowInspector.StepView receipt = stepNamed(run, "receipt");
        assertEquals(StepStanding.DONE, receipt.outcome());
        assertTrue(
                receipt.recordedOutput().isEmpty(),
                "a step with no codec records no output to show");
    }

    @Test
    void describesAFailedRunWhoseCompensatedStepIsNoLongerStanding() {
        InMemoryStore store = new InMemoryStore();
        seedFailed(store, "failed-1");

        WorkflowInspector.RunDescription run =
                new WorkflowInspector(store).describe("failed-1").orElseThrow();

        assertEquals(RunState.FAILED, run.status());
        assertTrue(run.stepInDoubt().isEmpty());
        // The charge succeeded and was then compensated, so its effect no longer
        // stands, and the failing ship never took hold either.
        assertEquals(StepStanding.NOT_DONE, stepNamed(run, "charge").outcome());
        assertEquals(StepStanding.NOT_DONE, stepNamed(run, "ship").outcome());
    }

    @Test
    void describesARunThatDiedInsideARecordedStepAsInDoubtAtThatStep() {
        InMemoryStore store = new InMemoryStore();
        seedInDoubt(store, "indoubt-1");

        WorkflowInspector.RunDescription run =
                new WorkflowInspector(store).describe("indoubt-1").orElseThrow();

        assertEquals(RunState.IN_DOUBT, run.status());
        assertEquals("charge", run.stepInDoubt().orElseThrow());

        WorkflowInspector.StepView charge = stepNamed(run, "charge");
        assertEquals(StepStanding.ENTERED, charge.outcome());
        assertTrue(
                charge.recordedOutput().isEmpty(),
                "the step died before it could record an output");
    }

    @Test
    void aRunTheStoreDoesNotHoldDescribesToEmpty() {
        assertTrue(new WorkflowInspector(new InMemoryStore()).describe("never-ran").isEmpty());
    }

    private void seedCompleted(WorkflowStore store, String runId) {
        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> order + "-charged",
                                Codec.ofString())
                        .step("receipt", (String charged, StepContext ctx) -> charged + "-receipt")
                        .build();
        assertTrue(engine(store).run(checkout, "order-4417", runId).succeeded());
    }

    private void seedFailed(WorkflowStore store, String runId) {
        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> order + "-charged",
                                Codec.ofString())
                        .compensate((output, ctx) -> { })
                        .<String>step(
                                "ship",
                                (String charged, StepContext ctx) -> {
                                    throw new IllegalStateException("out of stock");
                                })
                        .build();
        assertTrue(engine(store).run(checkout, "order-4417", runId).failed());
    }

    private void seedInDoubt(WorkflowStore store, String runId) {
        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .<String>step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    throw new Crash();
                                },
                                Codec.ofString())
                        .step("receipt", (String charged, StepContext ctx) -> charged + "-receipt")
                        .build();
        assertThrows(Crash.class, () -> engine(store).run(checkout, "order-4417", runId));
    }

    /**
     * A run that died cleanly between steps: the recorded charge is complete and
     * the process fell over before ship even started, so nothing is in doubt and
     * a resume would simply carry on. The failing store is the stand-in for the
     * crash, so the test never leans on timing.
     */
    private void seedResumable(WorkflowStore store, String runId) {
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
                        .step("ship", (String charged, StepContext ctx) -> charged + "-shipped")
                        .build();

        DieBeforeStep dying = new DieBeforeStep(store, "ship");
        assertThrows(Crash.class, () -> engine(dying).run(checkout, "order-4417", runId));
        assertEquals(1, charges.get(), "the charge should have completed before the crash");
    }

    private static List<String> stepNames(WorkflowInspector.RunDescription run) {
        return run.steps().stream().map(WorkflowInspector.StepView::name).toList();
    }

    private static WorkflowInspector.StepView stepNamed(
            WorkflowInspector.RunDescription run, String name) {
        return run.steps().stream()
                .filter(step -> step.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no step " + name));
    }

    /** A dying process, as far as the engine can tell: an Error, so nothing catches it. */
    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /**
     * A store that throws the moment a given step is about to start, standing in
     * for a process that died between one step finishing and the next beginning.
     */
    private static final class DieBeforeStep implements WorkflowStore {

        private final WorkflowStore delegate;
        private final String stepName;

        DieBeforeStep(WorkflowStore delegate, String stepName) {
            this.delegate = delegate;
            this.stepName = stepName;
        }

        @Override
        public void append(WorkflowEvent event) {
            if (event.type() == EventType.STEP_STARTED && event.stepName().equals(stepName)) {
                throw new Crash();
            }
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
        public java.util.Optional<String> loadInput(String runId) {
            return delegate.loadInput(runId);
        }

        @Override
        public void saveOutput(String runId, String step, String encoded) {
            delegate.saveOutput(runId, step, encoded);
        }

        @Override
        public java.util.Optional<String> loadOutput(String runId, String step) {
            return delegate.loadOutput(runId, step);
        }
    }
}
