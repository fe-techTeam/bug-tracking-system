package com.bugtracking.service;

import com.bugtracking.config.EmailProperties;
import com.bugtracking.model.Bug;
import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.AttachmentRepository;
import com.bugtracking.repository.BugRepository;
import com.bugtracking.repository.CommentRepository;
import com.bugtracking.repository.TeamMemberRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Sends the notifications that are already being raised, by email as well.
 *
 * <p>Every message here mirrors one bell: this is deliberately not a second,
 * parallel set of rules about who hears what. {@link NotificationService}
 * decides that — including the "you did it yourself" and "you were told this a
 * moment ago" guards — and hands the decision here. If a change ever ought to
 * email somebody it does not notify, the notification is what should be added.
 *
 * <p>Three things make it safe to leave switched on:
 *
 * <ul>
 *   <li><b>Off by default.</b> Without {@code bugtracking.mail.enabled} and a
 *       {@code spring.mail.host} nothing is built and no connection is opened.
 *   <li><b>After the commit, never inside it.</b> A message about a change that
 *       then rolled back cannot be recalled, so the send is registered as an
 *       after-commit hook and skipped entirely if the transaction fails.
 *   <li><b>It cannot break a save.</b> The send happens on another thread and
 *       every failure is caught and logged. A dead SMTP server makes the bell
 *       the only thing that rang; it does not make raising a bug fail.
 * </ul>
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    /** A due date is a day, so it is written as one. */
    private static final DateTimeFormatter DUE =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    /** How much of a report travels in the email before it is cut. */
    private static final int DESCRIPTION_CHARS = 1200;

    private final EmailProperties properties;
    private final ObjectProvider<JavaMailSender> senders;
    private final TeamMemberRepository team;
    private final BugRepository bugs;
    private final BoardColumnService columns;
    private final CommentRepository comments;
    private final AttachmentRepository attachments;
    private final SpringTemplateEngine templates;
    private final Executor executor;

    /**
     * The connection host, read straight off the environment rather than
     * through Spring's own MailProperties: it is only ever used to tell
     * "enabled but never configured" from "deliberately off", and that answer
     * should not depend on which auto-configuration happened to run.
     */
    private final String host;

    public EmailService(EmailProperties properties,
                        ObjectProvider<JavaMailSender> senders,
                        TeamMemberRepository team,
                        BugRepository bugs,
                        BoardColumnService columns,
                        CommentRepository comments,
                        AttachmentRepository attachments,
                        SpringTemplateEngine templates,
                        @Qualifier("mailExecutor") Executor executor,
                        @Value("${spring.mail.host:}") String host) {
        this.properties = properties;
        this.senders = senders;
        this.team = team;
        this.bugs = bugs;
        this.columns = columns;
        this.comments = comments;
        this.attachments = attachments;
        this.templates = templates;
        this.executor = executor;
        this.host = host == null ? "" : host.trim();
    }

    /** Whether a message would actually go anywhere if one were built. */
    public boolean isConfigured() {
        return properties.isEnabled() && !host.isEmpty()
                && properties.getFrom() != null && !properties.getFrom().isBlank()
                && senders.getIfAvailable() != null;
    }

    /** What to say in the startup log about the state of all this. */
    public String describe() {
        if (!properties.isEnabled()) {
            return "Off. Set bugtracking.mail.enabled=true in .env to turn it on.";
        }
        if (host.isEmpty()) {
            return "On, but no SMTP host is set — nothing can be sent. Set SMTP_HOST in .env.";
        }
        if (properties.getFrom() == null || properties.getFrom().isBlank()) {
            return "On, but no From address is set. Set MAIL_FROM in .env.";
        }
        if (senders.getIfAvailable() == null) {
            return "On, but Spring built no mail sender. Check spring.mail.* in .env.";
        }
        return "Sending as " + properties.getFrom() + " through " + host + ".";
    }

    /**
     * Emails one person about one bug, once the change is safely committed.
     *
     * <p>Named for a person, not an address: notifications are addressed to
     * display names, because that is what a bug stores. Somebody who is only a
     * name on a bug — no row, or a row with no address — simply gets no email,
     * which is the same answer the roster gives.
     */
    public void bugNotification(Long bugId, String type, String recipientName, String message) {
        if (!isConfigured() || bugId == null) {
            return;
        }
        String to = addressFor(recipientName);
        if (to == null) {
            return;
        }
        afterCommit(() -> sendAboutBug(bugId, type, to, recipientName, message));
    }

    /**
     * The same for something that is not a bug — a mention in a project
     * document. There is nothing to look up, so the message and the link are
     * the whole of it.
     */
    public void linkNotification(String link, String type, String recipientName, String message) {
        if (!isConfigured() || link == null || link.isBlank()) {
            return;
        }
        String to = addressFor(recipientName);
        if (to == null) {
            return;
        }
        String url = properties.trimmedBaseUrl() + link;
        afterCommit(() -> send(to, subject(type, null),
                render(recipientName, headline(type, null), message, null, List.of(), null, url, "Open it"),
                plain(recipientName, message, List.of(), null, url)));
    }

    // ------------------------------------------------------------- building

    private void sendAboutBug(Long bugId, String type, String to, String recipientName, String message) {
        Bug bug = bugs.findById(bugId).orElse(null);
        if (bug == null) {
            return;                     // deleted between the notification and here
        }

        BoardColumns board = columns.snapshot();
        List<String[]> facts = properties.isIncludeDetails() ? factsOf(bug, board) : List.of();
        String description = properties.isIncludeDetails() ? clip(bug.getDescription()) : null;
        String url = properties.trimmedBaseUrl() + "/bugs/" + bug.getId();
        String headline = headline(type, bug);
        String title = "BUG-" + bug.getId() + " — " + bug.getTitle();

        send(to, subject(type, bug),
                render(recipientName, headline, message, title, facts, description, url, "Open the bug"),
                plain(recipientName, message, facts, description, url));
    }

    /** The rail of the bug page, as label/value pairs, skipping what is empty. */
    private List<String[]> factsOf(Bug bug, BoardColumns board) {
        List<String[]> facts = new ArrayList<>();
        fact(facts, "Status", board.label(bug));
        fact(facts, "Severity", bug.getSeverity() == null ? null : bug.getSeverity().getLabel());
        fact(facts, "Environment", bug.getEnvironment() == null ? null : bug.getEnvironment().getLabel());
        fact(facts, "Project", bug.getProject());
        fact(facts, "Module", bug.getModule());
        fact(facts, "Raised by", bug.getReportedBy());
        fact(facts, "Assigned to", bug.getAssignees().isEmpty() ? "Nobody" : bug.getAssigneesLabel());

        // Only while it is still open, exactly as the bug page reads it:
        // waiting on something already fixed is not being blocked.
        if (bug.getBlockedBy() != null) {
            bugs.findById(bug.getBlockedBy())
                    .filter(board::openWork)
                    .ifPresent(blocker -> fact(facts, "Blocked by",
                            "BUG-" + blocker.getId() + " — " + blocker.getTitle()));
        }

        long files = attachments.countByBugId(bug.getId());
        long said = comments.countByBugId(bug.getId());
        if (files > 0) {
            fact(facts, "Attachments", files + (files == 1 ? " file" : " files"));
        }
        if (said > 0) {
            fact(facts, "Comments", String.valueOf(said));
        }

        // Same question the board asks, so an email never says "overdue" about
        // something that is finished.
        fact(facts, "Due", bug.getDueDate() == null ? null
                : DUE.format(bug.getDueDate()) + (board.late(bug) ? " — overdue" : ""));
        fact(facts, "Raised", stamp(bug.getCreatedAt()));
        fact(facts, "Last updated", stamp(bug.getUpdatedAt()));
        return facts;
    }

    private static void fact(List<String[]> facts, String label, String value) {
        if (value != null && !value.isBlank()) {
            facts.add(new String[]{label, value.trim()});
        }
    }

    /**
     * What happened, in the reader's terms rather than the type's. The
     * notification's own message says the same thing in a sentence underneath;
     * this is what an inbox is scanned by.
     */
    private static String phrase(String type) {
        return switch (type == null ? "" : type) {
            case "assigned" -> "Assigned to you";
            case "unassigned" -> "No longer yours";
            case "mention" -> "You were mentioned";
            case "guest" -> "From a client";
            case "fixed" -> "Marked fixed";
            case "reopened" -> "Reopened";
            case "closed" -> "Closed";
            default -> "Updated";
        };
    }

    /** The same, with the bug it is about, for the line at the top of the message. */
    private static String headline(String type, Bug bug) {
        return bug == null ? phrase(type) : phrase(type) + ": BUG-" + bug.getId();
    }

    /**
     * The subject line: the bug number first, so a thread of them sorts and
     * scans by the thing they are about rather than by what happened to it.
     */
    private String subject(String type, Bug bug) {
        String prefix = properties.getSubjectPrefix() == null ? "" : properties.getSubjectPrefix().trim();
        String head = prefix.isEmpty() ? "" : prefix + " ";
        return bug == null
                ? head + phrase(type)
                : head + "BUG-" + bug.getId() + " " + phrase(type).toLowerCase(Locale.ENGLISH)
                        + " — " + bug.getTitle();
    }

    private String render(String recipient, String headline, String message, String title,
                          List<String[]> facts, String description, String url, String action) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("recipient", recipient);
        context.setVariable("headline", headline);
        context.setVariable("message", message);
        context.setVariable("title", title);
        context.setVariable("facts", facts);
        context.setVariable("description", description);
        context.setVariable("url", url);
        context.setVariable("action", action);
        context.setVariable("product", properties.getFromName());
        return templates.process("email/notification", context);
    }

    /**
     * The same message as text. Not a courtesy: a multipart message whose text
     * half is missing is a message some clients show as empty, and some spam
     * filters score for.
     */
    private static String plain(String recipient, String message, List<String[]> facts,
                                String description, String url) {
        StringBuilder out = new StringBuilder(512);
        if (recipient != null && !recipient.isBlank()) {
            out.append("Hello ").append(recipient).append(",\n\n");
        }
        out.append(message == null ? "" : message).append("\n\n");
        for (String[] fact : facts) {
            out.append(fact[0]).append(": ").append(fact[1]).append('\n');
        }
        if (description != null && !description.isBlank()) {
            out.append('\n').append(description).append('\n');
        }
        out.append('\n').append(url).append('\n');
        return out.toString();
    }

    // ------------------------------------------------------------- sending

    /** Hands one message to the executor and makes sure nothing escapes it. */
    private void send(String to, String subject, String html, String text) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null) {
            return;
        }
        executor.execute(() -> {
            try {
                deliver(sender, to, subject, html, text);
                log.debug("Emailed {}: {}", to, subject);
            } catch (Exception e) {
                // Never rethrown. The bell has already rung, the change is
                // saved, and the person who made it is not the person who can
                // fix a mail server.
                log.warn("Could not email {} about \"{}\": {}", to, subject, rootMessage(e));
            }
        });
    }

    private void deliver(JavaMailSender sender, String to, String subject, String html, String text)
            throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setTo(to);
        helper.setSubject(subject);
        if (properties.getFromName() != null && !properties.getFromName().isBlank()) {
            helper.setFrom(properties.getFrom(), properties.getFromName());
        } else {
            helper.setFrom(properties.getFrom());
        }
        if (properties.getReplyTo() != null && !properties.getReplyTo().isBlank()) {
            helper.setReplyTo(properties.getReplyTo());
        }
        helper.setText(text, html);
        sender.send(message);
    }

    /**
     * Runs the send once the surrounding transaction has committed, or straight
     * away when there is no transaction to wait for.
     *
     * <p>Registering rather than sending is the whole point: notifications are
     * raised inside the same transaction as the change they are about, and a
     * change that then fails must not have been emailed. An email cannot be
     * rolled back.
     */
    private static void afterCommit(Runnable send) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send.run();
            }
        });
    }

    /**
     * The one address to use for a display name, or null.
     *
     * <p>Null for four separate reasons, all of them "say nothing rather than
     * guess": nobody by that name, more than one person by that name, somebody
     * who has been hidden, or somebody with no address on file.
     */
    private String addressFor(String recipientName) {
        if (recipientName == null || recipientName.isBlank()) {
            return null;
        }
        List<TeamMember> found = team.findByNameIgnoreCase(recipientName.trim()).stream()
                .filter(TeamMember::isActive)
                .toList();
        if (found.size() != 1) {
            if (found.size() > 1) {
                log.warn("{} people are called \"{}\", so no email was sent about their notification. "
                        + "Distinct names are what makes an address findable.", found.size(), recipientName);
            }
            return null;
        }
        String email = found.get(0).getEmail();
        return email == null || email.isBlank() ? null : email.trim();
    }

    // ------------------------------------------------------------- plumbing

    private static String clip(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String text = description.trim();
        return text.length() <= DESCRIPTION_CHARS
                ? text
                : text.substring(0, DESCRIPTION_CHARS) + "\n\n… the rest is on the bug.";
    }

    private static String stamp(LocalDateTime when) {
        return when == null ? null : STAMP.format(when);
    }

    /** The message worth showing: the bottom of the cause chain, not the wrapper. */
    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
