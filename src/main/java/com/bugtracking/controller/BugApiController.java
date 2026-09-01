package com.bugtracking.controller;

import com.bugtracking.model.Attachment;
import com.bugtracking.model.Bug;
import com.bugtracking.model.BugHistory;
import com.bugtracking.model.Comment;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Notification;
import com.bugtracking.model.Priority;
import com.bugtracking.model.Severity;
import com.bugtracking.model.Status;
import com.bugtracking.service.AttachmentService;
import com.bugtracking.service.BugHistoryService;
import com.bugtracking.service.BugService;
import com.bugtracking.service.CommentService;
import com.bugtracking.service.NotificationService;
import com.bugtracking.service.ProjectService;
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

import java.util.List;
import java.util.Map;

/** JSON API — handy for raising bugs from scripts or automated tests. */
@RestController
@RequestMapping("/api/bugs")
public class BugApiController {

    private final BugService service;
    private final ProjectService projects;
    private final CommentService comments;
    private final AttachmentService attachments;
    private final BugHistoryService history;
    private final NotificationService notifications;

    public BugApiController(BugService service,
                            ProjectService projects,
                            CommentService comments,
                            AttachmentService attachments,
                            BugHistoryService history,
                            NotificationService notifications) {
        this.service = service;
        this.projects = projects;
        this.comments = comments;
        this.attachments = attachments;
        this.history = history;
        this.notifications = notifications;
    }

    /** The vocabularies a script needs to fill in a bug correctly. */
    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of(
                "statuses", labels(Status.values(), Status::getLabel),
                "severities", labels(Severity.values(), Severity::getLabel),
                "priorities", labels(Priority.values(), Priority::getLabel),
                "environments", labels(Environment.values(), Environment::getLabel),
                "projects", projects.activeNames());
    }

    @GetMapping
    public List<Bug> list(@RequestParam(required = false) String project,
                          @RequestParam(required = false) Status status,
                          @RequestParam(required = false) Severity severity,
                          @RequestParam(required = false) Priority priority,
                          @RequestParam(required = false) Environment environment,
                          @RequestParam(required = false) String assignee,
                          @RequestParam(required = false) String reporter,
                          @RequestParam(required = false) String keyword,
                          @RequestParam(required = false) String sort) {
        return service.findAll(project, status, severity, priority, environment,
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

    @PostMapping("/{id}/assign")
    public Bug assign(@PathVariable Long id,
                      @RequestParam String assignedTo,
                      @RequestParam(required = false) String actor) {
        return service.assign(id, assignedTo, actor);
    }

    @PostMapping("/{id}/status")
    public Bug changeStatus(@PathVariable Long id,
                            @RequestParam Status status,
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

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return Map.of(
                "byStatus", service.statusSummary(),
                "bySeverity", service.severitySummary(),
                "byPriority", service.prioritySummary(),
                "urgent", service.urgentCount());
    }

    @GetMapping("/notifications")
    public List<Notification> notifications() {
        return notifications.recent();
    }

    private static <E> List<Map<String, String>> labels(E[] values, java.util.function.Function<E, String> label) {
        return java.util.Arrays.stream(values)
                .map(v -> Map.of("value", String.valueOf(v), "label", label.apply(v)))
                .toList();
    }
}
