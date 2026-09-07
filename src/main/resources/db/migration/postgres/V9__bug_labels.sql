-- V9: free-text labels on a bug, shaped like bug_assignees.

create table if not exists bug_labels (
    bug_id   bigint  not null,
    position integer not null,
    label    varchar(40),
    primary key (bug_id, position),
    constraint fk_bug_labels_bug foreign key (bug_id) references bugs (id)
);

-- The primary key already leads with bug_id; filtering and autocomplete read the other way.
create index if not exists idx_bug_labels_label on bug_labels (lower(label));

alter table bug_labels enable row level security;
