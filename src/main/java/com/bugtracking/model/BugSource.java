package com.bugtracking.model;

/** Where a bug came from: the team, a client's portal, or the public link. */
public enum BugSource {
    INTERNAL("Internal"),
    CLIENT("Client"),
    EXTERNAL("External");

    private final String label;

    BugSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
