package com.bugtracking.service;

import com.bugtracking.config.EmailProperties;
import com.bugtracking.model.Bug;
import com.bugtracking.model.Project;
import com.bugtracking.model.PublicTokens;
import com.bugtracking.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
@Transactional
public class PublicIntakeService {

    public static class UnknownLinkException extends RuntimeException {
        public UnknownLinkException() {
            super("This link is not valid any more.");
        }
    }

    private final ProjectRepository projects;
    private final BugService bugs;
    private final AttachmentService attachments;
    private final NotificationService notifications;
    private final ProjectService projectService;
    private final EmailProperties mail;
    private final GuestRateLimit limit;

    public PublicIntakeService(ProjectRepository projects,
                               BugService bugs,
                               AttachmentService attachments,
                               NotificationService notifications,
                               ProjectService projectService,
                               EmailProperties mail,
                               GuestRateLimit limit) {
        this.projects = projects;
        this.bugs = bugs;
        this.attachments = attachments;
        this.notifications = notifications;
        this.projectService = projectService;
        this.mail = mail;
        this.limit = limit;
    }

    @Transactional(readOnly = true)
    public Optional<Project> byToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return projects.findByPublicTokenAndActiveTrue(token.trim());
    }

    public GuestService.Filed raise(String token, PublicReport form, MultipartFile[] files,
                                    String clientIp, String userAgent) {
        Project project = byToken(token).orElseThrow(UnknownLinkException::new);
        limit.checkPublic(clientIp == null ? "unknown" : clientIp);

        String name = form.getReporterName().trim();
        Bug bug = new Bug();
        bug.setTitle(form.getTitle().trim());
        bug.setDescription(withBrowser(form.getDescription(), userAgent));
        bug.setSeverity(form.getSeverity());
        bug.setEnvironment(form.getEnvironment());
        bug.setProject(project.getName());
        bug.setReportedBy(name);
        bug.setReporterEmail(form.getReporterEmail().trim().toLowerCase(Locale.ROOT));
        bug.setViaPublic(true);

        Bug saved = bugs.save(bug, name);
        String rejected = attach(saved.getId(), files, name);
        for (String person : projectService.memberNamesOf(project.getName())) {
            notifications.notify(saved.getId(), "public", person,
                    name + " (external) raised BUG-" + saved.getId() + " on " + project.getName()
                            + ": " + saved.getTitle());
        }
        return new GuestService.Filed(saved.getId(), rejected);
    }

    public String regenerate(Long projectId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("No project found with id " + projectId));
        project.setPublicToken(PublicTokens.fresh());
        projects.save(project);
        return project.getPublicToken();
    }

    @Transactional(readOnly = true)
    public String publicUrl(Project project) {
        return mail.trimmedBaseUrl() + "/public/" + project.getPublicToken();
    }

    // The browser line follows Jira's issue collector: the one fact a stranger never thinks to give.
    private static String withBrowser(String description, String userAgent) {
        String body = description == null ? "" : description.trim();
        if (userAgent == null || userAgent.isBlank()) {
            return body;
        }
        return body + "\n\nBrowser: " + userAgent.trim();
    }

    // Refusals are collected rather than thrown: this class is transactional, so a bad file would take the report with it.
    private String attach(Long bugId, MultipartFile[] files, String by) {
        if (files == null) {
            return null;
        }
        List<String> problems = new ArrayList<>();
        int stored = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (stored >= AttachmentService.GUEST_MAX_FILES) {
                problems.add("Only " + AttachmentService.GUEST_MAX_FILES
                        + " files can come with one report; the rest were not attached.");
                break;
            }
            String refusal = attachments.refusalFor(file, true);
            if (refusal != null) {
                problems.add(refusal);
                continue;
            }
            attachments.storeFromGuest(bugId, null, file, by);
            stored++;
        }
        return problems.isEmpty() ? null : String.join(" ", problems);
    }
}
