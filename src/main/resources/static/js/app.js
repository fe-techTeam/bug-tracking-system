/* Bug Tracking — small progressive enhancements.
   Everything here is optional: with JS off the pages still render and work. */
(function () {
    "use strict";

    var calm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    /* ---------- theme: system by default, remembered once you choose ---------- */
    var THEME_KEY = "bugtracking.theme";

    function storedTheme() {
        try { return localStorage.getItem(THEME_KEY); } catch (e) { return null; }
    }

    function applyTheme(theme) {
        if (theme === "light" || theme === "dark") {
            document.documentElement.setAttribute("data-theme", theme);
        } else {
            document.documentElement.removeAttribute("data-theme");
        }
        var btn = document.getElementById("theme-toggle");
        if (!btn) return;
        var dark = theme === "dark" ||
            (!theme && window.matchMedia("(prefers-color-scheme: dark)").matches);
        btn.textContent = dark ? "☀" : "☾";
        btn.title = dark ? "Switch to light" : "Switch to dark";
        btn.setAttribute("aria-label", btn.title);
    }

    applyTheme(storedTheme());

    document.addEventListener("click", function (e) {
        var btn = e.target.closest && e.target.closest("#theme-toggle");
        if (!btn) return;
        var isDark = document.documentElement.getAttribute("data-theme") === "dark" ||
            (!document.documentElement.hasAttribute("data-theme") &&
                window.matchMedia("(prefers-color-scheme: dark)").matches);
        var next = isDark ? "light" : "dark";
        try { localStorage.setItem(THEME_KEY, next); } catch (err) { /* private mode */ }
        applyTheme(next);
    });

    /* ---------- client identity: same name always gets the same colour ---------- */
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
        var name = el.getAttribute("data-client") || "";
        if (!name) return;
        el.style.setProperty("--hue", hueOf(name));
        if (!el.textContent.trim()) el.textContent = initialsOf(name);
    }

    document.querySelectorAll("[data-client]").forEach(paintAvatar);

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
    document.querySelectorAll("[data-count]").forEach(function (el) {
        var target = parseInt(el.getAttribute("data-count"), 10);
        if (isNaN(target)) return;
        if (calm || target === 0) { el.textContent = target; return; }

        var duration = 620;
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

    /* ---------- flash toast ---------- */
    var toast = document.getElementById("flash-message");
    if (toast) {
        var hide = function () {
            toast.classList.add("is-gone");
            setTimeout(function () { toast.remove(); }, 300);
        };
        toast.addEventListener("click", hide);
        setTimeout(hide, 5000);
    }

    /* ---------- the raise form previews what you are about to file ---------- */
    var form = document.getElementById("bug-form");
    if (form) {
        var titleIn = document.getElementById("title");
        var sevIn = document.getElementById("severity");
        var clientIn = document.getElementById("client");
        var pvTitle = document.getElementById("preview-title");
        var pvSev = document.getElementById("preview-severity");
        var pvClient = document.getElementById("preview-client");
        var pvMeter = document.getElementById("preview-meter");
        var pvImpact = document.getElementById("preview-impact");

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
            if (pvClient && clientIn) {
                var name = clientIn.value;
                pvClient.setAttribute("data-client", name);
                pvClient.textContent = "";
                if (name) {
                    paintAvatar(pvClient);
                    pvClient.hidden = false;
                    var label = document.getElementById("preview-client-name");
                    if (label) label.textContent = name;
                } else {
                    pvClient.hidden = true;
                    var lbl = document.getElementById("preview-client-name");
                    if (lbl) lbl.textContent = "No client picked yet";
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
        } else if (e.key === "n") {
            var raise = document.getElementById("raise-bug-link");
            if (raise) { e.preventDefault(); window.location.href = raise.href; }
        }
    });
})();
