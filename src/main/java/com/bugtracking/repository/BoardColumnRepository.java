package com.bugtracking.repository;

import com.bugtracking.model.BoardColumn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Columns are read on every page — the board draws them, and every status
 * badge anywhere in the app needs one to know its wording and its colour — so
 * everything here is ordered the way it will be shown and the whole table is
 * small enough to fetch in one go.
 */
public interface BoardColumnRepository extends JpaRepository<BoardColumn, Long> {

    /** Every column, grouped by project and in board order within each. */
    List<BoardColumn> findAllByOrderByProjectAscPositionAsc();

    /** One project's board, left to right. */
    List<BoardColumn> findByProjectIgnoreCaseOrderByPositionAsc(String project);

    Optional<BoardColumn> findByProjectIgnoreCaseAndStatusKey(String project, String statusKey);

    boolean existsByProjectIgnoreCaseAndStatusKey(String project, String statusKey);

    long countByProjectIgnoreCase(String project);

    /** Project names that already have a board, for the seeder to skip. */
    @Query("SELECT DISTINCT LOWER(c.project) FROM BoardColumn c")
    List<String> distinctProjectNames();

    /**
     * Moves every bug out of a column that is going away. A bulk update rather
     * than a loop: the alternative is loading a column's worth of bugs to
     * change one field on each, and none of the entity's other machinery —
     * history, notifications — should fire for a move nobody asked for.
     */
    @Modifying
    @Query("UPDATE Bug b SET b.status = :to WHERE LOWER(b.project) = LOWER(:project) AND b.status = :from")
    int moveBugs(@Param("project") String project,
                 @Param("from") String from,
                 @Param("to") String to);

    /** How many live bugs are sitting in one column, so deleting it can say so. */
    @Query("""
            SELECT COUNT(b) FROM Bug b
            WHERE b.deletedAt IS NULL AND LOWER(b.project) = LOWER(:project) AND b.status = :statusKey
            """)
    long countBugsIn(@Param("project") String project, @Param("statusKey") String statusKey);
}
