/* Bug Tracking — the spreadsheet formula engine.

   Lifted out of the document editor so the bug's sheets and a project's sheets
   calculate the same way, and given somewhere to grow: comparisons and IF are
   here now, which the grid-bound version could not hold.

   A hand-written parser rather than eval. A formula is stored on a document and
   rendered for whoever opens it next, so nothing typed into a cell is ever
   handed to the JavaScript engine.

   Numbers only, on purpose. Text in a cell is worth nothing to a sum, blank is
   worth nothing to an average, and a formula that quietly coerces "N/A" to 0 is
   worse than one that says #VALUE. Comparisons and IF work in 1 and 0.

   Usage:
       var engine = window.BT.formulaEngine(function (r, c) {
           return rawTextOfCell(r, c);        // or null when there is no cell
       });
       engine.evaluate("=SUM(A1:A9)")   ->  "42"   or  "#DIV/0"
       engine.label(27)                 ->  "AB"
       engine.refOf("AB3")              ->  {r: 2, c: 27}
*/
window.BT = window.BT || {};

(function () {
    "use strict";

    /** A…Z, AA, AB — the scheme the server renders the headings with. */
    function label(index) {
        var out = "";
        var n = index;
        while (n >= 0) {
            out = String.fromCharCode(65 + (n % 26)) + out;
            n = Math.floor(n / 26) - 1;
        }
        return out;
    }

    /** "AB3" → {r: 2, c: 27}, or null when it is not a reference at all. */
    function refOf(name) {
        var parts = /^([A-Za-z]+)(\d+)$/.exec(name);
        if (!parts) return null;
        var c = 0;
        var letters = parts[1].toUpperCase();
        for (var i = 0; i < letters.length; i++) {
            c = c * 26 + (letters.charCodeAt(i) - 64);
        }
        return { r: parseInt(parts[2], 10) - 1, c: c - 1 };
    }

    function sum(values) {
        return values.reduce(function (a, b) { return a + b; }, 0);
    }

    function median(values) {
        if (!values.length) return 0;
        var sorted = values.slice().sort(function (a, b) { return a - b; });
        var mid = Math.floor(sorted.length / 2);
        return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    /* Each function is handed both shapes of its arguments: `flat` is every
       number the arguments produced with ranges expanded, which is what an
       aggregate wants; `args` is one value per argument, which is what IF and
       ROUND want. Writing IF over a range is not a thing, so taking the first
       value of each argument loses nothing real. */
    var FUNCS = {
        SUM:     function (flat) { return sum(flat); },
        AVG:     function (flat) { return flat.length ? sum(flat) / flat.length : 0; },
        AVERAGE: function (flat) { return FUNCS.AVG(flat); },
        MEDIAN:  function (flat) { return median(flat); },
        MIN:     function (flat) { return flat.length ? Math.min.apply(null, flat) : 0; },
        MAX:     function (flat) { return flat.length ? Math.max.apply(null, flat) : 0; },
        COUNT:   function (flat) { return flat.length; },
        COUNTA:  function (flat) { return flat.length; },
        PRODUCT: function (flat) { return flat.reduce(function (a, b) { return a * b; }, 1); },

        ABS:   function (flat, args) { return Math.abs(args[0] || 0); },
        SQRT:  function (flat, args) {
            if ((args[0] || 0) < 0) throw new Error("#NUM");
            return Math.sqrt(args[0] || 0);
        },
        POWER: function (flat, args) { return Math.pow(args[0] || 0, args[1] || 0); },
        ROUND: function (flat, args) {
            var places = args.length > 1 ? Math.round(args[1]) : 0;
            var factor = Math.pow(10, Math.max(-10, Math.min(10, places)));
            return Math.round((args[0] || 0) * factor) / factor;
        },
        IF:  function (flat, args) { return args[0] ? args[1] : (args.length > 2 ? args[2] : 0); },
        AND: function (flat) { return flat.every(function (v) { return !!v; }) ? 1 : 0; },
        OR:  function (flat) { return flat.some(function (v) { return !!v; }) ? 1 : 0; },
        NOT: function (flat, args) { return args[0] ? 0 : 1; }
    };

    /** How a computed number is written into the cell. */
    function show(number) {
        if (typeof number !== "number" || isNaN(number)) return "#VALUE";
        if (!isFinite(number)) return "#NUM";
        return String(Math.round(number * 1e10) / 1e10);
    }

    /**
     * Builds an engine over one grid. `getRaw(r, c)` returns a cell's text as
     * typed — formula and all — or null/undefined where there is no such cell.
     */
    window.BT.formulaEngine = function (getRaw) {

        /** What a cell is worth to a formula: a number, or NaN for text. */
        function valueAt(r, c, seen) {
            var raw = getRaw(r, c);
            if (raw === null || raw === undefined) return 0;
            raw = String(raw).trim();
            if (!raw) return 0;

            if (raw.charAt(0) === "=") {
                var key = r + ":" + c;
                if (seen[key]) throw new Error("#CYCLE");
                seen[key] = true;
                var nested = compute(raw.slice(1), seen);
                delete seen[key];
                return nested;
            }
            // "1,200" and "45%" are how people type numbers into a grid.
            var text = raw.replace(/,/g, "");
            var percent = /%$/.test(text);
            if (percent) text = text.slice(0, -1);
            var number = Number(text.replace(/^[₹$€£]\s*/, ""));
            if (isNaN(number)) return NaN;
            return percent ? number / 100 : number;
        }

        function rangeValues(from, to, seen) {
            var a = refOf(from);
            var b = refOf(to);
            if (!a || !b) throw new Error("#REF");

            var values = [];
            for (var r = Math.min(a.r, b.r); r <= Math.max(a.r, b.r); r++) {
                for (var c = Math.min(a.c, b.c); c <= Math.max(a.c, b.c); c++) {
                    var raw = getRaw(r, c);
                    if (raw === null || raw === undefined || !String(raw).trim()) continue;
                    var value = valueAt(r, c, seen);          // blanks are skipped
                    if (!isNaN(value)) values.push(value);    // so is text
                }
            }
            return values;
        }

        function compute(expression, seen) {
            var text = expression;
            var pos = 0;

            function skip() { while (pos < text.length && text.charAt(pos) === " ") pos++; }
            function peek() { skip(); return text.charAt(pos); }
            function eat(ch) { if (peek() === ch) { pos++; return true; } return false; }
            function eats(word) {
                skip();
                if (text.substr(pos, word.length) === word) { pos += word.length; return true; }
                return false;
            }

            /** One argument's worth of values: a range spreads, anything else is one. */
            function argument() {
                skip();
                var range = /^([A-Za-z]+\d+)\s*:\s*([A-Za-z]+\d+)/.exec(text.slice(pos));
                if (range) {
                    pos += range[0].length;
                    return rangeValues(range[1], range[2], seen);
                }
                var single = expr();
                return isNaN(single) ? [] : [single];
            }

            function primary() {
                skip();
                if (eat("(")) {
                    var inner = expr();
                    if (!eat(")")) throw new Error("#ERR");
                    return inner;
                }
                if (eat("-")) return -primary();
                if (eat("+")) return primary();

                var number = /^\d+(\.\d+)?/.exec(text.slice(pos));
                if (number) {
                    pos += number[0].length;
                    return parseFloat(number[0]);
                }

                var word = /^[A-Za-z]+\d*/.exec(text.slice(pos));
                if (!word) throw new Error("#ERR");
                pos += word[0].length;
                var name = word[0].toUpperCase();

                if (peek() === "(") {                                  // a function call
                    if (!FUNCS[name]) throw new Error("#NAME");
                    eat("(");
                    var args = [];
                    if (peek() !== ")") {
                        do {
                            args.push(argument());
                        } while (eat(","));
                    }
                    if (!eat(")")) throw new Error("#ERR");

                    var flat = [];
                    var positional = args.map(function (values) {
                        flat = flat.concat(values);
                        return values.length ? values[0] : 0;
                    });
                    return FUNCS[name](flat, positional);
                }

                if (name === "TRUE") return 1;
                if (name === "FALSE") return 0;

                var ref = refOf(name);
                if (!ref) throw new Error("#REF");
                return valueAt(ref.r, ref.c, seen);
            }

            function term() {
                var value = primary();
                for (;;) {
                    if (eat("*")) {
                        value *= primary();
                    } else if (eat("/")) {
                        var divisor = primary();
                        if (divisor === 0) throw new Error("#DIV/0");
                        value /= divisor;
                    } else if (eat("^")) {
                        value = Math.pow(value, primary());
                    } else {
                        return value;
                    }
                }
            }

            function arithmetic() {
                var value = term();
                for (;;) {
                    if (eat("+")) value += term();
                    else if (eat("-")) value -= term();
                    else return value;
                }
            }

            /* Comparisons sit below the arithmetic and answer in 1 and 0, which
               is what makes IF(A1>10, …) work without the parser ever having to
               carry a second kind of value. Two-character operators are tried
               first — otherwise "<=" is read as "<" followed by a stray "=". */
            function expr() {
                var value = arithmetic();
                for (;;) {
                    if (eats("<=")) value = value <= arithmetic() ? 1 : 0;
                    else if (eats(">=")) value = value >= arithmetic() ? 1 : 0;
                    else if (eats("<>")) value = value !== arithmetic() ? 1 : 0;
                    else if (eat("<")) value = value < arithmetic() ? 1 : 0;
                    else if (eat(">")) value = value > arithmetic() ? 1 : 0;
                    else if (eat("=")) value = value === arithmetic() ? 1 : 0;
                    else return value;
                }
            }

            var result = expr();
            skip();
            if (pos < text.length) throw new Error("#ERR");
            return result;
        }

        return {
            label: label,
            refOf: refOf,
            show: show,

            /** The formula's answer as text, or null when this is not a formula. */
            evaluate: function (raw) {
                var text = raw === null || raw === undefined ? "" : String(raw).trim();
                if (text.charAt(0) !== "=") return null;
                try {
                    return show(compute(text.slice(1), {}));
                } catch (e) {
                    return e.message && e.message.charAt(0) === "#" ? e.message : "#ERR";
                }
            },

            /** The raw number, for callers that want to do their own formatting. */
            number: function (raw) {
                var text = raw === null || raw === undefined ? "" : String(raw).trim();
                if (text.charAt(0) !== "=") return NaN;
                try {
                    return compute(text.slice(1), {});
                } catch (e) {
                    return NaN;
                }
            }
        };
    };
})();
