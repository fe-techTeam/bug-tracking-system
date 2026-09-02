package com.bugtracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Where attachments are stored and what is allowed through. */
@Component
@ConfigurationProperties(prefix = "bugtracking.attachments")
public class AttachmentProperties {

    /** Directory for the uploaded files, relative to the working directory. */
    private String dir = "data/attachments";

    /**
     * Per-file ceiling. Keep this strictly below
     * spring.servlet.multipart.max-file-size: at the same number the servlet
     * rejects first, and the friendlier message naming the file and its size
     * never gets a chance to run.
     */
    private long maxSizeBytes = 8 * 1024 * 1024L;

    /** Lower-case extensions, without the dot. Anything else is refused. */
    private List<String> allowedExtensions = List.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif",
            "pdf", "txt", "log", "csv", "json", "xml",
            "mp4", "webm", "zip");

    /**
     * What a project's documents area accepts, which is wider than what goes
     * on a bug. A bug attachment is evidence — a screenshot, a log, an export.
     * A project's documents folder is where the specs, contracts, decks and
     * signed-off sheets live, so the Office formats belong there and nowhere
     * else. Kept as a separate list rather than widening the one above, so
     * loosening one does not quietly loosen the other.
     *
     * <p>SVG is absent on purpose, as it is above: it is an image a browser
     * will happily run script out of, and these files are served from this
     * app's own origin.
     */
    private List<String> docExtensions = List.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif",
            "pdf", "txt", "log", "md", "csv", "json", "xml", "yaml", "yml",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf",
            "mp4", "webm", "mp3", "wav", "zip");

    /** How large a single file in a project's documents area may be. */
    private long maxDocSizeBytes = 25 * 1024 * 1024L;

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public List<String> getDocExtensions() {
        return docExtensions;
    }

    public void setDocExtensions(List<String> docExtensions) {
        this.docExtensions = docExtensions;
    }

    public long getMaxDocSizeBytes() {
        return maxDocSizeBytes;
    }

    public void setMaxDocSizeBytes(long maxDocSizeBytes) {
        this.maxDocSizeBytes = maxDocSizeBytes;
    }
}
