package me.blinkdev.tandem;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One entry in a run's log.
 *
 * <p>The log is the run's memory. Resuming reads it, observability reads it, and
 * an audit trail falls out of it for free.
 *
 * @param runId        the run this belongs to
 * @param workflowName the workflow definition's name
 * @param stepName     the step, or empty for run-level events
 * @param type         what happened
 * @param attempt      1-based attempt number, or 0 for run-level events
 * @param at           when it happened
 * @param detail       free text, typically an error message
 */
public record WorkflowEvent(
        String runId,
        String workflowName,
        String stepName,
        EventType type,
        int attempt,
        Instant at,
        String detail) {

    public WorkflowEvent {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(workflowName, "workflowName");
        Objects.requireNonNull(stepName, "stepName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(at, "at");
        detail = detail == null ? "" : detail;
    }

    /** Flat string map, which is all the on-disk format needs. */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("runId", runId);
        map.put("workflow", workflowName);
        map.put("step", stepName);
        map.put("type", type.name());
        map.put("attempt", String.valueOf(attempt));
        map.put("at", at.toString());
        map.put("detail", detail);
        return map;
    }

    public static WorkflowEvent fromMap(Map<String, String> map) {
        return new WorkflowEvent(
                map.getOrDefault("runId", ""),
                map.getOrDefault("workflow", ""),
                map.getOrDefault("step", ""),
                EventType.valueOf(map.getOrDefault("type", "RUN_FAILED")),
                Integer.parseInt(map.getOrDefault("attempt", "0")),
                Instant.parse(map.getOrDefault("at", Instant.EPOCH.toString())),
                map.getOrDefault("detail", ""));
    }
}
