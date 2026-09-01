package com.bugtracking.service;

import com.bugtracking.model.Comment;
import com.bugtracking.repository.CommentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class CommentService {

    private final CommentRepository repository;
    private final BugHistoryService history;

    public CommentService(CommentRepository repository, BugHistoryService history) {
        this.repository = repository;
        this.history = history;
    }

    public Comment add(Long bugId, String text, String author) {
        Comment comment = new Comment();
        comment.setBugId(bugId);
        comment.setText(text.trim());
        comment.setCreatedBy(BugHistoryService.actor(author));
        Comment saved = repository.save(comment);
        history.record(bugId, "comment", null, null, comment.getCreatedBy());
        return saved;
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
