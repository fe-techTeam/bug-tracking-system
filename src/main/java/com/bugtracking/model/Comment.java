package com.bugtracking.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * A note on a bug. Holds the bug id as a plain column rather than a JPA
 * relationship: with open-in-view off, a lazy association would blow up in the
 * templates, and nothing here needs to navigate back to the Bug.
 */
@Entity
@Table(name = "bug_comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bug_id", nullable = false)
    private Long bugId;

    /**
     * The comment this one answers, or null when it opens a thread.
     *
     * <p>The one it actually answers, at any depth: a reply can be replied to,
     * and that reply can be replied to again. It used to be flattened to the
     * top of its thread, which made every answer look like an answer to the
     * first comment and left a long exchange impossible to follow.
     *
     * <p>Depth is unbounded here and capped only where it is <em>drawn</em> —
     * see the {@code branch} fragment in {@code bugs/detail.html}. A staircase
     * that keeps going walks off the right-hand edge of the column, but that is
     * a question about a column, not about what answers what.
     */
    @Column(name = "parent_id")
    private Long parentId;

    @NotBlank(message = "Comment cannot be empty")
    @Size(max = 2000, message = "Comment must be 2000 characters or fewer")
    @Column(nullable = false, length = 2000)
    private String text;

    @Size(max = 80)
    @Column(name = "created_by", length = 80)
    private String createdBy;

    /**
     * Whether the client who raised this bug may read it.
     *
     * <p>False unless somebody ticked the box, and that direction is the only
     * safe one: a thread is where a team is candid about what is actually
     * broken, and a default of "shared" would have published every word already
     * written on the strength of a schema change. Sharing has to be a decision
     * somebody made about one comment.
     *
     * <p>The box only appears on a bug a client can see at all — see
     * {@code bugs/detail.html} — so nothing changes on the bugs nobody outside
     * is reading. A guest's own replies are stored shared, because a reply the
     * writer cannot see back is not a conversation.
     */
    @Column(nullable = false)
    private boolean shared = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** When it was last changed, or null if it never has been. */
    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /** True once somebody has changed the words, so the thread can say so. */
    public boolean isEdited() {
        return editedAt != null;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public LocalDateTime getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(LocalDateTime editedAt) {
        this.editedAt = editedAt;
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

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isShared() {
        return shared;
    }

    public void setShared(boolean shared) {
        this.shared = shared;
    }
}
