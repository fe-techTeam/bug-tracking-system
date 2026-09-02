package com.bugtracking.model;

/**
 * The palette a board column may be painted in.
 *
 * <p>Columns are yours to name and colour, but not to colour <em>freely</em>.
 * The board's first rule is that status reads as a journey — cool while it
 * waits, warm when it needs a person, green when it is done — and a free hex
 * field is how that rule dies: one #ff00ff and the column order stops meaning
 * anything at a glance. So a colour is a token, chosen from a list, and the
 * stylesheet owns what each one actually is in light and in dark. Nothing here
 * knows a hex value; {@link #getToken()} hands the template a {@code var()}.
 *
 * <p>Listed along the journey, so the picker reads left to right the way the
 * board does. Rose sits at the end for the columns that are an ending without
 * being a success — Rejected, Won't fix, Duplicate.
 */
public enum ColumnColour {

    SLATE("Slate", "waiting"),
    BLUE("Blue", "in flight"),
    INDIGO("Indigo", "in flight"),
    VIOLET("Violet", "parked"),
    TEAL("Teal", "with QA"),
    AMBER("Amber", "needs a person"),
    GREEN("Green", "done"),
    ROSE("Rose", "closed unhappily");

    private final String label;
    private final String reads;

    ColumnColour(String label, String reads) {
        this.label = label;
        this.reads = reads;
    }

    public String getLabel() {
        return label;
    }

    /** What the colour says on the board, for the picker's caption. */
    public String getReads() {
        return reads;
    }

    /** What a template puts in {@code --c}. */
    public String getToken() {
        return "var(--col-" + name() + ")";
    }

    /** Falls back rather than throwing: a colour dropped from this list one day
     *  should grey a column, not 500 the whole board. */
    public static ColumnColour of(String name) {
        if (name != null) {
            for (ColumnColour colour : values()) {
                if (colour.name().equalsIgnoreCase(name.trim())) {
                    return colour;
                }
            }
        }
        return SLATE;
    }
}
