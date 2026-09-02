package com.bugtracking.model;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * A document a tester writes against a bug: a page of notes or a sheet of test
 * cases. Everything about the testing that is longer than a comment and not a
 * file lives here, so it stays with the bug instead of in someone's drive.
 *
 * <p>Holds the bug id as a plain column, like {@link Comment} and
 * {@link Attachment}: {@code open-in-view} is off, so a lazy relationship would
 * blow up the moment a template touched it.
 *
 * <p>The body is one text column whatever the type — Markdown for a page, and
 * for a sheet the JSON {@code {"cols":6,"rows":[["a","b"],…]}}. One column
 * means one save path, one export path and no second table to keep in step;
 * {@code SupportingDocService} is the only thing that reads the shape.
 */
@Entity
@Table(name = "bug_docs")
public class SupportingDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bug_id", nullable = false)
    private Long bugId;

    @NotBlank(message = "Give the document a name")
    @Size(max = 150, message = "Name must be 150 characters or fewer")
    @Column(nullable = false, length = 150)
    private String title;

    /*
     * VARCHAR rather than a native ENUM, for the reason spelled out on Bug:
     * H2 pins an ENUM column to the constants that existed when it was created.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private DocType type = DocType.PAGE;

    /**
     * Markdown, or the sheet's JSON. Sized rather than a LOB on purpose: a
     * plain VARCHAR is the same column on H2 and on Postgres, where {@code @Lob}
     * would quietly become a large-object handle instead of text.
     */
    @Column(length = 100_000)
    private String content = "";

    /** "412 words" / "12 rows × 6 columns" — worked out on save, for the list. */
    @Column(length = 80)
    private String summary;

    @Column(name = "created_by", length = 80)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Who touched it last. Docs are worked on by more than one person. */
    @Column(name = "updated_by", length = 80)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Transient
    public boolean isSheet() {
        return type == DocType.SHEET;
    }

    @Transient
    public boolean isPage() {
        return type == DocType.PAGE;
    }

    /** True while nobody has typed anything, so the list can say so. */
    @Transient
    public boolean isBlank() {
        return summary == null || summary.isBlank();
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public DocType getType() {
        return type;
    }

    public void setType(DocType type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content == null ? "" : content;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
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

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
