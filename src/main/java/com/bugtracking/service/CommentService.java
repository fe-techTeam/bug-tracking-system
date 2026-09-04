package com.bugtracking.service;

import com.bugtracking.model.Attachment;
import com.bugtracking.model.Comment;
import com.bugtracking.repository.CommentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@Transactional
public class CommentService {

    /** How much of the comment a mention notification quotes back. */
    private static final int QUOTE_LENGTH = 90;

    private final CommentRepository repository;
    private final BugHistoryService history;
    private final TeamMemberService team;
    private final NotificationService notifications;
    /** Deleting a comment takes its files with it, bytes on disk included. */
    private final AttachmentService attachments;

    public CommentService(CommentRepository repository,
                          BugHistoryService history,
                          TeamMemberService team,
                          NotificationService notifications,
                          AttachmentService attachments) {
        this.repository = repository;
        this.history = history;
        this.team = team;
        this.notifications = notifications;
        this.attachments = attachments;
    }

    public Comment add(Long bugId, String text, String author) {
        return add(bugId, null, text, author);
    }

    /**
     * The same, as a reply to another comment — at whatever depth that is.
     *
     * <p>The parent kept is the comment actually answered. It used to be
     * flattened to the top of its thread, so the third message in an exchange
     * looked like another answer to the first, and following who was replying
     * to whom meant reading the words for it.
     */
    public Comment add(Long bugId, Long parentId, String text, String author) {
        Comment comment = new Comment();
        comment.setBugId(bugId);
        comment.setParentId(parentFor(bugId, parentId));
        comment.setText(text.trim());
        comment.setCreatedBy(BugHistoryService.actor(author));
        Comment saved = repository.save(comment);
        history.record(bugId, "comment", null, null, comment.getCreatedBy());

        tell(saved);
        return saved;
    }

    /** Everybody tagged with an "@" hears about it. */
    private void tell(Comment saved) {
        for (String person : team.mentionedIn(saved.getText())) {
            if (person.equalsIgnoreCase(saved.getCreatedBy())) {
                continue;                       // tagging yourself needs no telling
            }
            notifications.notify(saved.getBugId(), "mention", person,
                    saved.getCreatedBy() + " mentioned you on BUG-" + saved.getBugId()
                            + ": " + quote(saved.getText()));
        }
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

    /**
     * The comment a reply hangs under: the one it answers, if that is still one
     * of this bug's. A parent deleted between opening the form and posting it
     * leaves the reply standing on its own rather than orphaned under an id
     * nothing can draw.
     */
    private Long parentFor(Long bugId, Long parentId) {
        if (parentId == null) {
            return null;
        }
        return repository.findById(parentId)
                .filter(parent -> bugId.equals(parent.getBugId()))
                .map(Comment::getId)
                .orElse(null);
    }

    /**
     * Changes the words of a comment you wrote.
     *
     * <p>Yours only, checked here rather than in the template: hiding the
     * button is a courtesy, and the rule has to hold for anyone who posts the
     * form anyway. Mentions are matched again, so tagging somebody in an edit
     * tells them.
     */
    public Comment edit(Long bugId, Long commentId, String text, String author) {
        Comment comment = mine(bugId, commentId, author);
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("A comment cannot be emptied — delete it instead.");
        }

        String before = comment.getText();
        comment.setText(clean);
        comment.setEditedAt(LocalDateTime.now());
        Comment saved = repository.save(comment);

        if (!clean.equals(before)) {
            tell(saved);
        }
        return saved;
    }

    /**
     * Removes a comment you wrote, everything filed under it however deep, and
     * every file on any of them.
     *
     * <p>The whole subtree rather than one level: replies can be replied to
     * now, and a grandchild left behind points at a parent that is gone, which
     * is a comment no page will ever draw again.
     *
     * <p>The files are deleted through {@link AttachmentService} rather than by
     * dropping rows, because the bytes are on disk and a row deleted from under
     * them leaves a file nothing can ever list again.
     */
    public void remove(Long bugId, Long commentId, String author) {
        Comment comment = mine(bugId, commentId, author);

        List<Comment> going = new ArrayList<>();
        collect(comment, going);

        for (Comment one : going) {
            for (Attachment file : attachments.forComment(one.getId())) {
                attachments.delete(file.getId(), author);
            }
        }
        repository.deleteAll(going);
        history.record(bugId, "comment-removed", quote(comment.getText()), null,
                BugHistoryService.actor(author));
    }

    /**
     * A comment and everything under it, deepest last.
     *
     * <p>Recursive, and safe from looping without a guard: a parent always
     * exists before the reply that names it, so the links only ever point
     * backwards in time.
     */
    private void collect(Comment comment, List<Comment> into) {
        into.add(comment);
        for (Comment child : repository.findByParentIdOrderByCreatedAtAsc(comment.getId())) {
            collect(child, into);
        }
    }

    /** The comment, if it is this bug's and the person asking wrote it. */
    private Comment mine(Long bugId, Long commentId, String author) {
        Comment comment = repository.findById(commentId)
                .filter(c -> bugId.equals(c.getBugId()))
                .orElseThrow(() -> new NoSuchElementException("No comment found with id " + commentId));
        String who = BugHistoryService.actor(author);
        if (comment.getCreatedBy() == null || !comment.getCreatedBy().equalsIgnoreCase(who)) {
            throw new IllegalArgumentException("You can only change your own comments.");
        }
        return comment;
    }

    /**
     * The thread: what opens each conversation, and everything said under it.
     *
     * <p>Replies nest to any depth, and this deliberately flattens them for
     * drawing. A tree drawn as a tree is a staircase off the right-hand edge of
     * a column this wide, and once the indent has to stop somewhere the page is
     * explaining two things at once — how deep this is, and how deep it is
     * allowed to look. So every answer under one comment is one run in the
     * order it was said, and who answered whom is carried by the {@code @}
     * mention the reply box fills in — which is a thing the reader already
     * knows how to read, and which notifies the person as well.
     *
     * <p>One pass over one query. The rows come back oldest first, so a parent
     * is always seen before the replies that name it and the run it belongs to
     * is known by the time its own replies arrive.
     */
    @Transactional(readOnly = true)
    public Conversation conversationFor(Long bugId) {
        List<Comment> roots = new ArrayList<>();
        Map<Long, Long> runOf = new LinkedHashMap<>();      // comment id -> the run it is in
        Map<Long, List<Comment>> replies = new LinkedHashMap<>();
        Map<Long, List<String>> tags = new LinkedHashMap<>();

        for (Comment comment : forBug(bugId)) {
            Long parent = comment.getParentId();

            // Who a reply to this one should tag: its author, then everybody
            // already in the exchange above it. Built from the parent's own
            // list, so it costs one lookup however deep the chain goes.
            List<String> chain = new ArrayList<>();
            addName(chain, comment.getCreatedBy());
            if (parent != null) {
                for (String name : tags.getOrDefault(parent, List.of())) {
                    addName(chain, name);
                }
            }
            tags.put(comment.getId(), chain);

            Long run = parent == null ? null : runOf.get(parent);
            if (run == null) {
                // No parent, or one that is somehow gone: it opens a run.
                roots.add(comment);
                runOf.put(comment.getId(), comment.getId());
                continue;
            }
            runOf.put(comment.getId(), run);
            replies.computeIfAbsent(run, any -> new ArrayList<>()).add(comment);
        }
        return new Conversation(roots, replies, tags);
    }

    /** Appends a name if there is one and it is not already in the list. */
    private static void addName(List<String> names, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        for (String had : names) {
            if (had.equalsIgnoreCase(name)) {
                return;
            }
        }
        names.add(name);
    }

    /**
     * What opens a run, everything said under it, and who a reply to each
     * comment should tag.
     *
     * <p>That last map is the whole of how a nested exchange stays followable:
     * answering somebody who was themselves answering tags both of them, so the
     * people in the conversation are named in the words rather than implied by
     * an indent the page refuses to draw. Deduplicated, so two people going
     * back and forth stay two names however long they go on.
     */
    public record Conversation(List<Comment> roots,
                               Map<Long, List<Comment>> replies,
                               Map<Long, List<String>> tags) {
    }

    private static String quote(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= QUOTE_LENGTH ? flat : flat.substring(0, QUOTE_LENGTH - 1) + "…";
    }

    @Transactional(readOnly = true)
    public List<Comment> forBug(Long bugId) {
        return repository.findByBugIdOrderByCreatedAtAsc(bugId);
    }

    @Transactional(readOnly = true)
    public long countForBug(Long bugId) {
        return repository.countByBugId(bugId);
    }

    public void deleteForBug(Long bugId) {
        repository.deleteByBugId(bugId);
    }
}
