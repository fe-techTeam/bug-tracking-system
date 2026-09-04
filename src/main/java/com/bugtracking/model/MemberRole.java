package com.bugtracking.model;

/**
 * What somebody is allowed to administer.
 *
 * <p>Two roles for the people inside. This is a small internal tracker, and the
 * only question it needs to answer about them is "may this person change the
 * setup everybody else works inside" — the projects, the roster, who can sign in.
 * Everything to do with the <em>work</em> — raising a bug, moving a card,
 * commenting, renaming a column on the board — stays open to everyone signed
 * in, because a bug tracker that asks permission before letting you file a bug
 * is a bug tracker people route around.
 *
 * <p>{@link #GUEST} is not a third point on that line — it is somebody from
 * outside the company, and what it may reach is an allowlist rather than a
 * subtraction. Its own comment says why.
 *
 * <p>A permission matrix would have been the other way to do this, and is what
 * this deliberately is not: a table of checkboxes nobody can hold in their head
 * ends up with everything ticked. Three roles are three answers.
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
    ADMIN("Admin", "also manages projects, people and passwords"),

    /**
     * A client, from outside. Raises reports on the one project they are bound
     * to and follows those reports, and reaches nothing else in this app.
     *
     * <p>The third role the paragraphs above said there would not be, and it is
     * a different <em>kind</em> of answer from the other two rather than a third
     * point on the same line. MEMBER and ADMIN divide the setup from the work,
     * and both are people inside the company who are trusted with the board. A
     * guest is not on the board at all: what they may do is an allowlist of four
     * screens, and the reason it is a role and not a permission matrix is that
     * everything outside that list is refused without anybody having to
     * enumerate it.
     *
     * <p>Which is why a guest is the one role that does <em>not</em> get
     * ROLE_USER — see {@code AccountPrincipal.authorities}. Every route this app
     * has, and every route it grows, is closed to them until a line in
     * {@code SecurityConfig} opens it.
     */
    GUEST("Client", "raises and follows reports on one project");

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

    /** Whether this role is somebody from outside rather than on the team. */
    public boolean isGuest() {
        return this == GUEST;
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
