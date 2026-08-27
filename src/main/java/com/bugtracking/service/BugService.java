package com.bugtracking.service;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Severity;
import com.bugtracking.model.Status;
import com.bugtracking.repository.BugRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@Transactional
public class BugService {

    private final BugRepository repository;

    public BugService(BugRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Bug> findAll(Status status, Severity severity, String keyword) {
        String trimmed = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        return repository.search(status, severity, trimmed);
    }

    @Transactional(readOnly = true)
    public Bug findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No bug found with id " + id));
    }

    public Bug save(Bug bug) {
        return repository.save(bug);
    }

    /** Copies editable fields onto the managed entity so timestamps stay intact. */
    public Bug update(Long id, Bug changes) {
        Bug existing = findById(id);
        existing.setTitle(changes.getTitle());
        existing.setDescription(changes.getDescription());
        existing.setStepsToReproduce(changes.getStepsToReproduce());
        existing.setExpectedResult(changes.getExpectedResult());
        existing.setActualResult(changes.getActualResult());
        existing.setSeverity(changes.getSeverity());
        existing.setStatus(changes.getStatus());
        existing.setClient(changes.getClient());
        existing.setModule(changes.getModule());
        existing.setReportedBy(changes.getReportedBy());
        existing.setAssignedTo(changes.getAssignedTo());
        return repository.save(existing);
    }

    public Bug changeStatus(Long id, Status status) {
        Bug bug = findById(id);
        bug.setStatus(status);
        return repository.save(bug);
    }

    /** Backfills bugs that predate the client field. Returns how many were touched. */
    public int fillMissingClient(String client) {
        return repository.fillMissingClient(client);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new NoSuchElementException("No bug found with id " + id);
        }
        repository.deleteById(id);
    }

    /** Counts for the dashboard tiles: total, then one entry per status. */
    @Transactional(readOnly = true)
    public Map<String, Long> statusSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("Total", repository.count());
        for (Status status : Status.values()) {
            summary.put(status.getLabel(), repository.countByStatus(status));
        }
        return summary;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> severitySummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            summary.put(severity.getLabel(), repository.countBySeverity(severity));
        }
        return summary;
    }
}
