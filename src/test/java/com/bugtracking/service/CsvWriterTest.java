package com.bugtracking.service;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvWriterTest {

    @Test
    void plainFieldsAreJoinedWithCommasAndCrlf() {
        assertEquals("1,login,HIGH\r\n", CsvWriter.row(List.of("1", "login", "HIGH")));
    }

    @Test
    void quotesFieldsWithCommasQuotesOrNewlines() {
        assertEquals("\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n",
                CsvWriter.row(List.of("a,b", "say \"hi\"", "two\nlines")));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals(",x\r\n", CsvWriter.row(Arrays.asList(null, "x")));
    }

    @Test
    void rendersHeaderAndQuotedTitle() {
        Bug b = new Bug();
        b.setId(7L);
        b.setTitle("Login, then crash");
        b.setProject("Acme");
        b.setStatus("OPEN");
        b.setSeverity(Severity.HIGH);
        b.setLabels(List.of("ui", "login"));
        String csv = BugCsv.render(List.of(b), new BoardColumns(List.of()));
        String[] lines = csv.substring(1).split("\r\n");
        assertEquals("id,title,severity,status,environment,project,module,reported_by,assignees,labels,due_date,created_at,updated_at", lines[0]);
        assertTrue(lines[1].startsWith("7,\"Login, then crash\",HIGH,"));
        assertTrue(lines[1].contains(",ui;login,"));
    }
}
