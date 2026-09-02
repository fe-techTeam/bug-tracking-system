package com.bugtracking.controller;

import com.bugtracking.config.SecurityProperties;
import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.TeamMemberRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The sign-in page. Spring Security handles the POST and the logout itself. */
@Controller
public class AuthController {

    private final SecurityProperties security;
    private final TeamMemberRepository team;
    private final ObjectMapper json;

    public AuthController(SecurityProperties security, TeamMemberRepository team, ObjectMapper json) {
        this.security = security;
        this.team = team;
        this.json = json;
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error, Model model) {
        if (error != null) {
            model.addAttribute("loginError", "That email and password do not match.");
        }
        model.addAttribute("quickFillAccounts", quickFillAccounts());
        return "login";
    }

    /** Empty when quick fill is off, which is what keeps the passwords out of the page. */
    private String quickFillAccounts() {
        if (!security.isQuickFill()) {
            return "";
        }

        List<Map<String, String>> accounts = new ArrayList<>();
        for (SecurityProperties.Account account : security.getAccounts()) {
            String email = account.getEmail() == null ? "" : account.getEmail().trim();
            if (email.isEmpty()) {
                continue;
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("email", email);
            entry.put("password", account.getPassword());
            entry.put("name", team.findByEmailIgnoreCase(email).map(TeamMember::getName).orElse(email));
            accounts.add(entry);
        }

        try {
            return json.writeValueAsString(accounts);
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
