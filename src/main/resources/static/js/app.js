/* Bug Tracking — small progressive enhancements.
   Everything here is optional: with JS off the pages still render and work.
   The board falls back to links and an Apply button; only drag-and-drop and
   the collapsing chrome need scripting. */
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

    /* ---------- the sidebar: a rail you widen, not a panel that floats ---------- */
    var SIDEBAR_KEY = "bugtracking.sidebar";

    function sidebarState() {
        return root.getAttribute("data-sidebar") === "expanded" ? "expanded" : "collapsed";
    }

    function setSidebar(state) {
        root.setAttribute("data-sidebar", state);
        store(SIDEBAR_KEY, state);
        var btn = document.getElementById("sidebar-toggle");
        if (btn) {
            btn.title = state === "expanded" ? "Collapse sidebar" : "Expand sidebar";
            btn.setAttribute("aria-label", btn.title);
        }
    }

    setSidebar(sidebarState());

    document.addEventListener("click", function (e) {
        if (!e.target.closest) return;

        if (e.target.closest("#theme-toggle")) {
            var isDark = root.getAttribute("data-theme") === "dark" ||
                (!root.hasAttribute("data-theme") &&
                    window.matchMedia("(prefers-color-scheme: dark)").matches);
            var next = isDark ? "light" : "dark";
            store(THEME_KEY, next);
            applyTheme(next);
            return;
        }

        if (e.target.closest("#sidebar-toggle")) {
            setSidebar(sidebarState() === "expanded" ? "collapsed" : "expanded");
            return;
        }

        // Collapsed, the toggle is out of the way — so the mark opens the rail
        // rather than navigating, which is the thing you actually want there.
        var brand = e.target.closest("#sidebar-brand");
        if (brand && sidebarState() === "collapsed") {
            e.preventDefault();
            setSidebar("expanded");
        }
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
        var menus = Array.prototype.slice.call(document.querySelectorAll("details.fmenu"));
        if (!menus.length) return;

        function closeAll(except) {
            menus.forEach(function (m) { if (m !== except) m.open = false; });
        }

        menus.forEach(function (menu) {
            menu.addEventListener("toggle", function () {
                if (menu.open) closeAll(menu);
            });
        });

        document.addEventListener("click", function (e) {
            var inside = e.target.closest && e.target.closest("details.fmenu");
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

        keyword.addEventListener("input", function () {
            clearTimeout(timer);
            timer = setTimeout(function () {
                try { sessionStorage.setItem(FOCUS_KEY, "1"); } catch (e) { /* private mode */ }
                if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); }
            }, 550);
        });

        // Put the cursor back where it was, so you can keep typing.
        try {
            if (sessionStorage.getItem(FOCUS_KEY)) {
                sessionStorage.removeItem(FOCUS_KEY);
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

        function refreshColumn(body) {
            var col = body.closest(".kcol");
            var count = body.querySelectorAll(".kcard").length;
            var label = col.querySelector(".kcol-count");
            if (label) label.textContent = count;
            var empty = body.querySelector(".kcol-empty");
            if (empty) empty.hidden = count > 0;
        }

        /* The topbar summary is derived from the columns, so a drag keeps it
           honest instead of leaving a number that no longer matches. */
        function refreshSummary() {
            var openStatuses = ["OPEN", "REOPENED", "ASSIGNED", "IN_PROGRESS"];
            var open = 0;
            kanban.querySelectorAll(".kcol-body").forEach(function (body) {
                if (openStatuses.indexOf(body.getAttribute("data-status")) !== -1) {
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
                if (!dragged || body === from) return;

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
        toast.addEventListener("click", hide);
        setTimeout(hide, 5000);
    }

    var toast = document.getElementById("flash-message");
    if (toast) wireToast(toast);

    /* ---------- who am I ----------
       The server credits whoever is signed in: the hidden .actor-field inputs
       are left empty on purpose, and BugHistoryService fills in the
       authenticated name. One left over from the old "acting as" box is cleared
       here so a stale browser value can never masquerade as someone. */
    try { localStorage.removeItem("bugtracking.actor"); } catch (e) { /* private mode */ }
    document.querySelectorAll(".actor-field").forEach(function (field) {
        field.value = "";
    });

    /* ---------- file picker shows what you picked ---------- */
    var fileInput = document.getElementById("file");
    if (fileInput) {
        fileInput.addEventListener("change", function () {
            var label = document.getElementById("file-name");
            var drop = fileInput.closest(".file-drop");
            if (fileInput.files && fileInput.files.length) {
                var f = fileInput.files[0];
                var kb = f.size < 1024 * 1024
                    ? Math.round(f.size / 1024) + " KB"
                    : Math.round(f.size / (1024 * 1024) * 10) / 10 + " MB";
                if (label) label.textContent = f.name + " · " + kb;
                if (drop) drop.classList.add("has-file");
            } else {
                if (label) label.textContent = "Choose a screenshot, log or document";
                if (drop) drop.classList.remove("has-file");
            }
        });
    }

    /* ---------- avatar identity: same name always gets the same colour ---------- */
    function hueOf(name) {
        var h = 0;
        for (var i = 0; i < name.length; i++) {
            h = (h * 31 + name.charCodeAt(i)) % 360;
        }
        return h;
    }

    function initialsOf(name) {
        var parts = name.trim().split(/[\s\-_]+/).filter(Boolean);
        if (!parts.length) return "?";
        if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
        return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
    }

    function paintAvatar(el) {
        var name = el.getAttribute("data-avatar") || "";
        if (!name) return;
        el.style.setProperty("--hue", hueOf(name));
        if (!el.textContent.trim()) el.textContent = initialsOf(name);
    }

    document.querySelectorAll("[data-avatar]").forEach(paintAvatar);

    /* ---------- "3 hours ago", with the exact stamp on hover ---------- */
    var UNITS = [
        [60, "second", 1],
        [3600, "minute", 60],
        [86400, "hour", 3600],
        [604800, "day", 86400],
        [2629800, "week", 604800],
        [31557600, "month", 2629800]
    ];

    document.querySelectorAll("[data-time]").forEach(function (el) {
        var then = new Date(el.getAttribute("data-time"));
        if (isNaN(then)) return;
        if (!el.title) el.title = el.textContent.trim();

        var secs = Math.max(0, (Date.now() - then.getTime()) / 1000);
        if (secs < 45) { el.textContent = "just now"; return; }

        for (var i = 0; i < UNITS.length; i++) {
            if (secs < UNITS[i][0]) {
                var n = Math.round(secs / UNITS[i][2]);
                el.textContent = n + " " + UNITS[i][1] + (n === 1 ? "" : "s") + " ago";
                return;
            }
        }
        var years = Math.round(secs / 31557600);
        el.textContent = years + " year" + (years === 1 ? "" : "s") + " ago";
    });

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

    /* ---------- the raise form previews what you are about to file ---------- */
    var form = document.getElementById("bug-form");
    if (form) {
        var titleIn = document.getElementById("title");
        var sevIn = document.getElementById("severity");
        var projectIn = document.getElementById("project");
        var pvTitle = document.getElementById("preview-title");
        var pvSev = document.getElementById("preview-severity");
        var pvProject = document.getElementById("preview-project");
        var pvMeter = document.getElementById("preview-meter");
        var pvImpact = document.getElementById("preview-impact");
        var priIn = document.getElementById("priority");
        var envIn = document.getElementById("environment");
        var pvPriority = document.getElementById("preview-priority");
        var pvEnvironment = document.getElementById("preview-environment");

        var IMPACT = {
            CRITICAL: "Blocks work and has no workaround. Expect this to jump the queue.",
            HIGH:     "Hurts a core flow. Should be picked up in this cycle.",
            MEDIUM:   "Annoying but survivable. Gets scheduled normally.",
            LOW:      "Cosmetic or rare. Fixed when there is room."
        };

        var setSevClass = function (el, sev) {
            if (!el) return;
            el.className = el.className.replace(/\bsev-[A-Z]+\b/g, "").trim();
            el.classList.add("sev-" + sev);
        };

        var sync = function () {
            if (pvTitle) {
                var t = titleIn && titleIn.value.trim();
                pvTitle.textContent = t || "Untitled bug";
                pvTitle.classList.toggle("preview-empty", !t);
            }
            if (sevIn) {
                var sev = sevIn.value || "MEDIUM";
                if (pvSev) {
                    pvSev.textContent = sevIn.options[sevIn.selectedIndex].text;
                    setSevClass(pvSev, sev);
                }
                setSevClass(pvMeter, sev);
                if (pvImpact) {
                    setSevClass(pvImpact, sev);
                    pvImpact.innerHTML = "<b>" + sevIn.options[sevIn.selectedIndex].text +
                        "</b> — " + (IMPACT[sev] || "");
                }
            }
            if (priIn && pvPriority) {
                var pri = priIn.value || "P3";
                pvPriority.textContent = pri;
                pvPriority.className = pvPriority.className.replace(/\bpri-P\d\b/g, "").trim();
                pvPriority.classList.add("pri-" + pri);
                pvPriority.title = priIn.options[priIn.selectedIndex].text;
            }
            if (envIn && pvEnvironment) {
                var env = envIn.value || "QA";
                pvEnvironment.textContent = envIn.options[envIn.selectedIndex].text;
                pvEnvironment.className = pvEnvironment.className.replace(/\benv-[A-Z]+\b/g, "").trim();
                pvEnvironment.classList.add("env-" + env);
            }
            if (pvProject && projectIn) {
                var name = projectIn.value;
                pvProject.setAttribute("data-avatar", name);
                pvProject.textContent = "";
                if (name) {
                    paintAvatar(pvProject);
                    pvProject.hidden = false;
                    var label = document.getElementById("preview-project-name");
                    if (label) label.textContent = name;
                } else {
                    pvProject.hidden = true;
                    var lbl = document.getElementById("preview-project-name");
                    if (lbl) lbl.textContent = "No project picked yet";
                }
            }
        };

        ["input", "change"].forEach(function (evt) { form.addEventListener(evt, sync); });
        sync();
    }

    /* ---------- keyboard: the bit developers actually want ---------- */
    document.addEventListener("keydown", function (e) {
        var el = document.activeElement;
        var typing = el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.tagName === "SELECT");

        if (e.key === "Escape" && typing) { el.blur(); return; }
        if (typing || e.ctrlKey || e.metaKey || e.altKey) return;

        if (e.key === "/") {
            var search = document.getElementById("filter-keyword");
            if (search) { e.preventDefault(); search.focus(); search.select(); }
        } else if (e.key === "b") {
            e.preventDefault();
            setSidebar(sidebarState() === "expanded" ? "collapsed" : "expanded");
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
