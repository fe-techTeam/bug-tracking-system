package com.bugtracking.config;

import com.bugtracking.service.BoardColumnService;
import com.bugtracking.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Gives every project a board the first time this version starts.
 *
 * <p>Columns used to be a Java enum, so no database has any. Each project —
 * including a name that only ever existed on old bugs — gets the same six
 * columns the enum held, in the same order, with the same colours: the board
 * comes up looking exactly as it did, and is editable from then on.
 *
 * <p>Nothing here is load-bearing. A project the seeder misses is given the
 * defaults the first time its board is read, so this only exists to make the
 * columns real rows you can edit rather than waiting for someone to visit.
 */
@Configuration
public class BoardColumnSeed {

    private static final Logger log = LoggerFactory.getLogger(BoardColumnSeed.class);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 40)     // after the project backfill names every bug
    CommandLineRunner seedBoardColumns(ProjectService projects, BoardColumnService columns) {
        return args -> {
            int seeded = 0;
            for (String project : projects.sidebarCounts().keySet()) {
                if (!columns.hasBoard(project)) {
                    columns.seed(project);
                    seeded++;
                }
            }
            // Silent on every startup after the first: there is nothing to say
            // when every project already has the board it has been editing.
            if (seeded > 0) {
                log.info("Seeded the default board columns for {} project(s).", seeded);
            }
        };
    }
}
