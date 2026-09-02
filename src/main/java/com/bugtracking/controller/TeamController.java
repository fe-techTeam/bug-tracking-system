package com.bugtracking.controller;

import com.bugtracking.service.BugService;
import com.bugtracking.service.TeamMemberService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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

    public TeamController(TeamMemberService service, BugService bugs) {
        this.service = service;
        this.bugs = bugs;
    }

    /** Kept so old links and bookmarks still arrive somewhere useful. */
    @GetMapping
    public String list() {
        return "redirect:/settings";
    }

    /** One person's page: what they raised, and what is on their plate. */
    @GetMapping("/{id}")
    public String member(@PathVariable Long id, Model model) {
        var member = service.findById(id);
        model.addAttribute("member", member);
        model.addAttribute("load", service.workloadOf(member.getName()));
        model.addAttribute("reported", bugs.reportedBy(member.getName()));
        model.addAttribute("assigned", bugs.assignedTo(member.getName()));
        return "team-member";
    }

    @PostMapping
    public String add(@RequestParam String name,
                      @RequestParam String email,
                      RedirectAttributes flash) {
        try {
            // Asked before the save, or the answer is always yes: adding an
            // email that is already here renames that person rather than
            // making a second one, and the flash should say so.
            boolean existing = service.isOnTeam(email);
            var member = service.add(name, email);
            flash.addFlashAttribute("message", existing
                    ? member.getName() + "'s name was updated."
                    : member.getName() + " is on the team.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/{id}/delete")
    public String remove(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.remove(id);
            flash.addFlashAttribute("message", "Team member removed.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable Long id,
                            @RequestParam boolean active,
                            RedirectAttributes flash) {
        var member = service.setActive(id, active);
        flash.addFlashAttribute("message", active
                ? member.getName() + " is active again."
                : member.getName() + " is no longer offered in the dropdowns.");
        return "redirect:/settings";
    }
}
