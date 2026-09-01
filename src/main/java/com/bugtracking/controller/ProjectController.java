package com.bugtracking.controller;

import com.bugtracking.service.ProjectService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The projects a bug can be raised against. */
@Controller
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("projects", service.all());
        model.addAttribute("usage", service.usageByProjectId());
        return "projects";
    }

    @PostMapping
    public String add(@RequestParam String name, RedirectAttributes flash) {
        try {
            var project = service.add(name);
            flash.addFlashAttribute("message", project.getName() + " is on the board.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/projects";
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable Long id,
                            @RequestParam boolean active,
                            RedirectAttributes flash) {
        var project = service.setActive(id, active);
        flash.addFlashAttribute("message", active
                ? project.getName() + " is active again."
                : project.getName() + " is hidden from the sidebar and dropdowns.");
        return "redirect:/projects";
    }

    @PostMapping("/{id}/delete")
    public String remove(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.remove(id);
            flash.addFlashAttribute("message", "Project removed.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/projects";
    }
}
