package com.bugtracking.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The sign-in page. Spring Security handles the POST and the logout itself. */
@Controller
public class AuthController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        Model model) {
        if (error != null) {
            model.addAttribute("loginError", "That email and password do not match.");
        }
        if (logout != null) {
            model.addAttribute("loginNotice", "You are signed out.");
        }
        return "login";
    }
}
