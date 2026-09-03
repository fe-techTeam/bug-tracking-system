package com.bugtracking.service;

import com.bugtracking.model.Project;
import com.bugtracking.model.TeamMember;
import com.bugtracking.repository.BugRepository;
import com.bugtracking.repository.ProjectRepository;
import com.bugtracking.repository.TeamMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@Transactional
public class ProjectService {

    private final ProjectRepository repository;
    private final BugRepository bugs;
    private final ProjectDocService documents;
    private final BoardColumnService columns;
    private final TeamMemberRepository team;

    public ProjectService(ProjectRepository repository, BugRepository bugs,
                          ProjectDocService documents, BoardColumnService columns,
                          TeamMemberRepository team) {
        this.repository = repository;
        this.bugs = bugs;
        this.documents = documents;
        this.columns = columns;
        this.team = team;
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
     * What the switcher shows: the live projects, each with its bug count.
     *
     * <p>Projects, and nothing but projects. It used to add back any project
     * name a bug happened to carry, so that a bug filed against something that
     * was never created here still had a board to appear on — and the result
     * was a switcher listing four things that were not projects, could not be
     * opened in Settings and could not be deleted, because there was nothing
     * there to delete. "I removed it and it is still showing" is exactly what
     * that looks like from the outside.
     *
     * <p>A hidden project is left out too, which is the other half of the same
     * fix: hiding one that had bugs on it used to do nothing at all, because
     * the pass over bug names put it straight back.
     *
     * <p>Nothing is lost by a name not being here. The bug keeps it — bugs
     * name projects as text on purpose, so history is not rewritten — and it
     * is still found by search, by quick search and at
     * {@code /bugs?project=<name>}. Editing it offers the real project list,
     * which is how it gets filed somewhere that exists.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> sidebarCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Project project : active()) {
            // Counted case-insensitively, so "Godrej" picks up a bug filed as
            // "godrej" rather than leaving it uncounted and unreachable.
            counts.put(project.getName(),
                    bugs.countByProjectIgnoreCaseAndDeletedAtIsNull(project.getName()));
        }
        return counts;
    }

    /** One project by name, for the routes that are handed a name rather than an id. */
    @Transactional(readOnly = true)
    public java.util.Optional<Project> findByName(String name) {
        return name == null || name.isBlank()
                ? java.util.Optional.empty()
                : repository.findByNameIgnoreCase(name.trim());
    }

    @Transactional(readOnly = true)
    public long bugsIn(String name) {
        return bugs.countByProjectIgnoreCaseAndDeletedAtIsNull(name);
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
        return add(name, List.of());
    }

    /**
     * Adds a project and puts a team on it in one go.
     *
     * <p>Naming an existing project adds the chosen people to it rather than
     * failing — the form is "a project and who is on it", and re-submitting it
     * with more names is the obvious way to say "these people too". Nobody is
     * ever removed by this route; that is what the team editor on the project's
     * own row is for.
     */
    public Project add(String name, List<Long> memberIds) {
        String clean = name == null ? "" : name.trim();
        if (clean.isBlank()) {
            throw new IllegalArgumentException("A project needs a name.");
        }
        Project project = repository.findByNameIgnoreCase(clean)
                .orElseGet(() -> repository.save(new Project(clean)));
        // A new project opens on the six columns this app has always had, which
        // it is then free to rename, reorder or replace. Doing it here rather
        // than lazily means the columns are editable in Settings the moment the
        // project exists, instead of only once somebody has opened its board.
        columns.seed(project.getName());

        if (memberIds != null && !memberIds.isEmpty()) {
            Project withTeam = repository.findByIdWithMembers(project.getId()).orElse(project);
            withTeam.getMembers().addAll(team.findAllById(memberIds));
            return repository.save(withTeam);
        }
        return project;
    }

    /**
     * Replaces a project's team with exactly the people chosen.
     *
     * <p>Set, not merge: the editor shows every member with a tick beside the
     * ones who are on, so an unticked box has to mean "take them off" or the
     * form can only ever add. An empty list is a legitimate answer — a project
     * with nobody on it yet.
     *
     * <p>Nothing on a bug changes. Bugs name people as text, so taking somebody
     * off a project leaves every bug they are on exactly as it was; they simply
     * stop being offered here.
     */
    public Project setMembers(Long id, List<Long> memberIds) {
        Project project = repository.findByIdWithMembers(id)
                .orElseThrow(() -> new NoSuchElementException("No project found with id " + id));
        project.getMembers().clear();
        if (memberIds != null && !memberIds.isEmpty()) {
            project.getMembers().addAll(team.findAllById(memberIds));
        }
        return repository.save(project);
    }

    /**
     * Puts one person on one project, leaving whoever is already there.
     *
     * <p>For adding somebody from the board's team drawer: the person is being
     * created and put on the project you are looking at in a single form, and
     * {@link #setMembers} cannot be used for that — it replaces the team, and
     * the drawer's Add form does not carry the rest of it.
     */
    public Project addMember(Long projectId, Long memberId) {
        Project project = repository.findByIdWithMembers(projectId)
                .orElseThrow(() -> new NoSuchElementException("No project found with id " + projectId));
        team.findById(memberId).ifPresent(member -> project.getMembers().add(member));
        return repository.save(project);
    }

    /**
     * Every project's team, by project id — one query for the whole Settings
     * table rather than a walk into each project's lazy collection, which with
     * open-in-view=false would not have worked from the template anyway.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<TeamMember>> membersByProjectId() {
        Map<Long, List<TeamMember>> teams = new LinkedHashMap<>();
        for (Project project : repository.findAllWithMembers()) {
            teams.put(project.getId(), sortedByName(project));
        }
        return teams;
    }

    /**
     * The same as ids, which is what the tick boxes compare against. Ids rather
     * than the members themselves so the template never has to ask whether two
     * TeamMember objects loaded by two different queries are the same person.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> memberIdsByProjectId() {
        Map<Long, List<Long>> ids = new LinkedHashMap<>();
        for (Project project : repository.findAllWithMembers()) {
            ids.put(project.getId(), project.getMembers().stream().map(TeamMember::getId).toList());
        }
        return ids;
    }

    private static List<TeamMember> sortedByName(Project project) {
        return project.getMembers().stream()
                .sorted(Comparator.comparing(TeamMember::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The team on one project, by the project's name, sorted the way people are
     * listed everywhere else. Empty for a name no project row holds — a bug can
     * still carry the name of a project that was never created here.
     */
    @Transactional(readOnly = true)
    public List<TeamMember> membersOf(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return List.of();
        }
        return repository.findByNameIgnoreCaseWithMembers(projectName.trim())
                .map(ProjectService::sortedByName)
                .orElseGet(List::of);
    }

    /** The same as names, for the screens that only draw a face and a label. */
    @Transactional(readOnly = true)
    public List<String> memberNamesOf(String projectName) {
        return membersOf(projectName).stream().map(TeamMember::getName).toList();
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
        // Its documents go with it. They are reachable only through the project,
        // so leaving them behind would leave rows and files nothing can ever
        // list again - and the next project to be given this id would inherit
        // somebody else's folders.
        documents.deleteForProject(id);
        // Its board goes with it. Nothing else can reach those columns once the
        // project is gone, and a project later given the same name should start
        // from the defaults rather than inherit a stranger's process.
        columns.removeProject(project.getName());
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
