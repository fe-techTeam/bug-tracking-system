package com.bugtracking.controller;

import com.bugtracking.model.Status;
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

/** The people who can raise or be assigned a bug. */
@Controller
@RequestMapping("/team")
public class TeamController {

    private final TeamMemberService service;
    private final BugService bugs;

    public TeamController(TeamMemberService service, BugService bugs) {
        this.service = service;
        this.bugs = bugs;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("members", service.all());
        model.addAttribute("usage", service.usageByMemberId());
        model.addAttribute("workload", service.workloadByMemberId());
        return "team";
    }

    /** One person's page: what they raised, and what is on their plate. */
    @GetMapping("/{id}")
    public String member(@PathVariable Long id, Model model) {
        var member = service.findById(id);
        model.addAttribute("member", member);
        model.addAttribute("load", service.workloadOf(member.getName()));
        model.addAttribute("reported", bugs.reportedBy(member.getName()));
        model.addAttribute("assigned", bugs.assignedTo(member.getName()));
        model.addAttribute("boardStatuses", Status.boardOrder());
        return "team-member";
    }

    @PostMapping
    public String add(@RequestParam String name,
                      @RequestParam String email,
                      RedirectAttributes flash) {
        try {
            var member = service.add(name, email);
            flash.addFlashAttribute("message", member.getName() + " is on the team.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/team";
    }

    @PostMapping("/{id}/delete")
    public String remove(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.remove(id);
            flash.addFlashAttribute("message", "Team member removed.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/team";
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable Long id,
                            @RequestParam boolean active,
                            RedirectAttributes flash) {
        var member = service.setActive(id, active);
        flash.addFlashAttribute("message", active
                ? member.getName() + " is active again."
                : member.getName() + " is no longer offered in the dropdowns.");
        return "redirect:/team";
    }
}
