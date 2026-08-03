package io.github.martinkm.tandem;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A run's events, folded into the few questions anybody asks of them.
 *
 * <p>The engine loads a run's events before resuming and a recovery scan loads
 * the same events to classify it. Reading them twice, in two places, with two
 * slightly different ideas of what a started event means, is how the two end up
 * disagreeing about whether a step already happened. So the reading lives here
 * once, and both callers ask it.
 *
 * <p>Events that say nothing about the world are ignored on purpose. A replay is
 * a note that a value was reused, and a doubt is a note that a resume stopped;
 * counting either would let a run talk itself out of its own doubt.
 */
final class RunLog {

    private final List<WorkflowEvent> events;
    private final Map<String, StepStanding> standings;
    private final String workflowName;
    private final EventType ending;

    RunLog(List<WorkflowEvent> events) {
        this.events = events;

        Map<String, Tally> tallies = new HashMap<>();
        String name = "";
        EventType end = null;

        for (WorkflowEvent event : events) {
            // Settlements written out of band carry no workflow name, so the
            // first one that does is the run's.
            if (name.isEmpty()) {
                name = event.workflowName();
            }
            switch (event.type()) {
                // A new attempt reopens the run: what matters is how the latest
                // one ended, not that an earlier one failed.
                case RUN_STARTED, RUN_RESUMED -> end = null;
                case RUN_SUCCEEDED, RUN_FAILED -> end = event.type();
                default -> tallies
                        .computeIfAbsent(event.stepName(), key -> new Tally())
                        .fold(event.type());
            }
        }

        Map<String, StepStanding> folded = new HashMap<>();
        tallies.forEach((step, tally) -> folded.put(step, tally.standing()));
        this.standings = Map.copyOf(folded);
        this.workflowName = name;
        this.ending = end;
    }

    boolean isEmpty() {
        return events.isEmpty();
    }

    /** The workflow this run belongs to, or empty for a run with no named events. */
    String workflowName() {
        return workflowName;
    }

    StepStanding standingOf(String stepName) {
        return standings.getOrDefault(stepName, StepStanding.NOT_DONE);
    }

    /**
     * How the latest attempt ended, or null while it has not: that is what a
     * process dying looks like from the log.
     */
    EventType ending() {
        return ending;
    }

    List<WorkflowEvent> events() {
        return events;
    }

    Instant startedAt() {
        return events.get(0).at();
    }

    Instant lastEventAt() {
        return events.get(events.size() - 1).at();
    }

    /** One step's events, reduced as they are read. */
    private static final class Tally {

        private int entered;
        private boolean stands;
        private boolean undoUnknown;

        void fold(EventType type) {
            switch (type) {
                case STEP_STARTED -> entered++;
                case STEP_SUCCEEDED -> {
                    settle();
                    stands = true;
                    undoUnknown = false;
                }
                case STEP_FAILED, STEP_RETRYING -> {
                    settle();
                    stands = false;
                    undoUnknown = false;
                }
                case STEP_COMPENSATED -> {
                    stands = false;
                    undoUnknown = false;
                }
                case STEP_COMPENSATION_FAILED -> undoUnknown = true;
                default -> { }
            }
        }

        /**
         * Every attempt writes a started event before the step runs and exactly
         * one of succeeded, retrying or failed after it, so an outcome closes
         * the attempt it belongs to. An attempt still open at the end of the log
         * is one the process died inside.
         */
        private void settle() {
            if (entered > 0) {
                entered--;
            }
        }

        StepStanding standing() {
            if (entered > 0) {
                return StepStanding.ENTERED;
            }
            if (undoUnknown) {
                return StepStanding.UNDO_IN_DOUBT;
            }
            return stands ? StepStanding.DONE : StepStanding.NOT_DONE;
        }
    }
}
