package com.bugtracking.controller;

import com.bugtracking.model.BoardColumn;
import com.bugtracking.model.Bug;
import com.bugtracking.model.ColumnColour;
import com.bugtracking.model.Comment;
import com.bugtracking.model.DocType;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;
import com.bugtracking.service.AttachmentService;
import com.bugtracking.service.BoardColumnService;
import com.bugtracking.service.BoardColumns;
import com.bugtracking.service.BugHistoryService;
import com.bugtracking.service.BugMarkdown;
import com.bugtracking.service.BugService;
import com.bugtracking.service.CommentService;
import com.bugtracking.service.Dashboard;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.SupportingDocService;
import com.bugtracking.service.TeamMemberService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.security.Principal;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/bugs")
public class BugController {

    private final BugService service;
    private final BoardColumnService board;
    private final ProjectService projects;
    private final CommentService comments;
    private final AttachmentService attachments;
    private final BugHistoryService history;
    private final TeamMemberService team;
    private final SupportingDocService docs;
    private final BugMarkdown markdown;

    public BugController(BugService service,
                         BoardColumnService board,
                         ProjectService projects,
                         CommentService comments,
                         AttachmentService attachments,
                         BugHistoryService history,
                         TeamMemberService team,
                         SupportingDocService docs,
                         BugMarkdown markdown) {
        this.service = service;
        this.board = board;
        this.projects = projects;
        this.comments = comments;
        this.attachments = attachments;
        this.history = history;
        this.team = team;
        this.docs = docs;
        this.markdown = markdown;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String project,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Severity severity,
                       @RequestParam(required = false) Environment environment,
                       @RequestParam(required = false) String assignee,
                       @RequestParam(required = false) String reporter,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) String view,
                       jakarta.servlet.http.HttpSession session,
                       RedirectAttributes redirect,
                       Model model) {

        // There is no "All projects" tab any more: a bare /bugs lands you in a
        // project. Only a bare one, though — a cross-project link such as
        // /bugs?assignee=X still means what it says.
        boolean noFilters = isBlank(status) && severity == null
                && environment == null && isBlank(assignee) && isBlank(reporter)
                && isBlank(keyword) && isBlank(sort);
        if (isBlank(project) && noFilters) {
            String landing = landingProject(session);
            if (landing != null) {
                // addAttribute, not addFlashAttribute: these belong in the query
                // string, and Spring encodes them — project names have spaces.
                redirect.addAttribute("project", landing);
                // Whichever view was asked for survives the redirect. Naming
                // "list" here was enough while it was the only one; with Stats
                // beside it, /bugs?view=stats landed on the board instead.
                if ("list".equals(view) || "stats".equals(view)) {
                    redirect.addAttribute("view", view);
                }
                return "redirect:/bugs";
            }
        }

        // Three ways to look at the same project: the board, the same bugs as a
        // list, and the numbers behind them. Anything else is the board.
        String mode = switch (view == null ? "" : view) {
            case "list", "stats" -> view;
            default -> "board";
        };

        // The dashboard describes the project; the board answers the filters.
        Dashboard dashboard = service.dashboard(project);
        List<Bug> bugs = service.findAll(project, status, severity,
                environment, assignee, reporter, keyword, sort);

        // The board this project actually runs, in the order it runs it.
        List<BoardColumn> boardColumns = board.forProject(project);

        model.addAttribute("bugs", bugs);
        model.addAttribute("boardColumns", boardColumns);
        model.addAttribute("columns", groupByColumn(boardColumns, bugs));
        // One lookup for the whole board, so a blocked card can say so.
        model.addAttribute("blockers", service.blockersFor(bugs));
        // A card shows how many comments and files a bug has. Two queries for
        // the whole board rather than two per card.
        List<Long> onScreen = bugs.stream().map(Bug::getId).toList();
        model.addAttribute("commentCounts", comments.countsFor(onScreen));
        model.addAttribute("fileCounts", attachments.countsFor(onScreen));
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("boardTotal", service.dashboard(null).total());
        model.addAttribute("activity", history.recent(project == null ? null : dashboard.bugIds()));
        model.addAttribute("selectedProject", project);
        model.addAttribute("severities", Severity.values());
        model.addAttribute("environments", Environment.values());
        // The palette a column may be painted in, for the board's own menus.
        model.addAttribute("colours", ColumnColour.values());
        // Who the people filters offer: this project's team, not the whole
        // company. A board is worked by the handful of people on it, and
        // scrolling eighteen names to find one of five is the reason those
        // lists were a "···" menu nobody opened. The filter currently set is
        // always included even when they are not on the project — otherwise
        // the one control that could clear it would not list it.
        List<String> onProject = projects.memberNamesOf(project);
        model.addAttribute("people", onProject.isEmpty()
                ? team.optionsIncluding(assignee, reporter)
                : withCurrent(onProject, assignee, reporter));
        // Who is on this project, which is not the same question as who is
        // carrying a bug on it: Stats shows the team including the people with
        // nothing on their plate, and that is usually the point.
        // Who actually carries work here, so the people filter shows faces
        // rather than a directory.
        model.addAttribute("workload", workloadIn(bugs, dashboard));
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedSeverity", severity);
        model.addAttribute("selectedEnvironment", environment);
        model.addAttribute("assignee", assignee);
        model.addAttribute("reporter", reporter);
        model.addAttribute("keyword", keyword);
        model.addAttribute("sort", sort);
        model.addAttribute("view", mode);
        model.addAttribute("filtered", !noFilters);
        model.addAttribute("q", BoardQuery.builder()
                .put("project", project)
                .put("status", status)
                .put("severity", severity)
                .put("environment", environment)
                .put("assignee", assignee)
                .put("reporter", reporter)
                .put("keyword", keyword)
                .put("sort", sort)
                .put("view", "board".equals(mode) ? null : mode)
                .build());
        return "bugs/list";
    }

    /**
     * A project's team, plus whoever the board is currently filtered to.
     *
     * <p>The second part is not a nicety: the filter is cleared by clicking the
     * person it is set to, so somebody assigned a bug here who is not on the
     * team would otherwise be filterable to and not filterable out of.
     */
    private static List<String> withCurrent(List<String> team, String... current) {
        LinkedHashSet<String> people = new LinkedHashSet<>(team);
        for (String name : current) {
            if (name != null && !name.isBlank()) {
                people.add(name.trim());
            }
        }
        return new ArrayList<>(people);
    }

    /**
     * The people on this project's board and how much each is carrying.
     * Counted over the whole project rather than the filtered result, so the
     * faces do not vanish the moment you filter down to one of them.
     */
    private Map<String, Long> workloadIn(List<Bug> filtered, Dashboard dashboard) {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<Bug> scope = dashboard.project() == null
                ? filtered
                : service.findAll(dashboard.project(), null, null, null, null, null, null, null);
        for (Bug bug : scope) {
            for (String who : bug.getAssignees()) {
                counts.merge(who.trim(), 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    /**
     * The bugs that survived the filters, bucketed into their board columns.
     *
     * <p>A bug holding a key this board has no column for lands in the first
     * one. That should not happen — deleting a column empties it first, and
     * moving a bug between projects remaps it — but a card that renders
     * nowhere is the one outcome worth ruling out entirely.
     */
    private static Map<BoardColumn, List<Bug>> groupByColumn(List<BoardColumn> columns, List<Bug> bugs) {
        Map<String, BoardColumn> byKey = new LinkedHashMap<>();
        Map<BoardColumn, List<Bug>> grouped = new LinkedHashMap<>();
        for (BoardColumn column : columns) {
            byKey.put(column.getStatusKey(), column);
            grouped.put(column, new ArrayList<>());
        }
        if (columns.isEmpty()) {
            return grouped;
        }
        for (Bug bug : bugs) {
            BoardColumn column = byKey.getOrDefault(bug.getStatus(), columns.get(0));
            grouped.get(column).add(bug);
        }
        return grouped;
    }

    /**
     * Where a bare /bugs lands: the project you were last in, or the first one
     * the switcher offers. A remembered project that has since been removed is
     * ignored rather than 404-ing you into an empty board.
     */
    private String landingProject(jakarta.servlet.http.HttpSession session) {
        var names = projects.sidebarCounts().keySet();
        Object remembered = session.getAttribute(GlobalModelAttributes.PROJECT_KEY);
        if (remembered instanceof String name && names.contains(name)) {
            return name;
        }
        return names.stream().findFirst().orElse(null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * What ⌘K / Ctrl+K asks for.
     *
     * <p>The board's filter box is still the real search — it owns the URL, it
     * survives JavaScript being off and it is what this links out to. This is
     * the shortcut laid over the top of it: the same keyword, across every
     * project rather than the one you happen to be standing in, answered
     * without leaving the page you are on.
     *
     * <p>It is a page route rather than another {@code /api/bugs} one on
     * purpose. {@code /api/**} is deliberately open for scripts; bug titles
     * typed into a search box should not be, so this rides the session like
     * every other thing behind the login.
     *
     * <p>Declared above {@code /bugs/{id}} for the same reason {@code /trash}
     * is: otherwise Spring reads "search" as a bug id and 400s before arriving.
     *
     * <p>A blank {@code q} is not an error — it answers the most recent bugs,
     * which is what the palette shows the moment it opens.
     */
    @GetMapping("/search")
    @ResponseBody
    public Map<String, Object> quickSearch(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) String project,
                                           @RequestParam(required = false, defaultValue = "8") int limit) {
        List<Bug> found = service.findAll(project, null, null, null, null, null, q, null);
        BoardColumns cols = board.snapshot();

        // Clamped rather than trusted: the palette asks for eight, and nothing
        // should be able to ask this route to serialise the whole tracker.
        int cap = Math.max(1, Math.min(limit, 25));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", isBlank(q) ? "" : q.trim());
        // The count is of everything that matched, not of what came back, so
        // the palette can offer "all 43" while listing eight.
        body.put("total", found.size());
        body.put("hits", found.stream().limit(cap).map(bug -> hit(bug, cols)).toList());
        return body;
    }

    /**
     * One result row's worth of a bug — what the palette draws and nothing
     * else. Description, steps and history stay on the server; a search box
     * has no use for them and they are the bulk of the entity.
     */
    private static Map<String, Object> hit(Bug bug, BoardColumns cols) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", bug.getId());
        row.put("title", bug.getTitle());
        row.put("project", bug.getProject());
        row.put("module", bug.getModule());
        // The bug stores a column key; the wording and the colour belong to the
        // column, exactly as in the templates.
        row.put("status", cols.label(bug));
        row.put("statusToken", cols.token(bug));
        row.put("severity", bug.getSeverity() == null ? null : bug.getSeverity().name());
        row.put("severityLabel", bug.getSeverity() == null ? null : bug.getSeverity().getLabel());
        row.put("assignees", bug.getAssignees());
        row.put("reportedBy", bug.getReportedBy());
        row.put("createdAt", bug.getCreatedAt());
        return row;
    }

    /**
     * The bin. Declared above {@code /bugs/{id}} deliberately — Spring would
     * otherwise try to read "trash" as a bug id and 400 before getting here.
     */
    @GetMapping("/trash")
    public String trash(Model model) {
        model.addAttribute("trashed", service.trash());
        return "bugs/trash";
    }

    @GetMapping("/new")
    public String createForm(@RequestParam(required = false) String project,
                             @RequestParam(required = false) String status,
                             jakarta.servlet.http.HttpSession session,
                             Principal principal,
                             Model model) {
        Bug bug = new Bug();

        // Raising from inside a project pre-selects it; you rarely mean a
        // different one. Falling back to the session's project means the same
        // is true of the New bug button anywhere else in the app.
        String chosen = project;
        if (chosen == null || chosen.isBlank()) {
            Object remembered = session.getAttribute(GlobalModelAttributes.PROJECT_KEY);
            if (remembered instanceof String name && !name.isBlank()) {
                chosen = name;
            }
        }
        if (chosen != null && !chosen.isBlank()) {
            bug.setProject(chosen.trim());
        }

        // Raising from a column's + starts the bug in that column. Checked
        // against the project's own board, so a status from somewhere else —
        // or one that has since been renamed away — falls back to the first
        // column rather than filing a bug nowhere.
        if (status != null && !status.isBlank()
                && board.has(bug.getProject(), status.trim())) {
            bug.setStatus(status.trim());
        }

        // You are the one filing it. Still a dropdown, so filing on someone
        // else's behalf stays possible.
        if (principal != null) {
            bug.setReportedBy(principal.getName());
        }

        model.addAttribute("bug", bug);
        addFormOptions(model, bug);
        return "bugs/form";
    }

    @PostMapping
    public String create(@Valid @org.springframework.web.bind.annotation.ModelAttribute("bug") Bug bug,
                         BindingResult result,
                         Model model,
                         @RequestParam(required = false) String actor,
                         @RequestParam(value = "files", required = false) MultipartFile[] files,
                         RedirectAttributes flash) {
        if (result.hasErrors()) {
            addFormOptions(model, bug);
            return "bugs/form";
        }
        Bug saved = service.save(bug, actor);

        // Files come with the bug rather than after it: a screenshot is usually
        // the clearest part of the report, and asking for it on a second screen
        // is how it never gets attached.
        String rejected = attachFiles(saved.getId(), files, actor);

        flash.addFlashAttribute("message", rejected == null
                ? "Bug #" + saved.getId() + " raised successfully."
                : "Bug #" + saved.getId() + " raised, but " + rejected);
        return "redirect:/bugs/" + saved.getId();
    }

    /**
     * Stores whatever was picked on the form. Returns null when everything
     * landed, or a sentence naming what did not — a rejected screenshot must
     * not lose you the bug you just typed out.
     */
    private String attachFiles(Long bugId, MultipartFile[] files, String actor) {
        if (files == null) {
            return null;
        }
        List<String> problems = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                attachments.store(bugId, file, actor);
            } catch (AttachmentService.RejectedFileException e) {
                problems.add(e.getMessage());
            }
        }
        return problems.isEmpty() ? null : String.join(" ", problems);
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Bug bug = service.findById(id);
        model.addAttribute("bug", bug);
        // Keeps the sidebar switcher pointed at the project this bug belongs to.
        model.addAttribute("selectedProject", bug.getProject());
        model.addAttribute("boardColumns", board.forProject(bug.getProject()));
        model.addAttribute("people", peopleFor(bug));
        // Split into what opens a thread and what answers it, so the page
        // draws one level of nesting without walking the list twice.
        CommentService.Conversation thread = comments.conversationFor(id);
        model.addAttribute("comments", thread.roots());
        model.addAttribute("replies", thread.replies());
        model.addAttribute("commentTotal", thread.roots().size()
                + thread.replies().values().stream().mapToInt(List::size).sum());
        model.addAttribute("attachments", attachments.forBug(id));
        // Keyed by comment id, so the thread draws each one's files without a
        // query per comment.
        model.addAttribute("commentFiles", attachments.byComment(id));
        model.addAttribute("docs", docs.forBug(id));
        model.addAttribute("docTypes", DocType.values());
        model.addAttribute("timeline", history.forBug(id));
        model.addAttribute("blocker", bug.getBlockedBy() == null
                ? null
                : service.blockersFor(List.of(bug)).get(bug.getBlockedBy()));
        model.addAttribute("blockerOptions", service.blockerOptions(id, bug.getProject()));
        return "bugs/detail";
    }

    /**
     * The whole bug as Markdown — the thing the Copy markdown button copies.
     *
     * <p>Built on the server rather than scraped off the page, because what a
     * developer wants to hand an assistant is more than the page happens to be
     * showing: the report, every comment, the write-ups QA typed and the
     * sheets they filled in, in one block. The board and the list copy from
     * here too, and neither of them has any of that in the DOM.
     *
     * <p>{@code text/plain} rather than {@code text/markdown} on purpose: this
     * URL is also the no-JavaScript route. The button is a real link to it, so
     * a browser with scripting off opens the Markdown in a tab to be selected
     * and copied by hand, and {@code text/markdown} would download a file
     * instead of showing one.
     */
    @GetMapping("/{id}/markdown")
    public ResponseEntity<String> markdown(@PathVariable Long id) {
        String text = markdown.forBug(id,
                ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString());
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(text);
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Bug bug = service.findById(id);
        model.addAttribute("bug", bug);
        addFormOptions(model, bug);
        return "bugs/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @org.springframework.web.bind.annotation.ModelAttribute("bug") Bug bug,
                         BindingResult result,
                         Model model,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        if (result.hasErrors()) {
            addFormOptions(model, bug);
            return "bugs/form";
        }
        service.update(id, bug, actor);
        flash.addFlashAttribute("message", "Bug #" + id + " updated.");
        return "redirect:/bugs/" + id;
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable Long id,
                               @RequestParam String status,
                               @RequestParam(required = false) String actor,
                               RedirectAttributes flash) {
        Bug moved = service.changeStatus(id, status, actor);
        flash.addFlashAttribute("message", "Bug #" + id + " moved to "
                + board.snapshot().label(moved) + ".");
        return "redirect:/bugs/" + id;
    }

    /**
     * Sets who is on the bug. Takes a repeated {@code assignees} parameter, and
     * an empty submission means nobody — so clearing every box unassigns it.
     */
    @PostMapping("/{id}/assign")
    public String assign(@PathVariable Long id,
                         @RequestParam(name = "assignees", required = false) List<String> assignees,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        Bug bug = service.assign(id, assignees == null ? List.of() : assignees, actor);
        flash.addFlashAttribute("message", bug.getAssignees().isEmpty()
                ? "Bug #" + id + " is now unassigned."
                : "Bug #" + id + " assigned to " + bug.getAssigneesLabel() + ".");
        return "redirect:/bugs/" + id;
    }

    /** Records — or clears, with a blank value — the bug holding this one up. */
    @PostMapping("/{id}/block")
    public String block(@PathVariable Long id,
                        @RequestParam(required = false) Long blockedBy,
                        @RequestParam(required = false) String actor,
                        RedirectAttributes flash) {
        Bug bug = service.block(id, blockedBy, actor);
        flash.addFlashAttribute("message", bug.getBlockedBy() == null
                ? "Bug #" + id + " is not blocked any more."
                : "Bug #" + id + " is blocked by BUG-" + bug.getBlockedBy() + ".");
        return "redirect:/bugs/" + id;
    }

    /**
     * A comment, and whatever was attached to it.
     *
     * <p>A screenshot is very often the comment — "it still does this, look" —
     * so it goes on in the same post rather than as a second trip through the
     * attachments box, which is how it ended up on the report instead of on
     * the reply that was about it.
     */
    @PostMapping("/{id}/comments")
    public String addComment(@PathVariable Long id,
                             @RequestParam String text,
                             @RequestParam(required = false) Long parentId,
                             @RequestParam(required = false) String actor,
                             @RequestParam(value = "files", required = false) MultipartFile[] files,
                             RedirectAttributes flash) {
        boolean hasFiles = files != null && Arrays.stream(files).anyMatch(f -> f != null && !f.isEmpty());
        // A file with no words is still a comment. Empty and file-less is not.
        if ((text == null || text.isBlank()) && !hasFiles) {
            flash.addFlashAttribute("message", "Comment was empty - nothing added.");
            return "redirect:/bugs/" + id + "#comments";
        }

        Comment saved = comments.add(id, parentId, text == null ? "" : text, actor);
        String rejected = hasFiles ? attachTo(id, saved.getId(), files, actor) : null;

        flash.addFlashAttribute("message", rejected == null
                ? "Comment added to bug #" + id + "."
                : "Comment added, but " + rejected);
        return "redirect:/bugs/" + id + "#comments";
    }

    /** Changing the words of a comment you wrote. */
    @PostMapping("/{id}/comments/{commentId}")
    public String editComment(@PathVariable Long id,
                              @PathVariable Long commentId,
                              @RequestParam String text,
                              @RequestParam(required = false) String actor,
                              RedirectAttributes flash) {
        try {
            comments.edit(id, commentId, text, actor);
            flash.addFlashAttribute("message", "Comment updated.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/bugs/" + id + "#comments";
    }

    /** Removing one you wrote, its replies and every file on any of them. */
    @PostMapping("/{id}/comments/{commentId}/delete")
    public String deleteComment(@PathVariable Long id,
                                @PathVariable Long commentId,
                                @RequestParam(required = false) String actor,
                                RedirectAttributes flash) {
        try {
            comments.remove(id, commentId, actor);
            flash.addFlashAttribute("message", "Comment deleted.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/bugs/" + id + "#comments";
    }

    /**
     * Files onto one comment. Each is reported separately so a batch of five
     * that holds one .exe still attaches the other four.
     */
    private String attachTo(Long bugId, Long commentId, MultipartFile[] files, String actor) {
        List<String> refused = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                attachments.store(bugId, commentId, file, actor);
            } catch (AttachmentService.RejectedFileException e) {
                refused.add(e.getMessage());
            }
        }
        return refused.isEmpty() ? null : String.join(" ", refused);
    }

    @PostMapping("/{id}/attachments")
    public String upload(@PathVariable Long id,
                         @RequestParam("file") MultipartFile file,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        service.findById(id);                       // 404 before touching the disk
        try {
            attachments.store(id, file, actor);
            flash.addFlashAttribute("message", "Attachment added to bug #" + id + ".");
        } catch (AttachmentService.RejectedFileException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/bugs/" + id + "#attachments";
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<Resource> download(@PathVariable Long id, @PathVariable Long attachmentId)
            throws MalformedURLException {
        var attachment = attachments.findById(attachmentId);
        if (!attachment.getBugId().equals(id)) {
            return ResponseEntity.notFound().build();
        }
        Path path = attachments.pathOf(attachment);
        Resource resource = new UrlResource(path.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        // Worked out from the stored name rather than read back off the row:
        // the type on the row came from the uploader once, and anything the
        // caller chose must not decide what we serve it as.
        MediaType type = AttachmentService.mediaTypeFor(attachment.getFileName());

        // Images and PDFs open in the browser; everything else downloads.
        // filename= is not URL-decoded by browsers, so percent-encoding into it
        // saved "my report.png" as "my%20report.png". A plain name needs no
        // encoding at all; anything else gets the RFC 6266 filename* beside it,
        // which is the half browsers actually read.
        String name = attachment.getFileName();
        ContentDisposition.Builder builder = AttachmentService.isInlineSafe(type)
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        ContentDisposition disposition = (isPlainAscii(name)
                ? builder.filename(name)
                : builder.filename(name, StandardCharsets.UTF_8))
                .build();

        long length;
        try {
            length = resource.contentLength();
        } catch (IOException e) {
            length = attachment.getSizeBytes();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(type)
                .contentLength(length)
                .body(resource);
    }

    /** Whether a name can go in Content-Disposition as it stands, with no encoding. */
    private static boolean isPlainAscii(String name) {
        return name.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
    }

    @PostMapping("/{id}/attachments/{attachmentId}/delete")
    public String removeAttachment(@PathVariable Long id,
                                   @PathVariable Long attachmentId,
                                   @RequestParam(required = false) String actor,
                                   RedirectAttributes flash) {
        var attachment = attachments.findById(attachmentId);
        if (!attachment.getBugId().equals(id)) {
            // Deleting by guessing an id from another bug is not a thing.
            flash.addFlashAttribute("message", "That file is not on this bug.");
            return "redirect:/bugs/" + id + "#attachments";
        }
        attachments.delete(attachmentId, actor);
        flash.addFlashAttribute("message", "Removed " + attachment.getFileName() + ".");
        return "redirect:/bugs/" + id + "#attachments";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false) String actor,
                         RedirectAttributes flash) {
        Bug bug = service.delete(id, actor);
        flash.addFlashAttribute("message", "BUG-" + id + " moved to the trash.");
        flash.addFlashAttribute("undoBugId", id);
        return "redirect:/bugs" + (bug.getProject() == null
                ? "" : "?project=" + org.springframework.web.util.UriUtils.encode(
                        bug.getProject(), StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/restore")
    public String restore(@PathVariable Long id,
                          @RequestParam(required = false) String actor,
                          @RequestParam(required = false) String from,
                          RedirectAttributes flash) {
        service.restore(id, actor);
        flash.addFlashAttribute("message", "BUG-" + id + " is back.");
        // Undo from a board toast goes back to the bug; the trash page stays put.
        return "trash".equals(from) ? "redirect:/bugs/trash" : "redirect:/bugs/" + id;
    }

    @PostMapping("/{id}/purge")
    public String purge(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.purge(id);
            flash.addFlashAttribute("message", "BUG-" + id + " deleted for good.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return "redirect:/bugs/trash";
    }

    private void addFormOptions(Model model, Bug bug) {
        List<String> projectOptions = projects.optionsIncluding(bug.getProject());

        model.addAttribute("severities", Severity.values());
        model.addAttribute("environments", Environment.values());
        model.addAttribute("projectOptions", projectOptions);
        // The status picker offers this project's columns. Every project the
        // form can switch to goes in too, so changing the project swaps the
        // status options without a round trip — see form.html.
        model.addAttribute("boardColumns", board.forProject(bug.getProject()));
        model.addAttribute("columnsJson", columnsJson(projectOptions));
        model.addAttribute("people", peopleFor(bug));
        model.addAttribute("selectedProject", bug.getProject());
        model.addAttribute("blockerOptions", service.blockerOptions(bug.getId(), bug.getProject()));
    }

    /**
     * Each project's columns as JSON, carried on the status select so changing
     * the project on the form re-offers the right ones without a round trip.
     *
     * <p>Only key and wording: the form has no use for a column's colour or its
     * notification setting, and the less of an entity that leaves the server the
     * fewer surprises there are in what gets serialised.
     *
     * <p>Nothing depends on it. With JavaScript off the select keeps the
     * columns of the project the form opened on, and a status that does not
     * belong to the project it is saved against is remapped to that board's
     * first column by {@code BugService.update}.
     */
    private String columnsJson(List<String> projectNames) {
        Map<String, List<Map<String, String>>> columns = new LinkedHashMap<>();
        for (String name : projectNames) {
            columns.put(name, board.forProject(name).stream()
                    .map(column -> Map.of("status", column.getStatusKey(),
                                          "label", column.getLabel()))
                    .toList());
        }
        try {
            return new ObjectMapper().writeValueAsString(columns);
        } catch (JsonProcessingException e) {
            return "{}";                      // the select simply stops re-offering
        }
    }

    /** The team, plus any name already on this bug that has since been hidden. */
    /**
     * Who a bug may be assigned to or reported by: the team on <em>its</em>
     * project, plus whoever is already named on it.
     *
     * <p>A bug belongs to a project and a project is worked by a handful of
     * people, so offering the whole company was a list to scroll rather than a
     * list to choose from. The second part is not a nicety — somebody assigned
     * before they came off the team has to stay in the list, or the control
     * that could take them off would not show them.
     *
     * <p>Falls back to the whole roster when the project has nobody on it yet:
     * an empty picker is worse than a long one, and it would make a bug on a
     * teamless project impossible to assign at all.
     */
    private List<String> peopleFor(Bug bug) {
        List<String> current = new ArrayList<>(bug.getAssignees());
        current.add(bug.getReportedBy());
        String[] named = current.toArray(String[]::new);

        List<String> onProject = projects.memberNamesOf(bug.getProject());
        return onProject.isEmpty() ? team.optionsIncluding(named) : withCurrent(onProject, named);
    }
}
