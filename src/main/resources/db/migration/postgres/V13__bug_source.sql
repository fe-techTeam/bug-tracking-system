-- V13: where a bug came from, stored once instead of inferred from two booleans.

alter table bugs
    add column if not exists source varchar(16) not null default 'INTERNAL';

update bugs set source = 'CLIENT' where via_guest and source = 'INTERNAL';

update bugs set source = 'EXTERNAL' where via_public and source = 'INTERNAL';
