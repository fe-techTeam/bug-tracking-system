package com.bugtracking.service;

import com.bugtracking.model.Comment;
import com.bugtracking.repository.CommentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class CommentService {

    /** How much of the comment a mention notification quotes back. */
    private static final int QUOTE_LENGTH = 90;

    private final CommentRepository repository;
    private final BugHistoryService history;
    private final TeamMemberService team;
    private final NotificationService notifications;

    public CommentService(CommentRepository repository,
                          BugHistoryService history,
                          TeamMemberService team,
                          NotificationService notifications) {
        this.repository = repository;
        this.history = history;
        this.team = team;
        this.notifications = notifications;
    }

    public Comment add(Long bugId, String text, String author) {
        Comment comment = new Comment();
        comment.setBugId(bugId);
        comment.setText(text.trim());
        comment.setCreatedBy(BugHistoryService.actor(author));
        Comment saved = repository.save(comment);
        history.record(bugId, "comment", null, null, comment.getCreatedBy());

        for (String person : team.mentionedIn(saved.getText())) {
            if (person.equalsIgnoreCase(saved.getCreatedBy())) {
                continue;                       // tagging yourself needs no telling
            }
            notifications.notify(bugId, "mention", person,
                    saved.getCreatedBy() + " mentioned you on BUG-" + bugId + ": " + quote(saved.getText()));
        }
        return saved;
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
