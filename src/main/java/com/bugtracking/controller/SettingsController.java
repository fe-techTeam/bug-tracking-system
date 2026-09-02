package com.bugtracking.controller;

import com.bugtracking.model.ColumnColour;
import com.bugtracking.model.ColumnNotify;
import com.bugtracking.service.BoardColumnService;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.TeamMemberService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects, team and board columns on one page.
 *
 * <p>All three are administration rather than daily work — you set a project
 * up once and then live on the board — so they sit behind Settings and the
 * navbar stays short. The forms still post to {@code /projects}, {@code /team}
 * and {@code /columns}; only where you land afterwards changed.
 */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    private static final Set<String> TABS = Set.of("projects", "team", "board");

    private final ProjectService projects;
    private final TeamMemberService team;
    private final BoardColumnService columns;

    public SettingsController(ProjectService projects, TeamMemberService team,
                              BoardColumnService columns) {
        this.projects = projects;
        this.team = team;
        this.columns = columns;
    }

    @GetMapping
    public String settings(@RequestParam(required = false) String tab,
                           @RequestParam(required = false) String project,
                           HttpSession session,
                           Model model) {
        // A query param rather than script, so the tabs survive JS being off.
        model.addAttribute("tab", tab != null && TABS.contains(tab) ? tab : "projects");
        model.addAttribute("projects", projects.all());
        model.addAttribute("usage", projects.usageByProjectId());
        model.addAttribute("members", team.all());
        model.addAttribute("memberUsage", team.usageByMemberId());
        model.addAttribute("workload", team.workloadByMemberId());

        // Columns belong to one project at a time, so the Board tab edits one
        // board: the project named in the URL, else the one you were last
        // looking at, else the first there is.
        String board = boardProject(project, session);
        model.addAttribute("boardProject", board);
        model.addAttribute("boardNames", projects.sidebarCounts().keySet());
        model.addAttribute("boardColumns", board == null ? List.of() : columns.forProject(board));
        model.addAttribute("columnUsage", board == null ? Map.<Long, Long>of() : columns.usageIn(board));
        model.addAttribute("colours", ColumnColour.values());
        model.addAttribute("notifyModes", ColumnNotify.values());
        return "settings";
    }

    /** Which board the Board tab is editing. */
    private String boardProject(String asked, HttpSession session) {
        var names = projects.sidebarCounts().keySet();
        if (asked != null && !asked.isBlank() && names.contains(asked.trim())) {
            return asked.trim();
        }
        Object remembered = session.getAttribute(GlobalModelAttributes.PROJECT_KEY);
        if (remembered instanceof String name && names.contains(name)) {
            return name;
        }
        return names.stream().findFirst().orElse(null);
    }
}
