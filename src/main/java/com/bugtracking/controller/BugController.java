package com.bugtracking.controller;

import com.bugtracking.model.BoardColumn;
import com.bugtracking.model.Bug;
import com.bugtracking.model.ColumnColour;
import com.bugtracking.model.DocType;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;
import com.bugtracking.service.AttachmentService;
import com.bugtracking.service.BoardColumnService;
import com.bugtracking.service.BugHistoryService;
import com.bugtracking.service.BugService;
import com.bugtracking.service.CommentService;
import com.bugtracking.service.Dashboard;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.SupportingDocService;
import com.bugtracking.service.TeamMemberService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/bugs")
public class BugController {

    private final BugService service;
    private final BoardColumnService board;
    private final ProjectService projects;
    private final CommentService comments;
    private final AttachmentService attachments;
    private final BugHistoryService history;
    private final TeamMemberService team;
    private final SupportingDocService docs;

    public BugController(BugService service,
                         BoardColumnService board,
                         ProjectService projects,
                         CommentService comments,
                         AttachmentService attachments,
                         BugHistoryService history,
                         TeamMemberService team,
                         SupportingDocService docs) {
        this.service = service;
        this.board = board;
        this.projects = projects;
        this.comments = comments;
        this.attachments = attachments;
        this.history = history;
        this.team = team;
        this.docs = docs;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String project,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Severity severity,
                       @RequestParam(required = false) Environment environment,
                       @RequestParam(required = false) String assignee,
                       @RequestParam(required = false) String reporter,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) String view,
                       jakarta.servlet.http.HttpSession session,
                       RedirectAttributes redirect,
                       Model model) {

        // There is no "All projects" tab any more: a bare /bugs lands you in a
        // project. Only a bare one, though — a cross-project link such as
        // /bugs?assignee=X still means what it says.
        boolean noFilters = isBlank(status) && severity == null
                && environment == null && isBlank(assignee) && isBlank(reporter)
                && isBlank(keyword) && isBlank(sort);
        if (isBlank(project) && noFilters) {
            String landing = landingProject(session);
            if (landing != null) {
                // addAttribute, not addFlashAttribute: these belong in the query
                // string, and Spring encodes them — project names have spaces.
                redirect.addAttribute("project", landing);
                if ("list".equals(view)) {
                    redirect.addAttribute("view", "list");
                }
                return "redirect:/bugs";
            }
        }

        String mode = "list".equals(view) ? "list" : "board";

        // The dashboard describes the project; the board answers the filters.
        Dashboard dashboard = service.dashboard(project);
        List<Bug> bugs = service.findAll(project, status, severity,
                environment, assignee, reporter, keyword, sort);

        // The board this project actually runs, in the order it runs it.
        List<BoardColumn> boardColumns = board.forProject(project);

        model.addAttribute("bugs", bugs);
        model.addAttribute("boardColumns", boardColumns);
        model.addAttribute("columns", groupByColumn(boardColumns, bugs));
        // One lookup for the whole board, so a blocked card can say so.
        model.addAttribute("blockers", service.blockersFor(bugs));
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("boardTotal", service.dashboard(null).total());
        model.addAttribute("activity", history.recent(project == null ? null : dashboard.bugIds()));
        model.addAttribute("selectedProject", project);
        model.addAttribute("severities", Severity.values());
        model.addAttribute("environments", Environment.values());
        // The palette a column may be painted in, for the board's own menus.
        model.addAttribute("colours", ColumnColour.values());
        model.addAttribute("people", team.optionsIncluding(assignee, reporter));
        // Who actually carries work here, so the people filter shows faces
        // rather than a directory.
        model.addAttribute("workload", workloadIn(bugs, dashboard));
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedSeverity", severity);
        model.addAttribute("selectedEnvironment", environment);
        model.addAttribute("assignee", assignee);
        model.addAttribute("reporter", reporter);
        model.addAttribute("keyword", keyword);
        model.addAttribute("sort", sort);
        model.addAttribute("view", mode);
        model.addAttribute("filtered", !noFilters);
        model.addAttribute("q", BoardQuery.builder()
                .put("project", project)
                .put("status", status)
                .put("severity", severity)
                .put("environment", environment)
                .put("assignee", assignee)
                .put("reporter", reporter)
                .put("keyword", keyword)
                .put("sort", sort)
                .put("view", "list".equals(mode) ? "list" : null)
                .build());
        return "bugs/list";
    }

    /**
     * The people on this project's board and how much each is carrying.
     * Counted over the whole project rather than the filtered result, so the
     * faces do not vanish the moment you filter down to one of them.
     */
    private Map<String, Long> workloadIn(List<Bug> filtered, Dashboard dashboard) {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<Bug> scope = dashboard.project() == null
                ? filtered
                : service.findAll(dashboard.project(), null, null, null, null, null, null, null);
        for (Bug bug : scope) {
            for (String who : bug.getAssignees()) {
                counts.merge(who.trim(), 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    /**
     * The bugs that survived the filters, bucketed into their board columns.
     *
     * <p>A bug holding a key this board has no column for lands in the first
     * one. That should not happen — deleting a column empties it first, and
     * moving a bug between projects remaps it — but a card that renders
     * nowhere is the one outcome worth ruling out entirely.
     */
    private static Map<BoardColumn, List<Bug>> groupByColumn(List<BoardColumn> columns, List<Bug> bugs) {
        Map<String, BoardColumn> byKey = new LinkedHashMap<>();
        Map<BoardColumn, List<Bug>> grouped = new LinkedHashMap<>();
        for (BoardColumn column : columns) {
            byKey.put(column.getStatusKey(), column);
            grouped.put(column, new ArrayList<>());
        }
        if (columns.isEmpty()) {
            return grouped;
        }
        for (Bug bug : bugs) {
            BoardColumn column = byKey.getOrDefault(bug.getStatus(), columns.get(0));
            grouped.get(column).add(bug);
        }
        return grouped;
    }

    /**
     * Where a bare /bugs lands: the project you were last in, or the first one
     * the switcher offers. A remembered project that has since been removed is
     * ignored rather than 404-ing you into an empty board.
     */
    private String landingProject(jakarta.servlet.http.HttpSession session) {
        var names = projects.sidebarCounts().keySet();
        Object remembered = session.getAttribute(GlobalModelAttributes.PROJECT_KEY);
        if (remembered instanceof String name && names.contains(name)) {
            return name;
        }
        return names.stream().findFirst().orElse(null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The bin. Declared above {@code /bugs/{id}} deliberately — Spring would
     * otherwise try to read "trash" as a bug id and 400 before getting here.
     */
    @GetMapping("/trash")
    public String trash(Model model) {
        model.addAttribute("trashed", service.trash());
        return "bugs/trash";
    }

    @GetMapping("/new")
    public String createForm(@RequestParam(required = false) String project,
                             jakarta.servlet.http.HttpSession session,
                             Principal principal,
                             Model model) {
        Bug bug = new Bug();

        // Raising from inside a project pre-selects it; you rarely mean a
        // different one. Falling back to the session's project means the same
        // is true of the New bug button anywhere else in the app.
        String chosen = project;
        if (chosen == null || chosen.isBlank()) {
            Object remembered = session.getAttribute(GlobalModelAttributes.PROJECT_KEY);
            if (remembered instanceof String name && !name.isBlank()) {
                chosen = name;
            }
        }
        if (chosen != null && !chosen.isBlank()) {
            bug.setProject(chosen.trim());
        }

        // You are the one filing it. Still a dropdown, so filing on someone
        // else's behalf stays possible.
        if (principal != null) {
            bug.setReportedBy(principal.getName());
        }

        model.addAttribute("bug", bug);
        addFormOptions(model, bug);
        return "bugs/form";
    }

    @PostMapping
    public String create(@Valid @org.springframework.web.bind.annotation.ModelAttribute("bug") Bug bug,
                         BindingResult result,
                         Model model,
                         @RequestParam(required = false) String actor,
                         @RequestParam(value = "files", required = false) MultipartFile[] files,
                         RedirectAttributes flash) {
        if (result.hasErrors()) {
            addFormOptions(model, bug);
            return "bugs/form";
        }
        Bug saved = service.save(bug, actor);

        // Files come with the bug rather than after it: a screenshot is usually
        // the clearest part of the report, and asking for it on a second screen
        // is how it never gets attached.
        String rejected = attachFiles(saved.getId(), files, actor);

        flash.addFlashAttribute("message", rejected == null
                ? "Bug #" + saved.getId() + " raised successfully."
                : "Bug #" + saved.getId() + " raised, but " + rejected);
        return "redirect:/bugs/" + saved.getId();
    }

    /**
     * Stores whatever was picked on the form. Returns null when everything
     * landed, or a sentence naming what did not — a rejected screenshot must
     * not lose you the bug you just typed out.
     */
    private String attachFiles(Long bugId, MultipartFile[] files, String actor) {
        if (files == null) {
            return null;
        }
        List<String> problems = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                attachments.store(bugId, file, actor);
            } catch (AttachmentService.RejectedFileException e) {
                problems.add(e.getMessage());
            }
        }
        return problems.isEmpty() ? null : String.join(" ", problems);
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Bug bug = service.findById(id);
        model.addAttribute("bug", bug);
        // Keeps the sidebar switcher pointed at the project this bug belongs to.
        model.addAttribute("selectedProject", bug.getProject());
        model.addAttribute("boardColumns", board.forProject(bug.getProject()));
        model.addAttribute("people", peopleFor(bug));
        model.addAttribute("comments", comments.forBug(id));
        model.addAttribute("attachments", attachments.forBug(id));
        model.addAttribute("docs", docs.forBug(id));
        model.addAttribute("docTypes", DocType.values());
        model.addAttribute("timeline", history.forBug(id));
        model.addAttribute("blocker", bug.getBlockedBy() == null
                ? null
                : service.blockersFor(List.of(bug)).get(bug.getBlockedBy()));
        model.addAttribute("blockerOptions", service.blockerOptions(id));
        return "bugs/detail";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Bug bug = service.findById(id);
        model.addAttribute("bug", bug);
        addFormOptions(model, bug);
        return "bugs/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @org.springframework.web.bind.annotation.ModelAttribute("bug") Bug bug,
                         BindingResult result,
                         Model model,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        if (result.hasErrors()) {
            addFormOptions(model, bug);
            return "bugs/form";
        }
        service.update(id, bug, actor);
        flash.addFlashAttribute("message", "Bug #" + id + " updated.");
        return "redirect:/bugs/" + id;
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable Long id,
                               @RequestParam String status,
                               @RequestParam(required = false) String actor,
                               RedirectAttributes flash) {
        Bug moved = service.changeStatus(id, status, actor);
        flash.addFlashAttribute("message", "Bug #" + id + " moved to "
                + board.snapshot().label(moved) + ".");
        return "redirect:/bugs/" + id;
    }

    /**
     * Sets who is on the bug. Takes a repeated {@code assignees} parameter, and
     * an empty submission means nobody — so clearing every box unassigns it.
     */
    @PostMapping("/{id}/assign")
    public String assign(@PathVariable Long id,
                         @RequestParam(name = "assignees", required = false) List<String> assignees,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        Bug bug = service.assign(id, assignees == null ? List.of() : assignees, actor);
        flash.addFlashAttribute("message", bug.getAssignees().isEmpty()
                ? "Bug #" + id + " is now unassigned."
                : "Bug #" + id + " assigned to " + bug.getAssigneesLabel() + ".");
        return "redirect:/bugs/" + id;
    }

    /** Records — or clears, with a blank value — the bug holding this one up. */
    @PostMapping("/{id}/block")
    public String block(@PathVariable Long id,
                        @RequestParam(required = false) Long blockedBy,
                        @RequestParam(required = false) String actor,
                        RedirectAttributes flash) {
        Bug bug = service.block(id, blockedBy, actor);
        flash.addFlashAttribute("message", bug.getBlockedBy() == null
                ? "Bug #" + id + " is not blocked any more."
                : "Bug #" + id + " is blocked by BUG-" + bug.getBlockedBy() + ".");
        return "redirect:/bugs/" + id;
    }

    @PostMapping("/{id}/comments")
    public String addComment(@PathVariable Long id,
                             @RequestParam String text,
                             @RequestParam(required = false) String actor,
                             RedirectAttributes flash) {
        if (text == null || text.isBlank()) {
            flash.addFlashAttribute("message", "Comment was empty - nothing added.");
            return "redirect:/bugs/" + id + "#comments";
        }
        comments.add(id, text, actor);
        flash.addFlashAttribute("message", "Comment added to bug #" + id + ".");
        return "redirect:/bugs/" + id + "#comments";
    }

    @PostMapping("/{id}/attachments")
    public String upload(@PathVariable Long id,
                         @RequestParam("file") MultipartFile file,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        service.findById(id);                       // 404 before touching the disk
        try {
            attachments.store(id, file, actor);
            flash.addFlashAttribute("message", "Attachment added to bug #" + id + ".");
        } catch (AttachmentService.RejectedFileException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/bugs/" + id + "#attachments";
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<Resource> download(@PathVariable Long id, @PathVariable Long attachmentId)
            throws MalformedURLException {
        var attachment = attachments.findById(attachmentId);
        if (!attachment.getBugId().equals(id)) {
            return ResponseEntity.notFound().build();
        }
        Path path = attachments.pathOf(attachment);
        Resource resource = new UrlResource(path.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        // Worked out from the stored name rather than read back off the row:
        // the type on the row came from the uploader once, and anything the
        // caller chose must not decide what we serve it as.
        MediaType type = AttachmentService.mediaTypeFor(attachment.getFileName());

        // Images and PDFs open in the browser; everything else downloads.
        // filename= is not URL-decoded by browsers, so percent-encoding into it
        // saved "my report.png" as "my%20report.png". A plain name needs no
        // encoding at all; anything else gets the RFC 6266 filename* beside it,
        // which is the half browsers actually read.
        String name = attachment.getFileName();
        ContentDisposition.Builder builder = AttachmentService.isInlineSafe(type)
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        ContentDisposition disposition = (isPlainAscii(name)
                ? builder.filename(name)
                : builder.filename(name, StandardCharsets.UTF_8))
                .build();

        long length;
        try {
            length = resource.contentLength();
        } catch (IOException e) {
            length = attachment.getSizeBytes();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(type)
                .contentLength(length)
                .body(resource);
    }

    /** Whether a name can go in Content-Disposition as it stands, with no encoding. */
    private static boolean isPlainAscii(String name) {
        return name.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
    }

    @PostMapping("/{id}/attachments/{attachmentId}/delete")
    public String removeAttachment(@PathVariable Long id,
                                   @PathVariable Long attachmentId,
                                   @RequestParam(required = false) String actor,
                                   RedirectAttributes flash) {
        var attachment = attachments.findById(attachmentId);
        if (!attachment.getBugId().equals(id)) {
            // Deleting by guessing an id from another bug is not a thing.
            flash.addFlashAttribute("message", "That file is not on this bug.");
            return "redirect:/bugs/" + id + "#attachments";
        }
        attachments.delete(attachmentId, actor);
        flash.addFlashAttribute("message", "Removed " + attachment.getFileName() + ".");
        return "redirect:/bugs/" + id + "#attachments";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        Bug bug = service.delete(id, actor);
        flash.addFlashAttribute("message", "BUG-" + id + " moved to the trash.");
        flash.addFlashAttribute("undoBugId", id);
        return "redirect:/bugs" + (bug.getProject() == null
                ? "" : "?project=" + org.springframework.web.util.UriUtils.encode(
                        bug.getProject(), StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/restore")
    public String restore(@PathVariable Long id,
                          @RequestParam(required = false) String actor,
                          @RequestParam(required = false) String from,
                          RedirectAttributes flash) {
        service.restore(id, actor);
        flash.addFlashAttribute("message", "BUG-" + id + " is back.");
        // Undo from a board toast goes back to the bug; the trash page stays put.
        return "trash".equals(from) ? "redirect:/bugs/trash" : "redirect:/bugs/" + id;
    }

    @PostMapping("/{id}/purge")
    public String purge(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.purge(id);
            flash.addFlashAttribute("message", "BUG-" + id + " deleted for good.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/bugs/trash";
    }

    private void addFormOptions(Model model, Bug bug) {
        List<String> projectOptions = projects.optionsIncluding(bug.getProject());

        model.addAttribute("severities", Severity.values());
        model.addAttribute("environments", Environment.values());
        model.addAttribute("projectOptions", projectOptions);
        // The status picker offers this project's columns. Every project the
        // form can switch to goes in too, so changing the project swaps the
        // status options without a round trip — see form.html.
        model.addAttribute("boardColumns", board.forProject(bug.getProject()));
        model.addAttribute("columnsJson", columnsJson(projectOptions));
        model.addAttribute("people", peopleFor(bug));
        model.addAttribute("selectedProject", bug.getProject());
        model.addAttribute("blockerOptions", service.blockerOptions(bug.getId()));
    }

    /**
     * Each project's columns as JSON, carried on the status select so changing
     * the project on the form re-offers the right ones without a round trip.
     *
     * <p>Only key and wording: the form has no use for a column's colour or its
     * notification setting, and the less of an entity that leaves the server the
     * fewer surprises there are in what gets serialised.
     *
     * <p>Nothing depends on it. With JavaScript off the select keeps the
     * columns of the project the form opened on, and a status that does not
     * belong to the project it is saved against is remapped to that board's
     * first column by {@code BugService.update}.
     */
    private String columnsJson(List<String> projectNames) {
        Map<String, List<Map<String, String>>> columns = new LinkedHashMap<>();
        for (String name : projectNames) {
            columns.put(name, board.forProject(name).stream()
                    .map(column -> Map.of("status", column.getStatusKey(),
                                          "label", column.getLabel()))
                    .toList());
        }
        try {
            return new ObjectMapper().writeValueAsString(columns);
        } catch (JsonProcessingException e) {
            return "{}";                      // the select simply stops re-offering
        }
    }

    /** The team, plus any name already on this bug that has since been hidden. */
    private List<String> peopleFor(Bug bug) {
        List<String> current = new ArrayList<>(bug.getAssignees());
        current.add(bug.getReportedBy());
        return team.optionsIncluding(current.toArray(String[]::new));
    }
}
