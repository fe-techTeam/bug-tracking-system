# Tier 1 Jira gaps: design

Five small features from `docs/jira-feature-gap.md`, built together on branch `feat/tier1-jira-gaps`: labels, due dates, CSV export, saved filters, per-user theme. Each ships with its Flyway migration, and every recommendation respects the rules in `CLAUDE.md`: enums as `VARCHAR`, people and projects as text on bugs, status as a board column, one users table, idempotent migrations with indexes and RLS.

Migration numbers are fixed: **V6** labels, **V7** due date, **V8** saved filters, **V9** theme.

## 1. Labels on bugs

**Model.** `Bug.labels`, a `List<String>` `@ElementCollection` mirroring `assignees`: table `bug_labels (bug_id, position, label varchar(40))`, primary key `(bug_id, position)`, eager fetch, `@OrderColumn(position)`. `setLabels` trims, drops blanks, deduplicates case-insensitively keeping the first spelling, and caps each label at 40 characters. Labels keep the case the user typed.

**Migration V6.** `create table if not exists bug_labels` with the same shape as `bug_assignees`, `create index if not exists idx_bug_labels_label on bug_labels (lower(label))` for filtering and autocomplete (the primary key already leads with `bug_id`), `enable row level security`.

**Input.** On `bugs/form.html` one text input `labels` holding a comma-separated list, backed by a `<datalist>` of existing labels for the project. A `String` ↔ `List<String>` conversion happens in a `labelsText` accessor pair on `Bug` (transient), so `@ModelAttribute` binding stays untouched. `GET /api/bugs/labels?project=` returns the distinct labels in use, sorted, for the datalist and for the API. `POST /api/bugs` and `PUT /api/bugs/{id}` accept `labels` as a JSON array through the existing entity binding.

**Filter.** New request param `label` (single value, like `assignee`) on `BugController.list` and `BugApiController.list`, added to `BugService.findAll` and to `BugRepository.search` as an `EXISTS` subquery with `LOWER(l) = LOWER(:label)`. The filter panel gains a "Label" column listing the project's labels as toggle links, and an active-filter chip renders through the existing `q.without('label')` pattern.

**Display.** Board card foot and list row show labels as small `.pill` chips (a new `pill-label` modifier using existing tokens). Detail page shows the same chips, each a link to `/bugs?label=x`. History records action `labels` with the comma-joined old and new values via `recordIfChanged`.

## 2. Due dates

**Model.** `Bug.dueDate`, a nullable `LocalDate`, column `due_date date`. Migration V7: `alter table bugs add column if not exists due_date date`, plus `create index if not exists idx_bugs_due_date on bugs (due_date)`.

**Input.** `<input type="date" name="dueDate">` on the form; the API accepts an ISO date string. `BugService.update` diffs it like other fields and records history action `due` with ISO strings.

**Derived state.** `Bug.isOverdue(BoardColumns cols)` is false when there is no due date or the bug's column has `doneState`; otherwise true when `dueDate` is before today. Because done-ness depends on the project's columns, the check lives in `BugService` as `overdue(bug)` and is exposed to templates via a `Map<Long, Boolean>` or a per-bug view helper, whichever the implementer finds cleaner; it must not put column lookups inside the entity.

**Display.** A date badge on the board card foot and a "Due" column in the list view, formatted `dd MMM`. Overdue bugs get an `is-overdue` modifier that uses the existing critical-severity colour token. No badge when there is no due date.

**Sort and filter.** New `sort=due` (nulls last, then earliest first) added to `BugService.sorted` and to the Order list in the filter panel. New request param `due` with values `overdue` and `week`, applied in `BugService.findAll` after the repository query: `overdue` uses the same rule as the badge, `week` means due today through the next seven days. Both appear as toggle links in the filter panel and as chips.

## 3. CSV export

**Endpoint.** `GET /api/bugs.csv` with exactly the same parameters as `GET /api/bugs`, including the new `label` and `due`. Returns `text/csv; charset=UTF-8` with `Content-Disposition: attachment; filename="bugs-<project or all>-<yyyyMMdd>.csv"`.

**Writer.** A small `CsvWriter` in `service/` producing RFC 4180 output: fields containing commas, quotes or newlines are quoted, quotes doubled, CRLF line endings, a UTF-8 BOM so Excel opens it correctly. Columns, in order: `id, title, severity, status, environment, project, module, reported_by, assignees, labels, due_date, created_at, updated_at`. `assignees` and `labels` are `;`-separated inside one cell. Status is the column label, not the key, via `BoardColumns.label`.

**UI.** An "Export CSV" link in the list view toolbar, `href="/api/bugs.csv" + q.query()`, so the download honours the filters on screen.

## 4. Saved filters

**Model.** Entity `SavedFilter`: `id`, `name varchar(60)`, `project varchar(120)` (nullable; null means every project), `query varchar(1000)`, `createdBy varchar(80)` (text, not a foreign key), `createdAt`. Table `saved_filters`. Migration V8: `create table if not exists`, `create index if not exists idx_saved_filters_project on saved_filters (project)`, `enable row level security`.

**Visibility.** Every saved filter is visible to everyone. Only its creator (matched by the authenticated name) can delete it; anyone else gets 403 from the service, not the controller. There is no rename; delete and re-save.

**Stored query.** The current filter parameters minus `project` and minus `view` (so a saved filter applies to whichever view you are in). Saving with no active filters is rejected with a flash message.

**Creating.** A small `<details>` popover in the bugs filter bar, visible only when `q.isFiltered()`, with a name input and a "Save filter" button, posting `name` and the current query to `POST /filters` (HTML form, CSRF via `th:action`). Project defaults to the current project; a checkbox "All projects" stores null.

**Applying.** Saved filters for the current project plus the project-less ones render as chips in the filter bar, before the active-filter chips. Each chip links to `/bugs?project=<current>&view=<current>&<stored query>`.

**Managing.** `settings.html` gains a fourth tab `filters` (add `"filters"` to `SettingsController.TABS`) listing every saved filter with name, scope, creator, and a delete button shown only to the creator. `POST /filters/{id}/delete` handles both the settings page and the chip's remove affordance.

## 5. Per-user theme preference

**Model.** `TeamMember.theme`, enum `ThemePreference { SYSTEM, LIGHT, DARK }` stored as `VARCHAR` with `@JdbcTypeCode(SqlTypes.VARCHAR)`, default `SYSTEM`. Migration V9: `alter table team_members add column if not exists theme varchar(16) not null default 'SYSTEM'`.

**Reading.** `GlobalModelAttributes` exposes `themePreference` for the signed-in member (null when anonymous or on error dispatch). `layout.html` sets `data-theme` on `<html>` server-side when the preference is `LIGHT` or `DARK`, and the existing inline bootstrap script only consults `localStorage` when the server set nothing. This keeps the login page and any pre-login render working exactly as today.

**Writing.** The theme toggle in `app.js` keeps writing `localStorage` and additionally posts the new value to `POST /me/theme` (form-encoded `theme=DARK`, behind login, CSRF token read from a new `<meta name="_csrf">` and `<meta name="_csrf_header">` pair in `layout.html`). A `MeController` in `controller/` handles it and returns 204. Failures are ignored client-side; localStorage remains the fallback.

**Cycle.** The toggle cycles light → dark → light as now; the stored value mirrors whichever was chosen. `SYSTEM` is only the default for members who have never toggled.

## Cross-cutting

**Tests.** `src/test` does not exist yet; this work creates it. Each feature carries the tests that pin its rules: label normalisation, overdue and due-this-week rules, CSV quoting, saved-filter ownership, theme resolution. Use `@DataJpaTest` on H2 for repository queries and plain JUnit for the pure logic. Every implementer writes the failing test first.

**Templates.** All UI work follows `.claude/skills/frontend/SKILL.md`: tokens, both themes, existing component vocabulary (`.pill`, `.chip`, `.fmenu`, `.seg`).

**Comments.** One line, only for a non-obvious why. Migrations may carry a short header comment in the style of V5 but need not.

**Order of implementation.** Labels → due dates → CSV → saved filters → theme. The first three touch the same files (`Bug`, `BugService`, `BugRepository`, both bug controllers, `list.html`) and must run sequentially. Saved filters and theme are independent of each other and of the first three, but they also edit `list.html` and `layout.html`, so they run after CSV, in parallel with each other only if their template edits stay in distinct regions. Each feature is followed by a code review before the next begins.

**Out of scope.** Multi-value label filtering, label colours, due-date notifications, saved-filter rename or private filters, a three-way theme toggle in the UI.
