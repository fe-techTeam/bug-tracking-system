package com.bugtracking.model;

/**
 * The two shapes a supporting document comes in: something you write, and
 * something you tabulate.
 *
 * <p>A test plan or a sign-off note is prose; a set of test cases with a
 * pass/fail column is a grid. The type is chosen when the document is created
 * and never changes — the editor, the stored content and the export all hang
 * off it.
 */
public enum DocType {

    /** Markdown, stored as the text the tester typed. */
    PAGE("Page", "Untitled page", "i-file-text", "md", "text/markdown",
            "Notes, a test plan, a sign-off — anything you would write out."),

    /** A grid, stored as {@code {"cols":6,"rows":[[…],[…]]}}. */
    SHEET("Sheet", "Untitled sheet", "i-table", "csv", "text/csv",
            "A grid — test cases, test data, a pass/fail matrix.");

    private final String label;
    private final String defaultTitle;
    private final String icon;
    private final String extension;
    private final String mediaType;
    private final String blurb;

    DocType(String label, String defaultTitle, String icon,
            String extension, String mediaType, String blurb) {
        this.label = label;
        this.defaultTitle = defaultTitle;
        this.icon = icon;
        this.extension = extension;
        this.mediaType = mediaType;
        this.blurb = blurb;
    }

    public String getLabel() {
        return label;
    }

    /** What a document is called until somebody renames it. */
    public String getDefaultTitle() {
        return defaultTitle;
    }

    /** The sprite symbol id, so a template never hard-codes one. */
    public String getIcon() {
        return icon;
    }

    /** What the download is called, and what it is. */
    public String getExtension() {
        return extension;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getBlurb() {
        return blurb;
    }
}
