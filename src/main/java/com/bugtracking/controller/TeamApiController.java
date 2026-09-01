package com.bugtracking.controller;

import com.bugtracking.model.TeamMember;
import com.bugtracking.service.TeamMemberService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The names a script can put in "reportedBy" or "assignedTo". */
@RestController
@RequestMapping("/api/team")
public class TeamApiController {

    private final TeamMemberService service;

    public TeamApiController(TeamMemberService service) {
        this.service = service;
    }

    @GetMapping
    public List<TeamMember> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return activeOnly ? service.active() : service.all();
    }
}
