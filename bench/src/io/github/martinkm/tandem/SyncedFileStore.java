package io.github.martinkm.tandem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

/**
 * A store that writes the same bytes {@link FileStore} writes and then forces
 * them to the device, so the benchmark can price the durability guarantee
 * Tandem does not currently make.
 *
 * <p>This exists to answer one question: what would an fsync per event cost.
 * It is not FileStore with a flag switched on, and it is not proposed as a
 * replacement. It delegates every read, and the input path, to a real FileStore,
 * and reimplements only the two writes it needs to force. Read its numbers as
 * the price of the syscall on the machine that ran it, not as a measurement of
 * some other version of FileStore.
 *
 * <p>It lives in Tandem's own package so that it can call the same
 * {@code JsonLine} encoder rather than a second one that could drift from it.
 * It is compiled from {@code bench/src} and is not part of the published jar.
 *
 * <p>It computes the events path itself, which is only correct while run ids and
 * step names need no escaping. It refuses anything else rather than writing to a
 * path FileStore would not have used, and the benchmark keeps to ids of letters,
 * digits and hyphens for that reason.
 */
public final class SyncedFileStore implements WorkflowStore {

    private final Path root;
    private final FileStore plain;

    public SyncedFileStore(Path root) {
        this.root = root;
        this.plain = new FileStore(root);
    }

    /**
     * Appends the event line and forces it.
     *
     * <p>{@code force(true)} rather than {@code force(false)}: the file's length
     * is metadata, and a log whose bytes are on the device while the length that
     * covers them is not is a log of zero bytes after a power cut. Forcing
     * metadata is the more expensive and the only honest choice here.
     *
     * <p>The channel is opened and closed per append, which is what a store with
     * no per-run state has to do. A store holding an open channel per run would
     * be faster and is a design Tandem has not taken; the number below is
     * therefore an upper bound on the cost, not the best achievable.
     */
    @Override
    public void append(WorkflowEvent event) {
        Path directory = directoryOf(event.runId());
        Path file = directory.resolve("events.jsonl");
        byte[] line =
                (JsonLine.encode(event.toMap()) + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(directory);
            // FileStore records the run id beside the events on first append.
            // Mirrored here so the two rows of the benchmark are doing the same
            // work per run and the difference between them is the fsync.
            recordId(directory, event.runId());
            try (FileChannel channel =
                    FileChannel.open(
                            file,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND)) {
                channel.write(ByteBuffer.wrap(line));
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not append to " + file, e);
        }
    }

    /** Written beside and moved into place as FileStore does, and forced first. */
    @Override
    public void saveOutput(String runId, String stepName, String encoded) {
        Path directory = directoryOf(runId).resolve("steps");
        try {
            Files.createDirectories(directory);
            Path temporary = directory.resolve(safe(stepName) + ".out.tmp");
            try (FileChannel channel =
                    FileChannel.open(
                            temporary,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(encoded.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            Files.move(
                    temporary,
                    directory.resolve(safe(stepName) + ".out"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not save output for " + stepName, e);
        }
    }

    @Override
    public List<WorkflowEvent> eventsFor(String runId) {
        return plain.eventsFor(runId);
    }

    @Override
    public List<String> listRuns() {
        return plain.listRuns();
    }

    @Override
    public void saveInput(String runId, String encoded) {
        plain.saveInput(runId, encoded);
    }

    @Override
    public Optional<String> loadInput(String runId) {
        return plain.loadInput(runId);
    }

    @Override
    public Optional<String> loadOutput(String runId, String stepName) {
        return plain.loadOutput(runId, stepName);
    }

    /** What FileStore's own recordId does, including tolerating a lost race. */
    private static void recordId(Path directory, String runId) throws IOException {
        Path file = directory.resolve("id");
        if (Files.exists(file)) {
            return;
        }
        Path temporary = Files.createTempFile(directory, "id", ".tmp");
        try {
            Files.writeString(temporary, runId, StandardCharsets.UTF_8);
            Files.move(temporary, file);
        } catch (java.nio.file.FileAlreadyExistsException lostTheRace) {
            // Someone else recorded it first, with the same content.
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path directoryOf(String runId) {
        return root.resolve(safe(runId));
    }

    /**
     * FileStore's escaping is private, so rather than reimplement it and risk
     * drifting from it, this refuses any name that would need escaping.
     */
    private static String safe(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean untouched =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '-';
            if (!untouched) {
                throw new IllegalArgumentException(
                        "the benchmark store only handles names FileStore leaves alone, got: "
                                + name);
            }
        }
        return name;
    }
}
