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
| Inspect the raw database | `/h2-console` (JDBC URL `jdbc:h2:file:./data/bugtracking`, user `sa`, no password) |

A bug has: title, description, steps to reproduce, expected result, actual result,
severity (Critical / High / Medium / Low), status (Open / In Progress / Fixed / Closed / Reopened),
**client**, module, reported by, assigned to, and automatic created/updated timestamps.

## Clients

**Client is required** when raising a bug and is picked from a dropdown. The list lives in
`application.properties`, so changing it needs no code change or rebuild:

```properties
bugtracking.clients=Acme Capital,Bluepeak AMC,Northwind Securities,Internal
bugtracking.default-client=Unspecified
```

Bugs raised before this field existed are given `bugtracking.default-client` once, on startup
(they would otherwise fail validation the next time anyone touched them). Edit those bugs to set
the real client. If a bug holds a client no longer on the list, the edit form keeps showing it, so
editing never silently reassigns it. `GET /api/bugs/clients` returns the current list.

## The interface

The UI leans on visual cues rather than text alone:

| Cue | Means |
|---|---|
| Colour temperature | severity — cool cyan (Low) through to hot rose (Critical) |
| The 4-bar meter | severity again, as a count: 1 bar Low, 4 bars Critical |
| Coloured rail down a row | that bug's severity, readable before you read the title |
| Stacked bar on the dashboard | the shape of the whole queue by status |
| Stepper on the detail page | how far along Open → In Progress → Fixed → Closed a bug is |
| Coloured initials | the client — the same name always gets the same colour |
| Green / red panels | expected vs actual result |

Light and dark themes follow the OS, and the ☾ button in the top bar overrides that (remembered in
`localStorage`). Keyboard: **/** focuses search, **n** raises a bug, **Esc** leaves a field.
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
| GET | `/api/bugs/summary` | counts by status and severity |
| GET | `/api/bugs/clients` | the client names a create/update may use |

`client` is required on POST and PUT — omitting it returns **400**.

Example:

```powershell
$body = '{"title":"Login fails","severity":"HIGH","status":"OPEN","client":"Acme Capital","module":"Auth"}'
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
    config\      SampleDataLoader.java   seeds example bugs
                 ClientProperties.java   reads bugtracking.clients
                 ClientBackfill.java     gives old bugs a client on startup
  src\main\resources\
    application.properties
    templates\   layout.html, bugs\list.html, bugs\form.html, bugs\detail.html
    static\css\  style.css               design tokens + components
    static\js\   app.js                  theme, relative times, live preview
```

The layers go: **controller → service → repository → database**. The controller only handles
web requests, the service holds the rules, the repository talks to H2.

## Note for Selenium practice

Every interactive element has a stable `id` (`raise-bug-link`, `bug-form`, `title`, `client`,
`severity`, `submit-bug`, `filter-status`, `filter-severity`, `filter-keyword`, `apply-filters`,
`clear-filters`, `bug-table`, `flash-message`, `change-status`, `update-status`, `edit-bug`,
`delete-bug`, `bug-client`, `bug-severity`, `bug-status`), so this app makes a good local target
for writing WebDriver tests. The ids survived the UI redesign — they are part of the contract.

Two things to handle in a test:

- the delete button opens a JS `confirm()` dialog — `driver.switchTo().alert().accept()`
- `client` is a `<select>`, so drive it with `new Select(driver.findElement(By.id("client")))`,
  and remember it is **required** — submitting without it re-renders the form with an error

## Change the port

Edit `server.port` in `src\main\resources\application.properties`.
