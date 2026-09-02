package com.bugtracking.service;

import com.bugtracking.config.AttachmentProperties;
import com.bugtracking.model.Project;
import com.bugtracking.model.ProjectResource;
import com.bugtracking.model.ResourceKind;
import com.bugtracking.repository.ProjectRepository;
import com.bugtracking.repository.ProjectResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * A project's documents area: the folder tree, and everything in it.
 *
 * <p>One project, one tree, five kinds of thing in it — see
 * {@link ProjectResource} for why they share a table. This class is the only
 * thing that knows what each kind means: which columns it fills, where its
 * bytes live, what it exports as, and what happens to its children when it
 * goes.
 *
 * <p><b>The tree is read whole.</b> {@link #browse} loads every row for the
 * project in one query and works out the breadcrumb, the folder rail and the
 * current folder's contents from that. A project has tens of these, not
 * thousands, and one query answering the whole page beats a query per level
 * with a recursive CTE behind it.
 *
 * <p><b>Mentions.</b> Writing "@Anita Rao" into a page or a sheet cell tells
 * her, once, with a link to the document — the same behaviour a comment has.
 * The repeat window is an hour rather than thirty seconds because a document
 * saves itself as you type; see {@code NotificationService.notifyAt}.
 */
@Service
@Transactional
public class ProjectDocService {

    private static final Logger log = LoggerFactory.getLogger(ProjectDocService.class);

    /** As long a page as one column will hold. */
    private static final int MAX_PAGE = 200_000;

    /** How deep a folder may be nested. Deeper than this is a filing problem. */
    private static final int MAX_DEPTH = 8;

    private final ProjectResourceRepository repository;
    private final ProjectRepository projects;
    private final AttachmentProperties properties;
    private final TeamMemberService team;
    private final NotificationService notifications;
    private final SheetCodec sheets;

    public ProjectDocService(ProjectResourceRepository repository,
                             ProjectRepository projects,
                             AttachmentProperties properties,
                             TeamMemberService team,
                             NotificationService notifications,
                             SheetCodec sheets) {
        this.repository = repository;
        this.projects = projects;
        this.properties = properties;
        this.team = team;
        this.notifications = notifications;
        this.sheets = sheets;
    }

    /**
     * Thrown for something the user could fix by picking differently — a file
     * type this area does not take, a link that is not a web address, a folder
     * dragged inside itself. An IllegalArgumentException so it lands on the
     * handler that already turns those into a 400 with the message on it;
     * named so the callers that would rather show it beside the form can.
     */
    public static class RejectedException extends IllegalArgumentException {
        public RejectedException(String message) {
            super(message);
        }
    }

    /** One folder in the rail, and how deep it sits. */
    public record TreeNode(ProjectResource folder, int depth, long itemCount) { }

    /**
     * Everything one screen of the browser needs.
     *
     * @param folder    the folder being looked at, or null at the root
     * @param trail     its ancestors, root-most first, ending with itself
     * @param tree      every folder in the project, flattened with its depth
     */
    public record Listing(Project project,
                          ProjectResource folder,
                          List<ProjectResource> trail,
                          List<ProjectResource> folders,
                          List<ProjectResource> documents,
                          List<ProjectResource> files,
                          List<ProjectResource> links,
                          List<TreeNode> tree,
                          long total) {

        /** True when this folder has nothing in it at all. */
        public boolean isEmpty() {
            return folders.isEmpty() && documents.isEmpty() && files.isEmpty() && links.isEmpty();
        }

        public int count() {
            return folders.size() + documents.size() + files.size() + links.size();
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Project project(Long projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("No project found with id " + projectId));
    }

    /** One entry, or a 404 — including for an id that belongs to another project. */
    @Transactional(readOnly = true)
    public ProjectResource find(Long projectId, Long id) {
        return repository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Nothing with id " + id + " in this project's documents."));
    }

    /**
     * The contents of one folder, plus the chrome around it. {@code folderId}
     * of null is the root.
     */
    @Transactional(readOnly = true)
    public Listing browse(Long projectId, Long folderId) {
        Project project = project(projectId);
        List<ProjectResource> all = repository.findByProjectIdOrderByKindAscNameAsc(projectId);
        Map<Long, ProjectResource> byId = new LinkedHashMap<>();
        all.forEach(r -> byId.put(r.getId(), r));

        ProjectResource folder = folderId == null ? null : byId.get(folderId);
        if (folderId != null && (folder == null || !folder.isFolder())) {
            throw new NoSuchElementException("No folder with id " + folderId + " in this project.");
        }

        List<ProjectResource> here = all.stream()
                .filter(r -> java.util.Objects.equals(r.getParentId(), folderId))
                .sorted(byName())
                .toList();

        return new Listing(project, folder, trail(folder, byId),
                here.stream().filter(ProjectResource::isFolder).toList(),
                here.stream().filter(ProjectResource::isDocument).toList(),
                here.stream().filter(ProjectResource::isFile).toList(),
                here.stream().filter(ProjectResource::isLink).toList(),
                tree(all), all.size());
    }

    /**
     * Everything in the project whose name, note or link matches — across every
     * folder, because "where did I put it" is exactly when you search.
     */
    @Transactional(readOnly = true)
    public List<ProjectResource> search(Long projectId, String keyword) {
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        return repository.findByProjectIdOrderByKindAscNameAsc(projectId).stream()
                .filter(r -> contains(r.getName(), needle)
                        || contains(r.getNote(), needle)
                        || contains(r.getUrl(), needle))
                .sorted(byName())
                .limit(80)
                .toList();
    }

    /** The most recently touched documents and files, for the "carry on" strip. */
    @Transactional(readOnly = true)
    public List<ProjectResource> recent(Long projectId, int limit) {
        List<ProjectResource> recent = repository.findRecent(projectId);
        return recent.size() <= limit ? recent : recent.subList(0, limit);
    }

    /** Where an entry may be moved to: every folder except itself and its own children. */
    @Transactional(readOnly = true)
    public List<TreeNode> moveTargets(Long projectId, Long movingId) {
        List<ProjectResource> all = repository.findByProjectIdOrderByKindAscNameAsc(projectId);
        Set<Long> banned = movingId == null ? Set.of() : descendantIds(all, movingId);
        return tree(all).stream()
                .filter(node -> !banned.contains(node.folder().getId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public long countForProject(Long projectId) {
        return repository.countByProjectId(projectId);
    }

    /** How much each project has filed, for the list in Settings. */
    @Transactional(readOnly = true)
    public Map<Long, Long> countsByProject() {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Object[] row : repository.countGroupedByProject()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public SheetCodec.Sheet sheet(ProjectResource resource) {
        return sheets.parse(resource.getContent());
    }

    // ---------------------------------------------------------------- create

    public ProjectResource createFolder(Long projectId, Long parentId, String name, String actor) {
        ProjectResource folder = blank(projectId, parentId, ResourceKind.FOLDER, name, actor);
        return repository.save(folder);
    }

    /** A blank page or a blank sheet, ready to be typed into. */
    public ProjectResource createDocument(Long projectId, Long parentId, ResourceKind kind,
                                          String name, String actor) {
        if (kind == null || !kind.isDocument()) {
            throw new RejectedException("A document is either a page or a sheet.");
        }
        ProjectResource doc = blank(projectId, parentId, kind, name, actor);
        // A blank sheet is a grid of empty cells: an empty spreadsheet with
        // nowhere to type is not a starting point.
        doc.setContent(kind == ResourceKind.SHEET ? sheets.blankJson() : "");
        return repository.save(doc);
    }

    /**
     * Files a URL somebody on the project keeps having to re-share.
     *
     * <p>Only http and https are stored. A {@code javascript:} link rendered as
     * an anchor on a page everybody on the project opens is a way to run script
     * as them, and nothing else a browser understands belongs on this page.
     */
    public ProjectResource addLink(Long projectId, Long parentId, String name,
                                   String url, String note, String actor) {
        String href = cleanUrl(url);
        String title = name == null || name.isBlank() ? hostOf(href) : name;

        ProjectResource link = blank(projectId, parentId, ResourceKind.LINK, title, actor);
        link.setUrl(href);
        link.setNote(trimTo(note, 400));
        link.setSummary(hostOf(href));
        return repository.save(link);
    }

    /**
     * Stores one uploaded file: bytes on disk, the row pointing at them.
     *
     * <p>What a file <em>is</em> comes from its name, never from the
     * Content-Type the browser sent — see {@link AttachmentService#mediaTypeFor}
     * for why that header cannot be trusted or echoed back.
     */
    public ProjectResource upload(Long projectId, Long parentId, MultipartFile file, String actor) {
        if (file == null || file.isEmpty()) {
            throw new RejectedException("Pick a file before uploading.");
        }

        String original = Paths.get(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename())
                .getFileName().toString();          // strips any path the browser sent
        String extension = extensionOf(original);

        if (!properties.getDocExtensions().contains(extension)) {
            throw new RejectedException("\"" + original + "\" is not a file type this project accepts. Allowed: "
                    + String.join(", ", properties.getDocExtensions()) + ".");
        }
        if (file.getSize() > properties.getMaxDocSizeBytes()) {
            throw new RejectedException("\"" + original + "\" is "
                    + Math.round(file.getSize() / (1024.0 * 1024.0) * 10) / 10.0 + " MB — the limit is "
                    + Math.round(properties.getMaxDocSizeBytes() / (1024.0 * 1024.0)) + " MB.");
        }

        String storedName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);
        Path target = storageDir().resolve(storedName);
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + original, e);
        }
        deleteIfRolledBack(target);

        ProjectResource stored = blank(projectId, parentId, ResourceKind.FILE, original, actor);
        stored.setStoredName(storedName);
        stored.setContentType(AttachmentService.mediaTypeFor(original).toString());
        stored.setSizeBytes(file.getSize());
        stored.setSummary(stored.getReadableSize());
        return repository.save(stored);
    }

    /** The shared skeleton: parent checked, name cleaned, ownership stamped. */
    private ProjectResource blank(Long projectId, Long parentId, ResourceKind kind,
                                  String name, String actor) {
        project(projectId);                     // 404 before anything is written
        ProjectResource resource = new ProjectResource();
        resource.setProjectId(projectId);
        resource.setParentId(checkedParent(projectId, parentId));
        resource.setKind(kind);
        resource.setName(cleanName(name, kind));
        resource.setCreatedBy(BugHistoryService.actor(actor));
        resource.setUpdatedBy(resource.getCreatedBy());
        return resource;
    }

    // ----------------------------------------------------------------- write

    /** Saves a page: its name and the Markdown behind it, then tells anyone tagged. */
    public ProjectResource savePage(Long projectId, Long id, String name, String content, String actor) {
        ProjectResource doc = document(projectId, id, ResourceKind.PAGE);
        String body = content == null ? "" : content;
        if (body.length() > MAX_PAGE) {
            throw new RejectedException("This page is too long to save — keep it under "
                    + (MAX_PAGE / 1000) + ",000 characters.");
        }
        setName(doc, name);
        doc.setContent(body);
        doc.setSummary(wordCount(body));
        ProjectResource saved = touch(doc, actor);
        tell(saved, body);
        return saved;
    }

    /**
     * Saves a sheet from the grid of inputs an ordinary form post sends, after
     * applying a row or column button if one was pressed.
     *
     * <p>This is the path a browser with scripting switched off takes. It
     * carries values and nothing else, so the formatting is taken from what was
     * already stored rather than blanked — see
     * {@link SheetCodec#fromPostedCells}.
     */
    public ProjectResource saveSheetCells(Long projectId, Long id, String name, List<String> cells,
                                          Integer cols, String op, String actor) {
        ProjectResource doc = document(projectId, id, ResourceKind.SHEET);
        SheetCodec.Sheet stored = sheets.parse(doc.getContent());
        SheetCodec.Sheet edited = sheets.applyOp(sheets.fromPostedCells(stored, cells, cols), op);
        return writeSheet(doc, name, edited, actor);
    }

    /** Saves a sheet the editor has already serialised — formatting and all. */
    public ProjectResource saveSheetJson(Long projectId, Long id, String name,
                                         String content, String actor) {
        ProjectResource doc = document(projectId, id, ResourceKind.SHEET);
        return writeSheet(doc, name, sheets.parse(content), actor);
    }

    private ProjectResource writeSheet(ProjectResource doc, String name,
                                       SheetCodec.Sheet sheet, String actor) {
        setName(doc, name);
        doc.setContent(sheets.toJson(sheet));
        doc.setSummary(SheetCodec.summary(sheet));
        ProjectResource saved = touch(doc, actor);
        tell(saved, SheetCodec.allText(sheet));
        return saved;
    }

    /**
     * Just the name — and, for a link, where it points. Used when a save
     * arrives carrying no body at all: the beacon sent as the page is torn down
     * can be truncated, and that must never be read as "they emptied it".
     */
    public ProjectResource rename(Long projectId, Long id, String name, String note,
                                  String url, String actor) {
        ProjectResource resource = find(projectId, id);
        setName(resource, name);
        if (note != null) {
            resource.setNote(trimTo(note, 400));
        }
        if (resource.isLink() && url != null && !url.isBlank()) {
            resource.setUrl(cleanUrl(url));
            resource.setSummary(resource.getHost());
        }
        return touch(resource, actor);
    }

    /**
     * Moves an entry into another folder, or to the root with a null target.
     *
     * <p>A folder cannot be moved inside itself or anything under it: that
     * detaches the whole branch from the tree, and since the tree is a plain
     * parent pointer nothing would ever list those rows again.
     */
    public ProjectResource move(Long projectId, Long id, Long targetId, String actor) {
        ProjectResource resource = find(projectId, id);
        if (java.util.Objects.equals(resource.getId(), targetId)) {
            throw new RejectedException("A folder cannot be moved into itself.");
        }
        Long parent = checkedParent(projectId, targetId);

        if (resource.isFolder() && parent != null) {
            Set<Long> banned = descendantIds(
                    repository.findByProjectIdOrderByKindAscNameAsc(projectId), id);
            if (banned.contains(parent)) {
                throw new RejectedException(
                        "\"" + resource.getName() + "\" cannot be moved into a folder inside it.");
            }
        }
        resource.setParentId(parent);
        return touch(resource, actor);
    }

    /**
     * Removes an entry. A folder takes everything under it — the whole branch,
     * and the files on disk that belonged to it.
     */
    public ProjectResource delete(Long projectId, Long id, String actor) {
        ProjectResource resource = find(projectId, id);
        List<ProjectResource> all = repository.findByProjectIdOrderByKindAscNameAsc(projectId);
        Set<Long> doomed = descendantIds(all, id);

        List<ProjectResource> going = all.stream()
                .filter(r -> doomed.contains(r.getId()))
                .toList();
        going.stream().filter(ProjectResource::isFile).forEach(this::deleteFile);
        forgetMentions(going);
        repository.deleteAll(going);
        return resource;
    }

    /** For a project being removed outright: its whole documents area goes with it. */
    public void deleteForProject(Long projectId) {
        List<ProjectResource> all = repository.findByProjectIdOrderByKindAscNameAsc(projectId);
        all.stream().filter(ProjectResource::isFile).forEach(this::deleteFile);
        forgetMentions(all);
        repository.deleteAll(all);
    }

    /**
     * Takes down the bells that pointed at documents which are about to stop
     * existing. Only documents are ever linked to, so folders, files and links
     * contribute nothing here and are filtered out rather than queried for.
     */
    private void forgetMentions(List<ProjectResource> going) {
        notifications.deleteForLinks(going.stream()
                .filter(ProjectResource::isDocument)
                .map(ProjectDocService::linkTo)
                .toList());
    }

    private ProjectResource touch(ProjectResource resource, String actor) {
        resource.setUpdatedBy(BugHistoryService.actor(actor));
        return repository.save(resource);
    }

    private void setName(ProjectResource resource, String name) {
        if (name != null && !name.isBlank()) {
            resource.setName(cleanName(name, resource.getKind()));
        }
    }

    private ProjectResource document(Long projectId, Long id, ResourceKind expected) {
        ProjectResource resource = find(projectId, id);
        if (resource.getKind() != expected) {
            throw new RejectedException("\"" + resource.getName() + "\" is not a "
                    + expected.getLabel().toLowerCase(Locale.ROOT) + ".");
        }
        return resource;
    }

    // -------------------------------------------------------------- mentions

    /**
     * Tells everybody tagged in the text, once. The author is skipped —
     * tagging yourself needs no telling — and the notification links straight
     * to the document rather than to a bug.
     */
    private void tell(ProjectResource doc, String text) {
        List<String> tagged = team.mentionedIn(text);
        if (tagged.isEmpty()) {
            return;
        }
        String author = BugHistoryService.actor(doc.getUpdatedBy());
        String link = linkTo(doc);
        String where = project(doc.getProjectId()).getName();

        for (String person : tagged) {
            if (person.equalsIgnoreCase(author)) {
                continue;
            }
            notifications.notifyAt(link, "mention", person,
                    trimTo(author + " tagged you in " + doc.getName() + " (" + where + ")", 255));
        }
    }

    /** Where a document lives, as a path this app can hand to the browser. */
    public static String linkTo(ProjectResource doc) {
        return "/projects/" + doc.getProjectId() + "/docs/" + doc.getId();
    }

    // --------------------------------------------------------------- exports

    /** A page downloads as Markdown, a sheet as CSV — both open anywhere. */
    public byte[] export(ProjectResource doc) {
        if (doc.getKind() == ResourceKind.SHEET) {
            // The BOM is for Excel, which otherwise reads a UTF-8 CSV as
            // Latin-1 and turns every accented name into mojibake.
            return ("﻿" + SheetCodec.toCsv(sheet(doc))).getBytes(StandardCharsets.UTF_8);
        }
        return (doc.getContent() == null ? "" : doc.getContent()).getBytes(StandardCharsets.UTF_8);
    }

    /** A download name made of the title, so a folder of exports still reads. */
    public static String exportName(ProjectResource doc) {
        String slug = doc.getName()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            slug = doc.getKind().name().toLowerCase(Locale.ROOT);
        }
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        return slug + "." + doc.getKind().getExtension();
    }

    @Transactional(readOnly = true)
    public Path pathOf(ProjectResource file) {
        return storageDir().resolve(file.getStoredName());
    }

    private void deleteFile(ProjectResource file) {
        if (file.getStoredName() == null) {
            return;
        }
        try {
            Files.deleteIfExists(pathOf(file));
        } catch (IOException e) {
            // The row goes either way; a stray file is not worth failing on.
            log.warn("Could not delete project file {}: {}", file.getStoredName(), e.getMessage());
        }
    }

    /**
     * The bytes reach disk before the row that points at them exists. If that
     * insert then fails, nothing will ever come looking for the file again, so
     * it goes the moment the transaction gives up on it.
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
                    log.warn("Could not remove orphaned project file {}: {}", file, e.getMessage());
                }
            }
        });
    }

    /** Kept apart from bug attachments, so one area's files are never the other's. */
    private Path storageDir() {
        return Paths.get(properties.getDir()).toAbsolutePath().normalize().resolve("projects");
    }

    // ----------------------------------------------------------------- shapes

    /** The folder's ancestors, root-most first, ending with the folder itself. */
    private static List<ProjectResource> trail(ProjectResource folder, Map<Long, ProjectResource> byId) {
        List<ProjectResource> trail = new ArrayList<>();
        ProjectResource at = folder;
        Set<Long> seen = new HashSet<>();
        while (at != null && seen.add(at.getId())) {     // seen: a cycle must not hang the page
            trail.add(0, at);
            at = at.getParentId() == null ? null : byId.get(at.getParentId());
        }
        return trail;
    }

    /** Every folder, depth-first, so the rail can indent them into a tree. */
    private static List<TreeNode> tree(List<ProjectResource> all) {
        Map<Long, List<ProjectResource>> children = new LinkedHashMap<>();
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (ProjectResource resource : all) {
            Long parent = resource.getParentId();
            counts.merge(parent == null ? -1L : parent, 1L, Long::sum);
            if (resource.isFolder()) {
                children.computeIfAbsent(parent == null ? -1L : parent, key -> new ArrayList<>())
                        .add(resource);
            }
        }
        children.values().forEach(list -> list.sort(byName()));

        List<TreeNode> flat = new ArrayList<>();
        walk(children, counts, -1L, 0, flat, new HashSet<>());
        return flat;
    }

    private static void walk(Map<Long, List<ProjectResource>> children, Map<Long, Long> counts,
                             Long parent, int depth, List<TreeNode> into, Set<Long> seen) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (ProjectResource folder : children.getOrDefault(parent, List.of())) {
            if (!seen.add(folder.getId())) {
                continue;                        // a cycle: draw it once and move on
            }
            into.add(new TreeNode(folder, depth, counts.getOrDefault(folder.getId(), 0L)));
            walk(children, counts, folder.getId(), depth + 1, into, seen);
        }
    }

    /** An id and every id beneath it. Used by both delete and the move guard. */
    private static Set<Long> descendantIds(List<ProjectResource> all, Long rootId) {
        Set<Long> found = new HashSet<>();
        found.add(rootId);
        boolean grew = true;
        while (grew) {                           // a tree this shallow needs no recursion
            grew = false;
            for (ProjectResource resource : all) {
                if (resource.getParentId() != null
                        && found.contains(resource.getParentId())
                        && found.add(resource.getId())) {
                    grew = true;
                }
            }
        }
        return found;
    }

    /** Folders first, then documents, files and links — each alphabetical. */
    private static Comparator<ProjectResource> byName() {
        return Comparator.comparing((ProjectResource r) -> r.getKind().ordinal())
                .thenComparing(r -> r.getName() == null ? "" : r.getName(),
                        String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * The parent to store: null for the root, or the id of a folder that really
     * is a folder in this project. A parent from somewhere else would put the
     * entry in a tree it does not belong to.
     */
    private Long checkedParent(Long projectId, Long parentId) {
        if (parentId == null) {
            return null;
        }
        ProjectResource parent = find(projectId, parentId);
        if (!parent.isFolder()) {
            throw new RejectedException("\"" + parent.getName() + "\" is not a folder.");
        }
        return parent.getId();
    }

    private static String cleanName(String name, ResourceKind kind) {
        ResourceKind of = kind == null ? ResourceKind.FOLDER : kind;
        if (name == null || name.isBlank()) {
            return of.getDefaultName();
        }
        // A newline in a name is invisible in a field and breaks every list it
        // is drawn in; a name is one line by definition.
        String clean = name.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.isBlank() ? of.getDefaultName() : trimTo(clean, 200);
    }

    private static String cleanUrl(String url) {
        String clean = url == null ? "" : url.trim();
        if (clean.isBlank()) {
            throw new RejectedException("A link needs an address.");
        }
        // A bare "figma.com/file/…" is what people paste; assume the safe scheme
        // rather than refuse it.
        if (!clean.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            clean = "https://" + clean;
        }
        URI uri;
        try {
            uri = URI.create(clean);
        } catch (IllegalArgumentException e) {
            throw new RejectedException("That does not look like a web address.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new RejectedException("Only http:// and https:// links can be saved here.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new RejectedException("That web address has no site in it.");
        }
        return trimTo(clean, 2000);
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return url;
        }
    }

    private static String wordCount(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        int count = body.trim().split("\\s+").length;
        return count + (count == 1 ? " word" : " words");
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
