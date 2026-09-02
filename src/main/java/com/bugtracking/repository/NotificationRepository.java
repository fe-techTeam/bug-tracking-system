package com.bugtracking.repository;

import com.bugtracking.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A notification belongs to the person named in {@code recipient}, so every
 * read here takes one. The recipient is a display name typed by a human in
 * places, hence {@code IgnoreCase} throughout — "Nishana R" and "nishana r"
 * are one queue.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop50ByRecipientIgnoreCaseOrderByCreatedAtDescIdDesc(String recipient);

    long countByRecipientIgnoreCaseAndReadFalse(String recipient);

    List<Notification> findByRecipientIgnoreCaseAndReadFalse(String recipient);

    /**
     * Whether this person has just been told this about this bug. One save can
     * reach the same person down more than one path, and two identical bells a
     * second apart are noise rather than news.
     */
    boolean existsByBugIdAndTypeAndRecipientIgnoreCaseAndCreatedAtAfter(
            Long bugId, String type, String recipient, LocalDateTime since);

    /** The same guard for a notification that carries a link instead of a bug. */
    boolean existsByLinkAndTypeAndRecipientIgnoreCaseAndCreatedAtAfter(
            String link, String type, String recipient, LocalDateTime since);

    /** Everything, for the JSON API — which has no signed-in person to scope to. */
    List<Notification> findTop50ByOrderByCreatedAtDescIdDesc();

    void deleteByBugId(Long bugId);

    /**
     * For notifications that point at something being destroyed. Bug ones are
     * cleared by bug id above; anything carrying a link is cleared by the link,
     * which is the only handle it has on what it was about.
     */
    void deleteByLinkIn(java.util.Collection<String> links);
}
