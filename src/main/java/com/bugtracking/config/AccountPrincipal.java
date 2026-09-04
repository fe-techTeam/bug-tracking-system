package com.bugtracking.config;

import com.bugtracking.model.MemberRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Optional;

/**
 * The signed-in person, with the two things the display name cannot carry.
 *
 * <p>{@code getUsername()} is still the <em>display name</em> — that is
 * load-bearing, because {@code Authentication.getName()} is what every comment,
 * history line and notification is written against, and those read "Nishana R"
 * rather than an email address. But changing your own password needs to know
 * <em>which row</em> you are, and a display name is not unique enough to look
 * that up by: two people called the same thing would each be able to change a
 * password, just not reliably their own.
 *
 * <p>So the id travels with the principal. It is null for an account that
 * exists only in {@code bugtracking.security} and has no row yet — see
 * {@link SecurityConfig} — which is exactly the case
 * {@code AccountController} has to refuse rather than guess at.
 */
public class AccountPrincipal extends User {

    private final Long memberId;
    private final String email;
    private final MemberRole role;

    public AccountPrincipal(String displayName, String passwordHash, boolean active,
                            Long memberId, String email, MemberRole role) {
        super(displayName, passwordHash, active, true, true, true, authorities(role));
        this.memberId = memberId;
        this.email = email;
        this.role = role == null ? MemberRole.MEMBER : role;
    }

    /**
     * ROLE_USER is granted to everybody alongside the real role, so anything
     * that only asks "is somebody signed in" keeps working — including the
     * accounts this app had before roles existed.
     */
    private static List<GrantedAuthority> authorities(MemberRole role) {
        if (role == MemberRole.ADMIN) {
            return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                           new SimpleGrantedAuthority("ROLE_USER"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /** The row behind this sign-in, or empty for a properties-only account. */
    public Optional<Long> memberId() {
        return Optional.ofNullable(memberId);
    }

    public String email() {
        return email;
    }

    public MemberRole role() {
        return role;
    }

    public boolean isAdmin() {
        return role == MemberRole.ADMIN;
    }

    /**
     * Whoever is signed in, when they signed in through this app's own form.
     *
     * <p>Empty rather than throwing for anonymous requests and for the seeders
     * and startup runners, which reach the services with no security context at
     * all.
     */
    public static Optional<AccountPrincipal> of(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AccountPrincipal account
                ? Optional.of(account)
                : Optional.empty();
    }
}
