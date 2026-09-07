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
class BugRepositorySourceTest {

    @Autowired
    BugRepository bugs;

    private Bug bug(String title, boolean guest, boolean external) {
        Bug b = new Bug();
        b.setTitle(title);
        b.setProject("Acme");
        b.setSeverity(Severity.MEDIUM);
        b.setEnvironment(Environment.QA);
        b.setStatus("OPEN");
        b.setViaGuest(guest);
        b.setViaPublic(external);
        return bugs.save(b);
    }

    @Test
    void filtersExternalReportsOnTheirOwn() {
        bug("team", false, false);
        bug("client", true, false);
        bug("external", false, true);
        List<Bug> external = bugs.search(null, null, null, null, null, null, null, true, null, null, null);
        assertEquals(List.of("external"), external.stream().map(Bug::getTitle).toList());
        List<Bug> team = bugs.search(null, null, null, null, null, null, false, false, null, null, null);
        assertEquals(List.of("team"), team.stream().map(Bug::getTitle).toList());
        assertEquals(1, bugs.countByProjectIgnoreCaseAndViaPublicTrueAndDeletedAtIsNull("acme"));
    }
}
