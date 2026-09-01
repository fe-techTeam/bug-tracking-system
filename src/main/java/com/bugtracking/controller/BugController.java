package com.bugtracking.controller;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Priority;
import com.bugtracking.model.Severity;
import com.bugtracking.model.Status;
import com.bugtracking.service.AttachmentService;
import com.bugtracking.service.BugHistoryService;
import com.bugtracking.service.BugService;
import com.bugtracking.service.CommentService;
import com.bugtracking.service.Dashboard;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.TeamMemberService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
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
import org.springframework.web.util.UriUtils;

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
    private final ProjectService projects;
    private final CommentService comments;
    private final AttachmentService attachments;
    private final BugHistoryService history;
    private final TeamMemberService team;

    public BugController(BugService service,
                         ProjectService projects,
                         CommentService comments,
                         AttachmentService attachments,
                         BugHistoryService history,
                         TeamMemberService team) {
        this.service = service;
        this.projects = projects;
        this.comments = comments;
        this.attachments = attachments;
        this.history = history;
        this.team = team;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String project,
                       @RequestParam(required = false) Status status,
                       @RequestParam(required = false) Severity severity,
                       @RequestParam(required = false) Priority priority,
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
        boolean noFilters = status == null && severity == null && priority == null
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
        List<Bug> bugs = service.findAll(project, status, severity, priority,
                environment, assignee, reporter, keyword, sort);

        model.addAttribute("bugs", bugs);
        model.addAttribute("columns", groupByStatus(bugs));
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("boardTotal", service.dashboard(null).total());
        model.addAttribute("activity", history.recent(project == null ? null : dashboard.bugIds()));
        model.addAttribute("selectedProject", project);
        model.addAttribute("statuses", Status.values());
        model.addAttribute("boardStatuses", Status.boardOrder());
        model.addAttribute("severities", Severity.values());
        model.addAttribute("priorities", Priority.values());
        model.addAttribute("environments", Environment.values());
        model.addAttribute("people", team.optionsIncluding(assignee, reporter));
        // Who actually carries work here, so the people filter shows faces
        // rather than a directory.
        model.addAttribute("workload", workloadIn(bugs, dashboard));
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedSeverity", severity);
        model.addAttribute("selectedPriority", priority);
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
                .put("priority", priority)
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
                : service.findAll(dashboard.project(), null, null, null, null, null, null, null, null);
        for (Bug bug : scope) {
            String who = bug.getAssignedTo();
            if (who != null && !who.isBlank()) {
                counts.merge(who.trim(), 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    /** The bugs that survived the filters, bucketed into their board columns. */
    private static Map<Status, List<Bug>> groupByStatus(List<Bug> bugs) {
        Map<Status, List<Bug>> columns = new LinkedHashMap<>();
        for (Status status : Status.boardOrder()) {
            columns.put(status, new ArrayList<>());
        }
        for (Bug bug : bugs) {
            columns.computeIfAbsent(bug.getStatus(), key -> new ArrayList<>()).add(bug);
        }
        return columns;
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

    @GetMapping("/new")
    public String createForm(@RequestParam(required = false) String project, Model model) {
        Bug bug = new Bug();
        // Raising from inside a project pre-selects it; you rarely mean a different one.
        if (project != null && !project.isBlank()) {
            bug.setProject(project.trim());
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
                         RedirectAttributes flash) {
        if (result.hasErrors()) {
            addFormOptions(model, bug);
            return "bugs/form";
        }
        Bug saved = service.save(bug, actor);
        flash.addFlashAttribute("message", "Bug #" + saved.getId() + " raised successfully.");
        return "redirect:/bugs/" + saved.getId();
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Bug bug = service.findById(id);
        model.addAttribute("bug", bug);
        // Keeps the sidebar switcher pointed at the project this bug belongs to.
        model.addAttribute("selectedProject", bug.getProject());
        model.addAttribute("statuses", Status.values());
        model.addAttribute("boardStatuses", Status.boardOrder());
        model.addAttribute("people", team.optionsIncluding(bug.getAssignedTo()));
        model.addAttribute("comments", comments.forBug(id));
        model.addAttribute("attachments", attachments.forBug(id));
        model.addAttribute("timeline", history.forBug(id));
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
                               @RequestParam Status status,
                               @RequestParam(required = false) String actor,
                               RedirectAttributes flash) {
        service.changeStatus(id, status, actor);
        flash.addFlashAttribute("message", "Bug #" + id + " moved to " + status.getLabel() + ".");
        return "redirect:/bugs/" + id;
    }

    @PostMapping("/{id}/assign")
    public String assign(@PathVariable Long id,
                         @RequestParam String assignedTo,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        Bug bug = service.assign(id, assignedTo, actor);
        flash.addFlashAttribute("message", bug.getAssignedTo() == null
                ? "Bug #" + id + " is now unassigned."
                : "Bug #" + id + " assigned to " + bug.getAssignedTo() + ".");
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

        // Images and PDFs open in the browser; everything else downloads.
        String disposition = (attachment.isImage() || "application/pdf".equals(attachment.getContentType()))
                ? "inline" : "attachment";
        String encoded = UriUtils.encode(attachment.getFileName(), StandardCharsets.UTF_8);

        long length;
        try {
            length = resource.contentLength();
        } catch (IOException e) {
            length = attachment.getSizeBytes();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition + "; filename=\"" + encoded + "\"")
                .contentType(attachment.getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(attachment.getContentType()))
                .contentLength(length)
                .body(resource);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        service.delete(id);
        flash.addFlashAttribute("message", "Bug #" + id + " deleted.");
        return "redirect:/bugs";
    }

    private void addFormOptions(Model model, Bug bug) {
        model.addAttribute("statuses", Status.values());
        model.addAttribute("severities", Severity.values());
        model.addAttribute("priorities", Priority.values());
        model.addAttribute("environments", Environment.values());
        model.addAttribute("projectOptions", projects.optionsIncluding(bug.getProject()));
        model.addAttribute("people", team.optionsIncluding(bug.getReportedBy(), bug.getAssignedTo()));
        model.addAttribute("selectedProject", bug.getProject());
    }
}
