package com.bugtracking.model;

/**
 * Where the bug is in its life cycle:
 * Open → Assigned → In Progress → Fixed → Retest → Closed,
 * with Reopened sending a failed retest back to In Progress.
 */
public enum Status {
    OPEN("Open"),
    ASSIGNED("Assigned"),
    IN_PROGRESS("In Progress"),
    FIXED("Fixed"),
    RETEST("Retest"),
    CLOSED("Closed"),
    REOPENED("Reopened");

    private final String label;

    Status(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Column order for the board. Reopened sits next to Open because that is
     * where the work actually restarts, rather than at the end of the enum.
     */
    public static Status[] boardOrder() {
        return new Status[]{OPEN, REOPENED, ASSIGNED, IN_PROGRESS, FIXED, RETEST, CLOSED};
    }

    /** Everything before Fixed: the work still on somebody's plate. */
    public boolean isOpenWork() {
        return this != FIXED && this != RETEST && this != CLOSED;
    }

    /**
     * Position on the Open → Closed track, used to draw the stepper.
     * Reopened sits back at the start, because that is exactly what it means.
     */
    public int getStep() {
        return switch (this) {
            case OPEN, REOPENED -> 0;
            case ASSIGNED -> 1;
            case IN_PROGRESS -> 2;
            case FIXED -> 3;
            case RETEST -> 4;
            case CLOSED -> 5;
        };
    }
}
