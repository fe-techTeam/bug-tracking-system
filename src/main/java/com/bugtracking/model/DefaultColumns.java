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
 * the shape: Open → In Progress → On Hold → Ready for Test → Retest → Closed,
 * with the colours and the who-hears-about-it rules the app has always had, so
 * a project that has never been touched behaves exactly as it did before any
 * of this was editable.
 *
 * <p>The keys are the old enum constant names on purpose. Every bug already in
 * the database holds one of them, and {@code StatusMigration} maps the older
 * spellings onto them on the way in.
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
            new Seed("OPEN", "Open", ColumnColour.SLATE, false, ColumnNotify.NOBODY),
            new Seed("IN_PROGRESS", "In Progress", ColumnColour.BLUE, false, ColumnNotify.NOBODY),
            // Parked on something outside the team, so the people carrying it
            // are the ones who need telling.
            new Seed("ON_HOLD", "On Hold", ColumnColour.VIOLET, false, ColumnNotify.ASSIGNEES),
            // Past QA's door: not open work any more, and the reporter's turn.
            new Seed("READY_FOR_TEST", "Ready for Test", ColumnColour.TEAL, true, ColumnNotify.REPORTER),
            new Seed("RETEST", "Retest", ColumnColour.AMBER, true, ColumnNotify.REPORTER),
            new Seed("CLOSED", "Closed", ColumnColour.GREEN, true, ColumnNotify.EVERYONE));

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
