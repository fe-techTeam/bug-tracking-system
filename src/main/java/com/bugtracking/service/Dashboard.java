package com.bugtracking.service;

import java.util.List;
import java.util.Map;

/**
 * Everything the middle column of the board needs for one scope — a single
 * project, or all of them.
 *
 * @param project     the project this describes, or null for the whole board
 * @param total       bugs in scope
 * @param byStatus    count per status label, in lifecycle order
 * @param bySeverity  count per severity label, worst first
 * @param byPriority  count per priority label, P1 first
 * @param urgent      P1 + P2 — the ones that should not be sitting in the queue
 * @param open        not yet Fixed, Retest or Closed: the actual workload
 * @param maxStatus   the largest per-status count, so bars can be scaled
 * @param bugIds      ids in scope, used to narrow the activity timeline
 */
public record Dashboard(
        String project,
        long total,
        Map<String, Long> byStatus,
        Map<String, Long> bySeverity,
        Map<String, Long> byPriority,
        long urgent,
        long open,
        long maxStatus,
        List<Long> bugIds) {

    public boolean isEmpty() {
        return total == 0;
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
