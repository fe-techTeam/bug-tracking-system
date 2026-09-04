package com.bugtracking.service;

import com.bugtracking.model.Attachment;
import com.bugtracking.model.Bug;
import com.bugtracking.model.Comment;
import com.bugtracking.model.DocType;
import com.bugtracking.model.SupportingDoc;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A whole bug written out as Markdown, for pasting somewhere else.
 *
 * <p>The somewhere else is usually an assistant: a developer picking a bug up
 * wants the report, the reproduction, the environment, what QA wrote up and
 * what the thread said, in one block they can hand over without retyping any
 * of it. That is why this is one document rather than a JSON dump — the
 * headings are the structure, and the prose is left exactly as it was typed.
 *
 * <p>Everything here is a <em>copy</em>, so it is deliberately generous with
 * context and deliberately mean with size: a page is included up to
 * {@link #DOC_CHARS} characters and a sheet up to {@link #SHEET_ROWS} rows,
 * because a clipboard that will not paste is worse than one that says what it
 * left behind. Both cuts announce themselves in the text.
 */
@Service
public class BugMarkdown {

    /** How much of one written-up page travels with the bug. */
    private static final int DOC_CHARS = 6000;

    /** How many rows of one sheet travel with it. */
    private static final int SHEET_ROWS = 60;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    /** A due date is a day, so it is written as one — no hour nobody set. */
    private static final DateTimeFormatter DUE =
            DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final BugService bugs;
    private final BoardColumnService board;
    private final CommentService comments;
    private final AttachmentService attachments;
    private final SupportingDocService docs;

    public BugMarkdown(BugService bugs,
                       BoardColumnService board,
                       CommentService comments,
                       AttachmentService attachments,
                       SupportingDocService docs) {
        this.bugs = bugs;
        this.board = board;
        this.comments = comments;
        this.attachments = attachments;
        this.docs = docs;
    }

    /**
     * The bug as Markdown.
     *
     * @param id      which bug
     * @param baseUrl where this instance lives, for the link in the footer, or
     *                null to leave the bug identified by its number alone
     */
    @Transactional(readOnly = true)
    public String forBug(Long id, String baseUrl) {
        Bug bug = bugs.findById(id);
        BoardColumns columns = board.snapshot();
        StringBuilder out = new StringBuilder(2048);

        out.append("# BUG-").append(bug.getId()).append(" — ").append(bug.getTitle()).append("\n\n");

        facts(out, bug, columns);
        report(out, bug);
        files(out, bug);
        written(out, bug);
        thread(out, bug);

        out.append("---\n\n_BUG-").append(bug.getId()).append(" in Bug Tracking");
        if (baseUrl != null && !baseUrl.isBlank()) {
            out.append(" · ").append(trimSlash(baseUrl)).append("/bugs/").append(bug.getId());
        }
        out.append("_\n");

        return out.toString();
    }

    // ------------------------------------------------------------- the facts

    /**
     * The rail, as a list. Only the facts that hold something: a line reading
     * "Module: —" is a line the reader has to discard.
     */
    private void facts(StringBuilder out, Bug bug, BoardColumns columns) {
        fact(out, "Status", columns.label(bug));
        // The same "3 of 4" the meter paints — severity is declared worst
        // first, so the filled count is 4 - ordinal.
        fact(out, "Severity", bug.getSeverity() == null ? null
                : bug.getSeverity().getLabel() + " (" + (4 - bug.getSeverity().ordinal()) + " of 4)");
        fact(out, "Environment", bug.getEnvironment() == null ? null : bug.getEnvironment().getLabel());
        fact(out, "Project", bug.getProject());
        fact(out, "Module", bug.getModule());
        fact(out, "Raised by", bug.getReportedBy());
        fact(out, "Assigned to", bug.getAssignees().isEmpty() ? "Nobody" : bug.getAssigneesLabel());

        // Only while the blocker is still open, exactly as the bug page reads
        // it: waiting on something already fixed is not being blocked.
        if (bug.getBlockedBy() != null) {
            Map<Long, Bug> found = bugs.blockersFor(List.of(bug));
            Bug blocker = found.get(bug.getBlockedBy());
            if (blocker != null && columns.openWork(blocker)) {
                fact(out, "Blocked by", "BUG-" + blocker.getId() + " — " + blocker.getTitle());
            }
        }

        // Whether it is late is the board's question, not the date's — a bug in
        // a finished column is done, whatever the date said. Worth carrying:
        // this document is usually pasted to somebody being asked to pick the
        // bug up, and "overdue" is the first thing they should know.
        fact(out, "Due", bug.getDueDate() == null ? null
                : DUE.format(bug.getDueDate()) + (columns.late(bug) ? " (overdue)" : ""));
        fact(out, "Raised", stamp(bug.getCreatedAt()));
        fact(out, "Last updated", stamp(bug.getUpdatedAt()));
        out.append('\n');
    }

    private void fact(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append("- **").append(label).append(":** ").append(oneLine(value)).append('\n');
        }
    }

    // ------------------------------------------------------------ the report

    /** One section, because the report is one box — see {@code Bug.description}. */
    private void report(StringBuilder out, Bug bug) {
        section(out, "Description", blank(bug.getDescription())
                ? "_No details provided._" : bug.getDescription().trim());
    }

    // --------------------------------------------------------------- the files

    /** Names and sizes only — the bytes are not something a paste can carry. */
    private void files(StringBuilder out, Bug bug) {
        List<Attachment> files = attachments.forBug(bug.getId());
        if (files.isEmpty()) {
            return;
        }
        out.append("## Attachments (").append(files.size()).append(")\n\n");
        for (Attachment file : files) {
            out.append("- `").append(file.getFileName()).append('`')
                    .append(" — ").append(file.getReadableSize());
            if (file.isImage()) {
                out.append(", image");
            }
            if (file.getUploadedBy() != null && !file.getUploadedBy().isBlank()) {
                out.append(", uploaded by ").append(file.getUploadedBy());
            }
            out.append('\n');
        }
        out.append('\n');
    }

    // ----------------------------------------------------------- the write-ups

    /**
     * The supporting docs, contents and all. A page arrives as the Markdown it
     * already is; a sheet is turned into a Markdown table, since a grid pasted
     * as JSON is a grid nobody reads.
     */
    private void written(StringBuilder out, Bug bug) {
        List<SupportingDoc> written = docs.forBug(bug.getId());
        if (written.isEmpty()) {
            return;
        }
        out.append("## Supporting docs (").append(written.size()).append(")\n\n");
        for (SupportingDoc doc : written) {
            out.append("### ").append(doc.getTitle())
                    .append(" (").append(doc.getType().getLabel().toLowerCase()).append(")\n\n");

            if (doc.isBlank()) {
                out.append("_Empty._\n\n");
            } else if (doc.getType() == DocType.SHEET) {
                table(out, docs.sheet(doc));
            } else {
                out.append(demote(clip(doc.getContent().trim(), DOC_CHARS))).append("\n\n");
            }
        }
    }

    /**
     * A sheet as a Markdown table, first row as the heading.
     *
     * <p>Rows are squared off to the sheet's own width and the blank ones every
     * grid trails are dropped, so an eight-row test plan does not paste as
     * three hundred rows of nothing.
     */
    private void table(StringBuilder out, SupportingDocService.Sheet sheet) {
        List<List<String>> rows = filled(sheet.rows());
        if (rows.isEmpty()) {
            out.append("_Empty._\n\n");
            return;
        }

        int width = Math.max(1, sheet.cols());
        row(out, rows.get(0), width);
        out.append('|');
        for (int i = 0; i < width; i++) {
            out.append(" --- |");
        }
        out.append('\n');

        int shown = Math.min(rows.size(), SHEET_ROWS + 1);
        for (int i = 1; i < shown; i++) {
            row(out, rows.get(i), width);
        }
        if (rows.size() > shown) {
            out.append("\n_… and ").append(rows.size() - shown).append(" more rows._\n");
        }
        out.append('\n');
    }

    /**
     * Pushes a page's own headings down under the {@code ###} its title got.
     *
     * <p>A tester writing up a run starts at {@code ##}, which lands level with
     * "Supporting docs" and makes the write-up read as a sibling of the bug
     * rather than as part of it. Three levels down puts it back inside its own
     * document. Fenced blocks are left alone: a {@code #} at the start of a
     * line in one is a shell prompt or a comment, not a heading.
     */
    private static String demote(String markdown) {
        StringBuilder out = new StringBuilder(markdown.length() + 32);
        boolean fenced = false;
        for (String line : markdown.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced;
            } else if (!fenced && trimmed.matches("#{1,6}\\s.*")) {
                int depth = 0;
                while (trimmed.charAt(depth) == '#') {
                    depth++;
                }
                line = "#".repeat(Math.min(6, depth + 3)) + trimmed.substring(depth);
            }
            out.append(line).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    private void row(StringBuilder out, List<String> cells, int width) {
        out.append('|');
        for (int i = 0; i < width; i++) {
            out.append(' ').append(cell(i < cells.size() ? cells.get(i) : "")).append(" |");
        }
        out.append('\n');
    }

    /** A cell that cannot break the table: no bar, no newline. */
    private static String cell(String value) {
        return value == null ? "" : value.trim().replace("|", "\\|").replaceAll("\\s*\\R\\s*", "<br>");
    }

    /** The rows up to the last one somebody typed in. */
    private static List<List<String>> filled(List<List<String>> rows) {
        int last = -1;
        for (int i = 0; i < rows.size(); i++) {
            for (String value : rows.get(i)) {
                if (value != null && !value.isBlank()) {
                    last = i;
                    break;
                }
            }
        }
        return last < 0 ? List.of() : new ArrayList<>(rows.subList(0, last + 1));
    }

    // -------------------------------------------------------------- the thread

    /** The conversation, quoted, oldest first — the order it was read in. */
    private void thread(StringBuilder out, Bug bug) {
        List<Comment> said = comments.forBug(bug.getId());
        if (said.isEmpty()) {
            return;
        }
        out.append("## Comments (").append(said.size()).append(")\n\n");
        for (Comment comment : said) {
            out.append("**").append(comment.getCreatedBy() == null ? "unknown" : comment.getCreatedBy())
                    .append("** · ").append(stamp(comment.getCreatedAt())).append("\n\n");
            out.append(quote(comment.getText())).append("\n\n");
        }
    }

    // ---------------------------------------------------------------- plumbing

    private void section(StringBuilder out, String heading, String body) {
        out.append("## ").append(heading).append("\n\n").append(body).append("\n\n");
    }

    private static String quote(String text) {
        if (text == null || text.isBlank()) {
            return "> _(empty)_";
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.trim().split("\\R", -1)) {
            out.append("> ").append(line).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    /** Folds a value onto one line, so a fact list stays a fact list. */
    private static String oneLine(String value) {
        return value.trim().replaceAll("\\s*\\R\\s*", " ");
    }

    private static String clip(String text, int max) {
        return text.length() <= max
                ? text
                : text.substring(0, max) + "\n\n_… trimmed at " + max + " characters._";
    }

    private static String stamp(LocalDateTime when) {
        return when == null ? null : STAMP.format(when);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
