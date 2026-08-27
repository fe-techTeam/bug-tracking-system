package com.bugtracking.repository;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Severity;
import com.bugtracking.model.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BugRepository extends JpaRepository<Bug, Long> {

    /**
     * Newest first, with every filter optional: a null status/severity or a blank
     * keyword simply drops that condition instead of matching nothing.
     */
    @Query("""
            SELECT b FROM Bug b
            WHERE (:status IS NULL OR b.status = :status)
              AND (:severity IS NULL OR b.severity = :severity)
              AND (:keyword IS NULL
                   OR LOWER(b.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(b.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(b.module) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(b.client) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY b.createdAt DESC
            """)
    List<Bug> search(@Param("status") Status status,
                     @Param("severity") Severity severity,
                     @Param("keyword") String keyword);

    long countByStatus(Status status);

    long countBySeverity(Severity severity);

    /**
     * Gives a client to bugs raised before the field existed. A bulk update on
     * purpose: those rows would otherwise fail validation the next time anyone
     * edited them or changed their status.
     */
    @Modifying
    @Query("UPDATE Bug b SET b.client = :client WHERE b.client IS NULL OR TRIM(b.client) = ''")
    int fillMissingClient(@Param("client") String client);
}
