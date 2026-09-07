# Public Bug Intake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every project gets a public link at `/public/{token}` where anyone can raise a bug without signing in; the bug lands unassigned and marked External, and signed-in users can copy the link from the navbar.

**Architecture:** A random `public_token` on `projects` is the whole grant: `PublicIntakeService` resolves it to one active project and raises the bug from a closed form object, mirroring `GuestService.raise`. `/public/**` is `permitAll` in `SecurityConfig` with CSRF kept on. The new bug carries `via_public` (distinct from `via_guest`, which drives client-sharing controls) and `reporter_email`.

**Tech Stack:** Spring Boot 3.5, Java 21, Thymeleaf, Flyway (Postgres), H2 at test scope, JUnit 5 + Mockito + MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-07-public-bug-intake-design.md`

## Global Constraints

- Comments: single line only, and only for a non-obvious why. Never add to existing Javadoc blocks.
- Every stored enum is VARCHAR; every entity change ships a migration in `src/main/resources/db/migration/postgres/V<next>__*.sql`, idempotent (`add column if not exists`).
- Never edit an applied migration. Next number is **V12**.
- Layers: controller → service → repository. Controllers handle HTTP only.
- `/api/**` must never expose the token: `@JsonIgnore` on `Project.publicToken`.
- Any new admin write route gets a line in `SecurityConfig.filterChain` and its control gets `sec:authorize`.
- Frontend: read `.claude/skills/frontend/SKILL.md` first. Tokens not literals, both themes, reuse `.icon-btn`, `.portal-*`, `.guest-mark`, `.btn`, `.file-drop`. HTML forms use `th:action` for CSRF.
- Tests: `mvn test -Dtest=<Class>` via `./run.sh` JDK; existing tests are `@DataJpaTest` on H2 (`src/test/resources/application.properties`, `ddl-auto=create-drop`, Flyway off).
- Mock beans in Spring tests with `@MockitoBean` (`org.springframework.test.context.bean.override.mockito.MockitoBean`), not the deprecated `@MockBean`.
- Commit after every task; never on `main` (branch is `feat/public-bug-creation`).

---

### Task 1: Token on projects, external fields on bugs, V12 migration — **easy**

**Files:**
- Create: `src/main/resources/db/migration/postgres/V12__public_intake.sql`
- Create: `src/main/java/com/bugtracking/model/PublicTokens.java`
- Modify: `src/main/java/com/bugtracking/model/Project.java` (fields after `createdAt`, `onCreate`, getters)
- Modify: `src/main/java/com/bugtracking/model/Bug.java:199-200` (after `viaGuest`)
- Modify: `src/main/java/com/bugtracking/repository/ProjectRepository.java`
- Modify: `src/main/java/com/bugtracking/repository/BugRepository.java:118-120`
- Test: `src/test/java/com/bugtracking/model/PublicTokensTest.java`
- Test: `src/test/java/com/bugtracking/repository/ProjectPublicTokenTest.java`

**Interfaces:**
- Produces: `PublicTokens.fresh()` → 32-char URL-safe `String`; `Project.getPublicToken()/setPublicToken(String)`; `Bug.isViaPublic()/setViaPublic(boolean)`, `Bug.getReporterEmail()/setReporterEmail(String)`; `ProjectRepository.findByPublicTokenAndActiveTrue(String)` → `Optional<Project>`; `BugRepository.countByViaPublicTrueAndDeletedAtIsNull()`, `countByProjectIgnoreCaseAndViaPublicTrueAndDeletedAtIsNull(String)`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/bugtracking/model/PublicTokensTest.java`:
```java
package com.bugtracking.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicTokensTest {

    @Test
    void isThirtyTwoUrlSafeCharacters() {
        String token = PublicTokens.fresh();
        assertEquals(32, token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]{32}"));
    }

    @Test
    void differsEveryTime() {
        assertNotEquals(PublicTokens.fresh(), PublicTokens.fresh());
    }
}
```

`src/test/java/com/bugtracking/repository/ProjectPublicTokenTest.java`:
```java
package com.bugtracking.repository;

import com.bugtracking.model.Project;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class ProjectPublicTokenTest {

    @Autowired
    ProjectRepository projects;

    @Test
    void aNewProjectGetsAToken() {
        Project saved = projects.save(new Project("Acme"));
        assertNotNull(saved.getPublicToken());
        assertEquals(32, saved.getPublicToken().length());
    }

    @Test
    void findsAnActiveProjectByToken() {
        Project saved = projects.save(new Project("Acme"));
        assertEquals("Acme", projects.findByPublicTokenAndActiveTrue(saved.getPublicToken()).orElseThrow().getName());
    }

    @Test
    void aHiddenProjectIsNotFoundByToken() {
        Project saved = projects.save(new Project("Acme"));
        saved.setActive(false);
        projects.save(saved);
        assertTrue(projects.findByPublicTokenAndActiveTrue(saved.getPublicToken()).isEmpty());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./run.sh test` is the whole suite; for one class use the JDK `run.sh` picks: `mvn test -Dtest='PublicTokensTest,ProjectPublicTokenTest'`
Expected: compilation failure, `PublicTokens` and `getPublicToken` do not exist.

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/postgres/V12__public_intake.sql`:
```sql
-- V12: a public link per project, and where a bug raised through one came from.

alter table projects
    add column if not exists public_token varchar(32);

-- md5 rather than gen_random_bytes so no extension is needed; a Java-side token replaces it on regenerate.
update projects
   set public_token = md5(random()::text || clock_timestamp()::text || id::text)
 where public_token is null;

alter table projects
    alter column public_token set not null;

create unique index if not exists ux_projects_public_token
    on projects (public_token);

alter table bugs
    add column if not exists via_public boolean not null default false;

alter table bugs
    add column if not exists reporter_email varchar(200);
```

- [ ] **Step 4: Add the token generator**

`src/main/java/com/bugtracking/model/PublicTokens.java`:
```java
package com.bugtracking.model;

import java.security.SecureRandom;
import java.util.Base64;

public final class PublicTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicTokens() {
    }

    // 24 random bytes is exactly 32 chars of unpadded base64url
    public static String fresh() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

- [ ] **Step 5: Add the entity fields**

In `Project.java`, after the `createdAt` field:
```java
    @JsonIgnore
    @Column(name = "public_token", nullable = false, length = 32, unique = true)
    private String publicToken;
```
Change `onCreate()`:
```java
    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.publicToken == null) {
            this.publicToken = PublicTokens.fresh();
        }
    }
```
Add getters at the end of the class:
```java
    @JsonIgnore
    public String getPublicToken() {
        return publicToken;
    }

    public void setPublicToken(String publicToken) {
        this.publicToken = publicToken;
    }
```

In `Bug.java`, directly after the `viaGuest` field (line 200):
```java
    @Column(name = "via_public", nullable = false)
    private boolean viaPublic = false;

    @Column(name = "reporter_email", length = 200)
    private String reporterEmail;
```
And after `setViaGuest`:
```java
    public boolean isViaPublic() {
        return viaPublic;
    }

    public void setViaPublic(boolean viaPublic) {
        this.viaPublic = viaPublic;
    }

    public String getReporterEmail() {
        return reporterEmail;
    }

    public void setReporterEmail(String reporterEmail) {
        this.reporterEmail = reporterEmail;
    }
```

- [ ] **Step 6: Add the repository methods**

`ProjectRepository.java`, next to `findByNameIgnoreCase`:
```java
    Optional<Project> findByPublicTokenAndActiveTrue(String publicToken);
```
`BugRepository.java`, after line 120:
```java
    long countByViaPublicTrueAndDeletedAtIsNull();

    long countByProjectIgnoreCaseAndViaPublicTrueAndDeletedAtIsNull(String project);
```

- [ ] **Step 7: Run the tests**

Run: `mvn test -Dtest='PublicTokensTest,ProjectPublicTokenTest'`
Expected: 5 tests PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/postgres/V12__public_intake.sql src/main/java/com/bugtracking/model src/main/java/com/bugtracking/repository src/test
git commit -m "feat: public token on projects, external fields on bugs (V12)"
```

---

### Task 2: PublicReport form object and PublicIntakeService — **standard**

**Files:**
- Create: `src/main/java/com/bugtracking/service/PublicReport.java`
- Create: `src/main/java/com/bugtracking/service/PublicIntakeService.java`
- Modify: `src/main/java/com/bugtracking/service/GuestRateLimit.java` (add `checkPublic`)
- Test: `src/test/java/com/bugtracking/service/PublicIntakeServiceTest.java`

**Interfaces:**
- Consumes: Task 1's `Project.getPublicToken`, `Bug.setViaPublic/setReporterEmail`, `ProjectRepository.findByPublicTokenAndActiveTrue`; existing `BugService.save(Bug, String)`, `AttachmentService.refusalFor(MultipartFile, boolean)`, `AttachmentService.storeFromGuest(Long, Long, MultipartFile, String)`, `AttachmentService.GUEST_MAX_FILES`, `NotificationService.notify(Long, String, String, String)`, `ProjectService.memberNamesOf(String)`, `EmailProperties.getBaseUrl()`.
- Produces:
  - `PublicReport` bean: `reporterName`, `reporterEmail`, `title`, `description`, `severity`, `environment`.
  - `PublicIntakeService.byToken(String)` → `Optional<Project>`
  - `PublicIntakeService.raise(String token, PublicReport form, MultipartFile[] files, String clientIp, String userAgent)` → `GuestService.Filed`
  - `PublicIntakeService.regenerate(Long projectId)` → `String` (new token)
  - `PublicIntakeService.publicUrl(Project)` → `String`
  - `PublicIntakeService.UnknownLinkException` (RuntimeException)
  - `GuestRateLimit.checkPublic(String clientIp)`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/bugtracking/service/PublicIntakeServiceTest.java`:
```java
package com.bugtracking.service;

import com.bugtracking.config.EmailProperties;
import com.bugtracking.model.Bug;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Project;
import com.bugtracking.model.Severity;
import com.bugtracking.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicIntakeServiceTest {

    ProjectRepository projects = mock(ProjectRepository.class);
    BugService bugs = mock(BugService.class);
    AttachmentService attachments = mock(AttachmentService.class);
    NotificationService notifications = mock(NotificationService.class);
    ProjectService projectService = mock(ProjectService.class);
    EmailProperties mail = new EmailProperties();
    GuestRateLimit limit = new GuestRateLimit();

    PublicIntakeService intake;
    Project acme;

    @BeforeEach
    void setUp() {
        acme = new Project("Acme");
        acme.setId(7L);
        acme.setPublicToken("tok-acme");
        when(projects.findByPublicTokenAndActiveTrue("tok-acme")).thenReturn(Optional.of(acme));
        when(projects.findByPublicTokenAndActiveTrue("nope")).thenReturn(Optional.empty());
        when(projects.findById(7L)).thenReturn(Optional.of(acme));
        when(projects.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bugs.save(any(Bug.class), anyString())).thenAnswer(inv -> {
            Bug b = inv.getArgument(0);
            b.setId(42L);
            return b;
        });
        when(projectService.memberNamesOf("Acme")).thenReturn(List.of("Ana", "Bo"));
        mail.setBaseUrl("https://bugs.example.com/");
        intake = new PublicIntakeService(projects, bugs, attachments, notifications, projectService, mail, limit);
    }

    private PublicReport report() {
        PublicReport r = new PublicReport();
        r.setReporterName("  Priya Nair ");
        r.setReporterEmail("Priya@Example.com");
        r.setTitle(" Sign-in swallows the first click ");
        r.setDescription("It does.");
        r.setSeverity(Severity.HIGH);
        r.setEnvironment(Environment.PRODUCTION);
        return r;
    }

    @Test
    void raisesAnUnassignedExternalBugOnTheTokenProject() {
        GuestService.Filed filed = intake.raise("tok-acme", report(), null, "10.0.0.1", "Mozilla/5.0");

        ArgumentCaptor<Bug> saved = ArgumentCaptor.forClass(Bug.class);
        verify(bugs).save(saved.capture(), eq("Priya Nair"));
        Bug bug = saved.getValue();
        assertEquals(42L, filed.bugId());
        assertNull(filed.rejected());
        assertEquals("Acme", bug.getProject());
        assertEquals("Priya Nair", bug.getReportedBy());
        assertEquals("priya@example.com", bug.getReporterEmail());
        assertEquals("Sign-in swallows the first click", bug.getTitle());
        assertTrue(bug.isViaPublic());
        assertFalse(bug.isViaGuest());
        assertNull(bug.getGuestId());
        assertTrue(bug.getAssignees().isEmpty());
        assertTrue(bug.getDescription().endsWith("Browser: Mozilla/5.0"));
    }

    @Test
    void tellsTheProjectTeam() {
        intake.raise("tok-acme", report(), null, "10.0.0.1", null);
        verify(notifications).notify(eq(42L), eq("public"), eq("Ana"),
                eq("Priya Nair (external) raised BUG-42 on Acme: Sign-in swallows the first click"));
        verify(notifications).notify(eq(42L), eq("public"), eq("Bo"), anyString());
    }

    @Test
    void refusesAnUnknownToken() {
        assertThrows(PublicIntakeService.UnknownLinkException.class,
                () -> intake.raise("nope", report(), null, "10.0.0.1", null));
        assertTrue(intake.byToken("nope").isEmpty());
    }

    @Test
    void limitsOneAddressToTwelveAnHour() {
        for (int i = 0; i < 12; i++) {
            intake.raise("tok-acme", report(), null, "10.0.0.9", null);
        }
        assertThrows(GuestRateLimit.TooOftenException.class,
                () -> intake.raise("tok-acme", report(), null, "10.0.0.9", null));
        intake.raise("tok-acme", report(), null, "10.0.0.10", null);
    }

    @Test
    void regenerateWritesAFreshToken() {
        String fresh = intake.regenerate(7L);
        assertEquals(32, fresh.length());
        assertEquals(fresh, acme.getPublicToken());
        verify(projects).save(acme);
    }

    @Test
    void publicUrlJoinsBaseAndToken() {
        assertEquals("https://bugs.example.com/public/tok-acme", intake.publicUrl(acme));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=PublicIntakeServiceTest`
Expected: compilation failure, `PublicReport` and `PublicIntakeService` missing.

- [ ] **Step 3: Add the rate limit method**

In `GuestRateLimit.java`, after `REPLIES_PER_HOUR`:
```java
    private static final int PUBLIC_REPORTS_PER_HOUR = 12;
```
After `checkReply`:
```java
    void checkPublic(String clientIp) {
        check("public:" + clientIp, PUBLIC_REPORTS_PER_HOUR,
                "That is a lot of reports in one hour from this connection. Give it a few minutes.");
    }
```

- [ ] **Step 4: Write the form object**

`src/main/java/com/bugtracking/service/PublicReport.java`:
```java
package com.bugtracking.service;

import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Closed on purpose, like GuestReport: nothing that decides project, column or assignee binds here.
public class PublicReport {

    @NotBlank(message = "Tell us your name")
    @Size(max = 80, message = "Name must be 80 characters or fewer")
    private String reporterName;

    @NotBlank(message = "Tell us your email so the team can reach you")
    @Email(message = "That does not look like an email address")
    @Size(max = 200, message = "Email must be 200 characters or fewer")
    private String reporterEmail;

    @NotBlank(message = "Give the report a title")
    @Size(max = 150, message = "Title must be 150 characters or fewer")
    private String title;

    @NotBlank(message = "Describe what happened")
    @Size(max = 4000, message = "That is longer than the report can hold")
    private String description;

    @NotNull(message = "Say how bad it is")
    private Severity severity = Severity.MEDIUM;

    @NotNull(message = "Say where you saw it")
    private Environment environment = Environment.PRODUCTION;

    public String getReporterName() { return reporterName; }
    public void setReporterName(String reporterName) { this.reporterName = reporterName; }
    public String getReporterEmail() { return reporterEmail; }
    public void setReporterEmail(String reporterEmail) { this.reporterEmail = reporterEmail; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment environment) { this.environment = environment; }
}
```
(Expand the one-line accessors to the file's usual multi-line style if the reviewer prefers; behaviour is identical.)

- [ ] **Step 5: Write the service**

`src/main/java/com/bugtracking/service/PublicIntakeService.java`:
```java
package com.bugtracking.service;

import com.bugtracking.config.EmailProperties;
import com.bugtracking.model.Bug;
import com.bugtracking.model.Project;
import com.bugtracking.model.PublicTokens;
import com.bugtracking.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
@Transactional
public class PublicIntakeService {

    public static class UnknownLinkException extends RuntimeException {
        public UnknownLinkException() {
            super("This link is not valid any more.");
        }
    }

    private final ProjectRepository projects;
    private final BugService bugs;
    private final AttachmentService attachments;
    private final NotificationService notifications;
    private final ProjectService projectService;
    private final EmailProperties mail;
    private final GuestRateLimit limit;

    public PublicIntakeService(ProjectRepository projects,
                               BugService bugs,
                               AttachmentService attachments,
                               NotificationService notifications,
                               ProjectService projectService,
                               EmailProperties mail,
                               GuestRateLimit limit) {
        this.projects = projects;
        this.bugs = bugs;
        this.attachments = attachments;
        this.notifications = notifications;
        this.projectService = projectService;
        this.mail = mail;
        this.limit = limit;
    }

    @Transactional(readOnly = true)
    public Optional<Project> byToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return projects.findByPublicTokenAndActiveTrue(token.trim());
    }

    public GuestService.Filed raise(String token, PublicReport form, MultipartFile[] files,
                                    String clientIp, String userAgent) {
        Project project = byToken(token).orElseThrow(UnknownLinkException::new);
        limit.checkPublic(clientIp == null ? "unknown" : clientIp);

        String name = form.getReporterName().trim();
        Bug bug = new Bug();
        bug.setTitle(form.getTitle().trim());
        bug.setDescription(withBrowser(form.getDescription(), userAgent));
        bug.setSeverity(form.getSeverity());
        bug.setEnvironment(form.getEnvironment());
        bug.setProject(project.getName());
        bug.setReportedBy(name);
        bug.setReporterEmail(form.getReporterEmail().trim().toLowerCase(Locale.ROOT));
        bug.setViaPublic(true);

        Bug saved = bugs.save(bug, name);
        String rejected = attach(saved.getId(), files, name);
        for (String person : projectService.memberNamesOf(project.getName())) {
            notifications.notify(saved.getId(), "public", person,
                    name + " (external) raised BUG-" + saved.getId() + " on " + project.getName()
                            + ": " + saved.getTitle());
        }
        return new GuestService.Filed(saved.getId(), rejected);
    }

    public String regenerate(Long projectId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("No project found with id " + projectId));
        project.setPublicToken(PublicTokens.fresh());
        projects.save(project);
        return project.getPublicToken();
    }

    @Transactional(readOnly = true)
    public String publicUrl(Project project) {
        String base = mail.getBaseUrl() == null ? "" : mail.getBaseUrl().trim().replaceAll("/+$", "");
        return base + "/public/" + project.getPublicToken();
    }

    private static String withBrowser(String description, String userAgent) {
        String body = description == null ? "" : description.trim();
        if (userAgent == null || userAgent.isBlank()) {
            return body;
        }
        return body + "\n\nBrowser: " + userAgent.trim();
    }

    // Same shape as GuestService.attach: collect refusals rather than throw, so a bad file never loses the report
    private String attach(Long bugId, MultipartFile[] files, String by) {
        if (files == null) {
            return null;
        }
        List<String> problems = new ArrayList<>();
        int stored = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (stored >= AttachmentService.GUEST_MAX_FILES) {
                problems.add("Only " + AttachmentService.GUEST_MAX_FILES
                        + " files can come with one report; the rest were not attached.");
                break;
            }
            String refusal = attachments.refusalFor(file, true);
            if (refusal != null) {
                problems.add(refusal);
                continue;
            }
            attachments.storeFromGuest(bugId, null, file, by);
            stored++;
        }
        return problems.isEmpty() ? null : String.join(" ", problems);
    }
}
```

- [ ] **Step 6: Run the test**

Run: `mvn test -Dtest=PublicIntakeServiceTest`
Expected: 6 tests PASS. If `EmailProperties` cannot be constructed with `new`, check it has a public no-arg constructor (it is a `@Component` with setters; it does).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/bugtracking/service src/test/java/com/bugtracking/service/PublicIntakeServiceTest.java
git commit -m "feat: PublicIntakeService raises external bugs from a project's public token"
```

---

### Task 3: Public routes, pages and the security allowlist — **complex** (UI: invoke `frontend-design:frontend-design` and read `.claude/skills/frontend/SKILL.md` before writing templates)

**Files:**
- Create: `src/main/java/com/bugtracking/controller/PublicIntakeController.java`
- Create: `src/main/resources/templates/public/layout.html`
- Create: `src/main/resources/templates/public/new.html`
- Create: `src/main/resources/templates/public/done.html`
- Create: `src/main/resources/templates/public/unavailable.html`
- Modify: `src/main/java/com/bugtracking/config/SecurityConfig.java:114` (the `permitAll` line)
- Modify: `src/main/resources/static/css/style.css` (append a `public-*` block after the portal block, only if a rule is needed that `.portal-*` does not cover)
- Test: `src/test/java/com/bugtracking/controller/PublicIntakeControllerTest.java`

**Interfaces:**
- Consumes: Task 2's `PublicIntakeService.byToken/raise`, `PublicReport`, `GuestService.Filed`, `GuestRateLimit.TooOftenException`, `AttachmentService.GUEST_MAX_FILES`, `Severity.values()`, `Environment.values()`.
- Produces: routes `GET /public/{token}`, `POST /public/{token}`, `GET /public/{token}/done?bug=n`; model attributes `project`, `report`, `severities`, `environments`, `maxFiles`, `bugId`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/bugtracking/controller/PublicIntakeControllerTest.java`:
```java
package com.bugtracking.controller;

import com.bugtracking.config.SecurityConfig;
import com.bugtracking.model.Project;
import com.bugtracking.repository.TeamMemberRepository;
import com.bugtracking.service.GuestService;
import com.bugtracking.service.PublicIntakeService;
import com.bugtracking.service.PublicReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = PublicIntakeController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {GlobalModelAttributes.class, GlobalExceptionHandler.class}))
@Import(SecurityConfig.class)
class PublicIntakeControllerTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    PublicIntakeService intake;
    @MockitoBean
    TeamMemberRepository team;

    private Project acme() {
        Project p = new Project("Acme");
        p.setId(7L);
        p.setPublicToken("tok-acme");
        return p;
    }

    @Test
    void theFormIsOpenWithoutSigningIn() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        mvc.perform(get("/public/tok-acme"))
                .andExpect(status().isOk())
                .andExpect(view().name("public/new"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Acme")));
    }

    @Test
    void anUnknownTokenIsNotFoundAndNotALoginRedirect() throws Exception {
        when(intake.byToken("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/public/nope"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("public/unavailable"));
    }

    @Test
    void aMissingEmailComesBackToTheForm() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        mvc.perform(multipart("/public/tok-acme").with(csrf())
                        .param("reporterName", "Priya")
                        .param("title", "Broken")
                        .param("description", "It is")
                        .param("severity", "HIGH")
                        .param("environment", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(view().name("public/new"));
    }

    @Test
    void aGoodReportRedirectsToDone() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        when(intake.raise(eq("tok-acme"), any(PublicReport.class), any(), anyString(), isNull()))
                .thenReturn(new GuestService.Filed(42L, null));
        mvc.perform(multipart("/public/tok-acme").with(csrf())
                        .param("reporterName", "Priya")
                        .param("reporterEmail", "priya@example.com")
                        .param("title", "Broken")
                        .param("description", "It is")
                        .param("severity", "HIGH")
                        .param("environment", "PRODUCTION"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/public/tok-acme/done?bug=42"));
    }

    @Test
    void doneShowsTheBugNumber() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        mvc.perform(get("/public/tok-acme/done").param("bug", "42"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BUG-42")));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=PublicIntakeControllerTest`
Expected: compilation failure, `PublicIntakeController` missing.

- [ ] **Step 3: Open the routes in SecurityConfig**

In `SecurityConfig.filterChain`, change the first matcher line to:
```java
                    .requestMatchers("/login", "/css/**", "/js/**", "/favicon.ico").permitAll()
                    // The public intake form: the token in the URL is the whole grant, checked by PublicIntakeService.
                    .requestMatchers("/public/**").permitAll()
```

- [ ] **Step 4: Write the controller**

`src/main/java/com/bugtracking/controller/PublicIntakeController.java`:
```java
package com.bugtracking.controller;

import com.bugtracking.model.Environment;
import com.bugtracking.model.Project;
import com.bugtracking.model.Severity;
import com.bugtracking.service.AttachmentService;
import com.bugtracking.service.GuestRateLimit;
import com.bugtracking.service.GuestService;
import com.bugtracking.service.PublicIntakeService;
import com.bugtracking.service.PublicReport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

@Controller
@RequestMapping("/public/{token}")
public class PublicIntakeController {

    private final PublicIntakeService intake;

    public PublicIntakeController(PublicIntakeService intake) {
        this.intake = intake;
    }

    @GetMapping
    public String form(@PathVariable String token, Model model, HttpServletResponse response) {
        Optional<Project> project = intake.byToken(token);
        if (project.isEmpty()) {
            return unavailable(model, response);
        }
        model.addAttribute("project", project.get());
        model.addAttribute("report", new PublicReport());
        addOptions(model);
        return "public/new";
    }

    @PostMapping
    public String raise(@PathVariable String token,
                        @Valid @ModelAttribute("report") PublicReport report,
                        BindingResult result,
                        @RequestParam(value = "files", required = false) MultipartFile[] files,
                        HttpServletRequest request,
                        HttpServletResponse response,
                        Model model,
                        RedirectAttributes flash) {
        Optional<Project> project = intake.byToken(token);
        if (project.isEmpty()) {
            return unavailable(model, response);
        }
        if (result.hasErrors()) {
            model.addAttribute("project", project.get());
            addOptions(model);
            return "public/new";
        }
        GuestService.Filed filed = intake.raise(token, report, files,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        if (filed.rejected() != null) {
            flash.addFlashAttribute("message", "Sent, but " + filed.rejected());
        }
        return "redirect:/public/" + token + "/done?bug=" + filed.bugId();
    }

    @GetMapping("/done")
    public String done(@PathVariable String token,
                       @RequestParam(required = false) Long bug,
                       Model model,
                       HttpServletResponse response) {
        Optional<Project> project = intake.byToken(token);
        if (project.isEmpty()) {
            return unavailable(model, response);
        }
        model.addAttribute("project", project.get());
        model.addAttribute("bugId", bug);
        return "public/done";
    }

    private static String unavailable(Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        model.addAttribute("message", "This link is not valid any more. Ask the team for a fresh one.");
        return "public/unavailable";
    }

    private static void addOptions(Model model) {
        model.addAttribute("severities", Severity.values());
        model.addAttribute("environments", Environment.values());
        model.addAttribute("maxFiles", AttachmentService.GUEST_MAX_FILES);
    }

    // Local handlers: GlobalExceptionHandler renders the app shell, which a stranger must never see.
    @ExceptionHandler(PublicIntakeService.UnknownLinkException.class)
    public String unknownLink(Model model, HttpServletResponse response) {
        return unavailable(model, response);
    }

    @ExceptionHandler({GuestRateLimit.TooOftenException.class,
                       AttachmentService.RejectedFileException.class,
                       IllegalArgumentException.class})
    public String refused(RuntimeException e, @PathVariable String token, RedirectAttributes flash) {
        flash.addFlashAttribute("message", e.getMessage());
        return "redirect:/public/" + token;
    }
}
```
Note: `@PathVariable` inside an `@ExceptionHandler` method is supported by Spring MVC 6; if it resolves to null in the test, read it from `request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)` instead.

- [ ] **Step 5: Write the templates**

`public/layout.html`: copy `portal/layout.html` and change it so the shell is anonymous. Keep the sprite, the theme script, the flash, the footer. Remove `portal-who`, the Sign out form and the brand link target (brand becomes a `<span class="brand portal-brand">`, not a link). The `<em>` under the project name reads `report a bug`. The footer reads:
```html
    <footer class="portal-foot">
        <span>What you send here reaches the people working on
            <b th:text="${project.name}">Godrej</b>. Only they see your email.</span>
    </footer>
```
Keep `class="portal-page"` on `<body>` and the `portal-shell` wrapper so the existing portal CSS does all the work.

`public/new.html`: replace `portal/layout` with `public/layout`, drop the Back link and Cancel, post to the token URL, and add the two identity fields above the title:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{public/layout :: page('Report a bug', ~{::section})}">
<body>
<section>
    <header class="portal-head">
        <div>
            <h1>Report a bug</h1>
            <p class="sub">
                Tell the <b th:text="${project.name}">Godrej</b> team what went wrong.
                Somebody picks it up from here and can reply to your email.
            </p>
        </div>
    </header>

    <form class="panel portal-form" method="post" th:action="@{/public/{t}(t=${project.publicToken})}"
          th:object="${report}" enctype="multipart/form-data" id="report-form">
        <div class="panel-pad">
            <div class="portal-pair">
                <label>
                    <span>
                        <svg class="i" aria-hidden="true"><use href="#i-user"/></svg>
                        Your name <span class="req">*</span>
                    </span>
                    <input type="text" th:field="*{reporterName}" id="reporterName"
                           autocomplete="name" maxlength="80" enterkeyhint="next">
                    <span class="field-error" th:if="${#fields.hasErrors('reporterName')}"
                          th:errors="*{reporterName}">Name error</span>
                </label>
                <label>
                    <span>
                        <svg class="i" aria-hidden="true"><use href="#i-mail"/></svg>
                        Your email <span class="req">*</span>
                        <span class="hint">— so the team can reply</span>
                    </span>
                    <input type="email" th:field="*{reporterEmail}" id="reporterEmail"
                           autocomplete="email" maxlength="200" enterkeyhint="next" inputmode="email">
                    <span class="field-error" th:if="${#fields.hasErrors('reporterEmail')}"
                          th:errors="*{reporterEmail}">Email error</span>
                </label>
            </div>
            <!-- then the title, description, severity/environment pair and the
                 file-drop exactly as in portal/new.html, with margin-top:16px on the title label -->
        </div>
        <div class="panel-foot">
            <button type="submit" class="btn btn-primary" id="send-report">Send it</button>
        </div>
    </form>
</section>
</body>
</html>
```
Add `<symbol id="i-user">` and `<symbol id="i-mail">` to the sprite in `public/layout.html`, copied from `layout.html` lines 106 and 143.

`public/done.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{public/layout :: page('Sent', ~{::section})}">
<body>
<section>
    <div class="portal-empty">
        <h1>Thanks, that is with the team.</h1>
        <p th:if="${bugId != null}">It is logged as <b th:text="'BUG-' + ${bugId}">BUG-42</b>.
            Somebody on <b th:text="${project.name}">Godrej</b> will reply to the email you gave.</p>
        <a class="btn btn-ghost" th:href="@{/public/{t}(t=${project.publicToken})}">Report another</a>
    </div>
</section>
</body>
</html>
```

`public/unavailable.html`: copy `portal/unavailable.html`, title `Link not valid · Bug Tracking`, heading `This link does not work`, no Sign out form, and the `<p th:text="${message}">`.

- [ ] **Step 6: Run the test, then boot the app and open a real link**

Run: `mvn test -Dtest=PublicIntakeControllerTest`
Expected: 5 tests PASS.

Then `./run.sh migrate` (applies V12), `./run.sh bg`, sign in, run `psql`-free check: open `http://localhost:8085/public/<token>` where the token comes from `select public_token from projects limit 1` via `./run.sh db-info` is not enough; use the Supabase SQL editor or wait for Task 5's copy button. Confirm the form renders in light and dark, submit one report, confirm it lands on the board unassigned. `./run.sh stop`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/bugtracking/controller/PublicIntakeController.java src/main/java/com/bugtracking/config/SecurityConfig.java src/main/resources/templates/public src/main/resources/static/css/style.css src/test/java/com/bugtracking/controller/PublicIntakeControllerTest.java
git commit -m "feat: public bug intake page at /public/{token}"
```

---

### Task 4: Regenerate route and Settings controls — **standard** (UI: invoke `frontend-design:frontend-design`)

**Files:**
- Modify: `src/main/java/com/bugtracking/controller/ProjectController.java` (constructor, new handler)
- Modify: `src/main/java/com/bugtracking/controller/SettingsController.java` (add `publicLinks` model attribute on the projects tab)
- Modify: `src/main/java/com/bugtracking/config/SecurityConfig.java:103-104`
- Modify: `src/main/resources/templates/settings.html:454-472` (Actions cell)
- Modify: `src/main/resources/static/js/app.js` (one delegated click handler for `[data-copy-url]`)
- Test: `src/test/java/com/bugtracking/controller/ProjectPublicLinkRouteTest.java`

**Interfaces:**
- Consumes: Task 2's `PublicIntakeService.regenerate(Long)` and `publicUrl(Project)`.
- Produces: `POST /projects/{id}/public-link` (ADMIN); model attribute `publicLinks` (`Map<Long,String>` project id → URL) on the Settings projects tab; `app.js` behaviour: any element with `data-copy-url="<url>"` copies it on click and toasts "Public link copied".

- [ ] **Step 1: Write the failing test**

`src/test/java/com/bugtracking/controller/ProjectPublicLinkRouteTest.java`:
```java
package com.bugtracking.controller;

import com.bugtracking.config.SecurityConfig;
import com.bugtracking.repository.TeamMemberRepository;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.PublicIntakeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProjectController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {GlobalModelAttributes.class, GlobalExceptionHandler.class}))
@Import(SecurityConfig.class)
class ProjectPublicLinkRouteTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ProjectService projects;
    @MockitoBean
    PublicIntakeService intake;
    @MockitoBean
    TeamMemberRepository team;

    @Test
    void aMemberIsRefused() throws Exception {
        mvc.perform(post("/projects/7/public-link").with(csrf()).with(user("bo").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminRegeneratesAndReturnsToSettings() throws Exception {
        when(intake.regenerate(7L)).thenReturn("fresh-token-fresh-token-fresh-tok");
        mvc.perform(post("/projects/7/public-link").with(csrf()).with(user("ana").roles("ADMIN", "USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"));
        verify(intake).regenerate(7L);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=ProjectPublicLinkRouteTest`
Expected: FAIL — the member test gets 404/405 and the admin test's `verify` fails (no handler yet). If the context fails on a missing constructor argument, that is the next step.

- [ ] **Step 3: Add the route and the security line**

`ProjectController.java`: add a `PublicIntakeService intake` constructor parameter and field, then:
```java
    @PostMapping("/{id}/public-link")
    public String regenerateLink(@PathVariable Long id, RedirectAttributes flash) {
        intake.regenerate(id);
        flash.addFlashAttribute("message", "The old public link no longer works. Copy the new one from the row.");
        return "redirect:/settings";
    }
```
`SecurityConfig.java`, change the projects matcher to:
```java
                    .requestMatchers(HttpMethod.POST,
                            "/projects", "/projects/*/active", "/projects/*/delete",
                            "/projects/*/public-link").hasRole("ADMIN")
```

- [ ] **Step 4: Run the test**

Run: `mvn test -Dtest=ProjectPublicLinkRouteTest`
Expected: 2 tests PASS.

- [ ] **Step 5: Hand the URLs to the Settings page**

In `SettingsController`, where the projects tab builds `projects`, `usage` and `projectTeam`, add a `PublicIntakeService intake` dependency and:
```java
        Map<Long, String> publicLinks = new LinkedHashMap<>();
        for (Project p : projects) {
            publicLinks.put(p.getId(), intake.publicUrl(p));
        }
        model.addAttribute("publicLinks", publicLinks);
```
(Read the file first; match its variable names and the existing imports.)

- [ ] **Step 6: The Settings row controls**

In `settings.html`, inside the Actions cell's `inline-form` div, before the Hide form:
```html
                                    <button type="button" class="btn btn-sm btn-ghost"
                                            th:attr="data-copy-url=${publicLinks.get(p.id)},aria-label='Copy the public link for ' + ${p.name}"
                                            title="Anyone with this link can report a bug on this project">
                                        <svg class="i" aria-hidden="true"><use href="#i-link"/></svg>
                                        <span>Copy link</span>
                                    </button>
                                    <form method="post" th:action="@{/projects/{id}/public-link(id=${p.id})}"
                                          class="inline-form" sec:authorize="hasRole('ADMIN')"
                                          onsubmit="return confirm('Anyone holding the current link will lose it. Make a new one?');">
                                        <button type="submit" class="btn btn-sm btn-ghost"
                                                th:attr="aria-label='Regenerate the public link for ' + ${p.name}">Regenerate</button>
                                    </form>
```
Ensure `<html>` in `settings.html` declares `xmlns:sec="http://www.thymeleaf.org/extras/spring-security"`; add it if missing. Extend the hint paragraph under the table with one sentence: `Every project has a public link where anyone can report a bug without signing in; regenerate it if it gets into the wrong hands.`

- [ ] **Step 7: The copy behaviour in app.js**

Inside the IIFE, near the toast helpers (after `wireToast` is defined), add:
```js
    /* ---------- copy a URL carried on a button ---------- */
    document.addEventListener("click", function (e) {
        var btn = e.target.closest && e.target.closest("[data-copy-url]");
        if (!btn) return;
        var url = btn.getAttribute("data-copy-url");
        var copier = (window.BT && window.BT.copyText)
            || function (text) { return navigator.clipboard.writeText(text); };
        copier(url).then(function () {
            flash("Public link copied");
        }).catch(function () {
            window.prompt("Copy this link", url);
        });
    });
```

- [ ] **Step 8: Verify in the app**

`./run.sh restart`, sign in as admin, open Settings → projects. Copy link on a row toasts and the clipboard holds `http://localhost:8085/public/<token>`. Regenerate asks, then the old URL 404s and the new one renders. Check light and dark. `./run.sh stop`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/bugtracking/controller src/main/java/com/bugtracking/config/SecurityConfig.java src/main/resources/templates/settings.html src/main/resources/static/js/app.js src/test/java/com/bugtracking/controller/ProjectPublicLinkRouteTest.java
git commit -m "feat: copy and regenerate a project's public link from Settings"
```

---

### Task 5: Navbar copy-link icon — **easy** (UI: invoke `frontend-design:frontend-design`)

**Files:**
- Modify: `src/main/java/com/bugtracking/controller/GlobalModelAttributes.java` (constructor, new `@ModelAttribute("publicLink")`)
- Modify: `src/main/resources/templates/layout.html:328` (right after the closing `</div>` of `#project-switcher`)
- Modify: `src/main/resources/static/css/style.css` (one rule if `.icon-btn` needs spacing next to the switcher)

**Interfaces:**
- Consumes: Task 2's `PublicIntakeService.publicUrl(Project)`; existing `ProjectService.findByName(String)`; Task 4's `[data-copy-url]` handler in `app.js`.
- Produces: model attribute `publicLink` (`String`, null when no project is selected or the reader is chromeless).

- [ ] **Step 1: Add the model attribute**

Add `PublicIntakeService intake` to `GlobalModelAttributes`'s constructor and fields, then after `currentProject(...)`:
```java
    /** The selected project's public intake URL, for the navbar's copy button. */
    @ModelAttribute("publicLink")
    public String publicLink(HttpServletRequest request, HttpSession session) {
        if (chromeless(request)) {
            return null;
        }
        String name = currentProject(request, session);
        return projects.findByName(name).map(intake::publicUrl).orElse(null);
    }
```

- [ ] **Step 2: The button in the navbar**

In `layout.html`, immediately after the switcher's closing `</div>` (before `<nav class="nav-links">`):
```html
        <!-- The selected project's public intake link. Anyone holding it can
             file a bug without signing in, so it is copied, never shown. -->
        <button type="button" class="icon-btn" id="copy-public-link" th:if="${publicLink != null}"
                th:attr="data-copy-url=${publicLink}"
                title="Copy the public bug-report link for this project"
                aria-label="Copy the public bug-report link for this project">
            <svg class="i" aria-hidden="true"><use href="#i-link"/></svg>
        </button>
```
If the button crowds the switcher, add one rule in `style.css` next to `.switcher` using only tokens (for example `#copy-public-link { margin-left: 4px; }` is acceptable as spacing is not a colour, but prefer an existing gap token if the navbar defines one).

- [ ] **Step 3: Verify in the app**

`./run.sh restart`. On the board with a project selected the icon shows; on "All projects" it does not. Clicking toasts "Public link copied" and the clipboard holds the URL. Check both themes and a narrow window (under 900px). `./run.sh stop`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bugtracking/controller/GlobalModelAttributes.java src/main/resources/templates/layout.html src/main/resources/static/css/style.css
git commit -m "feat: copy the project's public link from the navbar"
```

---

### Task 6: External mark, reporter email, and the source filter — **standard** (UI: invoke `frontend-design:frontend-design`)

**Files:**
- Modify: `src/main/java/com/bugtracking/repository/BugRepository.java:52-89` (search query + parameter)
- Modify: `src/main/java/com/bugtracking/service/BugService.java:78-86, 110-115` (`findAll` signature, `publicRaisedIn`)
- Modify: `src/main/java/com/bugtracking/controller/BugController.java:109, 155, 203-207, 465-472`
- Modify: every other caller of `BugService.findAll(` and `BugRepository.search(` (run `grep -rn "\.findAll(\|\.search(" src/main/java src/test/java`)
- Modify: `src/test/java/com/bugtracking/repository/BugRepositoryLabelsTest.java` (search now takes 11 args)
- Modify: `src/main/resources/templates/fragments.html:383-386` (add `externalMark`)
- Modify: `src/main/resources/templates/bugs/list.html:136-150, 394-402, 747, 1044`
- Modify: `src/main/resources/templates/bugs/detail.html:701`
- Modify: `src/main/resources/static/css/style.css:5982-5994`
- Test: `src/test/java/com/bugtracking/repository/BugRepositorySourceTest.java`

**Interfaces:**
- Consumes: Task 1's `Bug.isViaPublic`, `Bug.getReporterEmail`, `BugRepository.countBy…ViaPublic…`.
- Produces: `BugRepository.search(project, status, severity, environment, assignee, reporter, viaGuest, viaPublic, label, keyword, keywordId)`; `BugService.findAll(project, status, severity, environment, assignee, reporter, viaGuest, viaPublic, label, due, keyword, sort)`; `BugService.publicRaisedIn(String)`; `?source=public`; fragment `fragments :: externalMark(bug)`; model attribute `publicRaised`.

- [ ] **Step 1: Write the failing repository test**

`src/test/java/com/bugtracking/repository/BugRepositorySourceTest.java`:
```java
package com.bugtracking.repository;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class BugRepositorySourceTest {

    @Autowired
    BugRepository bugs;

    private Bug bug(String title, boolean guest, boolean external) {
        Bug b = new Bug();
        b.setTitle(title);
        b.setProject("Acme");
        b.setSeverity(Severity.MEDIUM);
        b.setEnvironment(Environment.QA);
        b.setStatus("OPEN");
        b.setViaGuest(guest);
        b.setViaPublic(external);
        return bugs.save(b);
    }

    @Test
    void filtersExternalReportsOnTheirOwn() {
        bug("team", false, false);
        bug("client", true, false);
        bug("external", false, true);
        List<Bug> external = bugs.search(null, null, null, null, null, null, null, true, null, null, null);
        assertEquals(List.of("external"), external.stream().map(Bug::getTitle).toList());
        List<Bug> team = bugs.search(null, null, null, null, null, null, false, false, null, null, null);
        assertEquals(List.of("team"), team.stream().map(Bug::getTitle).toList());
        assertEquals(1, bugs.countByProjectIgnoreCaseAndViaPublicTrueAndDeletedAtIsNull("acme"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=BugRepositorySourceTest`
Expected: compilation failure, `search` has 10 parameters.

- [ ] **Step 3: Extend the query**

In `BugRepository.search`, add after the `viaGuest` line of the JPQL:
```
              AND (:viaPublic IS NULL OR b.viaPublic = :viaPublic)
```
and add the parameter after `@Param("viaGuest") Boolean viaGuest,`:
```java
                     @Param("viaPublic") Boolean viaPublic,
```
Update `BugRepositoryLabelsTest` so each `search(` call passes `null` in the new eighth position.

- [ ] **Step 4: Thread it through the service**

`BugService.findAll` gains `Boolean viaPublic` after `viaGuest` and passes it to `repository.search` in the same position. Add next to `guestRaisedIn`:
```java
    @Transactional(readOnly = true)
    public long publicRaisedIn(String project) {
        String scope = blankToNull(project);
        return scope == null
                ? repository.countByViaPublicTrueAndDeletedAtIsNull()
                : repository.countByProjectIgnoreCaseAndViaPublicTrueAndDeletedAtIsNull(scope);
    }
```
Fix every other `findAll(` caller found by the grep (pass `null`).

- [ ] **Step 5: The controller's source words**

In `BugController`, replace `sourceFilter` with two helpers and use both:
```java
    // "guest", "public" or "team"; anything else is no filter, so a mistyped bookmark shows the whole board
    private static Boolean guestFilter(String source) {
        return switch (normalised(source)) {
            case "guest" -> Boolean.TRUE;
            case "team" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Boolean publicFilter(String source) {
        return switch (normalised(source)) {
            case "public" -> Boolean.TRUE;
            case "team" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String normalised(String source) {
        return source == null ? "" : source.trim().toLowerCase(java.util.Locale.ROOT);
    }
```
At line 155: `List<Bug> bugs = service.findAll(project, status, severity, environment, assignee, reporter, guestFilter(source), publicFilter(source), label, due, keyword, sort);`
After the `guestRaised` attribute: `model.addAttribute("publicRaised", service.publicRaisedIn(project));`

- [ ] **Step 6: Run the tests**

Run: `mvn test -Dtest='BugRepositorySourceTest,BugRepositoryLabelsTest'`
Expected: PASS. Then `mvn -q compile` must succeed with no other caller left on the old arity.

- [ ] **Step 7: The mark**

`fragments.html`, directly after `guestMark`:
```html
<!-- Raised through the project's public link, by somebody outside with no account. -->
<span th:fragment="externalMark(bug)" class="public-mark" th:if="${bug.viaPublic}"
      title="Raised from the public link">
    <svg class="i" aria-hidden="true"><use href="#i-link"/></svg>
    <span class="sr-only">Raised from the public link</span>
</span>
```
`style.css`, extend the existing rules so both marks share them:
```css
.guest-mark,
.public-mark {
    display: inline-flex;
    align-items: center;
    color: var(--muted);
}
.guest-mark svg.i,
.public-mark svg.i { width: 11px; height: 11px; }

.fmenu-opt.is-active .guest-mark,
.fmenu-opt.is-active .public-mark,
.chip .guest-mark,
.chip .public-mark { color: var(--accent); }
.fmenu-opt.is-active .guest-mark svg.i,
.fmenu-opt.is-active .public-mark svg.i,
.chip .guest-mark svg.i,
.chip .public-mark svg.i { fill: currentColor; }
```
Render it beside every `guestMark`:
- `list.html:747` (card) and `list.html:1044` (row): add `<span th:replace="~{fragments :: externalMark(${bug})}"></span>` on the next line.
- `detail.html:701`: same, and under it show the email for public reports:
```html
                                <a th:if="${bug.viaPublic and bug.reporterEmail != null}"
                                   class="muted" th:href="'mailto:' + ${bug.reporterEmail}"
                                   th:text="${bug.reporterEmail}">priya@example.com</a>
```

- [ ] **Step 8: The filter option and chip**

`list.html` "Came from" column, after the client option (line ~144):
```html
                            <a class="fmenu-opt" th:href="@{${'/bugs' + q.toggle('source', 'public')}}"
                               th:classappend="${source == 'public'} ? 'is-active' : ''">
                                <span class="public-mark" aria-hidden="true">
                                    <svg class="i"><use href="#i-link"/></svg>
                                </span>
                                <span>The public link</span>
                                <span class="opt-count num" th:if="${publicRaised > 0}"
                                      th:text="${publicRaised}">2</span>
                            </a>
```
The chip at line ~394 becomes:
```html
        <a class="chip" th:if="${source != null}"
           th:href="@{${'/bugs' + q.without('source')}}">
            <span class="guest-mark" aria-hidden="true" th:if="${source == 'guest'}">
                <svg class="i"><use href="#i-guest"/></svg>
            </span>
            <span class="public-mark" aria-hidden="true" th:if="${source == 'public'}">
                <svg class="i"><use href="#i-link"/></svg>
            </span>
            <span>from <b th:text="${source == 'guest'} ? 'a client' : (${source == 'public'} ? 'the public link' : 'the team')">a client</b></span>
            <span class="x" aria-hidden="true"><svg class="i"><use href="#i-x"/></svg></span>
        </a>
```

- [ ] **Step 9: Verify in the app**

`./run.sh restart`. Raise one bug through a public link. The card, the list row and the detail rail show the link mark with its tooltip; the detail rail shows the mailto. Filter "The public link" shows only it; "The team" hides both it and client reports. Both themes. `./run.sh stop`. Then run the whole suite: `./run.sh test`.

- [ ] **Step 10: Commit**

```bash
git add src/main src/test
git commit -m "feat: External mark, reporter email and public-link source filter"
```

---

## Self-review notes

- Spec coverage: data (T1), form object and service (T2), routes, pages, security allowlist, JSON hiding (T1 `@JsonIgnore`, T3), regenerate (T4), navbar copy (T5), badge, detail email, filter (T6). Tests match the spec's list except `SecurityConfigTest`, folded into T3's "not a login redirect" assertion.
- Type consistency: `search` and `findAll` place `viaPublic` immediately after `viaGuest` everywhere; `Filed` is reused from `GuestService`; `publicUrl(Project)` is used by T4 and T5.
