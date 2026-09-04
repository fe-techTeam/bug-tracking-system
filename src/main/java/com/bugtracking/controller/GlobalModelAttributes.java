package com.bugtracking.controller;

import com.bugtracking.model.Notification;
import com.bugtracking.service.BoardColumnService;
import com.bugtracking.service.BoardColumns;
import com.bugtracking.service.BugService;
import com.bugtracking.service.NotificationService;
import com.bugtracking.service.ProjectService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;
import java.util.Map;

/**
 * Values every page needs — the navbar is on all of them, so the project list,
 * the current project and the notification popover's contents live here rather
 * than in one controller.
 * Restricted to {@link Controller} so the JSON API does not pay for them.
 */
@ControllerAdvice(annotations = Controller.class)
public class GlobalModelAttributes {

    /** Where the last-used project is parked between pages. */
    static final String PROJECT_KEY = "bugtracking.project";

    /** And the way you were last reading it: board, list or stats. */
    static final String VIEW_KEY = "bugtracking.view";

    /** The only three answers {@code ?view=} has. */
    private static final java.util.Set<String> VIEWS = java.util.Set.of("board", "list", "stats");

    /** How many notifications the bell popover lists before "View all". */
    private static final int POPOVER_LIMIT = 8;

    private final NotificationService notifications;
    private final ProjectService projects;
    private final BugService bugs;
    private final BoardColumnService columns;

    public GlobalModelAttributes(NotificationService notifications,
                                 ProjectService projects,
                                 BugService bugs,
                                 BoardColumnService columns) {
        this.notifications = notifications;
        this.projects = projects;
        this.bugs = bugs;
        this.columns = columns;
    }

    /**
     * Every project's board columns, for turning the key a bug stores into
     * wording and a colour.
     *
     * <p>Here rather than in a controller because a status badge is not one
     * page's problem: the board draws them, so do the list, the bug page and a
     * person's page, and each needs the same lookup. {@code ${cols.column(bug)}}
     * gives a template the column a bug is in; {@code ${cols.label(bug)}} and
     * {@code ${cols.token(bug)}} are the two things it usually wants from it.
     */
    @ModelAttribute("cols")
    public BoardColumns columns(HttpServletRequest request) {
        return chromeless(request) ? new BoardColumns(List.of()) : columns.snapshot();
    }

    /**
     * Whether the person reading this page may administer the setup.
     *
     * <p>{@code sec:authorize} answers the same question, but only as a
     * yes/no around a whole element. The navbar needs it as a <em>value</em> —
     * one link that keeps its id and its drawer behaviour either way and only
     * changes which class it carries — and duplicating the link under two
     * authorize branches would put the same id in the template twice.
     */
    @ModelAttribute("isAdmin")
    public boolean isAdmin(HttpServletRequest request) {
        return !chromeless(request)
                && com.bugtracking.config.AccountPrincipal
                        .of(org.springframework.security.core.context.SecurityContextHolder
                                .getContext().getAuthentication())
                        .map(com.bugtracking.config.AccountPrincipal::isAdmin)
                        .orElse(false);
    }

    /** Drives the count on the bell in the navbar. */
    @ModelAttribute("unreadNotifications")
    public long unreadNotifications(HttpServletRequest request) {
        return chromeless(request) ? 0 : notifications.unreadCount();
    }

    /** What the bell popover lists without leaving the page. */
    @ModelAttribute("recentNotifications")
    public List<Notification> recentNotifications(HttpServletRequest request) {
        return chromeless(request) ? List.of() : notifications.latest(POPOVER_LIMIT);
    }

    /** How much is in the bin, for the navbar's trash icon. */
    @ModelAttribute("trashCount")
    public long trashCount(HttpServletRequest request) {
        return chromeless(request) ? 0 : bugs.trashCount();
    }

    /** Every project the switcher offers, with its bug count. */
    @ModelAttribute("projectCounts")
    public Map<String, Long> projectCounts(HttpServletRequest request) {
        return chromeless(request) ? Map.of() : projects.sidebarCounts();
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

    /**
     * How you last read the bugs — the board, the same bugs as a list, or the
     * numbers.
     *
     * <p>Remembered for the same reason the project is: Home is one link, and
     * which of the three it should open is a thing about you rather than a
     * thing about the link. Somebody who works from the list gets the list
     * back, on the next page and on the next visit, without having to pick it
     * again.
     *
     * <p>Only ever set from a board URL, and only to one of the three names —
     * {@code /documents?view=} means nothing here, and neither does anything a
     * hand-typed URL invents.
     */
    @ModelAttribute("currentView")
    public String currentView(HttpServletRequest request, HttpSession session) {
        String asked = request.getParameter("view");
        if (request.getRequestURI().startsWith("/bugs")
                && asked != null && VIEWS.contains(asked.trim())) {
            session.setAttribute(VIEW_KEY, asked.trim());
            return asked.trim();
        }
        Object remembered = session.getAttribute(VIEW_KEY);
        return remembered instanceof String name && VIEWS.contains(name) ? name : "board";
    }

    /** Lets the navbar mark which section you are in. */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    /**
     * Whether this request wants none of the chrome above: a page rendered
     * after a failure, or a client's.
     *
     * <p>Tomcat re-dispatches to {@code /error} with {@link DispatcherType#ERROR},
     * and {@link ErrorPageController} draws a page with no navbar on it — so the
     * four queries above would be paid for chrome nobody sees. That alone would
     * only be waste. The reason it is a rule is the other case: when what failed
     * <em>is</em> the database, asking it again here is how an error page becomes
     * a second error, and the reader is left with Tomcat's bare page instead of
     * ours. So every count above answers from nothing on an error dispatch.
     *
     * <p>{@link GlobalExceptionHandler} is unaffected: it handles an exception
     * during the original request, where the dispatch is still a REQUEST and the
     * shell it renders does want its real counts.
     */
    private static boolean chromeless(HttpServletRequest request) {
        return request.getDispatcherType() == DispatcherType.ERROR || isGuest();
    }

    /**
     * A client, who has no navbar to fill.
     *
     * <p>Every attribute above describes the app shell — the project switcher,
     * the bell, the bin — and the portal draws none of it. Loading it for them
     * would be a handful of wasted queries, which is the small half; the other
     * half is that {@code projectCounts} is a list of every project in the
     * company, and a model attribute that is never rendered today is one a
     * template can start rendering tomorrow. Better that it is not there.
     */
    private static boolean isGuest() {
        return com.bugtracking.config.AccountPrincipal
                .of(org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication())
                .map(com.bugtracking.config.AccountPrincipal::isGuest)
                .orElse(false);
    }
}
