package com.bugtracking.config;

import com.bugtracking.model.Environment;
import com.bugtracking.service.BugService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Environment arrived after bugs already existed and is required, so rows
 * holding null would fail validation the next time anyone edited them - the
 * same problem the client field had. Fill them once, on startup.
 */
@Configuration
public class FieldDefaultsBackfill {

    private static final Logger log = LoggerFactory.getLogger(FieldDefaultsBackfill.class);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 30)     // after the client→project migration
    CommandLineRunner backfillFieldDefaults(BugService service) {
        return args -> {
            int projects = service.fillMissingProject("Unspecified");
            if (projects > 0) {
                log.info("Set project to 'Unspecified' on {} bug(s) that had none.", projects);
            }

            int environments = service.fillMissingEnvironment(Environment.QA);
            if (environments > 0) {
                log.info("Set environment to {} on {} bug(s) raised before the Environment field existed.",
                        Environment.QA.getLabel(), environments);
            }
        };
    }
}
