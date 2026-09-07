package com.bugtracking.repository;

import com.bugtracking.model.SavedFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SavedFilterRepository extends JpaRepository<SavedFilter, Long> {

    @Query("""
            SELECT f FROM SavedFilter f
            WHERE f.project IS NULL
               OR (:project IS NOT NULL AND LOWER(f.project) = LOWER(CAST(:project AS string)))
            ORDER BY LOWER(f.name)
            """)
    List<SavedFilter> forProject(@Param("project") String project);

    List<SavedFilter> findAllByOrderByProjectAscNameAsc();
}
