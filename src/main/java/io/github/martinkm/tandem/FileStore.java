package io.github.martinkm.tandem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes the log and step outputs under a directory, so a run survives a
 * restart and can be resumed.
 *
 * <p>Layout, one directory per run:
 *
 * <pre>
 *   &lt;root&gt;/&lt;runId&gt;/events.jsonl      one JSON object per line, append-only
 *   &lt;root&gt;/&lt;runId&gt;/steps/&lt;name&gt;.out  one encoded step output per file
 * </pre>
 *
 * <p>Append-only rather than a single rewritten file: a crash midway through a
 * rewrite can lose the whole history, while a torn append loses only the last
 * line, and the reader skips lines it cannot parse.
 *
 * <p>Durability is at the level the filesystem gives; there is no fsync per
 * event. A machine losing power may lose the last few events. That is the right
 * trade for a workflow log and the wrong one for a ledger.
 */
public final class FileStore implements WorkflowStore {

    private final Path root;

    public FileStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + root, e);
        }
    }

    @Override
    public void append(WorkflowEvent event) {
        Path file = runDirectory(event.runId()).resolve("events.jsonl");
        String line = JsonLine.encode(event.toMap()) + System.lineSeparator();
        try {
            Files.writeString(
                    file,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("could not append to " + file, e);
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
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
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

    @Override
    public void saveOutput(String runId, String stepName, String encoded) {
        Path directory = runDirectory(runId).resolve("steps");
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(safeName(stepName) + ".out");
            // Write beside, then move: a reader never sees a half-written output.
            Path temporary = directory.resolve(safeName(stepName) + ".out.tmp");
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not save output for " + stepName, e);
        }
    }

    @Override
    public Optional<String> loadOutput(String runId, String stepName) {
        Path file =
                root.resolve(safeName(runId))
                        .resolve("steps")
                        .resolve(safeName(stepName) + ".out");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    private Path runDirectory(String runId) {
        Path directory = root.resolve(safeName(runId));
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + directory, e);
        }
        return directory;
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
     * <p>Replacement alone is not injective: {@code "a/b"} and {@code "a_b"}
     * both flatten to {@code "a_b"}, and two runs sharing a directory would
     * interleave their event logs and resume from each other. Anything that had
     * to be replaced therefore carries a suffix derived from the original, so
     * distinct ids stay distinct. Ids that need no replacement, which is nearly
     * all of them, are untouched and stay readable on disk.
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
                            || c == '_'
                            || c == '.';
            out.append(allowed ? c : '_');
        }
        // "." and ".." would resolve to a directory rather than a file.
        String result = out.toString().replace("..", "__");
        if (result.isBlank()) {
            result = "unnamed";
        }
        return result.equals(name) ? result : result + "-" + disambiguator(name);
    }

    /**
     * A short, stable tag for an original name, so two names that sanitise to
     * the same characters do not end up in the same directory.
     *
     * <p>{@link String#hashCode} rather than a digest: this only has to separate
     * names, not resist anyone choosing them, and a digest would mean a
     * MessageDigest lookup every time a path is built.
     */
    private static String disambiguator(String name) {
        return Integer.toHexString(name.hashCode() & 0xFFFFFFF);
    }
}
