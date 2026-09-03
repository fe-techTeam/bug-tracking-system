# Bug Tracking — working notes

Spring Boot + Thymeleaf, no build step on the front end. `README.md` is the long
version; this file is the handful of rules that are easy to break.

## Running it

```bash
./run.sh              # foreground; takes the port back if something is already on it
./run.sh bg           # background, logging to app.log
./run.sh stop | status | logs
```

The app is H2-on-disk by default and Supabase (Postgres) under a profile:

```bash
SPRING_PROFILES_ACTIVE=supabase ./run.sh bg
```

Java changes need a restart; templates and static files are picked up on refresh.

## The schema rule: every change ships a migration

**On Postgres the schema is owned by the SQL files in
`src/main/resources/db/migration/postgres`, not by Hibernate.** That profile runs
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
- Enum-ish columns are plain `varchar`, never a native enum or a `CHECK` list —
  adding a constant must never need DDL.

Apply and inspect without booting the app:

```bash
./run.sh migrate     # apply what is pending
./run.sh db-info     # what is applied, what is pending
./run.sh db-repair   # after a failed migrate
```

H2 still builds its own schema from the entities (`ddl-auto=update`), so a local
run can hide a missing migration entirely. That is exactly why the rule is
"write the migration with the entity change", not "write it when something
breaks".

## The rest

- **Frontend work**: read `.claude/skills/frontend/SKILL.md` first. Tokens not
  literals, both themes, and the component vocabulary is already there.
- **Bugs name people and projects as text, not foreign keys.** That is
  deliberate — a bug is history and a rename must not rewrite it. Live facts
  (a project's team) are real relations; historical ones are strings.
- `team_members` is the users table. A row with `password_hash` can sign in;
  one without is only a name that appears on bugs.
- Credentials live in `.env`, which is gitignored. Never write one into
  `application.properties` or a migration.
