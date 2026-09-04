-- Client access: a third role, the bugs it raised, and what it may read.
--
-- A guest is a team_members row like everybody else — that table is still the
-- only users table — with a password hash on it and one project it is bound to.
-- role is already varchar (V6), so GUEST needs no DDL of its own; that is the
-- whole point of never putting an enum in the column type.
--
-- Everything here is additive and defaulted, so an existing database adopts it
-- with nothing to backfill: no bug was raised by a guest before this file, and
-- no comment was ever shared with one.

-- --- who the guest is --------------------------------------------------------

-- The one project a guest may see. Null for everybody else, which is everybody
-- who is not a guest. A bare id rather than a foreign key, matching bugs.blocked_by:
-- a project deleted out from under a guest should leave them with nothing to
-- read, not take the account down with it.
alter table team_members
    add column if not exists guest_project_id bigint;

create index if not exists ix_team_members_guest_project
    on team_members (guest_project_id);

-- --- what the guest raised ---------------------------------------------------

-- Who may read this bug in the portal. reported_by still holds the name as
-- text, the way every other reporter is stored, because that is history and a
-- rename must not rewrite it — but a display name is not what an access check
-- can be built on, so ownership gets an id of its own. Bare again: deleting the
-- guest makes their reports unreadable in the portal rather than deleting them.
alter table bugs
    add column if not exists guest_id bigint;

-- Denormalised from guest_id on purpose. Every board, list and card asks "did
-- this come from outside" to draw the badge, and the filter asks it of the
-- whole table; a null check on an id that also has to survive the guest being
-- deleted is not the same question.
alter table bugs
    add column if not exists via_guest boolean not null default false;

create index if not exists ix_bugs_guest on bugs (guest_id);

-- --- what the guest may read -------------------------------------------------

-- Comments are internal until somebody says otherwise, and that direction is
-- the only safe one: a default of true would have shared every comment already
-- written, on bugs whose threads were typed in the belief that nobody outside
-- would read them.
alter table bug_comments
    add column if not exists shared boolean not null default false;

-- The same flag on a file, for the same reason. It is set from the comment the
-- file arrives with, and true for the ones a guest uploads themselves — there
-- is no separate control for it, so sharing a file with a client means
-- attaching it to a shared comment.
alter table bug_attachments
    add column if not exists shared boolean not null default false;

-- No backfill. Guests are new in this file, so no bug was raised by one and no
-- file was uploaded by one — and a query that guessed ("every report-level file
-- on a guest bug") would wrongly share the ones the team attached. The flag is
-- set where the file is stored, not here.
