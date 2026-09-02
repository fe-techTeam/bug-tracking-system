package com.bugtracking.repository;

import com.bugtracking.model.ProjectResource;
import com.bugtracking.model.ResourceKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectResourceRepository extends JpaRepository<ProjectResource, Long> {

    /**
     * Everything in one project, in one query. Folders are a handful deep and
     * a few dozen wide, so the tree, the breadcrumb and the current folder's
     * contents are all worked out in memory from this rather than from a
     * query per level.
     */
    List<ProjectResource> findByProjectIdOrderByKindAscNameAsc(Long projectId);

    /**
     * Scoped by project on purpose — an id from another project must read as
     * "not found" rather than open.
     */
    Optional<ProjectResource> findByIdAndProjectId(Long id, Long projectId);

    long countByProjectId(Long projectId);

    List<ProjectResource> findByProjectIdAndKind(Long projectId, ResourceKind kind);

    /** For the counts beside each project in Settings, in one pass. */
    @Query("select r.projectId, count(r) from ProjectResource r group by r.projectId")
    List<Object[]> countGroupedByProject();

    /** Recently touched, for the "picked up where you left off" strip. */
    @Query("""
            select r from ProjectResource r
            where r.projectId = :projectId and r.kind <> com.bugtracking.model.ResourceKind.FOLDER
            order by r.updatedAt desc
            """)
    List<ProjectResource> findRecent(@Param("projectId") Long projectId);
}
