-- Replies, and comments you can change your mind about.
--
-- parent_id is the comment being answered, null for one that opens a thread.
-- One level and no more: a reply to a reply is filed against the same parent,
-- so an exchange stays a flat run under the thing it is about rather than a
-- tree that walks off the right-hand edge of a 380px column.
--
-- edited_at is null until somebody changes the words, which is how the thread
-- knows to say "edited" without keeping a second copy of anything.

alter table bug_comments
    add column if not exists parent_id bigint,
    add column if not exists edited_at timestamp;

-- Every render of a bug page groups the thread by parent, and Postgres indexes
-- neither side of a relation for you.
create index if not exists idx_bug_comments_parent
    on bug_comments (parent_id);

-- No foreign key back to bug_comments on purpose. Deleting a comment takes its
-- replies and their files with it, which the service does explicitly so the
-- bytes on disk go too; a cascade here would drop the rows and orphan the files.
