package com.bugtracking.service;

import com.bugtracking.config.AttachmentProperties;
import com.bugtracking.model.Attachment;
import com.bugtracking.repository.AttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Stores attachment bytes on disk and their metadata in the database. */
@Service
@Transactional
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository repository;
    private final AttachmentProperties properties;
    private final BugHistoryService history;

    public AttachmentService(AttachmentRepository repository,
                             AttachmentProperties properties,
                             BugHistoryService history) {
        this.repository = repository;
        this.properties = properties;
        this.history = history;
    }

    /** Thrown for a file the user could fix by picking a different one. */
    public static class RejectedFileException extends RuntimeException {
        public RejectedFileException(String message) {
            super(message);
        }
    }

    public Attachment store(Long bugId, MultipartFile file, String uploadedBy) {
        if (file == null || file.isEmpty()) {
            throw new RejectedFileException("Pick a file before uploading.");
        }

        String original = Paths.get(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename())
                .getFileName().toString();          // strips any path the browser sent
        String extension = extensionOf(original);

        if (!properties.getAllowedExtensions().contains(extension)) {
            throw new RejectedFileException("\"" + original + "\" is not an allowed file type. Allowed: "
                    + String.join(", ", properties.getAllowedExtensions()) + ".");
        }
        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw new RejectedFileException("\"" + original + "\" is "
                    + Math.round(file.getSize() / (1024.0 * 1024.0) * 10) / 10.0 + " MB - the limit is "
                    + Math.round(properties.getMaxSizeBytes() / (1024.0 * 1024.0)) + " MB.");
        }

        String storedName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);
        Path target = storageDir().resolve(storedName);

        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + original, e);
        }

        Attachment attachment = new Attachment();
        attachment.setBugId(bugId);
        attachment.setFileName(original);
        attachment.setStoredName(storedName);
        attachment.setContentType(file.getContentType());
        attachment.setSizeBytes(file.getSize());
        attachment.setUploadedBy(BugHistoryService.actor(uploadedBy));

        Attachment saved = repository.save(attachment);
        history.record(bugId, "attachment", null, original, attachment.getUploadedBy());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Attachment> forBug(Long bugId) {
        return repository.findByBugIdOrderByUploadedAtAsc(bugId);
    }

    @Transactional(readOnly = true)
    public long countForBug(Long bugId) {
        return repository.countByBugId(bugId);
    }

    @Transactional(readOnly = true)
    public Attachment findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No attachment found with id " + id));
    }

    @Transactional(readOnly = true)
    public Path pathOf(Attachment attachment) {
        return storageDir().resolve(attachment.getStoredName());
    }

    /** Removes both the rows and the files for a bug that is being deleted. */
    public void deleteForBug(Long bugId) {
        List<Attachment> attachments = repository.findByBugIdOrderByUploadedAtAsc(bugId);
        for (Attachment attachment : attachments) {
            try {
                Files.deleteIfExists(pathOf(attachment));
            } catch (IOException e) {
                // The row goes either way; a stray file is not worth failing the delete over.
                log.warn("Could not delete attachment file {}: {}", attachment.getStoredName(), e.getMessage());
            }
        }
        repository.deleteAll(attachments);
    }

    private Path storageDir() {
        return Paths.get(properties.getDir()).toAbsolutePath().normalize();
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
