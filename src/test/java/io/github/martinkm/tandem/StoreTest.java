package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {

    private WorkflowEvent event(String runId, String step, EventType type) {
        return new WorkflowEvent(runId, "wf", step, type, 1, Instant.parse("2026-08-02T10:00:00Z"), "");
    }

    @Test
    void inMemoryStoreKeepsEventsPerRun() {
        InMemoryStore store = new InMemoryStore();
        store.append(event("a", "one", EventType.STEP_STARTED));
        store.append(event("a", "one", EventType.STEP_SUCCEEDED));
        store.append(event("b", "one", EventType.STEP_STARTED));

        assertEquals(2, store.eventsFor("a").size());
        assertEquals(1, store.eventsFor("b").size());
        assertTrue(store.eventsFor("missing").isEmpty());
    }

    @Test
    void inMemoryStoreRoundTripsOutputs() {
        InMemoryStore store = new InMemoryStore();
        assertTrue(store.loadOutput("a", "step").isEmpty());
        store.saveOutput("a", "step", "value");
        assertEquals("value", store.loadOutput("a", "step").orElseThrow());
        // Keyed by both, so the same step name in another run is separate.
        assertTrue(store.loadOutput("b", "step").isEmpty());
    }

    @Test
    void fileStoreSurvivesBeingReopened(@TempDir Path root) {
        FileStore first = new FileStore(root);
        first.append(event("run-1", "step", EventType.RUN_STARTED));
        first.saveOutput("run-1", "step", "recorded");

        FileStore reopened = new FileStore(root);
        assertEquals(1, reopened.eventsFor("run-1").size());
        assertEquals(EventType.RUN_STARTED, reopened.eventsFor("run-1").get(0).type());
        assertEquals("recorded", reopened.loadOutput("run-1", "step").orElseThrow());
    }

    @Test
    void fileStoreKeepsGoodLinesWhenTheLastOneIsTorn(@TempDir Path root) throws IOException {
        FileStore store = new FileStore(root);
        store.append(event("run-1", "a", EventType.STEP_STARTED));
        store.append(event("run-1", "b", EventType.STEP_SUCCEEDED));

        // Simulate a crash mid-append.
        Path log = root.resolve("run-1").resolve("events.jsonl");
        Files.writeString(log, "{\"runId\":\"run-1\",\"ty", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        List<WorkflowEvent> events = store.eventsFor("run-1");
        assertEquals(2, events.size(), "the two complete lines must survive the torn one");
    }

    @Test
    void fileStoreDoesNotLetAStepNameEscapeTheDirectory(@TempDir Path root) {
        FileStore store = new FileStore(root);
        store.saveOutput("run-1", "../../escaped", "value");

        assertEquals("value", store.loadOutput("run-1", "../../escaped").orElseThrow());
        assertFalse(Files.exists(root.getParent().resolve("escaped.out")), "wrote outside the root");
    }

    @Test
    void fileStoreReadsBackARunIdThatNeededSanitising(@TempDir Path root) {
        // The write path sanitised and the read path did not, so events went to
        // one directory and came back from another: eventsFor returned nothing,
        // the engine called the run fresh, and every recorded step ran again.
        FileStore store = new FileStore(root);
        String runId = "order/4417 #2";

        store.append(event(runId, "charge", EventType.STEP_SUCCEEDED));
        store.saveOutput(runId, "charge", "receipt-1");

        assertEquals(1, store.eventsFor(runId).size(), "events were written where reads cannot see them");
        assertEquals("receipt-1", store.loadOutput(runId, "charge").orElseThrow());
    }

    @Test
    void fileStoreDoesNotLetARunIdEscapeTheDirectory(@TempDir Path root) {
        FileStore store = new FileStore(root);
        store.append(event("../../escaped", "step", EventType.STEP_SUCCEEDED));

        assertEquals(1, store.eventsFor("../../escaped").size());
        assertFalse(
                Files.exists(root.getParent().resolve("events.jsonl")), "wrote outside the root");
        assertFalse(
                Files.exists(root.getParent().getParent().resolve("events.jsonl")),
                "wrote outside the root");
    }

    @Test
    void fileStoreKeepsDistinctRunIdsApartWhenTheySanitiseAlike(@TempDir Path root) {
        // "a/b" and "a_b" both flatten to the same characters. Sharing a
        // directory would interleave two runs' event logs and let one resume
        // from the other, which is worse than the bug this replaced.
        FileStore store = new FileStore(root);
        store.append(event("a/b", "step", EventType.STEP_SUCCEEDED));
        store.append(event("a_b", "step", EventType.STEP_STARTED));

        assertEquals(1, store.eventsFor("a/b").size());
        assertEquals(1, store.eventsFor("a_b").size());
        assertEquals(EventType.STEP_SUCCEEDED, store.eventsFor("a/b").get(0).type());
        assertEquals(EventType.STEP_STARTED, store.eventsFor("a_b").get(0).type());
    }

    @Test
    void jsonLineRoundTripsAwkwardValues() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("plain", "value");
        fields.put("quotes", "he said \"no\"");
        fields.put("backslash", "C:\\path\\to");
        fields.put("newline", "one\ntwo");
        fields.put("tab", "a\tb");
        fields.put("empty", "");

        Map<String, String> decoded = JsonLine.decode(JsonLine.encode(fields));
        assertEquals(fields, decoded);
    }

    @Test
    void jsonLineRejectsMalformedInput() {
        assertThrows(TandemException.class, () -> JsonLine.decode("not json"));
        assertThrows(TandemException.class, () -> JsonLine.decode("{\"unterminated"));
        assertThrows(TandemException.class, () -> JsonLine.decode("{\"key\"}"));
    }

    @Test
    void eventsRoundTripThroughTheirMapForm() {
        WorkflowEvent original =
                new WorkflowEvent(
                        "run-1",
                        "wf",
                        "step",
                        EventType.STEP_RETRYING,
                        3,
                        Instant.parse("2026-08-02T10:00:00Z"),
                        "IllegalStateException: nope");

        assertEquals(original, WorkflowEvent.fromMap(original.toMap()));
    }
}
