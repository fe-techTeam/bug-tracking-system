# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Spring Boot 3.5 + Thymeleaf bug tracker, Java 21, Maven. No front-end build step.
`README.md` is the long version; this file is the handful of rules that are easy to break.

## Commands

```bash
./run.sh              # foreground; takes the port back if something is already on it
./run.sh bg           # background, logging to app.log
./run.sh stop | restart | status | logs
./run.sh build        # mvn clean package -> target/bugtracking-1.0.0.jar
./run.sh test         # mvn test
```

`run.sh` locates a JDK 21 and sets `JAVA_HOME` for that invocation only; prefer it over
calling `mvn` directly. To run one test class or method:

```bash
mvn test -Dtest=BugServiceTest
mvn test -Dtest='BugServiceTest#movesToColumn'
```

There is no `src/test` directory yet; `spring-boot-starter-test` is on the classpath.

The app serves `http://localhost:8085` and talks to one database: the Supabase
(Postgres) project named by `SUPABASE_DB_*` in `.env`. No profile switches it on and
there is nothing to run without it.

```bash
./run.sh migrate     # apply pending Flyway migrations without booting the app
./run.sh db-info     # what is applied, what is pending
./run.sh db-repair   # after a failed migrate
```

Java changes need a restart; templates and static files are picked up on refresh.

## Comments: one line, and only when required

- **Every comment is a single line.** No multi-line blocks, no Javadoc paragraphs, no banner
  separators, no narration of a function body.
- A comment earns its place only for a non-obvious *why*: a workaround, a business rule, a
  surprising constraint, a unit or format gotcha. Nothing else.
- Never restate what the code says. Prefer better naming or structure over a comment.
- Older files carry long Javadoc blocks. Do not add to them, and do not add comments to code
  you are only touching incidentally.

## Architecture

Layers go **controller → service → repository → database**, under
`src/main/java/com/bugtracking/`. Controllers handle HTTP only; services hold the rules.
`*ApiController` classes serve JSON under `/api/**`; the rest render Thymeleaf pages.

### One database, and Flyway owns its schema

Supabase Postgres is the only datasource. There is no embedded database on the
classpath and no default for the connection settings, so a checkout without
`SUPABASE_DB_*` in `.env` stops at startup naming what is missing — it never falls
back onto a private local database whose projects nobody else can see. Never add an
embedded driver, a second profile, or a default connection URL to bring one back.

Flyway SQL files own the schema and Hibernate only `validate`s. An entity that drifts
from the migrated schema fails startup. Bean-validation constraints do not shape DDL
(`apply_to_ddl=false`).

### Startup runners in `config/`

Data fixes and seeders are `CommandLineRunner` beans ordered with
`@Order(Ordered.HIGHEST_PRECEDENCE + n)`. The chain is: `SchemaUpgrade` (widen legacy H2
ENUM columns) → `StatusMigration` → `ProjectColumnMigration` → `LegacyReportMerge` →
`AssigneeMigration` → `FieldDefaultsBackfill` / `BootstrapAdmin` → `BoardColumnSeed` →
`BoardColumnRestyle`. Each is idempotent and runs on every start; a new one must be ordered
against these and safe to re-run. Most now find nothing to do — the equivalent work lives
in the migrations — and the older ones still carry H2-era SQL that is inert on Postgres.

### Rules that the code depends on

- **Every stored enum is `VARCHAR`.** `@Enumerated` fields carry
  `@JdbcTypeCode(SqlTypes.VARCHAR)`; migrations use plain `varchar`, never a native enum or
  `CHECK` list. Adding a constant must never need DDL.
- **Bugs name people and projects as text, not foreign keys.** A bug is history and a rename
  must not rewrite it. Live facts (a project's team) are real relations.
- **Status is a board column name**, not an enum: each project owns its `board_columns` rows
  and a bug's status is whichever column it sits in.
- **`team_members` is the users table, and the only one.** A row with `password_hash` can
  sign in; one without is only a name that appears on bugs. There is no configured account,
  no seeded person, no project and no example bug anywhere in the source — everything in this
  app was created in this app, so never add a seeder or a properties-file login to make
  something appear. The single exception is `BootstrapAdmin`, which writes the *first* admin
  from two `.env` values and does nothing once one active admin has a password; sign-in
  itself never reads it.
- **A row carries a `role` — ADMIN or MEMBER, and the line is drawn around the setup, not
  the work.** Raising, moving, commenting and assigning stay open to everyone; the roster,
  passwords, roles and projects are an admin's. A new write route that administers something
  needs a line in `SecurityConfig.filterChain`, and the control that posts to it needs
  `sec:authorize` so nobody is shown a button that 403s.
- **Security:** everything is behind login except `/login`, static files, `/error` and
  `/api/**`. The API is deliberately open and CSRF-exempt; HTML forms need `th:action` to
  get their CSRF token.
- **Email mirrors the bell; it never decides anything.** `NotificationService` says who hears
  what and `EmailService` carries it out, after the transaction commits and on its own
  thread. If a change should email somebody it does not notify, add the *notification*.
  Nothing is sent unless both `bugtracking.mail.enabled` and `spring.mail.host` are set.
- **Credentials live in `.env`**, gitignored and imported by `application.properties`.
  Never write one into a properties file or a migration.

### Frontend

Read `.claude/skills/frontend/SKILL.md` before touching a template, `style.css` or `app.js`.
Tokens not literals, both themes, reuse the component vocabulary. `[hidden]` is enforced with
one `!important` rule at the top of `style.css`; never work around it per component.

## The schema rule: every change ships a migration

**The schema is owned by the SQL files in
`src/main/resources/db/migration/postgres`, not by Hibernate.** The app runs
`ddl-auto=validate`, so an entity that has drifted from the migrated schema does
not quietly fix itself — the application fails to start.

So: **any change to a `@Entity` — a new field, a widened column, a new table, a
new relation, a changed name or length — is not finished until a new migration
file exists for it.** In the same change, not later.

```
src/main/resources/db/migration/postgres/V<next>__what_it_does.sql
```

- **Never edit a migration that has been applied.** Its checksum is recorded;
  editing it breaks startup for everyone whose database already ran it. Fix a
  mistake with the next number.
- **Write it so re-running is harmless** — `create table if not exists`,
  `add column if not exists`. A Supabase database that Hibernate built before
  Flyway took over has to adopt the file without being rewritten.
- **Index new foreign keys.** Postgres indexes neither side for you, so a join
  table needs an index on whichever column its primary key does not lead with.
- **`alter table … enable row level security`** on any new table, for the same
  reason `V1` does it: the app reaches Postgres as the owning role over JDBC and
  is unaffected, and it closes the table to Supabase's Data API.

Every run is a Postgres run, so a missing migration now breaks startup on the
first one — which is the cheap version of the same lesson. Write the migration
with the entity change, not when something breaks.
