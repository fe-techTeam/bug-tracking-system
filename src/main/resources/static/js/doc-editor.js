/* Bug Tracking — the supporting-document editor.

   Loaded only by bugs/doc.html. Everything here is an enhancement: with it
   switched off the page is still a textarea and a Save button, and the sheet
   is still a grid of ordinary text inputs that posts and saves. What this adds
   is the Markdown preview, the formatting buttons, spreadsheet behaviour in
   the grid — formulas, paste from Excel, keyboard movement — and not having to
   press Save. */
(function () {
    "use strict";

    var form = document.getElementById("doc-form");
    if (!form) return;

    var titleField = document.getElementById("doc-title");
    var stateBox = document.getElementById("doc-state");
    var saveUrl = form.getAttribute("data-autosave");

    function store(key, value) {
        try { localStorage.setItem(key, value); } catch (e) { /* private mode */ }
    }
    function read(key) {
        try { return localStorage.getItem(key); } catch (e) { return null; }
    }

    /* ======================================================================
       Saving — a beat after you stop typing, and again on the way out.
       ====================================================================== */

    var SAVE_AFTER = 1100;
    var timer = null;
    var dirty = false;
    var saving = false;

    /**
     * What gets posted. The hook is for an editor that keeps something the
     * form cannot hold — a project sheet's formatting, which lives in a hidden
     * field it has to fill in first. Called immediately before every save,
     * autosave and beacon, so there is one place that can go stale rather than
     * three.
     */
    function body() {
        if (window.BT && typeof window.BT.beforeDocSave === "function") {
            window.BT.beforeDocSave();
        }
        return new URLSearchParams(new FormData(form)).toString();
    }

    function setState(text, stamp) {
        if (!stateBox) return;
        stateBox.textContent = "";

        var label = document.createElement("span");
        label.textContent = text;
        stateBox.appendChild(label);

        if (stamp) {
            var when = document.createElement("span");
            when.className = "when";
            when.textContent = "just now";
            when.title = stamp;
            stateBox.appendChild(document.createTextNode(" "));
            stateBox.appendChild(when);
        }
        stateBox.classList.toggle("is-dirty", text === "Unsaved changes");
        stateBox.classList.toggle("is-failed", text.indexOf("Could not") === 0);
    }

    function touched() {
        dirty = true;
        setState("Unsaved changes");
        if (timer) clearTimeout(timer);
        timer = setTimeout(save, SAVE_AFTER);
    }

    function save() {
        if (!saveUrl || saving || !dirty) return;
        // An empty name is rejected on the server; fill it in here rather than
        // let every save from now on fail quietly.
        if (titleField && !titleField.value.trim()) {
            titleField.value = titleField.placeholder;
        }
        saving = true;
        setState("Saving…");

        fetch(saveUrl, {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" },
            body: body()
        }).then(function (response) {
            if (!response.ok) throw new Error(response.status);
            return response.json();
        }).then(function (saved) {
            saving = false;
            dirty = false;
            setState("Saved", new Date().toLocaleString());
            if (saved && saved.title) {
                document.title = saved.title + " · Bug Tracking";
            }
        }).catch(function () {
            saving = false;
            // Still dirty: the next keystroke tries again, and Save always works.
            setState("Could not save — press Save");
        });
    }

    form.addEventListener("input", touched);
    form.addEventListener("change", touched);

    // Pressing Save posts the form the ordinary way; nothing to autosave after.
    // The hook still has to run — the browser is about to serialise the form
    // itself, and a stale hidden field would be what it sends.
    form.addEventListener("submit", function () {
        if (window.BT && typeof window.BT.beforeDocSave === "function") {
            window.BT.beforeDocSave();
        }
        dirty = false;
        if (timer) clearTimeout(timer);
    });

    // Leaving with something unsaved: post it without a dialog. sendBeacon
    // survives the page going away, which a fetch does not.
    window.addEventListener("pagehide", function () {
        if (!dirty || !saveUrl || !navigator.sendBeacon) return;
        navigator.sendBeacon(saveUrl, new Blob([body()], {
            type: "application/x-www-form-urlencoded;charset=UTF-8"
        }));
        dirty = false;
    });

    /* ======================================================================
       A page: the Markdown toolbar, and the preview beside it.
       ====================================================================== */

    var textarea = document.getElementById("doc-content");
    var preview = document.getElementById("doc-preview");
    var panes = document.getElementById("doc-panes");

    if (textarea) {
        wirePage();
    }

    function wirePage() {
        var VIEW_KEY = "bugtracking.docview";
        var renderTimer = null;

        function render() {
            if (!preview || preview.hidden) return;
            if (renderTimer) clearTimeout(renderTimer);
            renderTimer = setTimeout(function () {
                preview.innerHTML = markdown(textarea.value);
                // app.js loads after this file but runs before the first
                // render, so asking for it here rather than holding a reference.
                if (window.BT && typeof window.BT.markMentions === "function") {
                    window.BT.markMentions(preview);
                }
            }, 90);
        }

        /* ---- inserting Markdown around what you have selected ---- */

        function replace(start, end, text, selectFrom, selectTo) {
            textarea.setRangeText(text, start, end, "end");
            if (selectFrom !== undefined) {
                textarea.setSelectionRange(selectFrom, selectTo);
            }
            textarea.focus();
            touched();
            render();
        }

        /** Wraps the selection — or unwraps it, so the button is a toggle. */
        function wrap(mark) {
            var start = textarea.selectionStart;
            var end = textarea.selectionEnd;
            var chosen = textarea.value.slice(start, end);
            var already = textarea.value.slice(start - mark.length, start) === mark
                && textarea.value.slice(end, end + mark.length) === mark;

            if (already) {
                replace(start - mark.length, end + mark.length, chosen,
                    start - mark.length, end - mark.length);
                return;
            }
            var text = chosen || (mark === "`" ? "code" : "text");
            replace(start, end, mark + text + mark,
                start + mark.length, start + mark.length + text.length);
        }

        /** Puts a prefix on every line the selection touches, or takes it off. */
        function prefixLines(prefix) {
            var value = textarea.value;
            var start = value.lastIndexOf("\n", textarea.selectionStart - 1) + 1;
            var end = value.indexOf("\n", textarea.selectionEnd);
            if (end < 0) end = value.length;

            var lines = value.slice(start, end).split("\n");
            var numbered = /^\d+\.\s$/.test(prefix);
            var on = lines.every(function (line) {
                return numbered ? /^\s*\d+[.)]\s/.test(line) : line.indexOf(prefix) === 0;
            });

            var changed = lines.map(function (line, i) {
                if (on) {
                    return numbered ? line.replace(/^\s*\d+[.)]\s/, "") : line.slice(prefix.length);
                }
                return (numbered ? (i + 1) + ". " : prefix) + line;
            }).join("\n");

            replace(start, end, changed, start, start + changed.length);
        }

        function insertTable() {
            var block = "\n| Step | Expected | Actual | Result |\n"
                + "|---|---|---|---|\n"
                + "|  |  |  | Pass |\n";
            var at = textarea.selectionEnd;
            replace(at, at, block, at + block.length, at + block.length);
        }

        /**
         * Types the "@" for you. The menu that follows belongs to app.js and
         * opens on an input event, which setRangeText does not fire — hence
         * dispatching one rather than reaching across for the menu itself.
         */
        function insertMention() {
            var at = textarea.selectionEnd;
            replace(at, at, "@", at + 1, at + 1);
            textarea.dispatchEvent(new Event("input", { bubbles: true }));
        }

        function insertLink() {
            var start = textarea.selectionStart;
            var end = textarea.selectionEnd;
            var text = textarea.value.slice(start, end) || "link";
            var out = "[" + text + "](https://)";
            // Land the caret on the URL — that is the bit you still have to type.
            replace(start, end, out, start + text.length + 3, start + out.length - 1);
        }

        document.addEventListener("click", function (e) {
            var button = e.target.closest ? e.target.closest(".mdbtn") : null;
            if (!button) return;
            e.preventDefault();
            if (button.dataset.wrap) wrap(button.dataset.wrap);
            else if (button.dataset.md) prefixLines(button.dataset.md);
            else if (button.dataset.table) insertTable();
            else if (button.dataset.link) insertLink();
            else if (button.dataset.mention) insertMention();
        });

        textarea.addEventListener("keydown", function (e) {
            if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
            var key = e.key.toLowerCase();
            if (key === "b") { e.preventDefault(); wrap("**"); }
            else if (key === "i") { e.preventDefault(); wrap("*"); }
            else if (key === "s") { e.preventDefault(); save(); }
        });

        /* ---- write / split / preview ---- */

        function setView(view) {
            if (!panes) return;
            panes.setAttribute("data-view", view);
            if (preview) preview.hidden = view === "write";
            ["write", "split", "preview"].forEach(function (name) {
                var button = document.getElementById("view-" + name);
                if (button) {
                    button.classList.toggle("is-active", name === view);
                    button.setAttribute("aria-pressed", name === view ? "true" : "false");
                }
            });
            store(VIEW_KEY, view);
            if (view !== "write") render();
        }

        document.addEventListener("click", function (e) {
            var button = e.target.closest ? e.target.closest("#doc-view button") : null;
            if (!button) return;
            e.preventDefault();
            setView(button.id.replace("view-", ""));
        });

        // Split needs the width for two columns; below that it is just Write.
        // 781 rather than a number of its own: the stylesheet hides #view-split
        // at 780, and the two have to agree about where Split stops existing.
        var wide = window.matchMedia("(min-width: 781px)").matches;
        var remembered = read(VIEW_KEY);
        setView(remembered === "preview" ? "preview"
            : (remembered === "write" || !wide) ? "write" : "split");

        textarea.addEventListener("input", render);
    }

    /* ======================================================================
       Markdown → HTML lives in markdown.js, which both this and the project
       page editor load. The fallback is not defensive dressing: with the file
       missing the preview should read as plain text, not throw on every
       keystroke and take the autosave down with it.
       ====================================================================== */

    function markdown(source) {
        if (window.BT && typeof window.BT.markdown === "function") {
            return window.BT.markdown(source);
        }
        var text = String(source == null ? "" : source);
        return "<pre>" + text.replace(/&/g, "&amp;").replace(/</g, "&lt;") + "</pre>";
    }

    /* ======================================================================
       A sheet. The grid on screen is the model — every cell is a real input,
       which is what makes the no-JavaScript form post work — so this reads and
       writes the table rather than keeping a second copy of it.
       ====================================================================== */

    var table = document.getElementById("sheet");
    if (table) {
        wireSheet();
    }

    function wireSheet() {
        var MAX_ROWS = 300;
        var MAX_COLS = 40;

        var head = table.tHead.rows[0];
        var grid = table.tBodies[0];
        var colsField = document.getElementById("sheet-cols");
        var active = null;

        // The per-row and per-column × buttons are there for the browser with
        // no JavaScript. Here the toolbar does that job, and leaving them in
        // would only leave their row numbers to go stale.
        table.querySelectorAll(".js-off").forEach(function (el) { el.remove(); });

        function rows() { return Array.prototype.slice.call(grid.rows); }
        function cellsIn(row) { return Array.prototype.slice.call(row.querySelectorAll("input.cell")); }
        function colCount() { return head.cells.length - 1; }

        function cellAt(r, c) {
            var row = grid.rows[r];
            return row ? cellsIn(row)[c] : null;
        }
        function where(input) {
            var row = input.closest("tr");
            return { r: row.rowIndex - 1, c: cellsIn(row).indexOf(input) };
        }

        /** A…Z, AA, AB — the same scheme the server rendered the first ones with. */
        function label(index) {
            var out = "";
            var n = index;
            while (n >= 0) {
                out = String.fromCharCode(65 + (n % 26)) + out;
                n = Math.floor(n / 26) - 1;
            }
            return out;
        }

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

        function renumber() {
            rows().forEach(function (row, r) {
                row.cells[0].textContent = String(r + 1);
                cellsIn(row).forEach(function (input, c) {
                    input.setAttribute("aria-label", label(c) + (r + 1));
                });
            });
            if (colsField) colsField.value = String(colCount());
        }

        function addRow() {
            if (grid.rows.length >= MAX_ROWS) return null;
            var row = grid.insertRow();
            var rowno = document.createElement("th");
            rowno.className = "sheet-rowno";
            rowno.scope = "row";
            row.appendChild(rowno);
            for (var c = 0; c < colCount(); c++) {
                row.appendChild(newCell(grid.rows.length - 1, c));
            }
            renumber();
            touched();
            return row;
        }

        function addCol() {
            var index = colCount();
            if (index >= MAX_COLS) return;
            var th = document.createElement("th");
            th.scope = "col";
            th.textContent = label(index);
            head.appendChild(th);
            rows().forEach(function (row, r) { row.appendChild(newCell(r, index)); });
            renumber();
            touched();
        }

        function delRow() {
            if (!active || grid.rows.length <= 1) return;
            var r = Math.min(active.r, grid.rows.length - 1);
            grid.deleteRow(r);
            renumber();
            touched();
            recompute();
            focusCell(Math.min(r, grid.rows.length - 1), active.c);
        }

        function delCol() {
            if (!active || colCount() <= 1) return;
            var c = active.c;
            head.deleteCell(c + 1);
            rows().forEach(function (row) { row.deleteCell(c + 1); });
            // The letters are positions, not names: after a delete they shift.
            Array.prototype.slice.call(head.cells, 1).forEach(function (th, i) {
                th.textContent = label(i);
            });
            renumber();
            touched();
            recompute();
            focusCell(active.r, Math.min(c, colCount() - 1));
        }

        function focusCell(r, c) {
            var input = cellAt(r, c);
            if (input) {
                input.focus();
                input.select();
            }
        }

        document.addEventListener("click", function (e) {
            if (!e.target.closest) return;
            var button = e.target.closest("#add-row, #add-col, #del-row, #del-col");
            if (!button) return;
            e.preventDefault();          // add-row and add-col are submit buttons without JS
            if (button.id === "add-row") {
                if (addRow()) focusCell(grid.rows.length - 1, 0);
            } else if (button.id === "add-col") {
                addCol();
            } else if (button.id === "del-row") {
                delRow();
            } else if (button.id === "del-col") {
                delCol();
            }
        });

        /* ---- moving about, the way a spreadsheet does ---- */

        table.addEventListener("focusin", function (e) {
            if (!e.target.classList || !e.target.classList.contains("cell")) return;
            active = where(e.target);
            var lit = table.querySelector("td.is-active");
            if (lit) lit.classList.remove("is-active");
            e.target.closest("td").classList.add("is-active");
        });

        table.addEventListener("keydown", function (e) {
            var input = e.target;
            if (!input.classList || !input.classList.contains("cell")) return;
            var at = where(input);

            if (e.key === "Enter") {
                e.preventDefault();      // Enter means "the row below", not "submit"
                if (at.r + 1 >= grid.rows.length) addRow();
                focusCell(at.r + 1, at.c);
            } else if (e.key === "ArrowDown") {
                e.preventDefault();
                focusCell(at.r + 1, at.c);
            } else if (e.key === "ArrowUp") {
                e.preventDefault();
                focusCell(at.r - 1, at.c);
            } else if (e.key === "ArrowLeft" && input.selectionStart === 0 && at.c > 0) {
                e.preventDefault();
                focusCell(at.r, at.c - 1);
            } else if (e.key === "ArrowRight" && input.selectionEnd === input.value.length
                    && at.c < colCount() - 1) {
                e.preventDefault();
                focusCell(at.r, at.c + 1);
            }
        });

        /* ---- a block pasted out of Excel lands as a block ---- */

        table.addEventListener("paste", function (e) {
            var input = e.target;
            if (!input.classList || !input.classList.contains("cell")) return;

            var text = (e.clipboardData || window.clipboardData).getData("text");
            if (!text || (text.indexOf("\t") < 0 && text.indexOf("\n") < 0)) return;  // one cell: leave it

            e.preventDefault();
            var at = where(input);
            var block = text.replace(/\r\n?/g, "\n").replace(/\n$/, "").split("\n")
                .map(function (line) { return line.split("\t"); });

            var widest = Math.max.apply(null, block.map(function (row) { return row.length; }));
            while (colCount() < Math.min(at.c + widest, MAX_COLS)) addCol();
            while (grid.rows.length < Math.min(at.r + block.length, MAX_ROWS)) addRow();

            block.forEach(function (row, r) {
                row.forEach(function (value, c) {
                    var target = cellAt(at.r + r, at.c + c);
                    if (target) target.value = value;
                });
            });
            touched();
            recompute();
        });

        /* ==================================================================
           Formulas. A cell holding "=SUM(A1:A9)" keeps the formula as its
           value — that is what gets saved, and what you see when you click
           into it — and shows the answer through an overlay while it is not
           being edited.

           The arithmetic itself is in sheet-formula.js, shared with the
           project sheets. All this has to supply is how to read a cell.
           ================================================================== */

        var engine = window.BT && typeof window.BT.formulaEngine === "function"
            ? window.BT.formulaEngine(function (r, c) {
                var input = cellAt(r, c);
                return input ? input.value : null;
            })
            : null;

        /** Repaints the overlay on every formula cell. Only they have one. */
        function recompute() {
            rows().forEach(function (row) {
                cellsIn(row).forEach(function (input) {
                    var td = input.parentNode;
                    var overlay = td.querySelector(".cell-view");
                    var raw = input.value.trim();

                    if (raw.charAt(0) !== "=" || !engine) {
                        if (overlay) overlay.remove();
                        return;
                    }
                    if (!overlay) {
                        overlay = document.createElement("span");
                        overlay.className = "cell-view";
                        overlay.setAttribute("aria-hidden", "true");
                        td.appendChild(overlay);
                    }
                    var text = engine ? engine.evaluate(raw) : raw;
                    overlay.textContent = text;
                    overlay.classList.toggle("is-error", text.charAt(0) === "#");
                });
            });
        }

        table.addEventListener("input", recompute);
        renumber();
        recompute();
    }
    /* What a sibling editor on the same page needs from this one: a way to say
       "something changed" that is not an input event. Formatting a cell, merging
       two, dragging a column wider — none of those fire one. */
    window.BT = window.BT || {};
    window.BT.docSaver = { touched: touched, save: save };
})();
