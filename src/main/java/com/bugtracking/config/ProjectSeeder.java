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
 * The projects, seeded on startup. Matched on name and inserted only if
 * missing, so this is safe to re-run and new projects can just be appended.
 * Anything added on the Projects page is left alone.
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
            int added = service.addMissing(PROJECTS);
            if (added > 0) {
                log.info("Added {} project(s) to the projects table.", added);
            }
        };
    }
}
