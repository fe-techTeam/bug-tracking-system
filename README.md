# Bug Tracking

A simple bug raising and tracking web application built with **Spring Boot 3.5**, **Thymeleaf** and an
**H2 file database**. No database install needed — everything runs from one command.

This is a standalone project. It is completely separate from the Selenium project on the Desktop.

## How to run

```powershell
cd C:\Users\nishana\OneDrive\Desktop\bugtracking
mvn spring-boot:run
```

Then open **http://localhost:8085**

To build a runnable jar instead:

```powershell
mvn clean package
java -jar target\bugtracking-1.0.0.jar
```

The first startup seeds 4 example bugs. Data is stored in `data\bugtracking.mv.db`
and survives restarts. Delete the `data` folder to start over.

## What you can do

| Action | Where |
|---|---|
| See all bugs + counts by status | `/bugs` |
| Filter by status / severity, search by keyword | filter bar on `/bugs` |
| Raise a new bug | `/bugs/new` |
| View one bug in full | `/bugs/{id}` |
| Edit a bug | `/bugs/{id}/edit` |
| Change status quickly | dropdown on the detail page |
| Delete a bug | detail page |
| Sign in / out | `/login`, and the ⏻ button in the top bar |
| Inspect the raw database | `/h2-console` (sign in first; JDBC URL `jdbc:h2:file:./data/bugtracking`, user `sa`, no password) |

A bug has: title, description, steps to reproduce, expected result, actual result,
severity (Critical / High / Medium / Low), **priority (P1–P4)**, **environment (QA / UAT / Production)**,
status (Open / Assigned / In Progress / Fixed / Retest / Closed / Reopened), **project**, module,
reported by, assigned to, and automatic created/updated timestamps. Each bug also carries
**comments**, **attachments** and a **history trail**.

## Against the BRD

Built from the Business Requirement Document, v1.0 (27-Aug-2026). Where the app stands:

| BRD requirement | State |
|---|---|
| FR-003 Raise Bug, FR-004 View Bug, edit, delete | done |
| Severity (Critical/High/Medium/Low) | done |
| **Priority (P1–P4), separate from severity** | done |
| **Environment (QA / UAT / Production)** | done |
| **Full lifecycle: Open → Assigned → In Progress → Fixed → Retest → Closed, plus Reopened** | done |
| **FR-005 Assign / reassign a bug** | done |
| FR-006 Update bug status | done |
| **FR-007 Comments** | done |
| **FR-008 Attachments, with type and size validation** | done |
| FR-009 Search and filter — id, title, description, module, project, people, status, severity, priority, environment, assignee | done |
| **Sorting** — newest, oldest, recently updated, severity, priority, status, title | done |
| **FR-010 Bug history** | done |
| **FR-011 Notifications — assigned, fixed, reopened, closed** | done (in-app; no email) |
| FR-002 Dashboard — totals per status, per severity, per priority, urgent count | done |
| **FR-001 Login / logout** | done (single account) |
| FR-001 User accounts, QA/Developer/Admin roles, role-based permissions | not built — see below |

### What is deliberately not built: roles

Login and logout exist (see **Signing in** below), but there is **one shared account**, not user
accounts with roles. So the business rules that depend on a role are not enforced — "a developer
should not directly close a bug", "only authorized QA users can raise bugs", and role-scoped views.
The lifecycle they govern is fully modelled; nothing yet restricts *who* may move a bug along it.

Getting there means per-person credentials on the `team_members` table (a `password_hash` and a
`role` column), which is a natural next step now that sign-in is in place.

Also still open from the BRD: user management (§6), and the future-enhancement list in §19.

## Signing in

Everything except the JSON API is behind a login. One account, set in
`application.properties`:

```properties
bugtracking.security.email=nishana@firsteconomy.com
bugtracking.security.password=Pass@2026
```

The password is plain text there only because this is a local app with a single account — it is
hashed with **BCrypt** when the account is built at startup, and the hash is what any comparison
runs against. Before this is used anywhere shared, move accounts into the database with stored
hashes.

You sign in with the **email**, but the name shown in the top bar and recorded against your work is
the **display name from the team table** ("Nishana R"), looked up by that email. Comments, status
changes, assignments and history entries are all credited to whoever is signed in — the old
"acting as" box is gone, because guessing at identity is no longer necessary.

Sign out with the ⏻ button in the top bar.

### What this changed

- **CSRF is on** for the HTML forms. Thymeleaf adds the token to any form with `th:action`, so every
  form in the app already carries one. A hand-rolled form without `th:action` would be rejected.
- **`/api/**` stays open and CSRF-exempt**, so scripts and test helpers keep working unchanged. That
  is a deliberate, reversible choice for a localhost tool — one line in `SecurityConfig` closes it:
  change `.requestMatchers("/api/**").permitAll()` to `.authenticated()`.
- **`/h2-console` now needs a login** (it was open before), and frame options are relaxed to
  same-origin so the console renders.
- **Selenium tests need a sign-in step first.** New ids: `login-form`, `email`, `password`,
  `login-button`, `login-error`, `logout-button`. Every other id is unchanged.

## The board

`/bugs` is a three-column board:

```
┌──────────────────┬─────────────────────────────┬──────────────┐
│ PROJECTS         │  Godrej                     │  ┌────┐      │
│ All Projects  12 │  3 shown                    │  │ 12 │ Total│
│ ▸ Mahindra     5 │                             │  │  7 │ Open │
│   Godrej       3 │  ┌───────────────────────┐  │  │  2 │Urgent│
│   Color Shine  3 │  │ Bug ID │ Title │Status│  │  └────┘      │
│   Orpat        1 │  │  #12   │  …    │ Open │  │  ▓▓▓░░ status│
│                  │  │  #9    │  …    │ Fixed│  │  chips       │
│ FILTER & SORT    │  │  #4    │  …    │Retest│  │              │
│ [Any status  ▾]  │  └───────────────────────┘  │  severity    │
│ [Any severity▾]  │                             │  cards       │
│ [Newest first▾]  │                             │              │
│ [Apply] [Clear]  │                             │              │
└──────────────────┴─────────────────────────────┴──────────────┘
```

**Left — projects, then filters.** Every project with its bug count and a bar showing its share of
the board; "All Projects" goes back to the whole board. Below it, the filter and sort controls as a
vertical stack.

**Middle — the bugs, and nothing else.** Five columns: **Bug ID, Title, Status, Assigned To,
Reported**. Severity still shows as the thin coloured rail down each row; everything else about a
bug is on its detail page. The list gets the full width of the column.

**Right — this project's dashboard.** Three KPIs (total, still open, urgent), the queue shape as one
stacked bar with clickable status chips, and severity cards. The KPIs describe
the **project**; the filters only change the **table**, so the headline numbers never move under you
while you filter. Every stat is also a filter — click a status chip or a severity card.

Both rails are sticky. Under 1400px the dashboard rail drops below the bugs as a row of cards; under
900px everything stacks.

### A note on the graph's colours

The bars use the reserved **status** colours, so a status looks the same in the graph as it does on
a badge, a rail or the stepper. That palette deliberately fails a *categorical* colour check —
`Open` is a neutral grey on purpose ("nothing has happened yet"), and a grey sits close to its
neighbours. Rather than repaint the whole app to satisfy a check that assumes arbitrary categories,
every bar carries its **own name and count**: colour is reinforcement, never the only thing telling
the bars apart. Contrast against the surface passes in both themes.

## Projects

**Project is required** on every bug and is picked from a `projects` table — the same pattern as the
team list. Four are seeded on startup (Mahindra Mutual Fund, Godrej, Color Shine, Orpat), matched on
name and inserted only if missing, so the seeder is safe to re-run.

Manage them at **`/projects`**: add, hide (stops being offered, existing bugs untouched), or remove
(only for a project with no bugs). Raising a bug from inside a project pre-selects it.

Bugs store the project **name** as text, not a foreign key — so a bug on a retired project still
reads correctly, and the edit form keeps showing a project that is no longer on the list rather than
silently moving the bug.

> **Renamed from "Client".** The old `client` field *was* this concept, so it became `project`. On
> first startup the values are copied across and the old column is dropped — see
> `ProjectColumnMigration`. **The JSON API field is now `project`, not `client`**, and
> `GET /api/bugs/clients` is gone; use `GET /api/projects`. Update any scripts.

## Team

**Reported By** and **Assigned To** are dropdowns, filled from a `team_members` table rather than
typed by hand — so names are spelled one way and filtering by assignee actually works. The 18
members are seeded on startup, matched on email and inserted only if missing, so the seeder is safe
to re-run and new names can just be appended to `TeamMemberSeeder`.

Manage them at **`/team`**:

| Action | What it does |
|---|---|
| Add | Name + email. A repeat email is ignored rather than duplicated. |
| Hide | Stops offering them in the dropdowns. Every bug they raised or were assigned is untouched. |
| Remove | Only offered for someone named on **no** bug — for a typo or a mistaken entry. Anyone with history must be hidden instead, so their bugs keep making sense. |

The "On bugs" column counts the bugs naming each person and links to them.

Bugs store the person's **name** as text, not a foreign key. That keeps bugs raised before this
table existed readable, and means renaming or hiding somebody never rewrites history. If a bug
holds a name that is no longer on the team — an old value like `dev-team`, or someone since
hidden — the edit form keeps showing it, so opening an old bug never silently reassigns it.

`GET /api/team` returns the active members; `?activeOnly=false` returns everyone.

## The interface

The UI leans on visual cues rather than text alone:

| Cue | Means |
|---|---|
| Colour temperature | severity — cool cyan (Low) through to hot rose (Critical) |
| The 4-bar meter | severity again, as a count: 1 bar Low, 4 bars Critical |
| Coloured rail down a row | that bug's severity, readable before you read the title |
| Stacked bar on the dashboard | the shape of the whole queue by status |
| Stepper on the detail page | how far along Open → In Progress → Fixed → Closed a bug is |
| Coloured initials | a project or a person — the same name always gets the same colour |
| Green / red panels | expected vs actual result |
| Square mono chip (P1–P4) | priority — deliberately shaped unlike the severity pill, because they are different things |
| Environment tag | QA is neutral, UAT violet, Production red — a production bug should look scarier |
| Timeline rail | the history trail, colour-coded by the kind of change |

Attachments are stored under `data\attachments\` with random names, and the metadata in the
database. Allowed types and the 10 MB ceiling are set by `bugtracking.attachments.*` in
`application.properties`.

Light and dark themes follow the OS, and the ☾ button in the top bar overrides that (remembered in `localStorage`). Keyboard: **/** focuses search, **n** raises a bug, **Esc** leaves a field.
All animation is skipped for anyone with "reduce motion" set.

## REST API

Useful if you ever want to raise bugs from a script or a failing test.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/bugs?status=OPEN&severity=HIGH&keyword=login` | list (all params optional) |
| GET | `/api/bugs/{id}` | one bug |
| POST | `/api/bugs` | create |
| PUT | `/api/bugs/{id}` | update |
| DELETE | `/api/bugs/{id}` | delete |
| GET | `/api/bugs/summary` | counts by status, severity and priority, plus the urgent count |
| GET | `/api/projects` | the projects a bug may be raised against |
| GET | `/api/projects/counts` | bug count per project, as the sidebar shows it |
| GET | `/api/projects/{name}/dashboard` | one project's dashboard numbers |
| GET | `/api/bugs/options` | every dropdown vocabulary: statuses, severities, priorities, environments, projects |
| POST | `/api/bugs/{id}/status?status=RETEST&actor=you` | move a bug along the lifecycle |
| POST | `/api/bugs/{id}/assign?assignedTo=dev-team&actor=you` | assign or reassign (Open/Reopened becomes Assigned) |
| GET/POST | `/api/bugs/{id}/comments` | read the thread, or post `{"text":"…","author":"…"}` |
| GET | `/api/bugs/{id}/history` | the audit trail |
| GET | `/api/bugs/{id}/attachments` | attachment metadata |
| GET | `/api/bugs/notifications` | the 50 most recent notifications |

Filters on `GET /api/bugs`: `project`, `status`, `severity`, `priority`, `environment`, `assignee`, `keyword`,
`sort`. All optional.

`project` is required on POST and PUT — omitting it returns **400**.

Example:

```powershell
$body = '{"title":"Login fails","severity":"HIGH","status":"OPEN","project":"Godrej","module":"Auth"}'
Invoke-RestMethod -Uri "http://localhost:8085/api/bugs" -Method Post -ContentType "application/json" -Body $body
```

## Project layout

```
bugtracking\
  pom.xml
  README.md
  data\                            H2 database file (created on first run)
  target\                          build output + runnable jar
  src\main\java\com\bugtracking\
    BugTrackingApplication.java    entry point
    model\       Bug.java, Severity.java, Status.java     the data
    repository\  BugRepository.java                       database queries
    service\     BugService.java                          business logic
    controller\  BugController.java      web pages
                 BugApiController.java   JSON API
                 HomeController.java     "/" -> "/bugs"
                 GlobalExceptionHandler.java
    model\       Project.java, Priority.java, Environment.java                 the extra vocabularies
                 Comment.java, BugHistory.java,
                 Attachment.java, Notification.java              the things hanging off a bug
                 TeamMember.java                                 the people dropdowns
    service\     CommentService, BugHistoryService,
                 AttachmentService, NotificationService,
                 TeamMemberService
    controller\  TeamController.java     /team page
                 TeamApiController.java  /api/team
    config\      SampleDataLoader.java   seeds example bugs
                 ProjectSeeder.java      the projects, seeded and kept up to date
                 AttachmentProperties.java  upload dir, size and type rules
                 ProjectColumnMigration.java  carries client values into project
                 FieldDefaultsBackfill.java  same for priority and environment
                 TeamMemberSeeder.java   the team, seeded and kept up to date
                 SchemaUpgrade.java      widens legacy H2 ENUM columns to VARCHAR
  src\main\resources\
    application.properties
    templates\   layout.html, bugs\list.html, bugs\form.html, bugs\detail.html
    static\css\  style.css               design tokens + components
    static\js\   app.js                  theme, relative times, live preview
```

The layers go: **controller → service → repository → database**. The controller only handles
web requests, the service holds the rules, the repository talks to H2.

## Note for Selenium practice

Every interactive element has a stable `id`, so this app makes a good local target for writing
WebDriver tests. The ids survive redesigns and feature work — they are part of the contract.

- **Auth:** `login-form`, `email`, `password`, `login-button`, `login-error`, `login-notice`, `logout-button`
- **Nav:** `raise-bug-link`, `notifications-link`, `theme-toggle`, `current-user`, `flash-message`
- **List:** `bug-table`, `filter-status`, `filter-severity`, `filter-priority`,
  `filter-environment`, `filter-assignee`, `filter-keyword`, `sort-by`, `apply-filters`,
  `clear-filters`, `no-bugs`
- **Form:** `bug-form`, `title`, `project`, `severity`, `priority`, `environment`, `status`,
  `module`, `description`, `stepsToReproduce`, `expectedResult`, `actualResult`, `reportedBy`,
  `assignedTo`, `submit-bug`
- **Detail:** `bug-title`, `bug-severity`, `bug-priority`, `bug-status`, `bug-environment`,
  `bug-project`, `change-status`, `update-status`, `assign-to`, `assign-bug`, `edit-bug`,
  `delete-bug`, `comment-form`, `comment-text`, `add-comment`, `comment-list`,
  `attachment-form`, `file`, `upload-attachment`, `history-list`
- **Notifications:** `notification-list`, `notification-count`, `mark-all-read`, `no-notifications`
- **Team:** `team-link`, `team-table`, `team-form`, `member-name`, `member-email`, `add-member`

`reportedBy`, `assignedTo` and `assign-to` are now `<select>` elements, not text inputs — drive them
with `new Select(driver.findElement(By.id("assignedTo")))`. The Remove button on `/team` opens a
`confirm()` dialog, like Delete on a bug.

Two things to handle in a test:

- the delete button opens a JS `confirm()` dialog — `driver.switchTo().alert().accept()`
- `project` is a `<select>`, so drive it with `new Select(driver.findElement(By.id("project")))`,
  and remember it is **required** — submitting without it re-renders the form with an error

## Change the port

Edit `server.port` in `src\main\resources\application.properties`.
