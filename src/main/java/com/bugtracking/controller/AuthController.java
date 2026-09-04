package com.bugtracking.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The sign-in page. Spring Security handles the POST and the logout itself.
 *
 * <p>The page offers nothing but the two fields. It used to be able to fill
 * them in from the accounts configured in application.properties, which meant
 * a working password sat in the HTML of a page served to anyone who asked for
 * it. Accounts are rows in {@code team_members} now, with only a hash on them,
 * and there is nothing left here that could be filled in.
 */
@Controller
public class AuthController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error, Model model) {
        if (error != null) {
            model.addAttribute("loginError", "That email and password do not match.");
        }
        return "login";
    }
}
