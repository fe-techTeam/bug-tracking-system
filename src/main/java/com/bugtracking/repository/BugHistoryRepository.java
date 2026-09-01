package com.bugtracking.repository;

import com.bugtracking.model.BugHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BugHistoryRepository extends JpaRepository<BugHistory, Long> {

    /** Newest first: the most recent change is the one people look for. */
    List<BugHistory> findByBugIdOrderByChangedAtDescIdDesc(Long bugId);

    /** The board-wide activity feed. */
    List<BugHistory> findTop12ByOrderByChangedAtDescIdDesc();

    /** The same feed narrowed to one project's bugs. */
    List<BugHistory> findTop12ByBugIdInOrderByChangedAtDescIdDesc(Collection<Long> bugIds);

    void deleteByBugId(Long bugId);
}
