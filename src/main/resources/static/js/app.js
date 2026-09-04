/* Bug Tracking — small progressive enhancements.
   Everything here is optional: with JS off the pages still render and work.
   Filters and menus are <details>, the board's cards are links, the drawer's
   content is a real page, and every self-submitting control has a button
   beside it. Only drag-and-drop genuinely needs scripting. */
(function () {
    "use strict";

    var calm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    var root = document.documentElement;

    function store(key, value) {
        try { localStorage.setItem(key, value); } catch (e) { /* private mode */ }
    }
    function read(key) {
        try { return localStorage.getItem(key); } catch (e) { return null; }
    }

    /* ---------- theme: system by default, remembered once you choose ---------- */
    var THEME_KEY = "bugtracking.theme";

    function applyTheme(theme) {
        if (theme === "light" || theme === "dark") {
            root.setAttribute("data-theme", theme);
        } else {
            root.removeAttribute("data-theme");
        }
        var btn = document.getElementById("theme-toggle");
        if (!btn) return;
        var dark = theme === "dark" ||
            (!theme && window.matchMedia("(prefers-color-scheme: dark)").matches);
        var use = btn.querySelector("use");
        if (use) use.setAttribute("href", dark ? "#i-sun" : "#i-moon");
        btn.title = dark ? "Switch to light" : "Switch to dark";
        btn.setAttribute("aria-label", btn.title);
    }

    applyTheme(read(THEME_KEY));

    /* ---------- theme ---------- */
    document.addEventListener("click", function (e) {
        if (!e.target.closest) return;
        if (!e.target.closest("#theme-toggle")) return;

        var isDark = root.getAttribute("data-theme") === "dark" ||
            (!root.hasAttribute("data-theme") &&
                window.matchMedia("(prefers-color-scheme: dark)").matches);
        var next = isDark ? "light" : "dark";
        store(THEME_KEY, next);
        applyTheme(next);
    });

    /* ---------- project switcher: closed until you need to move ---------- */
    (function () {
        var wrap = document.getElementById("project-switcher");
        if (!wrap) return;

        var btn = document.getElementById("switcher-btn");
        var menu = document.getElementById("switcher-menu");
        var filter = document.getElementById("switcher-filter");
        if (!btn || !menu) return;

        function open() {
            menu.hidden = false;
            btn.setAttribute("aria-expanded", "true");
            if (filter) { filter.value = ""; showAll(); filter.focus(); }
        }

        function close() {
            menu.hidden = true;
            btn.setAttribute("aria-expanded", "false");
        }

        function showAll() {
            menu.querySelectorAll(".switcher-item").forEach(function (item) { item.hidden = false; });
        }

        btn.addEventListener("click", function () {
            if (menu.hidden) { open(); } else { close(); }
        });

        if (filter) {
            filter.addEventListener("input", function () {
                var q = filter.value.trim().toLowerCase();
                menu.querySelectorAll(".switcher-item").forEach(function (item) {
                    var name = (item.getAttribute("data-name") || "").toLowerCase();
                    item.hidden = q !== "" && name.indexOf(q) === -1;
                });
            });
        }

        // Enter on the search box goes to the first project still showing.
        menu.addEventListener("keydown", function (e) {
            if (e.key !== "Enter" || e.target !== filter) return;
            var first = Array.prototype.find.call(
                menu.querySelectorAll(".switcher-item"), function (i) { return !i.hidden; });
            if (first) { e.preventDefault(); window.location.href = first.href; }
        });

        document.addEventListener("click", function (e) {
            if (!menu.hidden && !wrap.contains(e.target)) close();
        });

        document.addEventListener("keydown", function (e) {
            if (e.key === "Escape" && !menu.hidden) { close(); btn.focus(); }
        });
    })();

    /* ---------- a picker's closed button says how many are ticked ----------
       The stats drawer used to live here. It is a view of its own now, so what
       is left is the one thing a <details> full of checkboxes cannot say for
       itself: with it shut, how many did you choose? Progressive as ever — the
       markup ships a sensible word and this only keeps it current. */
    (function () {
        var pickers = document.querySelectorAll("[data-tally]");
        if (!pickers.length) return;

        Array.prototype.forEach.call(pickers, function (picker) {
            var out = picker.querySelector(".picker-tally");
            if (!out) return;
            var empty = picker.getAttribute("data-tally");

            function tally() {
                var n = picker.querySelectorAll("input[type=checkbox]:checked").length;
                out.textContent = n === 0 ? empty : (n === 1 ? "1 person" : n + " people");
                out.classList.toggle("muted", n === 0);
            }

            picker.addEventListener("change", tally);
            tally();
        });
    })();

    /* ---------- filters ----------
       Every filter is a link inside a <details>, so the toolbar works with JS
       switched off. All this adds is the behaviour a real menu needs: one open
       at a time, and clicking away closes it. */
    (function () {
        var SEL = "details.fmenu, details.pop-wrap";

        function all() {
            return Array.prototype.slice.call(document.querySelectorAll(SEL));
        }

        function closeAll(except) {
            all().forEach(function (m) { if (m !== except) m.open = false; });
        }

        // Delegated, because the drawer injects menus of its own after load.
        document.addEventListener("toggle", function (e) {
            var menu = e.target;
            if (menu.matches && menu.matches(SEL) && menu.open) closeAll(menu);
        }, true);

        document.addEventListener("click", function (e) {
            var inside = e.target.closest && e.target.closest(SEL);
            if (!inside) closeAll(null);
        });

        document.addEventListener("keydown", function (e) {
            if (e.key === "Escape") closeAll(null);
        });
    })();

    /* ---------- search: runs a beat after you stop typing ---------- */
    (function () {
        var form = document.getElementById("filter-form");
        var keyword = document.getElementById("filter-keyword");
        if (!form || !keyword) return;

        var FOCUS_KEY = "bugtracking.searchfocus";
        var timer = null;

        /* On a touch device this fought the keyboard: every pause reloaded the
           page, the keyboard closed and reopened, and the caret jumped to the
           end. The field is the only one that blocks implicit submission, so
           Go/Search on the soft keyboard already runs the search — which is
           also what happens with JS off. Nothing is lost by staying out of it. */
        var coarse = window.matchMedia("(pointer: coarse)").matches;

        if (!coarse) {
            keyword.addEventListener("input", function () {
                clearTimeout(timer);
                timer = setTimeout(function () {
                    try { sessionStorage.setItem(FOCUS_KEY, "1"); } catch (e) { /* private mode */ }
                    if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); }
                }, 550);
            });
        }

        // Put the cursor back where it was, so you can keep typing. Never on
        // touch: a focus on load raises the keyboard over the results.
        try {
            if (sessionStorage.getItem(FOCUS_KEY)) {
                sessionStorage.removeItem(FOCUS_KEY);
                if (coarse) return;
                keyword.focus();
                keyword.setSelectionRange(keyword.value.length, keyword.value.length);
            }
        } catch (e) { /* private mode */ }
    })();

    /* ---------- the board: drag a card, the status follows ---------- */
    (function () {
        var kanban = document.getElementById("kanban");
        if (!kanban) return;

        var dragged = null;
        var from = null;

        /* The whole card is clickable, not just its title. The title is still a
           real link — this only widens the target — so ⌘-click, middle-click and
           JS-off all keep working, and a click that lands on a control inside
           the card is left alone. */
        kanban.addEventListener("click", function (e) {
            var card = e.target.closest && e.target.closest(".kcard");
            if (!card) return;
            if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
            // details covers the card's own menu whole — its summary is not a
            // <button>, so without it opening the menu also opened the bug.
            if (e.target.closest("a, button, input, select, textarea, label, details")) return;

            // Selecting text inside a card should not navigate away from it.
            var selection = window.getSelection();
            if (selection && String(selection).length > 0) return;

            var href = card.getAttribute("data-href");
            if (href) window.location.href = href;
        });

        /* The placeholder is server-rendered only into a column that was empty
           when the page loaded, which left a drag in a mess both ways: a column
           you emptied kept no placeholder and collapsed to nothing you could
           drop back into, and a column you filled kept its "Empty" box sitting
           under the card you had just dropped. It is created and removed here
           instead, so what is on screen matches what a reload would draw. */
        function refreshColumn(body) {
            var col = body.closest(".kcol");
            if (!col) return;
            var count = body.querySelectorAll(".kcard").length;
            var label = col.querySelector(".kcol-count");
            if (label) label.textContent = count;

            var empty = body.querySelector(".kcol-empty");
            if (count > 0) {
                if (empty) empty.remove();
                return;
            }
            if (!empty) {
                empty = document.createElement("div");
                empty.className = "kcol-empty";
                var word = document.createElement("span");
                word.textContent = "Empty";
                empty.appendChild(word);
                body.appendChild(empty);
            }
            // Always last, whatever order the drop left things in.
            body.appendChild(empty);
            empty.hidden = false;
        }

        /* The bar used to carry a live "9 open" that a drag had to keep
           honest. Those counts are on the Stats view now, which is rendered
           per request, so a drag has nothing left up there to correct — only
           the two columns it moved a card between. */

        kanban.addEventListener("dragstart", function (e) {
            var card = e.target.closest(".kcard");
            if (!card) return;
            // Pressing an option in the card's menu is not picking the card up.
            // draggable="false" on the <details> says so too, but engines differ
            // on how far down that reaches, and a card dragged out from under an
            // open menu leaves the menu behind.
            if (e.target.closest(".kcard-menu")) { e.preventDefault(); return; }
            dragged = card;
            from = card.closest(".kcol-body");
            e.dataTransfer.effectAllowed = "move";
            // The browser photographs the element for the thing that follows
            // the cursor, and it does it during this handler. Fading the card
            // now means it photographs the faded card — so you drag a ghost of
            // a ghost, or on some engines nothing you can see at all. Told
            // explicitly what to photograph, then faded a tick later, once the
            // picture has been taken.
            try {
                var box = card.getBoundingClientRect();
                e.dataTransfer.setDragImage(card, e.clientX - box.left, e.clientY - box.top);
            } catch (err) { /* older engines: the default snapshot is fine */ }
            setTimeout(function () { if (dragged === card) card.classList.add("is-dragging"); }, 0);
            try { e.dataTransfer.setData("text/plain", card.getAttribute("data-id")); } catch (err) { /* IE-ish */ }
        });

        kanban.addEventListener("dragend", function () {
            if (dragged) dragged.classList.remove("is-dragging");
            kanban.querySelectorAll(".kcol.is-over").forEach(function (c) { c.classList.remove("is-over"); });
            dragged = null;
        });

        kanban.querySelectorAll(".kcol-body").forEach(function (body) {
            var col = body.closest(".kcol");

            body.addEventListener("dragover", function (e) {
                if (!dragged) return;
                e.preventDefault();
                e.dataTransfer.dropEffect = "move";
                col.classList.add("is-over");
            });

            body.addEventListener("dragleave", function (e) {
                if (!body.contains(e.relatedTarget)) col.classList.remove("is-over");
            });

            body.addEventListener("drop", function (e) {
                e.preventDefault();
                col.classList.remove("is-over");
                if (!dragged || !from || body === from) return;

                var card = dragged;
                var origin = from;
                var status = body.getAttribute("data-status");
                var id = card.getAttribute("data-id");

                var empty = body.querySelector(".kcol-empty");
                if (empty) { body.insertBefore(card, empty); } else { body.appendChild(card); }
                card.classList.remove("is-dragging");
                // Moving an element in the DOM restarts its CSS animation, and
                // every card has a staggered fade-in — so a dropped card went
                // invisible and faded back in, which is the flicker. It has
                // already arrived; it does not need introducing again.
                card.classList.add("is-placed");
                card.classList.add("is-saving");
                refreshColumn(body);
                refreshColumn(origin);

                fetch("/api/bugs/" + id + "/status?status=" + encodeURIComponent(status), {
                    method: "POST",
                    credentials: "same-origin"
                }).then(function (res) {
                    if (!res.ok) throw new Error(res.status + "");
                    card.classList.remove("is-saving");
                    if (!calm) {
                        card.classList.add("just-moved");
                        setTimeout(function () { card.classList.remove("just-moved"); }, 600);
                    }
                }).catch(function () {
                    // Put it back where it came from rather than lie about it.
                    var slot = origin.querySelector(".kcol-empty");
                    if (slot) { origin.insertBefore(card, slot); } else { origin.appendChild(card); }
                    card.classList.add("is-placed");
                    card.classList.remove("is-saving");
                    refreshColumn(body);
                    refreshColumn(origin);
                    flash("Could not move BUG-" + id + " — the change was not saved.");
                });
            });
        });
    })();

    /* ---------- the whole list row is the link its title is ----------
       The same widening the board's cards got, and the same rules: the title
       stays a real <a>, so a modified click, a middle click and JavaScript off
       all behave exactly as they did, and a click that lands on a control is
       left to the control. That last part is what lets a row hold a status
       picker and a copy button and still be clickable — without it, changing a
       status would navigate away from the list mid-change.

       Delegated on the document rather than bound to the bug list: the roster
       in Settings is the same shape — a row that stands for a page — and a
       second copy of this would be a second set of rules for the same gesture. */
    (function () {
        document.addEventListener("click", function (e) {
            var row = e.target.closest && e.target.closest("tr[data-href]");
            if (!row) return;
            if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
            if (e.target.closest("a, button, input, select, textarea, label, option")) return;

            // Selecting a title to copy it should not open the bug.
            var selection = window.getSelection();
            if (selection && String(selection).length > 0) return;

            window.location.href = row.getAttribute("data-href");
        });
    })();

    /* ---------- back means back ----------
       The arrow used to be a fixed link to one place — "Board" — which is only
       where you came from if you arrived the obvious way. Reached from a
       notification, a search result or the trash, it took you somewhere you
       had never been and called it going back.

       Its href is still that sensible default, and that is what a browser with
       JavaScript off follows and what a modified click opens. What this adds is
       the ordinary case: if the page before this one was on this site, go to
       it. document.referrer rather than history.length, which counts entries
       this tab made before it ever reached us. */
    document.addEventListener("click", function (e) {
        if (!e.target.closest) return;
        var back = e.target.closest(".back-link");
        if (!back) return;
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;

        var from = document.referrer || "";
        if (from.indexOf(window.location.origin + "/") !== 0) return;   // arrived from elsewhere
        if (from === window.location.href) return;                      // a reload, not a step
        e.preventDefault();
        window.history.back();
    });

    /* ---------- drag a card to the trash ----------
       The bin in the navbar takes a card. Deleting from the board otherwise
       meant opening the bug to reach its Delete, which is a page load and a
       page back for something you had already decided.

       It posts the ordinary form rather than fetching: the delete route
       answers with the flash that carries **Undo**, and that undo is the whole
       reason this is safe to do by accident. */
    (function () {
        var bin = document.getElementById("trash-link");
        var form = document.getElementById("trash-drop-form");
        if (!bin || !form) return;

        function dragging(e) {
            // types is the only thing readable during a dragover, and a card
            // is the only thing in this app that sets text/plain.
            return e.dataTransfer && Array.prototype.indexOf.call(
                e.dataTransfer.types || [], "text/plain") !== -1;
        }

        bin.addEventListener("dragover", function (e) {
            if (!dragging(e)) return;
            e.preventDefault();
            e.dataTransfer.dropEffect = "move";
            bin.classList.add("is-drop");
        });
        bin.addEventListener("dragleave", function () { bin.classList.remove("is-drop"); });

        bin.addEventListener("drop", function (e) {
            e.preventDefault();
            bin.classList.remove("is-drop");
            var id = "";
            try { id = e.dataTransfer.getData("text/plain"); } catch (err) { /* older engines */ }
            if (!/^\d+$/.test(id)) return;

            // Composed from the route's base so the context path comes along.
            form.action = form.getAttribute("data-base") + "/" + id + "/delete";
            form.submit();
        });
    })();

    /* ---------- the board: drag a column head, the order follows ----------
       The arrows in a column's menu do the same thing and need no script; this
       is the mouse's shortcut. Reordering is only offered on a single project's
       board — the whole-board view merges several projects' columns and there
       is no one board to change. */
    (function () {
        var kanban = document.getElementById("kanban");
        if (!kanban) return;
        var project = kanban.getAttribute("data-project");
        if (!project) return;

        var dragged = null;

        function columns() {
            return Array.prototype.slice.call(
                kanban.querySelectorAll(".kcol:not(.kcol-new)"));
        }

        function clearMarks() {
            kanban.querySelectorAll(".kcol.is-col-over").forEach(function (col) {
                col.classList.remove("is-col-over");
            });
        }

        function target(e) {
            var over = e.target.closest && e.target.closest(".kcol");
            if (!over || over === dragged || over.classList.contains("kcol-new")) return null;
            return over;
        }

        /* The card handler on the same element looks for .kcard and finds
           nothing here, so the two never both claim a drag. */
        kanban.addEventListener("dragstart", function (e) {
            var head = e.target.closest && e.target.closest(".kcol-head");
            if (!head || head.getAttribute("draggable") !== "true") return;
            dragged = head.closest(".kcol");
            dragged.classList.add("is-col-dragging");
            e.dataTransfer.effectAllowed = "move";
            try { e.dataTransfer.setData("text/plain", dragged.getAttribute("data-column")); } catch (err) { /* IE-ish */ }
        });

        kanban.addEventListener("dragend", function () {
            if (dragged) dragged.classList.remove("is-col-dragging");
            clearMarks();
            dragged = null;
        });

        kanban.addEventListener("dragover", function (e) {
            var over = target(e);
            if (!dragged || !over) return;
            e.preventDefault();
            e.dataTransfer.dropEffect = "move";
            clearMarks();
            over.classList.add("is-col-over");
        });

        kanban.addEventListener("drop", function (e) {
            var over = target(e);
            if (!dragged || !over) return;
            e.preventDefault();
            clearMarks();

            var order = columns();
            var moving = dragged;
            /* Dropped to the right of where it started it goes after that
               column, to the left it goes before — which is what the pointer
               was pointing at either way. */
            kanban.insertBefore(moving,
                order.indexOf(moving) < order.indexOf(over) ? over.nextSibling : over);
            moving.classList.remove("is-col-dragging");
            dragged = null;
            refreshEnds();
            save(moving);
        });

        /* The two arrows and the live dot describe positions, so they are wrong
           the moment something moves. */
        function refreshEnds() {
            var order = columns();
            order.forEach(function (col, i) {
                var left = col.querySelector("[data-move='left']");
                var right = col.querySelector("[data-move='right']");
                if (left) left.disabled = i === 0;
                if (right) right.disabled = i === order.length - 1;
                var dot = col.querySelector(".kcol-head .dot");
                if (dot) dot.classList.toggle("is-live", i === 0);
                col.style.setProperty("--i", i);
            });
        }

        function save(moved) {
            var body = "project=" + encodeURIComponent(project);
            columns().forEach(function (col) {
                var id = col.getAttribute("data-column");
                if (id) body += "&ids=" + encodeURIComponent(id);
            });

            moved.classList.add("is-saving");
            fetch("/api/columns/order", {
                method: "POST",
                credentials: "same-origin",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: body
            }).then(function (res) {
                if (!res.ok) throw new Error(res.status + "");
                moved.classList.remove("is-saving");
            }).catch(function () {
                /* The board on screen and the board in the database have
                   diverged, and only one of them is right. */
                moved.classList.remove("is-saving");
                flash("Could not reorder the board — putting it back.");
                setTimeout(function () { window.location.reload(); }, 900);
            });
        }
    })();

    /* ---------- folding a column out of the way ----------
       Seven columns is a board you scroll; four columns and three folded strips
       is a board you read. Nothing about a bug changes and nothing is posted —
       it is a view, so it is remembered in this browser and per project: the
       columns you fold on one board are not the ones you fold on the next.

       The buttons are rendered hidden and revealed here. With script off there
       is nothing behind them, and a control that does nothing is worse than no
       control; the board still shows every column, which is the state this
       starts from anyway. */
    (function () {
        var kanban = document.getElementById("kanban");
        if (!kanban) return;
        var buttons = kanban.querySelectorAll(".kcol-fold");
        if (!buttons.length) return;

        var KEY = "bugtracking.board.folded";
        var project = kanban.getAttribute("data-project") || "*";

        /* Every board's folded columns, keyed by project. Anything unreadable
           is treated as nothing folded rather than thrown: this is a
           convenience, and a bad value in localStorage must not be able to
           stop the board drawing. */
        function all() {
            try {
                var held = JSON.parse(read(KEY) || "{}");
                return held && typeof held === "object" ? held : {};
            } catch (e) { return {}; }
        }

        function folded() {
            var here = all()[project];
            return Object.prototype.toString.call(here) === "[object Array]" ? here : [];
        }

        function remember(list) {
            var map = all();
            if (list.length) { map[project] = list; } else { delete map[project]; }
            store(KEY, JSON.stringify(map));
        }

        function name(col) {
            var head = col.querySelector("h3");
            return head ? head.textContent.trim() : "this column";
        }

        function label(button, col, isFolded) {
            button.title = (isFolded ? "Unfold " : "Fold ") + name(col);
            button.setAttribute("aria-label", isFolded
                ? "Unfold the " + name(col) + " column"
                : "Fold the " + name(col) + " column away");
            button.setAttribute("aria-expanded", isFolded ? "false" : "true");
        }

        function paint(col, button, isFolded) {
            col.classList.toggle("is-collapsed", isFolded);
            label(button, col, isFolded);
        }

        /* Applied before the buttons are shown, so a board that opens with
           three columns folded never flashes them open first. This runs from a
           synchronous script at the end of <body>, which is before the first
           paint. */
        var open = folded();
        Array.prototype.forEach.call(buttons, function (button) {
            var col = button.closest(".kcol");
            if (!col) return;
            paint(col, button, open.indexOf(button.getAttribute("data-fold")) >= 0);
            button.hidden = false;
        });

        kanban.addEventListener("click", function (e) {
            var button = e.target.closest && e.target.closest(".kcol-fold");
            if (!button) return;
            var col = button.closest(".kcol");
            if (!col) return;

            var key = button.getAttribute("data-fold");
            var list = folded();
            var at = list.indexOf(key);
            var isFolded = at < 0;
            if (isFolded) { list.push(key); } else { list.splice(at, 1); }

            remember(list);
            paint(col, button, isFolded);
            /* The strip is 46px wide and the button inside it is the thing that
               was just pressed, so keeping focus on it is what lets a keyboard
               fold and unfold without hunting. */
            button.focus();
        });
    })();

    /* ---------- the raise/edit form: the status list follows the project ----------
       Columns belong to a project, so changing the project changes which ones
       exist. Every project's are on the select as data-columns. Nothing depends
       on this: with script off the list stays as it loaded, and the server puts
       a bug that arrives holding a column its new project does not have into
       that board's first column. */
    (function () {
        var project = document.getElementById("project");
        var status = document.getElementById("status");
        if (!project || !status) return;

        var boards;
        try {
            boards = JSON.parse(status.getAttribute("data-columns") || "{}");
        } catch (e) {
            return;
        }

        project.addEventListener("change", function () {
            var columns = boards[project.value];
            if (!columns || !columns.length) return;

            var chosen = status.value;
            status.innerHTML = "";
            columns.forEach(function (column) {
                var option = document.createElement("option");
                option.value = column.status;
                option.textContent = column.label;
                if (column.status === chosen) option.selected = true;
                status.appendChild(option);
            });
            if (status.selectedIndex < 0) status.selectedIndex = 0;
        });
    })();

    /* ---------- @mentions ----------
       Two halves. Reading: a name the roster knows is drawn as a chip so a
       tagged comment — or a tagged document — scans. Writing: typing "@" offers
       the roster, because names have spaces and nobody should have to type them
       exactly. Neither is load-bearing — the server matches mentions against
       the team again on save, so a name typed by hand still notifies.

       The roster comes off whatever element on the page carries data-people:
       the comment form on a bug, the editor form on a project page or sheet.
       One implementation, because "who did they tag" is the same question
       wherever the "@" was typed. */
    /* ---------- fenced code, in a report and in the thread ----------
       A developer pasting a response body onto a bug is pasting evidence, and
       evidence rendered as a paragraph — wrapped, proportional, its indentation
       collapsed — is evidence nobody can check. So ``` fences in text that is
       already on the page become real blocks: monospaced, never wrapped,
       scrolling inside themselves, with Copy and, when the contents parse,
       Format.

       Nothing is stored differently and nothing new is posted. The fences are
       in the text the way the author typed them, the server keeps them
       verbatim, BugMarkdown already leaves them alone, and with script off they
       are visible as ``` — which carries the same information. This is a
       rendering, not a format.

       It runs before the mentions pass below on purpose: that pass walks text
       nodes and skips anything inside CODE or PRE, so a "@name" in a snippet
       must already be in a snippet by the time it looks. */
    (function () {
        var MIN_FENCE = /^\s*```(\w*)\s*$/;
        var CLOSE_FENCE = /^\s*```\s*$/;

        /* The text as JSON, indented, or null when it is not JSON. Only ever
           tried on something that opens with a brace or a bracket: JSON.parse
           accepts "12" and "null" as well, and offering to reformat a stack
           trace's first line is worse than offering nothing. */
        function asJson(text) {
            var trimmed = text.trim();
            if (!trimmed || "{[".indexOf(trimmed.charAt(0)) < 0) return null;
            try {
                return JSON.stringify(JSON.parse(trimmed), null, 2);
            } catch (e) { return null; }
        }

        /* Splits text into runs of prose and fenced blocks. An opening fence
           with nothing closing it is left as text — somebody mid-sentence about
           three backticks has not opened a block. */
        function segments(text) {
            var lines = String(text).replace(/\r\n?/g, "\n").split("\n");
            var out = [];
            var prose = [];
            var i = 0;

            while (i < lines.length) {
                var open = MIN_FENCE.exec(lines[i]);
                if (!open) { prose.push(lines[i++]); continue; }

                var end = -1;
                for (var j = i + 1; j < lines.length; j++) {
                    if (CLOSE_FENCE.test(lines[j])) { end = j; break; }
                }
                if (end < 0) { prose.push(lines[i++]); continue; }

                out.push({ text: prose.join("\n") });
                prose = [];
                out.push({ code: lines.slice(i + 1, end).join("\n"), lang: open[1] || "" });
                i = end + 1;
            }
            out.push({ text: prose.join("\n") });
            return out;
        }

        function button(text, title) {
            var el = document.createElement("button");
            el.type = "button";
            el.className = "code-act";
            el.textContent = text;
            el.title = title;
            return el;
        }

        function block(code, lang) {
            var pretty = asJson(code);
            var figure = document.createElement("figure");
            figure.className = "code-block";

            var head = document.createElement("figcaption");
            head.className = "code-head";

            var kind = document.createElement("span");
            kind.className = "code-lang";
            // What it says it is, else what it turns out to be, else nothing
            // more specific than "code".
            kind.textContent = lang || (pretty ? "json" : "code");
            head.appendChild(kind);

            var gap = document.createElement("span");
            gap.className = "spacer";
            head.appendChild(gap);

            var pre = document.createElement("pre");
            var body = document.createElement("code");
            body.textContent = code;                  // text, never HTML
            pre.appendChild(body);

            // Only when indenting it would actually change something: a block
            // somebody already formatted needs no button offering to.
            if (pretty !== null && pretty !== code.trim()) {
                var format = button("Format", "Indent this JSON");
                format.setAttribute("aria-pressed", "false");
                format.addEventListener("click", function () {
                    var on = format.getAttribute("aria-pressed") === "true";
                    body.textContent = on ? code : pretty;
                    format.setAttribute("aria-pressed", on ? "false" : "true");
                    format.textContent = on ? "Format" : "Original";
                    say(on ? "Showing it as it was written" : "Indented");
                });
                head.appendChild(format);
            }

            var copy = button("Copy", "Copy this block");
            copy.addEventListener("click", function () {
                var copier = (window.BT && window.BT.copyText)
                    || function (text) { return navigator.clipboard.writeText(text); };
                copier(body.textContent).then(function () {
                    copy.textContent = "Copied";
                    say("Copied");
                    setTimeout(function () { copy.textContent = "Copy"; }, 1400);
                }).catch(function () {
                    copy.textContent = "Press ⌘C";
                    setTimeout(function () { copy.textContent = "Copy"; }, 1800);
                });
            });
            head.appendChild(copy);

            figure.appendChild(head);
            figure.appendChild(pre);
            return figure;
        }

        /* A <p> cannot legally hold a <figure>, and a browser handed one closes
           the paragraph early and leaves the rest of the comment outside it. So
           the paragraph becomes a <div> wearing the same classes, which is what
           every rule that styled it was matching on anyway. */
        function render(el) {
            var text = el.textContent;
            if (!text || text.indexOf("```") < 0) return;

            var parts = segments(text);
            if (!parts.some(function (part) { return part.code !== undefined; })) return;

            var box = document.createElement("div");
            box.className = el.className;
            parts.forEach(function (part) {
                if (part.code !== undefined) {
                    box.appendChild(block(part.code, part.lang));
                    return;
                }
                // The container keeps white-space: pre-wrap, so the newlines
                // that hugged a fence would otherwise show as blank lines
                // around the block.
                var prose = part.text.replace(/^\n+/, "").replace(/\n+$/, "");
                if (prose) box.appendChild(document.createTextNode(prose));
            });
            el.parentNode.replaceChild(box, el);
        }

        document.querySelectorAll(".report-sec > .pre, .comment-text").forEach(render);

        /* ---- writing one ----
           Wraps the selection in a fence, and indents it on the way if it is
           JSON — which is what somebody pasting into a bug usually has. The
           button is hidden in the markup and revealed here; typing the fences
           by hand works exactly as it did and is what happens with script off. */
        function wrap(box) {
            var start = box.selectionStart;
            var end = box.selectionEnd;
            var chosen = box.value.slice(start, end);
            var pretty = asJson(chosen);
            var body = (pretty === null ? chosen : pretty).replace(/^\n+/, "").replace(/\n+$/, "");

            var before = box.value.slice(0, start);
            var after = box.value.slice(end);
            // A fence only opens a block at the start of a line.
            if (before && !/\n$/.test(before)) before += "\n";
            if (after && !/^\n/.test(after)) after = "\n" + after;

            var lang = pretty === null ? "" : "json";
            var fenced = "```" + lang + "\n" + body + "\n```";
            box.value = before + fenced + after;

            // Inside an empty block, ready to type; after a filled one.
            var caret = body
                ? (before + fenced).length
                : before.length + 4 + lang.length;
            box.focus();
            box.setSelectionRange(caret, caret);
            // So anything counting characters or auto-growing the box hears it.
            box.dispatchEvent(new Event("input", { bubbles: true }));
        }

        document.querySelectorAll("[data-code-insert]").forEach(function (button) {
            if (document.getElementById(button.getAttribute("data-code-insert"))) {
                button.hidden = false;
            }
        });

        document.addEventListener("click", function (e) {
            var button = e.target.closest && e.target.closest("[data-code-insert]");
            if (!button) return;
            var box = document.getElementById(button.getAttribute("data-code-insert"));
            if (box) wrap(box);
        });
    })();

    (function () {
        var source = document.querySelector("[data-people]");
        var people = [];
        if (source && source.getAttribute("data-people")) {
            people = source.getAttribute("data-people").split("|").filter(Boolean);
        }
        if (!people.length) return;

        // Longest first, so "@Anita Rao" is not matched as "@Anita".
        var byLength = people.slice().sort(function (a, b) { return b.length - a.length; });

        /* ---- reading ---- */

        /** The roster name written at `at`, or null. */
        function hitAt(text, at) {
            for (var n = 0; n < byLength.length; n++) {
                var candidate = text.substr(at + 1, byLength[n].length);
                if (candidate.toLowerCase() === byLength[n].toLowerCase()) return byLength[n];
            }
            return null;
        }

        /** Rewrites one text node's "@Name"s into chips. */
        function chipify(node) {
            var text = node.nodeValue;
            if (!text || text.indexOf("@") < 0) return;

            var out = document.createDocumentFragment();
            var i = 0;
            var found = false;
            while (i < text.length) {
                var at = text.indexOf("@", i);
                if (at < 0) break;

                var hit = hitAt(text, at);
                if (!hit) { i = at + 1; continue; }

                out.appendChild(document.createTextNode(text.slice(i, at)));
                var chip = document.createElement("span");
                chip.className = "mention";
                chip.textContent = "@" + hit;
                out.appendChild(chip);
                i = at + 1 + hit.length;
                found = true;
            }
            if (!found) return;
            out.appendChild(document.createTextNode(text.slice(i)));
            node.parentNode.replaceChild(out, node);
        }

        /* Walks text nodes rather than rewriting innerHTML: the preview holds
           rendered Markdown, and replacing its HTML would break every link and
           checkbox in it. Code and links are skipped — an "@" inside a snippet
           or a URL is not a tag. */
        function markMentions(root) {
            if (!root) return;
            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                acceptNode: function (node) {
                    var parent = node.parentNode;
                    while (parent && parent !== root) {
                        var name = parent.nodeName;
                        if (name === "CODE" || name === "PRE" || name === "A"
                                || (parent.classList && parent.classList.contains("mention"))) {
                            return NodeFilter.FILTER_REJECT;
                        }
                        parent = parent.parentNode;
                    }
                    return node.nodeValue.indexOf("@") < 0
                        ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
                }
            });
            var nodes = [];
            while (walker.nextNode()) nodes.push(walker.currentNode);
            nodes.forEach(chipify);          // collected first: chipify moves nodes
        }

        document.querySelectorAll(".comment-text").forEach(markMentions);

        // The Markdown preview is redrawn as you type, so it asks for this
        // itself rather than being marked up once here.
        window.BT = window.BT || {};
        window.BT.markMentions = markMentions;

        /* ---- writing ----
           Every box that takes a comment gets the same menu: the one at the top
           of the section, and the reply and edit boxes inside the thread, which
           are marked data-mentions. Tagging somebody is most of what a reply is
           for, and it used to work only in the box you never replied from. */
        var boxes = document.querySelectorAll("#comment-text, textarea[data-mentions]");
        if (!boxes.length) return;
        Array.prototype.forEach.call(boxes, attach);

        function attach(box) {

            var menu = document.createElement("div");
            menu.className = "mention-menu";
            menu.hidden = true;
            box.parentNode.appendChild(menu);

            var active = -1;
            var matches = [];

            function close() {
                menu.hidden = true;
                menu.textContent = "";
                active = -1;
                matches = [];
            }

            // The "@" being typed right now: the last one with no space after it.
            function pending() {
                var upto = box.value.slice(0, box.selectionStart);
                var at = upto.lastIndexOf("@");
                if (at < 0) return null;
                var typed = upto.slice(at + 1);
                // A mention is at most two words; beyond that they are just writing.
                if (typed.indexOf("\n") >= 0 || typed.split(" ").length > 2) return null;
                return { at: at, typed: typed };
            }

            function render() {
                var state = pending();
                if (!state) { close(); return; }

                var needle = state.typed.toLowerCase();
                matches = people.filter(function (p) {
                    return p.toLowerCase().indexOf(needle) === 0;
                }).slice(0, 6);

                if (!matches.length) { close(); return; }

                menu.textContent = "";
                matches.forEach(function (name, index) {
                    var item = document.createElement("button");
                    item.type = "button";
                    item.className = "mention-opt" + (index === 0 ? " is-active" : "");
                    item.textContent = name;
                    item.addEventListener("mousedown", function (e) {
                        e.preventDefault();             // keep the caret in the textarea
                        pick(name);
                    });
                    menu.appendChild(item);
                });
                active = 0;
                menu.hidden = false;
            }

            function highlight() {
                Array.prototype.forEach.call(menu.children, function (el, i) {
                    el.classList.toggle("is-active", i === active);
                });
            }

            function pick(name) {
                var state = pending();
                if (!state) { close(); return; }
                var before = box.value.slice(0, state.at);
                var after = box.value.slice(box.selectionStart);
                box.value = before + "@" + name + " " + after;
                var caret = (before + "@" + name + " ").length;
                box.setSelectionRange(caret, caret);
                close();
                box.focus();
                // The editor saves on input, and setting .value fires nothing.
                box.dispatchEvent(new Event("input", { bubbles: true }));
            }

            box.addEventListener("input", render);
            box.addEventListener("blur", function () { setTimeout(close, 120); });

            box.addEventListener("keydown", function (e) {
                if (menu.hidden || !matches.length) return;
                if (e.key === "ArrowDown") {
                    e.preventDefault();
                    active = (active + 1) % matches.length;
                    highlight();
                } else if (e.key === "ArrowUp") {
                    e.preventDefault();
                    active = (active - 1 + matches.length) % matches.length;
                    highlight();
                } else if (e.key === "Enter" || e.key === "Tab") {
                    e.preventDefault();
                    pick(matches[active]);
                } else if (e.key === "Escape") {
                    e.preventDefault();
                    close();
                }
            });
        }
    })();

    /* ---------- opening a reply puts you after the mention ----------
       The box arrives holding "@Name ", written by the server so it is there
       with scripting off. All this adds is the caret landing after it rather
       than at the start, which is the difference between a prefilled field and
       one you have to click into and arrow past. */
    (function () {
        document.addEventListener("click", function (e) {
            var summary = e.target.closest && e.target.closest(".comment-tool > summary");
            if (!summary) return;
            var fold = summary.parentNode;
            // The click has not toggled it yet, so [open] here means closing.
            if (fold.hasAttribute("open")) return;

            setTimeout(function () {
                var box = fold.querySelector("textarea");
                if (!box) return;
                box.focus();
                box.setSelectionRange(box.value.length, box.value.length);
            }, 0);
        });
    })();

    /* ---------- filtering a picker by name ----------
       Any .pick-search narrows the list beside it: the assignee pickers on the
       raise form and the bug page, and the team on a new project in Settings.
       Delegated on the class rather than bound to three ids, because it is one
       gesture — and because the third of them is inside a popover that may not
       exist when this runs. */
    (function () {
        function listFor(box) {
            var wrap = box.closest(".pick-search");
            return wrap && wrap.parentNode
                ? wrap.parentNode.querySelector(".pick-list, .pop-list")
                : null;
        }

        document.addEventListener("input", function (e) {
            var box = e.target;
            if (!box.closest || !box.closest(".pick-search")) return;
            var list = listFor(box);
            if (!list) return;

            var q = box.value.trim().toLowerCase();
            var shown = 0;
            list.querySelectorAll(".pick-opt").forEach(function (opt) {
                var name = (opt.getAttribute("data-name") || opt.textContent || "").toLowerCase();
                var hit = q === "" || name.indexOf(q) !== -1;
                opt.hidden = !hit;
                if (hit) shown++;
            });
            var none = list.querySelector(".pick-none");
            if (none) none.hidden = shown > 0;
        });

        // Typing is for finding, not for submitting the form underneath.
        document.addEventListener("keydown", function (e) {
            if (e.key !== "Enter" || !e.target.closest) return;
            if (e.target.closest(".pick-search")) e.preventDefault();
        });
    })();

    /* ---------- attachments open in a viewer over the page ----------
       You are still reading the bug, so the evidence opens over it rather than
       instead of it. Every thumbnail stays a real link to the file, which is
       what makes all of this optional: with scripting off a click is still the
       attachment in a new tab, exactly as it was before there was a viewer.

       What it adds over "the picture, big": the rest of the bug's evidence is
       beside it, so comparing the screenshot before the fix with the one after
       is an arrow key rather than two round trips; a picture zooms and pans,
       because the thing you need to see is usually eight pixels of a stack
       trace; and a clip plays with its own native controls, which are better at
       being a video player than anything written here would be. */
    (function () {
        var box = document.getElementById("lightbox");
        var stage = document.getElementById("lightbox-stage");
        if (!box || !stage) return;

        var name = document.getElementById("lightbox-name");
        var openIn = document.getElementById("lightbox-open");
        var count = document.getElementById("lightbox-count");
        var strip = document.getElementById("lightbox-strip");
        var prevBtn = document.getElementById("lightbox-prev");
        var nextBtn = document.getElementById("lightbox-next");
        var zoomBar = document.getElementById("lightbox-zoom");
        var zoomLevel = document.getElementById("lightbox-zoom-level");

        var MIN = 1, MAX = 6, STEP = 1.4;

        var lastFocus = null;
        var closeTimer = null;
        var prevOverflow = "";
        var showing = false;

        var items = [];          // the gallery this opening is stepping through
        var at = -1;
        var media = null;        // the <img> or <video> currently on the stage
        var zoom = 1, panX = 0, panY = 0;
        var drag = null;

        /* ---- what a trigger says about itself ---- */
        function srcOf(el) { return el.getAttribute("href") || el.getAttribute("data-src") || ""; }
        function labelOf(el) { return el.getAttribute("data-lightbox") || "Attachment"; }
        function kindOf(el) { return el.getAttribute("data-lightbox-kind") || "image"; }

        /* The set to step through: everything openable inside the nearest
           [data-gallery], which is what keeps a comment's screenshots out of the
           report's. Without one — anywhere that has not said — the trigger is a
           gallery of one, rather than silently joining every image on the page. */
        function galleryFor(el) {
            var scope = el.closest("[data-gallery]");
            if (!scope) return [el];
            return Array.prototype.slice.call(scope.querySelectorAll("[data-lightbox]"));
        }

        /* ---- zoom and pan ---- */
        function apply() {
            if (!media) return;
            media.style.transform = "translate(" + panX + "px, " + panY + "px) scale(" + zoom + ")";
            media.classList.toggle("is-zoomed", zoom > 1);
            if (zoomLevel) zoomLevel.textContent = Math.round(zoom * 100) + "%";
        }

        /* Panned so far that the picture has left the window is a zoom control
           that has lost the thing it was pointed at. */
        function clampPan() {
            if (!media) { panX = panY = 0; return; }
            var wide = Math.max(0, (media.offsetWidth * zoom - stage.clientWidth) / 2);
            var tall = Math.max(0, (media.offsetHeight * zoom - stage.clientHeight) / 2);
            panX = Math.min(wide, Math.max(-wide, panX));
            panY = Math.min(tall, Math.max(-tall, panY));
        }

        /* originX/Y are measured from the middle of the stage, so a wheel or a
           double-click zooms into what is under the pointer rather than into the
           centre and away from whatever you were looking at. */
        function zoomTo(next, originX, originY) {
            if (!media || media.tagName !== "IMG") return;
            next = Math.min(MAX, Math.max(MIN, next));
            if (next === zoom) return;
            var k = next / zoom;
            panX = (originX || 0) - k * ((originX || 0) - panX);
            panY = (originY || 0) - k * ((originY || 0) - panY);
            zoom = next;
            if (zoom === MIN) { panX = panY = 0; }
            clampPan();
            apply();
        }

        function resetZoom() {
            zoom = 1; panX = 0; panY = 0;
            apply();
        }

        function originFrom(e) {
            var r = stage.getBoundingClientRect();
            return [e.clientX - (r.left + r.width / 2), e.clientY - (r.top + r.height / 2)];
        }

        /* ---- the stage ---- */

        /* A 404, or an error page served as HTML with a 200, both arrive here.
           Either way the stage would sit empty while the bar names the file with
           confidence; say so instead, and keep a way out of the overlay. */
        function failed(href) {
            stage.innerHTML = "";
            media = null;
            var note = document.createElement("p");
            note.className = "panel panel-pad hint";
            note.textContent = "This file could not be loaded. ";
            var link = document.createElement("a");
            link.href = href;
            link.target = "_blank";
            link.rel = "noopener";
            link.textContent = "Open it in a new tab";
            note.appendChild(link);
            stage.appendChild(note);
        }

        /* Whatever is on the stage stops being a thing the browser is working
           on: a video left in the DOM keeps its buffer and its sound. */
        function clearStage() {
            var playing = stage.querySelector("video");
            if (playing) { playing.pause(); playing.removeAttribute("src"); playing.load(); }
            stage.innerHTML = "";
            media = null;
        }

        function show(index) {
            if (index < 0 || index >= items.length) return;
            at = index;
            var el = items[at];
            var href = srcOf(el);
            var label = labelOf(el);

            clearStage();
            resetZoom();

            if (kindOf(el) === "video") {
                var video = document.createElement("video");
                video.className = "lightbox-media lightbox-video";
                video.controls = true;
                video.playsInline = true;
                video.preload = "metadata";
                video.setAttribute("aria-label", label);
                video.onerror = function () { failed(href); };
                video.src = href;
                stage.appendChild(video);
                media = video;
            } else {
                var img = document.createElement("img");
                img.className = "lightbox-media lightbox-img";
                img.alt = label;
                img.draggable = false;
                img.onerror = function () { failed(href); };
                img.src = href;
                stage.appendChild(img);
                media = img;
            }

            if (zoomBar) zoomBar.hidden = kindOf(el) !== "image";
            if (name) name.textContent = label;
            if (openIn) openIn.href = href;
            if (count) {
                count.hidden = items.length < 2;
                count.textContent = (at + 1) + " / " + items.length;
            }
            if (prevBtn) prevBtn.hidden = items.length < 2;
            if (nextBtn) nextBtn.hidden = items.length < 2;
            markStrip();
            apply();
        }

        /* Wraps on purpose: four screenshots read in a circle, and an arrow that
           stops dead at the end is one you press twice to find that out. */
        function step(by) {
            if (items.length < 2) return;
            show((at + by + items.length) % items.length);
        }

        /* ---- the filmstrip ---- */
        function buildStrip() {
            if (!strip) return;
            strip.innerHTML = "";
            strip.hidden = items.length < 2;
            if (items.length < 2) return;

            items.forEach(function (el, i) {
                var tile = document.createElement("button");
                tile.type = "button";
                tile.className = "lightbox-thumb";
                tile.setAttribute("data-go", String(i));
                tile.title = labelOf(el);
                tile.setAttribute("aria-label", labelOf(el));

                if (kindOf(el) === "video") {
                    // No frame to show without decoding the file, so say what it
                    // is rather than pull a video down to draw a strip.
                    tile.className += " is-clip";
                    var mark = document.createElementNS("http://www.w3.org/2000/svg", "svg");
                    mark.setAttribute("class", "i");
                    mark.setAttribute("aria-hidden", "true");
                    var use = document.createElementNS("http://www.w3.org/2000/svg", "use");
                    use.setAttribute("href", "#i-play");
                    mark.appendChild(use);
                    tile.appendChild(mark);
                } else {
                    var thumb = document.createElement("img");
                    thumb.src = srcOf(el);
                    thumb.alt = "";
                    thumb.loading = "lazy";
                    tile.appendChild(thumb);
                }
                strip.appendChild(tile);
            });
        }

        function markStrip() {
            if (!strip || strip.hidden) return;
            var tiles = strip.querySelectorAll(".lightbox-thumb");
            Array.prototype.forEach.call(tiles, function (tile, i) {
                var here = i === at;
                tile.classList.toggle("is-here", here);
                tile.setAttribute("aria-current", here ? "true" : "false");
                if (here && tile.scrollIntoView) {
                    tile.scrollIntoView({ block: "nearest", inline: "nearest",
                                          behavior: calm ? "auto" : "smooth" });
                }
            });
        }

        /* ---- opening and closing ---- */
        function open(trigger) {
            // A close still fading owns a timer that blanks the stage. Reopening
            // inside those 210ms fired it over the new picture: the overlay went
            // empty with nothing to show for it.
            if (closeTimer) { clearTimeout(closeTimer); closeTimer = null; }

            lastFocus = document.activeElement;
            items = galleryFor(trigger);
            buildStrip();
            show(Math.max(0, items.indexOf(trigger)));

            box.hidden = false;
            requestAnimationFrame(function () { box.classList.add("is-open"); });

            // Whatever was parked here belongs to whoever parked it — and only
            // the opening that put the overlay up gets to record it. Opening
            // again over one that is already up would otherwise note down its
            // own "hidden" as the thing to put back, and closing would leave
            // the page underneath unscrollable with nothing on top of it.
            if (!showing) {
                prevOverflow = document.body.style.overflow;
                document.body.style.overflow = "hidden";
                showing = true;
            }

            var closer = document.getElementById("lightbox-close");
            if (closer) closer.focus();
        }

        function close() {
            // Not box.hidden: it is still false through the fade, and a second
            // Escape inside those 210ms would run all of this again.
            if (!showing) return;
            showing = false;
            box.classList.remove("is-open");
            document.body.style.overflow = prevOverflow;

            // Sound stops when the overlay starts fading, not when it finishes:
            // a clip still talking over a page you have closed is a bug.
            var playing = stage.querySelector("video");
            if (playing) playing.pause();

            var finish = function () {
                closeTimer = null;
                box.hidden = true;
                clearStage();
                items = [];
                at = -1;
            };
            if (calm) { finish(); } else { closeTimer = setTimeout(finish, 210); }

            if (lastFocus && lastFocus.focus) lastFocus.focus();
            lastFocus = null;
        }

        /* ---- what a click means ---- */
        document.addEventListener("click", function (e) {
            if (!e.target.closest) return;

            if (e.target.closest("#lightbox-close")) { e.preventDefault(); close(); return; }
            if (e.target.closest("#lightbox-prev")) { e.preventDefault(); step(-1); return; }
            if (e.target.closest("#lightbox-next")) { e.preventDefault(); step(1); return; }
            if (e.target.closest("#lightbox-zoom-in")) { e.preventDefault(); zoomTo(zoom * STEP); return; }
            if (e.target.closest("#lightbox-zoom-out")) { e.preventDefault(); zoomTo(zoom / STEP); return; }
            if (e.target.closest("#lightbox-zoom-level")) { e.preventDefault(); resetZoom(); return; }

            var tile = e.target.closest("[data-go]");
            if (tile && box.contains(tile)) {
                e.preventDefault();
                show(parseInt(tile.getAttribute("data-go"), 10));
                return;
            }

            /* Only the backdrop dismisses. The picture is the reason the overlay
               is open — on a phone the first tap to look closer used to throw it
               away — so a click has to land beside it, not on it. A zoomed
               picture is being dragged, not clicked, so it never dismisses. */
            if ((e.target === box || e.target === stage) && !drag) {
                e.preventDefault();
                close();
                return;
            }
            if (box.contains(e.target)) return;

            var shot = e.target.closest("[data-lightbox]");
            if (!shot) return;
            if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;

            e.preventDefault();
            open(shot);
        });

        /* Double-click is the shortcut every picture viewer has: in to look, out
           to see the whole thing again. */
        stage.addEventListener("dblclick", function (e) {
            if (!media || media.tagName !== "IMG") return;
            e.preventDefault();
            if (zoom > MIN) { resetZoom(); return; }
            var o = originFrom(e);
            zoomTo(2.5, o[0], o[1]);
        });

        /* The page behind cannot scroll while this is open, so the wheel has
           nothing else to mean here. */
        stage.addEventListener("wheel", function (e) {
            if (!media || media.tagName !== "IMG") return;
            e.preventDefault();
            var o = originFrom(e);
            zoomTo(zoom * (e.deltaY < 0 ? STEP : 1 / STEP), o[0], o[1]);
        }, { passive: false });

        /* ---- drag to pan, once there is more picture than window ---- */
        stage.addEventListener("pointerdown", function (e) {
            if (!media || media.tagName !== "IMG" || zoom <= MIN || e.button !== 0) return;
            e.preventDefault();
            drag = { x: e.clientX, y: e.clientY, px: panX, py: panY };
            media.classList.add("is-dragging");
            if (stage.setPointerCapture) stage.setPointerCapture(e.pointerId);
        });

        stage.addEventListener("pointermove", function (e) {
            if (!drag) return;
            panX = drag.px + (e.clientX - drag.x);
            panY = drag.py + (e.clientY - drag.y);
            clampPan();
            apply();
        });

        function endDrag(e) {
            if (!drag) return;
            if (stage.releasePointerCapture && e.pointerId != null) {
                try { stage.releasePointerCapture(e.pointerId); } catch (err) { /* already gone */ }
            }
            if (media) media.classList.remove("is-dragging");
            // Cleared after this event finishes, so the click it turns into does
            // not read as a click on the backdrop and close the overlay.
            setTimeout(function () { drag = null; }, 0);
        }
        stage.addEventListener("pointerup", endDrag);
        stage.addEventListener("pointercancel", endDrag);

        /* ---- keys ---- */
        document.addEventListener("keydown", function (e) {
            if (box.hidden) return;

            if (e.key === "Escape") { close(); return; }

            if (e.key === "ArrowLeft") { e.preventDefault(); step(-1); return; }
            if (e.key === "ArrowRight") { e.preventDefault(); step(1); return; }

            // Space is play/pause, which is what it is everywhere else a video
            // is on screen. The native controls own it once one is focused.
            if ((e.key === " " || e.key === "Spacebar") && media && media.tagName === "VIDEO") {
                if (document.activeElement === media) return;
                e.preventDefault();
                if (!media.paused) { media.pause(); return; }
                // play() answers with a promise everywhere that matters and with
                // nothing at all in a few places that still do not; a rejection
                // is the browser refusing to start it, which the play button on
                // the controls is the answer to.
                var started = media.play();
                if (started && started.catch) { started.catch(function () { /* press play */ }); }
                return;
            }

            if (e.key === "+" || e.key === "=") { e.preventDefault(); zoomTo(zoom * STEP); return; }
            if (e.key === "-" || e.key === "_") { e.preventDefault(); zoomTo(zoom / STEP); return; }
            if (e.key === "0") { e.preventDefault(); resetZoom(); return; }

            if (e.key !== "Tab") return;

            /* aria-modal="true" is a promise the page cannot keep on its own:
               nothing behind the overlay is inert, so Tab walked straight out of
               a dialog a screen reader had just announced as modal. */
            var focusable = box.querySelectorAll("a[href], button:not([disabled]), video[controls]");
            var reachable = Array.prototype.filter.call(focusable, function (el) {
                return !el.hidden && el.offsetParent !== null;
            });
            if (!reachable.length) return;
            var first = reachable[0];
            var last = reachable[reachable.length - 1];
            var here = document.activeElement;

            if (!box.contains(here)) { e.preventDefault(); first.focus(); return; }
            if (e.shiftKey && here === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && here === last) { e.preventDefault(); first.focus(); }
        });

        /* A window that changed size while something was zoomed in leaves the
           picture panned somewhere it can no longer be. */
        window.addEventListener("resize", function () {
            if (box.hidden) return;
            clampPan();
            apply();
        });
    })();

    /* ---------- a password field can be unmasked ----------
       Every reveal button names the field it uncovers in data-reveal, so one
       handler serves the single field on the sign-in page and the three on
       /account. It used to be a pair of hard-coded ids, which is why there was
       no toggle anywhere else.

       The buttons are rendered hidden and revealed here: with scripting off
       there is nothing behind them, and the field simply stays masked — which
       is what it would have done anyway. */
    (function () {
        var buttons = document.querySelectorAll("[data-reveal]");
        if (!buttons.length) return;

        Array.prototype.forEach.call(buttons, function (button) {
            if (document.getElementById(button.getAttribute("data-reveal"))) {
                button.hidden = false;          // only offered when it can work
            }
        });

        document.addEventListener("click", function (e) {
            var button = e.target.closest && e.target.closest("[data-reveal]");
            if (!button) return;
            var field = document.getElementById(button.getAttribute("data-reveal"));
            if (!field) return;

            var shown = field.type === "text";
            field.type = shown ? "password" : "text";
            var use = button.querySelector("use");
            if (use) use.setAttribute("href", shown ? "#i-eye" : "#i-eye-off");
            // The button is icon-only and its meaning just flipped, so both the
            // tooltip and the accessible name flip with it.
            button.title = shown ? "Show password" : "Hide password";
            button.setAttribute("aria-label", button.title);
            button.setAttribute("aria-pressed", shown ? "false" : "true");
            field.focus();
        });
    })();

    /* ---------- a toast, for things that happen without a page load ---------- */
    function flash(text) {
        var existing = document.getElementById("flash-message");
        if (existing) existing.remove();
        var el = document.createElement("div");
        el.className = "toast";
        el.id = "flash-message";
        el.setAttribute("role", "status");
        el.textContent = text;
        document.body.appendChild(el);
        wireToast(el);
    }

    function wireToast(toast) {
        var hide = function () {
            toast.classList.add("is-gone");
            setTimeout(function () { toast.remove(); }, 220);
        };

        // A toast offering Undo has to survive long enough to be used, and must
        // not dismiss itself when the click lands on the Undo button.
        var undo = toast.querySelector("#undo-delete");

        toast.addEventListener("click", function (e) {
            if (undo && e.target.closest && e.target.closest("form")) return;
            hide();
        });

        setTimeout(hide, undo ? 12000 : 5000);
    }

    var toast = document.getElementById("flash-message");
    if (toast) wireToast(toast);

    /* ---------- what a screen reader gets from something that only animates ----------
       Cleared first, because a live region that is handed the same string
       twice announces it once. */
    function say(text) {
        var note = document.getElementById("live-note");
        if (!note) return;
        note.textContent = "";
        setTimeout(function () { note.textContent = text; }, 40);
    }

    /* ---------- copy a bug as Markdown ----------
       Every copy button is a real link to /bugs/{id}/markdown, so this is only
       the shortcut: fetch the same text and put it on the clipboard rather
       than opening it in a tab. A modified click is left alone, because that
       is somebody deliberately asking to see the Markdown.

       The clipboard hands back no receipt of its own, so the button is the
       whole answer — is-copying, is-copied, is-failed are the three states and
       the CSS draws all of them. */
    (function () {
        var HELD = 1600;                        // how long the tick stays up

        document.addEventListener("click", function (e) {
            var btn = e.target.closest && e.target.closest(".copy-md");
            if (!btn) return;
            if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;

            e.preventDefault();
            if (btn.dataset.copyBusy === "1") return;

            // A second copy is allowed to interrupt the first one's tick,
            // otherwise the button looks like it ignored the click.
            if (btn.copyTimer) { clearTimeout(btn.copyTimer); btn.copyTimer = null; }
            btn.classList.remove("is-copied", "is-failed");
            btn.classList.add("is-copying");
            btn.dataset.copyBusy = "1";

            var name = btn.getAttribute("data-copy-md") || "the bug";

            grab(btn.href).then(function () {
                settle(btn, "is-copied");
                say("Copied " + name + " as Markdown.");
            }).catch(function () {
                settle(btn, "is-failed");
                say("Could not copy " + name + ".");
                flash("Could not copy " + name + " — open this button in a new tab "
                    + "to take its Markdown by hand.");
            });
        });

        function settle(btn, state) {
            btn.classList.remove("is-copying");
            btn.classList.add(state);
            btn.dataset.copyBusy = "0";
            btn.copyTimer = setTimeout(function () {
                btn.classList.remove(state);
                btn.copyTimer = null;
            }, HELD);
        }

        /* Safari will not write the clipboard from a promise callback — by the
           time the fetch answers, the click that authorised it is over. It
           will take the promise itself, though, so where ClipboardItem exists
           the pending text goes straight to the clipboard and the gesture is
           never lost. Everything else waits for the text and writes it. */
        function grab(url) {
            var wanted = fetch(url, {
                credentials: "same-origin",
                headers: { "Accept": "text/plain" }
            }).then(function (res) {
                if (!res.ok) throw new Error(res.status + "");
                return res.text();
            });

            if (window.ClipboardItem && navigator.clipboard && navigator.clipboard.write) {
                try {
                    var item = new ClipboardItem({
                        "text/plain": wanted.then(function (text) {
                            return new Blob([text], { type: "text/plain" });
                        })
                    });
                    return navigator.clipboard.write([item]).catch(function () {
                        return wanted.then(write);
                    });
                } catch (err) { /* older ClipboardItem: fall through */ }
            }
            return wanted.then(write);
        }

        /* navigator.clipboard only exists in a secure context, which localhost
           is and http://192.168… is not — so the office machine reaching this
           over the LAN lands on the textarea. */
        function write(text) {
            if (navigator.clipboard && window.isSecureContext) {
                return navigator.clipboard.writeText(text);
            }
            var pad = document.createElement("textarea");
            pad.value = text;
            pad.setAttribute("readonly", "");
            pad.style.position = "fixed";
            pad.style.top = "-1000px";
            pad.style.opacity = "0";
            document.body.appendChild(pad);
            pad.select();

            var ok = false;
            try { ok = document.execCommand("copy"); } catch (err) { ok = false; }
            pad.remove();
            return ok ? Promise.resolve() : Promise.reject(new Error("clipboard refused"));
        }

        /* The code blocks want the same thing, including the non-secure-context
           fallback above. One implementation: "put this on the clipboard" is
           the same problem wherever it is asked. */
        window.BT = window.BT || {};
        window.BT.copyText = write;
    })();

    /* ---------- destructive forms confirm once, from a data attribute ----------
       The message used to be built into an inline onsubmit, which put a
       free-form file name inside a JS string literal: one apostrophe made the
       handler a syntax error, onsubmit returned undefined, and the form
       submitted with no prompt at all. It is an attribute value now, so the
       browser hands it over as text and it is never parsed as script. */
    document.addEventListener("submit", function (e) {
        var form = e.target;
        if (!form || !form.getAttribute) return;
        var msg = form.getAttribute("data-confirm");
        if (msg && !window.confirm(msg)) e.preventDefault();
    }, true);

    /* ---------- who am I ----------
       The server credits whoever is signed in: the hidden .actor-field inputs
       are left empty on purpose, and BugHistoryService fills in the
       authenticated name. One left over from the old "acting as" box is cleared
       here so a stale browser value can never masquerade as someone. */
    try { localStorage.removeItem("bugtracking.actor"); } catch (e) { /* private mode */ }

    /* ---------- file picker shows what you picked ----------
       Both pickers, not just the detail page's: the create form's input is
       #files and takes several, and binding to #file alone left it mute. */
    (function () {
        function size(bytes) {
            return bytes < 1024 * 1024
                ? Math.round(bytes / 1024) + " KB"
                : Math.round(bytes / (1024 * 1024) * 10) / 10 + " MB";
        }

        // The comment box's clip has no .file-drop around it — its label *is*
        // the button — so what was picked is written into the note beside it.
        (function () {
            var input = document.getElementById("comment-files");
            var note = document.getElementById("comment-file-note");
            if (!input || !note) return;
            input.addEventListener("change", function () {
                var files = input.files;
                if (!files || !files.length) { note.textContent = ""; return; }
                var total = 0;
                for (var i = 0; i < files.length; i++) { total += files[i].size; }
                note.textContent = files.length === 1
                    ? files[0].name + " · " + size(total)
                    : files.length + " files · " + size(total);
            });
        })();

        document.querySelectorAll("#file, #files").forEach(function (input) {
            var drop = input.closest(".file-drop");
            var label = drop ? drop.querySelector(".file-drop-text") : null;
            if (!label) return;
            // The two forms word their prompt differently; keep whichever it was.
            var idle = label.textContent.trim();

            input.addEventListener("change", function () {
                var files = input.files;
                if (!files || !files.length) {
                    label.textContent = idle;
                    if (drop) drop.classList.remove("has-file");
                    return;
                }

                var total = 0;
                for (var i = 0; i < files.length; i++) { total += files[i].size; }
                // One name is worth reading; five are a wall. Count them instead.
                label.textContent = files.length === 1
                    ? files[0].name + " · " + size(total)
                    : files.length + " files selected · " + size(total);
                if (drop) drop.classList.add("has-file");
            });
        });
    })();

    /* ---------- avatar identity: same name always gets the same colour ---------- */
    function hueOf(name) {
        var h = 0;
        for (var i = 0; i < name.length; i++) {
            h = (h * 31 + name.charCodeAt(i)) % 360;
        }
        return h;
    }

    /* slice() counts UTF-16 units, so it cuts an emoji or an astral script in
       half and the avatar renders as mojibake. Step whole code points. */
    function firstOf(word, count) {
        var out = "";
        var i = 0;
        var taken = 0;
        while (i < word.length && taken < count) {
            var code = word.charCodeAt(i);
            var wide = code >= 0xD800 && code <= 0xDBFF && i + 1 < word.length;
            out += word.substr(i, wide ? 2 : 1);
            i += wide ? 2 : 1;
            taken++;
        }
        return out;
    }

    function initialsOf(name) {
        var parts = name.trim().split(/[\s\-_]+/).filter(Boolean);
        if (!parts.length) return "?";
        if (parts.length === 1) return firstOf(parts[0], 2).toUpperCase();
        return (firstOf(parts[0], 1) + firstOf(parts[parts.length - 1], 1)).toUpperCase();
    }

    function paintAvatar(el) {
        var name = el.getAttribute("data-avatar") || "";
        if (!name) return;
        el.style.setProperty("--hue", hueOf(name));
        if (!el.textContent.trim()) el.textContent = initialsOf(name);
    }



    /* ---------- "3 hours ago", with the exact stamp on hover ---------- */
    var UNITS = [
        [60, "second", 1],
        [3600, "minute", 60],
        [86400, "hour", 3600],
        [604800, "day", 86400],
        [2629800, "week", 604800],
        [31557600, "month", 2629800]
    ];

    /* The board renders "01 Jan" and the rail renders a full stamp, so the text
       already on the page is not a reliable source for the exact time. The ISO
       attribute is. */
    function exactStamp(el, then) {
        try {
            return then.toLocaleString(undefined, {
                year: "numeric", month: "short", day: "numeric",
                hour: "2-digit", minute: "2-digit"
            });
        } catch (e) {
            return el.textContent.trim();
        }
    }

    function relativeTime(el) {
        var then = new Date(el.getAttribute("data-time"));
        if (isNaN(then.getTime())) return;

        var exact = exactStamp(el, then);
        if (!el.title) el.title = exact;

        // A clock-skewed stamp from the server is still "now", never "in -3 hours".
        var secs = Math.max(0, (Date.now() - then.getTime()) / 1000);
        var text = "just now";

        if (secs >= 45) {
            text = "";
            for (var i = 0; i < UNITS.length && !text; i++) {
                if (secs >= UNITS[i][0]) continue;
                var n = Math.round(secs / UNITS[i][2]);
                // Rounding up to the next unit's own threshold reads as "60
                // minutes ago"; hand it on rather than say that.
                if (n * UNITS[i][2] >= UNITS[i][0]) continue;
                text = n + " " + UNITS[i][1] + (n === 1 ? "" : "s") + " ago";
            }
            if (!text) {
                var years = Math.round(secs / 31557600);
                text = years + " year" + (years === 1 ? "" : "s") + " ago";
            }
        }

        el.textContent = text;

        /* title is a desktop-only affordance: a phone has no hover, so the exact
           time of every comment and history line was simply gone. Carry it in
           the accessible name too, where a screen reader and a long-press
           inspection can both still reach it. */
        var sr = document.createElement("span");
        sr.className = "sr-only";
        sr.textContent = " (" + exact + ")";
        el.appendChild(sr);
    }

    /* Everything that has to run over freshly rendered markup. Called for the
       page on load, and again for whatever the drawer fetches. */
    function enhance(scope) {
        var at = scope || document;
        at.querySelectorAll("[data-avatar]").forEach(paintAvatar);
        at.querySelectorAll("[data-time]").forEach(relativeTime);
        at.querySelectorAll(".actor-field").forEach(function (f) { f.value = ""; });

        // A select that saves itself. The Save button beside it is .js-off, so
        // with JS disabled the form is still submitted the ordinary way.
        at.querySelectorAll("[data-autosubmit]").forEach(function (sel) {
            if (sel.dataset.wired === "1") return;
            sel.dataset.wired = "1";
            sel.addEventListener("change", function () {
                var form = sel.closest("form");
                if (!form) return;
                if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); }
            });
        });
    }

    enhance(document);

    /* ---------- numbers count up, so a change is something you notice ---------- */
    function countUp(scope) {
        (scope || document).querySelectorAll("[data-count]").forEach(function (el) {
            if (el.dataset.counted === "1") return;
            var target = parseInt(el.getAttribute("data-count"), 10);
            if (isNaN(target)) return;
            el.dataset.counted = "1";
            if (calm || target === 0) { el.textContent = target; return; }

            var duration = 480;
            var start = null;

            function frame(ts) {
                if (start === null) start = ts;
                var p = Math.min(1, (ts - start) / duration);
                var eased = 1 - Math.pow(1 - p, 3);
                el.textContent = Math.round(target * eased);
                if (p < 1) requestAnimationFrame(frame);
            }

            el.textContent = "0";
            requestAnimationFrame(frame);
        });
    }

    countUp(document);

    /* ---------- quick search: Cmd+K / Ctrl+K, over every project ----------
       The board's filter box is still the search: it owns the URL, it works
       with JavaScript off, and the last row here hands the query straight to
       it. This is the shortcut over the top — every project at once, from
       whatever page you are standing on, without leaving it.

       It opens while you are typing, unlike every other shortcut in the file,
       which is the point of the chord: nothing else in the app claims Cmd/Ctrl
       and a key, so there is nothing for it to interrupt. */
    (function () {
        var box = document.getElementById("palette");
        var input = document.getElementById("palette-input");
        var list = document.getElementById("palette-results");
        var said = document.getElementById("palette-status");
        var trigger = document.getElementById("palette-open");
        var hint = document.getElementById("palette-hint");
        if (!box || !input || !list) return;

        // The chord is one key on a Mac and another everywhere else, so the
        // hint has to say which — a "Ctrl K" badge on a Mac is a wrong answer,
        // not a rough one. platform is deprecated but still the only thing that
        // tells the two apart without parsing a user-agent string.
        var mac = /Mac|iPhone|iPad|iPod/.test(navigator.platform || navigator.userAgent || "");
        var chord = mac ? "⌘K" : "Ctrl+K";
        if (hint) hint.textContent = mac ? "⌘ K" : "Ctrl K";
        if (trigger) {
            trigger.title = "Search bugs (" + chord + ")";
            trigger.setAttribute("aria-label", trigger.title);
        }

        var lastFocus = null;
        var timer = null;
        var closeTimer = null;
        var prevOverflow = "";
        var run = 0;              // the answer to a keystroke you have moved on from
        var rows = [];
        var at = -1;

        function open() {
            if (!box.hidden) { input.focus(); input.select(); return; }
            // A close still fading owns the timer that hides the box.
            if (closeTimer) { clearTimeout(closeTimer); closeTimer = null; }

            lastFocus = document.activeElement;
            // Whatever was open in the bar is behind the overlay now, and Escape
            // belongs to the palette from here on.
            document.querySelectorAll("details.fmenu[open], details.pop-wrap[open]")
                .forEach(function (menu) { menu.open = false; });

            box.hidden = false;
            requestAnimationFrame(function () { box.classList.add("is-open"); });

            // Whatever was parked here belongs to whoever parked it.
            prevOverflow = document.body.style.overflow;
            document.body.style.overflow = "hidden";

            input.focus();
            // The last search is still in the field and selected: refine it by
            // pressing an arrow, replace it by typing. Either without deciding.
            input.select();
            search(input.value);
        }

        function close() {
            if (box.hidden) return;
            clearTimeout(timer);
            run++;                                  // and drop any answer still in flight
            box.classList.remove("is-open");
            document.body.style.overflow = prevOverflow;

            var finish = function () { closeTimer = null; box.hidden = true; };
            if (calm) { finish(); } else { closeTimer = setTimeout(finish, 210); }

            if (lastFocus && lastFocus.focus) lastFocus.focus();
            lastFocus = null;
        }

        /* ---- asking ---- */

        function search(q) {
            clearTimeout(timer);
            var mine = ++run;
            // No wait on the empty query: that one is the list of recent bugs
            // the palette opens on, and a pause there reads as a broken box.
            timer = setTimeout(function () {
                fetch("/bugs/search?limit=8&q=" + encodeURIComponent(q), {
                    credentials: "same-origin",
                    headers: { "Accept": "application/json" }
                }).then(function (res) {
                    if (!res.ok) throw new Error(res.status + "");
                    return res.json();
                }).then(function (data) {
                    if (mine !== run) return;       // a later keystroke already won
                    render(data);
                }).catch(function () {
                    if (mine !== run) return;
                    // A signed-out session answers the login page, not an error,
                    // so "no results" would be a lie. Say so, and offer the way
                    // the search works without any of this.
                    failed(q);
                });
            }, q === "" ? 0 : 160);
        }

        /* ---- drawing ---- */

        function el(tag, cls, text) {
            var node = document.createElement(tag);
            if (cls) node.className = cls;
            // textContent throughout: a bug title is somebody's typing, and it
            // is going into the page verbatim.
            if (text !== undefined && text !== null) node.textContent = text;
            return node;
        }

        function icon(id) {
            var svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
            svg.setAttribute("class", "i");
            svg.setAttribute("aria-hidden", "true");
            var use = document.createElementNS("http://www.w3.org/2000/svg", "use");
            use.setAttribute("href", "#" + id);
            svg.appendChild(use);
            return svg;
        }

        function sep() {
            var dot = el("span", "qs-sep", "·");
            dot.setAttribute("aria-hidden", "true");
            return dot;
        }

        function meter(hit) {
            var bar = el("span", "meter" + (hit.severity ? " sev-" + hit.severity : ""));
            bar.setAttribute("aria-hidden", "true");
            for (var i = 0; i < 4; i++) { bar.appendChild(el("i")); }
            return bar;
        }

        function row(hit, index) {
            var a = el("a", "qs-row");
            a.href = "/bugs/" + hit.id;
            a.id = "qs-opt-" + index;
            a.setAttribute("role", "option");
            a.setAttribute("aria-selected", "false");
            a.tabIndex = -1;                        // the input keeps focus; arrows move here

            a.appendChild(meter(hit));

            var main = el("span", "qs-main");
            main.appendChild(el("span", "qs-title", hit.title));

            var meta = el("span", "qs-meta");
            // The meter is decorative, so the severity has to be said somewhere.
            if (hit.severityLabel) {
                meta.appendChild(el("span", "sr-only", hit.severityLabel + " severity"));
            }
            meta.appendChild(el("span", "qs-id num", "BUG-" + hit.id));
            if (hit.project) { meta.appendChild(sep()); meta.appendChild(el("span", null, hit.project)); }

            if (hit.assignees && hit.assignees.length) {
                meta.appendChild(sep());
                var faces = el("span", "qs-faces");
                for (var i = 0; i < hit.assignees.length && i < 3; i++) {
                    var face = el("span", "avatar avatar-sm");
                    face.setAttribute("data-avatar", hit.assignees[i]);
                    face.setAttribute("aria-hidden", "true");
                    faces.appendChild(face);
                }
                faces.appendChild(el("span", "sr-only", "Assigned to " + hit.assignees.join(", ")));
                meta.appendChild(faces);
            }

            if (hit.createdAt) {
                meta.appendChild(sep());
                // enhance() rewrites this to "3 hours ago". The day is what is
                // left if the browser will not parse the stamp — better than the
                // ISO string sitting there in full.
                var when = el("span", "when", String(hit.createdAt).slice(0, 10));
                when.setAttribute("data-time", hit.createdAt);
                meta.appendChild(when);
            }

            main.appendChild(meta);
            a.appendChild(main);

            var right = el("span", "qs-right");
            if (hit.status) {
                var badge = el("span", "badge", hit.status);
                // Same one-token indirection the templates use: the bug stores a
                // key, the colour belongs to the column that key names.
                badge.style.setProperty("--c", hit.statusToken || "var(--muted)");
                right.appendChild(badge);
            }
            var go = el("kbd", "qs-go", "↵");
            go.setAttribute("aria-hidden", "true");    // "return" read out on every row
            right.appendChild(go);
            a.appendChild(right);

            return a;
        }

        /* The way out to the real search — the board, with the query in the URL,
           where it can be filtered further, sorted, bookmarked and shared. */
        function moreRow(query, total, index) {
            var a = el("a", "qs-row qs-more");
            a.href = "/bugs?keyword=" + encodeURIComponent(query);
            a.id = "qs-opt-" + index;
            a.setAttribute("role", "option");
            a.setAttribute("aria-selected", "false");
            a.tabIndex = -1;
            a.appendChild(icon("i-list"));
            var label = el("span");
            label.appendChild(document.createTextNode("See all "));
            label.appendChild(el("b", null, total));
            label.appendChild(document.createTextNode(total === 1 ? " match on the board" : " matches on the board"));
            a.appendChild(label);
            return a;
        }

        function render(data) {
            var hits = data.hits || [];
            var query = data.query || "";
            list.innerHTML = "";
            rows = [];

            if (!hits.length) {
                var none = el("p", "qs-empty");
                none.setAttribute("role", "presentation");
                if (query) {
                    none.appendChild(document.createTextNode("Nothing matches "));
                    none.appendChild(el("b", null, "“" + query + "”"));
                    none.appendChild(document.createTextNode(". Titles, descriptions, a project, "
                        + "a person's name and a bug id are all searched."));
                } else {
                    none.textContent = "No bugs yet.";
                }
                list.appendChild(none);
                say(query ? "No bugs match " + query : "No bugs yet.");
                mark();
                return;
            }

            var note = el("div", "qs-note", query
                ? (data.total === 1 ? "1 match" : data.total + " matches")
                : "Recent");
            note.setAttribute("role", "presentation");
            list.appendChild(note);

            for (var i = 0; i < hits.length; i++) {
                var node = row(hits[i], rows.length);
                list.appendChild(node);
                rows.push(node);
            }

            // Only where it leads somewhere the palette is not already showing.
            if (query && data.total > hits.length) {
                var more = moreRow(query, data.total, rows.length);
                list.appendChild(more);
                rows.push(more);
            }

            enhance(list);                          // faces and "3 hours ago"
            at = 0;
            mark();
            say(hits.length === 1 ? "1 bug listed. Press Enter to open it."
                                  : hits.length + " bugs listed. Use the arrow keys.");
        }

        function failed(query) {
            list.innerHTML = "";
            rows = [];
            at = -1;
            var note = el("p", "qs-empty", "The search could not be reached. ");
            note.setAttribute("role", "presentation");
            var link = el("a", "bug-link", "Search the board instead");
            link.href = "/bugs?keyword=" + encodeURIComponent(query);
            note.appendChild(link);
            list.appendChild(note);
            input.removeAttribute("aria-activedescendant");
            say("The search could not be reached.");
        }

        function say(text) { if (said) said.textContent = text; }

        /* ---- moving ---- */

        function mark() {
            for (var i = 0; i < rows.length; i++) {
                var on = i === at;
                rows[i].classList.toggle("is-active", on);
                rows[i].setAttribute("aria-selected", on ? "true" : "false");
            }
            var here = rows[at];
            if (here) {
                input.setAttribute("aria-activedescendant", here.id);
                if (here.scrollIntoView) here.scrollIntoView({ block: "nearest" });
            } else {
                input.removeAttribute("aria-activedescendant");
            }
        }

        function move(step) {
            if (!rows.length) return;
            at = (at + step + rows.length) % rows.length;
            mark();
        }

        /* Filing happens in the drawer, so it stays in the drawer. These forms
           are the documents page's own — they answer with a redirect to it —
           and letting the browser follow that took you off whatever you were
           reading to a page you did not ask for. Posted by fetch instead, and
           the panel is redrawn from the server afterwards so what you see is
           what was actually saved.

           A form marked data-leave is exempt: a new page or sheet redirects to
           its editor, which is where you were going anyway. */
        document.addEventListener("submit", function (e) {
            if (e.defaultPrevented) return;             // a confirm() said no
            var form = e.target;
            if (!form || !form.closest || !form.closest("#docs-drawer")) return;
            if (form.hasAttribute("data-leave")) return;

            e.preventDefault();
            var panel = docsBody ? docsBody.querySelector(".dpanel") : null;
            var project = panel ? panel.getAttribute("data-project") : null;
            var folder = panel ? panel.getAttribute("data-folder") : null;

            docsBody.setAttribute("aria-busy", "true");
            fetch(form.action, {
                method: "POST",
                body: new FormData(form),
                credentials: "same-origin"
            }).then(function (res) {
                if (!res.ok) throw new Error(res.status + "");
                loadDocs(project, folder);
            }).catch(function () {
                docsBody.removeAttribute("aria-busy");
                flash("That could not be saved.");
            });
        });

        /* ---- wiring ---- */

        input.addEventListener("input", function () { search(input.value); });

        // On the box, not on document: the page's own keyboard handler is on
        // document, and Escape here has to close the palette rather than blur
        // the field inside it.
        box.addEventListener("keydown", function (e) {
            if (e.key === "Escape") { e.stopPropagation(); close(); return; }
            if ((e.metaKey || e.ctrlKey) && (e.key === "k" || e.key === "K")) {
                e.preventDefault(); e.stopPropagation(); close(); return;
            }
            if (e.key === "ArrowDown" || (e.key === "Tab" && !e.shiftKey)) {
                // Tab moves the highlight rather than walking out of a dialog
                // that has told a screen reader it is modal. Nothing else in
                // here is focusable, so that is the whole trap.
                e.preventDefault(); move(1); return;
            }
            if (e.key === "ArrowUp" || (e.key === "Tab" && e.shiftKey)) {
                e.preventDefault(); move(-1); return;
            }
            if (e.key === "Enter" && rows[at]) {
                e.preventDefault();
                window.location.href = rows[at].href;
            }
        });

        // Mouse and keyboard fight otherwise: the pointer would hover one row
        // while Enter opened another.
        list.addEventListener("mouseover", function (e) {
            var hit = e.target.closest && e.target.closest(".qs-row");
            if (!hit) return;
            var index = rows.indexOf(hit);
            if (index !== -1 && index !== at) { at = index; mark(); }
        });

        document.addEventListener("keydown", function (e) {
            if (!(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey) return;
            if (e.key !== "k" && e.key !== "K") return;
            // Chrome and Firefox both hand Ctrl+K to the address bar otherwise.
            e.preventDefault();
            open();
        });

        document.addEventListener("click", function (e) {
            if (!e.target.closest) return;

            if (e.target.closest("#palette-open")) {
                // A modified click on a link means "open the board in a tab",
                // and it is a real link — let it be one.
                if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
                e.preventDefault();
                open();
                return;
            }
            if (box.hidden) return;
            if (e.target.closest("#palette-close")) { e.preventDefault(); close(); return; }
            // Only the backdrop dismisses; a click inside the box is aimed at
            // something in it.
            if (e.target === box) close();
        });
    })();

    /* ---------- drawers: a panel beside the page ----------
       Two things on the board are looked up rather than navigated to — who is
       on this project, and what is filed against it. Both open here instead of
       taking the page away.

       Every trigger is a real link to the full page that answers the same
       question, and this only intercepts the plain click: with JavaScript off,
       or on a modified click, the link is simply a link. Nothing below is the
       only way to reach anything. */
    (function () {
        var OPEN_KEY = "bugtracking.drawer";      // survives the redirect a form causes
        var open = null;
        var lastFocus = null;
        var prevOverflow = "";

        function remember(id) {
            try {
                if (id) { sessionStorage.setItem(OPEN_KEY, id); }
                else { sessionStorage.removeItem(OPEN_KEY); }
            } catch (e) { /* private mode */ }
        }

        /* .drawer is inset:0 and only [hidden] takes it out of the way, so the
           box has to end up hidden however it was closed. The timer lives on
           the element rather than in one shared variable: with one variable,
           opening a second drawer cancelled the first one's timer and left an
           invisible full-screen layer swallowing every click. */
        function park(box, now) {
            if (box.hideTimer) { clearTimeout(box.hideTimer); box.hideTimer = null; }
            if (now || calm) { box.hidden = true; return; }
            box.hideTimer = setTimeout(function () { box.hideTimer = null; box.hidden = true; }, 210);
        }

        function show(box) {
            if (!box || open === box) return;
            var swapping = open !== null;
            if (swapping) { var prev = open; open = null; prev.classList.remove("is-open"); park(prev, true); }
            if (box.hideTimer) { clearTimeout(box.hideTimer); box.hideTimer = null; }

            if (!swapping) {
                lastFocus = document.activeElement;
                // Whatever the page parked here belongs to whoever parked it.
                prevOverflow = document.body.style.overflow;
                document.body.style.overflow = "hidden";
            }

            // Whatever was open in a bar is behind the drawer now.
            document.querySelectorAll("details.fmenu[open], details.pop-wrap[open]")
                .forEach(function (menu) { menu.open = false; });

            open = box;
            box.hidden = false;
            requestAnimationFrame(function () { box.classList.add("is-open"); });

            // The close button, so Tab starts inside the panel rather than back
            // at the top of the page behind it. The scrim carries the same
            // attribute and is a bare <div>, so ask for the button by name.
            var first = box.querySelector("button[data-drawer-close]");
            if (first && first.focus) first.focus();
        }

        function hide() {
            if (!open) return;
            var box = open;
            open = null;
            box.classList.remove("is-open");
            document.body.style.overflow = prevOverflow;
            remember(null);
            park(box, false);

            if (lastFocus && lastFocus.focus) lastFocus.focus();
            lastFocus = null;
        }

        /* ---- the panels, fetched rather than rendered into every page ---- */

        /* Both drawers show a fragment of a page that already exists. Fetching
           it keeps the CSRF token, the form and the escaping in Thymeleaf,
           where every other form in this app is rendered — and keeps a panel
           nobody has opened off the board's own query count. */
        function fill(body, url, after) {
            if (!body) return;
            body.setAttribute("aria-busy", "true");

            fetch(url, {
                credentials: "same-origin",
                headers: { "Accept": "text/html" }
            }).then(function (res) {
                if (!res.ok) throw new Error(res.status + "");
                return res.text();
            }).then(function (html) {
                body.innerHTML = html;
                body.removeAttribute("aria-busy");
                enhance(body);                     // faces, and "3 hours ago"
                if (after) after(body);
                body.scrollTop = 0;
            }).catch(function () {
                // A signed-out session answers the login page, not an error, so
                // "empty" would be a lie. Say so, and leave the way out.
                body.removeAttribute("aria-busy");
                body.textContent = "";
                var note = document.createElement("p");
                note.className = "dpanel-empty";
                note.textContent = "That could not be loaded. Open the full page instead.";
                body.appendChild(note);
            });
        }

        /* ---- the team ---- */

        var teamBox = document.getElementById("team-drawer");
        var teamBody = document.getElementById("team-drawer-body");
        var teamLink = document.getElementById("settings-link");
        var teamUrl = teamLink ? teamLink.pathname.replace(/\/settings$/, "/team") + "/panel"
                               : "/team/panel";

        /* Where a form in the drawer comes back to: here, filters and all. Set
           from the address bar rather than rendered into the fragment, so no
           value from a request is ever echoed back into a form field. */
        function here() {
            return window.location.pathname + window.location.search;
        }

        /* The panel is fetched in one of two states: the list, or the same list
           as tick boxes with the rest of the company under it. Edit and Cancel
           are the same fetch with a different flag rather than a class toggle,
           so what is on screen is always what the server would render. */
        function loadTeam(project, edit) {
            var query = [];
            if (project) query.push("project=" + encodeURIComponent(project));
            if (edit) query.push("edit=true");

            fill(teamBody, teamUrl + (query.length ? "?" + query.join("&") : ""), function (body) {
                // Where a form in here comes back to. Set from the address bar
                // rather than rendered into the fragment, so no value from a
                // request is ever echoed back into a form field.
                body.querySelectorAll("input[name=back]").forEach(function (field) {
                    field.value = here();
                });
            });
        }

        /** The project the panel on screen is showing, if any. */
        function teamProject() {
            var panel = teamBody ? teamBody.querySelector("[data-project]") : null;
            return panel ? panel.getAttribute("data-project") : null;
        }

        /* ---- the documents panel, fetched a folder at a time ---- */

        var docsBox = document.getElementById("docs-drawer");
        var docsBody = document.getElementById("docs-drawer-body");
        var docsLink = document.getElementById("docs-link");
        // The navbar link is where the route lives, context path and all.
        var panelUrl = docsLink ? docsLink.pathname + "/panel" : "/documents/panel";

        function loadDocs(project, folder) {
            var query = [];
            if (project) query.push("project=" + encodeURIComponent(project));
            if (folder) query.push("folder=" + encodeURIComponent(folder));

            fill(docsBody, panelUrl + (query.length ? "?" + query.join("&") : ""));
        }

        /* ---- wiring ---- */

        document.addEventListener("click", function (e) {
            if (!e.target.closest) return;
            // A modified click on a link means "open it over there", and every
            // trigger here is a real link. Let it be one.
            var plain = !(e.metaKey || e.ctrlKey || e.shiftKey || e.altKey);

            if (e.target.closest("[data-drawer-close]")) { e.preventDefault(); hide(); return; }

            var team = e.target.closest("#settings-link");
            if (team && plain && teamBox) {
                e.preventDefault();
                show(teamBox);
                loadTeam(team.search ? new URLSearchParams(team.search).get("project") : null, false);
                return;
            }

            if (e.target.closest("#team-edit")) { loadTeam(teamProject(), true); return; }
            if (e.target.closest("#team-cancel")) { loadTeam(teamProject(), false); return; }

            var docs = e.target.closest("#docs-link");
            if (docs && plain && docsBox) {
                e.preventDefault();
                show(docsBox);
                loadDocs(docs.search ? new URLSearchParams(docs.search).get("project") : null, null);
                return;
            }

            // A folder inside the panel reloads the panel; everything else in
            // there is a link out to where that thing actually opens.
            var row = e.target.closest("#docs-drawer [data-folder]");
            if (row && plain) {
                e.preventDefault();
                var panel = docsBody.querySelector(".dpanel");
                loadDocs(panel ? panel.getAttribute("data-project") : null,
                         row.getAttribute("data-folder"));
                return;
            }

            // The scrim is the rest of the drawer; a click on it is a click away.
            if (open && e.target === open) hide();
        });

        document.addEventListener("keydown", function (e) {
            if (!open) return;

            if (e.key === "Escape") {
                // Ahead of the page's own Escape, which only blurs a field.
                e.stopPropagation();
                hide();
                return;
            }

            // The panel has told a screen reader it is modal, so Tab has to
            // behave like it: without this it walks straight out into the page
            // behind, which is inert to the eye and still fully focusable.
            if (e.key !== "Tab") return;
            var stops = open.querySelectorAll(
                "a[href], button, input:not([type=hidden]), select, textarea, summary, [tabindex]:not([tabindex='-1'])");
            var live = Array.prototype.filter.call(stops, function (el) {
                return !el.disabled && el.offsetParent !== null;
            });
            if (!live.length) return;
            var first = live[0];
            var last = live[live.length - 1];
            if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        }, true);

        // A form in the team drawer posts and the server redirects back to this
        // board. Without this the drawer you were working in shuts on the way.
        // Set on the submit below rather than when the drawer opens: opening it
        // and then walking off to somebody's page would otherwise pop it back
        // open the next time a board rendered.
        try {
            var was = sessionStorage.getItem(OPEN_KEY);
            if (was === "team-drawer" && teamBox) {
                // Cleared either way: reopening it is a one-off, not a mode.
                remember(null);
                show(teamBox);
                loadTeam(null, false);
            } else if (was) {
                remember(null);
            }
        } catch (e) { /* private mode */ }

        // The drawer is a shortcut to the same page, so a form inside it is
        // still going there and back — hold the flag over the round trip.
        document.addEventListener("submit", function (e) {
            var form = e.target;
            if (form && form.closest && form.closest("#team-drawer")) remember("team-drawer");
        });
    })();

    /* ---------- keyboard: the bit developers actually want ---------- */
    document.addEventListener("keydown", function (e) {
        var el = document.activeElement;
        // isContentEditable covers anything nested inside an editable region too;
        // without it, "n" typed in one navigated away and took the draft with it.
        var typing = !!el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" ||
            el.tagName === "SELECT" || el.isContentEditable === true);

        if (e.key === "Escape" && typing) { el.blur(); return; }
        // Esc belongs to whatever is open on top; the drawer and the menus
        // handle it themselves.
        //
        // Every shortcut below is a bare key, so every one of them is off while
        // you are typing and off under a modifier. The one shortcut that is
        // neither is Cmd/Ctrl+K — a chord is precisely how quick search opens
        // out of a half-written comment — and it lives in its own block above.
        if (typing || e.ctrlKey || e.metaKey || e.altKey) return;

        if (e.key === "/") {
            var search = document.getElementById("filter-keyword");
            if (search) { e.preventDefault(); search.focus(); search.select(); }
        } else if (e.key === "s") {
            // Was "open the stats drawer". Same key, same job — the numbers are
            // a view now, so it navigates rather than unfolds.
            var stats = document.getElementById("view-stats");
            if (stats) { e.preventDefault(); window.location.href = stats.href; }
        } else if (e.key === "p") {
            var switcher = document.getElementById("switcher-btn");
            if (switcher) { e.preventDefault(); switcher.click(); }
        } else if (e.key === "c") {
            // Only where there is one bug to mean — the board's cards each
            // carry their own button instead.
            var copy = document.getElementById("copy-markdown");
            if (copy) { e.preventDefault(); copy.click(); }
        } else if (e.key === "n") {
            // The board carries the button; other pages fall back to the route,
            // so the shortcut works everywhere rather than only where it renders.
            var raise = document.getElementById("raise-bug-link");
            e.preventDefault();
            window.location.href = raise ? raise.href : "/bugs/new";
        }
    });
})();
