package com.bugtracking.service;

import com.bugtracking.config.AttachmentProperties;
import com.bugtracking.model.Attachment;
import com.bugtracking.repository.AttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Stores attachment bytes on disk and their metadata in the database. */
@Service
@Transactional
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /** Fills the gaps Spring's own mime.types leaves among the extensions we allow. */
    private static final Map<String, MediaType> EXTRA_TYPES = Map.of(
            "log", MediaType.TEXT_PLAIN,
            "webp", MediaType.valueOf("image/webp"),
            "bmp", MediaType.valueOf("image/bmp"),
            "heic", MediaType.valueOf("image/heic"),
            "heif", MediaType.valueOf("image/heif"),
            "csv", MediaType.valueOf("text/csv"));

    /**
     * What a browser will both draw in place and never be talked into running
     * script from. SVG is the reason this is a list and not a startsWith:
     * an image/* test would hand an attacker a script on our own origin.
     * HEIC is left out for the duller reason that only Safari draws it.
     */
    private static final Set<String> INLINE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "application/pdf");

    /** As much of a name as the file_name column holds. */
    private static final int MAX_FILE_NAME = 255;

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

    /**
     * What a file actually is, worked out from its name. The uploader's own
     * Content-Type is deliberately ignored: browsers send
     * application/octet-stream for .webp and .bmp on Windows and for anything
     * dragged in from another app, which was enough to keep a screenshot out of
     * the thumbnail grid altogether. It is also the caller's to choose, so
     * echoing it back would let a part header claiming image/svg+xml on an
     * allow-listed .png be served inline as script.
     */
    public static MediaType mediaTypeFor(String fileName) {
        String name = fileName == null ? "" : fileName;
        return MediaTypeFactory.getMediaType(name)
                .or(() -> Optional.ofNullable(EXTRA_TYPES.get(extensionOf(name))))
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    /** Whether this can be shown in the page rather than handed over as a download. */
    public static boolean isInlineSafe(MediaType type) {
        return type != null && INLINE_TYPES.contains(type.getType() + "/" + type.getSubtype());
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

        // Trimmed before anything is written: the column stops at 255, and an
        // insert that fails on the length would leave the bytes behind on disk.
        String fileName = shorten(original, extension);

        String storedName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);
        Path target = storageDir().resolve(storedName);

        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + original, e);
        }
        deleteIfRolledBack(target);

        Attachment attachment = new Attachment();
        attachment.setBugId(bugId);
        attachment.setFileName(fileName);
        attachment.setStoredName(storedName);
        attachment.setContentType(mediaTypeFor(fileName).toString());
        attachment.setSizeBytes(file.getSize());
        attachment.setUploadedBy(BugHistoryService.actor(uploadedBy));

        Attachment saved = repository.save(attachment);
        history.record(bugId, "attachment", null, fileName, attachment.getUploadedBy());
        return saved;
    }

    /**
     * Oldest first. A multi-file upload stamps several rows inside the same
     * tick, so uploadedAt on its own leaves the thumbnail order to chance from
     * one page load to the next; the id breaks the tie the way the upload did.
     */
    @Transactional(readOnly = true)
    public List<Attachment> forBug(Long bugId) {
        return repository.findByBugIdOrderByUploadedAtAsc(bugId).stream()
                .sorted(Comparator.comparing(Attachment::getUploadedAt).thenComparing(Attachment::getId))
                .toList();
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

    /**
     * Removes one attachment — the row and the file behind it. Recorded in the
     * history like every other change, so a file vanishing is never a mystery.
     */
    public Attachment delete(Long attachmentId, String actor) {
        Attachment attachment = findById(attachmentId);
        try {
            Files.deleteIfExists(pathOf(attachment));
        } catch (IOException e) {
            // The row goes either way; a stray file is not worth failing on.
            log.warn("Could not delete attachment file {}: {}", attachment.getStoredName(), e.getMessage());
        }
        repository.delete(attachment);
        history.record(attachment.getBugId(), "attachment-removed",
                attachment.getFileName(), null, BugHistoryService.actor(actor));
        return attachment;
    }

    /**
     * Removes both the rows and the files for a bug that is being deleted.
     * Recorded the same way a single delete is: this class promises a file
     * never vanishes without a trail, and clearing them all is still clearing
     * them.
     */
    public void deleteForBug(Long bugId) {
        List<Attachment> attachments = repository.findByBugIdOrderByUploadedAtAsc(bugId);
        String actor = BugHistoryService.actor(null);
        for (Attachment attachment : attachments) {
            try {
                Files.deleteIfExists(pathOf(attachment));
            } catch (IOException e) {
                // The row goes either way; a stray file is not worth failing the delete over.
                log.warn("Could not delete attachment file {}: {}", attachment.getStoredName(), e.getMessage());
            }
            history.record(bugId, "attachment-removed", attachment.getFileName(), null, actor);
        }
        repository.deleteAll(attachments);
    }

    /**
     * The bytes have to reach disk before the row that points at them exists.
     * If that insert then fails, nothing will ever come looking for the file
     * again, so it goes the moment the transaction gives up on it.
     */
    private void deleteIfRolledBack(Path file) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.warn("Could not remove orphaned attachment file {}: {}", file, e.getMessage());
                }
            }
        });
    }

    /** Keeps the extension when a name has to lose its middle - it is what tells a reader what the file is. */
    private static String shorten(String fileName, String extension) {
        if (fileName.length() <= MAX_FILE_NAME) {
            return fileName;
        }
        String suffix = extension.isEmpty() ? "" : "." + extension;
        return fileName.substring(0, MAX_FILE_NAME - suffix.length()) + suffix;
    }

    private Path storageDir() {
        return Paths.get(properties.getDir()).toAbsolutePath().normalize();
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
