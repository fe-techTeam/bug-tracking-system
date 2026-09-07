package com.bugtracking.repository;

import com.bugtracking.model.Bug;
import com.bugtracking.model.BugSource;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class BugSourceTest {

    @Autowired
    BugRepository bugs;

    private Bug bug(String title) {
        Bug b = new Bug();
        b.setTitle(title);
        b.setProject("Acme");
        b.setSeverity(Severity.MEDIUM);
        b.setEnvironment(Environment.QA);
        b.setStatus("OPEN");
        return b;
    }

    @Test
    void defaultsToInternal() {
        Bug saved = bugs.save(bug("team raised"));
        assertEquals(BugSource.INTERNAL, bugs.findById(saved.getId()).orElseThrow().getSource());
    }

    @Test
    void readsBackAnExternalSource() {
        Bug b = bug("public link report");
        b.setSource(BugSource.EXTERNAL);
        Bug saved = bugs.save(b);
        assertEquals(BugSource.EXTERNAL, bugs.findById(saved.getId()).orElseThrow().getSource());
    }
}
