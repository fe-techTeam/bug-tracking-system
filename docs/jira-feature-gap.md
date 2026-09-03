# Jira Feature Gap Analysis

## 1. Summary

The in-house tracker does the core job of a QA bug tracker well. Raising, assigning, moving and discussing a bug is fast, the per-project board is fully configurable, the audit trail is complete, and the per-bug and per-project document trees are something Jira only gets by bolting on Confluence. The biggest gaps against Jira Software Cloud are all outside the single bug record: there are no roles or permissions, no saved filters, no planning layer (sprints, epics, sub-tasks, versions), no outbound integrations of any kind, and reporting stops at counts. The headline recommendation is to close the cheap, high-friction gaps first: labels, due dates, saved filters, watchers, CSV export , then add an admin/member role split and outgoing webhooks, and to deliberately not chase JQL, custom fields, a workflow engine or agile ceremonies.

## 2. Scope

This compares Jira Software Cloud as of 2026 (not Jira Service Management, not Data Center, not the Jira Work Management variants) against the bug tracker in this repository as it stands today: Spring Boot 3.5, Thymeleaf, H2 by default with a Supabase Postgres profile, no front-end build step. Jira marketplace apps are treated as out of scope for the comparison itself but noted where the base product genuinely lacks something.

## 3. Feature comparison

### Issue model

| Feature | Jira | Ours | Gap |
|---|---|---|---|
| Issue types | Bug, Task, Story, Epic, Sub-task, custom types | Bug only | ❌ |
| Description | Rich text, 32k+ | 4000 chars, markdown rendering | ⚠️ |
| Severity | Not built in (a custom field) | First-class enum, 4 levels | ✅ better |
| Priority | Built-in, 5 levels | Deliberately absent | ⚠️ by choice |
| Environment | Free-text field | Enum QA / UAT / Production | ✅ better |
| Multiple assignees | Single assignee only | Ordered list of assignees | ✅ better |
| Labels / components | Both, searchable | None | ❌ |
| Due date / dates | Due date, start date | None | ❌ |
| Custom fields | Full framework | None | ❌ |
| Soft delete / recycle | Trash with restore | Soft delete on bugs | ✅ |

### Workflow and board

| Feature | Jira | Ours | Gap |
|---|---|---|---|
| Board columns | Per-board, mapped to statuses | Per-project, own the status | ✅ parity |
| Drag and drop | Yes | Yes | ✅ |
| Rename / recolour / reorder columns | Yes, via admin screens | Yes, from the column menu | ✅ better |
| Transition rules | Conditions, validators, post-functions | None, any column to any column | ❌ by choice |
| Notify rule per column | No | NOBODY / REPORTER / ASSIGNEES / EVERYONE | ✅ better |
| Done state | Column category (To Do / In Progress / Done) | doneState flag drives done % | ✅ parity |
| Swimlanes | Yes | No | ❌ |
| WIP limits | Yes on kanban boards | No | ❌ |

### Views and search

| Feature | Jira | Ours | Gap |
|---|---|---|---|
| Board view | Yes | Yes | ✅ |
| List / table view | Yes, sortable, configurable columns | Yes, sortable | ⚠️ |
| Filtering | JQL plus a filter bar | Assignee, reporter, severity, environment, status, keyword | ⚠️ |
| Saved filters | Yes, shareable, subscribable | None, query-params only | ❌ |
| Query language | JQL | None | ❌ by choice |
| Bulk edit | Yes, up to 1000 issues | None | ❌ |
| Keyboard shortcuts | Extensive | None | ❌ |

### Collaboration

| Feature | Jira | Ours | Gap |
|---|---|---|---|
| Comments | Threaded, rich text, restricted visibility | One-level threading, editable | ⚠️ |
| @mentions | Yes, notify | Yes, create notifications | ✅ parity |
| Attachments | On issue and comment | On bug and comment, disk or S3 | ✅ parity |
| Watchers | Yes, explicit subscribe | None | ❌ |
| Email notifications | Yes, per-scheme, digests | In-app only | ❌ |
| Activity / audit trail | Full history tab | BugHistory, full field audit | ✅ parity |

### Users and permissions

| Feature | Jira | Ours | Gap |
|---|---|---|---|
| User accounts | Atlassian identity, SSO, SCIM | team_members with password_hash, BCrypt | ⚠️ |
| Roles | Project roles, groups | Everyone is ROLE_USER | ❌ |
| Permission schemes | Fine-grained, ~40 permissions | None | ❌ by choice |
| Per-project membership | Yes | project_members relation | ✅ parity |
| Issue-level security | Yes | None | ❌ |
| Licence cost | Per user, per month | Zero | ✅ better |
| Data residency | Atlassian cloud | Self-hosted, own database | ✅ better |

### Planning

| Feature | Jira | Ours | Gap |
|---|---|---|---|
| Backlog | Yes | None | ❌ |
| Sprints | Yes, with burndown | None | ❌ by choice |
| Epics | Yes | None | ❌ |
| Sub-tasks | Yes | None | ❌ |
| Issue links | Blocks, relates, duplicates, clones, custom | blockedBy, a bare bug id | ⚠️ |
| Story points / estimates | Yes | None | ❌ by choice |
| Versions / releases | Yes, with release notes | None | ❌ |
| Roadmap / timeline | Yes | None | ❌ by choice |

### Reporting

| Feature | Jira | Ours | Gap |
|---|---|---|---|
| Dashboard | Configurable gadgets | Fixed counts by status, severity, environment | ⚠️ |
| Charts | Burndown, velocity, CFD, created vs resolved | None, bar counts only | ❌ |
| Done percentage | Via reports | Yes, from column done state | ✅ parity |
| Unassigned / urgent view | Via filters | First-class counts | ✅ parity |
| Export | CSV, JSON, XML, printable | None | ❌ |

### Integrations and API

| Feature | Jira | Ours | Gap |
|---|---|---|---|
| REST API | Full, OAuth or token auth | Full JSON under /api/**, open | ⚠️ |
| API friction | Token setup required | No auth, no CSRF, curl works immediately | ✅ better for local tooling |
| Webhooks | Yes | None | ❌ |
| Automation rules | Built-in no-code engine | None | ❌ |
| Git / CI integration | Bitbucket, GitHub, GitLab, smart commits | None | ❌ |
| Slack / Teams | Official apps | None | ❌ |
| Import | CSV, Jira-to-Jira, other trackers | None | ❌ |
| Marketplace | Thousands of apps | None | ❌ by choice |

### Customisation

| Feature | Jira | Ours | Gap |
|---|---|---|---|
| Themes | Light / dark / system, per user, server-stored | Light / dark, client-side only | ⚠️ |
| Per-user preferences | Yes | None server-side | ❌ |
| Field configuration | Screens, schemes, contexts | Fixed field set | ❌ by choice |
| Setup effort | Days of scheme configuration | Works out of the box | ✅ better |

### Documents and knowledge

| Feature | Jira | Ours | Gap |
|---|---|---|---|
| Per-issue documents | Attachments only, or a linked Confluence page | SupportingDoc pages and sheets on the bug | ✅ better |
| Project knowledge base | Confluence, separate product and licence | ProjectResource tree: folder, page, sheet, file, link | ✅ better |
| Spreadsheets | No | Sheet resources, in-app | ✅ better |

## 4. Where we deliberately differ

**No priority field.** Jira ships severity-free and priority-first; we did the opposite. For a QA team, "how bad is the defect" and "when should it be fixed" collapse into one judgement most of the time, and two overlapping dropdowns produce inconsistent data. The trade-off is real: there is no way to say "low severity, fix it today". Due dates would cover that case better than a second enum.

**People and projects are text on a bug.** A bug is a historical record. If somebody leaves and their row is renamed or deactivated, the bug should still read the way it read when it was filed. Jira uses account ids and rewrites the display everywhere. The trade-off is that we cannot join a bug to a user, so "all bugs for this person" is a string match and a rename silently splits history.

**Status is a board column, not an enum.** Adding a status is a row, not a deployment. Jira needs a workflow, a scheme and an admin with permission. The trade-off is that nothing stops an invalid move, and the same conceptual status can be spelled differently in two projects.

**The API is open and CSRF-exempt.** It makes test helpers and scripts trivial. It is also a deliberate localhost-tool choice and is one line in `SecurityConfig` away from being closed. On a network-reachable deployment it is a real exposure and should be closed.

**No roles.** Every signed-in person can do everything, including delete. The BRD asked for QA / Developer / Admin. The trade-off is that a shared tool with no admin boundary loses data to accidents, not malice. This is the one deliberate gap that should stop being deliberate.

## 5. Recommendations

### Tier 1: Build now

**Labels on bugs** (size S)
- What: a free-text tag list on a bug, filterable, with autocomplete from existing values.
- Why: covers most of what components, custom fields and ad-hoc triage categories are used for in Jira, at a fraction of the cost.
- How: `@ElementCollection` on `Bug` exactly like assignees, new `bug_labels` table, migration `V6`, index the `bug_id` column, enable RLS. Filter chip in the existing filter bar on `bugs/`.

**Due dates** (size S)
- What: an optional due date on a bug, shown on the card, sortable and filterable (overdue, due this week).
- Why: the honest replacement for the priority field we dropped, and the single most common "when" question the board cannot answer.
- How: one nullable `date` column on `Bug`, migration `V7` with `add column if not exists`, a badge on the board card and a sort key in the list view. History entry via `BugHistoryService` like any other field.

**CSV export** (size S)
- What: an export of the current filtered view from the list view and from `/api/bugs`.
- Why: it is the escape hatch that makes every missing report survivable, and it is the standard way anybody gets data out of Jira anyway.
- How: no schema change. A new method on `BugApiController` reusing `BoardQuery` so the export honours the filters already on screen.

**Saved filters** (size M)
- What: name the current filter set, pin it to the filter bar, share it project-wide.
- Why: filters are query-params only today, so every recurring question is retyped. This is the highest-friction daily gap.
- How: new `saved_filters` table storing the query string plus owner name and optional project, migration with FK index and RLS. Owner is text, not an FK, per the naming rule. New tab under `settings.html`, chips in the bugs filter bar.

**Per-user theme preference** (size S)
- What: persist light/dark to the signed-in `TeamMember` instead of only to the browser.
- Why: small, but the theme currently resets per device and the users table is already there.
- How: one `varchar` column on `team_members` (enum stored as VARCHAR, per the rule), migration, read in `GlobalModelAttributes`, written from `app.js` through a small endpoint.

### Tier 2: Build next

**Roles: admin vs member** (size M)
- What: a single role column on `team_members`, two values, gating destructive and configuration actions (delete bug, delete column, manage team, manage projects).
- Why: the one BRD requirement still open, and the only gap that risks data loss.
- How: `varchar` role column with a VARCHAR-stored enum, migration defaulting existing rows to MEMBER and promoting the seeded accounts, `SecurityConfig` granting authorities from the row, `sec:authorize` in templates. Must not break the "team_members is the users table" rule by adding a second table.

**Watchers and subscribe** (size M)
- What: explicit per-bug subscription, in addition to the column notify rule and mentions.
- Why: today you get notified because of the column you are not in control of. Watching is the opt-in half that is missing.
- How: `bug_watchers` element collection keyed by member name as text, migration with index and RLS, `NotificationService` unions watchers into its existing target set, a watch toggle on the bug detail page.

**Real issue links** (size M)
- What: replace the bare `blockedBy` id with a small typed link table: blocks, relates to, duplicates.
- Why: `blockedBy` already proves the need, and duplicates in particular are constant in QA work.
- How: new `bug_links` table (source id, target id, `varchar` type), migration that backfills existing `blockedBy` values and leaves the column readable until a later migration drops it. Index both id columns.

**Outgoing webhooks and Slack** (size M)
- What: fire a JSON POST on bug created, status changed and mention, configurable per project.
- Why: turns the in-app-only notification model into something people actually see, without building an email pipeline.
- How: new `project_webhooks` table (url, event mask, active), migration with FK index and RLS, an async dispatcher hanging off `NotificationService` so the delivery path stays out of the request thread.

**Bulk edit and keyboard shortcuts** (size M)
- What: multi-select on the list view for assign, status, label and delete; single-key shortcuts for new bug, search, board/list toggle.
- Why: triage is the slowest workflow in the app and both are pure front-end plus one endpoint.
- How: no schema change. `app.js` selection state and a batch endpoint on `BugApiController`. No build step, so keep it to plain event handlers.

**Checklists on a bug** (size M)
- What: an ordered list of tick-off items on a bug, with a done count on the card.
- Why: the useful 80 percent of sub-tasks without inventing a second issue type or a parent/child hierarchy.
- How: new `bug_checklist_items` table (bug id, text, done, position), migration with index and RLS. Reuse the existing `SupportingDoc` panel pattern on the detail page.

**Jira CSV import** (size M)
- What: a one-off importer mapping a Jira CSV export onto bugs, columns and people.
- Why: only needed if a team migrates, but its absence makes migration a hand job. Build it when a migration is actually scheduled, not before.
- How: a service plus an admin-only screen, no schema change. Maps Jira status to a board column, priority to severity, reporter and assignee to text names.

### Tier 3: Deliberately skip

**JQL.** A query language needs a parser, a permission-aware evaluator and documentation. Saved filters plus the existing filter bar cover the real queries a QA team runs, and CSV export covers the rest.

**A custom workflow engine.** Conditions, validators and post-functions are where Jira administration goes to die. Status-as-column with no transition rules is a feature for a team that trusts each other. If a rule is genuinely needed, hard-code the one rule.

**A custom-field framework.** Screens, schemes and field contexts are the single largest source of Jira complexity. Labels plus the fixed field set covers the same ground. Add a named column when a field earns one.

**Story points, sprints, velocity, burndown.** This is a defect tracker for a QA team, not a delivery-planning tool. Estimation ceremony without a delivery team to serve produces numbers nobody reads.

**Epics, roadmaps and timeline views.** Same reasoning. Planning lives wherever the team already plans; duplicating it here creates two sources of truth.

**A marketplace or plugin system.** There is no build step and no plugin audience. Extension means editing the code, which for a team of this size is faster than any plugin API would be.

**Issue-level security and permission schemes.** Admin versus member is the whole permission model this team needs. Anything finer is unenforceable in a tool everyone in the room can already read.

## 6. Suggested order

1. **Labels and due dates.** One small migration each, they touch code paths that already exist for assignees and dates, and together they answer the two questions the board cannot answer today.
2. **CSV export.** No schema change at all, and it immediately de-risks every reporting gap while the bigger items are being built.
3. **Saved filters.** Once labels and due dates exist there is more worth filtering on, so building saved filters after them means the feature ships already useful.
4. **Roles: admin versus member.** The largest remaining correctness gap and the only open BRD requirement, but it touches security config and every template, so it wants a clear run rather than being squeezed between smaller work.
