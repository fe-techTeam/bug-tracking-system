package com.bugtracking.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Folds the three retired report fields into the one description box.
 *
 * <p>A bug used to be raised as four separate boxes — description, steps to
 * reproduce, expected result, actual result. It is one box now, so the other
 * three no longer exist on the entity. The text people wrote in them does
 * exist, on every bug raised before the change, and dropping the fields without
 * this would leave it in the database where nothing could ever read it again.
 *
 * <p>So each row that still holds any of the three has them appended to its
 * description, in the order the form used to ask for them, and the old columns
 * are then emptied. Emptying them is what makes a second run do nothing, which
 * matters because this runs on every startup.
 *
 * <p>Postgres does the same fold in {@code V3__fold_report_into_description.sql}
 * and drops the columns outright, so there this finds nothing to do — hence the
 * missing columns being a quiet no-op rather than a failure. H2 keeps its
 * columns (ddl-auto never drops one); they stay behind, empty and unmapped.
 */
@Configuration
public class LegacyReportMerge {

    private static final Logger log = LoggerFactory.getLogger(LegacyReportMerge.class);

    /** The description column's width — the fold has to fit back into it. */
    private static final int MAX_DESCRIPTION = 4000;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 15)     // after SchemaUpgrade, before the seeders
    CommandLineRunner foldLegacyReportFields(JdbcTemplate jdbc) {
        return args -> {
            if (!hasLegacyColumns(jdbc)) {
                return;
            }

            List<Object[]> updates = new ArrayList<>();
            int truncated = 0;

            List<Object[]> rows = jdbc.query("""
                    SELECT id, description, steps_to_reproduce, expected_result, actual_result
                    FROM bugs
                    WHERE steps_to_reproduce IS NOT NULL
                       OR expected_result IS NOT NULL
                       OR actual_result IS NOT NULL
                    """, (rs, i) -> new Object[]{
                            rs.getLong("id"), rs.getString("description"),
                            rs.getString("steps_to_reproduce"),
                            rs.getString("expected_result"),
                            rs.getString("actual_result")});

            for (Object[] row : rows) {
                String folded = fold((String) row[1], (String) row[2], (String) row[3], (String) row[4]);
                if (folded.length() > MAX_DESCRIPTION) {
                    folded = folded.substring(0, MAX_DESCRIPTION - 1) + "…";
                    truncated++;
                }
                updates.add(new Object[]{folded, row[0]});
            }

            if (updates.isEmpty()) {
                return;
            }

            jdbc.batchUpdate("""
                    UPDATE bugs SET description = ?, steps_to_reproduce = NULL,
                                    expected_result = NULL, actual_result = NULL
                    WHERE id = ?
                    """, updates);

            log.info("Folded steps, expected and actual into the description of {} bug(s) "
                    + "raised before the report became one box.", updates.size());
            if (truncated > 0) {
                log.warn("{} of them were longer than {} characters together and were cut to fit.",
                        truncated, MAX_DESCRIPTION);
            }
        };
    }

    /** True only when all three columns are still there to be read. */
    private boolean hasLegacyColumns(JdbcTemplate jdbc) {
        try {
            Integer found = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE UPPER(TABLE_NAME) = 'BUGS'
                      AND UPPER(COLUMN_NAME) IN ('STEPS_TO_REPRODUCE', 'EXPECTED_RESULT', 'ACTUAL_RESULT')
                    """, Integer.class);
            return found != null && found == 3;
        } catch (Exception noSuchTableYet) {
            return false;
        }
    }

    /**
     * The four boxes as one report, laid out the way the form's placeholder now
     * suggests writing it. A box that was left empty contributes nothing — an
     * "Actual:" line with nothing after it would read as missing information
     * rather than as information that was never asked for.
     */
    private static String fold(String description, String steps, String expected, String actual) {
        StringBuilder out = new StringBuilder();
        append(out, description == null ? null : description.strip());
        if (notBlank(steps)) {
            append(out, "How to see it:\n" + steps.strip());
        }
        StringBuilder results = new StringBuilder();
        if (notBlank(expected)) {
            results.append("Expected: ").append(expected.strip());
        }
        if (notBlank(actual)) {
            if (results.length() > 0) {
                results.append('\n');
            }
            results.append("Actual: ").append(actual.strip());
        }
        append(out, results.length() == 0 ? null : results.toString());
        return out.toString();
    }

    /** Adds a paragraph, with a blank line before it once there is anything above. */
    private static void append(StringBuilder out, String part) {
        if (!notBlank(part)) {
            return;
        }
        if (out.length() > 0) {
            out.append("\n\n");
        }
        out.append(part);
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
