package com.bugtracking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * A bug used to have one assignee, in BUGS.ASSIGNED_TO. It now has a list, in
 * BUG_ASSIGNEES. This carries the old value across so nobody's queue empties
 * itself the first time the new build starts.
 *
 * <p>Runs once per database, and what records it as done is a row in
 * SCHEMA_MIGRATIONS rather than the shape of the data. That distinction is the
 * whole point: "this bug has no rows in bug_assignees" is what an un-migrated
 * bug looks like <em>and</em> what a deliberately unassigned one looks like, so
 * a guard reading only the data puts the old name back on every restart and
 * quietly undoes the user. ASSIGNED_TO is never cleared — nothing writes to it
 * any more, it costs nothing, and it is the only copy of the data if this ever
 * needs to be rolled back.
 */
@Configuration
public class AssigneeMigration {

    private static final Logger log = LoggerFactory.getLogger(AssigneeMigration.class);

    /** The name this migration is remembered by once it has been applied. */
    private static final String MIGRATION_ID = "assigned-to-list";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)   // after SchemaUpgrade, before the seeders
    CommandLineRunner migrateAssignedToList(JdbcTemplate jdbc) {
        return args -> {
            ensureMigrationLog(jdbc);
            if (alreadyApplied(jdbc)) {
                return;
            }

            // A database with no old column has nothing to carry across, ever.
            if (!columnExists(jdbc, "BUGS", "ASSIGNED_TO") || !tableExists(jdbc, "BUG_ASSIGNEES")) {
                markApplied(jdbc);
                return;
            }

            // Only this build ever writes BUG_ASSIGNEES, so a row in it means the
            // carry-over already happened on an earlier start — back when there
            // was no log to say so. Copying again would put back names the user
            // has removed since.
            if (alreadyCarriedOver(jdbc)) {
                markApplied(jdbc);
                return;
            }

            int copied = jdbc.update("""
                    INSERT INTO BUG_ASSIGNEES (BUG_ID, POSITION, ASSIGNEE)
                    SELECT b.ID, 0, TRIM(b.ASSIGNED_TO)
                    FROM BUGS b
                    WHERE b.ASSIGNED_TO IS NOT NULL
                      AND TRIM(b.ASSIGNED_TO) <> ''
                    """);
            markApplied(jdbc);

            if (copied > 0) {
                log.info("Carried {} single assignee(s) into the new bug_assignees table.", copied);
            }
        };
    }

    /**
     * The one-row-per-migration log. Created in plain SQL rather than mapped as
     * an entity: it has to exist before the first runner reads it, and nothing
     * outside this package has any business knowing about it.
     */
    private void ensureMigrationLog(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS SCHEMA_MIGRATIONS (
                    ID VARCHAR(100) PRIMARY KEY,
                    APPLIED_AT TIMESTAMP NOT NULL
                )
                """);
    }

    private boolean alreadyApplied(JdbcTemplate jdbc) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM SCHEMA_MIGRATIONS WHERE ID = ?", Integer.class, MIGRATION_ID);
        return found != null && found > 0;
    }

    private void markApplied(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO SCHEMA_MIGRATIONS (ID, APPLIED_AT) VALUES (?, ?)",
                MIGRATION_ID, Timestamp.valueOf(LocalDateTime.now()));
    }

    private boolean alreadyCarriedOver(JdbcTemplate jdbc) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM BUG_ASSIGNEES", Integer.class);
        return rows != null && rows > 0;
    }

    /*
     * INFORMATION_SCHEMA holds identifiers in whatever case the database keeps
     * them — upper on H2, lower on Postgres. Folding both sides is the only
     * comparison that finds the column on either, and without it the whole
     * migration silently no-ops on the supabase profile.
     */
    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = UPPER(?) AND UPPER(COLUMN_NAME) = UPPER(?)
                """, Integer.class, table, column);
        return found != null && found > 0;
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = UPPER(?)
                """, Integer.class, table);
        return found != null && found > 0;
    }
}
