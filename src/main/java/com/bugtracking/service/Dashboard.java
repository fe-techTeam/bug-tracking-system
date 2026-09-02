package com.bugtracking.service;

import java.util.List;
import java.util.Map;

/**
 * Everything the middle column of the board needs for one scope — a single
 * project, or all of them.
 *
 * @param project     the project this describes, or null for the whole board
 * @param total       bugs in scope
 * @param byStatus    count per column key, in board order — keyed by the key
 *                    rather than the wording, since two projects may name two
 *                    different columns the same thing and the whole-board view
 *                    shows both
 * @param bySeverity  count per severity label, worst first
 * @param urgent      Critical + High severity, still open — the ones that
 *                    should not be sitting in the queue
 * @param open        sitting in a column its project does not count as done:
 *                    the actual workload
 * @param maxStatus   the largest per-status count, so bars can be scaled
 * @param bugIds      ids in scope, used to narrow the activity timeline
 */
public record Dashboard(
        String project,
        long total,
        Map<String, Long> byStatus,
        Map<String, Long> bySeverity,
        long urgent,
        long open,
        long maxStatus,
        List<Long> bugIds) {

    public boolean isEmpty() {
        return total == 0;
    }

    /**
     * Everything in a column its project counts as finished. Derived rather
     * than counted, because "done" is no longer one named status to look up —
     * a project can have several, or call its only one something else.
     */
    public long done() {
        return total - open;
    }

    /** Bar length as a percentage of the busiest status. Never divides by zero. */
    public double share(long count) {
        return maxStatus == 0 ? 0 : (count * 100.0) / maxStatus;
    }

    /** Percentage of the scope, for the stacked distribution bar. */
    public double percent(long count) {
        return total == 0 ? 0 : (count * 100.0) / total;
    }
}
