package com.bugtracking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and writes the JSON a project sheet is stored as.
 *
 * <p>The shape is a grid plus everything a spreadsheet remembers <em>about</em>
 * the grid:
 *
 * <pre>
 * {"cols":8,
 *  "rows":[["Step","Result"], …],
 *  "formats":{"0:1":{"b":true,"a":"center","bg":"#fff7ed","nf":"currency"}},
 *  "widths":{"2":220}, "heights":{"0":34},
 *  "merges":[{"r":0,"c":0,"rs":1,"cs":3}],
 *  "frozen":{"r":1,"c":0}}
 * </pre>
 *
 * <p>Formats are keyed {@code "row:col"} rather than held on the cell, so a
 * sheet where three cells are bold costs three entries instead of a wrapper
 * object around every empty string in a 300 × 40 grid.
 *
 * <p><b>Everything read back is validated, not trusted.</b> A colour has to
 * match {@code #rrggbb}, an alignment has to be one of three words, a number
 * format has to be one this app knows. These values end up in a {@code style}
 * attribute on the page that renders them, so a stored sheet is treated as
 * hostile input — it was typed by a person, and it is displayed to everybody
 * else on the project.
 */
@Component
public class SheetCodec {

    /** A new sheet opens with room to type into, not one empty cell. */
    public static final int NEW_ROWS = 20;
    public static final int NEW_COLS = 8;

    /** Ceilings, so a runaway paste cannot outgrow the column it is stored in. */
    public static final int MAX_ROWS = 500;
    public static final int MAX_COLS = 40;
    public static final int MAX_CONTENT = 200_000;

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Set<String> ALIGN = Set.of("left", "center", "right");
    private static final Set<String> VALIGN = Set.of("top", "middle", "bottom");
    private static final Set<String> FONTS = Set.of("sans", "mono", "serif");

    /**
     * The number formats the editor offers. Named rather than a pattern string:
     * the browser does the rendering, both sides have to agree on the list, and
     * an arbitrary pattern out of the stored JSON is one more thing to have to
     * make safe.
     */
    public static final Set<String> NUMBER_FORMATS = Set.of(
            "auto", "text", "number", "comma", "currency", "percent", "date", "time", "datetime");

    /** The boolean switches a cell can carry: bold, italic, underline, strike, wrap, border. */
    private static final Set<String> FLAGS = Set.of("b", "i", "u", "s", "w", "br");

    private final ObjectMapper json;

    public SheetCodec(ObjectMapper json) {
        this.json = json;
    }

    /**
     * A sheet, squared off: every row is exactly {@code cols} long, and every
     * format key points at a cell that exists.
     *
     * @param formats cell key ("r:c") to its validated format map
     * @param widths  column index to pixel width
     * @param heights row index to pixel height
     */
    public record Sheet(int cols,
                        List<List<String>> rows,
                        Map<String, Map<String, Object>> formats,
                        Map<Integer, Integer> widths,
                        Map<Integer, Integer> heights,
                        List<Merge> merges,
                        int frozenRows,
                        int frozenCols) {

        public int rowCount() {
            return rows.size();
        }

        /** True while nobody has typed anything into it. */
        public boolean isEmpty() {
            return rows.stream().flatMap(List::stream).noneMatch(cell -> cell != null && !cell.isBlank());
        }
    }

    /** A rectangle of cells drawn as one. The top-left cell holds the content. */
    public record Merge(int r, int c, int rowSpan, int colSpan) { }

    // ------------------------------------------------------------------ read

    /**
     * Reads stored JSON back into a grid. Deliberately forgiving: content that
     * cannot be read at all becomes a blank sheet rather than a 500, so a
     * document is never a page you cannot open. Also reads the plain
     * {@code {"cols":n,"rows":[…]}} a sheet with no formatting is written as.
     */
    public Sheet parse(String content) {
        int cols = NEW_COLS;
        List<List<String>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> formats = new LinkedHashMap<>();
        Map<Integer, Integer> widths = new LinkedHashMap<>();
        Map<Integer, Integer> heights = new LinkedHashMap<>();
        List<Merge> merges = new ArrayList<>();
        int frozenRows = 0;
        int frozenCols = 0;

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

                readFormats(root.path("formats"), formats);
                readSizes(root.path("widths"), widths, MAX_COLS, 40, 640);
                readSizes(root.path("heights"), heights, MAX_ROWS, 22, 400);
                readMerges(root.path("merges"), merges);

                JsonNode frozen = root.path("frozen");
                frozenRows = clamp(frozen.path("r").asInt(0), 0, 10);
                frozenCols = clamp(frozen.path("c").asInt(0), 0, 10);
            }
        } catch (Exception e) {
            rows.clear();                       // unreadable: start them a blank one
        }

        if (rows.isEmpty()) {
            return blank();
        }
        // The stored width wins, but a row that outgrew it drags the rest along.
        for (List<String> row : rows) {
            cols = Math.max(cols, Math.min(row.size(), MAX_COLS));
        }
        return new Sheet(cols, square(rows, cols), formats, widths, heights,
                merges, frozenRows, frozenCols);
    }

    private static void readFormats(JsonNode node, Map<String, Map<String, Object>> into) {
        if (!node.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!entry.getKey().matches("^\\d{1,4}:\\d{1,3}$") || into.size() >= 20_000) {
                continue;
            }
            Map<String, Object> format = format(entry.getValue());
            if (!format.isEmpty()) {
                into.put(entry.getKey(), format);
            }
        }
    }

    /** One cell's formatting, with every value checked against what it may be. */
    private static Map<String, Object> format(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!node.isObject()) {
            return out;
        }
        for (String flag : FLAGS) {
            if (node.path(flag).asBoolean(false)) {
                out.put(flag, true);            // only what is on is stored
            }
        }
        putIfIn(out, "a", node.path("a").asText(""), ALIGN);
        putIfIn(out, "va", node.path("va").asText(""), VALIGN);
        putIfIn(out, "nf", node.path("nf").asText(""), NUMBER_FORMATS);
        putIfIn(out, "ff", node.path("ff").asText(""), FONTS);
        putIfHex(out, "fg", node.path("fg").asText(""));
        putIfHex(out, "bg", node.path("bg").asText(""));

        int size = node.path("fs").asInt(0);
        if (size >= 8 && size <= 48) {
            out.put("fs", size);
        }
        return out;
    }

    private static void putIfIn(Map<String, Object> out, String key, String value, Set<String> allowed) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (allowed.contains(clean) && !"auto".equals(clean)) {
            out.put(key, clean);
        }
    }

    private static void putIfHex(Map<String, Object> out, String key, String value) {
        if (value != null && HEX.matcher(value.trim()).matches()) {
            out.put(key, value.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static void readSizes(JsonNode node, Map<Integer, Integer> into,
                                  int maxIndex, int min, int max) {
        if (!node.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            try {
                int index = Integer.parseInt(entry.getKey());
                int value = entry.getValue().asInt(0);
                if (index >= 0 && index < maxIndex && value >= min && value <= max) {
                    into.put(index, value);
                }
            } catch (NumberFormatException e) {
                /* a key that is not an index is not a size */
            }
        }
    }

    private static void readMerges(JsonNode node, List<Merge> into) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode merge : node) {
            int r = merge.path("r").asInt(-1);
            int c = merge.path("c").asInt(-1);
            int rs = merge.path("rs").asInt(1);
            int cs = merge.path("cs").asInt(1);
            boolean sane = r >= 0 && c >= 0 && r < MAX_ROWS && c < MAX_COLS
                    && rs >= 1 && cs >= 1 && rs <= MAX_ROWS && cs <= MAX_COLS
                    && (rs > 1 || cs > 1);
            if (sane && into.size() < 500) {
                into.add(new Merge(r, c, rs, cs));
            }
        }
    }

    // ----------------------------------------------------------------- write

    /** The stored form. Formatting is left out entirely when there is none. */
    public String toJson(Sheet sheet) {
        ObjectNode root = json.createObjectNode();
        root.put("cols", sheet.cols());

        ArrayNode rows = root.putArray("rows");
        for (List<String> row : sheet.rows()) {
            ArrayNode cells = rows.addArray();
            row.forEach(cells::add);
        }

        if (!sheet.formats().isEmpty()) {
            ObjectNode formats = root.putObject("formats");
            sheet.formats().forEach((key, format) -> {
                ObjectNode cell = formats.putObject(key);
                format.forEach((name, value) -> {
                    if (value instanceof Boolean flag) {
                        cell.put(name, flag);
                    } else if (value instanceof Integer number) {
                        cell.put(name, number);
                    } else {
                        cell.put(name, String.valueOf(value));
                    }
                });
            });
        }
        putSizes(root, "widths", sheet.widths());
        putSizes(root, "heights", sheet.heights());

        if (!sheet.merges().isEmpty()) {
            ArrayNode merges = root.putArray("merges");
            for (Merge merge : sheet.merges()) {
                ObjectNode node = merges.addObject();
                node.put("r", merge.r());
                node.put("c", merge.c());
                node.put("rs", merge.rowSpan());
                node.put("cs", merge.colSpan());
            }
        }
        if (sheet.frozenRows() > 0 || sheet.frozenCols() > 0) {
            ObjectNode frozen = root.putObject("frozen");
            frozen.put("r", sheet.frozenRows());
            frozen.put("c", sheet.frozenCols());
        }

        String out = root.toString();
        if (out.length() > MAX_CONTENT) {
            throw new IllegalArgumentException(
                    "This sheet is too large to save — try splitting it into two.");
        }
        return out;
    }

    private static void putSizes(ObjectNode root, String field, Map<Integer, Integer> sizes) {
        if (sizes.isEmpty()) {
            return;
        }
        ObjectNode node = root.putObject(field);
        sizes.forEach((index, value) -> node.put(String.valueOf(index), value));
    }

    // ------------------------------------------------------------- reshaping

    public Sheet blank() {
        List<List<String>> rows = new ArrayList<>(NEW_ROWS);
        for (int r = 0; r < NEW_ROWS; r++) {
            rows.add(blankRow(NEW_COLS));
        }
        return new Sheet(NEW_COLS, rows, new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), new ArrayList<>(), 0, 0);
    }

    public String blankJson() {
        return toJson(blank());
    }

    /**
     * Rebuilds a sheet from the flat, row-major list of cells an ordinary form
     * post sends, keeping everything the form could not carry.
     *
     * <p>This is the no-JavaScript save path. A browser posting a grid of text
     * inputs sends values and nothing else, so the formatting, widths, merges
     * and frozen panes are taken from what was already stored. Without that,
     * saving a typo with scripting off would strip every colour on the sheet.
     */
    public Sheet fromPostedCells(Sheet stored, List<String> cells, Integer cols) {
        int width = clamp(cols == null ? stored.cols() : cols, 1, MAX_COLS);
        List<List<String>> rows = fold(cells, width);
        return new Sheet(width, rows, stored.formats(), stored.widths(), stored.heights(),
                stored.merges(), stored.frozenRows(), stored.frozenCols());
    }

    /** Turns the flat, row-major list of posted cells back into rows. */
    private List<List<String>> fold(List<String> cells, int cols) {
        List<List<String>> rows = new ArrayList<>();
        if (cells == null || cells.isEmpty()) {
            return blank().rows();
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

    /**
     * The row and column buttons, for the browser that is not running the
     * editor. Formats move with the cells they belong to — inserting a row
     * above a red total and leaving the red behind is a broken sheet.
     */
    public Sheet applyOp(Sheet sheet, String op) {
        if (op == null || op.isBlank()) {
            return sheet;
        }
        String name = op;
        int at = -1;
        int colon = op.indexOf(':');
        if (colon > 0) {
            name = op.substring(0, colon);
            try {
                at = Integer.parseInt(op.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                return sheet;
            }
        }

        List<List<String>> rows = new ArrayList<>(sheet.rows());
        int cols = sheet.cols();

        switch (name) {
            case "add-row" -> {
                if (rows.size() >= MAX_ROWS) {
                    return sheet;
                }
                rows.add(blankRow(cols));
                return withRows(sheet, cols, rows);
            }
            case "add-col" -> {
                if (cols >= MAX_COLS) {
                    return sheet;
                }
                rows = rows.stream().map(row -> {
                    List<String> copy = new ArrayList<>(row);
                    copy.add("");
                    return copy;
                }).toList();
                return withRows(sheet, cols + 1, new ArrayList<>(rows));
            }
            case "del-row" -> {
                if (rows.size() <= 1 || at < 0 || at >= rows.size()) {
                    return sheet;
                }
                rows.remove(at);
                return shifted(sheet, cols, rows, at, true);
            }
            case "del-col" -> {
                if (cols <= 1 || at < 0 || at >= cols) {
                    return sheet;
                }
                int index = at;
                rows = rows.stream().map(row -> {
                    List<String> copy = new ArrayList<>(row);
                    copy.remove(index);
                    return copy;
                }).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                return shifted(sheet, cols - 1, rows, at, false);
            }
            default -> {
                return sheet;                   // an op nobody recognises changes nothing
            }
        }
    }

    private static Sheet withRows(Sheet from, int cols, List<List<String>> rows) {
        return new Sheet(cols, square(rows, cols), from.formats(), from.widths(), from.heights(),
                from.merges(), from.frozenRows(), from.frozenCols());
    }

    /**
     * A delete moves everything after it back one. Formats keyed by position
     * have to move with it; merges that straddle the gap are dropped rather
     * than left pointing at cells that are no longer theirs.
     */
    private static Sheet shifted(Sheet from, int cols, List<List<String>> rows, int at, boolean byRow) {
        Map<String, Map<String, Object>> formats = new LinkedHashMap<>();
        from.formats().forEach((key, format) -> {
            String[] parts = key.split(":");
            int r = Integer.parseInt(parts[0]);
            int c = Integer.parseInt(parts[1]);
            int moved = byRow ? r : c;
            if (moved == at) {
                return;                          // the deleted line's own formatting
            }
            if (moved > at) {
                if (byRow) {
                    r--;
                } else {
                    c--;
                }
            }
            formats.put(r + ":" + c, format);
        });

        Map<Integer, Integer> sizes = new LinkedHashMap<>();
        Map<Integer, Integer> source = byRow ? from.heights() : from.widths();
        source.forEach((index, value) -> {
            if (index == at) {
                return;
            }
            sizes.put(index > at ? index - 1 : index, value);
        });

        List<Merge> merges = from.merges().stream()
                .filter(m -> byRow ? (at < m.r() || at >= m.r() + m.rowSpan())
                                   : (at < m.c() || at >= m.c() + m.colSpan()))
                .map(m -> byRow
                        ? new Merge(m.r() > at ? m.r() - 1 : m.r(), m.c(), m.rowSpan(), m.colSpan())
                        : new Merge(m.r(), m.c() > at ? m.c() - 1 : m.c(), m.rowSpan(), m.colSpan()))
                .toList();

        return new Sheet(cols, square(rows, cols), formats,
                byRow ? from.widths() : sizes,
                byRow ? sizes : from.heights(),
                new ArrayList<>(merges), from.frozenRows(), from.frozenCols());
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

    // --------------------------------------------------------------- exports

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

    /**
     * CSV of what was typed. Formulas go out as their text, the way the
     * existing document export does — a spreadsheet reading the file will work
     * them out itself, and a formula is what somebody wrote down.
     */
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

    /** "12 rows × 6 columns", or null while the grid is untouched. */
    public static String summary(Sheet sheet) {
        if (sheet.isEmpty()) {
            return null;
        }
        int rows = sheet.rowCount();
        return rows + (rows == 1 ? " row × " : " rows × ")
                + sheet.cols() + (sheet.cols() == 1 ? " column" : " columns");
    }

    /** Everything typed into the grid, for scanning a sheet for @mentions. */
    public static String allText(Sheet sheet) {
        StringBuilder out = new StringBuilder();
        for (List<String> row : sheet.rows()) {
            for (String cell : row) {
                if (cell != null && !cell.isBlank()) {
                    out.append(cell).append('\n');
                }
            }
        }
        return out.toString();
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
