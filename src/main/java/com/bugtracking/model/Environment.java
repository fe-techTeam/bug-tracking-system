package com.bugtracking.model;

/** Where the bug was seen. */
public enum Environment {
    QA("QA"),
    UAT("UAT"),
    PRODUCTION("Production");

    private final String label;

    Environment(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
