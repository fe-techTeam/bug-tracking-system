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
 * "Client" became "Project". Hibernate adds the new PROJECT column on startup;
 * this copies the old CLIENT values across and then drops the dead column, so
 * no bug loses the thing it was raised against.
 *
 * <p>Runs once per database — after the first pass there is no CLIENT column
 * left to find.
 */
@Configuration
public class ProjectColumnMigration {

    private static final Logger log = LoggerFactory.getLogger(ProjectColumnMigration.class);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)     // after SchemaUpgrade, before the seeders
    CommandLineRunner migrateClientToProject(JdbcTemplate jdbc) {
        return args -> {
            if (!columnExists(jdbc, "BUGS", "CLIENT") || !columnExists(jdbc, "BUGS", "PROJECT")) {
                return;
            }

            int copied = jdbc.update("""
                    UPDATE BUGS SET PROJECT = CLIENT
                    WHERE PROJECT IS NULL AND CLIENT IS NOT NULL
                    """);
            jdbc.execute("ALTER TABLE BUGS DROP COLUMN CLIENT");

            log.info("Renamed client to project: carried {} value(s) across and dropped the old column.",
                    copied);
        };
    }

    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = ? AND COLUMN_NAME = ?
                """, Integer.class, table, column);
        return found != null && found > 0;
    }
}
