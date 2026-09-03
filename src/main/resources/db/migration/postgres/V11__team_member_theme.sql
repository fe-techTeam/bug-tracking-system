-- V11: the theme a member chose, so it follows them between devices.

alter table team_members
    add column if not exists theme varchar(16) not null default 'SYSTEM';
