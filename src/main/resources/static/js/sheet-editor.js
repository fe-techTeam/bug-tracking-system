/* Bug Tracking — the project sheet editor.

   The grid in the page is the model. Every cell is a real <input>, which is
   what makes the no-JavaScript form post work, so this reads and writes the
   table rather than keeping a second copy of the values beside it. The one
   thing a grid of inputs cannot carry is what a cell *looks* like, so
   formatting — weight, colour, alignment, number format, merges, column
   widths, frozen panes — is held here and written into the hidden "content"
   field just before every save.

   Loaded alongside doc-editor.js, which owns saving. Two seams join them:
   BT.docSaver.touched() says "something changed" for edits that fire no input
   event, and BT.beforeDocSave is called immediately before the form is
   serialised, which is where the JSON gets written.

   Nothing here is load-bearing. With scripting off the same page is a grid of
   text inputs that still posts, still saves, and still grows a row. */
(function () {
    "use strict";

    var table = document.getElementById("gsheet");
    if (!table) return;

    var MAX_ROWS = 500;
    var MAX_COLS = 40;
    var UNDO_DEPTH = 80;
    var MIN_WIDTH = 40;
    var MAX_WIDTH = 640;

    var head = table.tHead.rows[0];
    var grid = table.tBodies[0];
    var jsonField = document.getElementById("sheet-json");
    var colsField = document.getElementById("sheet-cols");
    var refBox = document.getElementById("cell-ref");
    var calm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    /* The × buttons on every row and column are there for the browser with no
       JavaScript. Here the toolbar does that job, and leaving them in would
       only leave their indexes to go stale. */
    table.querySelectorAll(".js-off").forEach(function (el) { el.remove(); });

    /* ======================================================================
       The model: everything about the grid that is not a cell's text.
       ====================================================================== */

    var formats = {};          /* "r:c" -> { b,i,u,s,w,br, a, va, fg, bg, fs, nf } */
    var widths = {};           /* column index -> px */
    var heights = {};          /* row index -> px */
    var merges = [];           /* { r, c, rs, cs } */
    var frozen = { r: 0, c: 0 };

    (function load() {
        var raw = table.getAttribute("data-sheet");
        if (!raw) return;
        var stored;
        try {
            stored = JSON.parse(raw);
        } catch (e) {
            return;                        // unreadable formatting: the values still open
        }
        if (!stored || typeof stored !== "object") return;

        if (stored.formats && typeof stored.formats === "object") formats = stored.formats;
        if (stored.widths && typeof stored.widths === "object") widths = stored.widths;
        if (stored.heights && typeof stored.heights === "object") heights = stored.heights;
        if (Object.prototype.toString.call(stored.merges) === "[object Array]") merges = stored.merges;
        if (stored.frozen) {
            frozen.r = Math.max(0, Math.min(10, stored.frozen.r || 0));
            frozen.c = Math.max(0, Math.min(10, stored.frozen.c || 0));
        }
    })();

    function key(r, c) { return r + ":" + c; }

    function formatAt(r, c) { return formats[key(r, c)] || null; }

    /** The format object for a cell, made if it is not there yet. */
    function formatFor(r, c) {
        var k = key(r, c);
        if (!formats[k]) formats[k] = {};
        return formats[k];
    }

    function dropIfEmpty(r, c) {
        var k = key(r, c);
        var format = formats[k];
        if (format && !Object.keys(format).length) delete formats[k];
    }

    /* ======================================================================
       Reading the table.
       ====================================================================== */

    function rows() { return Array.prototype.slice.call(grid.rows); }
    function cellsIn(row) { return Array.prototype.slice.call(row.querySelectorAll("input.cell")); }
    function colCount() { return head.cells.length - 1; }
    function rowCount() { return grid.rows.length; }

    function cellAt(r, c) {
        var row = grid.rows[r];
        if (!row) return null;
        return cellsIn(row)[c] || null;
    }
    function boxAt(r, c) {
        var input = cellAt(r, c);
        return input ? input.parentNode : null;
    }
    function where(input) {
        var row = input.closest("tr");
        return { r: row.rowIndex - 1, c: cellsIn(row).indexOf(input) };
    }

    var engine = window.BT && typeof window.BT.formulaEngine === "function"
        ? window.BT.formulaEngine(function (r, c) {
            var input = cellAt(r, c);
            return input ? input.value : null;
        })
        : null;

    function label(index) {
        if (engine) return engine.label(index);
        var out = "";
        var n = index;
        while (n >= 0) {
            out = String.fromCharCode(65 + (n % 26)) + out;
            n = Math.floor(n / 26) - 1;
        }
        return out;
    }

    function markDirty() {
        if (window.BT && window.BT.docSaver) window.BT.docSaver.touched();
    }

    /* ======================================================================
       Serialising, and the hook that keeps the hidden field honest.
       ====================================================================== */

    function serialise() {
        var cols = colCount();
        var out = { cols: cols, rows: [] };

        rows().forEach(function (row) {
            out.rows.push(cellsIn(row).map(function (input) { return input.value; }));
        });

        if (Object.keys(formats).length) out.formats = formats;
        if (Object.keys(widths).length) out.widths = widths;
        if (Object.keys(heights).length) out.heights = heights;
        if (merges.length) out.merges = merges;
        if (frozen.r || frozen.c) out.frozen = { r: frozen.r, c: frozen.c };

        return JSON.stringify(out);
    }

    function syncField() {
        if (jsonField) jsonField.value = serialise();
        if (colsField) colsField.value = String(colCount());
    }

    window.BT = window.BT || {};
    window.BT.beforeDocSave = syncField;
    syncField();

    /* ======================================================================
       Undo and redo.

       Typing is stored as one small before/after per cell, because a snapshot
       of a 500-row grid on every keystroke is not something to do. Everything
       else — formatting, merging, adding a column — is stored as a pair of
       whole-sheet snapshots, which are rare enough to afford and are the only
       honest way to undo a structural change.
       ====================================================================== */

    var undoStack = [];
    var redoStack = [];

    function pushEdit(entry) {
        undoStack.push(entry);
        if (undoStack.length > UNDO_DEPTH) undoStack.shift();
        redoStack = [];
        reflectHistory();
    }

    /** Runs an action that changes the shape or look of the sheet, undoably. */
    function change(action) {
        var before = serialise();
        action();
        pushEdit({ k: "full", before: before, after: serialise() });
        syncField();
        markDirty();
    }

    function apply(entry, direction) {
        if (entry.k === "cell") {
            var input = cellAt(entry.r, entry.c);
            if (input) {
                input.value = direction === "undo" ? entry.before : entry.after;
                focusCell(entry.r, entry.c);
            }
        } else {
            restore(direction === "undo" ? entry.before : entry.after);
        }
        syncField();
        markDirty();
        paint();
    }

    function undo() {
        var entry = undoStack.pop();
        if (!entry) return;
        redoStack.push(entry);
        apply(entry, "undo");
        reflectHistory();
    }

    function redo() {
        var entry = redoStack.pop();
        if (!entry) return;
        undoStack.push(entry);
        apply(entry, "redo");
        reflectHistory();
    }

    function reflectHistory() {
        var undoButton = document.getElementById("sh-undo");
        var redoButton = document.getElementById("sh-redo");
        if (undoButton) undoButton.disabled = !undoStack.length;
        if (redoButton) redoButton.disabled = !redoStack.length;
    }

    /** Rebuilds the whole grid from a snapshot. Only undo and redo need this. */
    function restore(json) {
        var state;
        try {
            state = JSON.parse(json);
        } catch (e) {
            return;
        }
        formats = state.formats || {};
        widths = state.widths || {};
        heights = state.heights || {};
        merges = state.merges || [];
        frozen = state.frozen ? { r: state.frozen.r || 0, c: state.frozen.c || 0 } : { r: 0, c: 0 };

        if (!state.rows || !state.rows.length) return;

        var cols = state.cols || 1;
        while (colCount() > cols) removeColumn(colCount() - 1);
        while (colCount() < cols) appendColumn();
        while (rowCount() > state.rows.length) grid.deleteRow(rowCount() - 1);
        while (rowCount() < state.rows.length) appendRow();

        state.rows.forEach(function (values, r) {
            values.forEach(function (value, c) {
                var input = cellAt(r, c);
                if (input) input.value = value;
            });
        });
        renumber();
    }

    /* ======================================================================
       The selection. One rectangle, which is what every toolbar button acts on.
       ====================================================================== */

    var sel = { r1: 0, c1: 0, r2: 0, c2: 0 };
    var dragging = false;

    function bounds() {
        return {
            top: Math.min(sel.r1, sel.r2), bottom: Math.max(sel.r1, sel.r2),
            left: Math.min(sel.c1, sel.c2), right: Math.max(sel.c1, sel.c2)
        };
    }

    /** Runs a function over every cell in the selection. */
    function overSelection(fn) {
        var box = bounds();
        for (var r = box.top; r <= box.bottom; r++) {
            for (var c = box.left; c <= box.right; c++) {
                fn(r, c);
            }
        }
    }

    function select(r1, c1, r2, c2) {
        sel.r1 = clamp(r1, 0, rowCount() - 1);
        sel.c1 = clamp(c1, 0, colCount() - 1);
        sel.r2 = clamp(r2 === undefined ? r1 : r2, 0, rowCount() - 1);
        sel.c2 = clamp(c2 === undefined ? c1 : c2, 0, colCount() - 1);
        paintSelection();
        reflectToolbar();
    }

    function paintSelection() {
        table.querySelectorAll("td.is-sel, td.is-anchor")
            .forEach(function (td) { td.classList.remove("is-sel", "is-anchor"); });
        table.querySelectorAll("th.is-head-sel")
            .forEach(function (th) { th.classList.remove("is-head-sel"); });

        var box = bounds();
        for (var r = box.top; r <= box.bottom; r++) {
            for (var c = box.left; c <= box.right; c++) {
                var td = boxAt(r, c);
                if (td) td.classList.add("is-sel");
            }
            var rowHead = grid.rows[r] && grid.rows[r].cells[0];
            if (rowHead) rowHead.classList.add("is-head-sel");
        }
        for (var h = box.left; h <= box.right; h++) {
            if (head.cells[h + 1]) head.cells[h + 1].classList.add("is-head-sel");
        }
        var anchor = boxAt(sel.r1, sel.c1);
        if (anchor) anchor.classList.add("is-anchor");

        if (refBox) {
            var single = box.top === box.bottom && box.left === box.right;
            refBox.textContent = single
                ? label(box.left) + (box.top + 1)
                : label(box.left) + (box.top + 1) + ":" + label(box.right) + (box.bottom + 1);
        }
    }

    function focusCell(r, c) {
        var input = cellAt(r, c);
        if (!input) return;
        input.focus();
        input.select();
        select(r, c);
    }

    /* ======================================================================
       Painting: what a cell looks like, and what it says while you are not
       editing it.

       A formula cell and a formatted number both keep what was typed as the
       input's value — that is what gets saved, and what you get back when you
       click into it — and show their result through an overlay that the input's
       own focus hides.
       ====================================================================== */

    var paintTimer = null;
    var paintJob = null;

    /**
     * Two passes, because they cost wildly different amounts.
     *
     * The full one writes a dozen style properties per cell and has to run when
     * formatting changes — but at the 500 × 40 ceiling that is 20,000 cells and
     * a quarter of a million style writes, which is not something to do between
     * two keystrokes. Typing only ever changes what cells *say*, so it takes
     * the value pass, which touches nothing but the cells that show something
     * other than their own text.
     */
    function paint() { schedule(paintAll); }
    function paintValues() { schedule(refreshValues); }

    function schedule(job) {
        // A full repaint already covers a value one, so it wins the slot.
        if (paintJob !== paintAll) paintJob = job;
        if (paintTimer) clearTimeout(paintTimer);
        paintTimer = setTimeout(function () {
            var run = paintJob;
            paintJob = null;
            paintTimer = null;
            run();
        }, calm ? 0 : 60);
    }

    function paintAll() {
        rows().forEach(function (row, r) {
            cellsIn(row).forEach(function (input, c) {
                paintCell(r, c, input);
            });
        });
        paintMerges();
        paintWidths();
        paintFrozen();
    }

    /**
     * Only what a cell shows. A formula anywhere can depend on the cell that
     * just changed, so every cell is asked — but the answer is null for the
     * overwhelming majority, which then cost one function call and no DOM work
     * at all.
     */
    function refreshValues() {
        rows().forEach(function (row, r) {
            cellsIn(row).forEach(function (input, c) {
                paintValue(input.parentNode, input, formatAt(r, c) || {});
            });
        });
    }

    /* The typography goes on both the cell's box and the input inside it. The
       box is what the overlay inherits from; the input draws its own text and
       inherits nothing, and text-decoration in particular never crosses into
       a form control. */
    var TYPE = ["fontWeight", "fontStyle", "textDecoration", "textAlign", "color", "fontSize"];

    function paintCell(r, c, input) {
        var td = input.parentNode;
        var format = formatAt(r, c) || {};

        var type = {
            fontWeight: format.b ? "650" : "",
            fontStyle: format.i ? "italic" : "",
            textDecoration: decorationOf(format),
            textAlign: format.a || "",
            color: format.fg || "",
            fontSize: format.fs ? format.fs + "px" : ""
        };
        TYPE.forEach(function (name) {
            td.style[name] = type[name];
            input.style[name] = type[name];
        });
        td.style.verticalAlign = format.va || "";
        td.style.background = format.bg || "";
        td.classList.toggle("is-wrapped", !!format.w);
        td.classList.toggle("is-bordered", !!format.br);
        // A dark fill needs light text, or the cell is unreadable in both themes.
        td.classList.toggle("on-dark", !!format.bg && isDark(format.bg) && !format.fg);

        paintValue(td, input, format);
    }

    /** The overlay: what the cell shows while you are not typing into it. */
    function paintValue(td, input, format) {
        var shown = display(input.value, format);
        var overlay = td.querySelector(".cell-view");
        if (shown === null) {
            if (overlay) overlay.remove();
            return;
        }
        if (!overlay) {
            overlay = document.createElement("span");
            overlay.className = "cell-view";
            overlay.setAttribute("aria-hidden", "true");
            td.appendChild(overlay);
        }
        if (overlay.textContent !== shown) overlay.textContent = shown;
        overlay.classList.toggle("is-error", shown.charAt(0) === "#");
    }

    function decorationOf(format) {
        var marks = [];
        if (format.u) marks.push("underline");
        if (format.s) marks.push("line-through");
        return marks.join(" ");
    }

    /**
     * What the cell shows when it is not being typed into, or null when that is
     * simply the text itself — the overwhelming majority, and the case worth
     * not building a DOM node for.
     */
    function display(raw, format) {
        var text = raw === null || raw === undefined ? "" : String(raw);
        if (text.trim().charAt(0) === "=") {
            var answer = engine ? engine.evaluate(text) : text;
            return format.nf && answer && answer.charAt(0) !== "#"
                ? formatNumber(Number(answer), format.nf)
                : answer;
        }
        var plain = !format.nf || format.nf === "auto" || format.nf === "text";
        if (plain || !text.trim()) {
            // An <input> cannot wrap, so a wrapped cell is shown through the
            // overlay — which is in the flow, and gives the row its height.
            return format.w && text ? text : null;
        }
        var number = Number(text.replace(/,/g, ""));
        if (isNaN(number)) return format.w && text ? text : null;   // text stays text
        return formatNumber(number, format.nf);
    }

    /* The formats the toolbar offers, and nothing else — the server validates
       against the same list, so an unrecognised one never reaches here. */
    function formatNumber(value, how) {
        if (typeof value !== "number" || isNaN(value)) return String(value);
        switch (how) {
            case "number":   return fixed(value, 2);
            case "comma":    return grouped(fixed(value, 2));
            case "currency": return currency(value);
            case "percent":  return fixed(value * 100, 2) + "%";
            case "date":     return stamp(value, { day: "2-digit", month: "short", year: "numeric" });
            case "time":     return stamp(value, { hour: "2-digit", minute: "2-digit", hour12: false });
            case "datetime": return stamp(value, {
                day: "2-digit", month: "short", year: "numeric",
                hour: "2-digit", minute: "2-digit", hour12: false
            });
            default:         return String(value);
        }
    }

    function currency(value) {
        var sign = value < 0 ? "-" : "";
        return sign + "₹" + grouped(fixed(Math.abs(value), 2));
    }

    function fixed(value, places) {
        return (Math.round(value * Math.pow(10, places)) / Math.pow(10, places)).toFixed(places);
    }

    /** 1234567.89 -> "12,34,567.89". Indian grouping: this is an Indian team. */
    function grouped(text) {
        var negative = text.charAt(0) === "-";
        var body = negative ? text.slice(1) : text;
        var dot = body.indexOf(".");
        var whole = dot < 0 ? body : body.slice(0, dot);
        var rest = dot < 0 ? "" : body.slice(dot);

        if (whole.length > 3) {
            var last3 = whole.slice(-3);
            var lead = whole.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ",");
            whole = lead + "," + last3;
        }
        return (negative ? "-" : "") + whole + rest;
    }

    /**
     * A number as a date. Spreadsheets count days from 1899-12-30, and a sheet
     * exported from Excel and pasted in here arrives holding those numbers, so
     * that is what a date format has to read.
     */
    function stamp(value, options) {
        var millis = (value - 25569) * 86400000;
        var date = new Date(Math.round(millis));
        if (isNaN(date.getTime())) return String(value);
        try {
            return date.toLocaleString(undefined, options);
        } catch (e) {
            return date.toISOString().slice(0, 10);
        }
    }

    /** Whether a fill is dark enough that the text on it has to turn white. */
    function isDark(hex) {
        var m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
        if (!m) return false;
        var r = parseInt(m[1], 16), g = parseInt(m[2], 16), b = parseInt(m[3], 16);
        return (0.299 * r + 0.587 * g + 0.114 * b) < 140;
    }

    /* ---------- merges, widths and frozen panes ---------- */

    function paintMerges() {
        table.querySelectorAll("td.is-merged, td.is-covered").forEach(function (td) {
            td.classList.remove("is-merged", "is-covered");
            td.removeAttribute("colspan");
            td.removeAttribute("rowspan");
            td.hidden = false;
        });

        merges.forEach(function (m) {
            var anchor = boxAt(m.r, m.c);
            if (!anchor) return;
            anchor.classList.add("is-merged");
            if (m.cs > 1) anchor.colSpan = m.cs;
            if (m.rs > 1) anchor.rowSpan = m.rs;

            for (var r = m.r; r < m.r + m.rs; r++) {
                for (var c = m.c; c < m.c + m.cs; c++) {
                    if (r === m.r && c === m.c) continue;
                    var covered = boxAt(r, c);
                    if (covered) {
                        covered.classList.add("is-covered");
                        covered.hidden = true;      // still posts, still in the model
                    }
                }
            }
        });
    }

    var DEFAULT_WIDTH = 132;

    function paintWidths() {
        // Every column, not only the resized ones: the table is table-layout
        // fixed, so a column with no width stated shares whatever is left and
        // the drag you just finished would move its neighbours instead.
        for (var c = 0; c < colCount(); c++) {
            var th = head.cells[c + 1];
            if (!th) continue;
            th.style.width = (widths[c] || DEFAULT_WIDTH) + "px";
        }
        rows().forEach(function (row, r) {
            row.style.height = heights[r] ? heights[r] + "px" : "";
        });
    }

    function paintFrozen() {
        // Measured, not guessed: the heading row's height moves with the font
        // and the theme, and a frozen row parked at the wrong offset either
        // overlaps the letters or floats below them.
        var headHeight = head.getBoundingClientRect().height || 26;
        var stacked = headHeight;

        rows().forEach(function (row, r) {
            var isFrozen = r < frozen.r;
            row.classList.toggle("is-frozen-row", isFrozen);
            row.style.setProperty("--frozen-top", isFrozen ? Math.round(stacked) + "px" : "");
            if (isFrozen) stacked += row.getBoundingClientRect().height;
        });
        var freezeButton = document.getElementById("sh-freeze");
        if (freezeButton) {
            freezeButton.classList.toggle("is-active", frozen.r > 0);
            freezeButton.setAttribute("aria-pressed", frozen.r > 0 ? "true" : "false");
        }
    }

    /* ======================================================================
       The toolbar.
       ====================================================================== */

    /**
     * Toggles a switch across the selection. Google Sheets' rule, and the one
     * that matches what people expect: if any cell in the selection is not
     * bold, the whole selection becomes bold; only when every cell already is
     * does the button turn it off.
     */
    function toggleFlag(flag) {
        var allOn = true;
        overSelection(function (r, c) {
            var format = formatAt(r, c);
            if (!format || !format[flag]) allOn = false;
        });
        change(function () {
            overSelection(function (r, c) {
                if (allOn) {
                    var format = formatAt(r, c);
                    if (format) { delete format[flag]; dropIfEmpty(r, c); }
                } else {
                    formatFor(r, c)[flag] = true;
                }
            });
        });
        paint();
        reflectToolbar();
    }

    /** Sets a named property across the selection; an empty value clears it. */
    function setProperty(name, value) {
        change(function () {
            overSelection(function (r, c) {
                if (value === "" || value === null || value === undefined) {
                    var format = formatAt(r, c);
                    if (format) { delete format[name]; dropIfEmpty(r, c); }
                } else {
                    formatFor(r, c)[name] = value;
                }
            });
        });
        paint();
        reflectToolbar();
    }

    function clearFormatting() {
        change(function () {
            overSelection(function (r, c) { delete formats[key(r, c)]; });
            var box = bounds();
            merges = merges.filter(function (m) {
                return m.r + m.rs - 1 < box.top || m.r > box.bottom
                    || m.c + m.cs - 1 < box.left || m.c > box.right;
            });
        });
        paint();
        reflectToolbar();
    }

    function mergeSelection() {
        var box = bounds();
        var existing = mergeCovering(box.top, box.left);

        change(function () {
            if (existing) {
                merges = merges.filter(function (m) { return m !== existing; });
                return;
            }
            if (box.top === box.bottom && box.left === box.right) return;

            // Anything already merged inside the new rectangle is absorbed.
            merges = merges.filter(function (m) {
                return m.r + m.rs - 1 < box.top || m.r > box.bottom
                    || m.c + m.cs - 1 < box.left || m.c > box.right;
            });
            // The top-left cell keeps the content, the way every spreadsheet
            // does it — anything else is a silent way to lose what was typed.
            var kept = [];
            for (var r = box.top; r <= box.bottom; r++) {
                for (var c = box.left; c <= box.right; c++) {
                    var input = cellAt(r, c);
                    if (!input || !input.value.trim()) continue;
                    if (r === box.top && c === box.left) continue;
                    kept.push(input.value.trim());
                    input.value = "";
                }
            }
            var anchor = cellAt(box.top, box.left);
            if (anchor && kept.length) {
                anchor.value = [anchor.value.trim()].concat(kept)
                    .filter(Boolean).join(" ");
            }
            merges.push({
                r: box.top, c: box.left,
                rs: box.bottom - box.top + 1,
                cs: box.right - box.left + 1
            });
        });
        paint();
        reflectToolbar();
    }

    function mergeCovering(r, c) {
        for (var i = 0; i < merges.length; i++) {
            var m = merges[i];
            if (r >= m.r && r < m.r + m.rs && c >= m.c && c < m.c + m.cs) return m;
        }
        return null;
    }

    /** The toolbar shows the state of the anchor cell, the way a ribbon does. */
    function reflectToolbar() {
        var format = formatAt(sel.r1, sel.c1) || {};

        [["sh-bold", "b"], ["sh-italic", "i"], ["sh-underline", "u"],
         ["sh-strike", "s"], ["sh-wrap", "w"], ["sh-border", "br"]]
            .forEach(function (pair) {
                press(document.getElementById(pair[0]), !!format[pair[1]]);
            });

        ["left", "center", "right"].forEach(function (side) {
            press(document.getElementById("sh-align-" + side), format.a === side);
        });

        press(document.getElementById("sh-merge"), !!mergeCovering(sel.r1, sel.c1));

        var size = document.getElementById("sh-size");
        if (size) size.value = format.fs ? String(format.fs) : "13";

        var numberFormat = document.getElementById("sh-format");
        if (numberFormat) numberFormat.value = format.nf || "auto";

        swatchBar("fg-now", format.fg || "");
        swatchBar("bg-now", format.bg || "");
    }

    function press(button, on) {
        if (!button) return;
        button.classList.toggle("is-active", on);
        button.setAttribute("aria-pressed", on ? "true" : "false");
    }

    function swatchBar(id, colour) {
        var bar = document.getElementById(id);
        if (bar) bar.style.background = colour || "transparent";
    }

    /* ---------- wiring the toolbar up ---------- */

    document.addEventListener("click", function (e) {
        if (!e.target.closest) return;

        var flagButton = e.target.closest(".sheet-tools [data-flag]");
        if (flagButton) { e.preventDefault(); toggleFlag(flagButton.dataset.flag); return; }

        var alignButton = e.target.closest(".sheet-tools [data-align]");
        if (alignButton) {
            e.preventDefault();
            var current = (formatAt(sel.r1, sel.c1) || {}).a;
            setProperty("a", current === alignButton.dataset.align ? "" : alignButton.dataset.align);
            return;
        }

        var swatch = e.target.closest(".swatches .swatch");
        if (swatch) {
            e.preventDefault();
            var group = swatch.closest(".swatches");
            setProperty(group.dataset.target, swatch.dataset.colour || "");
            closePopovers();
            return;
        }

        var button = e.target.closest("#sh-undo, #sh-redo, #sh-merge, #sh-clear, #sh-freeze,"
            + " #add-row, #add-col, #del-row, #del-col");
        if (!button) return;
        e.preventDefault();          // add-row and add-col are submit buttons without JS

        switch (button.id) {
            case "sh-undo":   undo(); break;
            case "sh-redo":   redo(); break;
            case "sh-merge":  mergeSelection(); break;
            case "sh-clear":  clearFormatting(); break;
            case "sh-freeze": toggleFreeze(); break;
            case "add-row":   addRowAtEnd(); break;
            case "add-col":   addColAtEnd(); break;
            case "del-row":   deleteRows(); break;
            case "del-col":   deleteCols(); break;
        }
    });

    document.addEventListener("change", function (e) {
        if (e.target.id === "sh-size") {
            setProperty("fs", e.target.value ? parseInt(e.target.value, 10) : "");
        } else if (e.target.id === "sh-format") {
            setProperty("nf", e.target.value === "auto" ? "" : e.target.value);
        } else if (e.target.id === "fg-custom") {
            setProperty("fg", e.target.value);
        } else if (e.target.id === "bg-custom") {
            setProperty("bg", e.target.value);
        }
    });

    function closePopovers() {
        document.querySelectorAll(".swatch-wrap[open]").forEach(function (d) { d.open = false; });
    }

    function toggleFreeze() {
        change(function () { frozen.r = frozen.r > 0 ? 0 : 1; });
        paintFrozen();
    }

    /* ======================================================================
       Rows and columns.
       ====================================================================== */

    function newCell(r, c) {
        var td = document.createElement("td");
        var input = document.createElement("input");
        input.type = "text";
        input.name = "cell";
        input.className = "cell";
        input.autocomplete = "off";
        input.spellcheck = false;
        input.setAttribute("aria-label", label(c) + (r + 1));
        td.appendChild(input);
        return td;
    }

    function appendRow() {
        if (rowCount() >= MAX_ROWS) return null;
        var row = grid.insertRow();
        var rowno = document.createElement("th");
        rowno.className = "sheet-rowno";
        rowno.scope = "row";
        row.appendChild(rowno);
        for (var c = 0; c < colCount(); c++) {
            row.appendChild(newCell(rowCount() - 1, c));
        }
        return row;
    }

    function appendColumn() {
        var index = colCount();
        if (index >= MAX_COLS) return;
        var th = document.createElement("th");
        th.scope = "col";
        var name = document.createElement("span");
        name.className = "col-label";
        name.textContent = label(index);
        th.appendChild(name);
        addGrip(th);
        head.appendChild(th);
        rows().forEach(function (row, r) { row.appendChild(newCell(r, index)); });
    }

    function removeColumn(index) {
        head.deleteCell(index + 1);
        rows().forEach(function (row) { row.deleteCell(index + 1); });
    }

    function renumber() {
        rows().forEach(function (row, r) {
            row.cells[0].textContent = String(r + 1);
            cellsIn(row).forEach(function (input, c) {
                input.setAttribute("aria-label", label(c) + (r + 1));
            });
        });
        Array.prototype.slice.call(head.cells, 1).forEach(function (th, i) {
            // The letters are positions, not names: after a delete they shift.
            var name = th.querySelector(".col-label");
            if (name) name.textContent = label(i);
        });
        if (colsField) colsField.value = String(colCount());
        paint();
    }

    function addRowAtEnd() {
        if (rowCount() >= MAX_ROWS) return;
        change(function () { appendRow(); });
        renumber();
        focusCell(rowCount() - 1, 0);
    }

    function addColAtEnd() {
        if (colCount() >= MAX_COLS) return;
        change(function () { appendColumn(); });
        renumber();
    }

    function deleteRows() {
        var box = bounds();
        var going = box.bottom - box.top + 1;
        if (rowCount() - going < 1) return;
        change(function () {
            for (var n = 0; n < going; n++) grid.deleteRow(box.top);
            shiftKeys(box.top, going, true);
        });
        renumber();
        focusCell(Math.min(box.top, rowCount() - 1), box.left);
    }

    function deleteCols() {
        var box = bounds();
        var going = box.right - box.left + 1;
        if (colCount() - going < 1) return;
        change(function () {
            for (var n = 0; n < going; n++) removeColumn(box.left);
            shiftKeys(box.left, going, false);
        });
        renumber();
        focusCell(box.top, Math.min(box.left, colCount() - 1));
    }

    /**
     * Everything keyed by position moves back when rows or columns are removed.
     * A red total left sitting where its row used to be is a broken sheet, and
     * a merge straddling the gap is worse — it would draw over cells that are
     * no longer part of it.
     */
    function shiftKeys(at, count, byRow) {
        var moved = {};
        Object.keys(formats).forEach(function (k) {
            var parts = k.split(":");
            var r = parseInt(parts[0], 10);
            var c = parseInt(parts[1], 10);
            var index = byRow ? r : c;
            if (index >= at && index < at + count) return;          // deleted outright
            if (index >= at + count) {
                if (byRow) r -= count; else c -= count;
            }
            moved[r + ":" + c] = formats[k];
        });
        formats = moved;

        var sizes = {};
        var source = byRow ? heights : widths;
        Object.keys(source).forEach(function (k) {
            var index = parseInt(k, 10);
            if (index >= at && index < at + count) return;
            sizes[index >= at + count ? index - count : index] = source[k];
        });
        if (byRow) heights = sizes; else widths = sizes;

        merges = merges.filter(function (m) {
            var start = byRow ? m.r : m.c;
            var span = byRow ? m.rs : m.cs;
            return start + span - 1 < at || start >= at + count;
        }).map(function (m) {
            var start = byRow ? m.r : m.c;
            if (start < at + count) return m;
            return byRow
                ? { r: m.r - count, c: m.c, rs: m.rs, cs: m.cs }
                : { r: m.r, c: m.c - count, rs: m.rs, cs: m.cs };
        });
    }

    /* ======================================================================
       Mouse: selecting a block, clicking a heading, dragging a column wider.
       ====================================================================== */

    table.addEventListener("mousedown", function (e) {
        var resizer = e.target.closest(".col-grip");
        if (resizer) { startResize(e, resizer); return; }

        var th = e.target.closest("thead th");
        if (th && th.cellIndex > 0 && th.cellIndex <= colCount()) {
            e.preventDefault();
            select(0, th.cellIndex - 1, rowCount() - 1, th.cellIndex - 1);
            focusCellQuietly(0, th.cellIndex - 1);
            return;
        }

        var rowHead = e.target.closest("th.sheet-rowno");
        if (rowHead) {
            e.preventDefault();
            var r = rowHead.parentNode.rowIndex - 1;
            select(r, 0, r, colCount() - 1);
            focusCellQuietly(r, 0);
            return;
        }

        var input = e.target.closest("input.cell");
        if (!input) return;
        var at = where(input);
        if (e.shiftKey) {
            e.preventDefault();
            select(sel.r1, sel.c1, at.r, at.c);
            return;
        }
        dragging = true;
        select(at.r, at.c);
    });

    table.addEventListener("mouseover", function (e) {
        if (!dragging) return;
        var input = e.target.closest("input.cell");
        if (!input) return;
        var at = where(input);
        select(sel.r1, sel.c1, at.r, at.c);
    });

    document.addEventListener("mouseup", function () { dragging = false; });

    /** Selects a cell without stealing what you were typing into. */
    function focusCellQuietly(r, c) {
        var input = cellAt(r, c);
        if (input) input.focus();
    }

    /* ---------- dragging a column wider ---------- */

    var resize = null;

    function startResize(e, grip) {
        e.preventDefault();
        var th = grip.parentNode;
        resize = {
            index: th.cellIndex - 1,
            from: e.clientX,
            width: th.getBoundingClientRect().width,
            was: widths[th.cellIndex - 1] || DEFAULT_WIDTH
        };
        document.body.classList.add("is-resizing");
    }

    document.addEventListener("mousemove", function (e) {
        if (!resize) return;
        var next = clamp(Math.round(resize.width + (e.clientX - resize.from)), MIN_WIDTH, MAX_WIDTH);
        var th = head.cells[resize.index + 1];
        if (th) th.style.width = next + "px";
        resize.to = next;
    });

    document.addEventListener("mouseup", function () {
        if (!resize) return;
        var finished = resize;
        resize = null;
        document.body.classList.remove("is-resizing");
        if (finished.to === undefined || finished.to === finished.was) return;
        change(function () { widths[finished.index] = finished.to; });
    });

    /* Grips are added here rather than in the template: they only mean anything
       while this file is running, and a column you cannot drag should not
       advertise a handle. */
    function addGrip(th) {
        if (th.querySelector(".col-grip")) return;
        var grip = document.createElement("span");
        grip.className = "col-grip";
        grip.setAttribute("aria-hidden", "true");
        th.appendChild(grip);
    }
    Array.prototype.slice.call(head.cells, 1).forEach(addGrip);

    /* ======================================================================
       Keyboard: moving about, and the shortcuts a spreadsheet has.
       ====================================================================== */

    var editingFrom = null;          /* the value a cell held when you entered it */

    table.addEventListener("focusin", function (e) {
        if (!e.target.classList || !e.target.classList.contains("cell")) return;
        var at = where(e.target);
        editingFrom = { r: at.r, c: at.c, value: e.target.value };
        if (!dragging) select(at.r, at.c);
    });

    table.addEventListener("focusout", function (e) {
        if (!e.target.classList || !e.target.classList.contains("cell")) return;
        if (!editingFrom) return;
        // One undo entry per cell you actually changed, rather than one per
        // keystroke: a snapshot of the grid on every letter is not affordable,
        // and undoing a letter at a time is not what anybody wants anyway.
        if (e.target.value !== editingFrom.value) {
            pushEdit({
                k: "cell", r: editingFrom.r, c: editingFrom.c,
                before: editingFrom.value, after: e.target.value
            });
        }
        editingFrom = null;
        closeMentions();
    });

    table.addEventListener("keydown", function (e) {
        var input = e.target;
        if (!input.classList || !input.classList.contains("cell")) return;

        if (mentionKey(e)) return;                    // the roster menu has it

        var at = where(input);
        var meta = e.ctrlKey || e.metaKey;

        if (meta && !e.altKey) {
            var lower = e.key.toLowerCase();
            if (lower === "b") { e.preventDefault(); toggleFlag("b"); return; }
            if (lower === "i") { e.preventDefault(); toggleFlag("i"); return; }
            if (lower === "u") { e.preventDefault(); toggleFlag("u"); return; }
            if (lower === "z") { e.preventDefault(); if (e.shiftKey) redo(); else undo(); return; }
            if (lower === "y") { e.preventDefault(); redo(); return; }
            if (lower === "a") {
                e.preventDefault();
                select(0, 0, rowCount() - 1, colCount() - 1);
                return;
            }
        }

        if (e.key === "Enter") {
            e.preventDefault();                       // "the row below", never "submit"
            if (at.r + 1 >= rowCount()) { change(function () { appendRow(); }); renumber(); }
            focusCell(at.r + 1, at.c);
        } else if (e.key === "Tab") {
            e.preventDefault();
            var next = e.shiftKey ? at.c - 1 : at.c + 1;
            if (next < 0) focusCell(at.r - 1, colCount() - 1);
            else if (next >= colCount()) focusCell(at.r + 1, 0);
            else focusCell(at.r, next);
        } else if (e.key === "Escape") {
            if (editingFrom && input.value !== editingFrom.value) {
                input.value = editingFrom.value;      // back out of the edit
                paint();
            }
        } else if (e.key === "Delete" || e.key === "Backspace") {
            var box = bounds();
            if (box.top === box.bottom && box.left === box.right) return;   // one cell: normal typing
            e.preventDefault();
            change(function () {
                overSelection(function (r, c) {
                    var cell = cellAt(r, c);
                    if (cell) cell.value = "";
                });
            });
            paint();
        } else if (e.key === "ArrowDown" || e.key === "ArrowUp"
                || e.key === "ArrowLeft" || e.key === "ArrowRight") {
            var dr = e.key === "ArrowDown" ? 1 : e.key === "ArrowUp" ? -1 : 0;
            var dc = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;

            // Left and right only leave the cell once the caret is at its edge,
            // so arrowing through what you have typed still works.
            if (dc < 0 && input.selectionStart !== 0) return;
            if (dc > 0 && input.selectionEnd !== input.value.length) return;

            e.preventDefault();
            if (e.shiftKey) {
                select(sel.r1, sel.c1, sel.r2 + dr, sel.c2 + dc);
            } else {
                focusCell(at.r + dr, at.c + dc);
            }
        }
    });

    /* ======================================================================
       A block pasted out of Excel lands as a block.
       ====================================================================== */

    table.addEventListener("paste", function (e) {
        var input = e.target;
        if (!input.classList || !input.classList.contains("cell")) return;

        var text = (e.clipboardData || window.clipboardData).getData("text");
        if (!text || (text.indexOf("\t") < 0 && text.indexOf("\n") < 0)) return;   // one cell: leave it

        e.preventDefault();
        var at = where(input);
        var block = text.replace(/\r\n?/g, "\n").replace(/\n$/, "").split("\n")
            .map(function (line) { return line.split("\t"); });

        change(function () {
            var widest = Math.max.apply(null, block.map(function (row) { return row.length; }));
            while (colCount() < Math.min(at.c + widest, MAX_COLS)) appendColumn();
            while (rowCount() < Math.min(at.r + block.length, MAX_ROWS)) appendRow();

            block.forEach(function (row, r) {
                row.forEach(function (value, c) {
                    var target = cellAt(at.r + r, at.c + c);
                    if (target) target.value = value;
                });
            });
        });
        renumber();
        select(at.r, at.c,
            Math.min(at.r + block.length - 1, rowCount() - 1),
            Math.min(at.c + block[0].length - 1, colCount() - 1));
    });

    /* ======================================================================
       Tagging somebody from inside a cell.

       The same behaviour the comment box has: type "@", pick a name. The server
       matches the roster again on save, so a name typed out by hand notifies
       just the same — this only saves you spelling it.
       ====================================================================== */

    var form = document.getElementById("doc-form");
    var people = [];
    if (form && form.getAttribute("data-people")) {
        people = form.getAttribute("data-people").split("|").filter(Boolean);
    }

    var menu = document.createElement("div");
    menu.className = "mention-menu is-floating";
    menu.hidden = true;
    document.body.appendChild(menu);

    var matches = [];
    var activeMatch = -1;
    var mentionInput = null;

    function closeMentions() {
        menu.hidden = true;
        menu.textContent = "";
        matches = [];
        activeMatch = -1;
        mentionInput = null;
    }

    /** The "@" being typed right now: the last one with no space after it. */
    function pending(input) {
        var upto = input.value.slice(0, input.selectionStart);
        var at = upto.lastIndexOf("@");
        if (at < 0) return null;
        var typed = upto.slice(at + 1);
        if (typed.split(" ").length > 2) return null;    // beyond that they are just writing
        return { at: at, typed: typed };
    }

    function offerMentions(input) {
        if (!people.length) return;
        var state = pending(input);
        if (!state) { closeMentions(); return; }

        var needle = state.typed.toLowerCase();
        matches = people.filter(function (p) {
            return p.toLowerCase().indexOf(needle) === 0;
        }).slice(0, 6);
        if (!matches.length) { closeMentions(); return; }

        menu.textContent = "";
        matches.forEach(function (name, index) {
            var item = document.createElement("button");
            item.type = "button";
            item.className = "mention-opt" + (index === 0 ? " is-active" : "");
            item.textContent = name;
            item.addEventListener("mousedown", function (e) {
                e.preventDefault();                  // keep the caret in the cell
                pickMention(name);
            });
            menu.appendChild(item);
        });

        var box = input.getBoundingClientRect();
        menu.style.left = Math.round(box.left + window.scrollX) + "px";
        menu.style.top = Math.round(box.bottom + window.scrollY + 3) + "px";
        menu.hidden = false;
        activeMatch = 0;
        mentionInput = input;
    }

    function pickMention(name) {
        var input = mentionInput;
        if (!input) return;
        var state = pending(input);
        if (!state) { closeMentions(); return; }
        var before = input.value.slice(0, state.at);
        var after = input.value.slice(input.selectionStart);
        input.value = before + "@" + name + " " + after;
        var caret = (before + "@" + name + " ").length;
        input.setSelectionRange(caret, caret);
        closeMentions();
        input.focus();
        markDirty();
    }

    /** True when the roster menu handled the key, so the grid must not. */
    function mentionKey(e) {
        if (menu.hidden || !matches.length) return false;
        if (e.key === "ArrowDown") {
            e.preventDefault();
            activeMatch = (activeMatch + 1) % matches.length;
        } else if (e.key === "ArrowUp") {
            e.preventDefault();
            activeMatch = (activeMatch - 1 + matches.length) % matches.length;
        } else if (e.key === "Enter" || e.key === "Tab") {
            e.preventDefault();
            pickMention(matches[activeMatch]);
            return true;
        } else if (e.key === "Escape") {
            e.preventDefault();
            closeMentions();
            return true;
        } else {
            return false;
        }
        Array.prototype.forEach.call(menu.children, function (el, i) {
            el.classList.toggle("is-active", i === activeMatch);
        });
        return true;
    }

    /* ======================================================================
       Keeping everything in step as you type.
       ====================================================================== */

    table.addEventListener("input", function (e) {
        if (!e.target.classList || !e.target.classList.contains("cell")) return;
        offerMentions(e.target);
        // Not syncField(): serialising the whole grid on every keystroke is the
        // one thing here that would be felt on a large sheet, and
        // BT.beforeDocSave does it immediately before anything is sent anyway.
        paintValues();
    });

    function clamp(value, low, high) {
        return Math.max(low, Math.min(high, value));
    }

    /* ---------- off we go ---------- */
    renumber();
    paintAll();
    select(0, 0);
    reflectHistory();
})();
