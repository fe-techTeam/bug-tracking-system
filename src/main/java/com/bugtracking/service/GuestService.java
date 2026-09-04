package com.bugtracking.service;

import com.bugtracking.config.AccountPrincipal;
import com.bugtracking.model.Attachment;
import com.bugtracking.model.Bug;
import com.bugtracking.model.Comment;
import com.bugtracking.model.Project;
import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.BugRepository;
import com.bugtracking.repository.ProjectRepository;
import com.bugtracking.repository.TeamMemberRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything a client may do, and the only place that decides it.
 *
 * <p>One class rather than a check per handler, because "is this bug yours" has
 * to have exactly one answer. Spread across four controller methods it becomes
 * four answers, three of which are right — and the fourth is somebody reading
 * another company's bug through a URL they guessed.
 *
 * <p>The rules, in full:
 *
 * <ul>
 *   <li>A client sees the bugs whose {@code guestId} is their own row's id.
 *       Not the ones bearing their name, and not the ones on their project:
 *       their own. A name is not unique and a project is not private.</li>
 *   <li>Of a bug they own, they see the report they wrote, where it sits on the
 *       board, the comments somebody shared with them, and the files that came
 *       with those. Never the internal thread, the assignees, the history or
 *       anything about another bug.</li>
 *   <li>They can raise a report on the one project they are bound to, and reply
 *       on a bug they own. Nothing else writes.</li>
 * </ul>
 *
 * <p>Each read below starts from the account and narrows, rather than starting
 * from an id in the URL and checking afterwards. That ordering is the whole
 * defence: there is no path here that loads a bug first and asks about it
 * second, so there is nothing for a forgotten check to leak.
 */
@Service
@Transactional
public class GuestService {

    /**
     * What was filed, and what did not make it onto it.
     *
     * <p>Two values because a refused file must not take the words with it. The
     * report or the reply is saved either way, and {@code rejected} is the
     * sentence naming what was left off — somebody who attached a .zip should
     * be told it was not accepted, not handed back an empty box and the
     * paragraph they typed gone.
     */
    public record Filed(Long bugId, String rejected) {
    }

    /** Thrown for a bug this client does not own, or one that does not exist. */
    public static class NotYoursException extends RuntimeException {
        public NotYoursException(String message) {
            super(message);
        }
    }

    /** Thrown when the account is a guest of nothing — an admin left it unbound. */
    public static class NoProjectException extends RuntimeException {
        public NoProjectException(String message) {
            super(message);
        }
    }

    private final TeamMemberRepository team;
    private final ProjectRepository projects;
    private final BugRepository bugs;
    private final BugService bugService;
    private final CommentService comments;
    private final AttachmentService attachments;
    private final NotificationService notifications;
    private final ProjectService projectService;
    private final GuestRateLimit limit;

    public GuestService(TeamMemberRepository team,
                        ProjectRepository projects,
                        BugRepository bugs,
                        BugService bugService,
                        CommentService comments,
                        AttachmentService attachments,
                        NotificationService notifications,
                        ProjectService projectService,
                        GuestRateLimit limit) {
        this.team = team;
        this.projects = projects;
        this.bugs = bugs;
        this.bugService = bugService;
        this.comments = comments;
        this.attachments = attachments;
        this.notifications = notifications;
        this.projectService = projectService;
        this.limit = limit;
    }

    /**
     * The row behind the signed-in client, read fresh every request.
     *
     * <p>Not taken from the principal, which was built at sign-in and would go
     * on saying so for as long as the session lasts. An admin moving a client to
     * another project, or switching them off, has to take effect on their next
     * click rather than whenever they next happen to log in.
     */
    @Transactional(readOnly = true)
    public TeamMember account(Authentication authentication) {
        Long id = AccountPrincipal.of(authentication)
                .flatMap(AccountPrincipal::memberId)
                .orElseThrow(() -> new NotYoursException("You are not signed in."));
        return team.findById(id)
                .filter(TeamMember::isGuest)
                .filter(TeamMember::isActive)
                .orElseThrow(() -> new NotYoursException("That account no longer has access."));
    }

    /** The one project this client may report on. */
    @Transactional(readOnly = true)
    public Project project(TeamMember guest) {
        Long id = guest.getGuestProjectId();
        return Optional.ofNullable(id)
                .flatMap(projects::findById)
                .orElseThrow(() -> new NoProjectException(
                        "This account is not attached to a project yet. Whoever set it up"
                                + " needs to finish that before you can send anything in."));
    }

    /**
     * Everything this client has raised on the project they are bound to,
     * newest first.
     *
     * <p>Both halves, and the project half is not redundant: {@link #report}
     * applies it too, so without it here the list would offer a card that the
     * page behind it refuses to open. A bug moved to another project on the
     * board leaves this client's portal entirely, which is the safer of the two
     * readings — a report reassigned to work nobody meant them to see should
     * stop being theirs, and somebody who loses sight of their own bug will
     * say so.
     */
    @Transactional(readOnly = true)
    public List<Bug> reports(TeamMember guest) {
        String project = project(guest).getName();
        return bugs.findByGuestIdAndDeletedAtIsNullOrderByCreatedAtDesc(guest.getId()).stream()
                .filter(bug -> project.equalsIgnoreCase(bug.getProject()))
                .toList();
    }

    /**
     * One of their reports.
     *
     * <p>The project is checked as well as the owner, so moving a bug to another
     * project on the board takes it out of the portal it came in through. That
     * is the safer of the two readings: a report reassigned to work nobody meant
     * this client to see should stop being visible to them, and a client who
     * loses sight of their own bug will say so.
     *
     * <p>Missing and not-yours answer identically, because the difference
     * between them is the sentence "that bug exists".
     */
    @Transactional(readOnly = true)
    public Bug report(TeamMember guest, Long bugId) {
        return bugs.findById(bugId)
                .filter(bug -> !bug.isDeleted())
                .filter(bug -> guest.getId().equals(bug.getGuestId()))
                .filter(bug -> sameProject(bug, guest))
                .orElseThrow(() -> new NotYoursException("That report is not one of yours."));
    }

    private boolean sameProject(Bug bug, TeamMember guest) {
        return projects.findById(guest.getGuestProjectId())
                .map(project -> project.getName().equalsIgnoreCase(bug.getProject()))
                .orElse(false);
    }

    /** The part of a report's thread this client may read. */
    @Transactional(readOnly = true)
    public List<Comment> conversation(TeamMember guest, Long bugId) {
        report(guest, bugId);
        return comments.sharedFor(bugId);
    }

    /** The files on a report this client may download. */
    @Transactional(readOnly = true)
    public List<Attachment> files(TeamMember guest, Long bugId) {
        report(guest, bugId);
        return attachments.sharedFor(bugId);
    }

    /**
     * One file, checked three ways: it is on a bug they own, it is on
     * <em>that</em> bug, and it is shared. The middle one matters — without it
     * an id from another bug's page would be served on the strength of the URL
     * naming a bug they do own.
     */
    @Transactional(readOnly = true)
    public Attachment file(TeamMember guest, Long bugId, Long attachmentId) {
        report(guest, bugId);
        Attachment file = attachments.findById(attachmentId);
        if (!bugId.equals(file.getBugId()) || !file.isShared()) {
            throw new NotYoursException("That file is not on one of your reports.");
        }
        return file;
    }

    /**
     * Raises a report on the client's own project.
     *
     * <p>Everything that is not in {@link GuestReport} is decided here: the
     * project comes from the account, the column from the project's board, the
     * reporter from the account's name, and nobody is assigned. There is
     * deliberately no path by which a request can influence any of them.
     */
    public Filed raise(TeamMember guest, GuestReport form, MultipartFile[] files) {
        limit.checkReport(guest.getId());
        Project project = project(guest);

        Bug bug = new Bug();
        bug.setTitle(form.getTitle().trim());
        bug.setDescription(form.getDescription() == null ? null : form.getDescription().trim());
        bug.setSeverity(form.getSeverity());
        bug.setEnvironment(form.getEnvironment());
        bug.setProject(project.getName());
        bug.setReportedBy(guest.getName());
        bug.setGuestId(guest.getId());
        bug.setViaGuest(true);

        Bug saved = bugService.save(bug, guest.getName());
        String rejected = attach(saved.getId(), null, files, guest.getName());
        tellTheTeam(project, saved, guest,
                " raised BUG-" + saved.getId() + " on " + project.getName() + ": " + saved.getTitle());
        return new Filed(saved.getId(), rejected);
    }

    /**
     * A reply from the client on their own report.
     *
     * <p>Stored shared, and not by a choice anybody makes: a client who cannot
     * read back what they just wrote would think it had not been sent. The
     * direction the flag exists to guard is the other one.
     */
    public Filed reply(TeamMember guest, Long bugId, String text, MultipartFile[] files) {
        Bug bug = report(guest, bugId);
        limit.checkReply(guest.getId());
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Write something before you send it.");
        }

        Comment saved = comments.add(bugId, null, text, guest.getName(), true);
        String rejected = attach(bugId, saved.getId(), files, guest.getName());
        projects.findByNameIgnoreCase(bug.getProject())
                .ifPresent(project -> tellTheTeam(project, bug, guest,
                        " replied on BUG-" + bugId + ": " + bug.getTitle()));
        return new Filed(bugId, rejected);
    }

    /**
     * Stores what came with the report, and says what did not land.
     *
     * <p>A rejected screenshot must not lose somebody the report they typed
     * out, so this collects the problems and hands them back rather than
     * throwing: this class is transactional, and an exception here would roll
     * the report or the reply back along with the file. The internal form takes
     * the same shape for the same reason.
     */
    private String attach(Long bugId, Long commentId, MultipartFile[] files, String by) {
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
                        + " files can come with one message; the rest were not attached.");
                break;
            }
            // Asked before it is written, not caught after. This class is
            // transactional and AttachmentService is too, so a rejection thrown
            // from inside store() marks this whole transaction rollback-only —
            // the report or the reply would go down with the file, which is the
            // one thing this method exists to prevent.
            String refusal = attachments.refusalFor(file, true);
            if (refusal != null) {
                problems.add(refusal);
                continue;
            }
            attachments.storeFromGuest(bugId, commentId, file, by);
            stored++;
        }
        return problems.isEmpty() ? null : String.join(" ", problems);
    }

    /**
     * Tells the project's team that a client has been in touch.
     *
     * <p>A notification and not an email, on purpose: {@code NotificationService}
     * decides who hears what and the mailer mirrors it, so raising the
     * notification is what makes this arrive in the bell <em>and</em> in an
     * inbox. Sending mail from here would be a second answer to the same
     * question.
     */
    private void tellTheTeam(Project project, Bug bug, TeamMember guest, String what) {
        for (String person : projectService.memberNamesOf(project.getName())) {
            notifications.notify(bug.getId(), "guest", person, guest.getName() + " (client)" + what);
        }
    }
}
