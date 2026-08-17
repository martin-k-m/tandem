package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run directory is established once per run and cached, so it is shared
 * mutable state on the append path. These tests exist because of that: the
 * concurrency the {@link WorkflowStore} contract permits is exactly where a
 * cache goes wrong, and none of it shows up in a single threaded test.
 */
class FileStoreCacheTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 40;

    private static WorkflowEvent event(String runId, int i) {
        return new WorkflowEvent(
                runId, "w", "s", EventType.STEP_SUCCEEDED, i, Instant.now(), "n" + i);
    }

    /** Every append lands, and the id file is written exactly once. */
    @Test
    void concurrentAppendsToOneRunAllLand(@TempDir Path root) throws Exception {
        FileStore store = new FileStore(root);
        String runId = "one-run";

        assertEquals(THREADS * PER_THREAD, appendConcurrently(store, i -> runId).size());
        assertEquals(
                THREADS * PER_THREAD,
                store.eventsFor(runId).size(),
                "an event was lost by a concurrent append");
        assertEquals(runId, Files.readString(root.resolve(runId).resolve("id"), StandardCharsets.UTF_8));
    }

    /**
     * The cache is keyed by run id, so the failure to look for is one run being
     * handed another's directory.
     */
    @Test
    void concurrentAppendsToDistinctRunsStayApart(@TempDir Path root) throws Exception {
        FileStore store = new FileStore(root);

        appendConcurrently(store, i -> "run-" + (i % THREADS));

        for (int i = 0; i < THREADS; i++) {
            String runId = "run-" + i;
            List<WorkflowEvent> events = store.eventsFor(runId);
            assertEquals(PER_THREAD, events.size(), runId + " lost or gained events");
            assertTrue(
                    events.stream().allMatch(e -> e.runId().equals(runId)),
                    "another run's event landed in " + runId);
        }
        assertEquals(THREADS, store.listRuns().size());
    }

    /**
     * A cache that outlives the thing it caches must not turn a recoverable
     * state into a dead run. Something outside the process removing a run
     * directory is the case that used to be impossible, because nothing was
     * remembered.
     */
    @Test
    void anAppendSurvivesTheRunDirectoryBeingRemovedUnderneathIt(@TempDir Path root)
            throws Exception {
        FileStore store = new FileStore(root);
        store.append(event("gone", 0));

        Path directory = root.resolve("gone");
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.collect(Collectors.toList())) {
                Files.delete(entry);
            }
        }
        Files.delete(directory);

        store.append(event("gone", 1));

        assertEquals(1, store.eventsFor("gone").size(), "the re-established log holds the new event");
        assertEquals("gone", Files.readString(directory.resolve("id"), StandardCharsets.UTF_8));
    }

    /** Two stores over one root are two caches, and must not disagree. */
    @Test
    void twoStoresOverOneRootSeeEachOthersRuns(@TempDir Path root) {
        FileStore first = new FileStore(root);
        FileStore second = new FileStore(root);

        first.append(event("shared", 0));
        second.append(event("shared", 1));

        assertEquals(2, first.eventsFor("shared").size());
        assertEquals(2, second.eventsFor("shared").size());
    }

    /** Ids that need escaping are cached under the id, not under the file name. */
    @Test
    void escapedRunIdsAreCachedApart(@TempDir Path root) {
        FileStore store = new FileStore(root);
        store.append(event("a/b", 0));
        store.append(event("a/b", 1));
        store.append(event("a_002fb", 0));

        assertEquals(2, store.eventsFor("a/b").size());
        assertEquals(1, store.eventsFor("a_002fb").size());
    }

    /** Fires the appends from several threads released at once. */
    private static Set<String> appendConcurrently(
            FileStore store, java.util.function.IntFunction<String> runIds) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Set<String> written = ConcurrentHashMap.newKeySet();
        List<java.util.concurrent.Future<?>> pending = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            pending.add(
                    pool.submit(
                            () -> {
                                try {
                                    go.await();
                                    for (int i = 0; i < PER_THREAD; i++) {
                                        int n = thread * PER_THREAD + i;
                                        store.append(event(runIds.apply(n), n));
                                        written.add(String.valueOf(n));
                                    }
                                } catch (Throwable thrown) {
                                    failure.compareAndSet(null, thrown);
                                }
                            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<?> future : pending) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        if (failure.get() != null) {
            throw new AssertionError("an append failed", failure.get());
        }
        return written;
    }
}
