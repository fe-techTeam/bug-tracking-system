package com.bugtracking.controller;

import com.bugtracking.service.ProjectService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * The projects a bug can be raised against.
 *
 * <p>The list itself now renders inside Settings, so the GET here only
 * forwards; the POSTs are unchanged and simply land back on that page.
 */
@Controller
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    /** Kept so old links and bookmarks still arrive somewhere useful. */
    @GetMapping
    public String list() {
        return "redirect:/settings";
    }

    /**
     * Adds a project, with whoever was ticked as its starting team.
     *
     * <p>{@code members} is optional because the checkbox list sends nothing at
     * all when none are ticked — a project with no team yet is a perfectly
     * ordinary thing to create.
     */
    @PostMapping
    public String add(@RequestParam String name,
                      @RequestParam(required = false) List<Long> members,
                      RedirectAttributes flash) {
        try {
            var project = service.add(name, members == null ? List.of() : members);
            int size = members == null ? 0 : members.size();
            flash.addFlashAttribute("message", size == 0
                    ? project.getName() + " is on the board."
                    : project.getName() + " is on the board, with " + size
                            + (size == 1 ? " person" : " people") + " on it.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/settings";
    }

    /**
     * Sets exactly who is on a project.
     *
     * <p>The whole team is submitted every time, so an unticked box means "take
     * them off" — with an absent {@code members} the answer is nobody, which is
     * why it defaults to an empty list rather than being ignored.
     */
    @PostMapping("/{id}/members")
    public String setMembers(@PathVariable Long id,
                             @RequestParam(required = false) List<Long> members,
                             @RequestParam(required = false) String back,
                             RedirectAttributes flash) {
        var project = service.setMembers(id, members == null ? List.of() : members);
        int size = project.getMembers().size();
        flash.addFlashAttribute("message", size == 0
                ? "Nobody is on " + project.getName() + " now."
                : project.getName() + " has " + size + (size == 1 ? " person" : " people") + " on it.");
        // The board's team drawer carries this form too, and Settings is not
        // where you were when you ticked somebody onto the project.
        return SafeRedirect.to(back, "redirect:/settings");
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable Long id,
                            @RequestParam boolean active,
                            RedirectAttributes flash) {
        var project = service.setActive(id, active);
        flash.addFlashAttribute("message", active
                ? project.getName() + " is active again."
                : project.getName() + " is hidden from the switcher and dropdowns.");
        return "redirect:/settings";
    }

    @PostMapping("/{id}/delete")
    public String remove(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.remove(id);
            flash.addFlashAttribute("message", "Project removed.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/settings";
    }
}
