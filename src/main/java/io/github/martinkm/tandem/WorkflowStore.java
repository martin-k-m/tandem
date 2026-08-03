package io.github.martinkm.tandem;

import java.util.List;
import java.util.Optional;

/**
 * Where a run's log, the input it started with and its replayable step outputs
 * live.
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

    /**
     * Every run this store holds a log for.
     *
     * <p>This is what makes recovery possible. A crash takes the run ids with
     * it, and a resume that cannot name a run cannot happen, so the store has to
     * be able to say what it is holding.
     *
     * <p>Each id must come back <em>exactly</em> as it was passed in, whatever
     * the store had to do to turn it into a key, a file name or a column. That
     * generally means recording the id rather than deriving it back out of
     * whatever it was mangled into: {@code eventsFor} is called with what this
     * returns, and an id that does not round-trip reads an empty history, which
     * the engine takes for a run that never started.
     *
     * <p>The order is the store's own. Both built-ins sort by id, so a listing
     * is stable between calls.
     */
    List<String> listRuns();

    /**
     * Persist the encoded input a run started with, so a resume that no longer
     * has it can still continue the run.
     */
    void saveInput(String runId, String encoded);

    /** The encoded input of a run, if one was recorded. */
    Optional<String> loadInput(String runId);

    /** Persist a step's encoded output so a resume can skip re-running it. */
    void saveOutput(String runId, String stepName, String encoded);

    /** The encoded output of a completed step, if one was recorded. */
    Optional<String> loadOutput(String runId, String stepName);
}
