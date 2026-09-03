-- V3: the report becomes one box.
--
-- A bug used to be raised as four boxes - description, steps to reproduce,
-- expected result, actual result. People wrote the whole story in the first one
-- and left the other three empty, so the form asks for one thing now and the
-- entity has one field: description is the report.
--
-- The three columns therefore go. What was written in them does not: every row
-- that still holds any of them has it appended to its description first, in the
-- order the form used to ask for it. Only then are the columns dropped. Losing
-- the boxes is a simplification; losing what a tester wrote in them would be
-- data loss.
--
-- Re-running this file is harmless. The fold is guarded by the columns still
-- existing, so a second run walks past it, and the drops are "if exists".
-- H2 does the same fold at startup in LegacyReportMerge, which is how the
-- default profile - where Flyway is off - gets the same treatment.

do $$
begin
    if exists (select 1
                 from information_schema.columns
                where table_schema = current_schema()
                  and table_name = 'bugs'
                  and column_name = 'steps_to_reproduce') then

        -- concat_ws skips nulls but not empty strings, hence nullif on every
        -- part: a box left blank must not leave a stray heading or a blank
        -- paragraph behind. left(...) keeps the result inside the column - the
        -- four boxes together could hold more than description alone can.
        update bugs
           set description = left(
                   concat_ws(chr(10) || chr(10),
                       nullif(btrim(coalesce(description, '')), ''),
                       case when btrim(coalesce(steps_to_reproduce, '')) <> ''
                            then 'How to see it:' || chr(10) || btrim(steps_to_reproduce)
                       end,
                       nullif(concat_ws(chr(10),
                           case when btrim(coalesce(expected_result, '')) <> ''
                                then 'Expected: ' || btrim(expected_result)
                           end,
                           case when btrim(coalesce(actual_result, '')) <> ''
                                then 'Actual: ' || btrim(actual_result)
                           end), '')),
                   4000)
         where btrim(coalesce(steps_to_reproduce, '')) <> ''
            or btrim(coalesce(expected_result, '')) <> ''
            or btrim(coalesce(actual_result, '')) <> '';
    end if;
end $$;

alter table bugs drop column if exists steps_to_reproduce;
alter table bugs drop column if exists expected_result;
alter table bugs drop column if exists actual_result;
