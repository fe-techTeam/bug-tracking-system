package com.bugtracking.repository;

import com.bugtracking.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** Oldest first: a comment thread reads like a conversation. */
    List<Comment> findByBugIdOrderByCreatedAtAsc(Long bugId);

    /** One comment's replies, for taking them with it when it goes. */
    List<Comment> findByParentIdOrderByCreatedAtAsc(Long parentId);

    long countByBugId(Long bugId);

    /**
     * How many comments each of these bugs has, in one query.
     *
     * <p>For the board, which draws a comment count on every card: a count per
     * card is a query per card, and a column of thirty is thirty round trips
     * for a number that is usually zero.
     */
    @Query("SELECT c.bugId, COUNT(c) FROM Comment c WHERE c.bugId IN :ids GROUP BY c.bugId")
    List<Object[]> countGroupedByBugId(@Param("ids") Collection<Long> ids);

    void deleteByBugId(Long bugId);
}
