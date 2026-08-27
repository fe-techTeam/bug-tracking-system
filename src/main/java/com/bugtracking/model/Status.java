package com.bugtracking.model;

/** Where the bug is in its life cycle. */
public enum Status {
    OPEN("Open"),
    IN_PROGRESS("In Progress"),
    FIXED("Fixed"),
    CLOSED("Closed"),
    REOPENED("Reopened");

    private final String label;

    Status(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
