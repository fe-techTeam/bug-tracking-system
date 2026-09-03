package com.bugtracking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Converts enum columns created by older versions of this app from H2's native
 * ENUM type to plain VARCHAR.
 *
 * <p>Why it exists: Hibernate's default mapping put the enum constants into the
 * column type itself, so the table remembered the five statuses that existed at
 * the time. Adding Assigned and Retest then failed with
 * "Value not permitted for column" on every query, and ddl-auto=update will not
 * widen an existing column. VARCHAR keeps no such list, so this only ever needs
 * to run once per database.
 */
@Configuration
public class SchemaUpgrade {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrade.class);

    /** table, column, width — the enum-backed columns this app has ever created. */
    private static final List<String[]> ENUM_COLUMNS = List.of(
            new String[]{"BUGS", "STATUS", "20"},
            new String[]{"BUGS", "SEVERITY", "20"},
            new String[]{"BUGS", "PRIORITY", "10"},
            new String[]{"BUGS", "ENVIRONMENT", "20"},
            // A board's own two. Missed when columns became rows, and found
            // the moment Red and Brown were added to the palette: the column
            // type still held the eight colours that existed when it was made.
            new String[]{"BOARD_COLUMNS", "COLOUR", "20"},
            new String[]{"BOARD_COLUMNS", "NOTIFY", "20"});

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)          // before any backfill touches a row
    CommandLineRunner widenEnumColumns(JdbcTemplate jdbc) {
        return args -> {
            for (String[] column : ENUM_COLUMNS) {
                widen(jdbc, column[0], column[1], column[2]);
            }
        };
    }

    /**
     * Widens {@code bugs.status} so a column you invented fits in it.
     *
     * <p>A status used to be one of six enum constants, the longest of which
     * was READY_FOR_TEST, so VARCHAR(20) was generous. A status is now the key
     * of a board column somebody named, and {@code ddl-auto=update} will not
     * widen a column that already exists — so without this, adding a column
     * called "Waiting on the client" saves fine and then fails on the first bug
     * moved into it.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)      // before anything writes a status
    CommandLineRunner widenStatusForCustomColumns(JdbcTemplate jdbc) {
        return args -> widenText(jdbc, "BUGS", "STATUS", 40);
    }

    /** Grows a VARCHAR that is narrower than it now needs to be. Never shrinks one. */
    private void widenText(JdbcTemplate jdbc, String table, String column, int width) {
        Integer current;
        try {
            current = jdbc.queryForObject("""
                    SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?
                    """, Integer.class, table, column);
        } catch (Exception e) {
            return;                              // column not there yet, or not a text type
        }
        if (current == null || current >= width) {
            return;
        }

        // H2 and Postgres disagree on the wording, and neither accepts the
        // other's, so the second is only tried once the first is refused.
        try {
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column
                    + " SET DATA TYPE VARCHAR(" + width + ")");
        } catch (Exception h2Failed) {
            try {
                jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column
                        + " TYPE VARCHAR(" + width + ")");
            } catch (Exception postgresFailed) {
                log.warn("Could not widen {}.{} to VARCHAR({}): {}. Board columns with long "
                        + "names will fail to hold bugs until it is widened.",
                        table, column, width, postgresFailed.getMessage());
                return;
            }
        }
        log.info("Widened {}.{} from VARCHAR({}) to VARCHAR({}) so custom board columns fit.",
                table, column, current, width);
    }

    /**
     * Lets {@code notifications.bug_id} be null.
     *
     * <p>Every notification used to be about a bug, so the column was created
     * NOT NULL. A mention in a project document is a notification about no bug
     * at all — it carries a link instead — and {@code ddl-auto=update} will
     * never relax a constraint on an existing column, so the first such mention
     * would fail to save on any database created before this.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)      // after the widening, before any backfill
    CommandLineRunner allowNotificationsWithoutABug(JdbcTemplate jdbc) {
        return args -> dropNotNull(jdbc, "NOTIFICATIONS", "BUG_ID");
    }

    private void dropNotNull(JdbcTemplate jdbc, String table, String column) {
        String nullable;
        try {
            nullable = jdbc.queryForObject("""
                    SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?
                    """, String.class, table, column);
        } catch (Exception e) {
            return;                              // column not there yet: nothing to relax
        }
        if (nullable == null || nullable.toUpperCase().startsWith("Y")) {
            return;                              // already nullable
        }

        // H2 and Postgres spell this differently and neither understands the
        // other, so the second is tried only when the first is rejected.
        try {
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " SET NULL");
        } catch (Exception h2Failed) {
            try {
                jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL");
            } catch (Exception postgresFailed) {
                log.warn("Could not make {}.{} nullable: {}. Mentions in project documents "
                        + "will fail to save until it is.", table, column, postgresFailed.getMessage());
                return;
            }
        }
        log.info("Relaxed {}.{} to nullable so a notification can point at a document.", table, column);
    }

    private void widen(JdbcTemplate jdbc, String table, String column, String width) {
        String dataType;
        try {
            dataType = jdbc.queryForObject("""
                    SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_NAME = ? AND COLUMN_NAME = ?
                    """, String.class, table, column);
        } catch (Exception e) {
            return;                              // column not there yet: nothing to upgrade
        }

        if (dataType == null || !dataType.toUpperCase().contains("ENUM")) {
            return;                              // already VARCHAR
        }

        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column
                + " SET DATA TYPE VARCHAR(" + width + ")");
        log.info("Upgraded {}.{} from ENUM to VARCHAR({}) so new constants are accepted.",
                table, column, width);
    }
}
