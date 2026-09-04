package com.bugtracking.controller;

import com.bugtracking.model.TeamMember;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.TeamMemberService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * Projects and team — one console, one tab each.
 *
 * <p>Both are administration rather than daily work — you set a project up once
 * and then live on the board — so they sit behind Settings and the navbar stays
 * short. The forms still post to {@code /projects} and {@code /team}; only
 * where you land afterwards changed.
 *
 * <p>There is no Board tab. A board's columns are edited on the board itself,
 * from the menu on each column head, which is where you are standing when you
 * notice one is wrong — a second full editor behind Settings was the same
 * controls in a place nobody was.
 *
 * <p>There is no Email tab either. Whether notifications also leave the
 * building is decided by {@code .env} and nothing else — see {@code
 * EmailService} — and a screen whose only control sent a test message was a tab
 * to hold one button.
 *
 * <p>A tab shows one thing at a time, and each is a table with its adding done
 * in a popover off the head rather than in a form sitting open above it. The
 * Team tab goes further and is two screens: the roster, or — when {@code
 * member} names somebody — that person's account <em>instead of</em> the
 * roster.
 */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    private static final Set<String> TABS = Set.of("projects", "team");

    private final ProjectService projects;
    private final TeamMemberService team;

    public SettingsController(ProjectService projects, TeamMemberService team) {
        this.projects = projects;
        this.team = team;
    }

    @GetMapping
    public String settings(@RequestParam(required = false) String tab,
                           @RequestParam(required = false) String project,
                           @RequestParam(required = false) Long member,
                           Model model) {
        // A query param rather than script, so the tabs survive JS being off.
        String active = tab != null && TABS.contains(tab) ? tab : "projects";
        model.addAttribute("tab", active);
        model.addAttribute("projects", projects.all());
        model.addAttribute("usage", projects.usageByProjectId());
        // Who is on each project, and the same as ids for the tick boxes. Two
        // maps rather than a walk into project.members: the collection is lazy
        // and open-in-view is off, so a template cannot load it itself.
        model.addAttribute("projectTeam", projects.membersByProjectId());
        model.addAttribute("projectTeamIds", projects.memberIdsByProjectId());

        // Which project's team is being edited. A link rather than a popover in
        // the table: .table-wrap scrolls horizontally, which clips anything that
        // opens out of a cell. The name is in the URL, so the editor is
        // linkable and survives JavaScript being off.
        model.addAttribute("editingProject", "projects".equals(active) && project != null
                ? projects.findByName(project).orElse(null)
                : null);
        List<TeamMember> roster = team.all();
        model.addAttribute("members", roster);
        // Whose account is open, if anybody's. A query param rather than a
        // popover in the row: it is linkable, it works with scripting off, and
        // .table-wrap scrolls horizontally so anything opening out of a cell is
        // clipped by that scrollport anyway. When it is set the template draws
        // that person instead of the table — one screen, one job.
        model.addAttribute("editingMember", "team".equals(active) && member != null
                ? roster.stream().filter(m -> member.equals(m.getId())).findFirst().orElse(null)
                : null);
        // Only what the Remove control depends on: a member named on a bug is
        // deactivated rather than removed, so their history keeps making sense.
        model.addAttribute("workload", team.workloadByMemberId());

        // The clients, listed apart from the roster because they are not on it:
        // team.all() excludes them by role, and mixing the two lists back
        // together here would undo that in the one place people read it.
        model.addAttribute("guests", team.guests());
        model.addAttribute("activeProjects", projects.active());
        return "settings";
    }
}
