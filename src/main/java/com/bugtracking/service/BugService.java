package com.bugtracking.service;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Priority;
import com.bugtracking.model.Severity;
import com.bugtracking.model.Status;
import com.bugtracking.repository.BugRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@Transactional
public class BugService {

    private final BugRepository repository;
    private final BugHistoryService history;
    private final NotificationService notifications;
    private final CommentService comments;
    private final AttachmentService attachments;

    public BugService(BugRepository repository,
                      BugHistoryService history,
                      NotificationService notifications,
                      CommentService comments,
                      AttachmentService attachments) {
        this.repository = repository;
        this.history = history;
        this.notifications = notifications;
        this.comments = comments;
        this.attachments = attachments;
    }

    /** The original three-filter search, kept so existing callers are unaffected. */
    @Transactional(readOnly = true)
    public List<Bug> findAll(Status status, Severity severity, String keyword) {
        return findAll(null, status, severity, null, null, null, keyword, null);
    }

    /** The eight-argument search, kept for callers that predate the reporter filter. */
    @Transactional(readOnly = true)
    public List<Bug> findAll(String project, Status status, Severity severity, Priority priority,
                             Environment environment, String assignee, String keyword, String sort) {
        return findAll(project, status, severity, priority, environment, assignee, null, keyword, sort);
    }

    @Transactional(readOnly = true)
    public List<Bug> findAll(String project, Status status, Severity severity, Priority priority,
                             Environment environment, String assignee, String reporter,
                             String keyword, String sort) {
        String trimmed = blankToNull(keyword);
        List<Bug> found = repository.search(blankToNull(project), status, severity, priority,
                environment, blankToNull(assignee), blankToNull(reporter), trimmed, idIn(trimmed));
        return sorted(found, sort);
    }

    /** Who currently carries work, busiest first — the board's people filter. */
    @Transactional(readOnly = true)
    public Map<String, Long> assigneeCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : repository.countGroupedByAssignee()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** Everything one person raised, newest first. */
    @Transactional(readOnly = true)
    public List<Bug> reportedBy(String name) {
        return name == null || name.isBlank()
                ? List.of()
                : repository.findByReportedByIgnoreCaseOrderByCreatedAtDesc(name.trim());
    }

    /** Everything sitting on one person's plate, newest first. */
    @Transactional(readOnly = true)
    public List<Bug> assignedTo(String name) {
        return name == null || name.isBlank()
                ? List.of()
                : repository.findByAssignedToIgnoreCaseOrderByCreatedAtDesc(name.trim());
    }

    /**
     * The numbers behind one project's dashboard, or the whole board when
     * project is null. Counted in Java from a single fetch rather than a dozen
     * COUNT queries — and, more importantly, counted over the project scope
     * only, so the tiles never move when you change a status or severity
     * filter. The tiles describe the project; the table below them answers the
     * filter.
     */
    @Transactional(readOnly = true)
    public Dashboard dashboard(String project) {
        String scopeName = blankToNull(project);
        List<Bug> scope = scopeName == null
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findByProjectIgnoreCaseOrderByCreatedAtDesc(scopeName);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            byStatus.put(status.getLabel(), scope.stream().filter(b -> b.getStatus() == status).count());
        }
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity.getLabel(), scope.stream().filter(b -> b.getSeverity() == severity).count());
        }
        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (Priority priority : Priority.values()) {
            byPriority.put(priority.getLabel(), scope.stream().filter(b -> b.getPriority() == priority).count());
        }

        long urgent = scope.stream()
                .filter(b -> b.getPriority() != null && b.getPriority().isUrgent())
                .count();
        long open = scope.stream()
                .filter(b -> b.getStatus() != Status.FIXED
                        && b.getStatus() != Status.RETEST
                        && b.getStatus() != Status.CLOSED)
                .count();
        long maxStatus = byStatus.values().stream().mapToLong(Long::longValue).max().orElse(0);

        return new Dashboard(scopeName, scope.size(), byStatus, bySeverity, byPriority,
                urgent, open, maxStatus, scope.stream().map(Bug::getId).toList());
    }

    @Transactional(readOnly = true)
    public Bug findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No bug found with id " + id));
    }

    public Bug save(Bug bug) {
        return save(bug, null);
    }

    public Bug save(Bug bug, String actor) {
        Bug saved = repository.save(bug);
        history.record(saved.getId(), "created", null, saved.getStatus().getLabel(), actor);
        if (saved.getAssignedTo() != null && !saved.getAssignedTo().isBlank()) {
            notifications.notify(saved.getId(), "assigned", saved.getAssignedTo(),
                    "BUG-" + saved.getId() + " was raised and assigned to you: " + saved.getTitle());
        }
        return saved;
    }

    public Bug update(Long id, Bug changes) {
        return update(id, changes, null);
    }

    /** Copies editable fields onto the managed entity so timestamps stay intact. */
    public Bug update(Long id, Bug changes, String actor) {
        Bug existing = findById(id);

        history.recordIfChanged(id, "title", existing.getTitle(), changes.getTitle(), actor);
        history.recordIfChanged(id, "severity", label(existing.getSeverity()), label(changes.getSeverity()), actor);
        history.recordIfChanged(id, "priority", label(existing.getPriority()), label(changes.getPriority()), actor);
        history.recordIfChanged(id, "environment", label(existing.getEnvironment()), label(changes.getEnvironment()), actor);
        history.recordIfChanged(id, "status", label(existing.getStatus()), label(changes.getStatus()), actor);
        history.recordIfChanged(id, "project", existing.getProject(), changes.getProject(), actor);
        history.recordIfChanged(id, "module", existing.getModule(), changes.getModule(), actor);
        history.recordIfChanged(id, "assigned", existing.getAssignedTo(), changes.getAssignedTo(), actor);

        boolean assigneeChanged = !equalText(existing.getAssignedTo(), changes.getAssignedTo());
        Status before = existing.getStatus();

        existing.setTitle(changes.getTitle());
        existing.setDescription(changes.getDescription());
        existing.setStepsToReproduce(changes.getStepsToReproduce());
        existing.setExpectedResult(changes.getExpectedResult());
        existing.setActualResult(changes.getActualResult());
        existing.setSeverity(changes.getSeverity());
        existing.setPriority(changes.getPriority());
        existing.setEnvironment(changes.getEnvironment());
        existing.setStatus(changes.getStatus());
        existing.setProject(changes.getProject());
        existing.setModule(changes.getModule());
        existing.setReportedBy(changes.getReportedBy());
        existing.setAssignedTo(changes.getAssignedTo());

        Bug saved = repository.save(existing);

        if (assigneeChanged && saved.getAssignedTo() != null && !saved.getAssignedTo().isBlank()) {
            notifications.notify(id, "assigned", saved.getAssignedTo(),
                    "BUG-" + id + " is now assigned to you: " + saved.getTitle());
        }
        if (before != saved.getStatus()) {
            notifyStatusChange(saved);
        }
        return saved;
    }

    public Bug changeStatus(Long id, Status status) {
        return changeStatus(id, status, null);
    }

    public Bug changeStatus(Long id, Status status, String actor) {
        Bug bug = findById(id);
        Status before = bug.getStatus();
        if (before == status) {
            return bug;
        }
        bug.setStatus(status);
        Bug saved = repository.save(bug);
        history.record(id, "status", before.getLabel(), status.getLabel(), actor);
        notifyStatusChange(saved);
        return saved;
    }

    /**
     * Assigns a bug to somebody. A bug that is still merely Open moves to
     * Assigned, matching the lifecycle in the BRD; a bug already being worked on
     * keeps whatever status it had.
     */
    public Bug assign(Long id, String assignee, String actor) {
        Bug bug = findById(id);
        String before = bug.getAssignedTo();
        bug.setAssignedTo(blankToNull(assignee));

        Status statusBefore = bug.getStatus();
        if (bug.getAssignedTo() != null && (statusBefore == Status.OPEN || statusBefore == Status.REOPENED)) {
            bug.setStatus(Status.ASSIGNED);
        }

        Bug saved = repository.save(bug);
        history.recordIfChanged(id, "assigned", before, saved.getAssignedTo(), actor);
        if (statusBefore != saved.getStatus()) {
            history.record(id, "status", statusBefore.getLabel(), saved.getStatus().getLabel(), actor);
        }
        if (saved.getAssignedTo() != null) {
            notifications.notify(id, "assigned", saved.getAssignedTo(),
                    "BUG-" + id + " was assigned to you: " + saved.getTitle());
        }
        return saved;
    }

    /** Removes the bug and everything hanging off it, files included. */
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new NoSuchElementException("No bug found with id " + id);
        }
        attachments.deleteForBug(id);
        comments.deleteForBug(id);
        history.deleteForBug(id);
        notifications.deleteForBug(id);
        repository.deleteById(id);
    }

    /** Backfills bugs that predate the project field. Returns how many were touched. */
    public int fillMissingProject(String project) {
        return repository.fillMissingProject(project);
    }

    /** Same for the priority and environment fields, added later still. */
    public int fillMissingPriority(Priority priority) {
        return repository.fillMissingPriority(priority);
    }

    public int fillMissingEnvironment(Environment environment) {
        return repository.fillMissingEnvironment(environment);
    }

    /** Counts for the dashboard tiles: total, then one entry per status. */
    @Transactional(readOnly = true)
    public Map<String, Long> statusSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("Total", repository.count());
        for (Status status : Status.values()) {
            summary.put(status.getLabel(), repository.countByStatus(status));
        }
        return summary;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> severitySummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            summary.put(severity.getLabel(), repository.countBySeverity(severity));
        }
        return summary;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> prioritySummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (Priority priority : Priority.values()) {
            summary.put(priority.getLabel(), repository.countByPriority(priority));
        }
        return summary;
    }

    /** P1 + P2: the bugs that should not be sitting in the queue. */
    @Transactional(readOnly = true)
    public long urgentCount() {
        return repository.countByPriority(Priority.P1) + repository.countByPriority(Priority.P2);
    }

    private void notifyStatusChange(Bug bug) {
        Long id = bug.getId();
        String title = bug.getTitle();
        switch (bug.getStatus()) {
            case FIXED -> notifications.notify(id, "fixed", bug.getReportedBy(),
                    "BUG-" + id + " was marked Fixed and is ready for retest: " + title);
            case RETEST -> notifications.notify(id, "fixed", bug.getReportedBy(),
                    "BUG-" + id + " is ready for your retest: " + title);
            case REOPENED -> notifications.notify(id, "reopened", bug.getAssignedTo(),
                    "BUG-" + id + " was reopened - the issue still exists: " + title);
            case CLOSED -> {
                notifications.notify(id, "closed", bug.getReportedBy(),
                        "BUG-" + id + " was closed: " + title);
                if (!equalText(bug.getReportedBy(), bug.getAssignedTo())) {
                    notifications.notify(id, "closed", bug.getAssignedTo(),
                            "BUG-" + id + " was closed: " + title);
                }
            }
            default -> { /* Open, Assigned and In Progress need no announcement */ }
        }
    }

    /**
     * Sorting happens in memory. Severity and priority are stored as strings, so
     * ORDER BY would sort them alphabetically (Critical, High, Low, Medium)
     * rather than by how bad they are; a comparator over the enum gets it right,
     * and this app's board is small enough that it costs nothing.
     */
    private static List<Bug> sorted(List<Bug> bugs, String sort) {
        if (sort == null || sort.isBlank() || "newest".equals(sort)) {
            return bugs;                                  // the query already did this
        }
        List<Bug> out = new ArrayList<>(bugs);
        switch (sort) {
            case "oldest" -> out.sort(Comparator.comparing(Bug::getCreatedAt));
            case "updated" -> out.sort(Comparator.comparing(Bug::getUpdatedAt).reversed());
            case "severity" -> out.sort(Comparator.comparing(Bug::getSeverity)
                    .thenComparing(Bug::getCreatedAt, Comparator.reverseOrder()));
            case "priority" -> out.sort(Comparator.comparing(Bug::getPriority)
                    .thenComparing(Bug::getCreatedAt, Comparator.reverseOrder()));
            case "status" -> out.sort(Comparator.comparing(Bug::getStatus)
                    .thenComparing(Bug::getCreatedAt, Comparator.reverseOrder()));
            case "title" -> out.sort(Comparator.comparing(Bug::getTitle, String.CASE_INSENSITIVE_ORDER));
            default -> { /* unknown sort: leave the newest-first order alone */ }
        }
        return out;
    }

    /** Lets "12", "#12" and "BUG-12" in the search box find bug 12. */
    private static Long idIn(String keyword) {
        if (keyword == null) {
            return null;
        }
        String digits = keyword.trim().replaceFirst("(?i)^(bug-|#)", "");
        try {
            return Long.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String label(Object enumValue) {
        if (enumValue instanceof Severity s) {
            return s.getLabel();
        }
        if (enumValue instanceof Status s) {
            return s.getLabel();
        }
        if (enumValue instanceof Priority p) {
            return p.getLabel();
        }
        if (enumValue instanceof Environment e) {
            return e.getLabel();
        }
        return enumValue == null ? null : String.valueOf(enumValue);
    }

    private static boolean equalText(String a, String b) {
        return java.util.Objects.equals(blankToNull(a), blankToNull(b));
    }
}
