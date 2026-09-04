package com.bugtracking.controller;

import com.bugtracking.model.MemberRole;
import com.bugtracking.model.Project;
import com.bugtracking.model.TeamMember;
import com.bugtracking.service.BugService;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.TeamMemberService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The people who can raise or be assigned a bug.
 *
 * <p>The roster renders inside Settings now. One person's page is still a page
 * of its own — it is somewhere you read, not somewhere you administer.
 */
@Controller
@RequestMapping("/team")
public class TeamController {

    private final TeamMemberService service;
    private final BugService bugs;
    private final ProjectService projects;

    public TeamController(TeamMemberService service, BugService bugs, ProjectService projects) {
        this.service = service;
        this.bugs = bugs;
        this.projects = projects;
    }

    /** Kept so old links and bookmarks still arrive somewhere useful. */
    @GetMapping
    public String list() {
        return "redirect:/settings?tab=team";
    }

    /**
     * The roster, as a fragment for the navbar's team drawer.
     *
     * <p>"Who is on this?" is asked while you are looking at a board, so the
     * navbar answers it beside the page rather than instead of it. The whole
     * team, with the people on the current project ticked and sorted to the
     * top — one list rather than a roster with an editor folded under it,
     * because ticking <em>is</em> the edit.
     *
     * <p>A page route rather than an {@code /api} one, deliberately:
     * {@code /api/**} answers JSON to scripts, and this carries email
     * addresses and a CSRF token for the form it draws.
     *
     * <p>{@code /panel} ahead of {@code /{id}} is not an ordering accident —
     * a literal segment beats a template variable in Spring's matching, so
     * this is reached and never parsed as a member id.
     */
    @GetMapping("/panel")
    public String panel(@RequestParam(required = false) String project,
                        @RequestParam(defaultValue = "false") boolean edit,
                        HttpSession session,
                        Model model) {
        Object remembered = session.getAttribute(GlobalModelAttributes.PROJECT_KEY);
        Project found = projects.findByName(project)
                .or(() -> remembered instanceof String name ? projects.findByName(name) : Optional.empty())
                .or(() -> projects.active().stream().findFirst())
                .orElse(null);

        List<TeamMember> onIt = found == null ? List.of() : projects.membersOf(found.getName());
        Set<Long> onIds = onIt.stream().map(TeamMember::getId).collect(Collectors.toSet());

        model.addAttribute("project", found);
        // Split rather than sorted with a flag: the template draws two labelled
        // groups, and "who is on it" is the answer the drawer opens on.
        model.addAttribute("onTeam", onIt);
        // Only in edit: the rest of the company is an answer to "who else
        // could be on this", which is not the question the drawer opens on.
        model.addAttribute("offTeam", !edit ? List.<TeamMember>of() : service.all().stream()
                .filter(member -> !onIds.contains(member.getId()))
                .toList());
        model.addAttribute("edit", edit);
        return "fragments :: teamPanel";
    }

    /*
     * There is no page for one person any more. It listed what they raised and
     * what they are carrying — both of which are the board, filtered, and the
     * board says it better: their queue is /bugs?assignee=<name>, which is
     * where every face and every name in the app now links. A page that
     * reproduces a filtered board is a second board to keep in step with the
     * first.
     */

    /**
     * Adds somebody, optionally with the password they will sign in with.
     *
     * <p>The password is optional because most of the roster never sign in —
     * they are names that appear on bugs. Leaving it blank adds them without an
     * account, which is exactly what every member had before there was a
     * password column at all.
     */
    @PostMapping
    public String add(@RequestParam String name,
                      @RequestParam String email,
                      @RequestParam(required = false) String password,
                      @RequestParam(required = false) Long projectId,
                      @RequestParam(required = false) String back,
                      RedirectAttributes flash) {
        try {
            // Asked before the save, or the answer is always yes: adding an
            // email that is already here renames that person rather than
            // making a second one, and the flash should say so.
            boolean existing = service.isOnTeam(email);
            var member = service.add(name, email, password);
            boolean withAccount = password != null && !password.isBlank();

            // The board's team drawer adds somebody to the project you are
            // looking at, not to a roster in the abstract — so it names the
            // project and they land on it in the same post.
            String onto = "";
            if (projectId != null) {
                onto = " on " + projects.addMember(projectId, member.getId()).getName();
            }

            flash.addFlashAttribute("message", existing
                    ? member.getName() + (withAccount
                            ? "'s details and password were updated."
                            : "'s name was updated.")
                    : member.getName() + " is on the team" + onto + (withAccount
                            ? " and can sign in."
                            : "."));
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return SafeRedirect.to(back, "redirect:/settings?tab=team");
    }

    /**
     * Sets or clears one person's sign-in password.
     *
     * <p>The flash never repeats the password back — it would end up in the
     * session, and from there into the next page's HTML.
     */
    /**
     * Grants somebody outside the company access to one project's portal.
     *
     * <p>Its own route rather than {@code /team} with a role parameter, so the
     * admin-only line in {@code SecurityConfig} can name it: letting a client in
     * is the most consequential piece of setup this app has, and it should not
     * ride on a matcher written for adding a colleague.
     */
    @PostMapping("/{id}/guest")
    public String guest(@PathVariable Long id,
                        @RequestParam(required = false) String name,
                        @RequestParam(required = false) String email,
                        @RequestParam(required = false) String password,
                        @RequestParam(required = false) Long projectId,
                        @RequestParam(required = false) String back,
                        RedirectAttributes flash) {
        try {
            // id 0 is "a new one". A path variable rather than two routes,
            // because everything either does is the same work on the same row.
            if (id != null && id > 0) {
                var moved = service.setGuestProject(id, projectId);
                flash.addFlashAttribute("message", moved.getName() + " now reports on "
                        + projects.findById(projectId).map(Project::getName).orElse("that project") + ".");
            } else {
                var added = service.addGuest(name, email, password, projectId);
                flash.addFlashAttribute("message", added.getName()
                        + " can now sign in and send in reports. Send them the sign-in"
                        + " address and the password you just set — the password is not"
                        + " stored anywhere it can be read back.");
            }
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return SafeRedirect.to(back, "redirect:/settings?tab=team");
    }

    @PostMapping("/{id}/password")
    public String setPassword(@PathVariable Long id,
                              @RequestParam(required = false) String password,
                              @RequestParam(defaultValue = "false") boolean clear,
                              @RequestParam(required = false) String back,
                              RedirectAttributes flash) {
        try {
            if (clear) {
                var member = service.clearPassword(id);
                flash.addFlashAttribute("message", member.getName() + " can no longer sign in.");
            } else {
                var member = service.setPassword(id, password);
                flash.addFlashAttribute("message", member.getName() + "'s password is set.");
            }
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return SafeRedirect.to(back, "redirect:/settings?tab=team");
    }

    /**
     * Makes somebody an administrator, or takes it back.
     *
     * <p>Admin-only, like everything else that posts here — see
     * {@code SecurityConfig.filterChain}. The refusals that matter (a badge on
     * somebody who cannot sign in, and demoting the last admin) belong to the
     * service, because hiding and clearing a password reach the same cliff by
     * different routes.
     */
    @PostMapping("/{id}/role")
    public String setRole(@PathVariable Long id,
                          @RequestParam String role,
                          @RequestParam(required = false) String back,
                          RedirectAttributes flash) {
        try {
            var member = service.setRole(id, MemberRole.of(role));
            flash.addFlashAttribute("message", member.isAdmin()
                    ? member.getName() + " can now manage projects, people and passwords."
                    : member.getName() + " is a member again.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return SafeRedirect.to(back, "redirect:/settings?tab=team");
    }

    @PostMapping("/{id}/delete")
    public String remove(@PathVariable Long id,
                         @RequestParam(required = false) String back,
                         RedirectAttributes flash) {
        try {
            service.remove(id);
            flash.addFlashAttribute("message", "Team member removed.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return SafeRedirect.to(back, "redirect:/settings?tab=team");
    }

    /**
     * Hides somebody, or brings them back.
     *
     * <p>Wrapped like the other three: hiding is one of the ways the last
     * administrator can be taken away, and the service refuses it. Without the
     * catch that refusal leaves MVC as a 400 — a page saying "something went
     * wrong" instead of the sentence explaining what to do about it.
     */
    @PostMapping("/{id}/active")
    public String setActive(@PathVariable Long id,
                            @RequestParam boolean active,
                            @RequestParam(required = false) String back,
                            RedirectAttributes flash) {
        try {
            var member = service.setActive(id, active);
            flash.addFlashAttribute("message", active
                    ? member.getName() + " is active again."
                    : member.getName() + " is no longer offered in the dropdowns.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return SafeRedirect.to(back, "redirect:/settings?tab=team");
    }
}
