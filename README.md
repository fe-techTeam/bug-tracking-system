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

The first startup gives you an empty tracker: no bugs, no projects, nobody on the team. Everything
comes from the database and is created in the app. See [Accounts](#signing-in) for how the
first administrator gets in. Data is stored in `data\bugtracking.mv.db` and survives restarts.
Delete the `data` folder to start over.

## What you can do

| Action | Where |
|---|---|
| See all bugs + counts by status | `/bugs` |
| Filter by status / severity, search by keyword | the bar at the top of `/bugs` |
| Read the project's numbers | `/bugs?view=stats` |
| Raise a new bug | `/bugs/new` |
| View one bug in full | `/bugs/{id}` |
| Edit a bug | `/bugs/{id}/edit` |
| Change status quickly | dropdown on the detail page |
| Set or clear a due date | the *Due* field on `/bugs/new` and `/bugs/{id}/edit` |
| Rename, reorder, add or remove a board column | a column's ⋯ menu on `/bugs` |
| Say a column means "finished", or who is told when a bug lands in it | the same ⋯ menu |
| Fold a column out of the way | the `«` beside its heading on `/bugs` |
| Delete a bug | detail page |
| Sign in / out | `/login`, and the ⏻ button in the top bar |
| Change your own password | *Your account* in the top-right menu, or `/account` |
| Make somebody an admin, or set their password | `/settings?tab=team` — admins only |
| Turn on email, and test it | `.env`, then `/settings?tab=team` |
| Inspect the raw database | `/h2-console` (sign in first; JDBC URL `jdbc:h2:file:./data/bugtracking`, user `sa`, no password) |

A bug has: title, **description — the whole report, in one box**,
severity (Critical / High / Medium / Low), **environment (QA / UAT / Production)**,
status (whichever column of its project's board it is sitting in), **project**, module,
reported by, assigned to, an **optional due date**, and automatic created/updated timestamps. Each bug also carries
**comments**, **attachments** (screenshots, logs and **screen recordings**, which play in the page)
and a **history trail**.

## Against the BRD

Built from the Business Requirement Document, v1.0 (27-Aug-2026). Where the app stands:

| BRD requirement | State |
|---|---|
| FR-003 Raise Bug, FR-004 View Bug, edit, delete | done |
| Severity (Critical/High/Medium/Low) | done |
| ~~Priority (P1–P4), separate from severity~~ | **dropped** — see below |
| **Environment (QA / UAT / Production)** | done |
| **Lifecycle: Open → In Progress → Ready to test → Testing → Closed, plus On Hold** | done |
| **FR-005 Assign / reassign a bug** | done |
| **Due date, optional** | done — shown on the card, in the list, and sortable |
| FR-006 Update bug status | done |
| **FR-007 Comments** | done |
| **FR-008 Attachments, with type and size validation** | done |
| FR-009 Search and filter — id, title, description, module, project, people, status, severity, environment, assignee | done |
| **Sorting** — newest, oldest, recently updated, severity, status, title | done |
| **FR-010 Bug history** | done |
| **FR-011 Notifications — assigned, ready for test, on hold, closed, @mentions** | done (in-app, and by email when SMTP is configured) |
| FR-002 Dashboard — totals per status, per severity, urgent count | done |
| **FR-001 Login / logout** | done (per-person accounts) |
| **FR-001 User accounts and roles** | done — Member and Admin; see below |

### Signing in, and who administers

There is **one administrator account**, `admin@firsteconomy.com`, and everybody else is a plain
member. It is deliberately not a person: an admin account tied to somebody's name leaves when they
do, and administration is the one thing nobody can grant themselves back.

It is created by `BootstrapAdmin` on a database where no admin can sign in, from
`BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` in `.env` — never from a committed file. The
moment one active admin has a password those values are ignored, so a password changed on
Settings &gt; Team is never undone by a restart, and clearing them once you are in changes nothing.

**The last admin cannot be taken away.** Demoting, deactivating, removing or clearing the password
of the only admin who can still sign in is refused by all four routes, because only an admin can
appoint one and nothing inside the app could undo it.

### The shape of the roles

Two roles, `MEMBER` and `ADMIN`, on the `team_members` row. The line between them is drawn around
the **setup**, not around the work:

- **Everybody signed in** raises bugs, moves them, comments, is assigned, files documents, renames a
  column on the board and ticks somebody onto a project. A tracker that asks permission before
  letting you file a bug is one people route around. Their own password is theirs, on `/account`.
- **An admin also** manages projects, the roster, anybody's password, and who else is an admin.

**Settings is admin-only, page and all** — a member gets a 403, not a page of controls with the
controls taken out. `/account` is the whole of what is theirs. The navbar's *Team* entry opens the
project team drawer, which is daily work and stays for everybody; because its no-script fallback is
Settings, it is marked `.nav-link-js` for members and appears only once scripting has confirmed it
will work. A link that 403s is worse than no link.

So the BRD's per-role *lifecycle* rules — "a developer should not directly close a bug", "only
authorized QA users can raise bugs" — are still deliberately not enforced. The lifecycle is fully
modelled and every column is a row somebody can rename; what is not built is a matrix of who may
move a bug where. A table of checkboxes nobody can hold in their head ends up with everything
ticked, and this is a tool for one company.

Still open from the BRD: the rest of user management (§6), and the future-enhancement list in §19.

## Signing in

Everything except the JSON API is behind a login.

**`team_members` is the users table.** One row per person, with the unique email they sign in with,
the display name, whether they are active, and a BCrypt hash in `password_hash`. A member with a
password can sign in; a member without one is only a name that appears on bugs, which is most of the
roster. There is deliberately no second table for accounts — it would have split one person across
two rows and left every screen asking which half to draw.

**Settings → Team is two screens, not one page.** The first is the roster, read as a list — a row
per person: their name and address on one line, their role, and whether they are still on the team.
A badge marks the exceptions (*Admin*, *Hidden*) and plain text the rule, because the only reason to
look down those columns is to find the rows that differ. Every cell holds a single line, so the rows
share a baseline all the way down. Each row carries one control, *Manage*, and the whole row is that
link.

Nothing on it links to the board. Somebody's bugs are the board's question and the board answers it
well; a filter link out of an administration page is a trapdoor out of the thing you came to do.

**The second is one person's account, and it replaces the roster rather than opening above it.** It
is a list of actions and nothing else: a label, the control that changes it, and no paragraph in
between — password, sign-in, role, on the team, remove. One column for the labels and one for the
controls, so every button on the screen starts at the same x. Administering an account is one thing
at a time; what was here before drew an editor above the table, an add form permanently open below
it and the mail settings under that, and every click landed you back at the top of a page with four
things on it.

The explanations that used to sit under each button are here in the README instead, where they can
be read once rather than every time.

It is a link and a query param (`?tab=team&member=<id>`) rather than a popover in the row: it is
linkable, it survives scripting being off, and `.table-wrap` scrolls horizontally and clips anything
that opens out of a cell. Passwords are a minimum of eight characters. What is stored is the hash
and only the hash — there is no way to read a password back, only to replace it. Making somebody an
admin needs a sign-in first, so that button is disabled until they have a password rather than
letting you find the refusal out by pressing it.

**Adding somebody is a popover off the roster's head** — name, address and an optional password,
which is three fields rather than a panel that sits open under the table for ever. A `<details>`,
like every other popover here, so it works with JavaScript off.

**Deactivate** keeps every bug somebody raised or was assigned exactly as it is; they simply stop
being offered for new ones and can no longer sign in. **Remove** is offered only for a name with no
history behind it, such as a typo — for anybody else the row is simply not there, because a control
that can never be pressed is a control to leave out.

**Your own password is yours.** *Your account* in the top-right menu (`/account`) changes it, and is
the one password path that asks for the current one first — an admin setting somebody's password
cannot know the old one, the owner can, and asking is what stops a walked-away-from session being
enough to lock its owner out. Needing an admin to rotate your own password is how passwords stop
being rotated.

**The last admin cannot be taken away.** Demoting, hiding, removing or clearing the password of the
only admin who can still sign in is refused, because only an admin can appoint one and nothing
inside the app could undo it.

**Accounts are rows in `team_members` and nowhere else.** `SecurityConfig` reads that table and has
no other source — there is no configured account, no in-memory user and no fallback. An address with
no row, or a row with no `password_hash`, cannot sign in. People are added and given passwords on
**Settings → Team**, and roles are set there too.

The one thing that cannot work that way is the *first* admin: only an admin may add a member, so an
empty database is a locked door. Two values in `.env` are the key, and they are unset out of the box:

```properties
BOOTSTRAP_ADMIN_EMAIL=you@example.com
BOOTSTRAP_ADMIN_PASSWORD=<pick one>
```

On the next start `BootstrapAdmin` makes that address an administrator with that password — hashed,
written into the table. It never overwrites a hash that is already there, and it stops looking
entirely the moment one active admin has a password, so a password changed in Settings is never
undone by a restart. Clear both from `.env` once you are in; nothing reads them again.

`.env` is gitignored, which is the point: no password is ever written into `application.properties`,
and the sign-in page has nothing on it to fill in.

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
│ ⬤ Godrej ▾ │ Home Documents Team  │   🗑  🔔  ☾  ⬤ Nishana ▾ │
│ └── the project is the switcher, in the spot a logo would be   │
├────────────────────────────────────────────────────────────────┤
│ 🔍 search   [⚌ Filters ②]         [Board|List|Stats]  + New    │
├────────────────────────────────────────────────────────────────┤
│  OPEN 3      IN PROGRESS 2    ON HOLD 1     READY FOR TEST     │
│  ┌────────┐  ┌────────┐       ┌────────┐                       │
│  │BUG-12 ⊘│  │BUG-9   │       │BUG-4   │    ← drag between     │
│  │ title  │  │ title  │       │ title  │      columns          │
│  │ ▮▮▮▯ ⬤⬤│  │ ▮▮▯▯ ⬤ │       │ ▮▯▯▯ ⬤ │                       │
│  └────────┘  └────────┘       └────────┘                       │
└────────────────────────────────────────────────────────────────┘
```

**One navbar, one topbar, then the work.** There is no sidebar. The navbar carries the project, the
three sections and you; the topbar carries what narrows the board and where to go.

**Home remembers which view you were in.** The first navbar entry is *Home*, not *Board* — the board
is one of three ways to read the same bugs, and naming the way in after one of them made the other
two feel like somewhere you had wandered off to. It is a plain link to `/bugs`, and a URL with no
`?view=` on it means *the way you were last reading these*: pick the list once and Home comes back
to the list, as does switching project and searching, on this visit and the next. The session holds
it, so it survives a walk off to Settings. Anything that means the board specifically says so — the
Stats numbers link to `?view=board`, because "2 urgent" is about those two bugs and not about how you
like to read them.

**The project name is the switcher, and it is the first thing in the navbar** — the slot the app's
own name used to hold. One app does not need to say what it is on every screen; which project you
are looking at is the thing you actually check, and it used to be a bar further down. Click it and
every project drops down — no expanding, no detour through a menu. It is on every page now, not
only the board, because everything it needs was already a global model attribute.

**One bar, then the work.** The search box, one **Filters** button and the view switch are all in
the same row. There used to be three stacked bars — the project, a drawer of counts that folded
down over the board, and a filter bar of its own — which spent about a third of the screen before
the first card. Below 1180px the filters take a row of their own rather than pushing the New bug
button off the edge. The bar does not count what is on screen: "12 shown" was a number nobody
acted on, sitting where the eye lands first.

**Every filter is behind one button, laid out as columns.** Assigned to, Raised by, Severity and
Environment — and on the list view, Status and Order — are columns of a single *Filters* panel,
side by side, each scrolling on its own. The bar used to carry three faces, a "···" for everyone
else, and a menu each for severity and environment: five controls that said nothing at all until
they were opened. The button carries a count instead, so shut it still tells you whether the board
is narrowed and by how much; the chips under the bar say which, and each takes itself off. The
panel hangs off the bar rather than off its own button — a button several hundred pixels into the
row is not something you can hang six columns from without running off the edge. It is still a
`<details>` full of links, so it works with JavaScript switched off exactly as it did.

**The people filters offer the project's team**, not the whole company. A board is worked by the
handful of people on it, and scrolling eighteen names to find one of five is the reason those lists
used to be a "···" menu nobody opened. Whoever the board is currently filtered to is always in the
list even if they are not on the team — otherwise the one control that clears the filter would not
list the thing it clears.

**A column is a heading and the cards under it** — no box of its own. It used to be a bordered,
frosted panel, which made every card a card inside a card: two borders, two radii and two
backgrounds between the page and a title. Columns keep a width you can read a title in and the
board scrolls sideways when there are more than fit; the right-hand edge fades while there is more
over there, driven by `animation-timeline: scroll()` so it needs no script. **Add column** is a
hairline that fills in when the board is hovered, rather than a column-shaped tile sitting there
all day for something you do once a project.

**A card is a title and four facts:** whether it carries files, whether anybody has said anything,
and its severity and environment as words in their own colour. The id, the exact date and the
4-bar meter are a click away on the bug itself — on a card they were noise between the reader and
the title. The two counts are drawn the same way — a paperclip and a number, a speech bubble and a
number — because they are the same kind of thing; "3 attachments" in prose beside an icon-and-number
comment count made one line of a card argue with itself. Up to three faces and a **+3**. **⋯ in the corner moves it** to another column without
dragging, which is the route that works on a phone and from a keyboard; the list opens inside the
card, because the column scrolls and a popover out of it would be clipped.

**An empty column is the same height as a full one**, always, off one `--kcol-h` — a placeholder
that shrank to fit its own two words made the board look ragged, and an empty column is exactly
where you are about to drop something. Dragging creates and removes that placeholder as you go, so
what is on screen after a drag is what a reload would draw. A dropped card does not flash: moving
an element in the DOM restarts its CSS animation, and every card enters with a staggered fade, so
one that had just arrived went invisible and faded back in.

**Drag a card onto the bin to delete it.** The trash in the navbar is a drop target, and it posts
the ordinary delete form rather than fetching — that route answers with the flash carrying
**Undo**, which is the whole reason dropping a bug on a bin is a safe thing to be able to do by
accident.

**The list is a table you can work in, not just read.** *Board | List | Stats* is the same bugs three
ways, and the list is the one that reads down a column:

- **The headings sort, and a second click turns the order round.** The orders were always there —
  the service has answered `?sort=` for as long as the list has existed — but the only way to reach
  them was a column of the Filters panel called *Order*, which is not where anybody looks for it.
  Title, Status, Due, Reported and Severity are links; *Reported* starts marked, because newest
  first is the order the list is in until you say otherwise. Assigned and Raised by stay plain:
  neither has an order behind it, and a heading that looks clickable and is not is worse than one
  that does not. The Order menu is still there and still works, and ticks the order a heading set.
- **Status is a picker, not a label.** Moving a bug is the commonest thing anybody does to one, and
  a list that could only state the status was a list you left — out to the bug and back — to change
  it. It offers the columns of *that row's own project*, so a cross-project list
  (`?assignee=me`) offers each row the right board, and the change lands back on the same list with
  its filters and its order still on. It saves on change; with JavaScript off a *Move* button
  beside it submits the same form.
- **The whole row opens the bug.** The title is still a real link, so ⌘-click, middle-click and
  JavaScript-off are unchanged — this only widens the target, and a click that lands on a control
  is left to the control.
- **Severity reads as words, on the right**, next to the 4-bar meter and above the colour rail down
  the row's left edge. Colour on its own is not a legend: nothing on the row said which end of the
  scale red was.
- **Copy as Markdown is a row action**, in its own column at the end, rather than trailing the title
  where it read as part of the sentence it was sitting in.

**A project with no bugs still shows its board.** The columns are the first thing a new project
needs to see — the process it is going to work through, and the menus that rename it — so an
illustration in their place hid the wrong thing. With nothing to scroll they share the full width
and stand full height instead of sitting in a 260px strip with the screen blank beside them. An
empty column says *Empty* and stops there; it used to explain how a bug gets into a column, once
per column, six times over.

**Board, list or stats.** The board is one column per status and you drag a card between them; the
list is the same bugs as a table; **Stats** is the same project answered as numbers, and only the
two that are worth a page: how many bugs are sitting in each column, and how many are due inside
the next week, month and three months. The due windows count open bugs only, they nest — anything
due this week is also inside the month and the quarter — and anything already late is counted in
all three, because work that has slipped is still work that is owed. Each column links to the bugs
behind it and lands you back on the board; the due rows do not, because the board cannot be
filtered to a date. The filters are hidden on Stats on purpose: those are the project's totals, and
a half-applied filter would make them quietly answer a different question than the one they name.
Either way a card is clickable anywhere on it, not just its title. The columns themselves belong to
the project and are yours to change — see
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
team list. None are seeded — the table starts empty and every project in it was added in the app,
under **Settings → Projects**. There is no list of example names anywhere in the source, so nothing
can reappear after you delete it.

Manage them under **Settings → Projects**: add, hide (stops being offered, existing bugs
untouched), or remove (only for a project with no bugs). Raising a bug from inside a project
pre-selects it. `/projects` still redirects there, so old links keep working.

**Adding one is a popover off the table's head**, the same shape as *Add member* on the Team tab —
you add a project every few months and read the list every time you come here, so the form is not
sitting open above it by default. Inside it, who works on the project is a **searchable** list of
the roster: a company is twenty rows, and reading twenty is slower than typing three letters.

**The switcher lists projects, and nothing but projects.** It used to add back any project name a
bug happened to carry, so that a bug filed against something never created here still had a board
to appear on — and the result was a switcher listing things that were not projects, could not be
opened in Settings and could not be deleted, because there was nothing there to delete. "I removed
it and it is still showing" is exactly what that looks like from the outside. Hiding one now takes
it out too, which is the other half of the same fix: hiding a project that had bugs on it used to
do nothing at all, because the pass over bug names put it straight back.

Nothing is lost by a name not being in the switcher. The bug keeps it — bugs name projects as text
on purpose, so history is not rewritten — and it is still found by search, by ⌘K and at
`/bugs?project=<name>`. Editing it offers the real project list, which is how it gets filed
somewhere that exists.

**A project has a team.** The add form picks its people while the project is being made, and the
Team column on each row opens an editor for changing them later — every box is submitted, so
unticking somebody takes them off. It is a real relation (`project_members`, with foreign keys both
ways) rather than a list of names, because who is on a project is a live fact rather than history:
take somebody off and they are off, with no name stranded behind them.

Nothing on a bug depends on it. Bugs name people as text, so removing somebody from a project leaves
every bug they are on exactly as it was — the **Stats** view just starts listing them as *off team*,
which is usually the thing worth knowing.

Bugs store the project **name** as text, not a foreign key — so a bug on a retired project still
reads correctly, and the edit form keeps showing a project that is no longer on the list rather than
silently moving the bug.

> **Renamed from "Client".** The old `client` field *was* this concept, so it became `project`. On
> first startup the values are copied across and the old column is dropped — see
> `ProjectColumnMigration`. **The JSON API field is now `project`, not `client`**, and
> `GET /api/bugs/clients` is gone; use `GET /api/projects`. Update any scripts.

> **Priority was removed.** Severity and priority answered nearly the same question and were
> filled in by the same person at the same moment, so one of them was always noise. Severity is what
> survived, and "urgent" — the Stats tile, a person's workload — now means
> **Critical or High and still open** rather than P1 + P2. The `PRIORITY` column is left in the
> database rather than dropped, so nothing is lost if it is ever wanted back; the JSON API no longer
> accepts or returns it.

## A bug's page

Two columns, and each answers a different question.

**Left — what the bug says**, as one card: the title, and under it the report exactly as it was
written. It is one story and it is read in one pass, so it is neither broken into panels nor asked
for in pieces — raising a bug is one box, and this is that box. Under it, the supporting docs, and
then the comments.

**Right — what the bug *is*.** The first card carries its number, a status dropdown, and every fixed
fact (severity, environment, module, project, who raised it, when). It is never collapsed, because
it is the first thing you look at. Below it: who is on it, what is blocking it, the attachments, and
the history — which *is* collapsed, being reference rather than the job.

**Edit and Delete** sit together in the top right, where actions on the whole bug belong.

Images attach and then **show**: a thumbnail on the bug, opening in a viewer over the page rather
than swallowing the tab. Any attachment can be removed, and that is recorded in the history like
every other change.

### The attachment viewer

A screenshot is usually the whole bug report, and the thing you need out of it is eight pixels of a
stack trace. So the thumbnail opens a viewer, not a bigger picture:

- **The rest of the evidence is beside it.** ← and → step through the set, a filmstrip along the
  bottom jumps straight to one, and the bar counts where you are. Comparing the screenshot before
  the fix with the one after is an arrow key rather than two round trips.
- **The set is what it says it is.** Everything on the report is one gallery; a comment's own files
  are a gallery of their own, so opening a screenshot somebody replied with does not walk you off
  into the report's.
- **A picture zooms.** The **+** / **−** buttons, the scroll wheel, `+` `-` and `0`, or a
  double-click — into whatever is under the pointer, not into the middle and away from the thing you
  were looking at. Past the edge of the window it drags to pan, and the percentage in the bar is a
  button that fits it back.
- **A clip plays** with the browser's own controls, which are better at being a video player than
  anything here would be, plus **space** for play/pause. It stops the moment the overlay closes,
  rather than talking over a page you have shut. Screen recordings still play inline on the bug as
  well; the ⤢ in the corner of one is what opens it big.
- **Escape** closes, and so does a click beside the picture — but never a click *on* it: on a phone
  the first tap to look closer used to throw the thing away.

None of it is load-bearing. Every thumbnail is still a real link to the file, so with JavaScript off
a click is the attachment in a new tab, exactly as it was before there was a viewer.

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

| Column | Colour | |
|---|---|---|
| **Open** | red | raised, nobody on it |
| **In Progress** | blue | somebody has it |
| **Ready to test** | brown | handed over |
| **Testing** | violet | with QA |
| **Closed** | green | done |
| **On Hold** | slate | parked — last, because it is a siding rather than a stop on the line |

and can then run whatever it actually runs: rename them, recolour them, drag them into a different
order, add a *Waiting on client* or a *Sign-off*, remove the ones it does not use. Two projects
need not agree — a client engagement wants a sign-off step, an internal tool does not.

**A label is not a key.** *Testing* is stored as `RETEST` and *Ready to test* as `READY_FOR_TEST` —
the keys are the old enum constant names and they never change when a label does, because every bug
already in the database holds one and a key that moved would strand every bug carrying it. Renaming
a column is a word on a column, not a new one.

**Changing these defaults reaches boards that were never edited.** `DefaultColumns` only decides
what a *new* project opens on, which would leave everybody who never chose otherwise on the old
palette — so `BoardColumnRestyle` repaints, once, any board that is still *exactly* the set that
shipped before: same six keys, same six labels, same six colours. One edit anywhere in a board and
it is somebody's, and it is left alone entirely. Only the label, the colour and the order are
touched; `status_key` never is, so no bug moves.

**Where.** A column's own **⋯ menu** on the board is the whole editor: it renames the column, says
whether its bugs count as finished, picks who is told when a bug lands in it, moves it left or
right, and removes it. The dashed tile at the end of the board adds one. Dragging a column's head
reorders the board; the arrows in its menu do the same thing without scripting.

Settings has **no Board tab**. There was one, holding the same controls in a place nobody is
standing when they notice a column is wrong — you are on the board when that happens, so the board
is where they live.

**Fold a column away.** The `«` beside a column's heading collapses it to a 46px strip with its name
running up the side and its count still on it; the same button opens it again. Seven columns is a
board you scroll, four columns and three strips is a board you read. Nothing about a bug changes and
nothing is posted — it is a view, so it is remembered in that browser and **per project**, and with
JavaScript off the button is not rendered at all and every column is open.

**Renaming is free.** A bug stores the column's *key*, which is fixed when the column is created and
never rewritten, so a rename changes the wording and nothing else — no bug moves, and the history
trail still reads with the wording that was on screen when each move happened.

**Two settings that carry weight:**

| | |
|---|---|
| **Bugs here are** *work in hand* / *finished* | This is what "still open", the urgent count and the list of bugs you may pick as a blocker all read. A column you invent needs it set correctly, and a project may have as many finished columns as it likes |
| **Tell, on arrival** | Who gets a notification when a bug lands here: nobody, whoever raised it, whoever is on it, or both. Seeded so the six defaults behave as they always have — Open and In Progress announce nothing, On Hold tells the people on it, Ready to test and Testing tell the reporter, Closed tells everyone |

**Removing a column asks where its bugs go**, and moves them there — the trashed ones too, so a bug
restored later does not come back into a column that no longer exists. The last column on a board
cannot be removed; there would be nowhere for a bug to be. Moving a bug to a project whose board has
no such column lands it in that board's first column.

**Colours are chosen, not typed.** A column picks one of ten tokens the stylesheet owns
(`ColumnColour`), because the board only reads as a journey — cool while it waits, warm when it
needs a person, green when it is done — while every column is on the same scale. A free hex field is
how that would die. A column keeps the token it was created with; there is no picker, because the
colour is not drawn on the board itself — it paints the status badge in the list view and the bars
on Stats.

**Where the colour is seen:** a 2px rule under the column's heading on the board, mixed down so a
row of them reads as a track through the process rather than as a row of highlighters. That is the
one piece of colour on the board's chrome, and it is why the picker matters — two columns painted
the same are two columns that look the same, which is the one thing the rule is there to stop. The
cards below carry *severity* as colour; the heading carries status. Two colour systems on one
surface would cancel each other out, so the column has no tint of its own.

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

## Due dates

**Optional, and staying optional.** Most bugs never get one. A *required* due date is a field
people fill in with a guess to get past the form, and a board where every card carries a made-up
date is a board where no date means anything — so it is blank unless somebody meant it.

A **date, not a timestamp**: "by Friday" is what anybody actually means, and an hour on it would be
a precision nobody sets and everybody has to read past.

**Where it shows.** Quietly on the board card, in the list's *Due* column, and on the bug's own
rail — all three from **one fragment** (`fragments :: due`), so a date can never read as urgent on
one screen and calm on the next. No icon and no filled pill: on a 230px card, beside a paperclip
count and a comment count, a clock glyph and a coloured capsule were two more decorations competing
with the title. A word and a colour say it on their own — *Due 12 Sep*, *Today*, *Late 1 Sep* — and
the word is there as well as the colour because red alone is not something everybody can see.

It earns colour in exactly two states, because those are the only two anybody can act on:

| | |
|---|---|
| **Due today** | warm — the same orange the severity scale uses for *needs a person*, because today is the day it needs one |
| **Past due, and still open work** | a small red pill. Small on purpose: a full badge would out-shout the title, which is the thing you are scanning a column for |

**"Late" is a question for the board, not for the date.** A bug closed last month that was due last
week is *done* — painting it red would say somebody should act when nobody should. So every screen
asks `BoardColumns.late(bug)`, which is `pastDue` **and** the column it is sitting in still counting
as work in hand. `Bug.isPastDue()` answers only about the date and is deliberately named so, and
the same rule reaches the Markdown copy and the notification email, which append *(overdue)* under
exactly the same condition.

**Sort by it** from the list's *Due* heading, or *Due soonest* in the Order menu. Bugs with no due
date sort **last**, not first: a bug nobody put a date on is not the most urgent thing on the board,
which is what nulls-first would claim. Clicking the heading again turns the order round — latest
first — and the undated ones are *still* last, because no date is not a date at either end of the
range.

**Set it without opening the edit form.** The bug's rail has a date box, **Save** and — only when
there is one to remove — **Clear**, posting to `/bugs/{id}/due`. Going through the edit form to
change one date means re-submitting the title, the report and every dropdown, any of which could
carry a stale value from the moment the form was opened; that is the same reason status, assignees
and the blocker each have a path of their own. It is a plain form, so it works with scripting off.
The field is still on the raise and edit forms too, which is where a date gets set as the bug is
written up.

Changing one is recorded in the history like everything else, and reads as a sentence — *set it due
30 Sep 2026*, *moved the due date from … to …*, *cleared the due date, which was …*.

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

### Replies, and comments you can change

`bug_comments` grew `parent_id` (the comment being answered, null for one that opens a thread) and
`edited_at` (null until somebody changes the words). `parent_id` is the comment actually answered,
at any depth — a reply can be replied to, and so can that. It used to be flattened to the top of
its thread, which made the third message in an exchange look like another answer to the first.
`V5__comment_threads.sql` adds both and an index on `parent_id`, which every render of a bug page
groups by. No migration was needed to un-flatten it: the column already held exactly this.

### Fenced code, in a report or a comment

Anything between ``` ``` ``` fences is drawn as a real block: monospaced, **never wrapped**,
scrolling inside itself, with **Copy** and — when the contents parse as JSON — **Format**, which
indents it in place and toggles back. A developer pasting a response body onto a bug is pasting
evidence, and evidence rendered as a paragraph, wrapped and with its indentation collapsed, is
evidence nobody can check.

Nothing is stored differently. The fences are in the text the way the author typed them, the server
keeps them verbatim, the Markdown copy already left them alone, and with JavaScript off they are
visible as ``` ``` ``` — the same information. It is a rendering, not a format.

The **Code** button beside the paperclip (and under the report box on the raise form) wraps the
selection in a fence, pretty-printing it on the way if it is JSON. Typing the fences by hand works
exactly as it always did, which is what happens with scripting off.

### Files on a comment

`bug_attachments` grew one nullable column, `comment_id`: null means the file is on the report,
anything else means it arrived with that comment. One table rather than a second beside it — a
screenshot pasted into a reply is the same thing as one on the report, with the same bytes on disk,
the same route serving it and the same lightbox opening it; all that differs is what it hangs off.
`bug_id` stays NOT NULL either way, so deleting a bug still takes every file with it without
walking the thread first. `V4__comment_attachments.sql` adds the column and an index on it, because
every render of a bug page asks this table twice.

### Screen recordings

A clip is the same evidence a screenshot is, so it is **played where a screenshot is shown** —
inline on the report and under the comment it was said in, not listed as a file to go and fetch.
`mp4`, `webm`, `ogv` and `mov` get a player; `mkv` and `avi` are accepted and handed over as
downloads, because a browser will not play them and a black box with a broken play button is worse
than a link.

Video has a **ceiling of its own** — 64 MB against 8 MB for everything else
(`bugtracking.attachments.max-video-size-bytes`). Eight megabytes is about twenty seconds of a
screen recording, while a *screenshot* arriving at 60 MB is somebody uploading the wrong thing, and
the narrower limit is what tells them so.

The serving route answers **Range requests** — Spring does that itself for a
`ResponseEntity<Resource>`, but only if nothing has already declared a `Content-Length`, so it sets
none. Without that a browser gets the whole file back for every seek, and Safari will not start the
video at all.

### One note on `[hidden]`

`[hidden]` is only `display: none` in the UA stylesheet, so **any** author rule that sets `display`
beats it — and that is a silent bug every time. The assignee search hid its rows by setting the
attribute and `.pick-opt`'s own `display: flex` quietly won, so typing filtered nothing. There is
now one `[hidden] { display: none !important; }` near the top of `style.css`; do not work around it
per-component.

### One note on enums

Every enum this app stores is written as **VARCHAR**, never as a native database enum, and every
`@Enumerated` field carries `@JdbcTypeCode(SqlTypes.VARCHAR)` to make sure of it. Left to itself
Hibernate maps an enum onto H2's own ENUM type, which bakes today's constants into the *column
type* — so adding Red and Brown to the colour palette made every board fail with "Value not
permitted for column", and `ddl-auto=update` will not widen a type that already exists.
`SchemaUpgrade` converts the columns older versions created; Postgres has been `varchar` since
`V1`. Adding a constant must never need DDL.

## Team

**Reported By** and the assignee list are filled from a `team_members` table rather than typed by
hand — so names are spelled one way and filtering by assignee actually works. Nobody is seeded: the
table starts empty and every person in it was added in the app, by an admin. It is also the accounts
table — see [Accounts](#signing-in) — so a row with a password can sign in and a row without
one is only a name that appears on bugs.

Manage them under **Settings → Team** (`/team` redirects there), or in the **team drawer** on the
board, which carries the same forms with a way back to the board you opened it from:

| Action | What it does |
|---|---|
| Add | Name + email, and optionally a password — a popover off the roster's head. A repeat email renames that person rather than making a second one. |
| Set a password | A field on their own screen — at least 8 characters. That is what turns a name into an account. |
| Remove sign-in | Takes the account away; the person stays on the roster and on every bug they are named on. |
| Make an admin | Only once they can sign in: a badge on somebody with no password would do nothing but mislead the roster, so the button is disabled and says why. |
| Deactivate | Stops offering them in the dropdowns, and stops them signing in. Every bug they raised or were assigned is untouched. |
| Remove | Only offered for someone named on **no** bug — for a typo or a mistaken entry. Anyone with history must be hidden instead, so their bugs keep making sense. |

Everything but *Add* lives on that one person's screen, reached from their row — one action per
row, and no row for an action that is not available.

Bugs store the person's **name** as text, not a foreign key. That keeps bugs raised before this
table existed readable, and means renaming or hiding somebody never rewrites history. If a bug
holds a name that is no longer on the team — an old value like `dev-team`, or someone since
hidden — the edit form keeps showing it, so opening an old bug never silently reassigns it.

`GET /api/team` returns the active members; `?activeOnly=false` returns everyone. It never returns
`password_hash` — the field is `@JsonIgnore`d, and `/api/**` is open, so that is load-bearing rather
than tidiness.

The same table is the users table — see [Signing in](#signing-in) — and a project's own team is a
separate, live relation on top of it, see [Projects](#projects).

## The interface

The UI leans on visual cues rather than text alone:

| Cue | Means |
|---|---|
| Colour temperature | severity — cool cyan (Low) through to hot rose (Critical) |
| The 4-bar meter | severity again, as a count: 1 bar Low, 4 bars Critical |
| Coloured rail down a row | that bug's severity, readable before you read the title |
| Stacked bar on the dashboard | the shape of the whole queue by status |
| Coloured initials | a project or a person — the same name always gets the same colour |
| Environment tag | QA is neutral, UAT violet, Production red — a production bug should look scarier |
| Timeline rail | the history trail, colour-coded by the kind of change |
| Doc tile colour | what a document *is*, on its own axis rather than a status: indigo page, cyan sheet, amber folder, slate file, blue link |

Attachments are stored under `data\attachments\` with random names, and the metadata in the
database. Allowed types and the 10 MB ceiling are set by `bugtracking.attachments.*` in
`application.properties`.

**Copy markdown** hands the whole bug to something else. The button is on the bug page, on every
board card and on every list row; it copies one Markdown document — the facts, the report, what is
attached, every supporting page and sheet, and the whole comment thread — which is what you paste
into Claude when you pick the bug up. The button is a
real link to `/bugs/{id}/markdown`, so ⌘-click (or JavaScript being off) opens the same Markdown
in a tab to be copied by hand.

**The bug page and the raise form are the same screen.** Reading a bug and writing one used to
look like two applications — a wide report card with a rail of small cards beside it, against a
stacked form. Both are now the same two columns: what the bug *says* on the left, what it *is* in
the rail, and the only difference is that on the bug page the fields are already answered. The rail
holds Status, Assigned to, Blocked by and the fixed facts as sections of one card, exactly as the
form's rail holds the fields you pick.

Besides the report and its files, the page carries the **comment thread** and **supporting docs** —
and docs is one quiet line that opens, because most bugs never get one and a full panel with two
buttons above the comments said otherwise. History is still there, as a shut fold at the foot of
the rail.

**Comments are a thread, and so are replies.** Oldest first, and every comment can be replied to —
including a reply, and a reply to that. `parent_id` holds whatever was actually answered, at any
depth.

**The drawing is flat: one comment, and everything said under it as one run, indented once.** A
tree drawn as a tree is a staircase off the right-hand edge of the column, and an indent that has
to stop somewhere leaves the page explaining two things at once — how deep this is, and how deep it
is allowed to look. So the depth lives in the data and the run reads in the order it was said.

**Reply opens the box already tagging everybody in that exchange**, with the caret after them.
Answering the comment that opened a run tags its author; answering somebody who was themselves
answering tags **both** — `@Ajay @Nishana R ` — so replying into a conversation keeps the people in
it looped in rather than quietly narrowing it to two. The chain is deduplicated, so two people
going back and forth stay two names however long they go on, and you are never in your own list.

Who answered whom is carried by those mentions, in the words, where the answer is. It was a small ↳
arrow beside the name, which is one more mark to learn to read and told nobody anything; a mention
is one every reader already knows, and it *notifies the people* like any other. Written by the
server, so they are there with scripting off — `app.js` only moves the caret past them.

Posting lands you on **the comment you just wrote** (`#c{id}`), which briefly lights up. The count
beside the heading is everything said, not just what opened a thread.

Your own comments carry **Edit** and **Delete**; the server checks that you wrote it on the way in,
because hiding a button is a courtesy rather than a rule. Deleting one takes **everything under
it**, however deep, and every file on any of them, bytes on disk included — a grandchild left
behind would point at a parent that is gone, which is a comment no page can ever draw again.

**The @ menu works in every box that takes a comment**, the reply and edit boxes included. It used
to be bound to the one at the top of the section, which is the box you never reply from.

**Thumbnails show the whole picture.** Every preview — on the report, in a comment — is
`object-fit: contain` on its own ground at a 4:3 tile. `cover` crops a full-page screenshot to a
band through its middle, which is the half with none of the point in it.

**Comments take attachments.** A screenshot is very often the whole comment — "it still does this,
look" — so the box has an **Attach** button beside Comment and what you post arrives under your
words rather than in the report's own file list, where it lost the thing it was replying to. A
picture shows as a thumbnail that opens in the lightbox; anything else is a line with its size. One
column on `bug_attachments` rather than a table of its own: same bytes, same route, same lightbox,
and `bug_id` stays set so deleting a bug still takes every file with it.

**The raise form is two columns.** What you write is the left 65% — the title, the whole report in
one box, the screenshot — and everything you pick from a list is a rail beside it: reported by,
assigned to, severity, environment, and *More* for module, blocker and (when editing) status. It
used to be six stacked sections, so filing a one-line bug meant scrolling past Classification and
People to reach the button; the button now lives at the foot of the rail, which follows you down.
There is no **Project** field: you raise a bug from a board, so the project is a hidden input
carrying the one you were standing on.

**Quick search** is **⌘K** (**Ctrl+K** off a Mac), from any page and from inside a half-written
comment — every project at once, rather than the one you are standing in. It lists the most recent
bugs the moment it opens; typing searches titles, descriptions, modules, project names, the people
on a bug and a bare bug id, so `412`, `BUG-412` and `nav values` all land. Arrows move, **Enter**
opens, **Esc** closes, and the last row hands the same words to the board's own filter, where they
can be narrowed further, sorted, bookmarked and shared. The chord is the only way in — there is no
second search box in the navbar, because the board's own filter box is the search: it owns the URL,
it survives JavaScript being off, and it is what the palette's last row hands the words to.

Light and dark themes follow the OS, and the ☾ button in the navbar overrides that (remembered in
`localStorage`). Keyboard: **⌘K / Ctrl+K** searches every project, **/** focuses the search box on
the page you are on, **n** raises a bug, **c** copies the bug you are
looking at as Markdown, **s** opens the stats view,
**p** opens the project switcher,
**Esc** closes whatever is on top. Everything but ⌘K is a bare key and so is off while you are
typing; the chord is the one that is not, which is the point of it. All animation is skipped for
anyone with "reduce motion" set.

**Every page carries a breadcrumb.** A back arrow says "the way you came", which is only the same
thing as "where this sits" when you arrived by the obvious route — follow a link out of a
notification and it says nothing at all. The trail says both: Board → project → BUG-12 → Edit, each
step a link but the last. It is one fragment (`fragments :: crumbs`) taking a list of label/href
pairs; a step with a null label is dropped, so a form that is raising rather than editing simply
has one crumb fewer.

**Back means back.** The arrow in every topbar is just an arrow now. It used to say *Board*, which
is only where you came from if you arrived the obvious way — reached from a notification, a search
result or the trash it took you somewhere you had never been and called it going back. Its `href`
is still that sensible default, which is what a modified click opens and what a browser with
JavaScript off follows; with scripting on, a page you reached from this site goes back to it.

### Chrome

There is **one navbar and no sidebar**. It carries the project switcher, then Home, Documents and
Team, then the trash, the notification bell, the theme button and who you are signed in as. Your
name holds the account and nothing else — **Settings** and **Sign out**. It used to repeat the
section links and carry *Assigned to me* and *Raised by me* as well; the sections never leave the
bar (they become icons under 900px, never a menu), and both of those are one click inside *Filters*
on the board, which is where the rest of the filtering already is. A page below the bar is just its
own topbar and its content.

**There is no page for one person.** It listed what they raised and what they are carrying — both
of which are the board, filtered, and the board says it better. Every face and every name links to
`/bugs?assignee=<name>` instead.

**Team opens a drawer.** It opens on the plain list of who is on this project, with an **Edit**
button that turns the same rows into tick boxes and brings everybody else in underneath; **Add a
new member** sits under the excluded list, wearing the same row with a dashed + where a face would
be. Ticking is the edit — every box is submitted, so unticking somebody takes them off — and Save
returns you to exactly where you opened it from, filters and all.

**A drawer floats.** It is inset on all four sides with a radius and a shadow, rather than welded
to the right-hand edge where it read as another edge of the browser, and it is 520px wide. Behind
it is a flat dim and nothing else — no blur, no gradient: a ramp reads as a smudge rather than as
depth, and blurring a whole page to put a panel over it is expensive for something the panel's own
shadow already says. On a phone it comes up from the bottom instead.

**Filing stays in the drawer.** New folder, link and upload post by fetch and redraw the panel from
the server, rather than following the redirect to the documents page and taking you off whatever
you were reading. A new **page or sheet** is the exception and is marked `data-leave`: it opens in
its editor, which is where you were going anyway.

Neither drawer has a **Full page** button. Everything either one is for is in it, and a button that
leaves for somewhere else is an admission that it is not. Passwords, hiding and removing a member
are still on Settings → Team; opening a page or a sheet still leaves for its editor, which is a
document being written rather than a cabinet being managed. The navbar links are real links, so a
browser with JavaScript off gets those pages rather than nothing.

**Documents are managed from the drawer.** The navbar's Documents link slides the project's filing
cabinet in from the right rather than taking the page away — you fetch one document while reading a
bug, you do not go somewhere to browse. New page, sheet, folder, upload and link are a row at the
top; each row's **⋯** opens rename, move and delete under it. Folders walk you deeper into the
panel; anything you actually open leaves for where it opens in full.

The documents *page* is now the same fragment drawn full width, and exists for two reasons: it is
where **Full page** goes, and it is the whole of documents for a browser with JavaScript off. What
used to be there — a folder rail, a card grid, a search box and a popover per card — was a second
browser to keep in step with this one.

**Switching project is one click on its name.** The board's topbar shows the project as a heading;
click it and the switcher drops down with every project and its bug count. The numbers that used to
live behind a handle next to it are the **Stats** view now — a thing you go and read rather than
something folded above the board all day.

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
| GET | `/api/projects/{name}/team` | who is on one project |
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
                 TeamMember.java, MemberRole.java                 the people dropdowns, and who may administer
    service\     CommentService, BugHistoryService,
                 AttachmentService, NotificationService,
                 TeamMemberService, SupportingDocService
                 EmailService.java        the same notifications, by SMTP
    controller\  SupportingDocController.java  the doc editor and its saves
                 SettingsController.java  /settings - projects, team, board and email, a tab each
                 TeamController.java      one person's page; the roster redirects
                 AccountController.java   /account - your own password, and only yours
                 ProjectController.java   project actions; the list redirects
                 TeamApiController.java   /api/team
    config\      AttachmentProperties.java  upload dir, size and type rules
                 ProjectColumnMigration.java  carries client values into project
                 FieldDefaultsBackfill.java  same for environment
                 AssigneeMigration.java      one assignee -> the assignees list
                 SchemaUpgrade.java      widens legacy H2 ENUM columns to VARCHAR
                 BootstrapAdmin.java     the first admin, from .env, only when nobody can sign in
                 AccountPrincipal.java   who is signed in: name, id, email, role
                 EmailProperties.java    from, base URL, subject prefix, the switch
                 MailConfig.java         the small pool email is sent on
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
                 login.html, settings.html, notifications.html, account.html
                 bugs\list.html, bugs\form.html, bugs\detail.html, bugs\doc.html
                 email\notification.html the one message this app sends
    static\css\  style.css               design tokens + components
    static\js\   app.js                  theme, menus, mentions, relative times,
                                         folding a column, rendering ``` blocks
                 doc-editor.js           the doc editor: Markdown preview, the sheet, autosave
```

The layers go: **controller → service → repository → database**. The controller only handles
web requests, the service holds the rules, the repository talks to H2.

## Note for Selenium practice

Every interactive element has a stable `id`, so this app makes a good local target for writing
WebDriver tests. The ids survive redesigns and feature work — they are part of the contract.

- **Auth:** `login-form`, `email`, `password`, `login-button`, `login-error`, `login-notice`, `logout-button`
- **Nav:** `raise-bug-link`, `notifications-link`, `view-all-notifications`, `theme-toggle`,
  `current-user`, `user-menu`, `board-link` (labelled *Home*; the id is unchanged), `docs-link`,
  `settings-link` (labelled *Team*; it opens the team drawer), `project-switcher`, `switcher-btn`, `switcher-menu`, `switcher-filter`,
  `board-title`, `flash-message`
- **List:** `bug-table`, `filter-form`, `filter-keyword`, `filter-all` (the one menu that now holds
  assignee, reporter, severity, environment, status and order — it replaced `filter-severity`,
  `filter-environment`, `filter-status`, `filter-sort` and `filter-people`), `clear-filters`,
  `no-bugs`, `view-board`, `view-list`, `view-stats`. Each row carries `status-{id}` — the status
  `<select>` that moves that bug without leaving the list — and the row itself carries `data-href`,
  which is what makes the whole row clickable
- **Drawers:** `team-drawer`, `team-drawer-body`, `team-drawer-add` (the **+** popover),
  `drawer-member-name`, `drawer-member-email`, `drawer-member-password`, `drawer-add-member`,
  `save-project-team`, `docs-drawer`, `docs-drawer-body`, `docs-drawer-full`. Both drawers close on
  `[data-drawer-close]` or the scrim; both triggers are the ordinary navbar links to the full page,
  and both panels are fetched (`/team/panel`, `/documents/panel`) rather than rendered into every
  page.
- **Form:** `bug-form`, `title`, `description`, `files` and `submit-bug`, with `reportedBy`,
  `assignee-picker`, `severity`, `environment`, `module`, `blockedBy` and (editing only) `status`
  in the rail beside them. `project` is a hidden input carrying the project you raised from — it
  is only a `<select>` where the form could not work one out, which is a database with no projects
  at all
  — `stepsToReproduce`, `expectedResult` and `actualResult` are gone: the report is a single
  box and `description` is all of it, on the raise form, the edit form and the detail page
  alike. What older bugs held in those three was folded into their description on the way out
  (`LegacyReportMerge` on H2, `V3` on Postgres), so nothing that was written down was lost.
  A JSON payload that still carries the three is accepted and ignores them, so an old script
  keeps working — it just no longer has anywhere to put them.
- **Trash:** `trash-link`, `trash-count`, `trash-table`, `no-trash`, `restore-{id}`, `purge-{id}`,
  `undo-delete`. `trash-count` is `.sr-only` now — the navbar says full or empty by drawing a
  different bin, not by wearing a red badge; nothing in there is waiting to be acted on
- **Detail:** `bug-title`, `bug-severity`, `bug-status`, `bug-environment`, `due-form`, `due-date`,
  `save-due`, `clear-due` (`clear-due` is absent when the bug has no due date), `back-to-board`,
  `bug-project`, `status-menu`, `assign-form`, `assignee-picker`, `save-assignees`, `assign-to-me`,
  `assignee-filter`, `block-form`, `block-picker`, `block-none`, `edit-bug`, `delete-bug`,
  `comment-form`, `comment-text`, `comment-files`, `add-comment`, `comment-list`, `attachments`,
  `attachment-form`, `file`, `upload-attachment`, `docs`, `history`, `history-list`.
  `block-select` is gone — Blocked by is a picker of submit buttons now, not a `<select>`, so each
  option can carry the severity meter and status dot a native `<option>` cannot
- **Notifications:** `notification-list`, `notification-count`, `mark-all-read`, `no-notifications`
- **Settings** (admin-only — a member gets 403 on the page itself): `tab-projects`, `tab-team`,
  `project-form`, `project-name`, `add-project`, `add-project-pop`, `new-team-filter`,
  `new-team-list`, `project-table`. There is no `tab-board` (a board's columns are edited on the
  board) and no `tab-mail` (nothing about mail is set in the app)
- **Settings → Team**, the roster: `team-table` (each row carries `data-href` to that person's
  screen), `manage-{id}`, `no-members` (only when the roster is empty), and the add popover —
  `add-member-pop`, `team-form`, `member-name`, `member-email`, `member-password`, `add-member`
- **Settings → Team**, one person (`?tab=team&member={id}`) — a list of `.act-row` forms and
  nothing else: `member-editor`, `member-done`, `member-password-form`, `member-password-new`,
  `member-clear-password` (absent when they have no password), `member-role` (disabled when they
  cannot sign in), `member-active`, `member-remove` (absent when they are named on a bug)
- **Your account** (`/account`): `password-form`, `current-password`, `new-password`,
  `confirm-password`, `change-password`. Each field has an eye button beside it —
  `button[data-reveal="<field id>"]`, **hidden until `app.js` reveals it**, so a test with
  scripting off will not find one. Clicking it flips that field between `password` and `text`
  and leaves the other two alone; `toggle-password` on the sign-in page is the same control
- **Board columns:** the ⋯ menu's form carries `rename-{id}`, `done-{id}` and `notify-{id}`; each
  column carries `data-column` (its id) and `data-status` (the key a bug
  stores); the fold button is `.kcol-fold` with `data-fold="<status key>"` and is **hidden until
  `app.js` reveals it**, so a test that runs with scripting off will not find it

`dueDate` on the raise and edit forms is an `<input type="date">`: send it **ISO**
(`2026-09-30`), which is what the browser's own picker produces, and an empty string clears it.

`reportedBy` and `blockedBy` (on the raise form) are `<select>` elements, not text inputs — drive
them with `new Select(...)`. On the bug page, Blocked by is `block-picker`: open the `<details>` and
click the row you want, which submits. The Remove buttons in Settings open a `confirm()` dialog,
like Delete on a bug.

**Assignees are checkboxes, not a select.** Open `assignee-picker`, tick the `input[name=assignees]`
boxes you want, and submit `save-assignees`. Ticking none unassigns the bug.

`status-menu`, `assignee-picker`, `history`, the settings tabs and the filter menus are `<details>`
elements: click the `<summary>` to open one, or set the `open` property. The project switcher is
`switcher-btn` in the topbar and opens on its own. The three views are `view-board`, `view-list`
and `view-stats`, which are plain links — `/bugs?view=stats` gets there without a click.

Two things to handle in a test:

- the delete button opens a JS `confirm()` dialog — `driver.switchTo().alert().accept()`
- `project` is a `<select>`, so drive it with `new Select(driver.findElement(By.id("project")))`,
  and remember it is **required** — submitting without it re-renders the form with an error

## Email

Notifications ring the bell in the app and, when SMTP is configured, are **emailed to the same
person**. That is the whole rule: `NotificationService` decides who hears what — including the "you
did it yourself" and "you were told this a moment ago" guards — and `EmailService` only carries the
decision out. There is deliberately no second, parallel set of rules about who gets mail. If a
change ought to email somebody it does not notify, the *notification* is what should be added.

**Off until it is configured.** Two switches, because they fail differently: `bugtracking.mail.enabled`
is this app's, `spring.mail.host` is the server's. With either missing nothing is built, no
connection is opened, and the app behaves exactly as it did before any of this existed.

Fill in `.env` (see `.env.example` for every key and what it means):

```properties
MAIL_ENABLED=true
MAIL_FROM=bugs@firsteconomy.com
APP_BASE_URL=https://bugs.example.com
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=bugs@firsteconomy.com
SMTP_PASSWORD=<an app password, not an account password>
```

**The startup log says what state it is in** — "Off", "On, but no SMTP host is set", or "Sending as
… through …" — on the `Email:` line beside `Database:` and `Attachments:`, because all three are
decided by `.env` and nothing else. There is no Settings tab for it: whether mail leaves the
building is not something the app can change, so a screen for it was a tab holding one button.

Three things make it safe to leave switched on:

- **After the commit, never inside it.** A message about a change that then rolled back cannot be
  recalled, so the send is registered as an after-commit hook and skipped if the transaction fails.
- **It cannot break a save.** Sending happens on a small pool of its own (not the shared executor —
  an SMTP connection can take seconds to time out) and every failure is caught and logged. A dead
  mail server means the bell was the only thing that rang; it does not make raising a bug fail.
- **Nothing is guessed.** A notification is addressed to a display *name*, so the address is looked
  up on the roster. Nobody by that name, more than one person by that name, somebody hidden, or
  somebody with no address on file — all four say nothing rather than guess.

`APP_BASE_URL` is not derived from the request, deliberately: an email is built after the response
has gone, often on another thread, and a link built from "localhost" is a link that works for
exactly one reader.

The message carries the whole rail — status, severity, environment, project, module, who raised it,
who is on it, what is blocking it, the counts, the timestamps — and the report itself, in HTML and
as plain text. `bugtracking.mail.include-details=false` cuts it back to the title and the link.

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
behaves like a plain Postgres server. The **transaction pooler** on 6543 is cheaper on connections
but has no session state. The **direct** connection is IPv6 only unless you have the IPv4 add-on.

Leave a setting out and startup stops with a message naming it, rather than failing later as a
confusing hostname error. Without the profile, none of this is read and the app is on H2 as before.

### Schema migrations (Flyway)

On Postgres the schema is **not** built by Hibernate. It is owned by the numbered SQL files in
`src/main/resources/db/migration/postgres`, applied in order and recorded in the
`flyway_schema_history` table — the same model as Alembic or Rails migrations. Hibernate runs in
`validate` mode there and only checks that the entities and the migrated schema still agree.
(H2 is untouched by all of this: locally, `ddl-auto=update` still builds the schema from the
entities, and Flyway is switched off.)

```bash
./run.sh migrate      # apply pending migrations to Supabase (alembic upgrade head)
./run.sh db-info      # applied vs pending, with checksums   (alembic history/current)
./run.sh db-repair    # fix the history after a failed migrate
```

These read the `SUPABASE_DB_*` block of `.env`; the app also applies pending migrations itself
when started with the supabase profile, so `migrate` exists for doing it deliberately.

To change the schema, add a new file — `V2__add_due_date_to_bugs.sql` — next to the baseline.
Never edit a file that has been applied: its checksum is recorded, and a mismatch stops startup.
`V1__baseline_schema.sql` is written with `if not exists` throughout and `baseline-on-migrate`
is on, so a Supabase database that Hibernate built in the old days is adopted as-is, and a fresh
one gets the whole schema.

The baseline also **enables row level security on every table**: Supabase's Data API exposes the
`public` schema to anyone with the project's anon key, and this app never uses that API — all its
access is JDBC as the table owner, which RLS does not bind. No policies means the Data API is
closed, full stop.

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
