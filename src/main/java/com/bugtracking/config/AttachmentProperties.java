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

    /** Per-file ceiling. Keep this at or below spring.servlet.multipart.max-file-size. */
    private long maxSizeBytes = 10 * 1024 * 1024L;

    /** Lower-case extensions, without the dot. Anything else is refused. */
    private List<String> allowedExtensions = List.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "pdf", "txt", "log", "csv", "json", "xml",
            "mp4", "webm", "zip");

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
}
