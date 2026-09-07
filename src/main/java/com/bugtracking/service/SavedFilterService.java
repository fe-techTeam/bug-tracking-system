package com.bugtracking.service;

import com.bugtracking.model.SavedFilter;
import com.bugtracking.repository.SavedFilterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
@Transactional
public class SavedFilterService {

    private static final Set<String> DROPPED = Set.of("project", "view");

    private final SavedFilterRepository repository;

    public SavedFilterService(SavedFilterRepository repository) {
        this.repository = repository;
    }

    public SavedFilter save(String name, String project, String rawQuery, String createdBy) {
        String query = filterOnly(rawQuery);
        if (query.isEmpty()) {
            throw new EmptyQueryException();
        }
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            cleanName = "Untitled";
        }
        String scope = project == null || project.isBlank() ? null : project.trim();
        return repository.save(new SavedFilter(cleanName, scope, query, createdBy.trim()));
    }

    @Transactional(readOnly = true)
    public List<SavedFilter> forProject(String project) {
        return repository.forProject(project == null || project.isBlank() ? null : project.trim());
    }

    @Transactional(readOnly = true)
    public List<SavedFilter> all() {
        return repository.findAllByOrderByProjectAscNameAsc();
    }

    public void delete(Long id, String actor) {
        SavedFilter filter = repository.findById(id).orElseThrow(NoSuchElementException::new);
        if (!filter.ownedBy(actor)) {
            throw new NotOwnerException();
        }
        repository.delete(filter);
    }

    static String filterOnly(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }
        String q = rawQuery.startsWith("?") ? rawQuery.substring(1) : rawQuery;
        List<String> kept = new ArrayList<>();
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!DROPPED.contains(key)) {
                kept.add(pair);
            }
        }
        return String.join("&", kept);
    }

    public static class EmptyQueryException extends RuntimeException {
    }

    public static class NotOwnerException extends RuntimeException {
    }
}
