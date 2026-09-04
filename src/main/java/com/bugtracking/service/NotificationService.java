package com.bugtracking.service;

import com.bugtracking.model.Notification;
import com.bugtracking.repository.NotificationRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Notifications for the events the BRD calls out: assigned, fixed, reopened and
 * closed. Each one rings the bell in the app and, when SMTP is configured, is
 * emailed to the same person - see {@link EmailService}, which mirrors this
 * class rather than deciding anything of its own.
 *
 * <p>A notification is addressed to one person, and the reads here answer for
 * that person only. Who that is comes from the security context rather than a
 * parameter, so the navbar, the bell popover and /notifications are all scoped
 * without every caller having to remember to pass a name.
 */
@Service
@Transactional
public class NotificationService {

    /**
     * How close together two notifications about the same bug, of the same
     * kind, to the same person have to be before the second is dropped. One
     * save can reach somebody down several paths at once; a window this short
     * catches that without ever swallowing a genuine second event.
     */
    private static final Duration REPEAT_WINDOW = Duration.ofSeconds(30);

    /**
     * The same window for a document mention, but far wider. A document saves
     * itself as you type, and every one of those saves finds the same "@Anita"
     * still sitting in the text — thirty seconds of writing would otherwise be
     * a notification a minute. An hour means she is told once about being
     * tagged, and told again only if it happens in a later sitting.
     */
    private static final Duration MENTION_WINDOW = Duration.ofHours(1);

    private final NotificationRepository repository;

    /**
     * The same news, by email. Every send mirrors a row saved here — this class
     * decides who hears what, including the two guards above, and the mailer
     * only carries the decision out. It is inert unless SMTP is configured, so
     * on an instance with no mail server nothing below behaves differently.
     */
    private final EmailService email;

    public NotificationService(NotificationRepository repository, EmailService email) {
        this.repository = repository;
        this.email = email;
    }

    /**
     * Raises a notification, unless it would be noise: nobody to send it to,
     * the same thing this person was told a moment ago, or news of something
     * they did themselves.
     */
    public void notify(Long bugId, String type, String recipient, String message) {
        String to = worthTelling(recipient);
        if (to == null) {
            return;
        }
        if (repository.existsByBugIdAndTypeAndRecipientIgnoreCaseAndCreatedAtAfter(
                bugId, type, to, LocalDateTime.now().minus(REPEAT_WINDOW))) {
            return;
        }
        repository.save(new Notification(bugId, type, to, message));
        // Queued, not sent: the mailer waits for this transaction to commit, so
        // a change that rolls back is never announced. See EmailService.
        email.bugNotification(bugId, type, to, message);
    }

    /**
     * The same, for something that is not a bug — a project document, say.
     * {@code link} is where the bell takes you and doubles as the identity the
     * repeat guard compares on, so saving a document five times while you
     * write it does not ring five bells for the same mention.
     *
     * <p>Only ever called with a path this app built. Nothing user-typed
     * reaches it: a link somebody pastes into a document is content, and is
     * rendered as content.
     */
    public void notifyAt(String link, String type, String recipient, String message) {
        String to = worthTelling(recipient);
        if (to == null || link == null || link.isBlank()) {
            return;
        }
        if (repository.existsByLinkAndTypeAndRecipientIgnoreCaseAndCreatedAtAfter(
                link, type, to, LocalDateTime.now().minus(MENTION_WINDOW))) {
            return;
        }
        repository.save(new Notification(link, type, to, message));
        email.linkNotification(link, type, to, message);
    }

    /** The recipient to use, or null when raising this would be noise. */
    private String worthTelling(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return null;
        }
        String to = recipient.trim();
        return isCurrentUser(to) ? null : to;    // you clicked it; you do not need telling
    }

    /** The signed-in person's queue, newest first. */
    @Transactional(readOnly = true)
    public List<Notification> recent() {
        return recentFor(currentUser());
    }

    /** One named person's queue — for callers that know who they are asking about. */
    @Transactional(readOnly = true)
    public List<Notification> recentFor(String recipient) {
        return recipient == null || recipient.isBlank()
                ? List.of()
                : repository.findTop50ByRecipientIgnoreCaseOrderByCreatedAtDescIdDesc(recipient.trim());
    }

    /**
     * Everybody's, newest first. Only for the JSON API, which is open and has
     * no signed-in person to answer for.
     */
    @Transactional(readOnly = true)
    public List<Notification> all() {
        return repository.findTop50ByOrderByCreatedAtDescIdDesc();
    }

    /**
     * The head of {@link #recent()}, for the bell popover. The full list stays
     * one click away on /notifications rather than being poured into a menu.
     */
    @Transactional(readOnly = true)
    public List<Notification> latest(int limit) {
        List<Notification> all = recent();
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        String me = currentUser();
        return me == null ? 0 : repository.countByRecipientIgnoreCaseAndReadFalse(me);
    }

    /** Marks one of your own as read. Somebody else's is left alone. */
    public void markRead(Long id) {
        String me = currentUser();
        repository.findById(id)
                .filter(n -> me != null && me.equalsIgnoreCase(trimmed(n.getRecipient())))
                .ifPresent(n -> {
                    n.setRead(true);
                    repository.save(n);
                });
    }

    /** Clears the signed-in person's queue, and nobody else's. */
    public int markAllRead() {
        String me = currentUser();
        if (me == null) {
            return 0;
        }
        List<Notification> unread = repository.findByRecipientIgnoreCaseAndReadFalse(me);
        unread.forEach(n -> n.setRead(true));
        repository.saveAll(unread);
        return unread.size();
    }

    public void deleteForBug(Long bugId) {
        repository.deleteByBugId(bugId);
    }

    /**
     * Clears the notifications that point at pages which no longer exist — a
     * document that was deleted, and everything that was in the folder it sat
     * in. A bell that opens a "this does not exist" page is worse than one that
     * was never rung.
     */
    public void deleteForLinks(List<String> links) {
        if (links != null && !links.isEmpty()) {
            repository.deleteByLinkIn(links);
        }
    }

    /**
     * The signed-in person's display name, or null when nobody is signed in —
     * the JSON API, the seeders and the startup runners all reach this service
     * with no security context at all.
     */
    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return trimmed(auth.getName());
    }

    private static boolean isCurrentUser(String name) {
        String me = currentUser();
        return me != null && me.equalsIgnoreCase(name);
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
