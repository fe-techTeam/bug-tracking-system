package com.bugtracking.controller;

import com.bugtracking.model.Attachment;
import com.bugtracking.model.Bug;
import com.bugtracking.model.BugHistory;
import com.bugtracking.model.Comment;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Notification;
import com.bugtracking.model.Severity;
import com.bugtracking.model.SupportingDoc;
import com.bugtracking.service.AttachmentService;
import com.bugtracking.service.BoardColumnService;
import com.bugtracking.service.BugHistoryService;
import com.bugtracking.service.BugService;
import com.bugtracking.service.CommentService;
import com.bugtracking.service.NotificationService;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.SupportingDocService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON API — handy for raising bugs from scripts or automated tests. */
@RestController
@RequestMapping("/api/bugs")
public class BugApiController {

    private final BugService service;
    private final BoardColumnService columns;
    private final ProjectService projects;
    private final CommentService comments;
    private final AttachmentService attachments;
    private final BugHistoryService history;
    private final NotificationService notifications;
    private final SupportingDocService docs;

    public BugApiController(BugService service,
                            BoardColumnService columns,
                            ProjectService projects,
                            CommentService comments,
                            AttachmentService attachments,
                            BugHistoryService history,
                            NotificationService notifications,
                            SupportingDocService docs) {
        this.service = service;
        this.columns = columns;
        this.projects = projects;
        this.comments = comments;
        this.attachments = attachments;
        this.history = history;
        this.notifications = notifications;
        this.docs = docs;
    }

    /**
     * The vocabularies a script needs to fill in a bug correctly.
     *
     * <p>Statuses are per project now, so they come back as a map of project to
     * its columns rather than one flat list — a script setting a status has to
     * know which board it is writing to. Each column is given as the key a bug
     * stores alongside the wording a person reads.
     */
    @GetMapping("/options")
    public Map<String, Object> options() {
        Map<String, List<Map<String, String>>> statuses = new LinkedHashMap<>();
        for (String project : projects.activeNames()) {
            statuses.put(project, columns.forProject(project).stream()
                    .map(column -> Map.of("status", column.getStatusKey(),
                                          "label", column.getLabel()))
                    .toList());
        }
        return Map.of(
                "statuses", statuses,
                "severities", labels(Severity.values(), Severity::getLabel),
                "environments", labels(Environment.values(), Environment::getLabel),
                "projects", projects.activeNames());
    }

    @GetMapping
    public List<Bug> list(@RequestParam(required = false) String project,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) Severity severity,
                          @RequestParam(required = false) Environment environment,
                          @RequestParam(required = false) String assignee,
                          @RequestParam(required = false) String reporter,
                          @RequestParam(required = false) String keyword,
                          @RequestParam(required = false) String sort) {
        return service.findAll(project, status, severity, environment,
                assignee, reporter, keyword, sort);
    }

    @GetMapping("/{id}")
    public Bug get(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Bug create(@Valid @RequestBody Bug bug,
                      @RequestParam(required = false) String actor) {
        return service.save(bug, actor);
    }

    @PutMapping("/{id}")
    public Bug update(@PathVariable Long id,
                      @Valid @RequestBody Bug bug,
                      @RequestParam(required = false) String actor) {
        return service.update(id, bug, actor);
    }

    /**
     * Puts people on a bug. A bug can carry several, so this takes a list:
     * {@code ?assignees=Ajay&assignees=Nishana%20R}. The older single
     * {@code assignedTo} still works and still means what it always did —
     * <em>replace</em> whoever is on the bug with this one person — so a script
     * written against it is unaffected. Either form with {@code add=true} puts
     * the named people on alongside the ones already there, which is the way to
     * add somebody without first having to read the bug.
     *
     * <p>An empty {@code assignedTo}, or neither parameter, clears the list.
     */
    @PostMapping("/{id}/assign")
    public Bug assign(@PathVariable Long id,
                      @RequestParam(required = false) List<String> assignees,
                      @RequestParam(required = false) String assignedTo,
                      @RequestParam(required = false, defaultValue = "false") boolean add,
                      @RequestParam(required = false) String actor) {
        List<String> people = new java.util.ArrayList<>();
        if (add) {
            people.addAll(service.findById(id).getAssignees());
        }
        if (assignees != null) {
            people.addAll(assignees);
        }
        if (assignedTo != null) {
            people.add(assignedTo);
        }
        // Bug.setAssignees does the trimming, blank-dropping and de-duplicating.
        return service.assign(id, people, actor);
    }

    @PostMapping("/{id}/status")
    public Bug changeStatus(@PathVariable Long id,
                            @RequestParam String status,
                            @RequestParam(required = false) String actor) {
        return service.changeStatus(id, status, actor);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/comments")
    public List<Comment> comments(@PathVariable Long id) {
        service.findById(id);
        return comments.forBug(id);
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Comment addComment(@PathVariable Long id,
                              @RequestBody Map<String, String> body) {
        service.findById(id);
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A comment needs \"text\".");
        }
        return comments.add(id, text, body.get("author"));
    }

    @GetMapping("/{id}/history")
    public List<BugHistory> history(@PathVariable Long id) {
        service.findById(id);
        return history.forBug(id);
    }

    @GetMapping("/{id}/attachments")
    public List<Attachment> attachments(@PathVariable Long id) {
        service.findById(id);
        return attachments.forBug(id);
    }

    /**
     * The supporting documents on a bug. Read-only, and without their bodies —
     * a test-run script wants to know what is there, not to be handed 100 KB of
     * Markdown it did not ask for. Add {@code /{docId}} for the content.
     */
    @GetMapping("/{id}/docs")
    public List<Map<String, Object>> docs(@PathVariable Long id) {
        service.findById(id);
        return docs.forBug(id).stream().map(BugApiController::docSummary).toList();
    }

    @GetMapping("/{id}/docs/{docId}")
    public Map<String, Object> doc(@PathVariable Long id, @PathVariable Long docId) {
        SupportingDoc doc = docs.find(id, docId);
        Map<String, Object> body = new java.util.LinkedHashMap<>(docSummary(doc));
        body.put("content", doc.getContent());
        return body;
    }

    private static Map<String, Object> docSummary(SupportingDoc doc) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", doc.getId());
        body.put("bugId", doc.getBugId());
        body.put("title", doc.getTitle());
        body.put("type", doc.getType().name());
        body.put("summary", doc.getSummary());
        body.put("createdBy", doc.getCreatedBy());
        body.put("createdAt", doc.getCreatedAt());
        body.put("updatedBy", doc.getUpdatedBy());
        body.put("updatedAt", doc.getUpdatedAt());
        return body;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return Map.of(
                "byStatus", service.statusSummary(),
                "bySeverity", service.severitySummary());
    }

    /**
     * Notifications, newest first. This end of the app is open and has nobody
     * signed in to answer for, so it lists everyone's unless asked for one
     * person's with {@code ?recipient=Nishana R}. The bell and /notifications
     * are scoped to whoever is signed in and are not affected by this.
     */
    @GetMapping("/notifications")
    public List<Notification> notifications(@RequestParam(required = false) String recipient) {
        return recipient == null || recipient.isBlank()
                ? notifications.all()
                : notifications.recentFor(recipient);
    }

    private static <E> List<Map<String, String>> labels(E[] values, java.util.function.Function<E, String> label) {
        return java.util.Arrays.stream(values)
                .map(v -> Map.of("value", String.valueOf(v), "label", label.apply(v)))
                .toList();
    }
}
