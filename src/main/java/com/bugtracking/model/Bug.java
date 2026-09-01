package com.bugtracking.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
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

    @NotNull(message = "Status is required")
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    /**
     * Business urgency, kept separate from severity on purpose. Defaults to P3
     * so bugs raised before this field existed - and any caller that omits it -
     * still have a usable value.
     */
    @NotNull(message = "Priority is required")
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 10)
    private Priority priority = Priority.P3;

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

    @Size(max = 80, message = "Assignee name must be 80 characters or fewer")
    @Column(name = "assigned_to", length = 80)
    private String assignedTo;

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

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
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

    public String getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(String assignedTo) {
        this.assignedTo = assignedTo;
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
