package com.bugtracking.service;

import com.bugtracking.model.Project;
import com.bugtracking.repository.BugRepository;
import com.bugtracking.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
@Transactional
public class ProjectService {

    private final ProjectRepository repository;
    private final BugRepository bugs;

    public ProjectService(ProjectRepository repository, BugRepository bugs) {
        this.repository = repository;
        this.bugs = bugs;
    }

    @Transactional(readOnly = true)
    public List<Project> all() {
        return repository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Project> active() {
        return repository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<String> activeNames() {
        return active().stream().map(Project::getName).toList();
    }

    /**
     * Names for the raise/edit dropdown: the live projects, plus whatever this
     * bug already holds. Without the second part, editing a bug on a retired
     * project would silently move it somewhere else on save.
     */
    @Transactional(readOnly = true)
    public List<String> optionsIncluding(String current) {
        LinkedHashSet<String> options = new LinkedHashSet<>();
        if (current != null && !current.isBlank()) {
            options.add(current.trim());
        }
        options.addAll(activeNames());
        return new ArrayList<>(options);
    }

    /**
     * What the left sidebar shows: every live project, plus any project name
     * that only exists on old bugs, each with its bug count. Live projects
     * appear even at zero so the board is never a mystery.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> sidebarCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();

        // Matched case-insensitively, so "Godrej" picks up a bug filed as "godrej".
        Set<String> claimed = new HashSet<>();
        for (String name : activeNames()) {
            counts.put(name, bugs.countByProjectIgnoreCase(name));
            claimed.add(name.toLowerCase(Locale.ROOT));
        }

        for (Object[] row : bugs.countGroupedByProject()) {
            String name = (String) row[0];
            if (name == null || name.isBlank()) {
                continue;
            }
            // Compare folded, not exact: a differently-cased spelling of a live
            // project was already counted above, and adding it again here would
            // list the project twice and make the counts sum to more than the
            // number of bugs.
            if (claimed.add(name.toLowerCase(Locale.ROOT))) {
                counts.put(name, (Long) row[1]);       // a legacy or retired project
            }
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public long bugsIn(String name) {
        return bugs.countByProjectIgnoreCase(name);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> usageByProjectId() {
        Map<Long, Long> usage = new LinkedHashMap<>();
        for (Project project : all()) {
            usage.put(project.getId(), bugsIn(project.getName()));
        }
        return usage;
    }

    public Project add(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isBlank()) {
            throw new IllegalArgumentException("A project needs a name.");
        }
        return repository.findByNameIgnoreCase(clean)
                .orElseGet(() -> repository.save(new Project(clean)));
    }

    public Project setActive(Long id, boolean active) {
        Project project = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No project found with id " + id));
        project.setActive(active);
        return repository.save(project);
    }

    /** Only for a project nothing was ever raised against — a typo, say. */
    public void remove(Long id) {
        Project project = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No project found with id " + id));
        long used = bugsIn(project.getName());
        if (used > 0) {
            throw new IllegalArgumentException(project.getName() + " has " + used
                    + " bug" + (used == 1 ? "" : "s") + ", so it cannot be removed. Hide it instead.");
        }
        repository.delete(project);
    }

    /** Used by the seeder: adds only the projects that are not already here. */
    public int addMissing(List<String> names) {
        int added = 0;
        for (String name : names) {
            if (!repository.existsByNameIgnoreCase(name)) {
                repository.save(new Project(name));
                added++;
            }
        }
        return added;
    }
}
