package com.bugtracking.service;

import com.bugtracking.model.BoardColumn;
import com.bugtracking.model.Bug;
import com.bugtracking.model.DefaultColumns;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every project's columns, read once and handed to the templates.
 *
 * <p>A status badge appears on the board, in the list, on the bug page and on
 * a person's page, and each one has to answer two questions the bug itself no
 * longer can: what does this status <em>say</em>, and what colour is it? The
 * bug stores a key; the wording and the colour live on a column, and which
 * column depends on the bug's project. This is that lookup, built once per
 * request in {@code GlobalModelAttributes} and reachable from any template as
 * {@code ${cols}} — {@code ${cols.column(bug)}} gives a template the column a
 * bug is sitting in.
 *
 * <p>Two cases need a decision rather than a lookup:
 *
 * <ul>
 *   <li><b>No project.</b> {@code /bugs?assignee=X} crosses projects, so there
 *       is no single board to draw. The columns merge: every project's columns
 *       folded together by key, keeping the earliest position each key is given
 *       anywhere. Projects that agree on a process — which is most of them,
 *       since they all start from the same six — merge back into that process.
 *   <li><b>A project with no board of its own.</b> A name that only exists on
 *       old bugs, or a row the seeder has not reached. It gets the defaults,
 *       unsaved, so the board renders instead of coming up blank.
 * </ul>
 */
public final class BoardColumns {

    private final Map<String, List<BoardColumn>> byProject = new LinkedHashMap<>();
    private final List<BoardColumn> merged;

    public BoardColumns(Collection<BoardColumn> all) {
        for (BoardColumn column : all) {
            byProject.computeIfAbsent(fold(column.getProject()), key -> new ArrayList<>()).add(column);
        }
        for (List<BoardColumn> columns : byProject.values()) {
            columns.sort(java.util.Comparator.comparingInt(BoardColumn::getPosition));
        }
        this.merged = merge(all);
    }

    /** One project's board, or the merged view when the scope is every project. */
    public List<BoardColumn> of(String project) {
        if (project == null || project.isBlank()) {
            return merged.isEmpty() ? DefaultColumns.forProject(null) : merged;
        }
        List<BoardColumn> columns = byProject.get(fold(project));
        return columns == null || columns.isEmpty() ? DefaultColumns.forProject(project) : columns;
    }

    /**
     * The column a bug is sitting in — the one lookup templates actually use.
     *
     * <p>Not another {@code of}: overloading it on Bug makes {@code of(null)}
     * ambiguous, and the whole-board case passes exactly that.
     */
    public BoardColumn column(Bug bug) {
        return bug == null ? null : find(bug.getProject(), bug.getStatus());
    }

    /**
     * The column holding this key, or null.
     *
     * <p>Falls back to the merged view before giving up: a bug can hold a key
     * its project no longer defines — it was filed against another project, or
     * moved between two that disagree — and a badge reading its raw key is
     * still better than a blank.
     */
    public BoardColumn find(String project, String statusKey) {
        if (statusKey == null || statusKey.isBlank()) {
            return null;
        }
        BoardColumn found = lookup(of(project), statusKey);
        return found != null ? found : lookup(merged, statusKey);
    }

    /** What the status says, falling back to the stored key so nothing renders empty. */
    public String label(String project, String statusKey) {
        BoardColumn column = find(project, statusKey);
        return column != null ? column.getLabel() : statusKey;
    }

    public String label(Bug bug) {
        return bug == null ? null : label(bug.getProject(), bug.getStatus());
    }

    /** What a template puts in {@code --c}. Grey for a key nothing defines. */
    public String token(String project, String statusKey) {
        BoardColumn column = find(project, statusKey);
        return column != null ? column.getToken() : "var(--muted)";
    }

    public String token(Bug bug) {
        return bug == null ? "var(--muted)" : token(bug.getProject(), bug.getStatus());
    }

    /**
     * Whether a bug counts as work in hand. An unknown key counts as open: a
     * bug nobody can place is certainly not finished.
     */
    public boolean openWork(String project, String statusKey) {
        BoardColumn column = find(project, statusKey);
        return column == null || column.isOpenWork();
    }

    public boolean openWork(Bug bug) {
        return bug == null || openWork(bug.getProject(), bug.getStatus());
    }

    /** Where the column sits on its board, counting from 1, for the status picker. */
    public int step(String project, String statusKey) {
        List<BoardColumn> columns = of(project);
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getStatusKey().equals(statusKey)) {
                return i + 1;
            }
        }
        return columns.size() + 1;
    }

    private static BoardColumn lookup(List<BoardColumn> columns, String statusKey) {
        for (BoardColumn column : columns) {
            if (column.getStatusKey().equals(statusKey)) {
                return column;
            }
        }
        return null;
    }

    /**
     * Every key any project defines, once each, in the order the projects that
     * define it put it in. Ties break on the key so the merged board does not
     * reshuffle itself between requests.
     */
    private static List<BoardColumn> merge(Collection<BoardColumn> all) {
        Map<String, BoardColumn> first = new LinkedHashMap<>();
        for (BoardColumn column : all) {
            BoardColumn kept = first.get(column.getStatusKey());
            if (kept == null || column.getPosition() < kept.getPosition()) {
                first.put(column.getStatusKey(), column);
            }
        }
        List<BoardColumn> out = new ArrayList<>(new LinkedHashSet<>(first.values()));
        out.sort(java.util.Comparator.comparingInt(BoardColumn::getPosition)
                .thenComparing(BoardColumn::getStatusKey));
        return out;
    }

    private static String fold(String project) {
        return project == null ? "" : project.trim().toLowerCase(Locale.ROOT);
    }
}
