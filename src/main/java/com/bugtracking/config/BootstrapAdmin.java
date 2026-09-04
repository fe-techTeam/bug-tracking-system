package com.bugtracking.config;

import com.bugtracking.model.MemberRole;
import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.TeamMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;

/**
 * The one way in when nobody in the database can get in.
 *
 * <p>Accounts live in {@code team_members} and nowhere else — {@link
 * SecurityConfig} reads that table and has no other source to fall back on. So
 * an empty database is a locked door: only an admin may add a member, and there
 * is no admin to do it. This is the key to that door and nothing more.
 *
 * <p>It does nothing at all unless {@code bugtracking.bootstrap.admin.email}
 * and {@code .password} are both set — they come from {@code .env}, which is
 * gitignored, and are unset out of the box. Even then it does nothing while a
 * single active admin with a password exists: the moment the database can
 * administer itself, this is inert, so a password changed on Settings &gt; Team
 * is never undone by a restart.
 *
 * <p>This is not a login. The credentials here are written into the table,
 * hashed, once; sign-in never consults them. Clear both values from {@code .env}
 * after the first sign-in and nothing changes.
 */
@Configuration
public class BootstrapAdmin {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 30)
    CommandLineRunner bootstrapFirstAdmin(@Value("${bugtracking.bootstrap.admin.email:}") String configuredEmail,
                                     @Value("${bugtracking.bootstrap.admin.password:}") String password,
                                     TeamMemberRepository team,
                                     PasswordEncoder encoder) {
        return args -> {
            String email = configuredEmail == null ? "" : configuredEmail.trim().toLowerCase(Locale.ROOT);
            if (email.isEmpty() || password == null || password.isBlank()) {
                return;
            }
            if (team.findAll().stream().anyMatch(TeamMember::isActiveAdmin)) {
                return;                 // somebody can already administer this
            }

            TeamMember member = team.findByEmailIgnoreCase(email)
                    .orElseGet(() -> new TeamMember(displayNameFor(email), email));
            boolean created = member.getId() == null;
            member.setRole(MemberRole.ADMIN);
            member.setActive(true);
            // Only ever fills a blank. Somebody who already has a hash keeps it —
            // they were on the roster and only needed the badge.
            if (!member.hasPassword()) {
                member.setPasswordHash(encoder.encode(password));
            }
            team.save(member);

            log.warn("No administrator could sign in, so {} <{}> {}. Sign in, add the rest of "
                            + "the team on Settings > Team, then clear bugtracking.bootstrap.admin.* "
                            + "from .env — it is not read again once an admin exists.",
                    member.getName(), member.getEmail(),
                    created ? "has been created as one" : "has been made one");
        };
    }

    /** "admin@firsteconomy.com" -> "Admin". A name, not an address, on the roster. */
    private static String displayNameFor(String email) {
        String local = email.substring(0, Math.max(0, email.indexOf('@')));
        if (local.isBlank()) {
            return email;
        }
        String cleaned = local.replace('.', ' ').replace('_', ' ').replace('-', ' ').trim();
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }
}
