package com.bugtracking.repository;

import com.bugtracking.model.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    List<TeamMember> findAllByOrderByNameAsc();

    List<TeamMember> findByActiveTrueOrderByNameAsc();

    Optional<TeamMember> findByEmailIgnoreCase(String email);

    /**
     * Everybody going by this name. A list rather than an Optional because
     * nothing stops two people sharing one: the unique column is the email.
     * Callers that need an address out of a display name — the mailer, working
     * back from a notification — have to decide what to do about that, and
     * "send to whichever one came first" is not it.
     */
    List<TeamMember> findByNameIgnoreCase(String name);

    boolean existsByEmailIgnoreCase(String email);
}
