package com.bugtracking.service;

import com.bugtracking.model.Bug;
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
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@Transactional
public class TeamMemberService {

    private final TeamMemberRepository repository;
    private final BugRepository bugs;
    private final BoardColumnService columns;

    public TeamMemberService(TeamMemberRepository repository, BugRepository bugs,
                             BoardColumnService columns) {
        this.repository = repository;
        this.bugs = bugs;
        this.columns = columns;
    }

    /**
     * How many bugs name this person. Drives whether removal is offered, and
     * counts the same way the board's people filter matches — a whole name,
     * case-insensitively — so the Remove button and the board never disagree
     * about whether somebody is still on something.
     */
    @Transactional(readOnly = true)
    public long bugsNaming(String name) {
        return name == null || name.isBlank() ? 0 : bugs.countNaming(name.trim());
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
        return workloadOf(name, columns.snapshot());
    }

    /**
     * The same, given the columns already in hand.
     *
     * <p>Whether a bug counts as open is a property of the column it is in, and
     * one person's bugs can span several projects — so the answer needs the
     * whole set. Taking it as an argument means the team page reads the columns
     * once rather than once per person.
     */
    private Workload workloadOf(String name, BoardColumns board) {
        if (name == null || name.isBlank()) {
            return Workload.NONE;
        }
        String clean = name.trim();
        long reported = bugs.countByReportedByIgnoreCaseAndDeletedAtIsNull(clean);
        List<Bug> assigned = bugs.findAssignedTo(clean);

        long open = assigned.stream().filter(board::openWork).count();
        long urgent = assigned.stream()
                .filter(b -> b.getSeverity() == Severity.CRITICAL || b.getSeverity() == Severity.HIGH)
                .filter(board::openWork)
                .count();
        long critical = assigned.stream()
                .filter(b -> b.getSeverity() == Severity.CRITICAL)
                .filter(board::openWork)
                .count();
        return new Workload(reported, assigned.size(), open, urgent, critical);
    }

    /** The same, for everyone on the team page at once. */
    @Transactional(readOnly = true)
    public Map<Long, Workload> workloadByMemberId() {
        BoardColumns board = columns.snapshot();
        Map<Long, Workload> loads = new LinkedHashMap<>();
        for (TeamMember member : all()) {
            loads.put(member.getId(), workloadOf(member.getName(), board));
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
     * The team members tagged with an "@" in a piece of text.
     *
     * <p>Matched against the roster rather than parsed out of the text, because
     * names have spaces in them — "@Nishana R" cannot be found by looking for a
     * word after the "@". Longest names are checked first so "@Anita Rao" is not
     * mistaken for "@Anita".
     *
     * <p>Lives here rather than beside any one thing that can be written in:
     * comments, project pages and sheet cells all ask the same question, and
     * the answer is about the team, not about where the "@" was typed.
     */
    @Transactional(readOnly = true)
    public List<String> mentionedIn(String text) {
        List<String> found = new ArrayList<>();
        if (text == null || text.indexOf('@') < 0) {
            return found;
        }

        String haystack = text.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>(activeNames());
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));

        for (String name : names) {
            if (haystack.contains("@" + name.toLowerCase(Locale.ROOT)) && !found.contains(name)) {
                found.add(name);
            }
        }
        return found;
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

    /**
     * Adds a member, or renames the one already holding that email.
     *
     * <p>Re-adding somebody with a corrected spelling is how a typo in a name
     * gets fixed — the email is what identifies them, so there is nothing else
     * the second entry could mean. Returning the old row untouched made that a
     * silent no-op reported as a success.
     */
    public TeamMember add(String name, String email) {
        String cleanName = name == null ? "" : name.trim();
        String cleanEmail = email == null ? "" : email.trim().toLowerCase();

        if (cleanName.isBlank()) {
            throw new IllegalArgumentException("A team member needs a name.");
        }
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            throw new IllegalArgumentException("\"" + email + "\" is not a valid email address.");
        }

        return repository.findByEmailIgnoreCase(cleanEmail)
                .map(existing -> rename(existing, cleanName))
                .orElseGet(() -> repository.save(new TeamMember(cleanName, cleanEmail)));
    }

    /**
     * Gives an existing member the new spelling of their name. The bugs they
     * are on keep the old string — it is stored on the bug, not looked up —
     * so {@link #bugsNaming} still counts those and removal stays blocked,
     * which is the safe way round.
     */
    private TeamMember rename(TeamMember member, String name) {
        if (name.equals(member.getName())) {
            return member;
        }
        member.setName(name);
        return repository.save(member);
    }

    /** True when this email is already on the team — for wording the flash. */
    @Transactional(readOnly = true)
    public boolean isOnTeam(String email) {
        return email != null && !email.isBlank()
                && repository.existsByEmailIgnoreCase(email.trim().toLowerCase());
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
