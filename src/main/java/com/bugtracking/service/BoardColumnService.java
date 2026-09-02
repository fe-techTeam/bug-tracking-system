package com.bugtracking.service;

import com.bugtracking.model.BoardColumn;
import com.bugtracking.model.ColumnColour;
import com.bugtracking.model.ColumnNotify;
import com.bugtracking.model.DefaultColumns;
import com.bugtracking.repository.BoardColumnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Adding, renaming, recolouring, reordering and removing the columns of a
 * project's board.
 *
 * <p>The rules worth knowing:
 *
 * <ul>
 *   <li>A rename touches one row. The key a bug stores is generated once, when
 *       the column is created, and is never rewritten — so calling a column
 *       something else costs nothing and loses nothing, and the history still
 *       reads with the wording that was on screen at the time.
 *   <li>A column cannot be removed while it still holds bugs unless you say
 *       where they go. That is the one thing deletion needs a decision about,
 *       so it is the one thing it asks for.
 *   <li>A board is never empty. The last column of a project will not delete —
 *       there would be nowhere for a bug to be.
 * </ul>
 */
@Service
@Transactional
public class BoardColumnService {

    private final BoardColumnRepository repository;

    public BoardColumnService(BoardColumnRepository repository) {
        this.repository = repository;
    }

    /* ------------------------------ reading ------------------------------ */

    /** Every project's columns in one object, for the templates. */
    @Transactional(readOnly = true)
    public BoardColumns snapshot() {
        return new BoardColumns(repository.findAllByOrderByProjectAscPositionAsc());
    }

    /** One project's board, left to right. Seeds the defaults if it has none. */
    public List<BoardColumn> forProject(String project) {
        String name = clean(project);
        if (name == null) {
            return snapshot().of((String) null);
        }
        List<BoardColumn> columns = repository.findByProjectIgnoreCaseOrderByPositionAsc(name);
        return columns.isEmpty() ? seed(name) : columns;
    }

    /** The columns of every project that has any, grouped for the settings editor. */
    @Transactional(readOnly = true)
    public Map<String, List<BoardColumn>> byProject() {
        Map<String, List<BoardColumn>> grouped = new LinkedHashMap<>();
        for (BoardColumn column : repository.findAllByOrderByProjectAscPositionAsc()) {
            grouped.computeIfAbsent(column.getProject(), key -> new ArrayList<>()).add(column);
        }
        return grouped;
    }

    /** How many live bugs are sitting in a column. */
    @Transactional(readOnly = true)
    public long bugsIn(BoardColumn column) {
        return repository.countBugsIn(column.getProject(), column.getStatusKey());
    }

    /** Live bug counts for a project's columns, keyed by column id. */
    @Transactional(readOnly = true)
    public Map<Long, Long> usageIn(String project) {
        Map<Long, Long> usage = new LinkedHashMap<>();
        for (BoardColumn column : repository.findByProjectIgnoreCaseOrderByPositionAsc(clean(project))) {
            usage.put(column.getId(), bugsIn(column));
        }
        return usage;
    }

    /**
     * The key a bug on this project should hold, given one that may not belong
     * here. Used when a bug is filed, and again when a bug is moved to a
     * project whose board has no such column: rather than leaving it in a
     * column that does not exist, it lands in the project's first one.
     */
    public String keyOn(String project, String wanted) {
        List<BoardColumn> columns = forProject(project);
        if (wanted != null && !wanted.isBlank()) {
            for (BoardColumn column : columns) {
                if (column.getStatusKey().equals(wanted.trim())) {
                    return column.getStatusKey();
                }
            }
        }
        return columns.isEmpty() ? DefaultColumns.FIRST_KEY : columns.get(0).getStatusKey();
    }

    /** True when this project has a board of its own rather than falling back. */
    @Transactional(readOnly = true)
    public boolean hasBoard(String project) {
        String name = clean(project);
        return name != null && repository.countByProjectIgnoreCase(name) > 0;
    }

    /** True when this project's board actually has that column. */
    @Transactional(readOnly = true)
    public boolean has(String project, String statusKey) {
        return statusKey != null
                && repository.existsByProjectIgnoreCaseAndStatusKey(clean(project), statusKey.trim());
    }

    @Transactional(readOnly = true)
    public BoardColumn findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No board column found with id " + id));
    }

    /* ------------------------------ writing ------------------------------ */

    /** Gives a project the six columns this app has always had. */
    public List<BoardColumn> seed(String project) {
        String name = clean(project);
        if (name == null || repository.countByProjectIgnoreCase(name) > 0) {
            return repository.findByProjectIgnoreCaseOrderByPositionAsc(name);
        }
        return repository.saveAll(DefaultColumns.forProject(name));
    }

    public BoardColumn add(String project, String label, ColumnColour colour,
                           boolean done, ColumnNotify notify) {
        String name = clean(project);
        if (name == null) {
            throw new IllegalArgumentException("A column belongs to a project — pick one first.");
        }
        String title = clean(label);
        if (title == null) {
            throw new IllegalArgumentException("A column needs a name.");
        }

        List<BoardColumn> columns = forProject(name);
        for (BoardColumn column : columns) {
            if (column.getLabel().equalsIgnoreCase(title)) {
                throw new IllegalArgumentException(name + " already has a column called " + column.getLabel() + ".");
            }
        }

        BoardColumn column = new BoardColumn(name, uniqueKey(columns, title), title,
                colour == null ? ColumnColour.SLATE : colour,
                done, notify == null ? ColumnNotify.NOBODY : notify,
                columns.size());
        return repository.save(column);
    }

    /**
     * Everything about a column except where it sits and what bugs call it.
     *
     * <p>Any argument left null means "leave it alone", so the rename box in a
     * column's own menu can send a name and nothing else, and a colour swatch
     * can send a colour and nothing else, without either quietly resetting what
     * it did not mention. The Settings editor sends all four. A label that is
     * present but blank is still an error — that is somebody clearing the field,
     * not somebody omitting it.
     */
    public BoardColumn edit(Long id, String label, ColumnColour colour,
                            Boolean done, ColumnNotify notify) {
        BoardColumn column = findById(id);
        if (label != null) {
            String title = clean(label);
            if (title == null) {
                throw new IllegalArgumentException("A column needs a name.");
            }
            for (BoardColumn other : repository.findByProjectIgnoreCaseOrderByPositionAsc(column.getProject())) {
                if (!other.getId().equals(id) && other.getLabel().equalsIgnoreCase(title)) {
                    throw new IllegalArgumentException(column.getProject()
                            + " already has a column called " + other.getLabel() + ".");
                }
            }
            column.setLabel(title);
        }
        if (colour != null) {
            column.setColour(colour);
        }
        if (done != null) {
            column.setDoneState(done);
        }
        if (notify != null) {
            column.setNotify(notify);
        }
        return repository.save(column);
    }

    /**
     * Slides a column one place left or right. The keyboard-and-no-JavaScript
     * half of reordering — {@link #reorder} is what dragging a column head
     * ends up calling.
     */
    public BoardColumn move(Long id, int delta) {
        BoardColumn column = findById(id);
        List<BoardColumn> columns = repository
                .findByProjectIgnoreCaseOrderByPositionAsc(column.getProject());
        int at = indexOf(columns, id);
        int to = at + Integer.signum(delta);
        if (at < 0 || to < 0 || to >= columns.size()) {
            return column;                        // already at the end it was pushed towards
        }
        columns.add(to, columns.remove(at));
        renumber(columns);
        repository.saveAll(columns);
        return column;
    }

    /**
     * Sets a whole board's order at once, from a list of column ids.
     *
     * <p>Ids the project does not own are ignored and columns the list forgot
     * are appended in the order they already had, so a stale page cannot drop a
     * column or adopt someone else's.
     */
    public void reorder(String project, List<Long> ids) {
        List<BoardColumn> columns = repository
                .findByProjectIgnoreCaseOrderByPositionAsc(clean(project));
        if (columns.isEmpty()) {
            return;
        }
        Set<Long> wanted = new LinkedHashSet<>(ids == null ? List.of() : ids);
        List<BoardColumn> ordered = new ArrayList<>(columns.size());
        for (Long id : wanted) {
            int at = indexOf(columns, id);
            if (at >= 0) {
                ordered.add(columns.get(at));
            }
        }
        for (BoardColumn column : columns) {
            if (!ordered.contains(column)) {
                ordered.add(column);
            }
        }
        renumber(ordered);
        repository.saveAll(ordered);
    }

    /**
     * Removes a column, first emptying it into another one.
     *
     * <p>{@code moveToId} may be null only when the column is already empty.
     * Trashed bugs move too — one restored later should not come back into a
     * column that no longer exists.
     */
    public String remove(Long id, Long moveToId) {
        BoardColumn column = findById(id);
        List<BoardColumn> columns = repository
                .findByProjectIgnoreCaseOrderByPositionAsc(column.getProject());
        if (columns.size() <= 1) {
            throw new IllegalArgumentException(
                    "A board needs at least one column, so " + column.getLabel() + " has to stay.");
        }

        long held = bugsIn(column);
        BoardColumn target = null;
        if (moveToId != null) {
            target = findById(moveToId);
            if (!target.getProject().equalsIgnoreCase(column.getProject())) {
                throw new IllegalArgumentException("Bugs can only be moved to a column on the same board.");
            }
            if (target.getId().equals(column.getId())) {
                throw new IllegalArgumentException("Pick a different column for the bugs in "
                        + column.getLabel() + ".");
            }
        } else if (held > 0) {
            throw new IllegalArgumentException(column.getLabel() + " holds " + held
                    + " bug" + (held == 1 ? "" : "s") + ". Say which column they move to.");
        }

        int moved = target == null ? 0
                : repository.moveBugs(column.getProject(), column.getStatusKey(), target.getStatusKey());

        columns.remove(indexOf(columns, id));
        renumber(columns);
        repository.saveAll(columns);
        repository.delete(column);

        if (moved > 0) {
            return column.getLabel() + " is gone — " + moved + " bug"
                    + (moved == 1 ? "" : "s") + " moved to " + target.getLabel() + ".";
        }
        return column.getLabel() + " is gone.";
    }

    /** Drops a whole board, for a project being removed outright. */
    public void removeProject(String project) {
        repository.deleteAll(repository.findByProjectIgnoreCaseOrderByPositionAsc(clean(project)));
    }

    /* ------------------------------ helpers ------------------------------ */

    /**
     * A key nothing on this board is already using. Two columns called the same
     * thing are refused above, but "Won't fix" and "Wont fix" both slug down to
     * WONT_FIX, and a key is what a bug stores — a collision would silently
     * merge two columns.
     */
    private static String uniqueKey(List<BoardColumn> columns, String label) {
        String base = DefaultColumns.keyFor(label);
        Set<String> taken = new LinkedHashSet<>();
        for (BoardColumn column : columns) {
            taken.add(column.getStatusKey());
        }
        String key = base;
        for (int n = 2; taken.contains(key); n++) {
            key = base + "_" + n;
        }
        return key;
    }

    private static int indexOf(List<BoardColumn> columns, Long id) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static void renumber(List<BoardColumn> columns) {
        for (int i = 0; i < columns.size(); i++) {
            columns.get(i).setPosition(i);
        }
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** Only used by the seeder, which works in folded names. */
    static String fold(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
