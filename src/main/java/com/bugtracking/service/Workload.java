package com.bugtracking.service;

/**
 * What one person is carrying, for the team page and their own page.
 *
 * @param reported  bugs they raised
 * @param assigned  bugs currently on their plate
 * @param open      of those assigned, the ones not yet Fixed, Retest or Closed
 * @param urgent    of those assigned, the P1s and P2s
 * @param critical  of those assigned, the Critical-severity ones
 */
public record Workload(long reported, long assigned, long open, long urgent, long critical) {

    public static final Workload NONE = new Workload(0, 0, 0, 0, 0);

    /** Anything at all on record — drives whether removal is offered. */
    public long touched() {
        return reported + assigned;
    }

    /** How much of their queue still needs work, for the little bar. */
    public double openShare() {
        return assigned == 0 ? 0 : (open * 100.0) / assigned;
    }
}
