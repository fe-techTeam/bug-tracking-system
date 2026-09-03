package com.bugtracking.config;

import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.TeamMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;

/**
 * Moves the sign-in accounts out of application.properties and into the table.
 *
 * <p>The passwords under {@code bugtracking.security} were the only accounts
 * this app had, written in plain text and hashed afresh on every boot. Now that
 * {@code team_members} can hold a hash, this copies each configured account
 * onto the matching person the first time it runs, and from then on the table
 * is what {@link SecurityConfig} answers from — so a password changed on the
 * Team page sticks, which a property never could.
 *
 * <p>Only ever fills a blank. Somebody who already has a hash keeps it,
 * whatever the properties still say, or every restart would quietly undo a
 * password change. Removing the accounts from application.properties once
 * everyone has signed in once is the point of this, and safe.
 */
@Configuration
public class AccountSeeder {

    private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 30)     // after the team itself is seeded
    CommandLineRunner seedAccounts(SecurityProperties properties,
                                   TeamMemberRepository team,
                                   PasswordEncoder encoder) {
        return args -> {
            int given = 0;
            for (SecurityProperties.Account account : properties.getAccounts()) {
                String email = account.getEmail() == null ? "" : account.getEmail().trim();
                String password = account.getPassword();
                if (email.isEmpty() || password == null || password.isBlank()) {
                    continue;
                }

                TeamMember member = team.findByEmailIgnoreCase(email.toLowerCase(Locale.ROOT))
                        .orElse(null);
                if (member == null || member.hasPassword()) {
                    continue;               // not on the team, or already has one
                }

                member.setPasswordHash(encoder.encode(password));
                team.save(member);
                given++;
            }
            if (given > 0) {
                log.info("Gave {} configured account(s) a stored password. "
                        + "They can now be changed from Settings > Team, and the plain "
                        + "passwords in application.properties are no longer read for them.", given);
            }
        };
    }
}
