package com.bugtracking.controller;

import com.bugtracking.config.AccountPrincipal;
import com.bugtracking.model.TeamMember;
import com.bugtracking.service.TeamMemberService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Your own account — the one page in the app that is about you rather than
 * about the work.
 *
 * <p>It exists because changing a password used to be somebody else's job:
 * every password path went through Settings &gt; Team, which is now
 * administration and closed to most people. Needing an admin to rotate your own
 * password is how passwords stop being rotated, so this is deliberately open to
 * everybody signed in and deliberately holds nothing an admin would need.
 *
 * <p>It asks for the current password, which the admin path cannot: an admin
 * setting a password does not know the old one, but the owner does, and asking
 * is what stops a walked-away-from session being enough to lock somebody out of
 * their own account.
 */
@Controller
@RequestMapping("/account")
public class AccountController {

    private final TeamMemberService team;

    public AccountController(TeamMemberService team) {
        this.team = team;
    }

    @GetMapping
    public String account(Authentication authentication, Model model) {
        model.addAttribute("account", member(authentication));
        // Not read off the row: somebody signing in on a configured account has
        // no row at all, and the principal is what the request was authorised
        // against either way.
        model.addAttribute("isAdmin", AccountPrincipal.of(authentication)
                .map(AccountPrincipal::isAdmin).orElse(false));
        model.addAttribute("signInEmail", AccountPrincipal.of(authentication)
                .map(AccountPrincipal::email).orElse(null));
        return "account";
    }

    /**
     * Changes your own password.
     *
     * <p>The confirmation is compared here rather than in the service: it is a
     * property of this form — the service is asked to change a password, not to
     * check that somebody typed it twice — and the message has to name the
     * field the person got wrong.
     */
    @PostMapping("/password")
    public String changePassword(@RequestParam String current,
                                 @RequestParam String password,
                                 @RequestParam String confirm,
                                 Authentication authentication,
                                 RedirectAttributes flash) {
        Long id = AccountPrincipal.of(authentication).flatMap(AccountPrincipal::memberId).orElse(null);
        if (id == null) {
            flash.addFlashAttribute("message",
                    "You are signed in on an account configured in application.properties, "
                            + "which has no row to change. Ask an admin to add you to the team.");
            return "redirect:/account";
        }

        try {
            if (!password.equals(confirm)) {
                throw new IllegalArgumentException("The two new passwords do not match.");
            }
            team.changeOwnPassword(id, current, password);
            // Nothing is said back about the password itself - the flash goes
            // through the session and from there into the next page's HTML.
            flash.addFlashAttribute("message", "Your password is changed. "
                    + "It is what you sign in with from now on.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/account";
    }

    /** The row behind the sign-in, or null for a properties-only account. */
    private TeamMember member(Authentication authentication) {
        return AccountPrincipal.of(authentication)
                .flatMap(AccountPrincipal::memberId)
                .map(team::findById)
                .orElse(null);
    }
}
