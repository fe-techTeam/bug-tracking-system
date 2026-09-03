package com.bugtracking.model;

public enum ThemePreference {
    SYSTEM(null), LIGHT("light"), DARK("dark");

    private final String attr;

    ThemePreference(String attr) {
        this.attr = attr;
    }

    public String attr() {
        return attr;
    }
}
