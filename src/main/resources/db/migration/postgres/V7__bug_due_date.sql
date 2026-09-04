-- An optional date a bug is meant to be done by.
--
-- date, not timestamp: "by Friday" is what anybody means, and an hour on it
-- would be a precision nobody sets and everybody has to read past.
--
-- Nullable, and staying nullable. Most bugs have no due date, and a required
-- one is a field people fill in with a guess to get past the form — a board
-- where every card carries a made-up date is a board where no date means
-- anything. That also makes this safe on a table that already has rows: there
-- is nothing to backfill, because "no due date" is the right answer for all of
-- them.

alter table bugs
    add column if not exists due_date date;

-- No index. The due date is read off bugs the board has already loaded and
-- sorted in memory (BugService.sorted), never queried on its own, so an index
-- here would be write cost for nothing. Add one with the query that needs it.
