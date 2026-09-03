package com.bugtracking.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Somebody who can raise or be assigned a bug.
 *
 * <p>Bugs still store the person's <em>name</em> as text rather than a foreign
 * key. That keeps every bug raised before this table existed readable, and means
 * renaming or deactivating a member never rewrites history.
 *
 * <p>This is also the users table. Somebody here with a {@link #getPasswordHash()
 * password} can sign in; the rest are names that appear on bugs and nothing
 * more. There is deliberately no second table for accounts — it would have split
 * one person across two rows and left every screen asking which half to draw.
 */
@Entity
@Table(name = "team_members")
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be 80 characters or fewer")
    @Column(nullable = false, length = 80)
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "That does not look like an email address")
    @Size(max = 120, message = "Email must be 120 characters or fewer")
    @Column(nullable = false, length = 120, unique = true)
    private String email;

    /**
     * BCrypt hash of this person's sign-in password, or null when they have no
     * account. Null rather than blank on purpose: "cannot sign in" is the right
     * reading for everyone on the roster who was only ever a name on a bug, and
     * a null can never accidentally match an empty submitted password.
     *
     * <p>The plain password is never held here or anywhere else — it is hashed
     * on the way in by {@code TeamMemberService.setPassword} and only ever
     * compared against.
     *
     * <p>{@code @JsonIgnore} is load-bearing, not tidiness: {@code /api/team}
     * serialises this entity and {@code /api/**} is permitAll, so without it
     * every hash on the team would be readable by an unauthenticated GET.
     * {@link #hasPassword()} is what the API is allowed to say instead.
     */
    @JsonIgnore
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** Someone who has left stays in the table so old bugs still read correctly. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public TeamMember() {
    }

    public TeamMember(String name, String email) {
        this.name = name;
        this.email = email;
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @JsonIgnore
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** Whether this person can sign in at all — an account, not just a name. */
    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
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

    /**
     * Identity is the row, not the field values — a member is now held in a Set
     * on {@link Project}, and reference equality would make the same person
     * loaded through two paths look like two different people.
     *
     * <p>The constant hash code is the Hibernate-safe pairing for it: a member
     * put in a set before being saved has a null id, and hashing on the id
     * would strand it in the wrong bucket the moment the insert filled one in.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof TeamMember member
                && id != null && id.equals(member.id);
    }

    @Override
    public int hashCode() {
        return TeamMember.class.hashCode();
    }
}
