package com.bugtracking.repository;

import com.bugtracking.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findAllByOrderByNameAsc();

    List<Project> findByActiveTrueOrderByNameAsc();

    Optional<Project> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    /*
     * The team is lazy and the app runs with open-in-view=false, so anything
     * that draws it has to say so while the transaction is still open. These
     * three do it with a join fetch rather than a walk per project, which is
     * also the difference between one query and one-per-project on Settings.
     *
     * "distinct" because the join multiplies a project by its members; on a
     * fetch join Hibernate de-duplicates in memory and the keyword is only
     * there to stop the extra rows reaching the list.
     */

    @Query("select distinct p from Project p left join fetch p.members order by p.name asc")
    List<Project> findAllWithMembers();

    @Query("select p from Project p left join fetch p.members where p.id = :id")
    Optional<Project> findByIdWithMembers(@Param("id") Long id);

    @Query("select p from Project p left join fetch p.members where lower(p.name) = lower(:name)")
    Optional<Project> findByNameIgnoreCaseWithMembers(@Param("name") String name);
}
