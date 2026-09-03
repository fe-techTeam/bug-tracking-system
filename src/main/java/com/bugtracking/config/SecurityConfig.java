package com.bugtracking.config;

import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.TeamMemberRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sign-in for the web pages.
 *
 * <p>Accounts live in the {@code team_members} table: one row per person, with
 * a BCrypt hash in {@code password_hash} for the ones who can sign in. You sign
 * in with an email address, but the principal's name is that person's
 * <em>display name</em> — so comments, status changes and the history trail
 * read "Nishana R" rather than an email address.
 *
 * <p>The accounts configured under {@code bugtracking.security} are still
 * honoured, but only as a fallback for an address the table has no password
 * for. {@link AccountSeeder} copies them into the table on startup, so in
 * practice the table answers and the properties are the way a first account
 * gets in on an empty database.
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Looks a person up by the address they typed.
     *
     * <p>Read through the repository rather than {@code TeamMemberService},
     * which now needs the {@link PasswordEncoder} declared above — going
     * through the service would make this bean and that one wait on each other.
     */
    @Bean
    UserDetailsService userDetailsService(SecurityProperties properties,
                                          TeamMemberRepository team,
                                          PasswordEncoder encoder) {
        // Hashed once at startup, not per attempt: BCrypt is deliberately slow,
        // and re-hashing the configured password on every sign-in would spend
        // that cost on the login page for no gain.
        Map<String, String> configured = new LinkedHashMap<>();
        for (SecurityProperties.Account account : properties.getAccounts()) {
            String email = account.getEmail() == null ? "" : account.getEmail().trim();
            if (!email.isEmpty()) {
                configured.put(email.toLowerCase(Locale.ROOT), encoder.encode(account.getPassword()));
            }
        }

        return email -> {
            String key = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
            TeamMember member = team.findByEmailIgnoreCase(key).orElse(null);

            if (member != null && member.hasPassword()) {
                return account(member.getName(), member.getPasswordHash(), member.isActive());
            }

            // No row, or a row that is only a name on bugs. The properties are
            // what is left, and how the very first sign-in works on a database
            // where nobody has a password yet.
            String hash = configured.get(key);
            if (hash == null) {
                throw new UsernameNotFoundException("No account for " + email);
            }
            String displayName = member != null ? member.getName() : key;
            return account(displayName, hash, member == null || member.isActive());
        };
    }

    /**
     * getUsername() becomes Authentication.getName(), hence the display name.
     *
     * <p>Somebody hidden on the Team page is disabled rather than missing:
     * Spring then answers "account is disabled" instead of "bad credentials",
     * which is the difference between knowing you were switched off and
     * assuming you mistyped.
     */
    private static UserDetails account(String displayName, String passwordHash, boolean active) {
        return User.withUsername(displayName)
                .password(passwordHash)
                .roles("USER")
                .disabled(!active)
                .build();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/login", "/css/**", "/js/**", "/favicon.ico").permitAll()
                    // The error page has to be reachable by whoever hit the
                    // error, signed in or not - a 404 on a stylesheet is served
                    // to an anonymous request, and answering it with a redirect
                    // to /login would say "sign in" about a missing file.
                    .requestMatchers("/error").permitAll()
                    // The JSON API stays open so scripts and WebDriver helpers keep
                    // working. Change this line to .authenticated() to close it.
                    .requestMatchers("/api/**").permitAll()
                    .anyRequest().authenticated())

            .formLogin(form -> form
                    .loginPage("/login")
                    .usernameParameter("email")
                    .passwordParameter("password")
                    .defaultSuccessUrl("/bugs", true)
                    .failureUrl("/login?error")
                    .permitAll())

            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .permitAll())

            // CSRF protects the HTML forms (Thymeleaf adds the token to any form
            // with th:action). The API has no browser session to ride on, so it
            // is exempt — otherwise every script POST would need a token.
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/h2-console/**"))

            // The H2 console renders itself in frames.
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        // No HTTP Basic on purpose: with it configured alongside form login,
        // Spring answers an unauthenticated request with 401 + WWW-Authenticate
        // instead of redirecting to /login. The API needs no credentials, so
        // the form is the only way in.

        return http.build();
    }
}
