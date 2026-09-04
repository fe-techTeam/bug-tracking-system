package com.bugtracking.repository;

import com.bugtracking.model.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByBugIdOrderByUploadedAtAsc(Long bugId);

    /** The report's own files: everything on the bug that is not on a comment. */
    List<Attachment> findByBugIdAndCommentIdIsNullOrderByUploadedAtAsc(Long bugId);

    /** One comment's files. */
    List<Attachment> findByCommentIdOrderByUploadedAtAsc(Long commentId);

    /** Every comment's files on this bug, for grouping in one query. */
    List<Attachment> findByBugIdAndCommentIdIsNotNullOrderByUploadedAtAsc(Long bugId);

    /** The files a client may download: the ones they sent, and the ones shared back. */
    List<Attachment> findByBugIdAndSharedTrueOrderByUploadedAtAsc(Long bugId);

    long countByBugId(Long bugId);

    /** The same count for a whole board's worth of bugs, in one query. */
    @Query("SELECT a.bugId, COUNT(a) FROM Attachment a WHERE a.bugId IN :ids GROUP BY a.bugId")
    List<Object[]> countGroupedByBugId(@Param("ids") Collection<Long> ids);
}
