package com.bugtracking.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
     * serialises this entity, so without it every hash on the team travels in
     * the response. The API is signed-in-only now, which makes that a smaller
     * blast radius than when it was permitAll and not a reason to relax it.
     * {@link #hasPassword()} is what the API is allowed to say instead.
     */
    @JsonIgnore
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /**
     * What this person may administer. Everybody is a {@link MemberRole#MEMBER}
     * until somebody makes them otherwise.
     *
     * <p>Not null even for the people who never sign in: a role is what the row
     * <em>would</em> be allowed to do, and leaving it null would mean every
     * read had to decide what a missing one meant.
     *
     * <p>{@code @JdbcTypeCode(VARCHAR)} is the same load-bearing annotation
     * {@link BoardColumn} carries: without it Hibernate maps the enum to H2's
     * native ENUM type, writing today's constants into the column type itself,
     * and adding a third role later becomes a schema change that ddl-auto will
     * not make.
     *
     * <p>The {@code default 'MEMBER'} in the column definition is what lets H2
     * add this to a table that already has people in it. {@code ddl-auto=update}
     * would otherwise emit a bare {@code add column ... not null}, which H2
     * refuses on a non-empty table — it logs the failure and carries on, and the
     * next read of the roster fails on a column that is not there. Postgres
     * never sees this: {@code V6__team_member_roles.sql} owns that schema and
     * says the same thing.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'MEMBER'")
    private MemberRole role = MemberRole.MEMBER;

    /** Someone who has left stays in the table so old bugs still read correctly. */
    @Column(nullable = false)
    private boolean active = true;

    /**
     * The one project a guest may see, and null for everybody who is not one.
     *
     * <p>A bare id rather than a relation: it is read once per request to scope
     * a guest's own reports, never navigated, and a project deleted out from
     * under a client should leave them with an empty portal rather than take
     * the account down with it — the same reasoning {@code Bug.blockedBy}
     * carries.
     *
     * <p>Deliberately not {@code project_members}. Being on a project means
     * being on its team: it feeds the assignee picker, the people filter and
     * the workload bars, and a client appearing in any of those is a bug
     * waiting to be filed. Two different facts, so two different columns.
     */
    @Column(name = "guest_project_id")
    private Long guestProjectId;

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

    /**
     * {@code @JsonIgnore} for the same reason the hash carries one, if less
     * sharply: {@code /api/team} serialises this entity, and who is an admin is
     * the answer to "which of these accounts is worth attacking". The roster
     * page reads this server-side; the JSON has no business publishing it.
     */
    @JsonIgnore
    public MemberRole getRole() {
        return role;
    }

    public void setRole(MemberRole role) {
        this.role = role == null ? MemberRole.MEMBER : role;
    }

    /** Whether this person may change the setup, not just work inside it. */
    @JsonIgnore
    public boolean isAdmin() {
        return role == MemberRole.ADMIN;
    }

    /**
     * An admin who can actually sign in, which is the only kind that counts
     * when the app is deciding whether anybody is left who can administer it.
     * A row marked ADMIN with no password is a name on a bug wearing a badge.
     *
     * <p>Hidden from the API most of all: it is "admin, and has a password",
     * which is the single most useful sentence an attacker could be handed.
     */
    @JsonIgnore
    public boolean isActiveAdmin() {
        return isAdmin() && active && hasPassword();
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

    public Long getGuestProjectId() {
        return guestProjectId;
    }

    public void setGuestProjectId(Long guestProjectId) {
        this.guestProjectId = guestProjectId;
    }

    /** Somebody from outside: a client, not a member of the team. */
    public boolean isGuest() {
        return role == MemberRole.GUEST;
    }
}
