-- Who may administer the setup, as opposed to work inside it.
--
-- Two roles, MEMBER and ADMIN, and the column is a plain varchar rather than a
-- native enum or a CHECK list — adding a third one day has to be a Java
-- constant and not DDL, which is the same rule board_columns.colour follows
-- and for the same reason.
--
-- Everybody already in the table becomes a MEMBER: the default fills the
-- existing rows, and the seeder promotes the configured admins on the next
-- start. That ordering matters — a table where nobody is an admin is a table
-- nobody can add one to through the app, so AccountSeeder also promotes the
-- first account that can sign in if this leaves the app with no admin at all.

alter table team_members
    add column if not exists role varchar(20) not null default 'MEMBER';

-- Belt and braces for a database Hibernate built before this file existed: it
-- would have created the column nullable, and validate does not care but the
-- app's "is anybody an admin" read does.
update team_members set role = 'MEMBER' where role is null;
