/* Bug Tracking — Markdown → HTML.

   Lifted out of the document editor so the bug's supporting pages and a
   project's pages render identically: there is one renderer in this app, and
   fixing a list bug fixes it in both places.

   Small on purpose: headings, lists (nested, and with checkboxes), tables,
   quotes, fenced code, rules and the usual inline marks. Everything is escaped
   before a single tag is added, and a link that is not http(s), mailto or
   in-page keeps its text and loses its href — the preview renders what
   somebody else typed into this document.

   Exposes window.BT.markdown(source) and window.BT.escapeHtml(text). */
window.BT = window.BT || {};

(function () {
    "use strict";

    /* ======================================================================
       Markdown → HTML, for the preview only. Small on purpose: headings,
       lists (nested, and with checkboxes), tables, quotes, fenced code, rules
       and the usual inline marks. Everything is escaped before a single tag is
       added, and a link that is not http(s), mailto or in-page keeps its text
       and loses its href — the preview renders what somebody else typed into
       this document.
       ====================================================================== */

    /** Stands in for a code span while the other marks are applied. */
    var MARK = "\u0001";

    function esc(text) {
        return String(text)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;")
            .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
    }

    function safeHref(href) {
        return /^(https?:\/\/|mailto:|\/|#)/i.test(href.trim());
    }

    function inline(text) {
        var out = esc(text);
        var code = [];

        // Code spans are held back so nothing inside them is read as a mark.
        out = out.replace(/`([^`]+)`/g, function (whole, span) {
            return MARK + (code.push(span) - 1) + MARK;
        });

        out = out
            .replace(/\[([^\]]*)\]\(([^)\s]+)\)/g, function (whole, label, href) {
                return safeHref(href)
                    ? '<a href="' + href + '" target="_blank" rel="noopener">' + (label || href) + "</a>"
                    : whole;
            })
            .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
            .replace(/(^|[^*\w])\*([^*]+)\*/g, "$1<em>$2</em>")
            .replace(/(^|[^_\w])_([^_]+)_/g, "$1<em>$2</em>")
            .replace(/~~([^~]+)~~/g, "<del>$1</del>");

        return out.replace(new RegExp(MARK + "(\\d+)" + MARK, "g"), function (whole, i) {
            return "<code>" + code[i] + "</code>";
        });
    }

    function isBlank(line) { return /^\s*$/.test(line); }
    function bulletOf(line) { return /^(\s*)[-*+]\s+(.*)$/.exec(line); }
    function numberOf(line) { return /^(\s*)\d+[.)]\s+(.*)$/.exec(line); }

    function isSplitRow(line) {
        return line.indexOf("|") >= 0 && /^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$/.test(line);
    }

    function cellsOf(line) {
        return line.replace(/^\s*\|/, "").replace(/\|\s*$/, "").split("|")
            .map(function (cell) { return cell.trim(); });
    }

    function markdown(source) {
        var lines = String(source == null ? "" : source).replace(/\r\n?/g, "\n").split("\n");
        var out = [];
        var i = 0;

        while (i < lines.length) {
            var line = lines[i];

            if (isBlank(line)) { i++; continue; }

            if (/^```/.test(line)) {                                  // fenced code
                var fenced = [];
                i++;
                while (i < lines.length && !/^```/.test(lines[i])) fenced.push(lines[i++]);
                i++;                                                  // the closing fence
                out.push("<pre><code>" + esc(fenced.join("\n")) + "</code></pre>");
                continue;
            }

            var heading = /^(#{1,6})\s+(.*)$/.exec(line);
            if (heading) {
                var level = heading[1].length;
                out.push("<h" + level + ">" + inline(heading[2]) + "</h" + level + ">");
                i++;
                continue;
            }

            if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) { out.push("<hr>"); i++; continue; }

            if (/^\s*>/.test(line)) {                                 // quote
                var quoted = [];
                while (i < lines.length && /^\s*>/.test(lines[i])) {
                    quoted.push(lines[i].replace(/^\s*>\s?/, ""));
                    i++;
                }
                out.push("<blockquote>" + markdown(quoted.join("\n")) + "</blockquote>");
                continue;
            }

            if (line.indexOf("|") >= 0 && i + 1 < lines.length && isSplitRow(lines[i + 1])) {
                out.push(tableBlock(lines, i));
                i = tableEnd(lines, i);
                continue;
            }

            if (bulletOf(line) || numberOf(line)) {
                i = listBlock(lines, i, out);
                continue;
            }

            var paragraph = [];                                        // anything else
            while (i < lines.length && !isBlank(lines[i])
                    && !/^(```|#{1,6}\s|\s*>)/.test(lines[i])
                    && !bulletOf(lines[i]) && !numberOf(lines[i])) {
                paragraph.push(lines[i]);
                i++;
            }
            out.push("<p>" + inline(paragraph.join("\n")).replace(/\n/g, "<br>") + "</p>");
        }
        return out.join("\n");
    }

    function tableEnd(lines, i) {
        var at = i + 2;
        while (at < lines.length && !isBlank(lines[at]) && lines[at].indexOf("|") >= 0) at++;
        return at;
    }

    function tableBlock(lines, i) {
        var out = ["<table><thead><tr>"];
        cellsOf(lines[i]).forEach(function (cell) { out.push("<th>" + inline(cell) + "</th>"); });
        out.push("</tr></thead><tbody>");

        for (var at = i + 2; at < tableEnd(lines, i); at++) {
            out.push("<tr>");
            cellsOf(lines[at]).forEach(function (cell) { out.push("<td>" + inline(cell) + "</td>"); });
            out.push("</tr>");
        }
        out.push("</tbody></table>");
        return out.join("");
    }

    /**
     * One list, however deep. Indentation opens and closes the nested lists;
     * "- [ ]" and "- [x]" become a checkbox rather than a bullet, because a
     * test checklist is most of what gets written in here.
     */
    function listBlock(lines, i, out) {
        var stack = [];

        function openList(indent, ordered) {
            out.push(ordered ? "<ol>" : "<ul>");
            stack.push({ indent: indent, ordered: ordered, item: false });
        }
        function closeItem() {
            var level = stack[stack.length - 1];
            if (level && level.item) {
                out.push("</li>");
                level.item = false;
            }
        }
        function closeList() {
            closeItem();
            out.push(stack.pop().ordered ? "</ol>" : "</ul>");
        }

        while (i < lines.length) {
            var ordered = false;
            var match = bulletOf(lines[i]);
            if (!match) {
                match = numberOf(lines[i]);
                ordered = true;
            }
            if (!match) {
                // A blank line between two items is a gap, not the end of the list.
                var next = lines[i + 1] || "";
                if (isBlank(lines[i]) && (bulletOf(next) || numberOf(next))) { i++; continue; }
                break;
            }

            var indent = match[1].replace(/\t/g, "    ").length;
            var text = match[2];

            while (stack.length && indent < stack[stack.length - 1].indent) {
                closeList();
                closeItem();                    // the <li> the nested list sat in
            }
            if (!stack.length || indent > stack[stack.length - 1].indent) {
                openList(indent, ordered);      // nesting: the parent <li> stays open
            } else {
                closeItem();                    // a sibling at the same depth
            }

            var task = /^\[([ xX])\]\s+(.*)$/.exec(text);
            if (task) {
                out.push('<li class="task"><input type="checkbox" disabled'
                    + (task[1] === " " ? "" : " checked") + "> " + inline(task[2]));
            } else {
                out.push("<li>" + inline(text));
            }
            stack[stack.length - 1].item = true;
            i++;
        }
        while (stack.length) closeList();
        return i;
    }

    window.BT.markdown = markdown;
    window.BT.escapeHtml = esc;
})();
