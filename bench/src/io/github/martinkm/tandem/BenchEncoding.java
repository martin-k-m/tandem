package io.github.martinkm.tandem;

/**
 * Reaches the package private JSON encoder so the benchmark can time it on its
 * own, without keeping a second copy of it that could drift.
 *
 * <p>Compiled from {@code bench/src} and not part of the published jar.
 */
public final class BenchEncoding {

    private BenchEncoding() {}

    /** Exactly the line {@link FileStore#append} would write, minus the newline. */
    public static String encode(WorkflowEvent event) {
        return JsonLine.encode(event.toMap());
    }
}
