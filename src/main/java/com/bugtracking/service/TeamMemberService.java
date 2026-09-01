package com.bugtracking.service;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Priority;
import com.bugtracking.model.Severity;
import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.BugRepository;
import com.bugtracking.repository.TeamMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@Transactional
public class TeamMemberService {

    private final TeamMemberRepository repository;
    private final BugRepository bugs;

    public TeamMemberService(TeamMemberRepository repository, BugRepository bugs) {
        this.repository = repository;
        this.bugs = bugs;
    }

    /** How many bugs name this person. Drives whether removal is offered. */
    @Transactional(readOnly = true)
    public long bugsNaming(String name) {
        return bugs.countByReportedByIgnoreCaseOrAssignedToIgnoreCase(name, name);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> usageByMemberId() {
        Map<Long, Long> usage = new LinkedHashMap<>();
        for (TeamMember member : all()) {
            usage.put(member.getId(), bugsNaming(member.getName()));
        }
        return usage;
    }

    @Transactional(readOnly = true)
    public TeamMember findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No team member found with id " + id));
    }

    /**
     * What one person raised and what they are carrying. Counted from the two
     * lists rather than five COUNT queries, because the page shows the lists
     * anyway.
     */
    @Transactional(readOnly = true)
    public Workload workloadOf(String name) {
        if (name == null || name.isBlank()) {
            return Workload.NONE;
        }
        String clean = name.trim();
        long reported = bugs.countByReportedByIgnoreCase(clean);
        List<Bug> assigned = bugs.findByAssignedToIgnoreCaseOrderByCreatedAtDesc(clean);

        long open = assigned.stream().filter(b -> b.getStatus().isOpenWork()).count();
        long urgent = assigned.stream()
                .filter(b -> b.getPriority() != null && b.getPriority().isUrgent())
                .filter(b -> b.getStatus().isOpenWork())
                .count();
        long critical = assigned.stream()
                .filter(b -> b.getSeverity() == Severity.CRITICAL)
                .filter(b -> b.getStatus().isOpenWork())
                .count();
        return new Workload(reported, assigned.size(), open, urgent, critical);
    }

    /** The same, for everyone on the team page at once. */
    @Transactional(readOnly = true)
    public Map<Long, Workload> workloadByMemberId() {
        Map<Long, Workload> loads = new LinkedHashMap<>();
        for (TeamMember member : all()) {
            loads.put(member.getId(), workloadOf(member.getName()));
        }
        return loads;
    }

    /**
     * Permanently removes a member who appears on no bug — for fixing a typo or
     * an accidental entry. Anyone with history is kept and should be hidden
     * instead, so their bugs keep making sense.
     */
    public void remove(Long id) {
        TeamMember member = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No team member found with id " + id));
        long used = bugsNaming(member.getName());
        if (used > 0) {
            throw new IllegalArgumentException(member.getName() + " is named on " + used
                    + " bug" + (used == 1 ? "" : "s") + ", so they cannot be removed. Hide them instead.");
        }
        repository.delete(member);
    }

    @Transactional(readOnly = true)
    public List<TeamMember> all() {
        return repository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<TeamMember> active() {
        return repository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<String> activeNames() {
        return active().stream().map(TeamMember::getName).toList();
    }

    /**
     * Names for a Reported By / Assigned To dropdown: the current team, plus
     * whatever the bug already holds. Without that second part, opening an old
     * bug whose reporter has left - or that was assigned to "dev-team" before
     * this table existed - would silently reassign it on save.
     */
    @Transactional(readOnly = true)
    public List<String> optionsIncluding(String... currentValues) {
        LinkedHashSet<String> options = new LinkedHashSet<>();
        for (String current : currentValues) {
            if (current != null && !current.isBlank()) {
                options.add(current.trim());
            }
        }
        options.addAll(activeNames());
        return new ArrayList<>(options);
    }

    /** Adds a member, or returns the existing one if that email is already here. */
    public TeamMember add(String name, String email) {
        String cleanName = name == null ? "" : name.trim();
        String cleanEmail = email == null ? "" : email.trim().toLowerCase();

        if (cleanName.isBlank()) {
            throw new IllegalArgumentException("A team member needs a name.");
        }
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            throw new IllegalArgumentException("\"" + email + "\" is not a valid email address.");
        }

        return repository.findByEmailIgnoreCase(cleanEmail).orElseGet(
                () -> repository.save(new TeamMember(cleanName, cleanEmail)));
    }

    public TeamMember setActive(Long id, boolean active) {
        TeamMember member = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No team member found with id " + id));
        member.setActive(active);
        return repository.save(member);
    }

    /** Used by the seeder: adds only the people who are not already here. */
    public int addMissing(List<String[]> nameAndEmail) {
        int added = 0;
        for (String[] person : nameAndEmail) {
            if (!repository.existsByEmailIgnoreCase(person[1])) {
                repository.save(new TeamMember(person[0], person[1]));
                added++;
            }
        }
        return added;
    }
}
