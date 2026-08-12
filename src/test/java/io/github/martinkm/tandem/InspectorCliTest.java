package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line, driven through {@link InspectorCli#run} so a test can read
 * its output and exit code without the JVM being ended under it.
 */
class InspectorCliTest {

    @Test
    void listPrintsOneRunAndItsStatePerLine(@TempDir Path root) {
        seedCompleted(root, "order-4417");
        seedInDoubt(root, "order-9001");

        Captured captured = new Captured();
        int code = InspectorCli.run(new String[] {"list", root.toString()}, captured.out, captured.err);

        assertEquals(0, code);
        // FileStore lists ids sorted, so the order is stable.
        assertEquals(
                "order-4417  COMPLETED\norder-9001  IN_DOUBT",
                captured.stdout().strip().replace("\r\n", "\n"));
        assertTrue(captured.stderr().isEmpty());
    }

    @Test
    void showPrintsTheWorkflowStateAndSteps(@TempDir Path root) {
        seedInDoubt(root, "order-9001");

        Captured captured = new Captured();
        int code =
                InspectorCli.run(
                        new String[] {"show", root.toString(), "order-9001"},
                        captured.out,
                        captured.err);

        assertEquals(0, code);
        String out = captured.stdout().replace("\r\n", "\n");
        assertTrue(out.contains("run: order-9001"), out);
        assertTrue(out.contains("workflow: checkout"), out);
        assertTrue(out.contains("status: IN_DOUBT"), out);
        assertTrue(out.contains("in doubt at: charge"), out);
        assertTrue(out.contains("charge  started, no outcome recorded"), out);
    }

    @Test
    void anUnknownCommandPrintsUsageAndExitsTwo() {
        Captured captured = new Captured();
        int code = InspectorCli.run(new String[] {"peek"}, captured.out, captured.err);

        assertEquals(2, code);
        assertTrue(captured.stdout().isEmpty());
        assertTrue(captured.stderr().contains("usage:"), captured.stderr());
    }

    @Test
    void tooFewArgumentsPrintUsageAndExitTwo(@TempDir Path root) {
        Captured captured = new Captured();
        int code = InspectorCli.run(new String[] {"show", root.toString()}, captured.out, captured.err);

        assertEquals(2, code);
        assertTrue(captured.stderr().contains("usage:"), captured.stderr());
    }

    @Test
    void aMissingDirectoryIsAClearMessageAndExitOne() {
        Captured captured = new Captured();
        int code =
                InspectorCli.run(
                        new String[] {"list", "no-such-place-1234"}, captured.out, captured.err);

        assertEquals(1, code);
        assertTrue(captured.stderr().contains("no such directory"), captured.stderr());
    }

    @Test
    void aMissingRunIsAClearMessageAndExitOne(@TempDir Path root) {
        seedCompleted(root, "order-4417");

        Captured captured = new Captured();
        int code =
                InspectorCli.run(
                        new String[] {"show", root.toString(), "ghost"}, captured.out, captured.err);

        assertEquals(1, code);
        assertTrue(captured.stderr().contains("no run ghost"), captured.stderr());
    }

    @Test
    void listingAMissingDirectoryDoesNotCreateIt(@TempDir Path root) {
        Path directory = root.resolve("absent");
        Captured captured = new Captured();

        InspectorCli.run(new String[] {"list", directory.toString()}, captured.out, captured.err);

        assertFalse(java.nio.file.Files.exists(directory), "checking a store must not create it");
    }

    private WorkflowEngine engine(WorkflowStore store) {
        return new WorkflowEngine(store, Sleeper.none(), new Random(42));
    }

    private void seedCompleted(Path root, String runId) {
        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .step(
                                "charge",
                                (String order, StepContext ctx) -> order + "-charged",
                                Codec.ofString())
                        .build();
        assertTrue(engine(new FileStore(root)).run(checkout, "order", runId).succeeded());
    }

    private void seedInDoubt(Path root, String runId) {
        Workflow<String, String> checkout =
                Workflow.<String>named("checkout")
                        .input(Codec.ofString())
                        .<String>step(
                                "charge",
                                (String order, StepContext ctx) -> {
                                    throw new Crash();
                                },
                                Codec.ofString())
                        .build();
        assertThrows(Crash.class, () -> engine(new FileStore(root)).run(checkout, "order", runId));
    }

    /** A dying process: an Error, so nothing in the engine catches it. */
    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** A pair of streams to read back what the CLI wrote. */
    private static final class Captured {
        private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        String stdout() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }

        String stderr() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }
    }
}
