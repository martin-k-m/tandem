package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FileStore.Durability#SYNC_ON_EVERY_EVENT} writes the same bytes and
 * then forces them.
 *
 * <p>What a test on one machine cannot show is the guarantee itself: proving an
 * event survives a power cut needs a power cut. So these assert the part that is
 * checkable, which is that the sync policy is byte-for-byte the same store, and
 * the guarantee is argued in docs/durability.md rather than claimed here.
 */
class FileStoreSyncTest {

    private static WorkflowEvent event(String runId, int i) {
        return new WorkflowEvent(
                runId, "w", "s" + i, EventType.STEP_SUCCEEDED, i, Instant.EPOCH.plusSeconds(i), "d");
    }

    @Test
    void theDefaultIsTheBufferedPolicy(@TempDir Path root) {
        assertEquals(FileStore.Durability.OS_BUFFERED, new FileStore(root).durability());
    }

    @Test
    void bothPoliciesWriteTheSameBytes(@TempDir Path root) throws Exception {
        FileStore buffered = new FileStore(root.resolve("buffered"));
        FileStore synced =
                new FileStore(root.resolve("synced"), FileStore.Durability.SYNC_ON_EVERY_EVENT);

        for (FileStore store : List.of(buffered, synced)) {
            for (int i = 0; i < 5; i++) {
                store.append(event("run", i));
            }
            store.saveInput("run", "the-input");
            store.saveOutput("run", "step", "the-output");
        }

        assertEquals(
                Files.readString(root.resolve("buffered/run/events.jsonl"), StandardCharsets.UTF_8),
                Files.readString(root.resolve("synced/run/events.jsonl"), StandardCharsets.UTF_8));
        assertEquals(
                Files.readString(root.resolve("buffered/run/input"), StandardCharsets.UTF_8),
                Files.readString(root.resolve("synced/run/input"), StandardCharsets.UTF_8));
        assertEquals(
                Files.readString(
                        root.resolve("buffered/run/steps/step.out"), StandardCharsets.UTF_8),
                Files.readString(root.resolve("synced/run/steps/step.out"), StandardCharsets.UTF_8));
    }

    @Test
    void aSyncedStoreRunsAWorkflowAndIsReadBackByAFreshOne(@TempDir Path root) {
        Workflow<String, String> workflow =
                Workflow.<String>named("sync")
                        .step("one", (String in, StepContext c) -> in + "-1", Codec.ofString())
                        .step("two", (String in, StepContext c) -> in + "-2", Codec.ofString())
                        .build();

        WorkflowEngine engine =
                new WorkflowEngine(
                        new FileStore(root, FileStore.Durability.SYNC_ON_EVERY_EVENT));
        RunResult<String> result = engine.run(workflow, "in", "r1");
        assertTrue(result.succeeded());
        assertEquals("in-1-2", result.output().orElseThrow());

        WorkflowEngine restarted = new WorkflowEngine(new FileStore(root));
        assertEquals(
                RunState.COMPLETED,
                restarted.recoverable(workflow, "r1").orElseThrow().state(),
                "a forced log reads back as any other log");
    }

    @Test
    void aSyncedLogSurvivesTornBytesTheSameWay(@TempDir Path root) throws Exception {
        FileStore store = new FileStore(root, FileStore.Durability.SYNC_ON_EVERY_EVENT);
        store.append(event("torn", 0));
        store.append(event("torn", 1));

        Path log = root.resolve("torn/events.jsonl");
        byte[] whole = Files.readAllBytes(log);
        Files.write(log, java.util.Arrays.copyOf(whole, whole.length - 5));

        assertEquals(1, store.eventsFor("torn").size(), "the torn last line is dropped, not the log");
    }
}
