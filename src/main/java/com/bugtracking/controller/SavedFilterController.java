package com.bugtracking.controller;

import com.bugtracking.model.SavedFilter;
import com.bugtracking.service.SavedFilterService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
@RequestMapping("/filters")
public class SavedFilterController {

    private final SavedFilterService filters;

    public SavedFilterController(SavedFilterService filters) {
        this.filters = filters;
    }

    @PostMapping
    public String save(@RequestParam String name,
                       @RequestParam(required = false) String project,
                       @RequestParam String query,
                       @RequestParam(required = false) boolean allProjects,
                       Principal principal,
                       RedirectAttributes flash) {
        try {
            SavedFilter saved = filters.save(name, allProjects ? null : project, query, principal.getName());
            flash.addFlashAttribute("message", "Saved filter \"" + saved.getName() + "\".");
        } catch (SavedFilterService.EmptyQueryException e) {
            flash.addFlashAttribute("message", "Turn on a filter first, then save it.");
        }
        return "redirect:/bugs" + (query.startsWith("?") ? query : "?" + query);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false, defaultValue = "/settings?tab=filters") String back,
                         Principal principal,
                         RedirectAttributes flash) {
        filters.delete(id, principal.getName());
        flash.addFlashAttribute("message", "Saved filter removed.");
        return "redirect:" + (back.startsWith("/") ? back : "/settings?tab=filters");
    }
}
