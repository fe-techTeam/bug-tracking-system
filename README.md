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
| Rename, recolour, reorder or add a board column | a column's ⋯ menu on `/bugs`, or `/settings?tab=board` |
| Delete a bug | detail page |
| Sign in / out | `/login`, and the ⏻ button in the top bar |
| Inspect the raw database | `/h2-console` (sign in first; JDBC URL `jdbc:h2:file:./data/bugtracking`, user `sa`, no password) |

A bug has: title, description, steps to reproduce, expected result, actual result,
severity (Critical / High / Medium / Low), **environment (QA / UAT / Production)**,
status (whichever column of its project's board it is sitting in), **project**, module,
reported by, assigned to, and automatic created/updated timestamps. Each bug also carries
**comments**, **attachments** and a **history trail**.

## Against the BRD

Built from the Business Requirement Document, v1.0 (27-Aug-2026). Where the app stands:

| BRD requirement | State |
|---|---|
| FR-003 Raise Bug, FR-004 View Bug, edit, delete | done |
| Severity (Critical/High/Medium/Low) | done |
| ~~Priority (P1–P4), separate from severity~~ | **dropped** — see below |
| **Environment (QA / UAT / Production)** | done |
| **Lifecycle: Open → In Progress → Ready for Test → Retest → Closed, plus On Hold** | done |
| **FR-005 Assign / reassign a bug** | done |
| FR-006 Update bug status | done |
| **FR-007 Comments** | done |
| **FR-008 Attachments, with type and size validation** | done |
| FR-009 Search and filter — id, title, description, module, project, people, status, severity, environment, assignee | done |
| **Sorting** — newest, oldest, recently updated, severity, status, title | done |
| **FR-010 Bug history** | done |
| **FR-011 Notifications — assigned, ready for test, on hold, closed, @mentions** | done (in-app; no email) |
| FR-002 Dashboard — totals per status, per severity, urgent count | done |
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

```
┌────────────────────────────────────────────────────────────────┐
│ ▣ Bug Tracking  │ Board  Settings │      🔔  ☾  ⬤ Nishana R ▾ │
├────────────────────────────────────────────────────────────────┤
│ ⬤ Mahindra Mutual Fund   [ 12 bugs · 7 open · 2 urgent  ▾ ]    │
│                          └── pull this open for the switcher   │
│                              and the project's numbers         │
├────────────────────────────────────────────────────────────────┤
│ 🔍 search    · faces ·  [Any severity ▾] [Any status ▾] [⋯]    │
├────────────────────────────────────────────────────────────────┤
│  OPEN 3      IN PROGRESS 2    ON HOLD 1     READY FOR TEST     │
│  ┌────────┐  ┌────────┐       ┌────────┐                       │
│  │BUG-12 ⊘│  │BUG-9   │       │BUG-4   │    ← drag between     │
│  │ title  │  │ title  │       │ title  │      columns          │
│  │ ▮▮▮▯ ⬤⬤│  │ ▮▮▯▯ ⬤ │       │ ▮▯▯▯ ⬤ │                       │
│  └────────┘  └────────┘       └────────┘                       │
└────────────────────────────────────────────────────────────────┘
```

**One navbar, one topbar, then the work.** There is no sidebar. The navbar carries the app, the two
sections and you; the topbar carries the project you are in.

**The project name is the switcher.** Click it and every project drops down — no expanding, no
detour through a menu.

**The counts are the handle.** `12 bugs · 7 open · 2 urgent` is a button. Pull it open and you get
the KPI tiles, the queue shape as one stacked bar, and the severity and workload breakdowns. Closed,
it still tells you where the project stands — so the common case costs no clicks and the detail is
one click away. The panel is deliberately a different colour from the bars above and below it, so it
reads as something pulled out from behind the topbar rather than as a third bar.

**Board or list.** The board is one column per status and you drag a card between them; the list is
the same bugs as a table. Either way a card is clickable anywhere on it, not just its title. The
columns themselves belong to the project and are yours to change — see
[The board's columns](#the-boards-columns).

**What a card tells you without being opened:** its id, the severity meter (1–4 bars), a ⊘ if it is
blocked by another open bug, when it was raised, and the initials of everyone on it — hover a face
for the name. A card assigned to you is outlined in the accent colour.

The KPIs describe the **project**; the filters only change the **cards**, so the headline numbers
never move under you while you filter. Every stat is also a filter — click a status chip or a
severity card.

### A note on the graph's colours

The bars use each column's own colour, so a status looks the same in the graph as it does on a badge
or a rail. That palette deliberately fails a *categorical* colour check — `Open` is a neutral grey on
purpose ("nothing has happened yet"), and a grey sits close to its neighbours. Rather than repaint the whole app to satisfy a check that assumes arbitrary categories,
every bar carries its **own name and count**: colour is reinforcement, never the only thing telling
the bars apart. Contrast against the surface passes in both themes.

## Projects

**Project is required** on every bug and is picked from a `projects` table — the same pattern as the
team list. Four are seeded on startup (Mahindra Mutual Fund, Godrej, Color Shine, Orpat), matched on
name and inserted only if missing, so the seeder is safe to re-run.

Manage them under **Settings → Projects**: add, hide (stops being offered, existing bugs
untouched), or remove (only for a project with no bugs). Raising a bug from inside a project
pre-selects it. `/projects` still redirects there, so old links keep working.

Bugs store the project **name** as text, not a foreign key — so a bug on a retired project still
reads correctly, and the edit form keeps showing a project that is no longer on the list rather than
silently moving the bug.

> **Renamed from "Client".** The old `client` field *was* this concept, so it became `project`. On
> first startup the values are copied across and the old column is dropped — see
> `ProjectColumnMigration`. **The JSON API field is now `project`, not `client`**, and
> `GET /api/bugs/clients` is gone; use `GET /api/projects`. Update any scripts.

> **Priority was removed.** Severity and priority answered nearly the same question and were
> filled in by the same person at the same moment, so one of them was always noise. Severity is what
> survived, and "urgent" — the dashboard tile, the topbar count, a person's workload — now means
> **Critical or High and still open** rather than P1 + P2. The `PRIORITY` column is left in the
> database rather than dropped, so nothing is lost if it is ever wanted back; the JSON API no longer
> accepts or returns it.

## A bug's page

Two columns, and each answers a different question.

**Left — what the bug says**, as one card: the title, the description, the steps, and expected
against actual. It is one story and it is read in one pass, so it is not broken into four panels.
Under it, the supporting docs, and then the comments.

**Right — what the bug *is*.** The first card carries its number, a status dropdown, and every fixed
fact (severity, environment, module, project, who raised it, when). It is never collapsed, because
it is the first thing you look at. Below it: who is on it, what is blocking it, the attachments, and
the history — which *is* collapsed, being reference rather than the job.

**Edit and Delete** sit together in the top right, where actions on the whole bug belong.

Images attach and then **show**: a thumbnail on the bug, opening in a lightbox over the page rather
than swallowing the tab. Any attachment can be removed, and that is recorded in the history like
every other change.

## Supporting docs

A bug report says what went wrong. Testing it produces rather more than that — a plan, a set of
cases, the data you used, what each one did — and that had nowhere to go except a comment, an
attached spreadsheet, or somebody's own drive. **Supporting docs** is that place, on the bug.

Two shapes, and deliberately no more:

| | |
|---|---|
| **Page** | Markdown. Notes, a test plan, a sign-off. A formatting toolbar, and a live preview beside what you are writing — **Write / Split / Preview**, remembered between visits. Checklists (`- [ ] …`), tables and code blocks all render |
| **Sheet** | A grid. Test cases, test data, a pass/fail matrix. Letters across the top, numbers down the side, and the header row and number column stay put as you scroll |

Both are created from the bug's page with one click, open on a page of their own, and **save as you
type** — the toolbar says "Saved" with the time, "Unsaved changes" while you are mid-edit, and tells
you plainly if a save failed. Leaving the page mid-sentence still saves. There is a Save button too,
and it always works.

A **page** downloads as `.md`; a **sheet** downloads as `.csv` — with the byte-order mark Excel wants,
so accented names survive the round trip.

### In a sheet

- **Paste straight from Excel.** A block of cells lands as a block, growing the grid to fit.
- **Formulas.** `=SUM(A1:A9)`, `AVG`, `MIN`, `MAX`, `COUNT`, `PRODUCT`, cell references and ordinary
  arithmetic. The cell keeps the formula — that is what is saved, and what you get back when you
  click into it — and shows the answer while you are elsewhere. A loop reads `#CYCLE` rather than
  hanging the tab.
- **Keyboard.** Enter for the row below (adding one at the bottom), Tab across, arrows to move.
- **Rows and columns** are added and removed from the toolbar; the toolbar acts on the cell you are in.
- Ceilings of 300 rows and 40 columns, so a runaway paste cannot outgrow the column it is stored in.

Creating, renaming and deleting a document is recorded in the bug's history. Editing the body is
not — the editor saves as you type, and every keystroke in the timeline would bury the changes that
matter. Who last touched it, and when, is on the document itself.

Deleting a document cannot be undone. Deleting the *bug* keeps everything, as it always has:
documents come back with it out of the trash, and are only destroyed when the trash is emptied.

**With JavaScript off** every one of these still works, minus the conveniences. A page is a textarea
and a Save button. A sheet is a grid of ordinary text inputs, with a × on each row and column and
Add row / Add column beside it — the server applies those. What scripting adds is the preview, the
formulas, pasting a block, and not having to press Save.

## Project documents

Supporting docs live on a bug. Everything *else* a project runs on — the signed SOW, the API spec,
the Figma link, the UAT tracker, the deck somebody keeps re-sharing on WhatsApp — belongs to the
project, not to any one bug. **Settings → Projects → Documents**, or **Documents** in the navbar
(which opens the project you are working in), is where that goes.

One filing cabinet per project, holding four kinds of thing:

| | |
|---|---|
| **Folder** | Nests as deep as you like. Drag-free: things are moved with *Move to* on the card |
| **Page** | Markdown, the same editor the bug docs use — toolbar, live preview, Write / Split / Preview |
| **Sheet** | A spreadsheet, with the formatting a spreadsheet has — see below |
| **File** | Uploaded. PDFs, screenshots, Office documents, CSVs, video. Images show as thumbnails and open over the page |
| **Link** | An http(s) address with a name and a note. Nothing else is stored — a `javascript:` link on a page the whole team opens is a way to run script as them |

The screen is a folder rail on the left, a breadcrumb, and a grid of cards grouped by kind under
labelled headings — folders, then documents, then files, then links — so what a thing *is* reads
before its name does. Each kind carries its own colour on the tile at the card's left, the same
colour it has in the rail and in the editor. **Search** covers the whole project, across every
folder, matching names, descriptions and link addresses.

Uploads take several files at once, and you can **drop files anywhere on the page** to put them in
the folder you are looking at. Each file is reported on separately, so a batch of twelve containing
one `.exe` still files the other eleven. Allowed types and the 25 MB per-file ceiling are
`bugtracking.attachments.doc-extensions` and `.max-doc-size-bytes` — deliberately a *separate* pair
from the bug-attachment settings, so widening one never widens the other.

Deleting a folder deletes everything inside it, including the files on disk, and cannot be undone.
Removing a project takes its documents with it.

### A sheet, in a project

The bug sheets are a grid you type in. A project sheet is a spreadsheet:

- **Formatting.** Bold, italic, underline, strikethrough; font size; text and fill colour from a
  palette or a colour picker; left / centre / right; wrap; a cell outline. `Ctrl+B` / `I` / `U` work.
- **Number formats.** Plain, number, thousands, currency, percent, date, time, date and time.
  Thousands group the Indian way (`12,34,567.89`). The cell keeps what you typed and shows the
  formatted value while you are elsewhere — the same trick formulas use.
- **Selecting a block.** Click and drag, shift-click, shift-arrows, `Ctrl+A`, or click a column
  letter or row number for the whole of it. Every toolbar button acts on the selection.
- **Merge cells**, and unmerge them. The top-left cell keeps the content.
- **Column widths** drag from the edge of the heading. **Freeze header** keeps the top row on screen.
- **Undo and redo**, `Ctrl+Z` / `Ctrl+Shift+Z`, over typing and formatting alike.
- **Formulas**, shared with the bug sheets and extended here: `SUM` `AVERAGE` `MEDIAN` `MIN` `MAX`
  `COUNT` `COUNTA` `PRODUCT` `ROUND` `ABS` `SQRT` `POWER` `IF` `AND` `OR` `NOT`, comparisons
  (`=A1>10`), `^`, and cell references. Numbers only — text is worth nothing to a sum, and a formula
  that quietly reads "N/A" as 0 is worse than one that says `#VALUE`.
- **Paste straight from Excel**, and **`@` to tag someone**, in a cell.

Ceilings of 500 rows and 40 columns.

**With JavaScript off** the whole area still works: the New menus are `<details>`, upload has its own
submit button, and a sheet is a grid of ordinary text inputs with Add row / Add column beside it.
Saving that way keeps the formatting that was already on the sheet rather than stripping it — the
form can only carry the values, so the rest is taken from what was stored.

## The board's columns

**A status is a column, and the columns belong to the project.** Every project starts on
**Open → In Progress → On Hold → Ready for Test → Retest → Closed** and can then run whatever it
actually runs: rename them, recolour them, drag them into a different order, add a *Waiting on
client* or a *Sign-off*, remove the ones it does not use. Two projects need not agree — a client
engagement wants a sign-off step, an internal tool does not.

**Where.** A column's own **⋯ menu** on the board renames it, repaints it, moves it left or right
and removes it, and the dashed tile at the end of the board adds one. `/settings?tab=board` is the
same set of columns with the two settings that are not quick decisions — see below. Dragging a
column's head reorders the board; the arrows in its menu do the same thing without scripting.

**Renaming is free.** A bug stores the column's *key*, which is fixed when the column is created and
never rewritten, so a rename changes the wording and nothing else — no bug moves, and the history
trail still reads with the wording that was on screen when each move happened.

**Two settings that carry weight:**

| | |
|---|---|
| **Bugs here are** *work in hand* / *finished* | This is what "still open", the urgent count and the list of bugs you may pick as a blocker all read. A column you invent needs it set correctly, and a project may have as many finished columns as it likes |
| **Tell, on arrival** | Who gets a notification when a bug lands here: nobody, whoever raised it, whoever is on it, or both. Seeded so the six original columns behave exactly as they always did — Open and In Progress announce nothing, On Hold tells the people on it, Ready for Test and Retest tell the reporter, Closed tells everyone |

**Removing a column asks where its bugs go**, and moves them there — the trashed ones too, so a bug
restored later does not come back into a column that no longer exists. The last column on a board
cannot be removed; there would be nowhere for a bug to be. Moving a bug to a project whose board has
no such column lands it in that board's first column.

**Colours are chosen, not typed.** A column picks one of eight tokens the stylesheet owns
(`ColumnColour`), because the board only reads as a journey — cool while it waits, warm when it
needs a person, green when it is done — while every column is on the same scale. A free hex field is
how that would die.

> **How this used to work.** Statuses were a Java enum, so the six that shipped were the six you
> got. `BoardColumnSeed` turns them into rows on first startup — same names, same order, same
> colours, per project — and everything is editable from then on.
>
> **Assigned and Reopened were removed, and Fixed became Ready for Test.** *Assigned* said nothing
> the assignee list does not already say — a bug with somebody on it is visibly somebody's. *Reopened*
> was a status describing an event: a bug that comes back is simply In Progress again, and the
> history already records that it went round twice. On first startup `StatusMigration` rewrites the
> stored values in plain SQL — Assigned → Open, Reopened → In Progress, Fixed → Ready for Test —
> before anything reads a bug, so no row is left holding a value the app cannot parse.
>
> Assigning somebody no longer moves the status. Moving a bug along is a decision of its own.
>
> `GET /api/bugs/options` returns statuses as a map of project to that project's columns, each given
> as the key a bug stores alongside the wording a person reads — a script setting a status has to
> know which board it is writing to.

## Deleting, and undoing it

**Delete is reversible.** A deleted bug goes to the **trash** (the bin in the navbar, with a count):
its row, comments, files and history all stay exactly where they are, and every query simply stops
looking at them. It leaves the board, drops out of searches, and stops blocking anything — and
restoring puts all of that back untouched.

The toast that announces a delete carries an **Undo** for twelve seconds. The trash page has Restore
per bug, and **Delete for good**, which is the only irreversible button in the app and says so.

## Assignees, blockers and mentions

**A bug can have several people on it.** Assignees are a list (`bug_assignees`), not a single name,
because a fix usually needs a developer and a tester at the same time. They are picked with
checkboxes — ticking none unassigns the bug. Filtering by a person finds every bug they are on, not
just the ones they are first on, and the workload counts work the same way.

> The JSON API still exposes `assignedTo` — now the **first** assignee — alongside the new
> `assignees` array, so existing scripts keep working. `POST /api/bugs/{id}/assign?assignedTo=X`
> still sets a single name. On first startup `AssigneeMigration` copies each bug's old
> `ASSIGNED_TO` value into the new table; the old column is left in place rather than dropped.

**A bug can be blocked by another open bug.** Pick one under *Blocked by* on the detail page, or in
*More* on the form. Only bugs that are still open are offered — a closed bug cannot block anything,
and a blocker that is later fixed stops counting without you having to clear it. Blocked bugs carry
a ⊘ marker on the board and in the list, ahead of the title, and the bug itself shows a link to
whatever is in the way. Deleting a bug clears it from anything it was blocking.

**Comments, project pages and sheet cells take `@mentions`.** Type `@` and the team list appears;
the person you pick gets a notification — pointing at the comment's bug, or at the document, as the
case may be. Matching happens on the server against the roster as well, so a name typed by hand
still notifies — and because names have spaces, mentions are matched longest-first, so `@Anita Rao`
is never read as `@Anita`.

A document saves itself as you type, and every one of those saves finds the same `@` still sitting
in the text, so a tag in a document is only rung **once an hour** per person per document. A comment
is a single event and keeps its thirty-second window. Deleting a document takes its mentions with
it, rather than leaving a bell that opens a page which is not there.

## Team

**Reported By** and the assignee list are filled from a `team_members` table rather than typed by
hand — so names are spelled one way and filtering by assignee actually works. The 18
members are seeded on startup, matched on email and inserted only if missing, so the seeder is safe
to re-run and new names can just be appended to `TeamMemberSeeder`.

Manage them under **Settings → Team** (`/team` redirects there):

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
| Coloured initials | a project or a person — the same name always gets the same colour |
| Green / red panels | expected vs actual result |
| Environment tag | QA is neutral, UAT violet, Production red — a production bug should look scarier |
| Timeline rail | the history trail, colour-coded by the kind of change |
| Doc tile colour | what a document *is*, on its own axis rather than a status: indigo page, cyan sheet, amber folder, slate file, blue link |

Attachments are stored under `data\attachments\` with random names, and the metadata in the
database. Allowed types and the 10 MB ceiling are set by `bugtracking.attachments.*` in
`application.properties`.

Light and dark themes follow the OS, and the ☾ button in the navbar overrides that (remembered in
`localStorage`). Keyboard: **/** focuses search, **n** raises a bug, **s** folds the stats open,
**p** opens the project switcher (opening the stats panel first, since that is where it lives),
**Esc** closes whatever is on top. All animation is skipped for
anyone with "reduce motion" set.

### Chrome

There is **one navbar and no sidebar**. It carries the brand, Board, Documents and Settings, then
the notification bell, the theme button and who you are signed in as. Everything personal — *Assigned
to me*, *Raised by me*, sign out — is behind your name; everything administrative — projects,
team — is behind Settings. A page below it is just its own topbar and its content.

**Switching project happens in the stats panel.** The board's topbar shows the project as a
heading, and the line of counts beside it (`24 bugs · 9 open · 2 urgent`) is a button: pull it
open and you get the project switcher, the KPI tiles, and the severity and workload
breakdowns. One handle, and it already tells you something before you touch it.

The notification bell opens in place — the eight most recent, **View all →** for the full page.
Clicking a bug goes straight to the bug.

## REST API

Useful if you ever want to raise bugs from a script or a failing test.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/bugs?status=OPEN&severity=HIGH&keyword=login` | list (all params optional) |
| GET | `/api/bugs/{id}` | one bug |
| POST | `/api/bugs` | create |
| PUT | `/api/bugs/{id}` | update |
| DELETE | `/api/bugs/{id}` | delete |
| GET | `/api/bugs/summary` | counts by status and by severity |
| GET | `/api/projects` | the projects a bug may be raised against |
| GET | `/api/projects/counts` | bug count per project, as the switcher shows it |
| GET | `/api/projects/{name}/dashboard` | one project's dashboard numbers |
| GET | `/api/bugs/options` | every dropdown vocabulary: statuses, severities, environments, projects |
| POST | `/api/bugs/{id}/status?status=RETEST&actor=you` | move a bug along the lifecycle |
| POST | `/api/bugs/{id}/assign?assignedTo=dev-team&actor=you` | assign or reassign; the status is left alone |
| GET/POST | `/api/bugs/{id}/comments` | read the thread, or post `{"text":"…","author":"…"}` |
| GET | `/api/bugs/{id}/history` | the audit trail |
| GET | `/api/bugs/{id}/attachments` | attachment metadata |
| GET | `/api/bugs/{id}/docs` | the supporting docs on a bug, without their bodies |
| GET | `/api/bugs/{id}/docs/{docId}` | one document, `content` included — Markdown for a page, `{"cols":n,"rows":[…]}` for a sheet |
| GET | `/api/bugs/notifications` | the 50 most recent notifications |

Filters on `GET /api/bugs`: `project`, `status`, `severity`, `environment`, `assignee`, `keyword`,
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
    model\       Project.java, Environment.java                                the extra vocabularies
                 Comment.java, BugHistory.java,
                 Attachment.java, Notification.java              the things hanging off a bug
                 SupportingDoc.java, DocType.java                pages and sheets on a bug
                 TeamMember.java                                 the people dropdowns
    service\     CommentService, BugHistoryService,
                 AttachmentService, NotificationService,
                 TeamMemberService, SupportingDocService
    controller\  SupportingDocController.java  the doc editor and its saves
                 SettingsController.java  /settings - projects + team on one page
                 TeamController.java      one person's page; the roster redirects
                 ProjectController.java   project actions; the list redirects
                 TeamApiController.java   /api/team
    config\      SampleDataLoader.java   seeds example bugs
                 ProjectSeeder.java      the projects, seeded and kept up to date
                 AttachmentProperties.java  upload dir, size and type rules
                 ProjectColumnMigration.java  carries client values into project
                 FieldDefaultsBackfill.java  same for environment
                 AssigneeMigration.java      one assignee -> the assignees list
                 TeamMemberSeeder.java   the team, seeded and kept up to date
                 SchemaUpgrade.java      widens legacy H2 ENUM columns to VARCHAR
                 S3Properties.java       bucket, region, endpoint, keys
                 S3Config.java           builds the S3 client, only when enabled
                 SupabaseConnectionCheck.java  names any missing Supabase setting
                 ConnectionsStartupLog.java    logs the live database and file store
  .env.example                       credential template; copy to .env
  src\main\resources\
    application.properties
    application-supabase.properties  Postgres settings for the "supabase" profile
    templates\   layout.html             navbar and icon sprite
                 fragments.html          the notification bell popover
                 login.html, settings.html, notifications.html, team-member.html
                 bugs\list.html, bugs\form.html, bugs\detail.html, bugs\doc.html
    static\css\  style.css               design tokens + components
    static\js\   app.js                  theme, menus, mentions, relative times
                 doc-editor.js           the doc editor: Markdown preview, the sheet, autosave
```

The layers go: **controller → service → repository → database**. The controller only handles
web requests, the service holds the rules, the repository talks to H2.

## Note for Selenium practice

Every interactive element has a stable `id`, so this app makes a good local target for writing
WebDriver tests. The ids survive redesigns and feature work — they are part of the contract.

- **Auth:** `login-form`, `email`, `password`, `login-button`, `login-error`, `login-notice`, `logout-button`
- **Nav:** `raise-bug-link`, `notifications-link`, `view-all-notifications`, `theme-toggle`,
  `current-user`, `user-menu`, `board-link`, `settings-link`, `flash-message`
- **List:** `bug-table`, `filter-status`, `filter-severity`,
  `filter-environment`, `filter-assignee`, `filter-keyword`, `sort-by`, `apply-filters`,
  `clear-filters`, `no-bugs`
- **Form:** `bug-form`, `title`, `project`, `severity`, `environment`, `status`, `files`,
  `module`, `description`, `reportedBy`, `assignedTo`, `submit-bug`
  — `stepsToReproduce`, `expectedResult` and `actualResult` were retired when the report
  became a single box: `description` is now the whole report. The columns and the getters
  stay, bugs raised before the change still show those sections on the detail page, and an
  update that does not post them leaves them as they were.
- **Trash:** `trash-link`, `trash-count`, `trash-table`, `no-trash`, `restore-{id}`, `purge-{id}`,
  `undo-delete`
- **Detail:** `bug-title`, `bug-facts`, `bug-severity`, `bug-status`, `bug-environment`, `back-to-board`,
  `bug-project`, `status-menu`, `assign-form`, `assignee-picker`, `save-assignees`, `assign-to-me`,
  `blocked-by`, `block-form`, `block-select`, `assignee-filter`, `edit-bug`, `delete-bug`, `comment-form`,
  `comment-text`, `add-comment`, `comment-list`, `attachments`, `attachment-form`, `file`,
  `upload-attachment`, `history`, `history-list`
- **Notifications:** `notification-list`, `notification-count`, `mark-all-read`, `no-notifications`
- **Settings:** `tab-projects`, `tab-team`, `project-form`, `project-name`, `add-project`,
  `project-table`, `team-form`, `member-name`, `member-email`, `add-member`

`reportedBy`, `blockedBy` and `block-select` are `<select>` elements, not text inputs — drive them
with `new Select(...)`. `block-select` submits its form on change, so expect a page load straight
after `selectByVisibleText`. The Remove buttons in Settings open a `confirm()` dialog, like Delete
on a bug.

**Assignees are checkboxes, not a select.** Open `assignee-picker`, tick the `input[name=assignees]`
boxes you want, and submit `save-assignees`. Ticking none unassigns the bug.

`status-menu`, `assignee-picker`, `history`, the settings tabs and the filter menus are `<details>`
elements: click the `<summary>` to open one, or set the `open` property. The stats panel is opened
by `stats-toggle` — the project switcher lives inside it, so open that before reaching for
`switcher-btn`.

Two things to handle in a test:

- the delete button opens a JS `confirm()` dialog — `driver.switchTo().alert().accept()`
- `project` is a `<select>`, so drive it with `new Select(driver.findElement(By.id("project")))`,
  and remember it is **required** — submitting without it re-renders the form with an error

## Supabase and S3

The app runs on **H2 on disk with attachments in `data\attachments`** unless told otherwise.
Both alternatives are wired up but switched off, so nothing changes until you turn one on.

Credentials go in a `.env` file in the project root, which is gitignored. `application.properties`
imports it with `spring.config.import=optional:file:.env[.properties]`, so anything set there
behaves like a normal Spring property, and real environment variables override the file:

```powershell
copy .env.example .env
```

### Supabase as the database

Fill in the Supabase block of `.env` from **Dashboard > Project Settings > Database >
Connection string > JDBC**, then start with the profile on:

```powershell
$env:SPRING_PROFILES_ACTIVE = "supabase"
mvn spring-boot:run
```

That layers `application-supabase.properties` over everything else: the Postgres driver, the
Supabase host, SSL, and a small connection pool (Supabase counts connections across every client
on the project, so a laptop should not hold a large one open).

Supabase offers three ways in. The **session pooler** on port 5432 is the default here because it
behaves like a plain Postgres server, so Hibernate's `ddl-auto=update` can create the tables the
same way it does on H2. The **transaction pooler** on 6543 is cheaper on connections but has no
session state — use `SUPABASE_DB_DDL=validate` with it. The **direct** connection is IPv6 only
unless you have the IPv4 add-on.

Leave a setting out and startup stops with a message naming it, rather than failing later as a
confusing hostname error. Without the profile, none of this is read and the app is on H2 as before.

### S3 for attachment files

Set `S3_ENABLED=true` and `S3_BUCKET` in `.env`. Leaving the keys blank is the better option
where you can: the AWS default credential chain then reads `~\.aws\credentials` or an instance
role, so no key is written to a file. `S3_ENDPOINT` and `S3_PATH_STYLE=true` point the same client
at anything else that speaks S3 — Supabase Storage, MinIO, R2.

While `S3_ENABLED=false` the client is never built and no credentials are needed.

**The bytes still go to local disk either way.** `S3Config` publishes an `S3Client` and an
`S3Presigner`, but `AttachmentService` does not use them yet — moving uploads, downloads and
deletes across, plus a migration for existing files, is the next step. Turning the flag on today
changes the startup log and nothing else.

### Which one am I actually on?

Two lines at startup, read from the live connection rather than from the settings meant to shape it:

```
c.b.config.ConnectionsStartupLog : Database: H2 2.3.232 (2024-08-11)  jdbc:h2:file:./data/bugtracking
c.b.config.ConnectionsStartupLog : Attachments: local disk at data/attachments
```

A profile that quietly failed to activate otherwise looks exactly like one that worked, until data
goes missing.

## Change the port

Edit `server.port` in `src\main\resources\application.properties`.
