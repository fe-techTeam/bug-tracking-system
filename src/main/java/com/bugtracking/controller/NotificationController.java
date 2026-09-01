package com.bugtracking.controller;

import com.bugtracking.service.NotificationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("notifications", service.recent());
        return "notifications";
    }

    @PostMapping("/{id}/read")
    public String markRead(@PathVariable Long id) {
        service.markRead(id);
        return "redirect:/notifications";
    }

    @PostMapping("/read-all")
    public String markAllRead(RedirectAttributes flash) {
        int marked = service.markAllRead();
        flash.addFlashAttribute("message", marked == 0
                ? "Nothing was unread."
                : marked + " notification" + (marked == 1 ? "" : "s") + " marked as read.");
        return "redirect:/notifications";
    }
}
