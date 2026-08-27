package com.bugtracking.config;

import com.bugtracking.service.BugService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bugs raised before the Client field existed have no client, and client is now
 * required — so they would fail validation the next time someone edited them or
 * changed their status. This gives them a placeholder value once, on startup.
 */
@Configuration
public class ClientBackfill {

    private static final Logger log = LoggerFactory.getLogger(ClientBackfill.class);

    @Bean
    CommandLineRunner backfillClients(BugService service, ClientProperties properties) {
        return args -> {
            int filled = service.fillMissingClient(properties.getDefaultClient());
            if (filled > 0) {
                log.info("Set client to '{}' on {} bug(s) raised before the Client field existed. "
                        + "Edit them to pick the real client.", properties.getDefaultClient(), filled);
            }
        };
    }
}
