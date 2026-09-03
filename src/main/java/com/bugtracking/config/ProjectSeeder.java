package com.bugtracking.config;

import com.bugtracking.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;

/**
 * The projects a brand new database starts with.
 *
 * <p>Only ever into an <em>empty</em> table. It used to insert whichever of
 * these names was missing on every boot, which meant a project removed in
 * Settings came straight back on the next restart — the delete worked, and the
 * seeder undid it. A list of examples is a starting point, not a set of rows
 * this app insists on, so once there is a single project here this does
 * nothing at all.
 *
 * <p>Adding a name below therefore only reaches a database that has no
 * projects. Add it in Settings instead, which is the same table.
 */
@Configuration
public class ProjectSeeder {

    private static final Logger log = LoggerFactory.getLogger(ProjectSeeder.class);

    private static final List<String> PROJECTS = List.of(
            "Mahindra Mutual Fund",
            "Godrej",
            "Color Shine",
            "Orpat");

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)     // after the schema is settled
    CommandLineRunner seedProjects(ProjectService service) {
        return args -> {
            if (!service.all().isEmpty()) {
                return;                          // somebody's projects; leave them alone
            }
            int added = service.addMissing(PROJECTS);
            if (added > 0) {
                log.info("Seeded {} project(s) into an empty projects table.", added);
            }
        };
    }
}
