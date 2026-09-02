package com.bugtracking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The status list changed: Assigned and Reopened were dropped, Fixed became
 * Ready for Test, and On Hold was added.
 *
 * <p>Status is stored as the enum's name, so a row still holding "FIXED" would
 * blow up the moment Hibernate tried to read it. This rewrites those values in
 * plain SQL — no entity mapping involved — and runs before anything reads a
 * bug, so the app never sees a value it cannot understand.
 *
 * <p>Runs once per database: after the first pass there are no old values left
 * to match.
 */
@Configuration
public class StatusMigration {

    private static final Logger log = LoggerFactory.getLogger(StatusMigration.class);

    /**
     * Old value → new value.
     *
     * <p>Assigned becomes Open rather than In Progress: it meant somebody owned
     * it, not that work had started, and who owns it is on the bug anyway.
     * Reopened becomes In Progress, which is what reopening a bug asks for.
     */
    private static final String[][] REMAP = {
            {"ASSIGNED", "OPEN"},
            {"REOPENED", "IN_PROGRESS"},
            {"FIXED", "READY_FOR_TEST"},
    };

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 5)      // after SchemaUpgrade widens the column
    CommandLineRunner migrateStatuses(JdbcTemplate jdbc) {
        return args -> {
            if (!tableExists(jdbc, "BUGS")) {
                return;
            }
            int total = 0;
            for (String[] pair : REMAP) {
                int moved = jdbc.update("UPDATE BUGS SET STATUS = ? WHERE STATUS = ?", pair[1], pair[0]);
                if (moved > 0) {
                    log.info("Moved {} bug(s) from {} to {}.", moved, pair[0], pair[1]);
                    total += moved;
                }
            }
            if (total > 0) {
                log.info("Status migration touched {} bug(s) in total.", total);
            }
        };
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?
                """, Integer.class, table);
        return found != null && found > 0;
    }
}
