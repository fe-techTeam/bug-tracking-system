# Public bug intake per project

Every project gets a public link. Anyone holding it can raise a bug on that
project without signing in. The bug lands unassigned, in the project's first
board column, marked as external.

## Data

- `projects.public_token varchar(32) not null`, unique index. Generated on
  project creation (`ProjectService.create`) with `SecureRandom`, 32
  URL-safe chars. V12 migration adds the column, backfills every existing row
  with `encode(gen_random_bytes(24), 'hex')` trimmed to 32, then sets
  `not null` and the unique index. Idempotent (`add column if not exists`,
  backfill only `where public_token is null`).
- `bugs.via_public boolean not null default false`. Distinct from `via_guest`
  on purpose: `via_guest` drives the "Share with the client" controls, which
  have no client behind them here.
- `bugs.reporter_email varchar(200)` nullable. The reporter's name goes in
  `reported_by` as text, like every reporter.
- Entity changes: `Project.publicToken`, `Bug.viaPublic`, `Bug.reporterEmail`.

## Form object

`PublicReport` in `service/`, modelled on `GuestReport` and equally closed:
`reporterName` (required, max 80), `reporterEmail` (required, `@Email`, max
200), `title`, `description`, `severity`, `environment`. Nothing else binds.

## Service

`PublicIntakeService`:

- `Optional<Project> byToken(String token)` returns only active projects.
- `Filed raise(String token, PublicReport form, MultipartFile[] files, String clientIp, String userAgent)`:
  - `GuestRateLimit.checkPublic(clientIp)`: new method, 12 reports per hour
    per IP, same sliding window.
  - Project from the token only. Column from the project's board (same
    lookup `GuestService.raise` relies on through `BugService.save`).
  - `reportedBy` = trimmed name, `reporterEmail` = lowercased email,
    `viaPublic` = true, no assignees, no guest id.
  - Description gets one trailing line `Browser: <user agent>` when a user
    agent is present, following Jira's issue collector.
  - Attachments through the same `attach` path and limits the portal uses
    (extract that helper from `GuestService` into a shared package-private
    method or duplicate the call; rejected files come back in `Filed.rejected`).
  - Notifies the project's team through `NotificationService.notify` with
    kind `"public"` and text `<name> (external) raised BUG-n on <project>: <title>`.
    Email follows the bell as it already does.
- `String regenerate(Long projectId)` writes a fresh token. Admin only.
- `String publicUrl(Project)` = `bugtracking.mail.base-url` + `/public/` + token.

## Routes

`PublicIntakeController`, all under `/public/{token}`:

- `GET /public/{token}` renders `public/new.html`, or `public/unavailable.html`
  with 404 when the token matches nothing active.
- `POST /public/{token}` validates, re-renders on errors, otherwise redirects
  to `/public/{token}/done?bug=n`.
- `GET /public/{token}/done` renders `public/done.html`: "Thanks, that is with
  the team as BUG-n" plus a link to raise another.
- `POST /projects/{id}/public-link` regenerates, admin only, flashes the new
  URL and redirects to Settings.

Security: `/public/**` is `permitAll`, listed next to `/login`. CSRF stays on;
the form uses `th:action`. `/projects/*/public-link` joins the ADMIN list.
`SafeRedirect` untouched. No `/api` surface.

`ProjectApiController` JSON must not expose the token: `@JsonIgnore` on
`publicToken`.

## UI

Follow `.claude/skills/frontend/SKILL.md` and `frontend-design:frontend-design`.

- `templates/public/new.html`, `done.html`, `unavailable.html` reuse
  `portal/layout.html`'s shell (no navbar, no user menu, project name as the
  heading) and the `portal-form` panel. Fields in order: your name, your
  email, title, description, severity, environment, screenshots. Copy says
  the report goes to the project's team and nobody sees the email but them.
- Navbar: an `icon-btn` with a link icon after the project switcher, `th:if`
  a project is selected and the user has `ROLE_USER`. `data-url` carries the
  absolute URL; `app.js` copies it with `navigator.clipboard` and shows the
  existing toast/flash pattern "Public link copied". Model attribute
  `currentProjectPublicUrl` from `GlobalModelAttributes`.
- Badge: `fragments :: externalMark(bug)` next to `guestMark`, same shape,
  text "External", title "Raised from the public link", its own token colour.
  Rendered wherever `guestMark` is rendered: board card, list row, detail head.
- Detail page: reporter email shown under the reporter for `viaPublic` bugs.
- List filter `source` gains `public` ("External link"); `BugRepository`
  search gets a `viaPublic` three-valued parameter alongside `viaGuest`.
- Settings projects table: each row gets a "Copy public link" icon and, for
  admins, a "Regenerate" button in a confirm popover.

## Tests

`src/test/java/com/bugtracking/`:

- `PublicIntakeServiceTest`: raise sets project, column, viaPublic, email,
  no assignees; unknown or inactive token rejected; rate limit trips at 13.
- `PublicIntakeControllerTest` (`@WebMvcTest` + security): GET is 200 anonymous,
  bad token 404, POST with missing email re-renders, `/projects/1/public-link`
  is 403 for MEMBER and 302 for ADMIN.
- `SecurityConfigTest`: `/public/x` anonymous is 200-or-404, never 302 to login.

## Out of scope

Matching the email to an existing member, captcha, per-project disable
switch, editing a public report after sending.
