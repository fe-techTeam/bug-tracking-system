package com.bugtracking.controller;

import com.bugtracking.model.Project;
import com.bugtracking.model.ProjectResource;
import com.bugtracking.model.ResourceKind;
import com.bugtracking.service.AttachmentService;
import com.bugtracking.service.ProjectDocService;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.SheetCodec;
import com.bugtracking.service.TeamMemberService;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A project's documents area: the folder browser, and the editor it opens.
 *
 * <p>Its own controller rather than more methods on {@link ProjectController} —
 * that one is three forms behind Settings, and this is a filing system with a
 * page of its own.
 *
 * <p>Every route works with JavaScript switched off. New folders, links,
 * uploads and documents are ordinary form posts; the editor saves through a
 * Save button, and {@code /autosave} is the same save reached by fetch. What
 * scripting buys you is not having to press Save, and the formatting toolbar.
 */
@Controller
public class ProjectDocController {

    private final ProjectDocService docs;
    private final ProjectService projects;
    private final TeamMemberService team;

    public ProjectDocController(ProjectDocService docs, ProjectService projects, TeamMemberService team) {
        this.docs = docs;
        this.projects = projects;
        this.team = team;
    }

    /**
     * The navbar's way in. Nothing in the bar knows a project id, so this works
     * one out: whatever the link asked for, else the project you were last
     * working in, else the first there is. Only a database with no projects at
     * all falls through to Settings, which is where you would add one.
     *
     * <p>The session is read here rather than left to the caller so the route
     * is right on its own — typing /documents into the address bar should land
     * where the navbar link would.
     */
    @GetMapping("/documents")
    public String documents(@RequestParam(required = false) String project, HttpSession session) {
        Object remembered = session.getAttribute(GlobalModelAttributes.PROJECT_KEY);
        return projects.findByName(project)
                .or(() -> remembered instanceof String name ? projects.findByName(name) : Optional.empty())
                .or(() -> projects.active().stream().findFirst())
                .map(found -> "redirect:/projects/" + found.getId() + "/docs")
                .orElse("redirect:/settings");
    }

    // ---------------------------------------------------------------- browse

    @GetMapping("/projects/{projectId}/docs")
    public String browse(@PathVariable Long projectId,
                         @RequestParam(required = false) Long folder,
                         @RequestParam(required = false) String q,
                         HttpSession session,
                         Model model) {
        ProjectDocService.Listing listing = docs.browse(projectId, folder);
        Project project = listing.project();

        model.addAttribute("listing", listing);
        model.addAttribute("project", project);
        model.addAttribute("selectedProject", project.getName());

        // Filing in a project is working in it. Remembering it here is what
        // makes Board, and the navbar's own Documents link, come back to the
        // project you were last in rather than the first one alphabetically.
        session.setAttribute(GlobalModelAttributes.PROJECT_KEY, project.getName());
        // The "Recently worked on" strip is gone from this page: the folder it
        // listed is usually the folder already on screen, so it repeated the
        // grid above it. ProjectDocService.recent() is left in place — putting
        // the strip back is this line and the panel that read it.
        model.addAttribute("newKinds", List.of(ResourceKind.PAGE, ResourceKind.SHEET));
        model.addAttribute("folderTargets", docs.moveTargets(projectId, null));

        if (q != null && !q.isBlank()) {
            model.addAttribute("results", docs.search(projectId, q));
            model.addAttribute("keyword", q.trim());
        }
        return "projects/docs";
    }

    // ---------------------------------------------------------------- create

    @PostMapping("/projects/{projectId}/docs/new/folder")
    public String newFolder(@PathVariable Long projectId,
                            @RequestParam(required = false) Long parentId,
                            @RequestParam(required = false) String name,
                            @RequestParam(required = false) String actor,
                            RedirectAttributes flash) {
        ProjectResource folder = docs.createFolder(projectId, parentId, name, actor);
        flash.addFlashAttribute("message", "Created " + folder.getName() + ".");
        return back(projectId, parentId);
    }

    @PostMapping("/projects/{projectId}/docs/new/link")
    public String newLink(@PathVariable Long projectId,
                          @RequestParam(required = false) Long parentId,
                          @RequestParam(required = false) String name,
                          @RequestParam String url,
                          @RequestParam(required = false) String note,
                          @RequestParam(required = false) String actor,
                          RedirectAttributes flash) {
        try {
            ProjectResource link = docs.addLink(projectId, parentId, name, url, note, actor);
            flash.addFlashAttribute("message", "Saved the link to " + link.getName() + ".");
        } catch (ProjectDocService.RejectedException e) {
            // Beside the form it came from, not on an error page: the fix is to
            // retype the address, which needs the form still on screen.
            flash.addFlashAttribute("message", e.getMessage());
        }
        return back(projectId, parentId);
    }

    /**
     * Upload, one or many. Each file is reported on separately so a folder of
     * twelve that contains one .exe still files the other eleven.
     */
    @PostMapping("/projects/{projectId}/docs/new/upload")
    public String upload(@PathVariable Long projectId,
                         @RequestParam(required = false) Long parentId,
                         @RequestParam("files") List<MultipartFile> files,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        int stored = 0;
        List<String> refused = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                docs.upload(projectId, parentId, file, actor);
                stored++;
            } catch (ProjectDocService.RejectedException e) {
                refused.add(e.getMessage());
            }
        }

        String message = stored == 0 ? "" : stored + (stored == 1 ? " file added." : " files added.");
        if (!refused.isEmpty()) {
            message = (message + " " + String.join(" ", refused)).trim();
        }
        flash.addFlashAttribute("message", message.isBlank() ? "Pick a file before uploading." : message);
        return back(projectId, parentId);
    }

    /** Starts a blank page or a blank sheet and drops you straight into it. */
    @PostMapping("/projects/{projectId}/docs/new/doc")
    public String newDocument(@PathVariable Long projectId,
                              @RequestParam(required = false) Long parentId,
                              @RequestParam ResourceKind kind,
                              @RequestParam(required = false) String name,
                              @RequestParam(required = false) String actor,
                              RedirectAttributes flash) {
        ProjectResource doc = docs.createDocument(projectId, parentId, kind, name, actor);
        flash.addFlashAttribute("message",
                "New " + kind.getLabel().toLowerCase() + " added.");
        return "redirect:/projects/" + projectId + "/docs/" + doc.getId();
    }

    // ---------------------------------------------------------------- editor

    @GetMapping("/projects/{projectId}/docs/{id}")
    public String edit(@PathVariable Long projectId, @PathVariable Long id, Model model) {
        ProjectResource doc = docs.find(projectId, id);
        if (!doc.isDocument()) {
            // A file and a link are not editable here; go to where they open.
            return doc.isFile()
                    ? "redirect:/projects/" + projectId + "/docs/" + id + "/file"
                    : "redirect:/projects/" + projectId + "/docs?folder="
                            + (doc.getParentId() == null ? "" : doc.getParentId());
        }
        Project project = docs.project(projectId);

        model.addAttribute("doc", doc);
        model.addAttribute("project", project);
        model.addAttribute("selectedProject", project.getName());
        model.addAttribute("trail", docs.browse(projectId, doc.getParentId()).trail());
        // Drives the "@" autocomplete; the server matches mentions again on save.
        model.addAttribute("people", team.activeNames());

        if (doc.isSheet()) {
            SheetCodec.Sheet sheet = docs.sheet(doc);
            model.addAttribute("sheet", sheet);
            model.addAttribute("colLabels", SheetCodec.columnLabels(sheet.cols()));
        }
        return doc.isSheet() ? "projects/sheet" : "projects/page";
    }

    /**
     * Save. A page posts its Markdown as {@code content}; a sheet posts either
     * the serialised grid as {@code content} — which is what the editor sends,
     * formatting included — or one {@code cell} per box, which is what a form
     * of text inputs sends when there is no editor running.
     */
    @PostMapping("/projects/{projectId}/docs/{id}")
    public String save(@PathVariable Long projectId,
                       @PathVariable Long id,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) String content,
                       @RequestParam(name = "cell", required = false) List<String> cells,
                       @RequestParam(required = false) Integer cols,
                       @RequestParam(required = false) String op,
                       @RequestParam(required = false) String actor,
                       RedirectAttributes flash) {
        ProjectResource saved = apply(projectId, id, name, content, cells, cols, op, actor);
        flash.addFlashAttribute("message", "Saved " + saved.getName() + ".");
        return "redirect:/projects/" + projectId + "/docs/" + id;
    }

    /**
     * The same save, answered with JSON instead of a redirect. The editor calls
     * it a beat after you stop typing, which is why it says when it saved —
     * that is what the "Saved just now" line in the toolbar is reading.
     */
    @PostMapping("/projects/{projectId}/docs/{id}/autosave")
    @ResponseBody
    public Map<String, Object> autosave(@PathVariable Long projectId,
                                        @PathVariable Long id,
                                        @RequestParam(required = false) String name,
                                        @RequestParam(required = false) String content,
                                        @RequestParam(name = "cell", required = false) List<String> cells,
                                        @RequestParam(required = false) Integer cols,
                                        @RequestParam(required = false) String actor) {
        ProjectResource saved = apply(projectId, id, name, content, cells, cols, null, actor);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", saved.getName());
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
     * torn down, and a truncated one must never be read as "they emptied this
     * document".
     */
    private ProjectResource apply(Long projectId, Long id, String name, String content,
                                  List<String> cells, Integer cols, String op, String actor) {
        ProjectResource doc = docs.find(projectId, id);

        if (doc.isSheet()) {
            // Content wins over cells: the editor sends the whole grid with its
            // formatting, and the loose inputs only exist where it is not running.
            if (content != null && !content.isBlank()) {
                return docs.saveSheetJson(projectId, id, name, content, actor);
            }
            if (cells != null) {
                return docs.saveSheetCells(projectId, id, name, cells, cols, op, actor);
            }
            return docs.rename(projectId, id, name, null, null, actor);
        }

        return content == null
                ? docs.rename(projectId, id, name, null, null, actor)
                : docs.savePage(projectId, id, name, content, actor);
    }

    // ------------------------------------------------------------ housekeeping

    @PostMapping("/projects/{projectId}/docs/{id}/rename")
    public String rename(@PathVariable Long projectId,
                         @PathVariable Long id,
                         @RequestParam(required = false) String name,
                         @RequestParam(required = false) String note,
                         @RequestParam(required = false) String url,
                         @RequestParam(required = false) Long back,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        try {
            ProjectResource saved = docs.rename(projectId, id, name, note, url, actor);
            flash.addFlashAttribute("message", "Renamed to " + saved.getName() + ".");
        } catch (ProjectDocService.RejectedException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return back(projectId, back);
    }

    @PostMapping("/projects/{projectId}/docs/{id}/move")
    public String move(@PathVariable Long projectId,
                       @PathVariable Long id,
                       @RequestParam(required = false) Long targetId,
                       @RequestParam(required = false) Long back,
                       @RequestParam(required = false) String actor,
                       RedirectAttributes flash) {
        try {
            ProjectResource moved = docs.move(projectId, id, targetId, actor);
            flash.addFlashAttribute("message", "Moved " + moved.getName() + ".");
        } catch (ProjectDocService.RejectedException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return back(projectId, back);
    }

    @PostMapping("/projects/{projectId}/docs/{id}/delete")
    public String delete(@PathVariable Long projectId,
                         @PathVariable Long id,
                         @RequestParam(required = false) Long back,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        ProjectResource removed = docs.delete(projectId, id, actor);
        flash.addFlashAttribute("message", "Deleted " + removed.getName()
                + (removed.isFolder() ? " and everything in it." : "."));
        // Deleting the folder you are standing in would redirect you into a
        // folder that no longer exists; its parent is where you end up instead.
        Long land = back != null && back.equals(removed.getId()) ? removed.getParentId() : back;
        return back(projectId, land);
    }

    // ---------------------------------------------------------------- serving

    /**
     * The bytes of an uploaded file. Images and PDFs open in the page;
     * everything else downloads.
     */
    @GetMapping("/projects/{projectId}/docs/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable Long projectId,
                                         @PathVariable Long id,
                                         @RequestParam(required = false) boolean download)
            throws MalformedURLException {
        ProjectResource stored = docs.find(projectId, id);
        if (!stored.isFile()) {
            return ResponseEntity.notFound().build();
        }
        Path path = docs.pathOf(stored);
        Resource resource = new UrlResource(path.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        // Worked out from the stored name rather than read back off the row: the
        // type on the row came from the uploader once, and anything the caller
        // chose must not decide what we serve it as.
        MediaType type = AttachmentService.mediaTypeFor(stored.getName());

        String name = stored.getName();
        ContentDisposition.Builder builder = !download && AttachmentService.isInlineSafe(type)
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        // filename= is not URL-decoded by browsers, so a plain name goes in as
        // it is; anything else gets the RFC 6266 filename* beside it.
        ContentDisposition disposition = (isPlainAscii(name)
                ? builder.filename(name)
                : builder.filename(name, StandardCharsets.UTF_8))
                .build();

        long length;
        try {
            length = resource.contentLength();
        } catch (IOException e) {
            length = stored.getSizeBytes();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(type)
                .contentLength(length)
                .body(resource);
    }

    /** A page downloads as Markdown, a sheet as CSV — both open anywhere. */
    @GetMapping("/projects/{projectId}/docs/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long projectId, @PathVariable Long id) {
        ProjectResource doc = docs.find(projectId, id);
        if (!doc.isDocument()) {
            return ResponseEntity.notFound().build();
        }
        byte[] body = docs.export(doc);
        String name = UriUtils.encode(ProjectDocService.exportName(doc), StandardCharsets.UTF_8);
        String mediaType = doc.isSheet() ? "text/csv" : "text/markdown";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(mediaType + ";charset=UTF-8"))
                .contentLength(body.length)
                .body(body);
    }

    /** Whether a name can go in Content-Disposition as it stands, with no encoding. */
    private static boolean isPlainAscii(String name) {
        return name.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
    }

    /** Back to the folder the action was taken in — the root when there is none. */
    private static String back(Long projectId, Long folderId) {
        return "redirect:/projects/" + projectId + "/docs"
                + (folderId == null ? "" : "?folder=" + folderId);
    }
}
