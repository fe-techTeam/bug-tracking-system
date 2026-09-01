package com.bugtracking.controller;

import com.bugtracking.model.Project;
import com.bugtracking.service.BugService;
import com.bugtracking.service.Dashboard;
import com.bugtracking.service.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The projects, and the numbers behind each project's dashboard. */
@RestController
@RequestMapping("/api/projects")
public class ProjectApiController {

    private final ProjectService service;
    private final BugService bugs;

    public ProjectApiController(ProjectService service, BugService bugs) {
        this.service = service;
        this.bugs = bugs;
    }

    @GetMapping
    public List<Project> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return activeOnly ? service.active() : service.all();
    }

    /** Bug counts per project, as the left sidebar shows them. */
    @GetMapping("/counts")
    public Map<String, Long> counts() {
        return service.sidebarCounts();
    }

    /** One project's dashboard numbers. */
    @GetMapping("/{name}/dashboard")
    public Dashboard dashboard(@PathVariable String name) {
        return bugs.dashboard(name);
    }
}
