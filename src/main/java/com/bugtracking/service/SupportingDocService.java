package com.bugtracking.service;

import com.bugtracking.model.DocType;
import com.bugtracking.model.SupportingDoc;
import com.bugtracking.repository.SupportingDocRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * The supporting documents on a bug: creating them, saving what was typed, and
 * turning a sheet's stored JSON into something a template or a spreadsheet can
 * read.
 *
 * <p>Content edits are deliberately <em>not</em> written to the bug's history.
 * The editor saves as you type, so every keystroke-ish would become a timeline
 * entry and bury the changes that matter. Creating, renaming and deleting a
 * document are recorded; who last touched the body, and when, is on the
 * document itself.
 */
@Service
@Transactional
public class SupportingDocService {

    /** A new sheet opens with room to type into, not one empty cell. */
    private static final int NEW_ROWS = 12;
    private static final int NEW_COLS = 6;

    /** Ceilings, so a runaway paste cannot outgrow the column it is stored in. */
    public static final int MAX_ROWS = 300;
    public static final int MAX_COLS = 40;
    private static final int MAX_CONTENT = 100_000;

    private final SupportingDocRepository repository;
    private final BugHistoryService history;
    private final ObjectMapper json;

    public SupportingDocService(SupportingDocRepository repository,
                                BugHistoryService history,
                                ObjectMapper json) {
        this.repository = repository;
        this.history = history;
        this.json = json;
    }

    /** A sheet's contents, already squared off: every row is {@code cols} long. */
    public record Sheet(int cols, List<List<String>> rows) { }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<SupportingDoc> forBug(Long bugId) {
        return repository.findByBugIdOrderByUpdatedAtDesc(bugId);
    }

    @Transactional(readOnly = true)
    public long countForBug(Long bugId) {
        return repository.countByBugId(bugId);
    }

    /** One document, or a 404 — including for an id that belongs to another bug. */
    @Transactional(readOnly = true)
    public SupportingDoc find(Long bugId, Long docId) {
        return repository.findByIdAndBugId(docId, bugId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No document " + docId + " on bug #" + bugId + "."));
    }

    // ----------------------------------------------------------------- writes

    /**
     * Starts a new document. A blank page really is blank; a blank sheet is a
     * grid of empty cells, because an empty spreadsheet with nowhere to type is
     * not a starting point.
     */
    public SupportingDoc create(Long bugId, DocType type, String title, String actor) {
        DocType kind = type == null ? DocType.PAGE : type;
        SupportingDoc doc = new SupportingDoc();
        doc.setBugId(bugId);
        doc.setType(kind);
        doc.setTitle(cleanTitle(title, kind));
        doc.setContent(kind == DocType.SHEET ? blankSheetJson() : "");
        doc.setCreatedBy(BugHistoryService.actor(actor));
        doc.setUpdatedBy(doc.getCreatedBy());

        SupportingDoc saved = repository.save(doc);
        history.record(bugId, "doc", null, saved.getTitle(), saved.getCreatedBy());
        return saved;
    }

    /** Saves a page: the title and the Markdown behind it. */
    public SupportingDoc savePage(Long bugId, Long docId, String title, String content, String actor) {
        SupportingDoc doc = find(bugId, docId);
        String body = content == null ? "" : content;
        if (body.length() > MAX_CONTENT) {
            throw new IllegalArgumentException(
                    "This document is too long to save — keep it under "
                            + (MAX_CONTENT / 1000) + ",000 characters.");
        }
        rename(doc, title, actor);
        doc.setContent(body);
        doc.setSummary(pageSummary(body));
        return touch(doc, actor);
    }

    /**
     * Saves a sheet from the grid of cell inputs the form posted, applying an
     * add/remove row-or-column operation first when one was asked for.
     *
     * <p>The cells arrive row-major and flat, which is simply how a repeated
     * input posts. {@code op} is what the buttons beside the grid submit with
     * JavaScript switched off — with it on, the same edits happen in the page
     * and this only ever sees the finished grid.
     */
    public SupportingDoc saveSheet(Long bugId, Long docId, String title,
                                   List<String> cells, Integer cols, String op, String actor) {
        SupportingDoc doc = find(bugId, docId);
        int width = clamp(cols == null ? NEW_COLS : cols, 1, MAX_COLS);
        List<List<String>> rows = fold(cells, width);
        rows = apply(op, rows, width);
        width = rows.isEmpty() ? width : rows.get(0).size();

        rename(doc, title, actor);
        doc.setContent(toJson(new Sheet(width, rows)));
        doc.setSummary(sheetSummary(new Sheet(width, rows)));
        return touch(doc, actor);
    }

    /**
     * Saves a sheet the editor has already serialised. Same shape as
     * {@link #saveSheet}, but the browser did the folding — used by the
     * save-as-you-type request, which posts what is on screen.
     */
    public SupportingDoc saveSheetJson(Long bugId, Long docId, String title, String content, String actor) {
        SupportingDoc doc = find(bugId, docId);
        Sheet sheet = parse(content);
        rename(doc, title, actor);
        doc.setContent(toJson(sheet));
        doc.setSummary(sheetSummary(sheet));
        return touch(doc, actor);
    }

    /**
     * Just the name. Used when a save arrives carrying no body at all — a
     * truncated request must rename a document, never blank it.
     */
    public SupportingDoc rename(Long bugId, Long docId, String title, String actor) {
        SupportingDoc doc = find(bugId, docId);
        rename(doc, title, actor);
        return touch(doc, actor);
    }

    public SupportingDoc delete(Long bugId, Long docId, String actor) {
        SupportingDoc doc = find(bugId, docId);
        repository.delete(doc);
        history.record(bugId, "doc-removed", doc.getTitle(), null, BugHistoryService.actor(actor));
        return doc;
    }

    /** Everything on a bug that is being destroyed for good. */
    public void deleteForBug(Long bugId) {
        repository.deleteByBugId(bugId);
    }

    private void rename(SupportingDoc doc, String title, String actor) {
        String wanted = cleanTitle(title, doc.getType());
        if (!wanted.equals(doc.getTitle())) {
            history.record(doc.getBugId(), "doc-renamed", doc.getTitle(), wanted,
                    BugHistoryService.actor(actor));
            doc.setTitle(wanted);
        }
    }

    private SupportingDoc touch(SupportingDoc doc, String actor) {
        doc.setUpdatedBy(BugHistoryService.actor(actor));
        return repository.save(doc);
    }

    private static String cleanTitle(String title, DocType type) {
        if (title == null || title.isBlank()) {
            return type.getDefaultTitle();
        }
        String trimmed = title.trim();
        return trimmed.length() > 150 ? trimmed.substring(0, 150) : trimmed;
    }

    // ----------------------------------------------------------- sheet shapes

    /** The document's grid, squared off and never empty. */
    @Transactional(readOnly = true)
    public Sheet sheet(SupportingDoc doc) {
        return parse(doc.getContent());
    }

    /**
     * Reads the stored JSON back into a grid. Deliberately forgiving: content
     * that cannot be read at all becomes a blank sheet rather than a 500, so a
     * document is never a page you cannot open.
     */
    private Sheet parse(String content) {
        int cols = NEW_COLS;
        List<List<String>> rows = new ArrayList<>();
        try {
            JsonNode root = json.readTree(content == null ? "" : content);
            if (root != null && root.isObject()) {
                cols = clamp(root.path("cols").asInt(NEW_COLS), 1, MAX_COLS);
                for (JsonNode row : root.path("rows")) {
                    if (rows.size() >= MAX_ROWS) {
                        break;
                    }
                    List<String> cells = new ArrayList<>();
                    for (JsonNode cell : row) {
                        cells.add(cell.isNull() ? "" : cell.asText(""));
                    }
                    rows.add(cells);
                }
            }
        } catch (Exception e) {
            rows.clear();                       // unreadable: start them a blank one
        }
        if (rows.isEmpty()) {
            return blankSheet();
        }
        // The stored width wins, but a row that outgrew it drags the rest along.
        for (List<String> row : rows) {
            cols = Math.max(cols, Math.min(row.size(), MAX_COLS));
        }
        return new Sheet(cols, square(rows, cols));
    }

    /** Turns the flat, row-major list of posted cells back into rows. */
    private static List<List<String>> fold(List<String> cells, int cols) {
        List<List<String>> rows = new ArrayList<>();
        if (cells == null || cells.isEmpty()) {
            return blankSheet().rows();
        }
        List<String> current = new ArrayList<>();
        for (String cell : cells) {
            current.add(cell == null ? "" : cell);
            if (current.size() == cols) {
                rows.add(current);
                current = new ArrayList<>();
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
            }
        }
        if (!current.isEmpty() && rows.size() < MAX_ROWS) {
            rows.add(current);                  // a short last row: padded below
        }
        return square(rows, cols);
    }

    /** The row/column buttons, for the browser that is not running the editor. */
    private static List<List<String>> apply(String op, List<List<String>> rows, int cols) {
        if (op == null || op.isBlank() || rows.isEmpty()) {
            return rows;
        }
        String name = op;
        int at = -1;
        int colon = op.indexOf(':');
        if (colon > 0) {
            name = op.substring(0, colon);
            try {
                at = Integer.parseInt(op.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                return rows;
            }
        }

        switch (name) {
            case "add-row" -> {
                if (rows.size() < MAX_ROWS) {
                    rows.add(blankRow(cols));
                }
            }
            case "add-col" -> {
                if (cols < MAX_COLS) {
                    rows.forEach(row -> row.add(""));
                }
            }
            case "del-row" -> {
                if (rows.size() > 1 && at >= 0 && at < rows.size()) {
                    rows.remove(at);
                }
            }
            case "del-col" -> {
                if (cols > 1 && at >= 0 && at < cols) {
                    int index = at;
                    rows.forEach(row -> row.remove(index));
                }
            }
            default -> { /* an op nobody recognises changes nothing */ }
        }
        return rows;
    }

    /** Every row exactly {@code cols} wide — the templates assume a rectangle. */
    private static List<List<String>> square(List<List<String>> rows, int cols) {
        List<List<String>> squared = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> copy = new ArrayList<>(cols);
            for (int i = 0; i < cols; i++) {
                copy.add(i < row.size() ? row.get(i) : "");
            }
            squared.add(copy);
        }
        return squared;
    }

    private static List<String> blankRow(int cols) {
        List<String> row = new ArrayList<>(cols);
        for (int i = 0; i < cols; i++) {
            row.add("");
        }
        return row;
    }

    private static Sheet blankSheet() {
        List<List<String>> rows = new ArrayList<>(NEW_ROWS);
        for (int r = 0; r < NEW_ROWS; r++) {
            rows.add(blankRow(NEW_COLS));
        }
        return new Sheet(NEW_COLS, rows);
    }

    private String blankSheetJson() {
        return toJson(blankSheet());
    }

    private String toJson(Sheet sheet) {
        ObjectNode root = json.createObjectNode();
        root.put("cols", sheet.cols());
        ArrayNode rows = root.putArray("rows");
        for (List<String> row : sheet.rows()) {
            ArrayNode cells = rows.addArray();
            row.forEach(cells::add);
        }
        String out = root.toString();
        if (out.length() > MAX_CONTENT) {
            throw new IllegalArgumentException(
                    "This sheet is too large to save — try splitting it into two.");
        }
        return out;
    }

    /** A1-style column headings: A…Z, then AA, AB and so on. */
    public static List<String> columnLabels(int cols) {
        List<String> labels = new ArrayList<>(cols);
        for (int i = 0; i < cols; i++) {
            StringBuilder label = new StringBuilder();
            int n = i;
            while (n >= 0) {
                label.insert(0, (char) ('A' + n % 26));
                n = n / 26 - 1;
            }
            labels.add(label.toString());
        }
        return labels;
    }

    // ---------------------------------------------------------------- exports

    /** What the download contains: the Markdown as typed, or the grid as CSV. */
    public byte[] export(SupportingDoc doc) {
        if (doc.getType() == DocType.SHEET) {
            // The BOM is for Excel, which otherwise reads a UTF-8 CSV as Latin-1
            // and turns every accented name into mojibake.
            return ("\uFEFF" + toCsv(sheet(doc))).getBytes(StandardCharsets.UTF_8);
        }
        return doc.getContent().getBytes(StandardCharsets.UTF_8);
    }

    public static String toCsv(Sheet sheet) {
        StringBuilder out = new StringBuilder();
        for (List<String> row : sheet.rows()) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(csvCell(row.get(i)));
            }
            out.append("\r\n");                 // what every spreadsheet expects
        }
        return out.toString();
    }

    private static String csvCell(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0
                && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /** A download name made of the title, so a folder of exports still reads. */
    public static String fileName(SupportingDoc doc) {
        String slug = doc.getTitle()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            slug = doc.getType().name().toLowerCase(Locale.ROOT);
        }
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        return "BUG-" + doc.getBugId() + "-" + slug + "." + doc.getType().getExtension();
    }

    // -------------------------------------------------------------- summaries

    private static String pageSummary(String body) {
        String[] words = body.trim().split("\\s+");
        int count = body.isBlank() ? 0 : words.length;
        return count == 0 ? null : count + (count == 1 ? " word" : " words");
    }

    private static String sheetSummary(Sheet sheet) {
        boolean anything = sheet.rows().stream()
                .flatMap(List::stream)
                .anyMatch(cell -> cell != null && !cell.isBlank());
        if (!anything) {
            return null;
        }
        int rows = sheet.rows().size();
        return rows + (rows == 1 ? " row × " : " rows × ")
                + sheet.cols() + (sheet.cols() == 1 ? " column" : " columns");
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
