package io.github.martinkm.tandem;

import java.util.Objects;

/**
 * What a step knows about the run it is part of.
 *
 * <p>Most usefully {@link #attempt()}, which lets a step behave differently on a
 * retry, for example skipping a side effect it already performed.
 */
public final class StepContext {

    private final String runId;
    private final String workflowName;
    private final String stepName;
    private final int attempt;
    private final boolean replaying;

    StepContext(String runId, String workflowName, String stepName, int attempt, boolean replaying) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.workflowName = Objects.requireNonNull(workflowName, "workflowName");
        this.stepName = Objects.requireNonNull(stepName, "stepName");
        this.attempt = attempt;
        this.replaying = replaying;
    }

    public String runId() {
        return runId;
    }

    public String workflowName() {
        return workflowName;
    }

    public String stepName() {
        return stepName;
    }

    /** 1-based. Attempt 1 is the first try. */
    public int attempt() {
        return attempt;
    }

    /** True when this run is resuming and earlier steps came from the log. */
    public boolean replaying() {
        return replaying;
    }

    @Override
    public String toString() {
        return workflowName + "/" + stepName + " (run " + runId + ", attempt " + attempt + ")";
    }
}
