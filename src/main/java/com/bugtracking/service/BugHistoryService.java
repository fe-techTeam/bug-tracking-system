package com.bugtracking.service;

import com.bugtracking.model.BugHistory;
import com.bugtracking.repository.BugHistoryRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Writes and reads a bug's audit trail. */
@Service
@Transactional
public class BugHistoryService {

    private final BugHistoryRepository repository;

    public BugHistoryService(BugHistoryRepository repository) {
        this.repository = repository;
    }

    public void record(Long bugId, String action, String oldValue, String newValue, String actor) {
        repository.save(new BugHistory(bugId, action, oldValue, newValue, actor(actor)));
    }

    /** Records only if something actually changed - no "changed X from A to A" noise. */
    public void recordIfChanged(Long bugId, String action, Object oldValue, Object newValue, String actor) {
        String before = text(oldValue);
        String after = text(newValue);
        if (!Objects.equals(before, after)) {
            record(bugId, action, before, after, actor);
        }
    }

    @Transactional(readOnly = true)
    public List<BugHistory> forBug(Long bugId) {
        return repository.findByBugIdOrderByChangedAtDescIdDesc(bugId);
    }

    /**
     * The activity feed for the sidebar: board-wide, or narrowed to one
     * project's bugs. An empty id list means the project has no bugs, which is
     * not the same as "no filter" — so it returns nothing rather than everything.
     */
    @Transactional(readOnly = true)
    public List<BugHistory> recent(List<Long> bugIds) {
        if (bugIds == null) {
            return repository.findTop12ByOrderByChangedAtDescIdDesc();
        }
        if (bugIds.isEmpty()) {
            return List.of();
        }
        return repository.findTop12ByBugIdInOrderByChangedAtDescIdDesc(bugIds);
    }

    public void deleteForBug(Long bugId) {
        repository.deleteByBugId(bugId);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    /**
     * Who to credit. An explicit name wins (the API lets a script say who it is
     * acting for); otherwise it is whoever is signed in. Only a request with
     * neither is recorded as "unknown".
     */
    static String actor(String actor) {
        if (actor != null && !actor.isBlank()) {
            return actor.trim();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return authentication.getName();
        }
        return "unknown";
    }
}
