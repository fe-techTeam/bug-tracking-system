package com.bugtracking.controller;

import com.bugtracking.model.ThemePreference;
import com.bugtracking.service.TeamMemberService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

@Controller
@RequestMapping("/me")
public class MeController {

    private final TeamMemberService team;

    public MeController(TeamMemberService team) {
        this.team = team;
    }

    @PostMapping("/theme")
    public ResponseEntity<Void> theme(@RequestParam ThemePreference theme, Principal principal) {
        team.setTheme(principal.getName(), theme);
        return ResponseEntity.noContent().build();
    }
}
