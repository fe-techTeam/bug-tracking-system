package com.bugtracking.model;

/**
 * How urgently the business needs the fix — deliberately separate from
 * {@link Severity}. A Medium-severity bug can still be P1 if the business
 * needs it today.
 */
public enum Priority {
    P1("P1", "Immediate — drop other work"),
    P2("P2", "High — this cycle"),
    P3("P3", "Medium — schedule normally"),
    P4("P4", "Low — when there is room");

    private final String label;
    private final String description;

    Priority(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /** P1 and P2 are the ones that should not sit in the queue. */
    public boolean isUrgent() {
        return this == P1 || this == P2;
    }
}
