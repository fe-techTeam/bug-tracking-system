package com.bugtracking.repository;

import com.bugtracking.model.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    List<TeamMember> findAllByOrderByNameAsc();

    List<TeamMember> findByActiveTrueOrderByNameAsc();

    Optional<TeamMember> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
