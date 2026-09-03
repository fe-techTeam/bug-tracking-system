package com.bugtracking.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The board every new project starts with, and the last thing standing between
 * a broken database and a blank screen.
 *
 * <p>This is the old {@code Status} enum, demoted. It is no longer a type — a
 * bug's status is a string key now, and which keys exist is a per-project
 * question answered by the {@code board_columns} table. What survives here is
 * the shape a new project opens on: Open → In Progress → Ready to test →
 * Testing → Closed, with On Hold parked at the end because it is off the track
 * rather than a step along it. Every one of them is renameable, recolourable
 * and movable from the moment the project exists.
 *
 * <p>The keys are the old enum constant names on purpose, and they do not
 * change when a label does. Every bug already in the database holds one of
 * them, {@code StatusMigration} maps the older spellings onto them on the way
 * in, and a key that moved would strand every bug carrying it. So "Retest"
 * being renamed to "Testing" is a label change and nothing else — {@code
 * RETEST} is what the rows still say, and no bug moves column for it.
 */
public final class DefaultColumns {

    /** Where a bug starts, and where a bug lands when its column is deleted out from under it. */
    public static final String FIRST_KEY = "OPEN";

    /** {@code tells} rather than {@code notify}: a record component may not be
     *  named after a method every Object already has. */
    private record Seed(String key, String label, ColumnColour colour,
                        boolean done, ColumnNotify tells) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("OPEN", "Open", ColumnColour.RED, false, ColumnNotify.NOBODY),
            new Seed("IN_PROGRESS", "In Progress", ColumnColour.BLUE, false, ColumnNotify.NOBODY),
            // Past QA's door: not open work any more, and the reporter's turn.
            new Seed("READY_FOR_TEST", "Ready to test", ColumnColour.BROWN, true, ColumnNotify.REPORTER),
            new Seed("RETEST", "Testing", ColumnColour.VIOLET, true, ColumnNotify.REPORTER),
            new Seed("CLOSED", "Closed", ColumnColour.GREEN, true, ColumnNotify.EVERYONE),
            // Last, not fourth: it is a siding, not a step. Parked on something
            // outside the team, so the people carrying it are the ones to tell.
            new Seed("ON_HOLD", "On Hold", ColumnColour.SLATE, false, ColumnNotify.ASSIGNEES));

    /**
     * The set this app shipped before the colours above. Only
     * {@code BoardColumnRestyle} reads it, to tell a board nobody has touched
     * from one somebody has.
     */
    public static List<String[]> stock() {
        return List.of(
                new String[]{"OPEN", "Open", "SLATE"},
                new String[]{"IN_PROGRESS", "In Progress", "BLUE"},
                new String[]{"ON_HOLD", "On Hold", "VIOLET"},
                new String[]{"READY_FOR_TEST", "Ready for Test", "TEAL"},
                new String[]{"RETEST", "Retest", "AMBER"},
                new String[]{"CLOSED", "Closed", "GREEN"});
    }

    private DefaultColumns() {
    }

    /** A fresh, unsaved set of columns for one project. */
    public static List<BoardColumn> forProject(String project) {
        List<BoardColumn> columns = new ArrayList<>(SEEDS.size());
        for (int i = 0; i < SEEDS.size(); i++) {
            Seed seed = SEEDS.get(i);
            columns.add(new BoardColumn(project, seed.key(), seed.label(),
                    seed.colour(), seed.done(), seed.tells(), i));
        }
        return columns;
    }

    /**
     * Turns a column name into the key a bug will store: uppercase, wordy bits
     * joined with underscores, and short enough for the column it lands in.
     * Not unique on its own — {@code BoardColumnService} settles collisions.
     */
    public static String keyFor(String label) {
        String key = label == null ? "" : label.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (key.isEmpty()) {
            key = "COLUMN";                       // a name of only punctuation
        }
        return key.length() > 30 ? key.substring(0, 30).replaceAll("_+$", "") : key;
    }
}
