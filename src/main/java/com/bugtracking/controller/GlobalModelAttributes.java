package com.bugtracking.controller;

import com.bugtracking.service.NotificationService;
import com.bugtracking.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Map;

/**
 * Values every page needs — the sidebar is on all of them now, so the project
 * list and the current project live here rather than in one controller.
 * Restricted to {@link Controller} so the JSON API does not pay for them.
 */
@ControllerAdvice(annotations = Controller.class)
public class GlobalModelAttributes {

    /** Where the last-used project is parked between pages. */
    static final String PROJECT_KEY = "bugtracking.project";

    private final NotificationService notifications;
    private final ProjectService projects;

    public GlobalModelAttributes(NotificationService notifications, ProjectService projects) {
        this.notifications = notifications;
        this.projects = projects;
    }

    /** Drives the count on the Notifications item in the sidebar. */
    @ModelAttribute("unreadNotifications")
    public long unreadNotifications() {
        return notifications.unreadCount();
    }

    /** Every project the switcher offers, with its bug count. */
    @ModelAttribute("projectCounts")
    public Map<String, Long> projectCounts() {
        return projects.sidebarCounts();
    }

    /**
     * The project the switcher shows. Whatever the URL asks for wins and is
     * remembered, so walking off to Team and back to the board returns you to
     * the project you were working in rather than the first one alphabetically.
     */
    @ModelAttribute("currentProject")
    public String currentProject(HttpServletRequest request, HttpSession session) {
        String asked = request.getParameter("project");
        if (asked != null && !asked.isBlank()) {
            session.setAttribute(PROJECT_KEY, asked.trim());
            return asked.trim();
        }
        Object remembered = session.getAttribute(PROJECT_KEY);
        return remembered instanceof String name && !name.isBlank() ? name : null;
    }

    /** Lets the sidebar mark which section you are in. */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
