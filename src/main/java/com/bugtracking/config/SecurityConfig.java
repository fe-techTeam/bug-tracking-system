package com.bugtracking.config;

import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.TeamMemberRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
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
 * <p>The accounts are configured in {@code bugtracking.security}. You sign in
 * with an email address, but the principal's name is the person's <em>display
 * name</em> from the team table — so comments, status changes and the history
 * trail read "Nishana R" rather than an email address.
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The configured accounts, keyed by email. The display name is looked up at
     * sign-in rather than here, because the team table is seeded by a
     * CommandLineRunner that has not run while this bean is being built.
     */
    @Bean
    UserDetailsService userDetailsService(SecurityProperties properties,
                                          TeamMemberRepository team,
                                          PasswordEncoder encoder) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (SecurityProperties.Account account : properties.getAccounts()) {
            String email = account.getEmail() == null ? "" : account.getEmail().trim();
            if (!email.isEmpty()) {
                hashes.put(email.toLowerCase(Locale.ROOT), encoder.encode(account.getPassword()));
            }
        }

        return email -> {
            String key = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
            String hash = hashes.get(key);
            if (hash == null) {
                throw new UsernameNotFoundException("No account for " + email);
            }
            String displayName = team.findByEmailIgnoreCase(key)
                    .map(TeamMember::getName)
                    .orElse(key);
            // getUsername() becomes Authentication.getName(), hence the display name.
            return User.withUsername(displayName).password(hash).roles("USER").build();
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/login", "/css/**", "/js/**", "/favicon.ico").permitAll()
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
