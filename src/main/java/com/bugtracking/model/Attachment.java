package com.bugtracking.model;

import com.bugtracking.service.AttachmentService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Metadata for a file attached to a bug. The bytes live on disk under
 * {@code bugtracking.attachments.dir}; only the pointer is stored here, so the
 * H2 file does not balloon with screenshots.
 */
@Entity
@Table(name = "bug_attachments")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bug_id", nullable = false)
    private Long bugId;

    /**
     * The comment this was attached to, or null when it belongs to the bug
     * itself.
     *
     * <p>One table for both, rather than a second one beside it: a screenshot
     * pasted into a comment is the same thing as a screenshot on the report —
     * same bytes on disk, same size and type, same route serving it, same
     * lightbox opening it. All that differs is what it hangs off. The bug id
     * stays set either way, so deleting a bug still takes every file with it
     * and nothing has to walk the comments to find them.
     */
    @Column(name = "comment_id")
    private Long commentId;

    /** The name the uploader saw. Shown in the UI and used for the download. */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** The name on disk: random, so two people uploading "screenshot.png" cannot collide. */
    @Column(name = "stored_name", nullable = false, length = 120)
    private String storedName;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes")
    private long sizeBytes;

    @Column(name = "uploaded_by", length = 80)
    private String uploadedBy;

    /**
     * Whether the client who raised this bug may download it.
     *
     * <p>Taken from the comment the file arrives with rather than chosen
     * separately, and true for the files a guest uploads with their own report.
     * There is no control of its own on purpose: a second switch beside the
     * first is a second thing to get wrong, so sharing a file with a client
     * means attaching it to a comment that is shared.
     */
    @Column(nullable = false)
    private boolean shared = false;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    void onCreate() {
        this.uploadedAt = LocalDateTime.now();
    }

    /** For the UI: "412 KB" reads better than a byte count. */
    public String getReadableSize() {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024 * 1024) {
            return Math.round(sizeBytes / 1024.0) + " KB";
        }
        return Math.round(sizeBytes / (1024.0 * 1024.0) * 10) / 10.0 + " MB";
    }

    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }

    /**
     * A screen recording rather than a screenshot.
     *
     * <p>Worked out from the file's name, not from the stored type: that is
     * what the download route serves it as, and a row written before this app
     * knew what a .mov was still holds application/octet-stream. Parsing the
     * stored string would also mean a malformed one throwing out of a getter
     * a template calls.
     */
    public boolean isVideo() {
        return AttachmentService.isVideo(AttachmentService.mediaTypeFor(fileName));
    }

    /**
     * Whether the page can draw a player for this, as opposed to a link.
     *
     * <p>Asks {@code AttachmentService} rather than keeping a second list of
     * formats here: which types may be put in the page is one decision, and it
     * is the same decision that sets {@code Content-Disposition} on the way
     * out. Two lists that have to agree eventually do not.
     */
    public boolean isPlayable() {
        return AttachmentService.isPlayableVideo(AttachmentService.mediaTypeFor(fileName));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCommentId() {
        return commentId;
    }

    public void setCommentId(Long commentId) {
        this.commentId = commentId;
    }

    public Long getBugId() {
        return bugId;
    }

    public void setBugId(Long bugId) {
        this.bugId = bugId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
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

    public String getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(String uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public boolean isShared() {
        return shared;
    }

    public void setShared(boolean shared) {
        this.shared = shared;
    }
}
