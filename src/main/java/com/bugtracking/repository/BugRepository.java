package com.bugtracking.repository;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Deleting a bug is reversible, so every query here says
 * {@code deletedAt IS NULL}. A bug in the trash still has its row, its
 * comments, its files and its history — it is simply not looked at until it is
 * restored. The two methods that deliberately see trashed bugs are
 * {@link #findTrash()} and {@link #findAnyById(Long)}.
 */
public interface BugRepository extends JpaRepository<Bug, Long> {

    /**
     * Newest first, with every filter optional: a null project/status/severity/
     * environment or a blank keyword simply drops that condition instead of
     * matching nothing. The keyword also matches a bare bug id, so pasting "12"
     * or "BUG-12" into search finds that bug.
     *
     * <p>Assignees are a collection, so the two conditions that touch them are
     * EXISTS sub-queries rather than a column comparison — a join here would
     * multiply a bug by the number of people on it.
     *
     * <p>Person and keyword match differently on purpose. Every link that sets
     * assignee or reporter passes a whole display name and means that one
     * person, so those compare the name exactly (folded, since it is typed by
     * hand): a substring there would let "nishana" answer for "Nishana R" and
     * "a" for most of the team, and the count beside a face would stop agreeing
     * with what clicking it returns. The keyword is a free-text search box and
     * stays a substring.
     */
    /*
     * Every string parameter is CAST before it is used. It reads like noise and
     * is not: an unset filter arrives as a null String, and Postgres asks the
     * parameter what type it is before it asks what it holds. With nothing to
     * infer from, LOWER(?) resolves to lower(bytea), which does not exist, and
     * the whole board answers 500 - not just the filtered board, because an
     * unfiltered one is this same query with every filter null. The cast is the
     * answer to "what type", so it never has to be guessed.
     *
     * H2 infers happily and never needed this, which is why it only ever showed
     * up on Supabase.
     */
    @Query("""
            SELECT b FROM Bug b
            WHERE b.deletedAt IS NULL
              AND (:project IS NULL OR LOWER(b.project) = LOWER(CAST(:project AS string)))
              AND (:status IS NULL OR b.status = :status)
              AND (:severity IS NULL OR b.severity = :severity)
              AND (:environment IS NULL OR b.environment = :environment)
              AND (:assignee IS NULL OR EXISTS (
                       SELECT a FROM Bug ab JOIN ab.assignees a
                       WHERE ab.id = b.id AND LOWER(a) = LOWER(CAST(:assignee AS string))))
              AND (:reporter IS NULL OR LOWER(b.reportedBy) = LOWER(CAST(:reporter AS string)))
              AND (:keyword IS NULL
                   OR LOWER(b.title) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(b.description) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(b.module) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(b.project) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(b.reportedBy) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR EXISTS (SELECT a FROM Bug kb JOIN kb.assignees a
                              WHERE kb.id = b.id
                                AND LOWER(a) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
                   OR (:keywordId IS NOT NULL AND b.id = :keywordId))
            ORDER BY b.createdAt DESC
            """)
    List<Bug> search(@Param("project") String project,
                     @Param("status") String status,
                     @Param("severity") Severity severity,
                     @Param("environment") Environment environment,
                     @Param("assignee") String assignee,
                     @Param("reporter") String reporter,
                     @Param("keyword") String keyword,
                     @Param("keywordId") Long keywordId);

    /** Every live bug on one project, newest first — the input to a dashboard. */
    List<Bug> findByProjectIgnoreCaseAndDeletedAtIsNullOrderByCreatedAtDesc(String project);

    List<Bug> findByDeletedAtIsNullOrderByCreatedAtDesc();

    long countByProjectIgnoreCaseAndDeletedAtIsNull(String project);

    long countByStatusAndDeletedAtIsNull(String status);

    long countBySeverityAndDeletedAtIsNull(Severity severity);

    /** How many live bugs name this person, as reporter or as one of the assignees. */
    @Query("""
            SELECT COUNT(DISTINCT b) FROM Bug b
            WHERE b.deletedAt IS NULL
              AND (LOWER(b.reportedBy) = LOWER(:name)
                   OR EXISTS (SELECT a FROM Bug ab JOIN ab.assignees a
                              WHERE ab.id = b.id AND LOWER(a) = LOWER(:name)))
            """)
    long countNaming(@Param("name") String name);

    /* ---- one person's two piles: what they raised, and what landed on them ---- */

    List<Bug> findByReportedByIgnoreCaseAndDeletedAtIsNullOrderByCreatedAtDesc(String reportedBy);

    /** Everything this person is on, whether or not they are first on it. */
    @Query("""
            SELECT b FROM Bug b
            WHERE b.deletedAt IS NULL
              AND EXISTS (SELECT a FROM Bug ab JOIN ab.assignees a
                          WHERE ab.id = b.id AND LOWER(a) = LOWER(:name))
            ORDER BY b.createdAt DESC
            """)
    List<Bug> findAssignedTo(@Param("name") String name);

    long countByReportedByIgnoreCaseAndDeletedAtIsNull(String reportedBy);

    /**
     * Bugs per assignee, so the board can offer the people who actually carry
     * work rather than the whole directory. A bug with two people on it counts
     * once for each, which is the point — but only once each, hence DISTINCT:
     * the join would otherwise count a bug twice for anyone named on it twice.
     */
    @Query("""
            SELECT a, COUNT(DISTINCT b) FROM Bug b JOIN b.assignees a
            WHERE b.deletedAt IS NULL AND TRIM(a) <> ''
            GROUP BY a
            ORDER BY COUNT(DISTINCT b) DESC
            """)
    List<Object[]> countGroupedByAssignee();

    /* ---- blockers ---- */

    /**
     * The blocking bugs for a board, fetched in one go rather than per card.
     * A blocker that has been trashed is simply not returned, so the bug it was
     * holding up stops reading as blocked — and starts again if it is restored.
     */
    @Query("SELECT b FROM Bug b WHERE b.deletedAt IS NULL AND b.id IN :ids")
    List<Bug> findByIdIn(@Param("ids") List<Long> ids);

    /**
     * Every live bug but this one, newest first — the candidates for "blocked
     * by".
     *
     * <p>This used to name the three finished statuses inline. It cannot any
     * more: which columns count as finished is a per-project setting now, not
     * something JPQL can know, so the filtering moves to
     * {@code BugService.blockerOptions}, which has the columns to hand. The
     * query stays because the board is small and one fetch beats one per card.
     */
    @Query("""
            SELECT b FROM Bug b
            WHERE b.deletedAt IS NULL
              AND (:exclude IS NULL OR b.id <> :exclude)
            ORDER BY b.createdAt DESC
            """)
    List<Bug> findLiveBugs(@Param("exclude") Long exclude);

    /* ---- the trash ---- */

    /** What is in the bin, most recently thrown away first. */
    @Query("SELECT b FROM Bug b WHERE b.deletedAt IS NOT NULL ORDER BY b.deletedAt DESC")
    List<Bug> findTrash();

    long countByDeletedAtIsNotNull();

    /** Finds a bug whether it is live or trashed — for restoring and purging. */
    @Query("SELECT b FROM Bug b WHERE b.id = :id")
    java.util.Optional<Bug> findAnyById(@Param("id") Long id);

    /**
     * Gives a project to bugs that predate the field being required. A bulk
     * update on purpose: those rows would otherwise fail validation the next
     * time anyone edited them or changed their status.
     */
    @Modifying
    @Query("UPDATE Bug b SET b.project = :project WHERE b.project IS NULL OR TRIM(b.project) = ''")
    int fillMissingProject(@Param("project") String project);

    /** Same idea for environment, added later still. */
    @Modifying
    @Query("UPDATE Bug b SET b.environment = :environment WHERE b.environment IS NULL")
    int fillMissingEnvironment(@Param("environment") Environment environment);

    /** Clears a blocker that no longer exists, so no bug is stuck behind a ghost. */
    @Modifying
    @Query("UPDATE Bug b SET b.blockedBy = NULL WHERE b.blockedBy = :id")
    int clearBlocker(@Param("id") Long id);
}
