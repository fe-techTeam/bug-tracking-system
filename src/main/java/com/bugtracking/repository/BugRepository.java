package com.bugtracking.repository;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Priority;
import com.bugtracking.model.Severity;
import com.bugtracking.model.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BugRepository extends JpaRepository<Bug, Long> {

    /**
     * Newest first, with every filter optional: a null project/status/severity/
     * priority/environment or a blank keyword simply drops that condition
     * instead of matching nothing. The keyword also matches a bare bug id, so
     * pasting "12" or "BUG-12" into search finds that bug.
     */
    @Query("""
            SELECT b FROM Bug b
            WHERE (:project IS NULL OR LOWER(b.project) = LOWER(:project))
              AND (:status IS NULL OR b.status = :status)
              AND (:severity IS NULL OR b.severity = :severity)
              AND (:priority IS NULL OR b.priority = :priority)
              AND (:environment IS NULL OR b.environment = :environment)
              AND (:assignee IS NULL OR LOWER(b.assignedTo) LIKE LOWER(CONCAT('%', :assignee, '%')))
              AND (:reporter IS NULL OR LOWER(b.reportedBy) LIKE LOWER(CONCAT('%', :reporter, '%')))
              AND (:keyword IS NULL
                   OR LOWER(b.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(b.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(b.module) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(b.project) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(b.reportedBy) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(b.assignedTo) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR (:keywordId IS NOT NULL AND b.id = :keywordId))
            ORDER BY b.createdAt DESC
            """)
    List<Bug> search(@Param("project") String project,
                     @Param("status") Status status,
                     @Param("severity") Severity severity,
                     @Param("priority") Priority priority,
                     @Param("environment") Environment environment,
                     @Param("assignee") String assignee,
                     @Param("reporter") String reporter,
                     @Param("keyword") String keyword,
                     @Param("keywordId") Long keywordId);

    /** Every bug on one project, newest first — the input to a project dashboard. */
    List<Bug> findByProjectIgnoreCaseOrderByCreatedAtDesc(String project);

    List<Bug> findAllByOrderByCreatedAtDesc();

    long countByProjectIgnoreCase(String project);

    /** Bug counts per project name, including names no longer in the projects table. */
    @Query("SELECT b.project, COUNT(b) FROM Bug b GROUP BY b.project")
    List<Object[]> countGroupedByProject();

    long countByStatus(Status status);

    long countBySeverity(Severity severity);

    long countByPriority(Priority priority);

    /** How many bugs name this person, either as reporter or assignee. */
    long countByReportedByIgnoreCaseOrAssignedToIgnoreCase(String reportedBy, String assignedTo);

    /* ---- one person's two piles: what they raised, and what landed on them ---- */

    List<Bug> findByReportedByIgnoreCaseOrderByCreatedAtDesc(String reportedBy);

    List<Bug> findByAssignedToIgnoreCaseOrderByCreatedAtDesc(String assignedTo);

    long countByReportedByIgnoreCase(String reportedBy);

    long countByAssignedToIgnoreCase(String assignedTo);

    /**
     * Bugs per assignee, so the board can offer the people who actually carry
     * work rather than the whole directory. Counted in one query.
     */
    @Query("""
            SELECT b.assignedTo, COUNT(b) FROM Bug b
            WHERE b.assignedTo IS NOT NULL AND TRIM(b.assignedTo) <> ''
            GROUP BY b.assignedTo
            ORDER BY COUNT(b) DESC
            """)
    List<Object[]> countGroupedByAssignee();

    /**
     * Gives a project to bugs that predate the field being required. A bulk
     * update on purpose: those rows would otherwise fail validation the next
     * time anyone edited them or changed their status.
     */
    @Modifying
    @Query("UPDATE Bug b SET b.project = :project WHERE b.project IS NULL OR TRIM(b.project) = ''")
    int fillMissingProject(@Param("project") String project);

    /** Same idea for priority, which became a required field later. */
    @Modifying
    @Query("UPDATE Bug b SET b.priority = :priority WHERE b.priority IS NULL")
    int fillMissingPriority(@Param("priority") Priority priority);

    /** And for environment. */
    @Modifying
    @Query("UPDATE Bug b SET b.environment = :environment WHERE b.environment IS NULL")
    int fillMissingEnvironment(@Param("environment") Environment environment);
}
