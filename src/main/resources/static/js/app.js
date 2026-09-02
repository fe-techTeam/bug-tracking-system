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

    /* ---------- the stats drawer: folded under the project name ---------- */
    (function () {
        var toggle = document.getElementById("stats-toggle");
        var drawer = document.getElementById("stats-drawer");
        if (!toggle || !drawer) return;

        var STATS_KEY = "bugtracking.stats";

        function setOpen(open) {
            drawer.hidden = !open;
            toggle.setAttribute("aria-expanded", open ? "true" : "false");
            toggle.title = open ? "Hide project stats" : "Show project stats";
            store(STATS_KEY, open ? "open" : "closed");
            if (open) countUp(drawer);
        }

        setOpen(read(STATS_KEY) === "open");
        toggle.addEventListener("click", function () { setOpen(drawer.hidden); });
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
            if (e.target.closest("a, button, input, select, textarea, label")) return;

            // Selecting text inside a card should not navigate away from it.
            var selection = window.getSelection();
            if (selection && String(selection).length > 0) return;

            var href = card.getAttribute("data-href");
            if (href) window.location.href = href;
        });

        function refreshColumn(body) {
            var col = body.closest(".kcol");
            if (!col) return;
            var count = body.querySelectorAll(".kcard").length;
            var label = col.querySelector(".kcol-count");
            if (label) label.textContent = count;
            var empty = body.querySelector(".kcol-empty");
            if (empty) empty.hidden = count > 0;
        }

        /* The topbar summary is derived from the columns, so a drag keeps it
           honest instead of leaving a number that no longer matches. */
        function refreshSummary() {
            /* Which columns count as finished is a per-project setting, so each
               column says so in data-done and this adds up the rest. It used to
               be a list of status names written in here, which is a thing that
               goes stale — and now it would go stale from Settings. */
            var open = 0;
            kanban.querySelectorAll(".kcol-body").forEach(function (body) {
                if (body.getAttribute("data-done") !== "true") {
                    open += body.querySelectorAll(".kcard").length;
                }
            });
            var meta = document.querySelector(".topbar-meta");
            if (!meta) return;
            var cells = meta.querySelectorAll("b");
            if (cells.length > 1) cells[1].textContent = open;
        }

        kanban.addEventListener("dragstart", function (e) {
            var card = e.target.closest(".kcard");
            if (!card) return;
            dragged = card;
            from = card.closest(".kcol-body");
            card.classList.add("is-dragging");
            e.dataTransfer.effectAllowed = "move";
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
                card.classList.add("is-saving");
                refreshColumn(body);
                refreshColumn(origin);
                refreshSummary();

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
                    card.classList.remove("is-saving");
                    refreshColumn(body);
                    refreshColumn(origin);
                    refreshSummary();
                    flash("Could not move BUG-" + id + " — the change was not saved.");
                });
            });
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

        /* ---- writing ---- */
        var box = document.getElementById("comment-text")
            || document.querySelector("textarea[data-mentions]");
        if (!box) return;

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
    })();

    /* ---------- filtering a picker by name ---------- */
    (function () {
        var filter = document.getElementById("assignee-filter");
        var list = document.getElementById("assignee-list");
        var none = document.getElementById("assignee-none");
        if (!filter || !list) return;

        filter.addEventListener("input", function () {
            var q = filter.value.trim().toLowerCase();
            var shown = 0;
            list.querySelectorAll(".pick-opt").forEach(function (opt) {
                var name = (opt.getAttribute("data-name") || "").toLowerCase();
                var hit = q === "" || name.indexOf(q) !== -1;
                opt.hidden = !hit;
                if (hit) shown++;
            });
            if (none) none.hidden = shown > 0;
        });

        // Typing is for finding, not for submitting the form underneath.
        filter.addEventListener("keydown", function (e) {
            if (e.key === "Enter") e.preventDefault();
        });
    })();

    /* ---------- attachments open over the page, not instead of it ---------- */
    (function () {
        var box = document.getElementById("lightbox");
        var stage = document.getElementById("lightbox-stage");
        var name = document.getElementById("lightbox-name");
        var openIn = document.getElementById("lightbox-open");
        if (!box || !stage) return;

        var lastFocus = null;
        var closeTimer = null;
        var prevOverflow = "";

        /* A 404, or an error page served as HTML with a 200, both arrive here.
           Either way the stage would sit empty while the bar names the file
           with confidence; say so instead, and keep a way out of the overlay. */
        function failed(href) {
            stage.innerHTML = "";
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

        function open(href, label) {
            // A close still fading owns a timer that blanks the stage. Reopening
            // inside those 210ms fired it over the new picture: the overlay went
            // empty with nothing to show for it.
            if (closeTimer) { clearTimeout(closeTimer); closeTimer = null; }

            lastFocus = document.activeElement;
            stage.innerHTML = "";

            var img = document.createElement("img");
            img.alt = label || "Attachment";
            img.onerror = function () { failed(href); };
            img.src = href;
            stage.appendChild(img);

            if (name) name.textContent = label || "Attachment";
            if (openIn) openIn.href = href;

            box.hidden = false;
            requestAnimationFrame(function () { box.classList.add("is-open"); });
            // Whatever was parked here belongs to whoever parked it.
            prevOverflow = document.body.style.overflow;
            document.body.style.overflow = "hidden";

            var closer = document.getElementById("lightbox-close");
            if (closer) closer.focus();
        }

        function close() {
            if (box.hidden) return;
            box.classList.remove("is-open");
            document.body.style.overflow = prevOverflow;

            var finish = function () {
                closeTimer = null;
                box.hidden = true;
                stage.innerHTML = "";       // stop the image decoding in the background
            };
            if (calm) { finish(); } else { closeTimer = setTimeout(finish, 210); }

            if (lastFocus && lastFocus.focus) lastFocus.focus();
            lastFocus = null;
        }

        document.addEventListener("click", function (e) {
            if (!e.target.closest) return;

            if (e.target.closest("#lightbox-close")) { e.preventDefault(); close(); return; }
            /* Only the backdrop dismisses. The picture is the reason the overlay
               is open — on a phone the first tap to look closer used to throw it
               away — so a click has to land beside it, not on it. */
            if (e.target === box || e.target === stage) {
                e.preventDefault();
                close();
                return;
            }
            if (box.contains(e.target)) return;

            var shot = e.target.closest("[data-lightbox]");
            if (!shot) return;
            if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;

            e.preventDefault();
            open(shot.href, shot.getAttribute("data-lightbox"));
        });

        document.addEventListener("keydown", function (e) {
            if (box.hidden) return;
            if (e.key === "Escape") { close(); return; }
            if (e.key !== "Tab") return;

            /* aria-modal="true" is a promise the page cannot keep on its own:
               nothing behind the overlay is inert, so Tab walked straight out of
               a dialog a screen reader had just announced as modal. */
            var items = box.querySelectorAll("a[href], button:not([disabled])");
            if (!items.length) return;
            var first = items[0];
            var last = items[items.length - 1];
            var here = document.activeElement;

            if (!box.contains(here)) { e.preventDefault(); first.focus(); return; }
            if (e.shiftKey && here === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && here === last) { e.preventDefault(); first.focus(); }
        });
    })();

    /* ---------- the password field can be unmasked ---------- */
    (function () {
        var toggle = document.getElementById("toggle-password");
        var field = document.getElementById("password");
        if (!toggle || !field) return;

        toggle.hidden = false;                  // only offered when it can work
        toggle.addEventListener("click", function () {
            var shown = field.type === "text";
            field.type = shown ? "password" : "text";
            var use = toggle.querySelector("use");
            if (use) use.setAttribute("href", shown ? "#i-eye" : "#i-eye-off");
            toggle.title = shown ? "Show password" : "Hide password";
            toggle.setAttribute("aria-label", toggle.title);
            field.focus();
        });
    })();

    /* ---------- the sign-in heading cycles through the configured accounts ---------- */
    (function () {
        var heading = document.getElementById("welcome-heading");
        var emailField = document.getElementById("email");
        var passwordField = document.getElementById("password");
        if (!heading || !emailField || !passwordField) return;

        var accounts;
        try { accounts = JSON.parse(heading.getAttribute("data-accounts") || "[]"); }
        catch (e) { return; }
        if (!accounts || !accounts.length) return;

        var note = document.getElementById("login-switch-note");
        var at = -1;

        // Built here, not in the template: with no JS there is nothing to click.
        var button = document.createElement("button");
        button.type = "button";
        button.className = "welcome-switch";
        button.textContent = heading.textContent.trim();
        button.title = accounts.length > 1 ? "Fill in the next sign-in" : "Fill in the sign-in";
        button.setAttribute("aria-label", button.title);
        heading.textContent = "";
        heading.appendChild(button);

        button.addEventListener("click", function () {
            at = (at + 1) % accounts.length;
            var account = accounts[at];
            emailField.value = account.email || "";
            passwordField.value = account.password || "";
            if (!note) return;

            var who = document.createElement("b");
            who.textContent = account.name || account.email || "";
            note.textContent = "Filled in for ";
            note.appendChild(who);
            if (accounts.length > 1) {
                note.appendChild(document.createTextNode(" · " + (at + 1) + " of " + accounts.length));
            }
            note.hidden = false;
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
            var drawer = el.closest(".stats-drawer");
            if (drawer && drawer.hidden) return;          // count when it is shown
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

    // Anything already on screen counts now; a closed drawer counts when opened.
    countUp(document);

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
        if (typing || e.ctrlKey || e.metaKey || e.altKey) return;

        if (e.key === "/") {
            var search = document.getElementById("filter-keyword");
            if (search) { e.preventDefault(); search.focus(); search.select(); }
        } else if (e.key === "s") {
            var stats = document.getElementById("stats-toggle");
            if (stats) { e.preventDefault(); stats.click(); }
        } else if (e.key === "p") {
            var switcher = document.getElementById("switcher-btn");
            if (switcher) { e.preventDefault(); switcher.click(); }
        } else if (e.key === "n") {
            // The board carries the button; other pages fall back to the route,
            // so the shortcut works everywhere rather than only where it renders.
            var raise = document.getElementById("raise-bug-link");
            e.preventDefault();
            window.location.href = raise ? raise.href : "/bugs/new";
        }
    });
})();
