-- V12: a public link per project, and where a bug raised through one came from.

alter table projects
    add column if not exists public_token varchar(32);

-- md5 rather than gen_random_bytes so no extension is needed; a Java-side token replaces it on regenerate.
update projects
   set public_token = md5(random()::text || clock_timestamp()::text || id::text)
 where public_token is null;

alter table projects
    alter column public_token set not null;

create unique index if not exists ux_projects_public_token
    on projects (public_token);

alter table bugs
    add column if not exists via_public boolean not null default false;

alter table bugs
    add column if not exists reporter_email varchar(200);
