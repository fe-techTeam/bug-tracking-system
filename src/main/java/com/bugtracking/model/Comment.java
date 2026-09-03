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
     * <p>One level and no more. A reply to a reply is a conversation that has
     * outgrown a bug, and an unbounded tree in a 380px column is unreadable —
     * so a reply to a reply is filed against the same parent, which keeps every
     * exchange as one flat run under the thing it is about.
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
}
