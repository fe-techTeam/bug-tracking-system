package com.bugtracking.config;

import com.bugtracking.model.MemberRole;
import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.TeamMemberRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Locale;

/**
 * Sign-in for the web pages.
 *
 * <p>Accounts live in the {@code team_members} table: one row per person, with
 * a BCrypt hash in {@code password_hash} for the ones who can sign in. You sign
 * in with an email address, but the principal's name is that person's
 * <em>display name</em> — so comments, status changes and the history trail
 * read "Nishana R" rather than an email address.
 *
 * <p>The table is the only source. There is no configured account, no
 * in-memory user and no fallback: an address with no row, or a row with no
 * hash, cannot sign in. {@link BootstrapAdmin} is how the first admin gets
 * into an empty database, and it does that by writing a row — not by being
 * one.
 *
 * <p>Each row also carries a {@link MemberRole}, which becomes
 * {@code ROLE_ADMIN} on top of the {@code ROLE_USER} everybody gets. What that
 * buys is listed in {@link #filterChain} and is deliberately short:
 * administration, not work.
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
     *
     * <p>A row without a hash is a name that appears on bugs, not an account,
     * so it is turned away exactly like an address nobody has: whether somebody
     * is on the roster is not something the sign-in page should tell you.
     */
    @Bean
    UserDetailsService userDetailsService(TeamMemberRepository team) {
        return email -> {
            String key = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
            TeamMember member = team.findByEmailIgnoreCase(key)
                    .filter(TeamMember::hasPassword)
                    .orElseThrow(() -> new UsernameNotFoundException("No account for " + email));

            return account(member.getName(), member.getPasswordHash(), member.isActive(),
                    member.getId(), member.getEmail(), member.getRole());
        };
    }

    /**
     * getUsername() becomes Authentication.getName(), hence the display name.
     * The id and the email travel alongside it — see {@link AccountPrincipal}
     * for why a display name is not enough to change your own password by.
     *
     * <p>Somebody hidden on the Team page is disabled rather than missing:
     * Spring then answers "account is disabled" instead of "bad credentials",
     * which is the difference between knowing you were switched off and
     * assuming you mistyped.
     */
    private static UserDetails account(String displayName, String passwordHash, boolean active,
                                       Long memberId, String email, MemberRole role) {
        return new AccountPrincipal(displayName, passwordHash, active, memberId, email, role);
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

                    // ---- administration ----
                    // The line is drawn around the *setup*, not around the
                    // work. Raising a bug, moving a card, commenting, filing a
                    // document, renaming a column on the board and ticking
                    // somebody onto a project all stay open to anybody signed
                    // in - a tracker that asks permission before letting you
                    // file a bug is one people route around.
                    //
                    // What an admin has that a member does not is the setup
                    // everybody else works inside: who exists, who can sign in,
                    // what their password is, and which projects there are.
                    //
                    // Settings is the administration console, so a member does
                    // not get to look at it either — not a page of controls with
                    // the controls taken out, just not theirs. Their own account
                    // is at /account, which is open to everybody signed in.
                    //
                    // The navbar's Team entry falls back to this page when
                    // JavaScript is off, so for a member it is rendered as a
                    // drawer trigger that only appears once scripting has said
                    // it will work — see .nav-link-js in layout.html.
                    .requestMatchers("/settings", "/settings/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST,
                            "/team", "/team/*/password", "/team/*/role",
                            "/team/*/delete", "/team/*/active").hasRole("ADMIN")
                    // Deliberately not "/projects/**": that would take the
                    // documents area (/projects/{id}/docs/**) and a project's
                    // own team list with it, and both of those are daily work.
                    .requestMatchers(HttpMethod.POST,
                            "/projects", "/projects/*/active", "/projects/*/delete").hasRole("ADMIN")

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
