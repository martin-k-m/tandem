package io.github.martinkm.tandem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the log, the run's input and its step outputs under a directory, so a
 * run survives a restart and can be found and resumed afterwards.
 *
 * <p>Layout, one directory per run:
 *
 * <pre>
 *   &lt;root&gt;/&lt;runId&gt;/id               the run id exactly as it was given
 *   &lt;root&gt;/&lt;runId&gt;/events.jsonl     one JSON object per line, append-only
 *   &lt;root&gt;/&lt;runId&gt;/input           the encoded input, when the workflow records one
 *   &lt;root&gt;/&lt;runId&gt;/steps/&lt;name&gt;.out one encoded step output per file
 * </pre>
 *
 * <p>Append-only rather than a single rewritten file: a crash midway through a
 * rewrite can lose the whole history, while a torn append loses only the last
 * line, and the reader skips lines it cannot parse.
 *
 * <p>The caller picks how durable a write is, with {@link Durability}. The
 * default is {@link Durability#OS_BUFFERED}, which hands bytes to the operating
 * system and forces nothing: a process crash loses nothing, a power cut can lose
 * the last few events. {@link Durability#SYNC_ON_EVERY_EVENT} forces each event
 * and each step output to the device before returning, and costs several times
 * the throughput. Which to pick, and what it costs, is
 * docs/DECISIONS.md 4.
 */
public final class FileStore implements WorkflowStore {

    /** How hard a write is pushed before {@code append} returns. */
    public enum Durability {
        /**
         * Written and handed to the operating system. Survives a process crash,
         * not a power cut.
         */
        OS_BUFFERED,
        /**
         * Forced to the device before returning, metadata included: a log whose
         * bytes reached the device while the length covering them did not is an
         * empty log after a power cut.
         */
        SYNC_ON_EVERY_EVENT
    }

    /** Where {@link #established} is cleared, so it cannot grow without bound. */
    private static final int CACHE_LIMIT = 10_000;

    /** Cleared for the process the first time a directory cannot be forced. */
    private static volatile boolean directoryForceWorks = true;

    private final Path root;
    private final Durability durability;
    /**
     * Run directories this instance has established. Only a cache of work that
     * is idempotent, so clearing it costs one extra {@code createDirectories}
     * per run and nothing else.
     */
    private final Map<String, Path> established = new ConcurrentHashMap<>();

    public FileStore(Path root) {
        this(root, Durability.OS_BUFFERED);
    }

    public FileStore(Path root, Durability durability) {
        this.root = Objects.requireNonNull(root, "root");
        this.durability = Objects.requireNonNull(durability, "durability");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + root, e);
        }
    }

    public Durability durability() {
        return durability;
    }

    @Override
    public void append(WorkflowEvent event) {
        byte[] line =
                (JsonLine.encode(event.toMap()) + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8);
        Path file = runDirectory(event.runId()).resolve("events.jsonl");
        try {
            writeLine(file, line);
        } catch (IOException firstTry) {
            // The cache says the directory exists and something outside this
            // process may have removed it. Re-establish once before failing, so
            // caching cannot turn a recoverable state into a dead run.
            established.remove(event.runId());
            Path retry = runDirectory(event.runId()).resolve("events.jsonl");
            try {
                writeLine(retry, line);
            } catch (IOException e) {
                throw new UncheckedIOException("could not append to " + retry, e);
            }
        }
    }

    private void writeLine(Path file, byte[] line) throws IOException {
        if (durability == Durability.OS_BUFFERED) {
            Files.write(
                    file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return;
        }
        try (FileChannel channel =
                FileChannel.open(
                        file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(line));
            channel.force(true);
        }
    }

    @Override
    public List<WorkflowEvent> eventsFor(String runId) {
        Path file = root.resolve(safeName(runId)).resolve("events.jsonl");
        if (!Files.exists(file)) {
            return List.of();
        }
        List<WorkflowEvent> events = new ArrayList<>();
        try {
            // Decoded leniently, not with Files.readAllLines: a crash can tear
            // the last append mid-character, and a strict decoder rejects the
            // whole file rather than the one damaged line. See docs/BUGS.md 1.
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, String> fields = new LinkedHashMap<>(JsonLine.decode(line));
                    events.add(WorkflowEvent.fromMap(fields));
                } catch (RuntimeException malformed) {
                    // A torn final line from a crash. Everything before it is
                    // still good history, so keep it rather than failing.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        return List.copyOf(events);
    }

    /**
     * The ids of every run with a log under the root, sorted.
     *
     * <p>One open per run directory, so this is a scan and not a lookup. That is
     * inherent in a directory per run; a store over a database answers the same
     * question with one query.
     */
    @Override
    public List<String> listRuns() {
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                // The log is what says a run exists. A directory holding only an
                // input, from a process that died between the two writes, is not
                // something anything can resume.
                if (!Files.isRegularFile(entry.resolve("events.jsonl"))) {
                    continue;
                }
                String id = recordedId(entry);
                if (id != null) {
                    ids.add(id);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not list runs under " + root, e);
        }
        Collections.sort(ids);
        return List.copyOf(ids);
    }

    @Override
    public void saveInput(String runId, String encoded) {
        Path directory = runDirectory(runId);
        try {
            // Beside, then move, for the same reason an output is written that
            // way: a reader must not see half a value.
            Path temporary = Files.createTempFile(directory, "input", ".tmp");
            writeWhole(temporary, encoded);
            Files.move(
                    temporary,
                    directory.resolve("input"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not save the input of " + runId, e);
        }
    }

    @Override
    public Optional<String> loadInput(String runId) {
        return read(root.resolve(safeName(runId)).resolve("input"));
    }

    @Override
    public void saveOutput(String runId, String stepName, String encoded) {
        Path directory = runDirectory(runId).resolve("steps");
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(safeName(stepName) + ".out");
            // Write beside, then move: a reader never sees a half-written output.
            Path temporary = directory.resolve(safeName(stepName) + ".out.tmp");
            writeWhole(temporary, encoded);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (durability == Durability.SYNC_ON_EVERY_EVENT) {
                forceDirectory(directory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not save output for " + stepName, e);
        }
    }

    @Override
    public Optional<String> loadOutput(String runId, String stepName) {
        return read(
                root.resolve(safeName(runId))
                        .resolve("steps")
                        .resolve(safeName(stepName) + ".out"));
    }

    /** Writes a whole file, forced to the device when the policy says so. */
    private void writeWhole(Path file, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (durability == Durability.OS_BUFFERED) {
            Files.write(file, bytes);
            return;
        }
        try (FileChannel channel =
                FileChannel.open(
                        file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
    }

    private static Optional<String> read(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /**
     * The run's directory, created and stamped with its id on first use.
     *
     * <p>Both of those are no-ops after a run's first event and both cost real
     * syscalls, about 40% of an append when repeated
     * (docs/BENCHMARKS.md), so the result is cached per run id.
     * {@code computeIfAbsent} is what makes that safe: concurrent appends to one
     * run establish the directory once, and a mapping is only recorded if the
     * function returned. See docs/DECISIONS.md 5.
     */
    private Path runDirectory(String runId) {
        Path cached = established.get(runId);
        if (cached != null) {
            return cached;
        }
        if (established.size() >= CACHE_LIMIT) {
            established.clear();
        }
        return established.computeIfAbsent(
                runId,
                id -> {
                    Path directory = root.resolve(safeName(id));
                    try {
                        Files.createDirectories(directory);
                        recordId(directory, id);
                        if (durability == Durability.SYNC_ON_EVERY_EVENT) {
                            forceDirectory(directory);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException("could not create " + directory, e);
                    }
                    return directory;
                });
    }

    /**
     * Forces a directory entry, so a newly created file is still there after a
     * power cut and not just its contents.
     *
     * <p>Windows cannot open a directory as a channel and throws, so the first
     * failure turns this off for the process: retrying it per write costs a
     * failed syscall and an exception on every step, and the answer never
     * changes within a run. Best effort by design, since failing a write over
     * this would be worse than the residual window it leaves.
     */
    private static void forceDirectory(Path directory) {
        if (!directoryForceWorks) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException notOnThisPlatform) {
            directoryForceWorks = false;
        }
    }

    /**
     * Writes the run id beside its events, once per run.
     *
     * <p>{@code listRuns} has to hand back the id the caller used. Escaping is
     * reversible in principle, but recording the id keeps the answer a read
     * rather than a second implementation of the escaping that has to stay in
     * step with the first. An id that came back wrong would be read from a
     * different directory, find no history, and be run from the start.
     *
     * <p>Written beside and moved into place, so a crash mid-write cannot leave
     * a truncated id, and only when it is missing, so an append pays one
     * existence check rather than a write.
     */
    private static void recordId(Path directory, String runId) throws IOException {
        Path file = directory.resolve("id");
        if (Files.exists(file)) {
            return;
        }
        Path temporary = Files.createTempFile(directory, "id", ".tmp");
        try {
            Files.writeString(temporary, runId, StandardCharsets.UTF_8);
            // Not REPLACE_EXISTING. The check above and this move are two steps,
            // so two threads appending to one run can both try to create it.
            // Failing the move is how the loser is told, and losing is not an
            // error: the winner wrote the same id. See docs/BUGS.md 3.
            Files.move(temporary, file);
        } catch (java.nio.file.FileAlreadyExistsException lostTheRace) {
            // Someone else recorded it first, with the same content.
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * The id a run directory belongs to, or null if it cannot be established.
     *
     * <p>A directory written before ids were recorded has only its name to go
     * on. A name that sanitising would have left alone is its own id, so those
     * are still listed; anything else is skipped rather than guessed at, because
     * a listing that hands back an id nothing can read is worse than a listing
     * that is short.
     */
    private static String recordedId(Path directory) throws IOException {
        Path file = directory.resolve("id");
        if (Files.isRegularFile(file)) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        String name = directory.getFileName().toString();
        return safeName(name).equals(name) ? name : null;
    }

    /**
     * Step and run names become file names, so anything that could escape the
     * directory or upset a filesystem is replaced.
     *
     * <p>Every path is built through here, reads included. It used to sanitise
     * on the way in and not on the way out, so a run id holding anything
     * outside the allowed set wrote its events to one directory and read them
     * back from another. {@code eventsFor} returned nothing, the engine decided
     * the run was fresh, and every recorded step ran a second time, which is
     * the exact failure durability exists to prevent.
     *
     * <p>Replacing every awkward character with the same stand-in is not
     * injective: {@code "a/b"} and {@code "a_b"} both flatten to {@code "a_b"},
     * and two runs sharing a directory would interleave their event logs and
     * resume from each other. So this escapes rather than replaces. A character
     * that cannot be a file name becomes {@code _} followed by its four hex
     * digits, which is reversible, and therefore cannot collide.
     *
     * <p>That only holds if {@code _} itself is escaped, which is why it is not
     * in the pass-through set even though a file system is perfectly happy with
     * it. It buys the property the whole scheme rests on: a name that passed
     * through untouched contains no {@code _}, an escaped one always does, so
     * the two can never meet. An earlier version kept {@code _} and appended a
     * hash to escaped names instead, which left the escaped spelling of an id a
     * legal id in its own right: passing {@code "a_b-17234"} landed in the
     * directory belonging to {@code "a/b"}.
     *
     * <p>Ids made of letters, digits, {@code -} and {@code .}, which is every
     * UUID and nearly every hand-written id, are untouched and stay readable on
     * disk.
     */
    private static String safeName(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '-'
                            // A dot is fine in the middle and nowhere else. At
                            // the front it risks spelling "." or "..", which
                            // resolve to a directory rather than to a file. At
                            // the end it is silently dropped by Windows, so
                            // ".." and "." would land in one directory on one
                            // platform and two on another.
                            || (c == '.' && i > 0 && i < name.length() - 1);
            if (allowed) {
                out.append(c);
            } else {
                out.append('_').append(String.format("%04x", (int) c));
            }
        }
        // An escape is always _ and four digits, so a bare _ is a spelling
        // nothing else produces, which makes it a safe stand-in for the one
        // name that would otherwise come out empty.
        return out.isEmpty() ? "_" : out.toString();
    }
}
