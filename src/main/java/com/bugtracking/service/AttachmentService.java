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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final Map<String, MediaType> EXTRA_TYPES = Map.ofEntries(
            Map.entry("log", MediaType.TEXT_PLAIN),
            Map.entry("webp", MediaType.valueOf("image/webp")),
            Map.entry("bmp", MediaType.valueOf("image/bmp")),
            Map.entry("heic", MediaType.valueOf("image/heic")),
            Map.entry("heif", MediaType.valueOf("image/heif")),
            Map.entry("csv", MediaType.valueOf("text/csv")),
            // Video. Guessed from the name like everything else here — the part
            // header a browser sends for a screen recording is as unreliable as
            // the one it sends for a .webp, and worse: Windows reports .mov as
            // application/octet-stream, which would file a screen recording
            // among the zips.
            Map.entry("mp4", MediaType.valueOf("video/mp4")),
            Map.entry("m4v", MediaType.valueOf("video/mp4")),
            Map.entry("webm", MediaType.valueOf("video/webm")),
            Map.entry("ogv", MediaType.valueOf("video/ogg")),
            Map.entry("mov", MediaType.valueOf("video/quicktime")),
            Map.entry("mkv", MediaType.valueOf("video/x-matroska")),
            Map.entry("avi", MediaType.valueOf("video/x-msvideo")));

    /**
     * What a browser will both draw in place and never be talked into running
     * script from. SVG is the reason this is a list and not a startsWith:
     * an image/* test would hand an attacker a script on our own origin.
     * HEIC is left out for the duller reason that only Safari draws it.
     */
    private static final Set<String> INLINE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "application/pdf",
            // The video formats a browser will play in a <video> element
            // without a plugin or a transcode. quicktime is on the list because
            // a .mov out of a Mac is almost always H.264 in a MOV wrapper,
            // which Safari and Chrome both play; mkv and avi are deliberately
            // absent, so they arrive as a download rather than as a black box
            // with a broken play button on it.
            "video/mp4", "video/webm", "video/ogg", "video/quicktime");

    /**
     * What a client may send in. A picture of the screen, a recording of it, or
     * a PDF — the three things a report is ever actually made of. SVG is absent
     * for the reason it is absent from INLINE_TYPES, and every archive, document
     * and log format is absent because none of them is evidence of a bug and all
     * of them are surface.
     */
    private static final Set<String> GUEST_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
            "image/heic", "image/heif", "application/pdf",
            "video/mp4", "video/webm", "video/ogg", "video/quicktime");

    /** How many files can ride along with one report or one reply. */
    public static final int GUEST_MAX_FILES = 3;

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

    /** Whether this is video at all — playable or not. */
    public static boolean isVideo(MediaType type) {
        return type != null && "video".equals(type.getType());
    }

    /**
     * Whether a {@code <video>} element will actually play this, which is the
     * narrower question: every video is video, only some of it is playable
     * here. The page asks this before drawing a player, so a .mkv is listed as
     * a file instead of being offered as one that will not start.
     */
    public static boolean isPlayableVideo(MediaType type) {
        return isVideo(type) && isInlineSafe(type);
    }

    public Attachment store(Long bugId, MultipartFile file, String uploadedBy) {
        return store(bugId, null, file, uploadedBy);
    }

    /**
     * The same, hung off a comment rather than off the report.
     *
     * <p>{@code bugId} is still set: it is what makes deleting a bug take every
     * file with it without walking the thread first, and what the serving route
     * checks the file against.
     */
    public Attachment store(Long bugId, Long commentId, MultipartFile file, String uploadedBy) {
        return store(bugId, commentId, file, uploadedBy, false);
    }

    /**
     * The same, saying whether the client who raised the bug may download it.
     *
     * <p>Taken from the comment the file arrives with rather than asked
     * separately — a file on a shared comment is shared, one on an internal
     * comment is not — and true for what a guest uploads themselves. There is
     * no control of its own, so nothing can be shared by ticking the wrong box.
     */
    /**
     * Why this file cannot be stored, or null when it can.
     *
     * <p>An answer rather than an exception, and that is what it is for. This
     * class is transactional, so a {@link RejectedFileException} thrown from
     * inside {@link #store} marks the caller's transaction rollback-only —
     * catching it is not enough, and a caller that saves a report and then
     * attaches to it would lose the report along with the file. Asking first
     * keeps the two separable: the words are saved, and the file is reported as
     * left off.
     *
     * <p>{@code guest} narrows it to what a client may send. Same size rules,
     * shorter list of things: see {@link #GUEST_TYPES}.
     */
    public String refusalFor(MultipartFile file, boolean guest) {
        if (file == null || file.isEmpty()) {
            return "Pick a file before uploading.";
        }
        String original = nameOf(file);
        String extension = extensionOf(original);

        if (!properties.getAllowedExtensions().contains(extension)) {
            return "\"" + original + "\" is not an allowed file type. Allowed: "
                    + String.join(", ", properties.getAllowedExtensions()) + ".";
        }
        MediaType type = mediaTypeFor(original);
        if (guest && !GUEST_TYPES.contains(type.getType() + "/" + type.getSubtype())) {
            return "\"" + original + "\" was not attached — a report takes a screenshot,"
                    + " a screen recording or a PDF.";
        }
        // Video is measured against its own, much larger ceiling: see
        // AttachmentProperties.maxVideoSizeBytes for why the general one is not
        // simply raised to meet it.
        long ceiling = isVideo(type) ? properties.getMaxVideoSizeBytes() : properties.getMaxSizeBytes();
        if (file.getSize() > ceiling) {
            return "\"" + original + "\" is "
                    + Math.round(file.getSize() / (1024.0 * 1024.0) * 10) / 10.0 + " MB - the limit is "
                    + Math.round(ceiling / (1024.0 * 1024.0)) + " MB.";
        }
        return null;
    }

    /** The name the browser sent, with any path it came with stripped off. */
    private static String nameOf(MultipartFile file) {
        String sent = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        return Paths.get(sent).getFileName().toString();
    }

    public Attachment store(Long bugId, Long commentId, MultipartFile file, String uploadedBy,
                            boolean shared) {
        String refusal = refusalFor(file, false);
        if (refusal != null) {
            throw new RejectedFileException(refusal);
        }

        String original = nameOf(file);
        String extension = extensionOf(original);

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
        attachment.setCommentId(commentId);
        attachment.setFileName(fileName);
        attachment.setStoredName(storedName);
        attachment.setContentType(mediaTypeFor(fileName).toString());
        attachment.setSizeBytes(file.getSize());
        attachment.setUploadedBy(BugHistoryService.actor(uploadedBy));
        attachment.setShared(shared);

        Attachment saved = repository.save(attachment);
        // Only the report's own files earn a history line. A file on a comment
        // arrives with the comment, which is already recorded — two entries a
        // second apart saying the same thing is noise in a trail people read.
        if (commentId == null) {
            history.record(bugId, "attachment", null, fileName, attachment.getUploadedBy());
        }
        return saved;
    }

    /**
     * The same, for a file arriving from outside the company.
     *
     * <p>Narrower than {@link #store}, and narrower on purpose. The general
     * allow-list exists so a colleague can attach a log, a zip or a spreadsheet
     * to a bug they are working; none of that is what a client is doing, and
     * every extension on a list is one more thing an outside uploader can put
     * on this server. What is left is what a report is actually made of: a
     * picture of the screen, a recording of it, or the PDF somebody was sent.
     *
     * <p>Stored shared, because a client who cannot see the screenshot they
     * just attached would reasonably think it never arrived.
     */
    public Attachment storeFromGuest(Long bugId, Long commentId, MultipartFile file, String uploadedBy) {
        return store(bugId, commentId, file, uploadedBy, true);
    }

    /**
     * Oldest first. A multi-file upload stamps several rows inside the same
     * tick, so uploadedAt on its own leaves the thumbnail order to chance from
     * one page load to the next; the id breaks the tie the way the upload did.
     */
    @Transactional(readOnly = true)
    public List<Attachment> forBug(Long bugId) {
        return ordered(repository.findByBugIdAndCommentIdIsNullOrderByUploadedAtAsc(bugId));
    }

    /** Every file on this bug its client may download, report and thread alike. */
    @Transactional(readOnly = true)
    public List<Attachment> sharedFor(Long bugId) {
        return ordered(repository.findByBugIdAndSharedTrueOrderByUploadedAtAsc(bugId));
    }

    /**
     * Every comment's files on this bug, grouped by comment id.
     *
     * <p>One query for the whole thread rather than one per comment: the page
     * draws every comment anyway, and a bug with forty of them would otherwise
     * be forty round trips to find that most of them have no files at all.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Attachment>> byComment(Long bugId) {
        Map<Long, List<Attachment>> byId = new LinkedHashMap<>();
        for (Attachment attachment : ordered(
                repository.findByBugIdAndCommentIdIsNotNullOrderByUploadedAtAsc(bugId))) {
            byId.computeIfAbsent(attachment.getCommentId(), any -> new ArrayList<>()).add(attachment);
        }
        return byId;
    }

    /** The files on one comment — for taking them with it when it is deleted. */
    @Transactional(readOnly = true)
    public List<Attachment> forComment(Long commentId) {
        return ordered(repository.findByCommentIdOrderByUploadedAtAsc(commentId));
    }

    private static List<Attachment> ordered(List<Attachment> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(Attachment::getUploadedAt).thenComparing(Attachment::getId))
                .toList();
    }

    /**
     * How many each of these bugs has, keyed by bug id, in one query.
     *
     * <p>For the board: a card shows the number, and a count per card is a
     * query per card. Bugs with none are simply absent from the map, which is
     * what a template checking {@code != null} wants anyway.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> countsFor(List<Long> bugIds) {
        if (bugIds == null || bugIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Object[] row : repository.countGroupedByBugId(bugIds)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
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
