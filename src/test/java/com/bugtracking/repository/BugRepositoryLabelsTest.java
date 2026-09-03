package com.bugtracking.repository;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class BugRepositoryLabelsTest {

    @Autowired
    BugRepository bugs;

    private Bug bug(String title, String... labels) {
        Bug b = new Bug();
        b.setTitle(title);
        b.setProject("Acme");
        b.setSeverity(Severity.MEDIUM);
        b.setEnvironment(Environment.QA);
        b.setStatus("OPEN");
        b.setLabels(List.of(labels));
        return bugs.save(b);
    }

    @Test
    void filtersByLabelIgnoringCase() {
        bug("one", "Login");
        bug("two", "payments");
        List<Bug> found = bugs.search(null, null, null, null, null, null, "login", null, null);
        assertEquals(1, found.size());
        assertEquals("one", found.get(0).getTitle());
    }

    @Test
    void distinctLabelsAreSortedAndScopedToProject() {
        bug("one", "ui", "Login");
        bug("two", "payments");
        assertEquals(List.of("Login", "payments", "ui"), bugs.distinctLabels("acme"));
        assertEquals(List.of(), bugs.distinctLabels("Other"));
    }
}
