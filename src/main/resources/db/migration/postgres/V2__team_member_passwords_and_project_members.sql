-- V2: team_members becomes the users table, and a project gets a team.
--
-- Two changes that arrived together because they are the same idea from two
-- sides: a person is now something you can sign in as, and a project now says
-- which of those people work on it.
--
--   1. team_members.password_hash — the sign-in accounts used to live in
--      application.properties, in plain text, rebuilt into BCrypt hashes at
--      startup. There was no users table, so there was nowhere to put a
--      password that outlived a restart. There is now: team_members already
--      held the name, the unique email and the active flag, which is every
--      other column a users table would have had. A second table would have
--      split one person in two and left the board asking which half to draw.
--
--   2. project_members — bugs still name people as text (see V1), because a
--      bug is history and history must not be rewritten by a rename. Team
--      membership is not history: it is a live fact about who is on a project
--      right now, so it is a real join table with real foreign keys, and
--      removing a person removes them from the projects rather than leaving a
--      dangling name behind.

-- --- the users half ----------------------------------------------------------

-- Nullable on purpose, and no default: most of the team are names on bugs, not
-- accounts. A null here means "cannot sign in", which is the safe reading for
-- every row that already exists. 100 chars fits a BCrypt hash (60) with room
-- for a stronger encoder later.
alter table team_members
    add column if not exists password_hash varchar(100);

-- --- the project team half ---------------------------------------------------

create table if not exists project_members (
    project_id bigint not null,
    member_id  bigint not null,
    primary key (project_id, member_id),
    constraint fk_project_members_project
        foreign key (project_id) references projects (id) on delete cascade,
    constraint fk_project_members_member
        foreign key (member_id) references team_members (id) on delete cascade
);

-- Postgres indexes neither side of a foreign key for you. The primary key
-- above already leads with project_id, which covers "who is on this project"
-- and the cascade from projects. The other direction — "which projects is this
-- person on", and the cascade when a member is deleted — has nothing without
-- this.
create index if not exists ix_project_members_member on project_members (member_id);

-- Same reasoning as V1: the app reaches this table as the owning postgres
-- role over JDBC, so RLS with no policies changes nothing for it and closes
-- the table to Supabase's Data API.
alter table project_members enable row level security;
