package com.bugtracking.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.LocalDateTime;

/**
 * An in-app notification raised when something happens to a bug, or when
 * somebody is tagged in a project document.
 *
 * <p>The recipient is the name already on the bug (its assignee or reporter)
 * rather than a user account: a display name, which is also what sign-in makes
 * the principal, so the two line up without a join. Matched case-insensitively
 * everywhere, because it is typed by hand in places. Swapping this for a real
 * user id later is a one-column change.
 *
 * <p>Where it takes you is {@link #getHref()}. A bug notification carries a
 * bug id and nothing else, as it always has; anything that is not about a bug
 * carries an explicit {@code link} instead. Exactly one of the two is set —
 * hence {@code bug_id} being nullable, which {@code SchemaUpgrade} relaxes on
 * a database created before project documents existed.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bug_id")
    private Long bugId;

    /** "assigned", "fixed", "reopened" or "closed" - drives the icon and colour. */
    @Column(nullable = false, length = 30)
    private String type;

    @Column(length = 80)
    private String recipient;

    @Column(nullable = false, length = 255)
    private String message;

    /**
     * Where clicking it goes, for a notification that is not about a bug.
     * An app-relative path, only ever built here — never a URL out of a form.
     */
    @Column(length = 300)
    private String link;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Notification() {
    }

    public Notification(Long bugId, String type, String recipient, String message) {
        this.bugId = bugId;
        this.type = type;
        this.recipient = recipient;
        this.message = message;
    }

    /** For anything that is not a bug: the link is the whole address. */
    public Notification(String link, String type, String recipient, String message) {
        this.link = link;
        this.type = type;
        this.recipient = recipient;
        this.message = message;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Where this notification takes you. The templates ask for this rather
     * than building "/bugs/" + id themselves, so a notification about a
     * document lands on the document.
     */
    @Transient
    public String getHref() {
        if (link != null && !link.isBlank()) {
            return link;
        }
        return bugId == null ? "/notifications" : "/bugs/" + bugId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBugId() {
        return bugId;
    }

    public void setBugId(Long bugId) {
        this.bugId = bugId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
