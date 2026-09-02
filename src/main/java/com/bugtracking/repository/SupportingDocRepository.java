package com.bugtracking.repository;

import com.bugtracking.model.SupportingDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportingDocRepository extends JpaRepository<SupportingDoc, Long> {

    /** Most recently worked on first: the one you are in the middle of. */
    List<SupportingDoc> findByBugIdOrderByUpdatedAtDesc(Long bugId);

    /**
     * Scoped by bug on purpose — a document id from another bug must read as
     * "not found" rather than open.
     */
    Optional<SupportingDoc> findByIdAndBugId(Long id, Long bugId);

    long countByBugId(Long bugId);

    void deleteByBugId(Long bugId);
}
