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
            new String[]{"BUGS", "ENVIRONMENT", "20"});

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)          // before any backfill touches a row
    CommandLineRunner widenEnumColumns(JdbcTemplate jdbc) {
        return args -> {
            for (String[] column : ENUM_COLUMNS) {
                widen(jdbc, column[0], column[1], column[2]);
            }
        };
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
