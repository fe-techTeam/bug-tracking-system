package com.bugtracking.service;

import com.bugtracking.model.BoardColumn;
import com.bugtracking.model.Bug;
import com.bugtracking.model.ColumnNotify;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;
import com.bugtracking.repository.BugRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class BugService {

    private final BugRepository repository;
    private final BoardColumnService columns;
    private final BugHistoryService history;
    private final NotificationService notifications;
    private final CommentService comments;
    private final AttachmentService attachments;
    private final SupportingDocService docs;

    public BugService(BugRepository repository,
                      BoardColumnService columns,
                      BugHistoryService history,
                      NotificationService notifications,
                      CommentService comments,
                      AttachmentService attachments,
                      SupportingDocService docs) {
        this.repository = repository;
        this.columns = columns;
        this.history = history;
        this.notifications = notifications;
        this.comments = comments;
        this.attachments = attachments;
        this.docs = docs;
    }

    /** The original three-filter search, kept so existing callers are unaffected. */
    @Transactional(readOnly = true)
    public List<Bug> findAll(String status, Severity severity, String keyword) {
        return findAll(null, status, severity, null, null, null, keyword, null);
    }

    @Transactional(readOnly = true)
    public List<Bug> findAll(String project, String status, Severity severity,
                             Environment environment, String assignee, String reporter,
                             String keyword, String sort) {
        String trimmed = blankToNull(keyword);
        List<Bug> found = repository.search(blankToNull(project), status, severity,
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
                : repository.findByReportedByIgnoreCaseAndDeletedAtIsNullOrderByCreatedAtDesc(name.trim());
    }

    /** Everything sitting on one person's plate, newest first. */
    @Transactional(readOnly = true)
    public List<Bug> assignedTo(String name) {
        return name == null || name.isBlank()
                ? List.of()
                : repository.findAssignedTo(name.trim());
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
                ? repository.findByDeletedAtIsNullOrderByCreatedAtDesc()
                : repository.findByProjectIgnoreCaseAndDeletedAtIsNullOrderByCreatedAtDesc(scopeName);

        // Keyed by the column's key rather than its wording: two projects can
        // both have a column called Done and mean different columns, and the
        // whole-board view puts them side by side.
        BoardColumns board = columns.snapshot();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (BoardColumn column : board.of(scopeName)) {
            byStatus.put(column.getStatusKey(),
                    scope.stream().filter(b -> column.getStatusKey().equals(b.getStatus())).count());
        }
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity.getLabel(), scope.stream().filter(b -> b.getSeverity() == severity).count());
        }
        // "Urgent" used to be P1 + P2. With priority gone it is the top two
        // severities, still open — the same question, asked of the field that
        // is left: what should not be sitting in this queue?
        long urgent = scope.stream()
                .filter(b -> b.getSeverity() == Severity.CRITICAL || b.getSeverity() == Severity.HIGH)
                .filter(board::openWork)
                .count();
        long open = scope.stream().filter(board::openWork).count();
        long maxStatus = byStatus.values().stream().mapToLong(Long::longValue).max().orElse(0);

        return new Dashboard(scopeName, scope.size(), byStatus, bySeverity,
                urgent, open, maxStatus, scope.stream().map(Bug::getId).toList());
    }

    /** A live bug. A trashed one reads as gone, because that is what it is. */
    @Transactional(readOnly = true)
    public Bug findById(Long id) {
        return repository.findById(id)
                .filter(bug -> !bug.isDeleted())
                .orElseThrow(() -> new NoSuchElementException("No bug found with id " + id));
    }

    public Bug save(Bug bug) {
        return save(bug, null);
    }

    public Bug save(Bug bug, String actor) {
        // The form only ever offers columns this project has, but the JSON API
        // takes whatever it is handed — so a status that is not a column on
        // this board becomes the board's first column rather than a bug that
        // renders nowhere.
        bug.setStatus(columns.keyOn(bug.getProject(), bug.getStatus()));

        Bug saved = repository.save(bug);
        BoardColumns board = columns.snapshot();
        String by = BugHistoryService.actor(actor);
        history.record(saved.getId(), "created", null, board.label(saved), by);
        for (String person : saved.getAssignees()) {
            if (!equalText(person, by)) {
                notifications.notify(saved.getId(), "assigned", person,
                        "BUG-" + saved.getId() + " was raised and assigned to you: " + saved.getTitle());
            }
        }
        return saved;
    }

    public Bug update(Long id, Bug changes) {
        return update(id, changes, null);
    }

    /** Copies editable fields onto the managed entity so timestamps stay intact. */
    public Bug update(Long id, Bug changes, String actor) {
        Bug existing = findById(id);

        // Moving a bug to another project can leave it holding a column key
        // that project does not have — the two boards need not agree. Rather
        // than strand it in a column nothing draws, it arrives in the first
        // column of the board it has moved to.
        changes.setStatus(columns.keyOn(changes.getProject(), changes.getStatus()));
        BoardColumns board = columns.snapshot();

        history.recordIfChanged(id, "title", existing.getTitle(), changes.getTitle(), actor);
        history.recordIfChanged(id, "severity", label(existing.getSeverity()), label(changes.getSeverity()), actor);
        history.recordIfChanged(id, "environment", label(existing.getEnvironment()), label(changes.getEnvironment()), actor);
        // The wording as it stands today, not the key: the trail should read
        // the way the board read when the move happened, and renaming a column
        // afterwards must not rewrite what people saw.
        history.recordIfChanged(id, "status",
                board.label(existing.getProject(), existing.getStatus()),
                board.label(changes.getProject(), changes.getStatus()), actor);
        history.recordIfChanged(id, "project", existing.getProject(), changes.getProject(), actor);
        history.recordIfChanged(id, "module", existing.getModule(), changes.getModule(), actor);
        history.recordIfChanged(id, "assigned", existing.getAssigneesLabel(),
                changes.getAssigneesLabel(), actor);
        history.recordIfChanged(id, "blocked", blockerLabel(existing.getBlockedBy()),
                blockerLabel(changes.getBlockedBy()), actor);

        List<String> assigneesBefore = List.copyOf(existing.getAssignees());
        String before = existing.getStatus();

        existing.setTitle(changes.getTitle());
        existing.setDescription(changes.getDescription());
        // The report is one box now, and the form no longer posts these three.
        // An absent field arrives as null, so assigning it straight across would
        // wipe the steps and results off every bug raised before the change the
        // first time anyone edited it. Only a value actually sent replaces one.
        if (changes.getStepsToReproduce() != null) {
            existing.setStepsToReproduce(changes.getStepsToReproduce());
        }
        if (changes.getExpectedResult() != null) {
            existing.setExpectedResult(changes.getExpectedResult());
        }
        if (changes.getActualResult() != null) {
            existing.setActualResult(changes.getActualResult());
        }
        existing.setSeverity(changes.getSeverity());
        existing.setEnvironment(changes.getEnvironment());
        existing.setStatus(changes.getStatus());
        existing.setProject(changes.getProject());
        existing.setModule(changes.getModule());
        existing.setReportedBy(changes.getReportedBy());
        existing.setAssignees(changes.getAssignees());
        existing.setBlockedBy(validBlocker(id, changes.getBlockedBy()));

        Bug saved = repository.save(existing);
        String by = BugHistoryService.actor(actor);

        // Only the people newly put on it hear about it; the ones already
        // there have had their notification.
        Set<String> told = new LinkedHashSet<>();
        for (String person : saved.getAssignees()) {
            if (!contains(assigneesBefore, person) && !equalText(person, by)) {
                notifications.notify(id, "assigned", person,
                        "BUG-" + id + " is now assigned to you: " + saved.getTitle());
                told.add(person);
            }
        }
        for (String person : assigneesBefore) {
            if (!contains(saved.getAssignees(), person) && !equalText(person, by)) {
                notifications.notify(id, "unassigned", person,
                        "BUG-" + id + " is no longer assigned to you: " + saved.getTitle());
            }
        }
        if (!Objects.equals(before, saved.getStatus())) {
            notifyStatusChange(saved, board, told);
        }
        return saved;
    }

    public Bug changeStatus(Long id, String status) {
        return changeStatus(id, status, null);
    }

    /**
     * Moves a bug to another column of its own board.
     *
     * <p>A key this project's board does not have is refused rather than
     * quietly corrected: dropping a card is a deliberate act, and silently
     * landing it somewhere else would be worse than saying no. Raising a bug
     * is the forgiving case — see {@link #save} — because there the caller may
     * simply not have said.
     */
    public Bug changeStatus(Long id, String status, String actor) {
        Bug bug = findById(id);
        String asked = blankToNull(status);
        String wanted = columns.keyOn(bug.getProject(), asked);
        if (asked == null || !wanted.equals(asked)) {
            throw new IllegalArgumentException("There is no such column on "
                    + bug.getProject() + "'s board.");
        }

        String before = bug.getStatus();
        if (wanted.equals(before)) {
            return bug;
        }
        bug.setStatus(wanted);
        Bug saved = repository.save(bug);

        BoardColumns board = columns.snapshot();
        history.record(id, "status", board.label(bug.getProject(), before), board.label(saved), actor);
        notifyStatusChange(saved, board, Set.of());
        return saved;
    }

    /** Single-name assign, kept for the JSON API and anything scripted. */
    public Bug assign(Long id, String assignee, String actor) {
        return assign(id, blankToNull(assignee) == null ? List.of() : List.of(assignee.trim()), actor);
    }

    /**
     * Puts a set of people on a bug. The status is left alone: there is no
     * "Assigned" stage any more, and moving a bug along is a decision of its
     * own rather than a side effect of picking someone. Both ends of the
     * change are announced — the people who were not on it already, and the
     * people taken off it.
     */
    public Bug assign(Long id, List<String> people, String actor) {
        Bug bug = findById(id);
        List<String> before = List.copyOf(bug.getAssignees());
        bug.setAssignees(people);

        Bug saved = repository.save(bug);
        String by = BugHistoryService.actor(actor);
        history.recordIfChanged(id, "assigned", join(before), saved.getAssigneesLabel(), by);
        for (String person : saved.getAssignees()) {
            if (!contains(before, person) && !equalText(person, by)) {
                notifications.notify(id, "assigned", person,
                        "BUG-" + id + " was assigned to you: " + saved.getTitle());
            }
        }
        for (String person : before) {
            if (!contains(saved.getAssignees(), person) && !equalText(person, by)) {
                notifications.notify(id, "unassigned", person,
                        "BUG-" + id + " is no longer assigned to you: " + saved.getTitle());
            }
        }
        return saved;
    }

    /**
     * Records which open bug is holding this one up. A blocker that does not
     * exist, is already closed, or is the bug itself is refused rather than
     * stored — a cycle of two bugs blocking each other helps nobody.
     */
    public Bug block(Long id, Long blockerId, String actor) {
        Bug bug = findById(id);
        Long before = bug.getBlockedBy();
        bug.setBlockedBy(validBlocker(id, blockerId));
        Bug saved = repository.save(bug);
        history.recordIfChanged(id, "blocked", blockerLabel(before),
                blockerLabel(saved.getBlockedBy()), actor);
        return saved;
    }

    /**
     * The open bugs this one could be waiting on. Which columns count as open
     * is a per-project setting, so the filtering happens here rather than in
     * the query — one fetch, then a predicate the columns can answer.
     */
    @Transactional(readOnly = true)
    public List<Bug> blockerOptions(Long exclude) {
        BoardColumns board = columns.snapshot();
        return repository.findLiveBugs(exclude).stream().filter(board::openWork).toList();
    }

    /**
     * The blocking bugs behind a list, keyed by id, so the board can draw the
     * blocked marker without a query per card.
     */
    @Transactional(readOnly = true)
    public Map<Long, Bug> blockersFor(List<Bug> bugs) {
        List<Long> ids = bugs.stream()
                .map(Bug::getBlockedBy)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(Bug::getId, Function.identity()));
    }

    /** null unless the id names a real, still-open, different bug. */
    private Long validBlocker(Long selfId, Long blockerId) {
        if (blockerId == null || blockerId.equals(selfId)) {
            return null;
        }
        BoardColumns board = columns.snapshot();
        return repository.findById(blockerId)
                .filter(board::openWork)
                .map(Bug::getId)
                .orElse(null);
    }

    private String blockerLabel(Long blockerId) {
        return blockerId == null ? null : "BUG-" + blockerId;
    }

    private static String join(List<String> people) {
        return people.isEmpty() ? null : String.join(", ", people);
    }

    /**
     * Moves a bug to the trash. Nothing is destroyed: the row, its comments,
     * its files and its history stay put and every query stops looking at
     * them, so {@link #restore} brings the whole thing back intact.
     *
     * <p>Blockers are deliberately not cleared either — a bug waiting on this
     * one stops reading as blocked while it is in the bin, and starts again if
     * it comes back.
     */
    public Bug delete(Long id, String actor) {
        Bug bug = findById(id);
        bug.setDeletedAt(java.time.LocalDateTime.now());
        bug.setDeletedBy(BugHistoryService.actor(actor));
        Bug saved = repository.save(bug);
        history.record(id, "deleted", null, null, saved.getDeletedBy());
        return saved;
    }

    /** Kept for callers that never had an actor to pass. */
    public void delete(Long id) {
        delete(id, null);
    }

    /** Takes a bug back out of the trash, exactly as it was. */
    public Bug restore(Long id, String actor) {
        Bug bug = repository.findAnyById(id)
                .orElseThrow(() -> new NoSuchElementException("No bug found with id " + id));
        if (!bug.isDeleted()) {
            return bug;
        }
        bug.setDeletedAt(null);
        bug.setDeletedBy(null);
        Bug saved = repository.save(bug);
        history.record(id, "restored", null, null, BugHistoryService.actor(actor));
        return saved;
    }

    /**
     * Destroys a bug and everything hanging off it, files included. Only ever
     * reached from the trash, and only for something already thrown away.
     */
    public void purge(Long id) {
        Bug bug = repository.findAnyById(id)
                .orElseThrow(() -> new NoSuchElementException("No bug found with id " + id));
        if (!bug.isDeleted()) {
            throw new IllegalArgumentException("BUG-" + id + " is not in the trash.");
        }
        attachments.deleteForBug(id);
        comments.deleteForBug(id);
        docs.deleteForBug(id);
        history.deleteForBug(id);
        notifications.deleteForBug(id);
        repository.clearBlocker(id);        // nothing stays blocked by a bug that is gone
        repository.deleteById(id);
    }

    /** What is in the bin, most recently thrown away first. */
    @Transactional(readOnly = true)
    public List<Bug> trash() {
        return repository.findTrash();
    }

    @Transactional(readOnly = true)
    public long trashCount() {
        return repository.countByDeletedAtIsNotNull();
    }

    /** Backfills bugs that predate the project field. Returns how many were touched. */
    public int fillMissingProject(String project) {
        return repository.fillMissingProject(project);
    }

    /** Same for the environment field, added later still. */
    public int fillMissingEnvironment(Environment environment) {
        return repository.fillMissingEnvironment(environment);
    }

    /**
     * Counts for the JSON dashboard: total, then one entry per column.
     *
     * <p>Keyed by wording, since this is read by people and scripts rather than
     * drawn — and summed rather than overwritten, because two projects are free
     * to give two different columns the same name.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> statusSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("Total", (long) repository.findByDeletedAtIsNullOrderByCreatedAtDesc().size());
        for (BoardColumn column : columns.snapshot().of((String) null)) {
            summary.merge(column.getLabel(),
                    repository.countByStatusAndDeletedAtIsNull(column.getStatusKey()), Long::sum);
        }
        return summary;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> severitySummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            summary.put(severity.getLabel(), repository.countBySeverityAndDeletedAtIsNull(severity));
        }
        return summary;
    }

    /**
     * Announces a move to the people it concerns, skipping anyone this same
     * save has already reached. Being put on a bug and being told where it
     * stands are one piece of news to whoever receives them.
     *
     * <p>This used to be a switch over the six statuses, which said the move
     * out loud — "is ready for test", "was closed". A column is something you
     * name now, so who hears about it is a setting on the column
     * ({@link ColumnNotify}) and the sentence is built from the column's own
     * wording. Seeded to match: Open and In Progress announce nothing, On Hold
     * tells the people on it, Ready for Test and Retest tell the reporter, and
     * Closed tells everyone the bug names.
     */
    private void notifyStatusChange(Bug bug, BoardColumns board, Set<String> alreadyTold) {
        BoardColumn column = board.find(bug.getProject(), bug.getStatus());
        if (column == null || column.getNotify().isSilent()) {
            return;
        }

        Long id = bug.getId();
        ColumnNotify who = column.getNotify();
        String type = who.getType();
        String message = "BUG-" + id + " moved to " + column.getLabel() + ": " + bug.getTitle();

        if (who == ColumnNotify.REPORTER || who == ColumnNotify.EVERYONE) {
            tell(alreadyTold, id, type, bug.getReportedBy(), message);
        }
        if (who == ColumnNotify.ASSIGNEES || who == ColumnNotify.EVERYONE) {
            for (String person : bug.getAssignees()) {
                // The reporter has already been told when the column tells both,
                // and being on your own bug is not two pieces of news.
                if (who == ColumnNotify.ASSIGNEES || !equalText(bug.getReportedBy(), person)) {
                    tell(alreadyTold, id, type, person, message);
                }
            }
        }
    }

    private void tell(Set<String> alreadyTold, Long bugId, String type, String person, String message) {
        if (alreadyTold.stream().noneMatch(name -> equalText(name, person))) {
            notifications.notify(bugId, type, person, message);
        }
    }

    /**
     * Sorting happens in memory. Severity is stored as a string, so ORDER BY
     * would sort alphabetically (Critical, High, Low, Medium) rather than by how
     * bad it is; a comparator over the enum gets it right, and this app's board
     * is small enough that it costs nothing.
     */
    private List<Bug> sorted(List<Bug> bugs, String sort) {
        if (sort == null || sort.isBlank() || "newest".equals(sort)) {
            return bugs;                                  // the query already did this
        }
        List<Bug> out = new ArrayList<>(bugs);
        switch (sort) {
            case "oldest" -> out.sort(Comparator.comparing(Bug::getCreatedAt));
            case "updated" -> out.sort(Comparator.comparing(Bug::getUpdatedAt).reversed());
            case "severity" -> out.sort(Comparator.comparing(Bug::getSeverity)
                    .thenComparing(Bug::getCreatedAt, Comparator.reverseOrder()));
            case "status" -> {
                // By where the column sits on its board, not by the key: sorting
                // on the stored string would put Closed before In Progress.
                BoardColumns board = columns.snapshot();
                out.sort(Comparator.comparingInt((Bug b) -> board.step(b.getProject(), b.getStatus()))
                        .thenComparing(Bug::getCreatedAt, Comparator.reverseOrder()));
            }
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
        if (enumValue instanceof Environment e) {
            return e.getLabel();
        }
        return enumValue == null ? null : String.valueOf(enumValue);
    }

    /**
     * Two names for the same person? Trimmed and case-blind, because that is
     * how the board, the repository queries and the assignee list all match
     * one — "nishana r" and "Nishana R" are not two people.
     */
    private static boolean equalText(String a, String b) {
        String left = blankToNull(a);
        String right = blankToNull(b);
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    /** {@link List#contains} over names, matched the same forgiving way. */
    private static boolean contains(List<String> names, String person) {
        return names.stream().anyMatch(name -> equalText(name, person));
    }
}
