/* Bug Tracking — a project's documents browser.

   Everything on this page already works without scripting: the New menus are
   <details>, and Upload is a file input with a submit button beside it. What
   this adds is the two things that make a filing screen feel like one —
   dropping files onto the page, and not having to press a second button after
   choosing them — plus putting the caret where you are about to type. */
(function () {
    "use strict";

    var form = document.getElementById("upload-form");
    var input = document.getElementById("doc-files");
    var note = document.getElementById("upload-note");
    var label = document.getElementById("upload-label");
    var hint = document.getElementById("drop-hint");
    var veil = document.getElementById("drop-veil");

    function size(bytes) {
        return bytes < 1024 * 1024
            ? Math.round(bytes / 1024) + " KB"
            : Math.round(bytes / (1024 * 1024) * 10) / 10 + " MB";
    }

    function describe(files) {
        if (!note) return;
        if (!files || !files.length) { note.textContent = ""; return; }
        var total = 0;
        for (var i = 0; i < files.length; i++) { total += files[i].size; }
        // One name is worth reading; five are a wall. Count them instead.
        note.textContent = files.length === 1
            ? files[0].name + " · " + size(total)
            : files.length + " files · " + size(total);
    }

    /* Choosing a file submits straight away, so between the click and the new
       page there is a gap with nothing in it — long enough on a big file to
       read as a dead button, and long enough to pick a second file on top of
       the first. This is what fills it. */
    function busy(on) {
        if (!form) return;
        form.classList.toggle("is-busy", on);
        if (label) label.textContent = on ? "Uploading…" : "Upload";
        if (hint) hint.hidden = on;
    }

    function send(files) {
        describe(files);
        busy(true);
        if (form.requestSubmit) form.requestSubmit(); else form.submit();
    }

    /* ---------- choosing files submits them ----------
       The submit button stays in the markup for the browser that is not
       running this, and is hidden by .js-off. */
    if (form && input) {
        input.addEventListener("change", function () {
            if (!input.files || !input.files.length) return;
            send(input.files);
        });
    }

    /* Back-button restores the page exactly as it was left — mid-upload, with
       a dead Upload button and a stale filename under it. */
    window.addEventListener("pageshow", function (e) {
        if (!e.persisted) return;
        if (input) input.value = "";
        if (note) note.textContent = "";
        busy(false);
    });

    /* ---------- dropping files onto the page ----------
       Into the folder you are looking at, which is the only folder a drop on
       this page could sensibly mean. */
    if (form && input && veil && window.DataTransfer) {
        var depth = 0;                      // dragenter fires for every child

        function carriesFiles(e) {
            var types = e.dataTransfer && e.dataTransfer.types;
            if (!types) return false;
            for (var i = 0; i < types.length; i++) {
                if (types[i] === "Files") return true;
            }
            return false;
        }

        function show(on) {
            if (!on) depth = 0;
            veil.hidden = !on;
            veil.classList.toggle("is-over", on);
        }

        /* Dragging out of the window does not reliably fire a last dragleave
           for every dragenter that was counted, and one missed pair leaves the
           veil covering the page with nothing being dragged. A leave whose
           relatedTarget is not in this document is the drag leaving the window,
           whatever the count says. */
        function left(e) {
            var to = e.relatedTarget;
            return !to || !document.contains(to);
        }

        window.addEventListener("dragenter", function (e) {
            if (!carriesFiles(e)) return;
            depth++;
            show(true);
        });

        window.addEventListener("dragover", function (e) {
            if (!carriesFiles(e)) return;
            e.preventDefault();             // without this the browser opens the file
            e.dataTransfer.dropEffect = "copy";
        });

        window.addEventListener("dragleave", function (e) {
            if (!carriesFiles(e)) return;
            depth = Math.max(0, depth - 1);
            if (!depth || left(e)) show(false);
        });

        // A drag abandoned with Escape ends here and nowhere else.
        window.addEventListener("dragend", function () { show(false); });

        window.addEventListener("drop", function (e) {
            if (!carriesFiles(e)) return;
            e.preventDefault();
            depth = 0;
            show(false);

            var dropped = e.dataTransfer.files;
            if (!dropped || !dropped.length) return;

            // Handing the input the same file list is what makes this an
            // ordinary multipart post rather than a second upload path.
            try {
                input.files = dropped;
            } catch (err) {
                return;                     // a browser that will not allow it: use the picker
            }
            send(dropped);
        });
    }

    /* ---------- opening a form puts the caret in it ---------- */
    document.addEventListener("toggle", function (e) {
        var menu = e.target;
        if (!menu.matches || !menu.matches("details.add-pop") || !menu.open) return;
        var field = menu.querySelector("input[type=text], input[type=url]");
        if (field) field.focus();
    }, true);
})();
