package com.bugtracking.model;

/**
 * What one entry in a project's documents area is.
 *
 * <p>Five kinds share one table and one tree, because to the person browsing
 * them they are one thing: the stuff you need to work on this project. A
 * folder holds others; a page and a sheet are written here; a file was
 * uploaded; a link points somewhere else entirely. Declared in the order they
 * are listed in, so a folder never sorts below a link.
 */
public enum ResourceKind {

    /** Holds other entries. The only kind with children. */
    FOLDER("Folder", "New folder", "i-folder", "",
            "A place to group everything about one part of the project."),

    /** Markdown, written in the browser. */
    PAGE("Page", "Untitled page", "i-file-text", "md",
            "Notes, a spec, a runbook — anything you would write out."),

    /** A grid with formatting, stored as JSON. */
    SHEET("Sheet", "Untitled sheet", "i-table", "csv",
            "A spreadsheet — trackers, test data, sign-off matrices."),

    /** An uploaded file. The bytes live on disk; this row is the pointer. */
    FILE("File", "File", "i-file", "",
            "A PDF, a screenshot, an export — whatever you already have."),

    /** A URL somebody on the project needs to hand: Figma, a dashboard, a doc. */
    LINK("Link", "New link", "i-link", "",
            "Figma, a staging URL, a dashboard — the addresses people keep re-asking for.");

    private final String label;
    private final String defaultName;
    private final String icon;
    private final String extension;
    private final String blurb;

    ResourceKind(String label, String defaultName, String icon, String extension, String blurb) {
        this.label = label;
        this.defaultName = defaultName;
        this.icon = icon;
        this.extension = extension;
        this.blurb = blurb;
    }

    public String getLabel() {
        return label;
    }

    /** What an entry is called until somebody renames it. */
    public String getDefaultName() {
        return defaultName;
    }

    /** The sprite symbol id, so no template ever hard-codes one. */
    public String getIcon() {
        return icon;
    }

    /** What an export of this kind is called. Blank where there is nothing to export. */
    public String getExtension() {
        return extension;
    }

    public String getBlurb() {
        return blurb;
    }

    /** The two kinds the in-browser editor opens. */
    public boolean isDocument() {
        return this == PAGE || this == SHEET;
    }

    public boolean isFolder() {
        return this == FOLDER;
    }

    /** The kinds a person can create from the New menu — a file is uploaded, not created. */
    public boolean isCreatable() {
        return this != FILE;
    }
}
