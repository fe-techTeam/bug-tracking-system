package com.bugtracking.repository;

import com.bugtracking.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** Oldest first: a comment thread reads like a conversation. */
    List<Comment> findByBugIdOrderByCreatedAtAsc(Long bugId);

    long countByBugId(Long bugId);

    void deleteByBugId(Long bugId);
}
