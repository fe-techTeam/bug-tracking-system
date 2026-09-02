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
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * One entry in a project's documents area: a folder, a page, a sheet, an
 * uploaded file or a link.
 *
 * <p><b>Why one table for five things.</b> They are one thing to the person
 * using them — a folder of stuff about the project — and everything that
 * happens to an entry happens to all five equally: it is listed, renamed,
 * moved to another folder, and deleted. Splitting them would mean five
 * queries to draw one folder, five move routes, and a sort that has to merge
 * five lists back into the order they are displayed in. {@link ResourceKind}
 * is the discriminator and the only thing that decides which columns matter.
 *
 * <p><b>Why a project id, not a project name.</b> {@link Bug} stores the name,
 * because bugs predate the projects table and had to keep reading correctly.
 * Nothing here predates anything: a document only ever exists inside a project
 * that exists, is only reachable through that project's page, and should
 * follow it through a rename rather than be orphaned by one.
 *
 * <p>The tree is a plain {@code parent_id} — null at the root. Folders here are
 * a handful deep, so walking up for a breadcrumb costs nothing and there is no
 * materialised path to keep in step with a move.
 */
@Entity
@Table(name = "project_resources", indexes = {
        @Index(name = "ix_resources_folder", columnList = "project_id, parent_id")
})
public class ProjectResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** The folder this sits in, or null at the root of the project. */
    @Column(name = "parent_id")
    private Long parentId;

    /*
     * VARCHAR rather than a native ENUM, for the reason spelled out on Bug:
     * H2 pins an ENUM column to the constants that existed when it was made.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ResourceKind kind = ResourceKind.FOLDER;

    @NotBlank(message = "Give it a name")
    @Size(max = 200, message = "Name must be 200 characters or fewer")
    @Column(nullable = false, length = 200)
    private String name;

    /** A line of context, shown under the name. Any kind may carry one. */
    @Size(max = 400, message = "Description must be 400 characters or fewer")
    @Column(length = 400)
    private String note;

    /** LINK only: where it points. Only http(s) is ever stored. */
    @Column(length = 2000)
    private String url;

    /** FILE only: the name on disk — random, so two "screenshot.png" cannot collide. */
    @Column(name = "stored_name", length = 120)
    private String storedName;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes")
    private long sizeBytes;

    /**
     * PAGE and SHEET only: the Markdown as typed, or the sheet's JSON.
     * Sized rather than a LOB on purpose — a plain VARCHAR is the same column
     * on H2 and on Postgres, where {@code @Lob} would quietly become a
     * large-object handle instead of text.
     */
    @Column(length = 200_000)
    private String content;

    /** "412 words" / "12 rows × 6 columns" / "1.2 MB" — worked out on save. */
    @Column(length = 80)
    private String summary;

    @Column(name = "created_by", length = 80)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

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

    // ------------------------------------------------------------- for views

    @Transient
    public boolean isFolder() {
        return kind == ResourceKind.FOLDER;
    }

    @Transient
    public boolean isDocument() {
        return kind != null && kind.isDocument();
    }

    @Transient
    public boolean isPage() {
        return kind == ResourceKind.PAGE;
    }

    @Transient
    public boolean isSheet() {
        return kind == ResourceKind.SHEET;
    }

    @Transient
    public boolean isFile() {
        return kind == ResourceKind.FILE;
    }

    @Transient
    public boolean isLink() {
        return kind == ResourceKind.LINK;
    }

    /** Whether the browser can draw this file itself, so the card shows it. */
    @Transient
    public boolean isImage() {
        return kind == ResourceKind.FILE && contentType != null && contentType.startsWith("image/");
    }

    @Transient
    public boolean isPdf() {
        return kind == ResourceKind.FILE && "application/pdf".equals(contentType);
    }

    /** "412 KB" reads better than a byte count. */
    @Transient
    public String getReadableSize() {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024 * 1024) {
            return Math.round(sizeBytes / 1024.0) + " KB";
        }
        return Math.round(sizeBytes / (1024.0 * 1024.0) * 10) / 10.0 + " MB";
    }

    /**
     * The bit of a link worth reading at a glance — "figma.com" rather than
     * eighty characters of path. Falls back to the whole thing if it will not
     * parse, because a link nobody can read is still better than a blank.
     */
    @Transient
    public String getHost() {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return url;
        }
    }

    /** The extension, upper-cased, for the tile on a file with no thumbnail. */
    @Transient
    public String getFileLabel() {
        if (name == null) {
            return "FILE";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "FILE";
        }
        String extension = name.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
        return extension.length() > 4 ? extension.substring(0, 4) : extension;
    }

    // ------------------------------------------------------- getters/setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public ResourceKind getKind() {
        return kind;
    }

    public void setKind(ResourceKind kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getStoredName() {
        return storedName;
    }

    public void setStoredName(String storedName) {
        this.storedName = storedName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
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
