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
     * The same, as a reply to another comment.
     *
     * <p>The parent is flattened to its own root: one level of nesting and no
     * more, so a long exchange stays a run under the thing it is about instead
     * of a tree that walks off the edge of the column.
     */
    public Comment add(Long bugId, Long parentId, String text, String author) {
        Comment comment = new Comment();
        comment.setBugId(bugId);
        comment.setParentId(rootOf(bugId, parentId));
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

    /** The thread a reply belongs under: its parent, or its parent's parent. */
    private Long rootOf(Long bugId, Long parentId) {
        if (parentId == null) {
            return null;
        }
        return repository.findById(parentId)
                .filter(parent -> bugId.equals(parent.getBugId()))
                .map(parent -> parent.getParentId() == null ? parent.getId() : parent.getParentId())
                .orElse(null);          // a parent that has since been deleted
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
     * Removes a comment you wrote, its replies, and every file on any of them.
     *
     * <p>The files are deleted through {@link AttachmentService} rather than by
     * dropping rows, because the bytes are on disk and a row deleted from under
     * them leaves a file nothing can ever list again.
     */
    public void remove(Long bugId, Long commentId, String author) {
        Comment comment = mine(bugId, commentId, author);

        List<Comment> going = new ArrayList<>();
        going.add(comment);
        going.addAll(repository.findByParentIdOrderByCreatedAtAsc(commentId));

        for (Comment one : going) {
            for (Attachment file : attachments.forComment(one.getId())) {
                attachments.delete(file.getId(), author);
            }
        }
        repository.deleteAll(going);
        history.record(bugId, "comment-removed", quote(comment.getText()), null,
                BugHistoryService.actor(author));
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
     * The thread, split into what opens it and what answers each one.
     *
     * <p>Both halves in one pass over the same list: the page draws every
     * comment anyway, and asking the database twice for the same rows to sort
     * them differently is a query for nothing.
     */
    @Transactional(readOnly = true)
    public Conversation conversationFor(Long bugId) {
        List<Comment> roots = new ArrayList<>();
        Map<Long, List<Comment>> replies = new LinkedHashMap<>();
        for (Comment comment : forBug(bugId)) {
            if (comment.getParentId() == null) {
                roots.add(comment);
            } else {
                replies.computeIfAbsent(comment.getParentId(), any -> new ArrayList<>()).add(comment);
            }
        }
        return new Conversation(roots, replies);
    }

    /** What opens a thread, and what answers it. */
    public record Conversation(List<Comment> roots, Map<Long, List<Comment>> replies) {
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
