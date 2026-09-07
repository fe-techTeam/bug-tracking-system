# Tier 1 Jira Gaps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add labels, due dates, CSV export, saved filters and a per-user theme preference to the bug tracker, each with its Flyway migration and tests.

**Architecture:** Spring Boot 3.5 MVC, controller → service → repository. Bugs keep naming people and projects as text. New collections mirror the existing `assignees` element collection; new tables get an index and RLS in a numbered migration. Templates are Thymeleaf with one `style.css` and one `app.js`, no build step.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, Hibernate, Flyway (Postgres only), H2 (default, `ddl-auto=update`), Thymeleaf, JUnit 5 via `spring-boot-starter-test`.

**Spec:** `docs/superpowers/specs/2026-09-03-tier1-jira-gaps-design.md`

## Global Constraints

- Every stored enum is `VARCHAR`: `@Enumerated(EnumType.STRING)` plus `@JdbcTypeCode(SqlTypes.VARCHAR)`; migrations use plain `varchar`.
- Bugs name people and projects as text, never a foreign key. `saved_filters.created_by` is text.
- Status is a board column key, never an enum.
- `team_members` is the only users table.
- Every entity change ships a migration in `src/main/resources/db/migration/postgres/V<n>__*.sql`, idempotent (`if not exists`), with an index on any new non-leading key column and `enable row level security` on any new table. Migration numbers are fixed: V6 labels, V7 due date, V8 saved filters, V9 theme.
- Comments: one line, only for a non-obvious why. No Javadoc blocks on new code. Do not add comments to code you only touch incidentally.
- UI follows `.claude/skills/frontend/SKILL.md`: tokens not literals, both themes, reuse `.pill`, `.chip`, `.fmenu-*`, `.seg`, `.panel`. Every UI task must invoke `frontend-design:frontend-design` before writing template or CSS changes.
- `app.js` is ES5-style inside one IIFE; use the existing `store()`/`read()` helpers.
- Run tests with `./run.sh test` or `mvn test -Dtest=ClassName`. `run.sh` sets `JAVA_HOME` to a JDK 21.
- Work on branch `feat/tier1-jira-gaps`. Commit after each task with the trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- The authenticated principal's name is the team member's display name (see `SecurityConfig.account`), not the email.

## Task complexity

| Task | Complexity | Why |
|---|---|---|
| 1 Labels entity + test scaffolding | easy | code is given verbatim, single entity |
| 2 Label filter across layers | standard | JPQL, service overloads, two controllers |
| 3 Labels UI | standard | five template regions, both themes |
| 4 Due date rules, sort, filter | complex | date rules interact with board done-state |
| 5 Due date UI | standard | badges, filter column, order list |
| 6 CSV export | standard | new endpoint, writer, sprite icon |
| 7 Saved filters model + service | easy | code is given verbatim |
| 8 Saved filters controller + UI | complex | new controller, exception mapping, two pages |
| 9 Theme backend | standard | touches the users table and global model advice |
| 10 Theme front end | standard | pre-paint script, CSRF header, fetch |

Tasks run strictly in order: 1 through 6 edit the same files, and 8 and 10 both edit `layout.html`'s sprite.

---

## File map

| File | Responsibility |
|---|---|
| `src/main/java/com/bugtracking/model/Bug.java` | add `labels`, `dueDate`, `labelsText` accessors |
| `src/main/java/com/bugtracking/repository/BugRepository.java` | `search` gains `label`; new `distinctLabels` query |
| `src/main/java/com/bugtracking/service/BugService.java` | `findAll` gains `label` and `due`; `sorted` gains `due`; history for `labels` and `due`; `overdue`/`dueThisWeek` rules; `labelsIn` |
| `src/main/java/com/bugtracking/service/DueDates.java` (new) | pure date rules, testable without Spring |
| `src/main/java/com/bugtracking/service/CsvWriter.java` (new) | RFC 4180 writer |
| `src/main/java/com/bugtracking/service/BugCsv.java` (new) | maps bugs to CSV rows |
| `src/main/java/com/bugtracking/controller/BugController.java` | new params, model attributes |
| `src/main/java/com/bugtracking/controller/BugApiController.java` | new params, `/labels`, `.csv` endpoint |
| `src/main/java/com/bugtracking/model/SavedFilter.java` (new) | entity |
| `src/main/java/com/bugtracking/repository/SavedFilterRepository.java` (new) | |
| `src/main/java/com/bugtracking/service/SavedFilterService.java` (new) | create, list for project, owner-only delete |
| `src/main/java/com/bugtracking/controller/SavedFilterController.java` (new) | `POST /filters`, `POST /filters/{id}/delete` |
| `src/main/java/com/bugtracking/controller/SettingsController.java` | `filters` tab |
| `src/main/java/com/bugtracking/model/ThemePreference.java` (new) | enum |
| `src/main/java/com/bugtracking/model/TeamMember.java` | `theme` |
| `src/main/java/com/bugtracking/repository/TeamMemberRepository.java` | `findFirstByNameIgnoreCase` |
| `src/main/java/com/bugtracking/service/TeamMemberService.java` | `themeOf`, `setTheme` |
| `src/main/java/com/bugtracking/controller/MeController.java` (new) | `POST /me/theme` |
| `src/main/java/com/bugtracking/controller/GlobalModelAttributes.java` | `themePreference` |
| `src/main/resources/db/migration/postgres/V6..V9` (new) | |
| `src/main/resources/templates/bugs/list.html`, `form.html`, `detail.html`, `settings.html`, `layout.html` | UI |
| `src/main/resources/static/css/style.css`, `static/js/app.js` | UI |
| `src/test/java/com/bugtracking/**` (new) | tests |
| `src/test/resources/application.properties` (new) | in-memory H2 for tests |

---

### Task 1: Test scaffolding and labels on the entity

**Files:**
- Create: `src/test/resources/application.properties`
- Create: `src/test/java/com/bugtracking/model/BugLabelsTest.java`
- Modify: `src/main/java/com/bugtracking/model/Bug.java` (after the `assignees` field at line 132 and after `getAssigneesLabel()` at line 306)
- Create: `src/main/resources/db/migration/postgres/V6__bug_labels.sql`

**Interfaces:**
- Produces: `Bug.getLabels(): List<String>`, `Bug.setLabels(List<String>)`, `Bug.getLabelsText(): String`, `Bug.setLabelsText(String)`, `Bug.getLabelsLabel(): String` (comma-joined, null when empty).

- [ ] **Step 1: Create the test properties so tests never touch `data/bugtracking`**

`src/test/resources/application.properties`:

```properties
spring.datasource.url=jdbc:h2:mem:bugtracking-test;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=create-drop
spring.flyway.enabled=false
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.validator.apply_to_ddl=false
bugtracking.security.quick-fill=false
bugtracking.attachments.dir=target/test-attachments
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/bugtracking/model/BugLabelsTest.java`:

```java
package com.bugtracking.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BugLabelsTest {

    @Test
    void trimsBlanksAndDeduplicatesIgnoringCase() {
        Bug bug = new Bug();
        bug.setLabels(Arrays.asList(" login ", "", null, "Login", "payments"));
        assertEquals(List.of("login", "payments"), bug.getLabels());
    }

    @Test
    void capsEachLabelAtFortyCharacters() {
        Bug bug = new Bug();
        bug.setLabels(List.of("a".repeat(50)));
        assertEquals(40, bug.getLabels().get(0).length());
    }

    @Test
    void labelsTextRoundTripsThroughCommas() {
        Bug bug = new Bug();
        bug.setLabelsText("login, payments ,,  ui");
        assertEquals(List.of("login", "payments", "ui"), bug.getLabels());
        assertEquals("login, payments, ui", bug.getLabelsText());
        assertEquals("login, payments, ui", bug.getLabelsLabel());
    }

    @Test
    void emptyLabelsReadAsEmptyTextAndNullLabel() {
        Bug bug = new Bug();
        bug.setLabelsText("  ");
        assertTrue(bug.getLabels().isEmpty());
        assertEquals("", bug.getLabelsText());
        assertNull(bug.getLabelsLabel());
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `mvn test -Dtest=BugLabelsTest -q`
Expected: compilation error, `setLabels` not found.

- [ ] **Step 4: Add the field and accessors to `Bug`**

Insert directly after the `assignees` field (line 132):

```java
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "bug_labels", joinColumns = @JoinColumn(name = "bug_id"))
    @OrderColumn(name = "position")
    @Column(name = "label", length = 40)
    private List<String> labels = new ArrayList<>();
```

Insert directly after `getAssigneesLabel()`:

```java
    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> values) {
        List<String> clean = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                String label = value.trim();
                if (label.length() > 40) {
                    label = label.substring(0, 40).trim();
                }
                if (!label.isEmpty() && seen.add(label.toLowerCase(Locale.ROOT))) {
                    clean.add(label);
                }
            }
        }
        this.labels = clean;
    }

    @Transient
    @JsonIgnore
    public String getLabelsText() {
        return String.join(", ", labels);
    }

    public void setLabelsText(String text) {
        setLabels(text == null ? List.of() : Arrays.asList(text.split(",")));
    }

    @Transient
    @JsonIgnore
    public String getLabelsLabel() {
        return labels.isEmpty() ? null : String.join(", ", labels);
    }
```

Add `import java.util.Arrays;` and `import com.fasterxml.jackson.annotation.JsonIgnore;` if not already imported (check the top of the file; `JsonProperty` is already imported from the same package).

- [ ] **Step 5: Run the test and see it pass**

Run: `mvn test -Dtest=BugLabelsTest -q`
Expected: 4 tests pass.

- [ ] **Step 6: Write the migration**

`src/main/resources/db/migration/postgres/V6__bug_labels.sql`:

```sql
-- V6: free-text labels on a bug, shaped like bug_assignees.

create table if not exists bug_labels (
    bug_id   bigint  not null,
    position integer not null,
    label    varchar(40),
    primary key (bug_id, position),
    constraint fk_bug_labels_bug foreign key (bug_id) references bugs (id)
);

-- The primary key already leads with bug_id; filtering and autocomplete read the other way.
create index if not exists idx_bug_labels_label on bug_labels (lower(label));

alter table bug_labels enable row level security;
```

- [ ] **Step 7: Boot once against H2 to confirm Hibernate builds the table**

Run: `./run.sh build` (compiles and packages; skip if slow, `mvn -q compile` is enough).
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/test src/main/java/com/bugtracking/model/Bug.java src/main/resources/db/migration/postgres/V6__bug_labels.sql
git commit -m "feat: labels on bugs with V6 migration and test scaffolding

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Label filter, distinct labels, history

**Files:**
- Modify: `src/main/java/com/bugtracking/repository/BugRepository.java:52-79`
- Modify: `src/main/java/com/bugtracking/service/BugService.java` (`findAll` at 58-66, `update` history block at 196-208)
- Modify: `src/main/java/com/bugtracking/controller/BugController.java:89-98, 106-108, 133-135, 189-206`
- Modify: `src/main/java/com/bugtracking/controller/BugApiController.java:93-103`
- Create: `src/test/java/com/bugtracking/repository/BugRepositoryLabelsTest.java`

**Interfaces:**
- Consumes: `Bug.setLabels` from Task 1.
- Produces: `BugRepository.search(project, status, severity, environment, assignee, reporter, label, keyword, keywordId)`; `BugRepository.distinctLabels(project): List<String>`; `BugService.findAll(project, status, severity, environment, assignee, reporter, label, keyword, sort)`; `BugService.labelsIn(project): List<String>`; request param `label` on `GET /bugs` and `GET /api/bugs`; `GET /api/bugs/labels?project=`.
- The old eight-argument `BugService.findAll` overload stays and delegates with `label = null` so `Dashboard` and other callers compile.

- [ ] **Step 1: Write the failing repository test**

`src/test/java/com/bugtracking/repository/BugRepositoryLabelsTest.java`:

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
class BugRepositoryLabelsTest {

    @Autowired
    BugRepository bugs;

    private Bug bug(String title, String... labels) {
        Bug b = new Bug();
        b.setTitle(title);
        b.setProject("Acme");
        b.setSeverity(Severity.MEDIUM);
        b.setEnvironment(Environment.QA);
        b.setStatus("OPEN");
        b.setLabels(List.of(labels));
        return bugs.save(b);
    }

    @Test
    void filtersByLabelIgnoringCase() {
        bug("one", "Login");
        bug("two", "payments");
        List<Bug> found = bugs.search(null, null, null, null, null, null, "login", null, null);
        assertEquals(1, found.size());
        assertEquals("one", found.get(0).getTitle());
    }

    @Test
    void distinctLabelsAreSortedAndScopedToProject() {
        bug("one", "ui", "Login");
        bug("two", "payments");
        assertEquals(List.of("Login", "payments", "ui"), bugs.distinctLabels("acme"));
        assertEquals(List.of(), bugs.distinctLabels("Other"));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn test -Dtest=BugRepositoryLabelsTest -q`
Expected: compile error on `search` arity and `distinctLabels`.

- [ ] **Step 3: Extend the repository**

In `BugRepository.search`, add this clause after the `:assignee` block:

```
              AND (:label IS NULL OR EXISTS (
                       SELECT l FROM Bug lb JOIN lb.labels l
                       WHERE lb.id = b.id AND LOWER(l) = LOWER(CAST(:label AS string))))
```

Add the parameter after `reporter`:

```java
                     @Param("label") String label,
```

Add the new query method below `search`:

```java
    @Query("""
            SELECT DISTINCT l FROM Bug b JOIN b.labels l
            WHERE b.deletedAt IS NULL
              AND (:project IS NULL OR LOWER(b.project) = LOWER(CAST(:project AS string)))
            ORDER BY l
            """)
    List<String> distinctLabels(@Param("project") String project);
```

- [ ] **Step 4: Extend the service**

Replace the two `findAll` methods in `BugService` (lines 52-66) with:

```java
    @Transactional(readOnly = true)
    public List<Bug> findAll(String status, Severity severity, String keyword) {
        return findAll(null, status, severity, null, null, null, null, keyword, null);
    }

    @Transactional(readOnly = true)
    public List<Bug> findAll(String project, String status, Severity severity,
                             Environment environment, String assignee, String reporter,
                             String keyword, String sort) {
        return findAll(project, status, severity, environment, assignee, reporter, null, keyword, sort);
    }

    @Transactional(readOnly = true)
    public List<Bug> findAll(String project, String status, Severity severity,
                             Environment environment, String assignee, String reporter,
                             String label, String keyword, String sort) {
        String trimmed = blankToNull(keyword);
        List<Bug> found = repository.search(blankToNull(project), status, severity,
                environment, blankToNull(assignee), blankToNull(reporter), blankToNull(label),
                trimmed, idIn(trimmed));
        return sorted(found, sort);
    }

    @Transactional(readOnly = true)
    public List<String> labelsIn(String project) {
        return repository.distinctLabels(blankToNull(project));
    }
```

In `update`, after the `"assigned"` history line add:

```java
        history.recordIfChanged(id, "labels", existing.getLabelsLabel(), changes.getLabelsLabel(), actor);
```

and in the copy block after `existing.setAssignees(...)` add:

```java
        existing.setLabels(changes.getLabels());
```

Find every other `repository.search(` call in `src/main/java` (`grep -rn "repository.search(" src/main/java`) and add a `null` label argument in the new position.

- [ ] **Step 5: Extend both controllers**

`BugController.list`: add `@RequestParam(required = false) String label,` after `reporter`. Include `isBlank(label)` in `noFilters`. Pass `label` into `service.findAll(project, status, severity, environment, assignee, reporter, label, keyword, sort)`. Add model attributes:

```java
        model.addAttribute("label", label);
        model.addAttribute("labels", service.labelsIn(project));
```

and `.put("label", label)` to the `BoardQuery.builder()` chain.

`BugApiController.list`: add the same param and pass it through. Add:

```java
    @GetMapping("/labels")
    public List<String> labels(@RequestParam(required = false) String project) {
        return service.labelsIn(project);
    }
```

Also add `addFormOptions` support: in `BugController.addFormOptions(Model, Bug)` add `model.addAttribute("labels", service.labelsIn(bug.getProject()));` so the form's datalist has data (Task 3 uses it).

- [ ] **Step 6: Run tests**

Run: `mvn test -q`
Expected: all pass (BugLabelsTest and BugRepositoryLabelsTest).

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java src/test
git commit -m "feat: filter bugs by label and expose labels in use

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Labels UI

**Files:**
- Modify: `src/main/resources/templates/bugs/form.html` (inside the `More` details after the Module label, ~line 242)
- Modify: `src/main/resources/templates/bugs/list.html` (filter count 28-33, hidden inputs 35-43, filter columns before Status ~line 156, chips ~line 262, card foot 628-635, list table head 727-734 and row ~782)
- Modify: `src/main/resources/templates/bugs/detail.html` (rail facts after Module, ~line 550)
- Modify: `src/main/resources/static/css/style.css` (after the `.pill` block at line 1636)

**Interfaces:**
- Consumes: model attributes `label`, `labels` from Task 2; `Bug.labels`, `Bug.labelsText`.

Invoke `frontend-design:frontend-design` before editing.

- [ ] **Step 1: Form input**

After the Module `<label>` in `form.html` add:

```html
                    <label>
                        <span>Labels <span class="hint">— comma separated</span></span>
                        <input type="text" th:field="*{labelsText}" id="labelsText"
                               list="label-options" placeholder="e.g. login, regression"
                               autocomplete="off" autocapitalize="none" spellcheck="false">
                        <datalist id="label-options">
                            <option th:each="l : ${labels}" th:value="${l}"></option>
                        </datalist>
                    </label>
```

- [ ] **Step 2: Filter bar**

In `list.html`, extend the `activeFilters` sum with `+ (label != null and !label.isBlank() ? 1 : 0)`. Add a hidden input `<input type="hidden" name="label" th:if="${label != null}" th:value="${label}">`. Add a filter column before the Status column:

```html
                        <div class="fmenu-col" th:unless="${#lists.isEmpty(labels)}">
                            <p class="fmenu-head">Label</p>
                            <a class="fmenu-opt" th:href="@{${'/bugs' + q.without('label')}}"
                               th:classappend="${label == null} ? 'is-active' : ''">
                                <svg class="i tick" aria-hidden="true"><use href="#i-check"/></svg>
                                <span>Any label</span><span class="opt-count"></span>
                            </a>
                            <a class="fmenu-opt" th:each="l : ${labels}"
                               th:href="@{${'/bugs' + q.toggle('label', l)}}"
                               th:classappend="${#strings.equalsIgnoreCase(l, label)} ? 'is-active' : ''">
                                <span class="pill pill-label" th:text="${l}">login</span>
                                <span class="opt-count"></span>
                            </a>
                        </div>
```

Add a chip after the reporter chip:

```html
        <a class="chip" th:if="${label != null and !label.isBlank()}"
           th:href="@{${'/bugs' + q.without('label')}}">
            <span>label <b th:text="${label}">login</b></span>
            <span class="x" aria-hidden="true"><svg class="i"><use href="#i-x"/></svg></span>
        </a>
```

- [ ] **Step 3: Card, list row, detail**

In the board card foot, after the environment pill:

```html
                        <span class="pill pill-label" th:each="l : ${bug.labels}" th:text="${l}">login</span>
```

In the list view, add `<th>Labels</th>` after `Status` and a cell after the status cell:

```html
                    <td data-label="Labels">
                        <span class="pill pill-label" th:each="l : ${bug.labels}" th:text="${l}">login</span>
                        <span class="muted" th:if="${#lists.isEmpty(bug.labels)}">—</span>
                    </td>
```

In `detail.html` rail facts, after Module:

```html
                        <div class="rail-fact" th:unless="${#lists.isEmpty(bug.labels)}">
                            <dt>Labels</dt>
                            <dd class="label-chips">
                                <a class="pill pill-label" th:each="l : ${bug.labels}"
                                   th:href="@{/bugs(label=${l})}" th:text="${l}">login</a>
                            </dd>
                        </div>
```

- [ ] **Step 4: CSS**

After the `.pill` block:

```css
.pill-label { --c: var(--accent); font-weight: 520; }
a.pill-label { text-decoration: none; }
a.pill-label:hover { background: color-mix(in oklab, var(--c) 22%, transparent); }
.label-chips { display: flex; flex-wrap: wrap; gap: 4px; }
```

Confirm `--accent` exists in both `:root` blocks (`grep -n '\-\-accent:' style.css`); if the token has another name, use that one.

- [ ] **Step 5: Verify in the browser**

Run: `./run.sh restart` then open `http://localhost:8085/bugs`. Create a bug with labels `login, regression`; confirm chips on the card, in the list, on the detail page, the Label filter column, and the active chip. Check dark theme and a narrow window.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources
git commit -m "feat: label chips, form input and filter

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Due dates, rules, sort and filter

**Files:**
- Create: `src/main/java/com/bugtracking/service/DueDates.java`
- Create: `src/test/java/com/bugtracking/service/DueDatesTest.java`
- Modify: `src/main/java/com/bugtracking/model/Bug.java` (after `reportedBy` field, line 117; accessors near `getReportedBy`)
- Modify: `src/main/java/com/bugtracking/service/BugService.java` (`findAll` nine-arg, `update`, `sorted`)
- Modify: `src/main/java/com/bugtracking/controller/BugController.java`, `BugApiController.java`
- Create: `src/main/resources/db/migration/postgres/V7__bug_due_date.sql`

**Interfaces:**
- Produces: `Bug.getDueDate(): LocalDate`, `Bug.setDueDate(LocalDate)`; `DueDates.overdue(LocalDate due, boolean done, LocalDate today): boolean`; `DueDates.dueWithinWeek(LocalDate due, LocalDate today): boolean`; `BugService.findAll(project, status, severity, environment, assignee, reporter, label, due, keyword, sort)` (ten args; the nine-arg overload from Task 2 delegates with `due = null`); `BugService.overdue(Bug): boolean`; `BugService.overdueIds(List<Bug>): Set<Long>`; `sort=due`; request param `due` in `overdue|week`.

- [ ] **Step 1: Write the failing rule tests**

`src/test/java/com/bugtracking/service/DueDatesTest.java`:

```java
package com.bugtracking.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DueDatesTest {

    private final LocalDate today = LocalDate.of(2026, 9, 3);

    @Test
    void overdueWhenDueBeforeTodayAndNotDone() {
        assertTrue(DueDates.overdue(today.minusDays(1), false, today));
    }

    @Test
    void notOverdueOnTheDay() {
        assertFalse(DueDates.overdue(today, false, today));
    }

    @Test
    void neverOverdueWhenDoneOrUndated() {
        assertFalse(DueDates.overdue(today.minusDays(5), true, today));
        assertFalse(DueDates.overdue(null, false, today));
    }

    @Test
    void weekMeansTodayThroughSevenDaysOut() {
        assertTrue(DueDates.dueWithinWeek(today, today));
        assertTrue(DueDates.dueWithinWeek(today.plusDays(7), today));
        assertFalse(DueDates.dueWithinWeek(today.plusDays(8), today));
        assertFalse(DueDates.dueWithinWeek(today.minusDays(1), today));
        assertFalse(DueDates.dueWithinWeek(null, today));
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `mvn test -Dtest=DueDatesTest -q`
Expected: compile error, `DueDates` missing.

- [ ] **Step 3: Implement the rules**

`src/main/java/com/bugtracking/service/DueDates.java`:

```java
package com.bugtracking.service;

import java.time.LocalDate;

public final class DueDates {

    private DueDates() {
    }

    public static boolean overdue(LocalDate due, boolean done, LocalDate today) {
        return due != null && !done && due.isBefore(today);
    }

    public static boolean dueWithinWeek(LocalDate due, LocalDate today) {
        return due != null && !due.isBefore(today) && !due.isAfter(today.plusDays(7));
    }
}
```

- [ ] **Step 4: Run and see it pass**

Run: `mvn test -Dtest=DueDatesTest -q`
Expected: 4 pass.

- [ ] **Step 5: Add the field to `Bug`**

After the `reportedBy` field:

```java
    @Column(name = "due_date")
    private LocalDate dueDate;
```

Accessors after `setReportedBy`:

```java
    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }
```

Add `import java.time.LocalDate;` and, on the field, `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)` from `org.springframework.format.annotation.DateTimeFormat` so the HTML form's `yyyy-MM-dd` binds. Jackson handles ISO dates already through `spring-boot-starter-json`.

- [ ] **Step 6: Service: filter, sort, history, overdue**

In `BugService`, replace the nine-arg `findAll` from Task 2 with a delegate and a ten-arg version:

```java
    @Transactional(readOnly = true)
    public List<Bug> findAll(String project, String status, Severity severity,
                             Environment environment, String assignee, String reporter,
                             String label, String keyword, String sort) {
        return findAll(project, status, severity, environment, assignee, reporter, label, null, keyword, sort);
    }

    @Transactional(readOnly = true)
    public List<Bug> findAll(String project, String status, Severity severity,
                             Environment environment, String assignee, String reporter,
                             String label, String due, String keyword, String sort) {
        String trimmed = blankToNull(keyword);
        List<Bug> found = repository.search(blankToNull(project), status, severity,
                environment, blankToNull(assignee), blankToNull(reporter), blankToNull(label),
                trimmed, idIn(trimmed));
        return sorted(dueFiltered(found, blankToNull(due)), sort);
    }

    private List<Bug> dueFiltered(List<Bug> bugs, String due) {
        if (due == null) {
            return bugs;
        }
        LocalDate today = LocalDate.now();
        BoardColumns board = columns.snapshot();
        return switch (due) {
            case "overdue" -> bugs.stream()
                    .filter(b -> DueDates.overdue(b.getDueDate(), !board.openWork(b), today)).toList();
            case "week" -> bugs.stream()
                    .filter(b -> DueDates.dueWithinWeek(b.getDueDate(), today)).toList();
            default -> bugs;
        };
    }

    @Transactional(readOnly = true)
    public boolean overdue(Bug bug) {
        return DueDates.overdue(bug.getDueDate(), !columns.snapshot().openWork(bug), LocalDate.now());
    }

    @Transactional(readOnly = true)
    public Set<Long> overdueIds(List<Bug> bugs) {
        LocalDate today = LocalDate.now();
        BoardColumns board = columns.snapshot();
        Set<Long> out = new LinkedHashSet<>();
        for (Bug b : bugs) {
            if (DueDates.overdue(b.getDueDate(), !board.openWork(b), today)) {
                out.add(b.getId());
            }
        }
        return out;
    }
```

Check `BoardColumns.openWork(Bug)` (line 119) returns true when the bug's column is not a done column; if its semantics differ, use `!column(bug).isDoneState()` instead.

In `sorted`, add a case:

```java
            case "due" -> out.sort(Comparator.comparing(Bug::getDueDate,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Bug::getCreatedAt, Comparator.reverseOrder()));
```

In `update`, after the `"labels"` history line:

```java
        history.recordIfChanged(id, "due", existing.getDueDate(), changes.getDueDate(), actor);
```

and in the copy block: `existing.setDueDate(changes.getDueDate());`

Add `import java.time.LocalDate;`.

- [ ] **Step 7: Controllers**

`BugController.list`: add `@RequestParam(required = false) String due,` after `label`; include `isBlank(due)` in `noFilters`; call the ten-arg `findAll`; add `model.addAttribute("due", due);`, `model.addAttribute("overdueIds", service.overdueIds(bugs));`, and `.put("due", due)` in the builder. Allow `sort=due` (the template's Order list is edited in Task 5; the service already accepts it).

`BugController.detail`: add `model.addAttribute("overdue", service.overdue(bug));` where the bug is put on the model.

`BugApiController.list`: add `due` param and call the ten-arg `findAll`.

- [ ] **Step 8: Migration**

`src/main/resources/db/migration/postgres/V7__bug_due_date.sql`:

```sql
-- V7: an optional due date on a bug.

alter table bugs
    add column if not exists due_date date;

create index if not exists idx_bugs_due_date on bugs (due_date);
```

- [ ] **Step 9: Run all tests**

Run: `mvn test -q`
Expected: pass.

- [ ] **Step 10: Commit**

```bash
git add -A src/main/java src/test src/main/resources/db
git commit -m "feat: due dates with overdue and due-this-week rules, V7 migration

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Due date UI

**Files:**
- Modify: `src/main/resources/templates/bugs/form.html` (after the Labels label from Task 3)
- Modify: `src/main/resources/templates/bugs/list.html` (filter count, hidden inputs, new "Due" filter column, Order options list at ~line 173, chips, card foot, list head/row)
- Modify: `src/main/resources/templates/bugs/detail.html` (rail facts after Labels)
- Modify: `src/main/resources/static/css/style.css`

**Interfaces:**
- Consumes: `due`, `overdueIds`, `overdue` model attributes; `Bug.dueDate`; `sort=due`.

Invoke `frontend-design:frontend-design` before editing.

- [ ] **Step 1: Form**

```html
                    <label>
                        <span>Due <span class="hint">— optional</span></span>
                        <input type="date" th:field="*{dueDate}" id="dueDate">
                        <span class="field-error" th:if="${#fields.hasErrors('dueDate')}"
                              th:errors="*{dueDate}">Date error</span>
                    </label>
```

- [ ] **Step 2: Filter column, order option, chip**

`activeFilters`: add `+ (due != null and !due.isBlank() ? 1 : 0)`. Hidden input: `<input type="hidden" name="due" th:if="${due != null}" th:value="${due}">`.

Filter column after Label:

```html
                        <div class="fmenu-col">
                            <p class="fmenu-head">Due</p>
                            <a class="fmenu-opt" th:href="@{${'/bugs' + q.without('due')}}"
                               th:classappend="${due == null} ? 'is-active' : ''">
                                <svg class="i tick" aria-hidden="true"><use href="#i-check"/></svg>
                                <span>Any time</span><span class="opt-count"></span>
                            </a>
                            <a class="fmenu-opt" th:href="@{${'/bugs' + q.toggle('due', 'overdue')}}"
                               th:classappend="${due == 'overdue'} ? 'is-active' : ''">
                                <span class="dot dot-lg is-overdue"></span>
                                <span>Overdue</span>
                                <span class="opt-count num" th:if="${!#lists.isEmpty(overdueIds)}"
                                      th:text="${#lists.size(overdueIds)}">2</span>
                            </a>
                            <a class="fmenu-opt" th:href="@{${'/bugs' + q.toggle('due', 'week')}}"
                               th:classappend="${due == 'week'} ? 'is-active' : ''">
                                <span class="dot dot-lg"></span>
                                <span>Due this week</span><span class="opt-count"></span>
                            </a>
                        </div>
```

Order list: change the literal list to `{'newest','oldest','updated','severity','due','title'}` and add `<span th:case="'due'">Due date, soonest first</span>`.

Chip after the label chip:

```html
        <a class="chip" th:if="${due != null and !due.isBlank()}"
           th:classappend="${due == 'overdue'} ? 'is-overdue' : ''"
           th:href="@{${'/bugs' + q.without('due')}}">
            <span class="dot"></span>
            <b th:text="${due == 'overdue'} ? 'Overdue' : 'Due this week'">Overdue</b>
            <span class="x" aria-hidden="true"><svg class="i"><use href="#i-x"/></svg></span>
        </a>
```

- [ ] **Step 3: Card, list, detail**

Card foot, before the `.spacer`:

```html
                        <span class="pill pill-due" th:if="${bug.dueDate != null}"
                              th:classappend="${overdueIds.contains(bug.id)} ? 'is-overdue' : ''"
                              th:title="'Due ' + ${#temporals.format(bug.dueDate, 'dd MMM yyyy')}"
                              th:text="${#temporals.format(bug.dueDate, 'dd MMM')}">12 Sep</span>
```

List: `<th>Due</th>` after Labels, and:

```html
                    <td data-label="Due">
                        <span class="pill pill-due" th:if="${bug.dueDate != null}"
                              th:classappend="${overdueIds.contains(bug.id)} ? 'is-overdue' : ''"
                              th:text="${#temporals.format(bug.dueDate, 'dd MMM yyyy')}">12 Sep 2026</span>
                        <span class="muted" th:if="${bug.dueDate == null}">—</span>
                    </td>
```

Detail rail fact:

```html
                        <div class="rail-fact" th:if="${bug.dueDate != null}">
                            <dt>Due</dt>
                            <dd th:classappend="${overdue} ? 'is-overdue' : ''" id="bug-due">
                                <span th:text="${#temporals.format(bug.dueDate, 'dd MMM yyyy')}">12 Sep 2026</span>
                                <span class="muted" th:if="${overdue}">overdue</span>
                            </dd>
                        </div>
```

- [ ] **Step 4: CSS**

```css
.pill-due { --c: var(--text-2); font-variant-numeric: tabular-nums; }
.pill-due.is-overdue, .chip.is-overdue, .dot.is-overdue, .rail-fact dd.is-overdue { --c: var(--sev-CRITICAL); }
.rail-fact dd.is-overdue { color: var(--c); }
```

Confirm `--text-2` exists in both `:root` blocks; if not, use `--muted`.

- [ ] **Step 5: Verify in the browser**

`./run.sh restart`; set a due date in the past on one bug and next week on another; check the badge colours, the Due filter, `sort=due` in the list Order menu, dark theme, narrow width.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources
git commit -m "feat: due date badge, filter and sort in the UI

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: CSV export

**Files:**
- Create: `src/main/java/com/bugtracking/service/CsvWriter.java`
- Create: `src/main/java/com/bugtracking/service/BugCsv.java`
- Create: `src/test/java/com/bugtracking/service/CsvWriterTest.java`
- Modify: `src/main/java/com/bugtracking/controller/BugApiController.java`
- Modify: `src/main/resources/templates/bugs/list.html` (topbar actions, next to the view `.seg`)

**Interfaces:**
- Consumes: ten-arg `BugService.findAll`, `BoardColumns.label(Bug)`, `BoardColumnService.snapshot()`.
- Produces: `CsvWriter.row(List<String>): String` (one CRLF-terminated line), `CsvWriter.BOM`; `BugCsv.render(List<Bug>, BoardColumns): String`; `GET /api/bugs.csv`.

- [ ] **Step 1: Failing test**

`src/test/java/com/bugtracking/service/CsvWriterTest.java`:

```java
package com.bugtracking.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvWriterTest {

    @Test
    void plainFieldsAreJoinedWithCommasAndCrlf() {
        assertEquals("1,login,HIGH\r\n", CsvWriter.row(List.of("1", "login", "HIGH")));
    }

    @Test
    void quotesFieldsWithCommasQuotesOrNewlines() {
        assertEquals("\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n",
                CsvWriter.row(List.of("a,b", "say \"hi\"", "two\nlines")));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals(",x\r\n", CsvWriter.row(Arrays.asList(null, "x")));
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `mvn test -Dtest=CsvWriterTest -q`

- [ ] **Step 3: Implement the writer**

`CsvWriter.java`:

```java
package com.bugtracking.service;

import java.util.List;

public final class CsvWriter {

    // Excel only detects UTF-8 when the file opens with a byte-order mark.
    public static final String BOM = "﻿";

    private CsvWriter() {
    }

    public static String row(List<String> fields) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escape(fields.get(i)));
        }
        return out.append("\r\n").toString();
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean risky = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return risky ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
```

- [ ] **Step 4: Run, expect pass**

Run: `mvn test -Dtest=CsvWriterTest -q`

- [ ] **Step 5: Map bugs to rows**

`BugCsv.java`:

```java
package com.bugtracking.service;

import com.bugtracking.model.Bug;

import java.util.ArrayList;
import java.util.List;

public final class BugCsv {

    private static final List<String> HEADER = List.of("id", "title", "severity", "status",
            "environment", "project", "module", "reported_by", "assignees", "labels",
            "due_date", "created_at", "updated_at");

    private BugCsv() {
    }

    public static String render(List<Bug> bugs, BoardColumns board) {
        StringBuilder out = new StringBuilder(CsvWriter.BOM);
        out.append(CsvWriter.row(HEADER));
        for (Bug b : bugs) {
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(b.getId()));
            row.add(b.getTitle());
            row.add(b.getSeverity() == null ? null : b.getSeverity().name());
            row.add(board.label(b));
            row.add(b.getEnvironment() == null ? null : b.getEnvironment().name());
            row.add(b.getProject());
            row.add(b.getModule());
            row.add(b.getReportedBy());
            row.add(String.join(";", b.getAssignees()));
            row.add(String.join(";", b.getLabels()));
            row.add(b.getDueDate() == null ? null : b.getDueDate().toString());
            row.add(b.getCreatedAt() == null ? null : b.getCreatedAt().toString());
            row.add(b.getUpdatedAt() == null ? null : b.getUpdatedAt().toString());
            out.append(CsvWriter.row(row));
        }
        return out.toString();
    }
}
```

Add a test to `CsvWriterTest` or a new `BugCsvTest` that renders one bug with a comma in the title and asserts the header line and the quoted title. Example:

```java
    @Test
    void rendersHeaderAndQuotedTitle() {
        Bug b = new Bug();
        b.setId(7L);
        b.setTitle("Login, then crash");
        b.setProject("Acme");
        b.setStatus("OPEN");
        b.setSeverity(Severity.HIGH);
        b.setLabels(List.of("ui", "login"));
        String csv = BugCsv.render(List.of(b), new BoardColumns(List.of()));
        String[] lines = csv.substring(1).split("\r\n");
        assertEquals("id,title,severity,status,environment,project,module,reported_by,assignees,labels,due_date,created_at,updated_at", lines[0]);
        assertTrue(lines[1].startsWith("7,\"Login, then crash\",HIGH,"));
        assertTrue(lines[1].contains(",ui;login,"));
    }
```

`Bug.setId` exists (check); if not, leave the id assertion out and assert on the title only. `BoardColumns.label(Bug)` with no columns should return the status key or null; either is fine for the test as long as the row still renders.

- [ ] **Step 6: Endpoint**

Spring 6 does not match a `.csv` suffix on the collection path, so the export lives at `/api/bugs/export.csv`. Add this method to `BugApiController`:

```java
    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> exportCsv(@RequestParam(required = false) String project,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Severity severity,
                                            @RequestParam(required = false) Environment environment,
                                            @RequestParam(required = false) String assignee,
                                            @RequestParam(required = false) String reporter,
                                            @RequestParam(required = false) String label,
                                            @RequestParam(required = false) String due,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String sort) {
        List<Bug> bugs = service.findAll(project, status, severity, environment,
                assignee, reporter, label, due, keyword, sort);
        String scope = project == null || project.isBlank() ? "all" : project.replaceAll("[^A-Za-z0-9-]+", "-");
        String name = "bugs-" + scope + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(BugCsv.render(bugs, columns.snapshot()));
    }
```

So the URL is `GET /api/bugs/export.csv`. Update the spec's URL wording accordingly in the plan's commit message; the behaviour is the same. Inject `BoardColumnService columns` into `BugApiController` if it is not already a field (check the constructor). Imports: `java.time.LocalDate`, `java.time.format.DateTimeFormatter`, `org.springframework.http.*`.

- [ ] **Step 7: UI link**

In `list.html` `.topbar-actions`, before the "New bug" button, visible on the list view only:

```html
            <a class="btn btn-ghost btn-sm" id="export-csv" th:if="${view == 'list'}"
               th:href="@{${'/api/bugs/export.csv' + q.without('view')}}" download>
                <svg class="i" aria-hidden="true"><use href="#i-download"/></svg>
                <span>Export CSV</span>
            </a>
```

If `#i-download` does not exist in the `layout.html` sprite, add an outline symbol:

```html
    <symbol id="i-download" viewBox="0 0 24 24">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
    </symbol>
```

- [ ] **Step 8: Test and verify**

Run: `mvn test -q`. Then `./run.sh restart` and `curl -s 'http://localhost:8085/api/bugs/export.csv?project=Acme' | head -3`. Expected: BOM, header, one row per bug.

- [ ] **Step 9: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: CSV export of the filtered bug list

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Saved filters model and service

**Files:**
- Create: `src/main/java/com/bugtracking/model/SavedFilter.java`
- Create: `src/main/java/com/bugtracking/repository/SavedFilterRepository.java`
- Create: `src/main/java/com/bugtracking/service/SavedFilterService.java`
- Create: `src/test/java/com/bugtracking/service/SavedFilterServiceTest.java`
- Create: `src/main/resources/db/migration/postgres/V8__saved_filters.sql`

**Interfaces:**
- Produces: `SavedFilter` (`id, name, project, query, createdBy, createdAt`); `SavedFilterService.save(String name, String project, String query, String createdBy): SavedFilter`; `forProject(String project): List<SavedFilter>` (project's plus project-less, name order); `all(): List<SavedFilter>`; `delete(Long id, String actor)` throws `SavedFilterService.NotOwnerException` (extends `RuntimeException`) when `actor` is not the creator; `SavedFilterService.EmptyQueryException` when the query has no filter params.

- [ ] **Step 1: Failing test**

```java
package com.bugtracking.service;

import com.bugtracking.model.SavedFilter;
import com.bugtracking.repository.SavedFilterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import(SavedFilterService.class)
class SavedFilterServiceTest {

    @Autowired
    SavedFilterService filters;
    @Autowired
    SavedFilterRepository repository;

    @Test
    void stripsProjectAndViewFromTheStoredQuery() {
        SavedFilter f = filters.save("Mine", "Acme", "?project=Acme&view=list&assignee=Ana&severity=HIGH", "Ana");
        assertEquals("assignee=Ana&severity=HIGH", f.getQuery());
    }

    @Test
    void rejectsAQueryWithNothingToSave() {
        assertThrows(SavedFilterService.EmptyQueryException.class,
                () -> filters.save("Nothing", "Acme", "?project=Acme&view=list", "Ana"));
    }

    @Test
    void listsProjectAndGlobalFiltersByName() {
        filters.save("Zed", "Acme", "severity=LOW", "Ana");
        filters.save("Any", null, "severity=HIGH", "Ana");
        filters.save("Other", "Beta", "severity=HIGH", "Ana");
        List<String> names = filters.forProject("acme").stream().map(SavedFilter::getName).toList();
        assertEquals(List.of("Any", "Zed"), names);
    }

    @Test
    void onlyTheCreatorCanDelete() {
        SavedFilter f = filters.save("Mine", "Acme", "severity=HIGH", "Ana");
        assertThrows(SavedFilterService.NotOwnerException.class, () -> filters.delete(f.getId(), "Bo"));
        filters.delete(f.getId(), "ana");
        assertTrue(repository.findById(f.getId()).isEmpty());
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `mvn test -Dtest=SavedFilterServiceTest -q`

- [ ] **Step 3: Entity**

```java
package com.bugtracking.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "saved_filters")
public class SavedFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 80)
    private String project;

    @Column(nullable = false, length = 1000)
    private String query;

    @Column(name = "created_by", nullable = false, length = 80)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected SavedFilter() {
    }

    public SavedFilter(String name, String project, String query, String createdBy) {
        this.name = name;
        this.project = project;
        this.query = query;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getProject() { return project; }
    public String getQuery() { return query; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public boolean ownedBy(String actor) {
        return actor != null && createdBy.equalsIgnoreCase(actor.trim());
    }
}
```

Check whether other entities use `GenerationType.IDENTITY`; mirror whatever `TeamMember` uses.

- [ ] **Step 4: Repository**

```java
package com.bugtracking.repository;

import com.bugtracking.model.SavedFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SavedFilterRepository extends JpaRepository<SavedFilter, Long> {

    @Query("""
            SELECT f FROM SavedFilter f
            WHERE f.project IS NULL
               OR (:project IS NOT NULL AND LOWER(f.project) = LOWER(CAST(:project AS string)))
            ORDER BY LOWER(f.name)
            """)
    List<SavedFilter> forProject(@Param("project") String project);

    List<SavedFilter> findAllByOrderByProjectAscNameAsc();
}
```

- [ ] **Step 5: Service**

```java
package com.bugtracking.service;

import com.bugtracking.model.SavedFilter;
import com.bugtracking.repository.SavedFilterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
@Transactional
public class SavedFilterService {

    private static final Set<String> DROPPED = Set.of("project", "view");

    private final SavedFilterRepository repository;

    public SavedFilterService(SavedFilterRepository repository) {
        this.repository = repository;
    }

    public SavedFilter save(String name, String project, String rawQuery, String createdBy) {
        String query = filterOnly(rawQuery);
        if (query.isEmpty()) {
            throw new EmptyQueryException();
        }
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            cleanName = "Untitled";
        }
        String scope = project == null || project.isBlank() ? null : project.trim();
        return repository.save(new SavedFilter(cleanName, scope, query, createdBy.trim()));
    }

    @Transactional(readOnly = true)
    public List<SavedFilter> forProject(String project) {
        return repository.forProject(project == null || project.isBlank() ? null : project.trim());
    }

    @Transactional(readOnly = true)
    public List<SavedFilter> all() {
        return repository.findAllByOrderByProjectAscNameAsc();
    }

    public void delete(Long id, String actor) {
        SavedFilter filter = repository.findById(id).orElseThrow(NoSuchElementException::new);
        if (!filter.ownedBy(actor)) {
            throw new NotOwnerException();
        }
        repository.delete(filter);
    }

    static String filterOnly(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }
        String q = rawQuery.startsWith("?") ? rawQuery.substring(1) : rawQuery;
        List<String> kept = new ArrayList<>();
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!DROPPED.contains(key)) {
                kept.add(pair);
            }
        }
        return String.join("&", kept);
    }

    public static class EmptyQueryException extends RuntimeException {
    }

    public static class NotOwnerException extends RuntimeException {
    }
}
```

- [ ] **Step 6: Migration**

`V8__saved_filters.sql`:

```sql
-- V8: named filter sets, shared with everyone, deletable by whoever made them.

create table if not exists saved_filters (
    id         bigint generated by default as identity primary key,
    name       varchar(60)   not null,
    project    varchar(80),
    query      varchar(1000) not null,
    created_by varchar(80)   not null,
    created_at timestamp(6)  not null
);

create index if not exists idx_saved_filters_project on saved_filters (project);

alter table saved_filters enable row level security;
```

- [ ] **Step 7: Run, expect pass**

Run: `mvn test -Dtest=SavedFilterServiceTest -q`

- [ ] **Step 8: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: saved filters entity, service and V8 migration

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Saved filters controller and UI

**Files:**
- Create: `src/main/java/com/bugtracking/controller/SavedFilterController.java`
- Modify: `src/main/java/com/bugtracking/controller/BugController.java` (list: model attribute `savedFilters`)
- Modify: `src/main/java/com/bugtracking/controller/SettingsController.java:23, 44-51`
- Modify: `src/main/java/com/bugtracking/controller/GlobalExceptionHandler.java` (map `NotOwnerException` to 403) — read it first and match its style
- Modify: `src/main/resources/templates/bugs/list.html` (bar: a `<details class="fmenu">` next to the Filters menu; chips row)
- Modify: `src/main/resources/templates/settings.html` (nav `.seg`, crumbs, new tab block)
- Modify: `src/main/resources/static/css/style.css` if a new class is needed

**Interfaces:**
- Consumes: `SavedFilterService` from Task 7; `BoardQuery.query()`; principal name.
- Produces: `POST /filters` (params `name`, `project`, `query`, `allProjects` optional), `POST /filters/{id}/delete`; model attribute `savedFilters: List<SavedFilter>` on the bugs page and `filters: List<SavedFilter>` on `settings?tab=filters`.

Invoke `frontend-design:frontend-design` before editing templates.

- [ ] **Step 1: Controller**

```java
package com.bugtracking.controller;

import com.bugtracking.model.SavedFilter;
import com.bugtracking.service.SavedFilterService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
@RequestMapping("/filters")
public class SavedFilterController {

    private final SavedFilterService filters;

    public SavedFilterController(SavedFilterService filters) {
        this.filters = filters;
    }

    @PostMapping
    public String save(@RequestParam String name,
                       @RequestParam(required = false) String project,
                       @RequestParam String query,
                       @RequestParam(required = false) boolean allProjects,
                       Principal principal,
                       RedirectAttributes flash) {
        try {
            SavedFilter saved = filters.save(name, allProjects ? null : project, query, principal.getName());
            flash.addFlashAttribute("message", "Saved filter \"" + saved.getName() + "\".");
        } catch (SavedFilterService.EmptyQueryException e) {
            flash.addFlashAttribute("message", "Turn on a filter first, then save it.");
        }
        return "redirect:/bugs" + (query.startsWith("?") ? query : "?" + query);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false, defaultValue = "/settings?tab=filters") String back,
                         Principal principal,
                         RedirectAttributes flash) {
        filters.delete(id, principal.getName());
        flash.addFlashAttribute("message", "Saved filter removed.");
        return "redirect:" + (back.startsWith("/") ? back : "/settings?tab=filters");
    }
}
```

In `GlobalExceptionHandler`, add a handler returning 403 for `SavedFilterService.NotOwnerException` in the same shape as the existing handlers (read the file first).

- [ ] **Step 2: Wire the bugs page and settings**

`BugController.list`: inject `SavedFilterService savedFilters` in the constructor and add `model.addAttribute("savedFilters", savedFilters.forProject(project));`.

`SettingsController`: `TABS = Set.of("projects", "team", "board", "filters")`; inject `SavedFilterService`; add `model.addAttribute("filters", filters.all());`.

- [ ] **Step 3: Bugs page UI**

Inside `.bar-filters`, after the `#filter-all` details, add:

```html
            <details class="fmenu" id="save-filter" th:if="${filtered}">
                <summary>
                    <span class="fmenu-btn">
                        <svg class="i" aria-hidden="true"><use href="#i-bookmark"/></svg>
                        <span>Save</span>
                    </span>
                </summary>
                <form class="fmenu-pop save-filter-pop" method="post" th:action="@{/filters}">
                    <input type="hidden" name="query" th:value="${q.query()}">
                    <input type="hidden" name="project" th:value="${selectedProject}">
                    <label>
                        <span>Name this view</span>
                        <input type="text" name="name" maxlength="60" required placeholder="e.g. My open highs">
                    </label>
                    <label class="check"><input type="checkbox" name="allProjects" value="true"> <span>All projects</span></label>
                    <button type="submit" class="btn btn-sm btn-primary">Save filter</button>
                </form>
            </details>
```

Add `#i-bookmark` to the sprite if missing:

```html
    <symbol id="i-bookmark" viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></symbol>
```

Saved-filter chips, rendered above the active-filter chips row (a sibling `.chips` block that shows even when nothing is filtered):

```html
    <div class="chips chips-saved" th:unless="${#lists.isEmpty(savedFilters)}" id="saved-filters">
        <span class="chips-label">Saved</span>
        <a class="chip" th:each="f : ${savedFilters}"
           th:href="@{'/bugs?' + (selectedProject != null ? 'project=' + #uris.escapeQueryParam(selectedProject) + '&' : '') + (view != 'board' ? 'view=' + view + '&' : '') + f.query}"
           th:title="${f.project} ?: 'All projects'">
            <svg class="i" aria-hidden="true" style="width:11px;height:11px"><use href="#i-bookmark"/></svg>
            <b th:text="${f.name}">My open highs</b>
        </a>
    </div>
```

CSS: `.save-filter-pop { display: grid; gap: 8px; min-width: 240px; padding: 10px; }` and `.chips-saved { border-bottom: 1px solid var(--line); }`.

- [ ] **Step 4: Settings tab**

Nav: add after the Board link:

```html
                <a th:href="@{/settings(tab='filters')}" th:classappend="${tab == 'filters'} ? 'is-active' : ''"
                   id="tab-filters">
                    <svg class="i" aria-hidden="true"><use href="#i-bookmark"/></svg>
                    <span>Filters</span>
                </a>
```

Crumbs: extend the ternary with `tab == 'filters' ? 'Saved filters'`.

Tab block after the team block:

```html
        <div th:if="${tab == 'filters'}" class="stack">
            <div class="panel">
                <div class="panel-head"><h2>Saved filters</h2></div>
                <div class="panel-pad" th:if="${#lists.isEmpty(filters)}">
                    <p class="muted">None yet. Turn on a filter on the board and press Save.</p>
                </div>
                <div class="table-wrap" th:unless="${#lists.isEmpty(filters)}">
                    <table>
                        <thead><tr><th>Name</th><th>Scope</th><th>Filters</th><th>Made by</th><th></th></tr></thead>
                        <tbody>
                        <tr th:each="f : ${filters}">
                            <td><a th:href="@{'/bugs?' + (f.project != null ? 'project=' + #uris.escapeQueryParam(f.project) + '&' : '') + f.query}" th:text="${f.name}">Name</a></td>
                            <td th:text="${f.project} ?: 'All projects'">Acme</td>
                            <td class="mono muted" th:text="${f.query}">severity=HIGH</td>
                            <td th:text="${f.createdBy}">Ana</td>
                            <td>
                                <form method="post" th:action="@{/filters/{id}/delete(id=${f.id})}" th:if="${f.ownedBy(me)}">
                                    <button type="submit" class="btn btn-sm btn-danger">Delete</button>
                                </form>
                            </td>
                        </tr>
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
```

- [ ] **Step 5: Verify**

`./run.sh restart`. Filter the board, save it under a name, confirm the chip appears, applies, and is listed under Settings › Filters; confirm a second account cannot delete it (403 page). Check dark theme and narrow width.

- [ ] **Step 6: Commit**

```bash
git add -A src/main
git commit -m "feat: save, apply and manage shared filters

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: Theme preference, backend

**Files:**
- Create: `src/main/java/com/bugtracking/model/ThemePreference.java`
- Modify: `src/main/java/com/bugtracking/model/TeamMember.java` (field after `active`, accessors)
- Modify: `src/main/java/com/bugtracking/repository/TeamMemberRepository.java`
- Modify: `src/main/java/com/bugtracking/service/TeamMemberService.java`
- Create: `src/main/java/com/bugtracking/controller/MeController.java`
- Modify: `src/main/java/com/bugtracking/controller/GlobalModelAttributes.java`
- Create: `src/test/java/com/bugtracking/service/TeamMemberThemeTest.java`
- Create: `src/main/resources/db/migration/postgres/V9__team_member_theme.sql`

**Interfaces:**
- Produces: `ThemePreference { SYSTEM, LIGHT, DARK }` with `String attr()` returning `null` for SYSTEM, `"light"`, `"dark"`; `TeamMember.getTheme()/setTheme()`; `TeamMemberRepository.findFirstByNameIgnoreCase(String)`; `TeamMemberService.themeOf(String name): ThemePreference` (SYSTEM when unknown); `TeamMemberService.setTheme(String name, ThemePreference)`; `POST /me/theme` with form field `theme` in `LIGHT|DARK|SYSTEM`, 204; model attribute `themeAttr: String` (null, `light` or `dark`).

- [ ] **Step 1: Failing test**

```java
package com.bugtracking.service;

import com.bugtracking.model.TeamMember;
import com.bugtracking.model.ThemePreference;
import com.bugtracking.repository.TeamMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
class TeamMemberThemeTest {

    @Autowired
    TeamMemberRepository members;

    @Test
    void defaultsToSystem() {
        TeamMember m = members.save(new TeamMember("Ana", "ana@example.com"));
        assertEquals(ThemePreference.SYSTEM, members.findById(m.getId()).orElseThrow().getTheme());
        assertNull(ThemePreference.SYSTEM.attr());
        assertEquals("dark", ThemePreference.DARK.attr());
    }

    @Test
    void findsByNameIgnoringCase() {
        members.save(new TeamMember("Ana Lopes", "ana@example.com"));
        assertEquals("Ana Lopes", members.findFirstByNameIgnoreCase("ana lopes").orElseThrow().getName());
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `mvn test -Dtest=TeamMemberThemeTest -q`

- [ ] **Step 3: Enum, entity, repository**

`ThemePreference.java`:

```java
package com.bugtracking.model;

public enum ThemePreference {
    SYSTEM(null), LIGHT("light"), DARK("dark");

    private final String attr;

    ThemePreference(String attr) {
        this.attr = attr;
    }

    public String attr() {
        return attr;
    }
}
```

`TeamMember`: after the `active` field:

```java
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private ThemePreference theme = ThemePreference.SYSTEM;
```

with imports `jakarta.persistence.Enumerated`, `jakarta.persistence.EnumType`, `org.hibernate.annotations.JdbcTypeCode`, `org.hibernate.type.SqlTypes` (copy the exact pattern from `Bug.severity`). Accessors:

```java
    public ThemePreference getTheme() {
        return theme == null ? ThemePreference.SYSTEM : theme;
    }

    public void setTheme(ThemePreference theme) {
        this.theme = theme == null ? ThemePreference.SYSTEM : theme;
    }
```

`TeamMemberRepository`: `Optional<TeamMember> findFirstByNameIgnoreCase(String name);`

- [ ] **Step 4: Run, expect pass**

Run: `mvn test -Dtest=TeamMemberThemeTest -q`

- [ ] **Step 5: Service, controller, model attribute**

`TeamMemberService`:

```java
    @Transactional(readOnly = true)
    public ThemePreference themeOf(String name) {
        if (name == null || name.isBlank()) {
            return ThemePreference.SYSTEM;
        }
        return repository.findFirstByNameIgnoreCase(name.trim())
                .map(TeamMember::getTheme)
                .orElse(ThemePreference.SYSTEM);
    }

    public void setTheme(String name, ThemePreference theme) {
        repository.findFirstByNameIgnoreCase(name.trim()).ifPresent(member -> {
            member.setTheme(theme);
            repository.save(member);
        });
    }
```

`MeController.java`:

```java
package com.bugtracking.controller;

import com.bugtracking.model.ThemePreference;
import com.bugtracking.service.TeamMemberService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

@Controller
@RequestMapping("/me")
public class MeController {

    private final TeamMemberService team;

    public MeController(TeamMemberService team) {
        this.team = team;
    }

    @PostMapping("/theme")
    public ResponseEntity<Void> theme(@RequestParam ThemePreference theme, Principal principal) {
        team.setTheme(principal.getName(), theme);
        return ResponseEntity.noContent().build();
    }
}
```

`GlobalModelAttributes`: inject `TeamMemberService team` and add:

```java
    @ModelAttribute("themeAttr")
    public String themeAttr(HttpServletRequest request) {
        if (recovering(request) || request.getUserPrincipal() == null) {
            return null;
        }
        return team.themeOf(request.getUserPrincipal().getName()).attr();
    }
```

Spring binds `theme=dark` to the enum case-sensitively by default; send upper-case from JS (Task 10).

- [ ] **Step 6: Migration**

`V9__team_member_theme.sql`:

```sql
-- V9: the theme a member chose, so it follows them between devices.

alter table team_members
    add column if not exists theme varchar(16) not null default 'SYSTEM';
```

- [ ] **Step 7: Run all tests, commit**

Run: `mvn test -q`

```bash
git add -A src/main src/test
git commit -m "feat: per-member theme preference with V9 migration

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: Theme preference, front end

**Files:**
- Modify: `src/main/resources/templates/layout.html:2-3, 15-23`
- Modify: `src/main/resources/static/js/app.js:19-49`

**Interfaces:**
- Consumes: `themeAttr` model attribute; `POST /me/theme`.

Invoke `frontend-design:frontend-design` before editing.

- [ ] **Step 1: Server-side attribute and CSRF metas**

On the `<html>` element in `layout.html` add `th:attr="data-theme=${themeAttr}"` (Thymeleaf omits the attribute when the value is null). In `<head>` add:

```html
    <meta name="_csrf" th:content="${_csrf != null} ? ${_csrf.token} : ''">
    <meta name="_csrf_header" th:content="${_csrf != null} ? ${_csrf.headerName} : ''">
```

Change the inline bootstrap script so localStorage only applies when the server set nothing:

```js
        (function () {
            var root = document.documentElement;
            root.classList.add("js");
            if (root.hasAttribute("data-theme")) return;
            try {
                var t = localStorage.getItem("bugtracking.theme");
                if (t === "light" || t === "dark") root.setAttribute("data-theme", t);
            } catch (e) { /* private mode: fall back to the system theme */ }
        })();
```

- [ ] **Step 2: app.js**

Replace `applyTheme(read(THEME_KEY));` with:

```js
    applyTheme(root.getAttribute("data-theme") || read(THEME_KEY));
```

In the click handler after `applyTheme(next);` add:

```js
        saveTheme(next);
```

and define, next to `applyTheme`:

```js
    function saveTheme(theme) {
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (!token || !token.content) return;
        var headers = { "Content-Type": "application/x-www-form-urlencoded" };
        headers[header.content] = token.content;
        fetch("/me/theme", {
            method: "POST",
            credentials: "same-origin",
            headers: headers,
            body: "theme=" + theme.toUpperCase()
        }).catch(function () { /* localStorage already has it */ });
    }
```

- [ ] **Step 3: Verify**

`./run.sh restart`. Sign in, toggle dark, open a private window and sign in again: the page must render dark before first paint. On the login page (no principal) the theme still follows localStorage. Check the Network tab shows a 204 from `/me/theme`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources
git commit -m "feat: theme follows the signed-in member across devices

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review

- **Spec coverage.** Labels: Tasks 1-3. Due dates: Tasks 4-5. CSV: Task 6 (URL is `/api/bugs/export.csv` rather than `/api/bugs.csv`, because Spring 6 no longer matches suffixes on a collection path). Saved filters: Tasks 7-8. Theme: Tasks 9-10. Tests and `src/test` scaffolding: Task 1. Every migration V6-V9 present with `if not exists`, indexes and RLS where a table is new.
- **Type consistency.** `BugService.findAll` grows in two steps: nine args in Task 2, ten in Task 4, each older arity delegating. `overdueIds` returns `Set<Long>`; the templates call `.contains(bug.id)`. `SavedFilter.ownedBy(String)` is used by both the service and the settings template. `ThemePreference.attr()` feeds `themeAttr`.
- **Placeholders.** None; every code step carries its code.
