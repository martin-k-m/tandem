package me.blinkdev.tandem;

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
