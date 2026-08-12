package io.github.martinkm.tandem;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A small command line over {@link WorkflowInspector}, pointed at a
 * {@link FileStore} directory.
 *
 * <pre>
 *   list &lt;dir&gt;            one run id and state per line
 *   show &lt;dir&gt; &lt;runId&gt;    a run's workflow, state and steps
 * </pre>
 *
 * <p>It reads and never writes, the same as the inspector it wraps. An unknown
 * command or the wrong number of arguments prints usage and exits 2; a missing
 * directory or run prints a message and exits 1.
 *
 * <p>The work is in {@link #run(String[], PrintStream, PrintStream)}, which
 * returns the exit code rather than calling {@link System#exit}, so a test can
 * drive it without ending the JVM. Only {@link #main} exits.
 */
public final class InspectorCli {

    private InspectorCli() {}

    /**
     * @param args the command line, see {@link InspectorCli the class docs}
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs one command and returns its exit code.
     *
     * @param out where a listing or description is written
     * @param err where usage and error messages are written
     * @return 0 on success, 1 for a missing directory or run, 2 for bad usage
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return usage(err);
        }
        return switch (args[0]) {
            case "list" -> list(args, out, err);
            case "show" -> show(args, out, err);
            default -> usage(err);
        };
    }

    private static int list(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            return usage(err);
        }
        Path directory = Path.of(args[1]);
        if (!Files.isDirectory(directory)) {
            err.println("no such directory: " + directory);
            return 1;
        }
        WorkflowInspector inspector = new WorkflowInspector(new FileStore(directory));
        for (WorkflowInspector.RunSummary summary : inspector.list()) {
            out.println(summary.runId() + "  " + summary.status());
        }
        return 0;
    }

    private static int show(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3) {
            return usage(err);
        }
        Path directory = Path.of(args[1]);
        if (!Files.isDirectory(directory)) {
            err.println("no such directory: " + directory);
            return 1;
        }
        String runId = args[2];
        WorkflowInspector inspector = new WorkflowInspector(new FileStore(directory));
        Optional<WorkflowInspector.RunDescription> found = inspector.describe(runId);
        if (found.isEmpty()) {
            err.println("no run " + runId + " in " + directory);
            return 1;
        }

        WorkflowInspector.RunDescription run = found.get();
        out.println("run: " + run.runId());
        out.println("workflow: " + run.workflowName().orElse("(unrecorded)"));
        out.println("status: " + run.status());
        run.stepInDoubt().ifPresent(step -> out.println("in doubt at: " + step));
        out.println("steps:");
        for (WorkflowInspector.StepView step : run.steps()) {
            out.println("  " + step.name() + "  " + label(step.outcome()));
        }
        return 0;
    }

    private static String label(StepStanding standing) {
        return switch (standing) {
            case DONE -> "done";
            case NOT_DONE -> "not run";
            case ENTERED -> "started, no outcome recorded";
            case UNDO_IN_DOUBT -> "compensation in doubt";
        };
    }

    private static int usage(PrintStream err) {
        err.println("usage: inspector list <dir>");
        err.println("       inspector show <dir> <runId>");
        return 2;
    }
}
