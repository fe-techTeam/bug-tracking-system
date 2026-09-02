package com.bugtracking.controller;

import com.bugtracking.model.Bug;
import com.bugtracking.model.DocType;
import com.bugtracking.model.SupportingDoc;
import com.bugtracking.service.BugService;
import com.bugtracking.service.SupportingDocService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supporting documents: the pages and sheets a tester writes against a bug.
 *
 * <p>Its own controller rather than more methods on {@link BugController} —
 * a document has an editor of its own, and the two have nothing in common
 * beyond the bug they hang off.
 *
 * <p>Every route works with JavaScript switched off. The editor saves through
 * an ordinary form post; {@code /autosave} is the same save reached by fetch,
 * and the row and column buttons post an {@code op} that the service applies
 * server-side. What scripting buys you is not having to press Save.
 */
@Controller
@RequestMapping("/bugs/{bugId}/docs")
public class SupportingDocController {

    private final BugService bugs;
    private final SupportingDocService docs;

    public SupportingDocController(BugService bugs, SupportingDocService docs) {
        this.bugs = bugs;
        this.docs = docs;
    }

    /** Starts a blank page or a blank sheet and drops you straight into it. */
    @PostMapping
    public String create(@PathVariable Long bugId,
                         @RequestParam DocType type,
                         @RequestParam(required = false) String title,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        bugs.findById(bugId);                   // 404 before anything is written
        SupportingDoc doc = docs.create(bugId, type, title, actor);
        flash.addFlashAttribute("message",
                "New " + type.getLabel().toLowerCase() + " added to bug #" + bugId + ".");
        return "redirect:/bugs/" + bugId + "/docs/" + doc.getId();
    }

    @GetMapping("/{docId}")
    public String edit(@PathVariable Long bugId, @PathVariable Long docId, Model model) {
        Bug bug = bugs.findById(bugId);
        SupportingDoc doc = docs.find(bugId, docId);

        model.addAttribute("bug", bug);
        model.addAttribute("doc", doc);
        model.addAttribute("selectedProject", bug.getProject());
        // The other documents on this bug, so moving between them is one hop.
        model.addAttribute("siblings", docs.forBug(bugId).stream()
                .filter(other -> !other.getId().equals(docId))
                .toList());

        if (doc.isSheet()) {
            SupportingDocService.Sheet sheet = docs.sheet(doc);
            model.addAttribute("sheetRows", sheet.rows());
            model.addAttribute("sheetCols", sheet.cols());
            model.addAttribute("colLabels", SupportingDocService.columnLabels(sheet.cols()));
        }
        return "bugs/doc";
    }

    /**
     * Save. A page posts its Markdown as {@code content}; a sheet posts one
     * {@code cell} per box, which is simply what a grid of inputs sends, plus
     * an {@code op} when a row or column button was the thing pressed.
     */
    @PostMapping("/{docId}")
    public String save(@PathVariable Long bugId,
                       @PathVariable Long docId,
                       @RequestParam(required = false) String title,
                       @RequestParam(required = false) String content,
                       @RequestParam(name = "cell", required = false) List<String> cells,
                       @RequestParam(required = false) Integer cols,
                       @RequestParam(required = false) String op,
                       @RequestParam(required = false) String actor,
                       RedirectAttributes flash) {
        SupportingDoc saved = apply(bugId, docId, title, content, cells, cols, op, actor);
        flash.addFlashAttribute("message", "Saved " + saved.getTitle() + ".");
        return "redirect:/bugs/" + bugId + "/docs/" + docId;
    }

    /**
     * The same save, answered with JSON instead of a redirect. The editor calls
     * it a beat after you stop typing, which is why it says when it saved —
     * that is what the "Saved just now" line in the toolbar is reading.
     */
    @PostMapping("/{docId}/autosave")
    @ResponseBody
    public Map<String, Object> autosave(@PathVariable Long bugId,
                                        @PathVariable Long docId,
                                        @RequestParam(required = false) String title,
                                        @RequestParam(required = false) String content,
                                        @RequestParam(name = "cell", required = false) List<String> cells,
                                        @RequestParam(required = false) Integer cols,
                                        @RequestParam(required = false) String actor) {
        SupportingDoc saved = apply(bugId, docId, title, content, cells, cols, null, actor);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", saved.getTitle());
        body.put("summary", saved.getSummary() == null ? "" : saved.getSummary());
        body.put("savedAt", saved.getUpdatedAt() == null
                ? LocalDateTime.now().toString() : saved.getUpdatedAt().toString());
        body.put("savedBy", saved.getUpdatedBy() == null ? "" : saved.getUpdatedBy());
        return body;
    }

    /**
     * The one place that decides which kind of save this is.
     *
     * <p>A request carrying no body at all — no cells and no content — renames
     * and nothing more. The save-on-leaving beacon is sent as the page is being
     * torn down, and a truncated one must never be read as "the tester emptied
     * this document".
     */
    private SupportingDoc apply(Long bugId, Long docId, String title, String content,
                                List<String> cells, Integer cols, String op, String actor) {
        SupportingDoc doc = docs.find(bugId, docId);

        if (doc.getType() == DocType.SHEET) {
            // Cells win over content: the grid is the thing on screen, and the
            // JSON is only ever sent when there was no grid to post.
            if (cells != null) {
                return docs.saveSheet(bugId, docId, title, cells, cols, op, actor);
            }
            if (content != null && !content.isBlank()) {
                return docs.saveSheetJson(bugId, docId, title, content, actor);
            }
            return docs.rename(bugId, docId, title, actor);
        }

        return content == null
                ? docs.rename(bugId, docId, title, actor)
                : docs.savePage(bugId, docId, title, content, actor);
    }

    @PostMapping("/{docId}/delete")
    public String delete(@PathVariable Long bugId,
                         @PathVariable Long docId,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        SupportingDoc removed = docs.delete(bugId, docId, actor);
        flash.addFlashAttribute("message", "Deleted " + removed.getTitle() + ".");
        return "redirect:/bugs/" + bugId + "#docs";
    }

    /** A page downloads as Markdown, a sheet as CSV — both open anywhere. */
    @GetMapping("/{docId}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long bugId, @PathVariable Long docId) {
        SupportingDoc doc = docs.find(bugId, docId);
        byte[] body = docs.export(doc);
        String name = UriUtils.encode(SupportingDocService.fileName(doc), StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(doc.getType().getMediaType() + ";charset=UTF-8"))
                .contentLength(body.length)
                .body(body);
    }
}
