package com.bugtracking.service;

import com.bugtracking.model.Bug;
import com.bugtracking.model.MemberRole;
import com.bugtracking.model.Severity;
import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.BugRepository;
import com.bugtracking.repository.TeamMemberRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
@Transactional
public class TeamMemberService {

    /**
     * How short a password is refused. Low, because this is a small internal
     * tool and a rule nobody can satisfy is a rule people work around — but not
     * absent, because "" would otherwise be a valid account password.
     */
    private static final int MIN_PASSWORD = 8;

    private final TeamMemberRepository repository;
    private final BugRepository bugs;
    private final BoardColumnService columns;
    private final PasswordEncoder encoder;

    public TeamMemberService(TeamMemberRepository repository, BugRepository bugs,
                             BoardColumnService columns, PasswordEncoder encoder) {
        this.repository = repository;
        this.bugs = bugs;
        this.columns = columns;
        this.encoder = encoder;
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
        refuseIfLastAdmin(member, "remove " + member.getName());
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
        return add(name, email, null);
    }

    /**
     * The same, optionally giving them a password so they can sign in.
     *
     * <p>A blank password means "no account", not "an empty one": most of the
     * roster are names on bugs and never sign in at all. Re-adding somebody
     * with a password set is how their password gets changed, which is the
     * same rule the name already follows.
     */
    public TeamMember add(String name, String email, String rawPassword) {
        String cleanName = name == null ? "" : name.trim();
        String cleanEmail = email == null ? "" : email.trim().toLowerCase();

        if (cleanName.isBlank()) {
            throw new IllegalArgumentException("A team member needs a name.");
        }
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            throw new IllegalArgumentException("\"" + email + "\" is not a valid email address.");
        }

        // Checked before anything is written, so a password too short does not
        // leave a half-made member behind for the second attempt to trip over.
        String hash = rawPassword == null || rawPassword.isBlank() ? null : hash(rawPassword);

        TeamMember member = repository.findByEmailIgnoreCase(cleanEmail)
                .map(existing -> rename(existing, cleanName))
                .orElseGet(() -> repository.save(new TeamMember(cleanName, cleanEmail)));

        if (hash != null) {
            member.setPasswordHash(hash);
            member = repository.save(member);
        }
        return member;
    }

    /**
     * Gives somebody a sign-in password, or changes the one they have.
     *
     * <p>What is stored is the BCrypt hash and only ever the hash — the plain
     * text is not written to the row, the log or the flash message. There is
     * deliberately no way to read a password back out; the only thing that can
     * be done with it afterwards is to replace it.
     */
    public TeamMember setPassword(Long id, String rawPassword) {
        TeamMember member = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No team member found with id " + id));
        member.setPasswordHash(hash(rawPassword));
        return repository.save(member);
    }

    /** Takes the account away again, leaving the person on the roster. */
    public TeamMember clearPassword(Long id) {
        TeamMember member = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No team member found with id " + id));
        refuseIfLastAdmin(member, "take away " + member.getName() + "'s password");
        member.setPasswordHash(null);
        return repository.save(member);
    }

    /**
     * Changes your own password, having proved you know the current one.
     *
     * <p>This is the one password path that is not administration, and the only
     * one that asks for the old password. An admin setting somebody's password
     * on Settings &gt; Team cannot know it; the person themselves can, and
     * asking is what stops a walked-away-from session being enough to lock its
     * owner out of their own account.
     */
    public TeamMember changeOwnPassword(Long id, String current, String next) {
        TeamMember member = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No team member found with id " + id));

        if (!member.hasPassword()) {
            // Signed in on a password from application.properties: there is no
            // stored hash to compare against and nothing here to change.
            throw new IllegalArgumentException(
                    "Your account has no stored password yet. Ask an admin to set one on Settings > Team.");
        }
        // Deliberately the same wording for a wrong password and a missing one:
        // this form is reachable by whoever is at the keyboard.
        if (current == null || !encoder.matches(current, member.getPasswordHash())) {
            throw new IllegalArgumentException("That is not your current password.");
        }
        if (current.equals(next)) {
            throw new IllegalArgumentException("That is the password you already have.");
        }

        member.setPasswordHash(hash(next));
        return repository.save(member);
    }

    /**
     * Makes somebody an administrator, or takes it back.
     *
     * <p>Two things are refused. A badge on a name that cannot sign in does
     * nothing but mislead the roster, so a password has to come first; and the
     * last admin who can sign in cannot be demoted, because only an admin may
     * promote anybody and the app would have locked its own door.
     */
    public TeamMember setRole(Long id, MemberRole role) {
        TeamMember member = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No team member found with id " + id));
        MemberRole wanted = role == null ? MemberRole.MEMBER : role;

        if (wanted == MemberRole.ADMIN && !member.hasPassword()) {
            throw new IllegalArgumentException(member.getName()
                    + " cannot sign in yet, so an admin role would do nothing. Set a password first.");
        }
        if (wanted != MemberRole.ADMIN) {
            refuseIfLastAdmin(member, "make " + member.getName() + " a member");
        }

        member.setRole(wanted);
        return repository.save(member);
    }

    /**
     * Stops the last way in from being closed.
     *
     * <p>Four routes lead to the same place — demoting, deactivating, removing
     * and clearing a password — and any of them applied to the only admin who
     * can still sign in leaves an installation nobody can administer, which
     * nothing inside the app can undo. So all four ask here first.
     */
    private void refuseIfLastAdmin(TeamMember member, String what) {
        if (!member.isActiveAdmin()) {
            return;
        }
        boolean anotherOne = repository.findAllByOrderByNameAsc().stream()
                .anyMatch(other -> !other.getId().equals(member.getId()) && other.isActiveAdmin());
        if (!anotherOne) {
            throw new IllegalArgumentException("There would then be no administrator left, "
                    + "and only an administrator can appoint one. Make somebody else an admin "
                    + "before you " + what + ".");
        }
    }

    private String hash(String rawPassword) {
        String raw = rawPassword == null ? "" : rawPassword;
        if (raw.trim().isEmpty()) {
            throw new IllegalArgumentException("A password cannot be blank.");
        }
        // Length on the raw value, spaces included: a space is a character in a
        // password, and trimming it here would accept something shorter than
        // what the sign-in form will later send.
        if (raw.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException(
                    "A password needs at least " + MIN_PASSWORD + " characters.");
        }
        return encoder.encode(raw);
    }

    /** One person by the address they sign in with — how sign-in finds them. */
    @Transactional(readOnly = true)
    public Optional<TeamMember> findByEmail(String email) {
        return email == null || email.isBlank()
                ? Optional.empty()
                : repository.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT));
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
        if (!active) {
            refuseIfLastAdmin(member, "deactivate " + member.getName());
        }
        member.setActive(active);
        return repository.save(member);
    }
}
