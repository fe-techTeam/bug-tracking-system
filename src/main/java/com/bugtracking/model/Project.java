package com.bugtracking.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A project a bug can be raised against — the top-level way the board is split.
 *
 * <p>As with {@link TeamMember}, bugs store the project <em>name</em> as text
 * rather than a foreign key, so a bug raised before this table existed still
 * reads correctly and hiding a project never rewrites history.
 *
 * <p>Its {@link #getMembers() team} is the exception, and deliberately so. A
 * bug is history and must not be rewritten by a rename; who is on a project is
 * a live fact, so it is a real relation with real foreign keys — take somebody
 * off the team and they are off it, with no name left stranded behind.
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Project name is required")
    @Size(max = 80, message = "Project name must be 80 characters or fewer")
    @Column(nullable = false, length = 80, unique = true)
    private String name;

    /**
     * The people who work on this project.
     *
     * <p>Lazy, and the app runs with {@code open-in-view=false}, so a template
     * cannot walk into this after the transaction has closed — every screen
     * that draws a team reads it through one of ProjectRepository's fetch-join
     * queries instead, which also keeps it off the N+1 path.
     *
     * <p>Which is exactly why Jackson must not see it: {@code /api/projects}
     * serialises this entity outside any transaction, and a lazy collection
     * there is not an empty list but a failed request. The team has an endpoint
     * of its own that loads it properly.
     */
    @JsonIgnore
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "project_members",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "member_id"))
    private Set<TeamMember> members = new LinkedHashSet<>();

    /** A retired project stays in the table so its bugs still read correctly. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Project() {
    }

    public Project(String name) {
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @JsonIgnore
    public Set<TeamMember> getMembers() {
        return members;
    }

    public void setMembers(Set<TeamMember> members) {
        this.members = members == null ? new LinkedHashSet<>() : members;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
