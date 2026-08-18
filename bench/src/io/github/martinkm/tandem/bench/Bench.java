package io.github.martinkm.tandem.bench;

import io.github.martinkm.tandem.BenchEncoding;
import io.github.martinkm.tandem.Codec;
import io.github.martinkm.tandem.FileStore;
import io.github.martinkm.tandem.InMemoryStore;
import io.github.martinkm.tandem.StepContext;
import io.github.martinkm.tandem.Workflow;
import io.github.martinkm.tandem.WorkflowBuilder;
import io.github.martinkm.tandem.WorkflowEngine;
import io.github.martinkm.tandem.WorkflowEvent;
import io.github.martinkm.tandem.WorkflowStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Function;

/**
 * The benchmarks behind docs/BENCHMARKS.md.
 *
 * <p>This is a plain harness, not JMH. It warms up explicitly, times with
 * {@link System#nanoTime}, and reports median and p99 rather than a mean, but it
 * does no dead code elimination analysis, no fork-per-trial, and no statistical
 * rigour beyond percentiles over a stated number of samples. Every figure it
 * prints should be read as an order of magnitude and a shape, not as a number
 * accurate to its last digit. The operations measured here are dominated by
 * system calls and file I/O, which is the regime where a plain harness is least
 * wrong.
 *
 * <p>Run it through {@code bench/run.sh}, which prints the environment
 * alongside the results so a number is never separated from the machine that
 * produced it.
 */
public final class Bench {

    /** Steps in the workflow used for the throughput benchmark. */
    private static final int STEPS_PER_RUN = 10;

    private Bench() {}

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("tandem-bench");
        try {
            String only = args.length > 0 ? args[0] : "all";
            if (only.equals("all") || only.equals("append")) {
                appendLatency(scratch);
            }
            if (only.equals("all") || only.equals("throughput")) {
                stepThroughput(scratch);
            }
            if (only.equals("all") || only.equals("recovery")) {
                recoveryCurve(scratch);
            }
            if (only.equals("all") || only.equals("breakdown")) {
                appendBreakdown(scratch);
            }
        } finally {
            deleteRecursively(scratch);
        }
    }

    // ------------------------------------------------------- append latency

    /**
     * The cost of recording one event, which is the unit everything else is
     * built from: a step costs two of these plus an output write.
     *
     * <p>Measured at the store rather than through the engine so that the
     * difference between the three rows is the write and nothing else.
     */
    private static void appendLatency(Path scratch) throws IOException {
        int warmup = 2_000;
        int samples = 20_000;

        System.out.println();
        System.out.println("## append latency, one event");
        System.out.printf("warmup %d, samples %d%n", warmup, samples);
        System.out.println();
        header();

        measureAppend("InMemoryStore", new InMemoryStore(), warmup, samples);
        measureAppend(
                "FileStore (OS_BUFFERED, the default)",
                new FileStore(scratch.resolve("append-plain")),
                warmup,
                samples);
        // An fsync per event is slow enough that twenty thousand of them is
        // minutes rather than seconds, so this row takes fewer samples. Stated
        // rather than hidden, because a percentile over fewer samples is a
        // weaker claim.
        measureAppend(
                "FileStore (SYNC_ON_EVERY_EVENT)",
                new FileStore(
                        scratch.resolve("append-fsync"),
                        FileStore.Durability.SYNC_ON_EVERY_EVENT),
                200,
                2_000);
        System.out.printf("%s   -> warmup %d, samples %,d for this row%n", pad(""), 200, 2_000);
    }

    private static void measureAppend(String label, WorkflowStore store, int warmup, int samples) {
        String runId = "bench-append";
        for (int i = 0; i < warmup; i++) {
            store.append(event(runId, i));
        }

        long[] nanos = new long[samples];
        for (int i = 0; i < samples; i++) {
            WorkflowEvent event = event(runId, i);
            long start = System.nanoTime();
            store.append(event);
            nanos[i] = System.nanoTime() - start;
        }
        report(label, nanos, samples);
    }

    private static WorkflowEvent event(String runId, int i) {
        return new WorkflowEvent(
                runId,
                "bench",
                "step-" + (i % STEPS_PER_RUN),
                io.github.martinkm.tandem.EventType.STEP_SUCCEEDED,
                1,
                Instant.ofEpochMilli(1_700_000_000_000L + i),
                "");
    }

    // ------------------------------------------------------ step throughput

    /**
     * Whole runs through the engine, every step carrying a codec, which is the
     * expensive and the interesting case: a recorded step writes a started
     * event, its output, and a succeeded event.
     *
     * <p>Timed per run rather than per step, because a run is the unit the
     * engine actually executes. The steps per second column divides through by
     * {@link #STEPS_PER_RUN}.
     */
    private static void stepThroughput(Path scratch) throws IOException {
        int warmup = 200;
        int samples = 2_000;

        System.out.println();
        System.out.printf(
                "## durable step throughput, %d recorded steps per run%n", STEPS_PER_RUN);
        System.out.printf("warmup %d runs, samples %d runs%n", warmup, samples);
        System.out.println();
        header();

        measureRuns(
                "InMemoryStore",
                root -> new InMemoryStore(),
                scratch.resolve("run-memory"),
                warmup,
                samples);
        measureRuns(
                "FileStore (OS_BUFFERED, the default)",
                FileStore::new,
                scratch.resolve("run-plain"),
                warmup,
                samples);
        measureRuns(
                "FileStore (SYNC_ON_EVERY_EVENT)",
                root -> new FileStore(root, FileStore.Durability.SYNC_ON_EVERY_EVENT),
                scratch.resolve("run-fsync"),
                20,
                200);
        System.out.printf("%s   -> warmup %d, samples %d for this row%n", pad(""), 20, 200);
    }

    private static void measureRuns(
            String label,
            Function<Path, WorkflowStore> stores,
            Path root,
            int warmup,
            int samples)
            throws IOException {

        Files.createDirectories(root);
        WorkflowStore store = stores.apply(root);
        WorkflowEngine engine = new WorkflowEngine(store);
        Workflow<String, String> workflow = chain("bench-throughput", STEPS_PER_RUN);

        for (int i = 0; i < warmup; i++) {
            engine.run(workflow, "in", "warm-" + i);
        }

        long[] nanos = new long[samples];
        for (int i = 0; i < samples; i++) {
            String runId = "run-" + i;
            long start = System.nanoTime();
            engine.run(workflow, "in", runId);
            nanos[i] = System.nanoTime() - start;
        }
        report(label, nanos, samples);
        System.out.printf(
                "%s   -> %,.0f steps/s at the median%n",
                pad(""), STEPS_PER_RUN * 1e9 / median(nanos.clone()));
    }

    // -------------------------------------------------------- recovery curve

    /**
     * How long it takes to pick a run back up, against how much log there is to
     * pick it up from.
     *
     * <p>A run of {@code L} recorded steps is executed to completion, then the
     * same run id is run again against a definition with one more step. The
     * timed call reads the whole log, folds it, replays {@code L} recorded
     * outputs from {@code L} separate files, and executes the one step that has
     * no output yet. That is what a restart does, and its shape against L is the
     * thing worth knowing.
     */
    private static void recoveryCurve(Path scratch) throws IOException {
        int[] lengths = {1, 2, 5, 10, 20, 50, 100, 200, 500};
        int repetitions = 60;
        int warmup = 10;

        System.out.println();
        System.out.println("## recovery time against log length");
        System.out.printf(
                "warmup %d, samples %d per point, FileStore (as shipped)%n", warmup, repetitions);
        System.out.println();
        System.out.printf(
                "| %-12s | %-8s | %-12s | %-12s | %-12s |%n",
                "steps done", "events", "median ms", "p99 ms", "ms per step");
        System.out.printf(
                "| %-12s | %-8s | %-12s | %-12s | %-12s |%n",
                ":---", ":---", "---:", "---:", "---:");

        for (int length : lengths) {
            long[] nanos = new long[repetitions];
            int events = -1;
            for (int i = -warmup; i < repetitions; i++) {
                Path root = scratch.resolve("recover-" + length + "-" + i);
                Files.createDirectories(root);
                WorkflowStore store = new FileStore(root);
                WorkflowEngine engine = new WorkflowEngine(store);
                String runId = "recovered";

                // Not timed: getting the store into the state a crash would
                // have left it in. The run records `length` steps and then
                // fails on one more, which is a resumable run. It used to be
                // built by completing the run and resuming it against a longer
                // definition, which only worked while run() would resume a run
                // that had already succeeded. It no longer does, and that was
                // the bug rather than the benchmark.
                engine.run(failingAfter("bench-recovery", length), "in", runId);
                int eventCount = store.eventsFor(runId).size();

                WorkflowEngine restarted = new WorkflowEngine(new FileStore(root));
                Workflow<String, String> more = chain("bench-recovery", length + 1);

                long start = System.nanoTime();
                restarted.run(more, "in", runId);
                long elapsed = System.nanoTime() - start;

                if (i >= 0) {
                    nanos[i] = elapsed;
                    events = eventCount;
                }
                deleteRecursively(root);
            }
            long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            System.out.printf(
                    "| %-12d | %-8d | %12.3f | %12.3f | %12.4f |%n",
                    length,
                    events,
                    median(nanos.clone()) / 1e6,
                    percentile(sorted, 99) / 1e6,
                    median(nanos.clone()) / 1e6 / length);
        }
    }

    // ------------------------------------------------------- append breakdown

    /**
     * Where an append's time actually goes, so the interpretation in
     * docs/BENCHMARKS.md is measured rather than assumed.
     *
     * <p>{@code FileStore.append} does four things: it calls
     * {@code createDirectories} on the run directory, checks whether the id file
     * exists, encodes the event, and opens, writes and closes the log. Each row
     * below adds one of those to the row above it, so the difference between two
     * neighbouring rows is the cost of the thing that was added.
     */
    private static void appendBreakdown(Path scratch) throws IOException {
        int warmup = 2_000;
        int samples = 20_000;

        Path directory = scratch.resolve("breakdown");
        Files.createDirectories(directory);
        Path file = directory.resolve("events.jsonl");
        Path idFile = directory.resolve("id");
        Files.writeString(idFile, "bench-breakdown");
        String line = BenchEncoding.encode(event("bench-breakdown", 0)) + System.lineSeparator();

        System.out.println();
        System.out.println("## where an append's time goes");
        System.out.printf("warmup %d, samples %d, on an existing directory and log%n",
                warmup, samples);
        System.out.println();
        header();

        timed(
                "encode the event only",
                warmup,
                samples,
                i -> BenchEncoding.encode(event("bench-breakdown", i)));

        timed(
                "createDirectories on an existing dir",
                warmup,
                samples,
                i -> {
                    try {
                        return Files.createDirectories(directory);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        timed("exists() on the id file", warmup, samples, i -> Files.exists(idFile));

        timed(
                "open, append one line, close",
                warmup,
                samples,
                i -> {
                    try {
                        return Files.writeString(
                                file,
                                line,
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        FileStore store = new FileStore(scratch.resolve("breakdown-store"));
        timed(
                "FileStore.append, all of the above",
                warmup,
                samples,
                i -> {
                    store.append(event("bench-breakdown", i));
                    return store;
                });

        // The run directory is established once and cached, so the two rows
        // below are the two halves of that: the first append of a run pays for
        // the directory and the id file, every later one does not. Fewer
        // samples, because each iteration is a new directory on disk.
        FileStore fresh = new FileStore(scratch.resolve("breakdown-fresh"));
        timed(
                "FileStore.append, first event of a run",
                200,
                2_000,
                i -> {
                    fresh.append(event("bench-fresh-" + i, i));
                    return fresh;
                });
    }

    /** Times {@code work} once per iteration and reports the distribution. */
    private static void timed(
            String label, int warmup, int samples, java.util.function.IntFunction<Object> work) {
        Object sink = null;
        for (int i = 0; i < warmup; i++) {
            sink = work.apply(i);
        }
        long[] nanos = new long[samples];
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            sink = work.apply(i);
            nanos[i] = System.nanoTime() - start;
        }
        // Kept live so nothing above can be optimised away as unobserved. The
        // JIT is free to do that within a method, and a benchmark that measures
        // an elided call reports a wonderful number for no work at all.
        if (sink != null && sink.hashCode() == 0xDEADBEEF) {
            System.out.print("");
        }
        report(label, nanos, samples);
    }

    // ------------------------------------------------------------- plumbing

    /** A workflow of {@code steps} recorded steps, each appending to a string. */
    private static Workflow<String, String> chain(String name, int steps) {
        WorkflowBuilder<String, String> builder = Workflow.<String>named(name);
        for (int i = 0; i < steps; i++) {
            builder =
                    builder.step(
                            "step-" + i,
                            (String in, StepContext ctx) -> in.length() > 64 ? in : in + "x",
                            Codec.ofString());
        }
        return builder.build();
    }

    /** {@link #chain} of {@code steps} recorded steps, then one that throws. */
    private static Workflow<String, String> failingAfter(String name, int steps) {
        WorkflowBuilder<String, String> builder = Workflow.<String>named(name);
        for (int i = 0; i < steps; i++) {
            builder =
                    builder.step(
                            "step-" + i,
                            (String in, StepContext ctx) -> in.length() > 64 ? in : in + "x",
                            Codec.ofString());
        }
        return builder.<String>step(
                        "step-" + steps,
                        (String in, StepContext ctx) -> {
                            throw new IllegalStateException("stop here");
                        },
                        Codec.ofString())
                .build();
    }

    private static void header() {
        System.out.printf(
                "| %-34s | %-12s | %-12s | %-12s | %-14s |%n",
                "store", "median us", "p99 us", "mean us", "ops/s (median)");
        System.out.printf(
                "| %-34s | %-12s | %-12s | %-12s | %-14s |%n",
                ":---", "---:", "---:", "---:", "---:");
    }

    private static void report(String label, long[] nanos, int samples) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double total = 0;
        for (long n : nanos) {
            total += n;
        }
        double med = percentile(sorted, 50);
        System.out.printf(
                "| %-34s | %12.3f | %12.3f | %12.3f | %,14.0f |%n",
                label,
                med / 1e3,
                percentile(sorted, 99) / 1e3,
                total / samples / 1e3,
                1e9 / med);
    }

    private static double median(long[] nanos) {
        Arrays.sort(nanos);
        return percentile(nanos, 50);
    }

    /** Nearest rank on an already sorted array. */
    private static double percentile(long[] sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static String pad(String text) {
        return String.format("| %-34s |", text);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                            throws IOException {
                        Files.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
