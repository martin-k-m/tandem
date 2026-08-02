package io.github.martinkm.tandem;

import java.util.List;
import java.util.Optional;

/**
 * Where a run's log and its replayable step outputs live.
 *
 * <p>Implementations must be safe to use from several threads, since one engine
 * can run many workflows at once.
 *
 * <p>Two built-ins ship: {@link InMemoryStore}, which forgets everything when
 * the process ends, and {@link FileStore}, which survives a restart. Anything
 * else, a database or an object store, is an implementation of this interface.
 */
public interface WorkflowStore {

    /** Record that something happened. Called on the thread running the step. */
    void append(WorkflowEvent event);

    /** Every event for a run, oldest first. Empty for a run that never started. */
    List<WorkflowEvent> eventsFor(String runId);

    /** Persist a step's encoded output so a resume can skip re-running it. */
    void saveOutput(String runId, String stepName, String encoded);

    /** The encoded output of a completed step, if one was recorded. */
    Optional<String> loadOutput(String runId, String stepName);
}
