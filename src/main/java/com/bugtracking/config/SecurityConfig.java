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
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

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
 * {@code ROLE_ADMIN} on top of the {@code ROLE_USER} everybody on the team
 * gets. What that buys is listed in {@link #filterChain} and is deliberately
 * short: administration, not work.
 *
 * <p>{@link MemberRole#GUEST} is the one that does not work that way. A client
 * gets {@code ROLE_GUEST} and <em>not</em> {@code ROLE_USER}, and the chain ends
 * with {@code anyRequest().hasRole("USER")} — so the portal is an allowlist and
 * everything else, written and unwritten, is already closed to them.
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
                    // The JSON API used to be permitAll, which meant an
                    // unauthenticated GET /api/bugs returned every bug on every
                    // project and /api/projects/*/team returned the roster. That
                    // was survivable while everybody with the URL was already
                    // inside; it stopped being survivable the moment a client
                    // could hold a session on this origin, because the portal
                    // below would be decorative next to it.
                    //
                    // ROLE_USER, so the app's own two fetch calls keep working
                    // on the session cookie and a guest is refused. A script
                    // that used to call this anonymously now has to sign in.
                    .requestMatchers("/api/**").hasRole("USER")

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
                    // Handing somebody outside the company a way in is setup of
                    // the most consequential kind, so it sits with the roster.
                    .requestMatchers(HttpMethod.POST,
                            "/team/*/guest").hasRole("ADMIN")

                    // ---- the client portal ----
                    // Everything a guest may reach, and it is a list rather
                    // than a subtraction. /account is on it because changing
                    // your own password is not a privilege; the four /portal
                    // routes are the whole of what a client can do here.
                    .requestMatchers("/portal", "/portal/**").hasRole("GUEST")
                    .requestMatchers("/account", "/account/**").hasAnyRole("USER", "GUEST")

                    // hasRole("USER") and not authenticated(). A guest is
                    // authenticated, so .authenticated() would hand them this
                    // app - and would keep handing them every route added after
                    // this line was written. ROLE_USER means "on the team", and
                    // AccountPrincipal.authorities is where a guest is refused
                    // it. Closed by default is the only version of this that
                    // stays true as the app grows.
                    .anyRequest().hasRole("USER"))

            .formLogin(form -> form
                    .loginPage("/login")
                    .usernameParameter("email")
                    .passwordParameter("password")
                    // A handler rather than defaultSuccessUrl("/bugs", true),
                    // which sends everybody to a page a guest is refused: they
                    // would sign in correctly and land on a 403.
                    .successHandler(landing())
                    .failureUrl("/login?error")
                    .permitAll())

            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .permitAll());

        // CSRF is left at its default, which is on for every state-changing
        // request including /api/**. That used to carry .ignoringRequestMatchers
        // ("/api/**"), on the grounds that the API had no browser session to
        // ride on; closing it to ROLE_USER above gave it one, and an exempt
        // endpoint that trusts a session cookie is the definition of CSRF. Both
        // fetch calls in app.js send the token from layout.html's meta tag.

        // No HTTP Basic on purpose: with it configured alongside form login,
        // Spring answers an unauthenticated request with 401 + WWW-Authenticate
        // instead of redirecting to /login. The form is the only way in.

        return http.build();
    }

    /**
     * Where signing in puts you: the board, or the portal if you are a client.
     *
     * <p>Fixed rather than "wherever you were heading" on purpose. Spring's
     * saved-request handler would send somebody who followed a deep link back to
     * it after signing in, which for a guest is a link into the app they cannot
     * open — a 403 as the first thing a client ever sees. One destination per
     * role is the whole rule.
     */
    private static AuthenticationSuccessHandler landing() {
        return (request, response, authentication) -> {
            boolean guest = AccountPrincipal.of(authentication)
                    .map(AccountPrincipal::isGuest)
                    .orElse(false);
            new SimpleUrlAuthenticationSuccessHandler(guest ? "/portal" : "/bugs")
                    .onAuthenticationSuccess(request, response, authentication);
        };
    }
}
