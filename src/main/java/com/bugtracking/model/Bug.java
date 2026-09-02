package com.bugtracking.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One reported bug. */
@Entity
@Table(name = "bugs")
public class Bug {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Title is required")
    @Size(max = 150, message = "Title must be 150 characters or fewer")
    @Column(nullable = false, length = 150)
    private String title;

    @Size(max = 4000, message = "Description is too long")
    @Column(length = 4000)
    private String description;

    @Size(max = 4000, message = "Steps to reproduce are too long")
    @Column(name = "steps_to_reproduce", length = 4000)
    private String stepsToReproduce;

    @Size(max = 1000)
    @Column(name = "expected_result", length = 1000)
    private String expectedResult;

    @Size(max = 1000)
    @Column(name = "actual_result", length = 1000)
    private String actualResult;

    /*
     * The @JdbcTypeCode on every enum below is deliberate. Left to itself,
     * Hibernate maps an enum to H2's native ENUM type, which pins the column to
     * the constants that existed when the table was created - adding a new
     * status later is then rejected at runtime. Plain VARCHAR has no such
     * memory. SchemaUpgrade converts columns created the old way.
     */
    @NotNull(message = "Severity is required")
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Severity severity = Severity.MEDIUM;

    /**
     * Which column of its project's board the bug is sitting in, held as that
     * column's key.
     *
     * <p>A plain string rather than an enum, because the columns are rows now:
     * a project can rename them, add its own and put them in whatever order it
     * runs. The key is written once, when the column is created, and never
     * rewritten — so renaming a column changes nothing here, which is exactly
     * why the two are separate. Resolve it to something readable through
     * {@code BoardColumns}, which knows the project's wording and colour.
     */
    @NotBlank(message = "Status is required")
    @Size(max = 40, message = "Status is too long")
    @Column(nullable = false, length = 40)
    private String status = DefaultColumns.FIRST_KEY;

    /** Where the bug was seen: QA, UAT or Production. */
    @NotNull(message = "Environment is required")
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private Environment environment = Environment.QA;

    /**
     * The project the bug belongs to — the top-level way the board is split.
     * Chosen from the {@code projects} table but stored as plain text, so
     * renaming or retiring a project never invalidates old bugs. Left nullable
     * in the database so the column could be added to an existing table;
     * "required" is enforced by validation on the way in.
     */
    @NotBlank(message = "Project is required")
    @Size(max = 80, message = "Project name must be 80 characters or fewer")
    @Column(length = 80)
    private String project;

    @Size(max = 80, message = "Module name must be 80 characters or fewer")
    @Column(length = 80)
    private String module;

    @Size(max = 80, message = "Reporter name must be 80 characters or fewer")
    @Column(name = "reported_by", length = 80)
    private String reportedBy;

    /**
     * Everyone working this bug. A list rather than one name, because a fix
     * often needs a developer and a tester on it at the same time.
     *
     * <p>Eager on purpose: {@code spring.jpa.open-in-view=false}, so a lazy
     * collection would explode the first time a template read it. Ordered, so
     * "the first assignee" is a stable idea rather than whatever the database
     * returns — see {@link #getAssignedTo()}, which is what the JSON API and
     * every older caller still see.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "bug_assignees", joinColumns = @JoinColumn(name = "bug_id"))
    @OrderColumn(name = "position")
    @Column(name = "assignee", length = 80)
    private List<String> assignees = new ArrayList<>();

    /**
     * The open bug that has to be dealt with before this one can move, if any.
     * A plain id rather than a relation: a blocker that is later deleted leaves
     * a dangling number, which reads as "not blocked", instead of taking this
     * bug down with it.
     */
    @Column(name = "blocked_by")
    private Long blockedBy;

    /**
     * When this bug was moved to the trash, or null while it is live.
     *
     * <p>Deleting is reversible: the row, its comments, its files and its
     * history all stay exactly where they are and every query simply stops
     * looking at them. Only emptying the trash actually destroys anything.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Size(max = 80)
    @Column(name = "deleted_by", length = 80)
    private String deletedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Timestamps are managed here so callers never have to set them. */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStepsToReproduce() {
        return stepsToReproduce;
    }

    public void setStepsToReproduce(String stepsToReproduce) {
        this.stepsToReproduce = stepsToReproduce;
    }

    public String getExpectedResult() {
        return expectedResult;
    }

    public void setExpectedResult(String expectedResult) {
        this.expectedResult = expectedResult;
    }

    public String getActualResult() {
        return actualResult;
    }

    public void setActualResult(String actualResult) {
        this.actualResult = actualResult;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getReportedBy() {
        return reportedBy;
    }

    public void setReportedBy(String reportedBy) {
        this.reportedBy = reportedBy;
    }

    public List<String> getAssignees() {
        return assignees;
    }

    /**
     * Trims, drops blanks and de-duplicates, so the list is always presentable.
     *
     * <p>Trimming comes first and the comparison folds case, because the two
     * names that reach here as one person's are "Nishana R" and "Nishana R " —
     * or "nishana r" from a hand-written script. Left as they were, each pair
     * would draw two faces, count twice in the people bar and split one
     * person's heat card in half. The casing that survives is the one seen
     * first, and the order is left alone: the first assignee means something.
     */
    public void setAssignees(List<String> people) {
        List<String> clean = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (people != null) {
            for (String person : people) {
                if (person == null) {
                    continue;
                }
                String name = person.trim();
                if (!name.isEmpty() && seen.add(name.toLowerCase(Locale.ROOT))) {
                    clean.add(name);
                }
            }
        }
        this.assignees = clean;
    }

    /**
     * The first assignee, or null. Keeps {@code setAssignedTo} and every
     * pre-existing caller working now that a bug can have several people on it.
     *
     * <p>Write-only in JSON on purpose: a body carrying both {@code assignees}
     * and {@code assignedTo} has the second overwrite the first, so a bug read
     * from the API and sent straight back used to come home with only its first
     * person on it. Sending {@code assignedTo} in still works; it is only no
     * longer handed out to be sent back.
     */
    @Transient
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getAssignedTo() {
        return assignees.isEmpty() ? null : assignees.get(0);
    }

    /** Replaces the whole list with one person, or clears it when given blank. */
    public void setAssignedTo(String assignedTo) {
        setAssignees(assignedTo == null || assignedTo.isBlank()
                ? List.of()
                : List.of(assignedTo));
    }

    /** The assignees as one string, for the history trail and flash messages. */
    @Transient
    public String getAssigneesLabel() {
        return assignees.isEmpty() ? null : String.join(", ", assignees);
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(String deletedBy) {
        this.deletedBy = deletedBy;
    }

    @Transient
    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Long getBlockedBy() {
        return blockedBy;
    }

    public void setBlockedBy(Long blockedBy) {
        this.blockedBy = blockedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
