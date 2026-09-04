package com.bugtracking.model;

/**
 * What somebody is allowed to administer.
 *
 * <p>Two roles and no more. This is a small internal tracker, and the only
 * question it has ever needed to answer is "may this person change the setup
 * everybody else works inside" — the projects, the roster, who can sign in.
 * Everything to do with the <em>work</em> — raising a bug, moving a card,
 * commenting, renaming a column on the board — stays open to everyone signed
 * in, because a bug tracker that asks permission before letting you file a bug
 * is a bug tracker people route around.
 *
 * <p>A permission matrix would have been the other way to do this, and is what
 * this deliberately is not: a table of checkboxes nobody can hold in their head
 * ends up with everything ticked. Two roles are two answers.
 *
 * <p>Stored as a plain {@code varchar} like every other enum here, so adding a
 * third one day is a constant and not a migration — see
 * {@link BoardColumn#getColour()} for what a native enum column cost the last
 * time.
 */
public enum MemberRole {

    /** Can raise, comment, move and be assigned. What everybody is. */
    MEMBER("Member", "raises and works on bugs"),

    /**
     * The above, plus the setup: projects, the roster, board columns, and
     * anybody's password.
     */
    ADMIN("Admin", "also manages projects, people and passwords");

    private final String label;
    private final String reads;

    MemberRole(String label, String reads) {
        this.label = label;
        this.reads = reads;
    }

    public String getLabel() {
        return label;
    }

    /** What the role means, for the picker's caption. */
    public String getReads() {
        return reads;
    }

    /** Falls back rather than throwing, the way {@link ColumnColour#of} does. */
    public static MemberRole of(String name) {
        if (name != null) {
            for (MemberRole role : values()) {
                if (role.name().equalsIgnoreCase(name.trim())) {
                    return role;
                }
            }
        }
        return MEMBER;
    }
}
