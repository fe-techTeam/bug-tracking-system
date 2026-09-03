-- A file attached to a comment rather than to the report.
--
-- One column, not a second table: a screenshot pasted into a comment is the
-- same thing as one on the report — same bytes on disk, same size and type,
-- the same route serving it and the same lightbox opening it. All that differs
-- is what it hangs off. bug_id stays NOT NULL either way, so deleting a bug
-- still takes every file with it without walking the comments first.
--
-- Null means "on the bug itself", which is every row that already exists.

alter table bug_attachments
    add column if not exists comment_id bigint;

-- Postgres indexes neither side of a relation for you, and every render of a
-- bug page asks this table twice: once for the report's files (comment_id is
-- null) and once per comment.
create index if not exists idx_bug_attachments_comment
    on bug_attachments (comment_id);

-- No foreign key to bug_comments on purpose. Deleting a comment is meant to
-- take its files with it, which the service does explicitly so the bytes on
-- disk go too; a cascade here would drop the rows and leave the files behind.
