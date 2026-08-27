package com.bugtracking.controller;

import com.bugtracking.config.ClientProperties;
import com.bugtracking.model.Bug;
import com.bugtracking.model.Severity;
import com.bugtracking.model.Status;
import com.bugtracking.service.BugService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/bugs")
public class BugController {

    private final BugService service;
    private final ClientProperties clientProperties;

    public BugController(BugService service, ClientProperties clientProperties) {
        this.service = service;
        this.clientProperties = clientProperties;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Status status,
                       @RequestParam(required = false) Severity severity,
                       @RequestParam(required = false) String keyword,
                       Model model) {
        model.addAttribute("bugs", service.findAll(status, severity, keyword));
        model.addAttribute("statusSummary", service.statusSummary());
        model.addAttribute("severitySummary", service.severitySummary());
        model.addAttribute("statuses", Status.values());
        model.addAttribute("severities", Severity.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedSeverity", severity);
        model.addAttribute("keyword", keyword);
        return "bugs/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        Bug bug = new Bug();
        model.addAttribute("bug", bug);
        addFormOptions(model, bug);
        return "bugs/form";
    }

    @PostMapping
    public String create(@Valid @org.springframework.web.bind.annotation.ModelAttribute("bug") Bug bug,
                         BindingResult result,
                         Model model,
                         RedirectAttributes flash) {
        if (result.hasErrors()) {
            addFormOptions(model, bug);
            return "bugs/form";
        }
        Bug saved = service.save(bug);
        flash.addFlashAttribute("message", "Bug #" + saved.getId() + " raised successfully.");
        return "redirect:/bugs/" + saved.getId();
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("bug", service.findById(id));
        model.addAttribute("statuses", Status.values());
        return "bugs/detail";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Bug bug = service.findById(id);
        model.addAttribute("bug", bug);
        addFormOptions(model, bug);
        return "bugs/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @org.springframework.web.bind.annotation.ModelAttribute("bug") Bug bug,
                         BindingResult result,
                         Model model,
                         RedirectAttributes flash) {
        if (result.hasErrors()) {
            addFormOptions(model, bug);
            return "bugs/form";
        }
        service.update(id, bug);
        flash.addFlashAttribute("message", "Bug #" + id + " updated.");
        return "redirect:/bugs/" + id;
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable Long id,
                               @RequestParam Status status,
                               RedirectAttributes flash) {
        service.changeStatus(id, status);
        flash.addFlashAttribute("message", "Bug #" + id + " moved to " + status.getLabel() + ".");
        return "redirect:/bugs/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        service.delete(id);
        flash.addFlashAttribute("message", "Bug #" + id + " deleted.");
        return "redirect:/bugs";
    }

    private void addFormOptions(Model model, Bug bug) {
        model.addAttribute("statuses", Status.values());
        model.addAttribute("severities", Severity.values());
        model.addAttribute("clients", clientOptions(bug));
    }

    /**
     * The configured clients, plus whatever this bug already has if it is no
     * longer on the list — so editing an old bug never quietly reassigns it to
     * a different client.
     */
    private List<String> clientOptions(Bug bug) {
        List<String> options = new ArrayList<>(clientProperties.getClients());
        String current = bug.getClient();
        if (current != null && !current.isBlank() && !options.contains(current)) {
            options.add(0, current);
        }
        return options;
    }
}
