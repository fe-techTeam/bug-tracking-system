# Moving the database to Mumbai (ap-south-1)

Supabase **cannot change a project's region in place** — a project is tied to the
infrastructure it was provisioned on. The move is: create a new project in
`ap-south-1`, copy the data in, repoint `.env`.

**Done — executed 4 Sep 2026.** The project now runs on `ap-south-1`, project
ref `hukmoravgwbxfibjsuft`. This is kept as the record of what was done and as
the procedure to follow if it ever has to happen again.

Measured after the move:

| | Tokyo | Mumbai |
|---|---|---|
| TCP round trip | 137.5 ms | **12.9 ms** |
| Flyway validate, 8 migrations, on startup | 1.555 s | **0.048 s** |

Verification: all six table counts matched, and an md5 over every row of every
table — ids, names, emails, roles, BCrypt hashes, bug titles, statuses, board
columns, history and assignees — was identical on both sides
(`a0cb9cb158de9f105bc92ba138314405`).

## Why

| | round trip from here |
|---|---|
| `ap-northeast-1` (Tokyo) — where the project is now | **~176 ms** |
| `ap-south-1` (Mumbai) | **~45 ms** |

## What has to move

Almost nothing. The database is 11 MB and 36 rows of real data:

| table | rows |
|---|---|
| team_members | 19 |
| board_columns | 6 |
| bugs | 4 |
| bug_history | 3 |
| bug_assignees | 3 |
| projects | 1 |

Nothing else moves. The app uses Postgres over JDBC only — no Supabase Auth,
Storage, Realtime or Edge Functions — so there are no auth users, buckets or
functions to carry across. Attachments are on local disk (`data/attachments`,
40 KB) because `S3_ENABLED` is unset, so they stay exactly where they are.

**The schema does not need to be dumped.** Flyway owns it: point the app at an
empty database and `V1`..`V8` build all 14 tables. Only data moves.

## Runbook

### 1. Create the project

Supabase dashboard → New project → region **South Asia (Mumbai) `ap-south-1`**.
Same Postgres major version as now (**17**). Keep the dashboard open for the
connection string: Project Settings → Database → Connection string → JDBC.

### 2. Dump the data from Tokyo

```bash
set -a; . ./.env; set +a; export PGPASSWORD="$SUPABASE_DB_PASSWORD"

# pg_dump must be >= the server. The server is 17.6; the Homebrew default here
# is 14.20 and will refuse outright.
/opt/homebrew/opt/postgresql@18/bin/pg_dump \
  "host=$SUPABASE_DB_HOST port=$SUPABASE_DB_PORT dbname=$SUPABASE_DB_NAME user=$SUPABASE_DB_USER sslmode=require" \
  --data-only --schema=public \
  --exclude-table=flyway_schema_history --exclude-table=schema_migrations \
  --no-owner --no-privileges --column-inserts \
  -f data.sql
```

`flyway_schema_history` is excluded because Flyway writes its own on the new
project; restoring the old one would leave it describing migrations it did not
run. `schema_migrations` is Supabase's, not ours.

Sequences need **no** manual reset — `pg_dump --data-only` emits the `setval`
calls, verified: the first insert after restore got the correct next id.

### 3. Point `.env` at Mumbai, with the bootstrap keys blank

```properties
SUPABASE_DB_HOST=aws-0-ap-south-1.pooler.supabase.com
SUPABASE_DB_PORT=5432
SUPABASE_DB_NAME=postgres
SUPABASE_DB_USER=postgres.<new-project-ref>
SUPABASE_DB_PASSWORD=<new password>

BOOTSTRAP_ADMIN_EMAIL=
BOOTSTRAP_ADMIN_PASSWORD=
```

Blanking the two bootstrap values matters. Left set, the first start against an
empty database writes `admin@firsteconomy.com` — and step 5 then restores 19
team_members including that same address, which collides on
`uk_team_members_email`. Restoring first and starting second avoids it too, but
blanking is the version that cannot go wrong. (Once the restore is in, an active
admin with a password exists and `BootstrapAdmin` stands down on its own — that
was confirmed in the rehearsal.)

### 4. Let Flyway build the schema

```bash
./run.sh migrate     # or just start the app once and stop it
```

Confirm with `./run.sh db-info` that `V1`..`V8` are applied and none pending.

### 5. Restore the data

```bash
psql "postgresql://postgres.<ref>:<password>@aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require" \
  -v ON_ERROR_STOP=1 <<'SQL'
begin;
set session_replication_role = replica;
\i data.sql
set session_replication_role = default;
commit;
SQL
```

`session_replication_role = replica` disables triggers and foreign-key checks
for the load, so the insert order in the dump cannot fail on a not-yet-inserted
parent row. It is set inside the transaction and reset before commit.

### 6. Verify, then start

```sql
select 'team_members' t, count(*) from team_members
union all select 'projects', count(*) from projects
union all select 'bugs', count(*) from bugs
union all select 'board_columns', count(*) from board_columns
union all select 'bug_history', count(*) from bug_history
union all select 'bug_assignees', count(*) from bug_assignees;
```

Expect 19 / 1 / 4 / 6 / 3 / 3. Then `./run.sh restart`, sign in, and check the
board, a bug page and Settings → Team.

### 7. Afterwards

Keep the Tokyo project paused rather than deleted for a week or two — it is the
only rollback, and rollback is just putting the old block back in `.env`.

## Gotchas hit during the rehearsal

- **`pg_dump` 14 cannot dump a 17.6 server.** Use the `postgresql@18` binary by
  full path; the one on `PATH` is 14.
- **`--use-set-session-authorization` takes no argument** in pg18's pg_dump.
- **`SET transaction_timeout = 0`** appears in the dump header. It is a
  Postgres 17 setting, fine on the Mumbai target (also 17), but it aborts a
  restore into a Postgres 14 database — only relevant if you rehearse locally
  against `postgresql@14`.
- **`--spring.config.import=` does not stop `.env` being read.** It is
  `--spring.datasource.url` on the command line that decides which database a
  scratch run talks to. Check the startup log's `Database:` line before
  trusting a scratch run.

## The other half of the latency

Region is not the only thing. See the `default_batch_fetch_size` note in
`application.properties`: a 120-bug board was issuing **504** SQL statements and
now issues **21**. Round trips multiply, so both fixes matter — 21 statements at
45 ms is under a second; 504 at 176 ms is a minute and a half.
