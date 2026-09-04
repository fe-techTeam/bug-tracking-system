package com.bugtracking.controller;

import com.bugtracking.model.Attachment;
import com.bugtracking.model.Bug;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Project;
import com.bugtracking.model.Severity;
import com.bugtracking.model.TeamMember;
import com.bugtracking.service.AttachmentService;
import com.bugtracking.service.BoardColumnService;
import com.bugtracking.service.BoardColumns;
import com.bugtracking.service.GuestRateLimit;
import com.bugtracking.service.GuestReport;
import com.bugtracking.service.GuestService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The client portal: raise a report, follow the ones you raised, and nothing
 * else.
 *
 * <p>These four screens are the entire surface a guest account can reach. That
 * is enforced in {@code SecurityConfig}, not here — the filter chain ends with
 * {@code anyRequest().hasRole("USER")} and a guest is not given ROLE_USER, so
 * every other route in this app is already closed to them and so is every route
 * added after this file was written. What this controller adds is the second
 * half: which <em>rows</em>, which is {@link GuestService}'s job and is asked of
 * it on every single request below.
 *
 * <p>Nothing here reads an id and then checks it. Every handler starts from the
 * account, asks the service for something belonging to that account, and works
 * with what comes back. An id in the URL is a filter, never a grant.
 */
@Controller
@RequestMapping("/portal")
public class GuestPortalController {

    private final GuestService guests;
    private final BoardColumnService board;
    private final AttachmentService attachments;

    public GuestPortalController(GuestService guests,
                                 BoardColumnService board,
                                 AttachmentService attachments) {
        this.guests = guests;
        this.board = board;
        this.attachments = attachments;
    }

    @GetMapping
    public String reports(Authentication auth, Model model) {
        TeamMember me = guests.account(auth);
        Project project = guests.project(me);

        model.addAttribute("me", me);
        model.addAttribute("project", project);
        model.addAttribute("reports", guests.reports(me));
        model.addAttribute("cols", columnsOf(project.getName()));
        return "portal/reports";
    }

    @GetMapping("/new")
    public String form(Authentication auth, Model model) {
        TeamMember me = guests.account(auth);
        model.addAttribute("me", me);
        model.addAttribute("project", guests.project(me));
        model.addAttribute("report", new GuestReport());
        addOptions(model);
        return "portal/new";
    }

    @PostMapping
    public String raise(@Valid @org.springframework.web.bind.annotation.ModelAttribute("report")
                        GuestReport report,
                        BindingResult result,
                        @RequestParam(value = "files", required = false) MultipartFile[] files,
                        Authentication auth,
                        Model model,
                        RedirectAttributes flash) {
        TeamMember me = guests.account(auth);
        if (result.hasErrors()) {
            model.addAttribute("me", me);
            model.addAttribute("project", guests.project(me));
            addOptions(model);
            return "portal/new";
        }
        GuestService.Filed filed = guests.raise(me, report, files);
        flash.addFlashAttribute("message", filed.rejected() == null
                ? "Thanks — that is with the team as BUG-" + filed.bugId() + "."
                : "Sent as BUG-" + filed.bugId() + ", but " + filed.rejected());
        return "redirect:/portal/reports/" + filed.bugId();
    }

    @GetMapping("/reports/{id}")
    public String report(@PathVariable Long id, Authentication auth, Model model) {
        TeamMember me = guests.account(auth);
        Bug bug = guests.report(me, id);

        model.addAttribute("me", me);
        model.addAttribute("project", guests.project(me));
        model.addAttribute("bug", bug);
        model.addAttribute("cols", columnsOf(bug.getProject()));
        model.addAttribute("conversation", guests.conversation(me, id));
        model.addAttribute("files", guests.files(me, id));
        return "portal/report";
    }

    @PostMapping("/reports/{id}/replies")
    public String reply(@PathVariable Long id,
                        @RequestParam String text,
                        @RequestParam(value = "files", required = false) MultipartFile[] files,
                        Authentication auth,
                        RedirectAttributes flash) {
        TeamMember me = guests.account(auth);
        GuestService.Filed filed = guests.reply(me, id, text, files);
        flash.addFlashAttribute("message", filed.rejected() == null
                ? "Sent." : "Sent, but " + filed.rejected());
        return "redirect:/portal/reports/" + id + "#conversation";
    }

    /**
     * A file off one of their own reports.
     *
     * <p>The same shape as the internal download, with the checks done by
     * {@link GuestService#file} first: on a bug they own, on <em>that</em> bug,
     * and shared. The type is worked out from the stored name rather than read
     * off the row for the reason the internal one gives — what a caller once
     * claimed a file was must not decide how it is served back.
     */
    @GetMapping("/reports/{id}/files/{fileId}")
    public ResponseEntity<Resource> file(@PathVariable Long id,
                                         @PathVariable Long fileId,
                                         Authentication auth) throws MalformedURLException {
        Attachment file = guests.file(guests.account(auth), id, fileId);
        Path path = attachments.pathOf(file);
        Resource resource = new UrlResource(path.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        MediaType type = AttachmentService.mediaTypeFor(file.getFileName());
        String name = file.getFileName();
        ContentDisposition.Builder builder = AttachmentService.isInlineSafe(type)
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        ContentDisposition disposition = (isPlainAscii(name)
                ? builder.filename(name)
                : builder.filename(name, StandardCharsets.UTF_8))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(type)
                .body(resource);
    }

    private static boolean isPlainAscii(String name) {
        return name.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
    }

    /**
     * The status lookup every portal page needs, holding one project's board.
     *
     * <p>The same {@code cols} helper the rest of the app draws a status badge
     * with, so a column renamed or recoloured on the board reads the same way
     * here — but built from one project's columns rather than every project's.
     * {@code GlobalModelAttributes} hands a guest an empty one; this replaces it
     * with exactly the board they are allowed to know about.
     */
    private BoardColumns columnsOf(String project) {
        return new BoardColumns(board.forProject(project));
    }

    private void addOptions(Model model) {
        model.addAttribute("severities", Severity.values());
        model.addAttribute("environments", Environment.values());
        model.addAttribute("maxFiles", AttachmentService.GUEST_MAX_FILES);
    }

    /**
     * Every way this controller can refuse, answered on the portal's own pages.
     *
     * <p>Local handlers rather than {@code GlobalExceptionHandler}'s, which
     * renders the app shell — and the app shell is the project switcher, the
     * bell and the roster. Answering a client's mistyped URL with a page listing
     * every project in the company would undo the whole of the rest of this.
     */
    @ExceptionHandler(GuestService.NotYoursException.class)
    public String notYours(GuestService.NotYoursException e, RedirectAttributes flash) {
        flash.addFlashAttribute("message", e.getMessage());
        return "redirect:/portal";
    }

    @ExceptionHandler({GuestRateLimit.TooOftenException.class,
                       AttachmentService.RejectedFileException.class,
                       IllegalArgumentException.class})
    public String refused(RuntimeException e, RedirectAttributes flash) {
        flash.addFlashAttribute("message", e.getMessage());
        return "redirect:/portal";
    }

    /**
     * An account nobody finished setting up. Its own page rather than a
     * redirect, because a redirect to /portal would land on the read that threw
     * this and loop.
     */
    @ExceptionHandler(GuestService.NoProjectException.class)
    public String noProject(GuestService.NoProjectException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "portal/unavailable";
    }
}
