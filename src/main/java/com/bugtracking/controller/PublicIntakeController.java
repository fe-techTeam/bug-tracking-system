package com.bugtracking.controller;

import com.bugtracking.model.Environment;
import com.bugtracking.model.Project;
import com.bugtracking.model.Severity;
import com.bugtracking.service.AttachmentService;
import com.bugtracking.service.GuestRateLimit;
import com.bugtracking.service.GuestService;
import com.bugtracking.service.PublicIntakeService;
import com.bugtracking.service.PublicReport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

/**
 * The one part of this app a stranger can open: a project's public intake link.
 *
 * <p>There is no session behind any of it. The token in the path is the whole
 * grant and {@link PublicIntakeService} is what turns it into a project, so a
 * token nobody recognises gets a 404 page rather than the sign-in redirect the
 * rest of the app answers with — a stranger has no account to sign into, and
 * saying so would only be a dead end.
 *
 * <p>The pages render out of {@code templates/public/}, whose shell carries no
 * navbar, no bell and no name. Nothing here reads or writes anything but the
 * one report being filed.
 */
@Controller
@RequestMapping("/public/{token}")
public class PublicIntakeController {

    private final PublicIntakeService intake;

    public PublicIntakeController(PublicIntakeService intake) {
        this.intake = intake;
    }

    @GetMapping
    public String form(@PathVariable String token, Model model, HttpServletResponse response) {
        Optional<Project> project = intake.byToken(token);
        if (project.isEmpty()) {
            return unavailable(model, response);
        }
        model.addAttribute("project", project.get());
        model.addAttribute("report", new PublicReport());
        addOptions(model);
        return "public/new";
    }

    @PostMapping
    public String raise(@PathVariable String token,
                        @Valid @ModelAttribute("report") PublicReport report,
                        BindingResult result,
                        @RequestParam(value = "files", required = false) MultipartFile[] files,
                        HttpServletRequest request,
                        HttpServletResponse response,
                        Model model,
                        RedirectAttributes flash) {
        Optional<Project> project = intake.byToken(token);
        if (project.isEmpty()) {
            return unavailable(model, response);
        }
        if (result.hasErrors()) {
            model.addAttribute("project", project.get());
            addOptions(model);
            return "public/new";
        }
        GuestService.Filed filed = intake.raise(token, report, files,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        if (filed.rejected() != null) {
            flash.addFlashAttribute("message", "Sent, but " + filed.rejected());
        }
        return "redirect:/public/" + token + "/done?bug=" + filed.bugId();
    }

    @GetMapping("/done")
    public String done(@PathVariable String token,
                       @RequestParam(required = false) Long bug,
                       Model model,
                       HttpServletResponse response) {
        Optional<Project> project = intake.byToken(token);
        if (project.isEmpty()) {
            return unavailable(model, response);
        }
        model.addAttribute("project", project.get());
        model.addAttribute("bugId", bug);
        return "public/done";
    }

    private static String unavailable(Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        model.addAttribute("message", "This link is not valid any more. Ask the team for a fresh one.");
        return "public/unavailable";
    }

    private static void addOptions(Model model) {
        model.addAttribute("severities", Severity.values());
        model.addAttribute("environments", Environment.values());
        model.addAttribute("maxFiles", AttachmentService.GUEST_MAX_FILES);
    }

    // Handled here rather than in GlobalExceptionHandler, which renders the app shell a stranger must never see.
    @ExceptionHandler(PublicIntakeService.UnknownLinkException.class)
    public String unknownLink(Model model, HttpServletResponse response) {
        return unavailable(model, response);
    }

    @ExceptionHandler({GuestRateLimit.TooOftenException.class,
                       AttachmentService.RejectedFileException.class,
                       IllegalArgumentException.class})
    public String refused(RuntimeException e, @PathVariable String token, RedirectAttributes flash) {
        flash.addFlashAttribute("message", e.getMessage());
        return "redirect:/public/" + token;
    }
}
