package me.blinkdev.tandem;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs workflows later, or repeatedly.
 *
 * <p>Threads are daemons, so a scheduler left open does not keep the JVM alive.
 * That is the friendlier default for a library: forgetting to close one should
 * not hang a process at shutdown.
 *
 * <p>This is in-process scheduling. It does not survive a restart and does not
 * coordinate across machines, so two instances of your application both running
 * {@link #every} will each fire. Distributed scheduling with leader election is
 * on the roadmap and is not here.
 */
public final class Scheduler implements AutoCloseable {

    private final WorkflowEngine engine;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;

    public Scheduler(WorkflowEngine engine) {
        this(engine, 2);
    }

    public Scheduler(WorkflowEngine engine, int threads) {
        this.engine = Objects.requireNonNull(engine, "engine");
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, got " + threads);
        }
        this.executor =
                Executors.newScheduledThreadPool(
                        threads,
                        runnable -> {
                            Thread thread = new Thread(runnable, "tandem-scheduler");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.ownsExecutor = true;
    }

    /** Use an executor you already own. {@link #close()} will not shut it down. */
    public Scheduler(WorkflowEngine engine, ScheduledExecutorService executor) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = false;
    }

    /** Run once, after {@code delay}. */
    public <I, O> ScheduledFuture<RunResult<O>> after(
            Workflow<I, O> workflow, I input, Duration delay) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(delay, "delay");
        Callable<RunResult<O>> task = () -> engine.run(workflow, input);
        return executor.schedule(task, Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    /** Repeat, waiting {@code period} after each run finishes. */
    public <I, O> ScheduledFuture<?> every(Workflow<I, O> workflow, I input, Duration period) {
        return every(workflow, input, period, period);
    }

    /**
     * Repeat with an initial delay.
     *
     * <p>The gap is measured from the end of one run to the start of the next,
     * not from start to start. At a fixed rate, a run that takes longer than its
     * period causes the next to start immediately, and a slow dependency turns
     * into a stampede.
     *
     * <p>A run that throws is swallowed so the schedule survives it. Attach a
     * {@link WorkflowListener} if you need to see those.
     */
    public <I, O> ScheduledFuture<?> every(
            Workflow<I, O> workflow, I input, Duration initialDelay, Duration period) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(period, "period");

        Runnable task =
                () -> {
                    try {
                        engine.run(workflow, input);
                    } catch (RuntimeException ignored) {
                        // One bad run must not cancel the schedule, which is what
                        // an escaping exception would do.
                    }
                };
        return executor.scheduleWithFixedDelay(
                task,
                Math.max(0L, initialDelay.toMillis()),
                Math.max(1L, period.toMillis()),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }
}
