package com.bugtracking.config;

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

/**
 * Sign-in for the web pages.
 *
 * <p>One account, configured in {@code bugtracking.security}. You sign in with
 * an email address, but the principal's name is the person's <em>display
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
     * The one account. Looks the display name up in the team table so the
     * signed-in person matches who they are everywhere else in the app.
     */
    @Bean
    UserDetailsService userDetailsService(SecurityProperties properties,
                                          TeamMemberRepository team,
                                          PasswordEncoder encoder) {
        String configured = properties.getEmail().trim();
        String hash = encoder.encode(properties.getPassword());

        String displayName = team.findByEmailIgnoreCase(configured)
                .map(m -> m.getName())
                .orElse(configured);

        return email -> {
            if (email == null || !email.trim().equalsIgnoreCase(configured)) {
                throw new UsernameNotFoundException("No account for " + email);
            }
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
