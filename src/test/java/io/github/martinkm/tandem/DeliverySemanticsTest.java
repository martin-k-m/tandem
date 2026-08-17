package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What Tandem actually delivers when a process dies between a step's side effect
 * and the log line that records it.
 *
 * <p>This is the claim a durable workflow engine is most often wrong about, so
 * it is settled here by demonstration rather than by reading the code. The crash
 * is a real one: a child JVM performs the side effect and then calls
 * {@link Runtime#halt}, which ends the process immediately, without shutdown
 * hooks and without flushing anything the runtime had not already written. The
 * parent then recovers from the same directory and counts how many times the
 * side effect happened.
 *
 * <p>The side effect is a line appended to a file, so counting repeats is
 * counting lines. It is the cheapest stand-in for a charge that a test can make,
 * and it has the property that matters: doing it twice is visibly different from
 * doing it once.
 *
 * <p>The answers, which the four tests below establish:
 *
 * <ul>
 *   <li>A step without a {@code Codec} is <b>at-least-once</b>. It is repeated
 *       after a crash. A run that already succeeded is refused rather than
 *       resumed, so finishing cleanly is not one of the ways it repeats.
 *   <li>A step with a {@code Codec} is never repeated silently. A crash before
 *       its output was recorded stops the resume with
 *       {@link StepInDoubtException} and leaves the decision to the caller.
 *   <li>The caller's decision is what settles it, and both answers are
 *       available: {@code confirmCompleted} finishes the run without repeating
 *       the step, {@code confirmNotCompleted} runs it again.
 * </ul>
 *
 * <p>So Tandem does not deliver exactly-once, and nothing that calls a remote
 * system from inside a JVM can. It delivers at-least-once for steps you declared
 * safe to repeat, and for the rest it converts a silent repeat into a visible
 * question.
 */
class DeliverySemanticsTest {

    private static final String RUN_ID = "delivery-1";
    private static final String INPUT = "order";

    // ------------------------------------------------------------ the workflow

    /**
     * The definition both sides use. {@code recorded} chooses whether the step
     * that crashes carries a codec, which is the only difference between the two
     * halves of this test and the whole of the durability model.
     *
     * <p>The step body is passed in because the child's version halts the JVM
     * and the parent's does not. That asymmetry is not a trick: a restarted
     * process runs the ordinary code, and only the log tells it what happened
     * last time.
     */
    private static Workflow<String, String> workflow(Path effects, boolean recorded, boolean crash) {
        Step<String, String> effect =
                (String in, StepContext ctx) -> {
                    recordSideEffect(effects);
                    if (crash) {
                        // A real death, at the exact instant the model is about
                        // to be tested: the side effect is done and nothing has
                        // recorded it. halt, not exit, so no shutdown hook runs.
                        Runtime.getRuntime().halt(9);
                    }
                    return in + "-charged";
                };

        WorkflowBuilder<String, String> builder = Workflow.<String>named("delivery");
        WorkflowBuilder<String, String> afterEffect =
                recorded
                        ? builder.step("effect", effect, Codec.ofString())
                        : builder.step("effect", effect);
        return afterEffect
                .step("after", (String in, StepContext ctx) -> in + "-receipt", Codec.ofString())
                .build();
    }

    /** The side effect: one line per occurrence, so repeats are countable. */
    private static void recordSideEffect(Path effects) {
        try {
            Files.writeString(
                    effects,
                    "charged" + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("could not record the side effect", e);
        }
    }

    private static long sideEffectCount(Path effects) throws IOException {
        if (!Files.exists(effects)) {
            return 0;
        }
        try (var lines = Files.lines(effects, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        }
    }

    // ------------------------------------------------------------- the crash

    /**
     * Runs the workflow in a child JVM that dies inside the step, and returns
     * once it is gone.
     *
     * <p>The classpath is taken from where these classes were actually loaded
     * from, rather than from {@code java.class.path}, so this works the same
     * under Maven and under a console launcher started with {@code -jar}.
     */
    private static void crashInsideTheStep(Path root, Path effects, boolean recorded)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath =
                codeSourceOf(WorkflowEngine.class)
                        + File.pathSeparator
                        + codeSourceOf(DeliverySemanticsTest.class);

        Process child =
                new ProcessBuilder(
                                java,
                                "-cp",
                                classpath,
                                DeliverySemanticsTest.class.getName(),
                                root.toString(),
                                effects.toString(),
                                Boolean.toString(recorded))
                        .redirectErrorStream(true)
                        .start();

        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child JVM did not finish");
        assertEquals(
                9,
                child.exitValue(),
                "the child was supposed to halt inside the step, but exited normally");
    }

    private static String codeSourceOf(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /** The child JVM. Runs the workflow, and never returns from the first step. */
    public static void main(String[] args) {
        Path root = Path.of(args[0]);
        Path effects = Path.of(args[1]);
        boolean recorded = Boolean.parseBoolean(args[2]);

        WorkflowEngine engine = new WorkflowEngine(new FileStore(root));
        engine.run(workflow(effects, recorded, true), INPUT, RUN_ID);

        // Unreachable: the step halts. Exiting with a different code makes that
        // a failed assertion in the parent rather than a silent pass.
        System.exit(0);
    }

    // -------------------------------------------------------------- the tests

    /**
     * A step without a codec is repeated after a crash. This is at-least-once,
     * stated as plainly as a test can state it: the side effect happened once
     * before the crash and happens a second time on the way through.
     */
    @Test
    void aStepWithoutACodecRunsAgainAfterACrash(@TempDir Path root) throws Exception {
        Path effects = root.resolve("effects.log");

        crashInsideTheStep(root.resolve("store"), effects, false);
        assertEquals(1, sideEffectCount(effects), "the child should have charged once");

        WorkflowEngine engine = new WorkflowEngine(new FileStore(root.resolve("store")));
        RunResult<String> resumed = engine.run(workflow(effects, false, false), INPUT, RUN_ID);

        assertTrue(resumed.succeeded(), "the resume should have completed the run");
        assertEquals(
                2,
                sideEffectCount(effects),
                "a step with no codec is repeated on resume: that is at-least-once");
    }

    /**
     * A step with a codec is not repeated. The crash left a started event with no
     * outcome and no recorded output, which is the one shape the log cannot
     * settle, so the resume stops instead of guessing.
     */
    @Test
    void aStepWithACodecStopsRatherThanRunningAgain(@TempDir Path root) throws Exception {
        Path effects = root.resolve("effects.log");

        crashInsideTheStep(root.resolve("store"), effects, true);
        assertEquals(1, sideEffectCount(effects), "the child should have charged once");

        WorkflowEngine engine = new WorkflowEngine(new FileStore(root.resolve("store")));
        RunResult<String> resumed = engine.run(workflow(effects, true, false), INPUT, RUN_ID);

        assertTrue(resumed.failed(), "a step in doubt should fail the resume");
        StepInDoubtException doubt =
                assertInstanceOf(StepInDoubtException.class, resumed.failure());
        assertEquals("effect", doubt.stepName());
        assertEquals(RUN_ID, doubt.runId());
        assertEquals(
                1,
                sideEffectCount(effects),
                "the step must not have run a second time");
    }

    /**
     * Settling the doubt with "it happened" finishes the run and still does not
     * repeat the step. This is the path that gets you to something a caller can
     * call exactly-once, and the price is that the caller had to go and look.
     */
    @Test
    void confirmingItHappenedFinishesWithoutRepeatingTheStep(@TempDir Path root) throws Exception {
        Path effects = root.resolve("effects.log");
        Path store = root.resolve("store");

        crashInsideTheStep(store, effects, true);

        WorkflowEngine engine = new WorkflowEngine(new FileStore(store));
        engine.run(workflow(effects, true, false), INPUT, RUN_ID);

        // What the caller found when it asked the system the step talked to.
        engine.confirmCompleted(RUN_ID, "effect", INPUT + "-charged", Codec.ofString());

        RunResult<String> settled = engine.run(workflow(effects, true, false), INPUT, RUN_ID);

        assertTrue(settled.succeeded(), "the settled run should complete");
        assertEquals(INPUT + "-charged-receipt", settled.output().orElseThrow());
        assertEquals(
                1,
                sideEffectCount(effects),
                "confirming completion replays the recorded output, it does not re-run the step");
    }

    /**
     * Settling it the other way runs the step again, which is the point of
     * having two answers: the engine does not choose, and either choice is
     * reachable from the same stopped run.
     */
    @Test
    void confirmingItDidNotHappenRunsTheStepAgain(@TempDir Path root) throws Exception {
        Path effects = root.resolve("effects.log");
        Path store = root.resolve("store");

        crashInsideTheStep(store, effects, true);

        WorkflowEngine engine = new WorkflowEngine(new FileStore(store));
        engine.run(workflow(effects, true, false), INPUT, RUN_ID);

        engine.confirmNotCompleted(RUN_ID, "effect");

        RunResult<String> settled = engine.run(workflow(effects, true, false), INPUT, RUN_ID);

        assertTrue(settled.succeeded(), "the settled run should complete");
        assertEquals(
                2,
                sideEffectCount(effects),
                "confirming it did not happen is how you ask for the step to run again");
    }

    /**
     * {@code run} with the id of a run that already finished used to resume it,
     * and resuming repeats every step with no codec. It was the one way to get a
     * duplicate side effect out of Tandem with no crash involved. Both entry
     * points now refuse a completed run. See docs/BUGS.md 10.
     */
    @Test
    void rerunningACompletedRunIdIsRefusedRatherThanRepeated(@TempDir Path root) throws Exception {
        Path effects = root.resolve("effects.log");
        WorkflowEngine engine = new WorkflowEngine(new FileStore(root.resolve("store")));
        Workflow<String, String> definition = workflow(effects, false, false);

        assertTrue(engine.run(definition, INPUT, RUN_ID).succeeded());
        assertEquals(1, sideEffectCount(effects));

        TandemException refused = assertThrowsTandem(() -> engine.run(definition, INPUT, RUN_ID));
        assertTrue(
                refused.getMessage().contains("already succeeded"),
                "run() should refuse a completed run id, was: " + refused.getMessage());
        assertEquals(
                1,
                sideEffectCount(effects),
                "the refusal must happen before anything runs a second time");

        RecoverableRun<String, String> found =
                engine.recoverable(definition, RUN_ID).orElseThrow();
        assertEquals(RunState.COMPLETED, found.state());
        assertNotEquals(
                null,
                assertThrowsTandem(() -> engine.resume(found)),
                "resume(), which knows the run completed, refuses it too");
        assertEquals(1, sideEffectCount(effects), "the refusal ran nothing");
    }

    private static TandemException assertThrowsTandem(Runnable action) {
        try {
            action.run();
        } catch (TandemException expected) {
            return expected;
        }
        throw new AssertionError("expected a TandemException");
    }

    /** Nothing here should depend on the order the tests ran in. */
    @Test
    void theCrashLeavesExactlyTheLogShapeTheModelDescribes(@TempDir Path root) throws Exception {
        Path effects = root.resolve("effects.log");
        Path store = root.resolve("store");

        crashInsideTheStep(store, effects, true);

        List<WorkflowEvent> events = new FileStore(store).eventsFor(RUN_ID);
        List<EventType> types = events.stream().map(WorkflowEvent::type).toList();

        assertEquals(
                List.of(EventType.RUN_STARTED, EventType.STEP_STARTED),
                types,
                "the crash should leave an intent record and nothing that closes it");
        assertEquals("effect", events.get(1).stepName());
    }
}
