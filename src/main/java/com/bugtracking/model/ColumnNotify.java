package com.bugtracking.model;

/**
 * Who hears about a bug landing in a column.
 *
 * <p>This used to be a switch over the {@code Status} enum: Ready for Test and
 * Retest told the reporter, On Hold told the assignees, Closed told both, and
 * Open and In Progress told nobody. Once a column is something you invent, that
 * switch has nothing left to match on — so the rule moves onto the column
 * itself and becomes a setting. Seeded so the six original columns behave
 * exactly as they always did.
 */
public enum ColumnNotify {

    /** The everyday columns. Moving work along is not news. */
    NOBODY("Nobody", null),

    /** Handing it back to whoever raised it: it needs their eyes now. */
    REPORTER("Whoever raised it", "fixed"),

    /** Something happened to the work itself, so the people on it should know. */
    ASSIGNEES("Whoever is on it", "reopened"),

    /** An ending. Everyone the bug names hears about it. */
    EVERYONE("Both", "closed");

    private final String label;

    /**
     * The notification's stored type, which is what picks its icon and colour
     * on the notifications page. Kept to the four names that page already
     * draws — a fifth would render as an unstyled row.
     */
    private final String type;

    ColumnNotify(String label, String type) {
        this.label = label;
        this.type = type;
    }

    public String getLabel() {
        return label;
    }

    public String getType() {
        return type;
    }

    public boolean isSilent() {
        return this == NOBODY;
    }

    /** Falls back to silence rather than throwing on a value this version does not know. */
    public static ColumnNotify of(String name) {
        if (name != null) {
            for (ColumnNotify notify : values()) {
                if (notify.name().equalsIgnoreCase(name.trim())) {
                    return notify;
                }
            }
        }
        return NOBODY;
    }
}
