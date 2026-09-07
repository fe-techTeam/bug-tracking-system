package com.bugtracking.service;

import com.bugtracking.model.SavedFilter;
import com.bugtracking.repository.SavedFilterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import(SavedFilterService.class)
class SavedFilterServiceTest {

    @Autowired
    SavedFilterService filters;
    @Autowired
    SavedFilterRepository repository;

    @Test
    void stripsProjectAndViewFromTheStoredQuery() {
        SavedFilter f = filters.save("Mine", "Acme", "?project=Acme&view=list&assignee=Ana&severity=HIGH", "Ana");
        assertEquals("assignee=Ana&severity=HIGH", f.getQuery());
    }

    @Test
    void rejectsAQueryWithNothingToSave() {
        assertThrows(SavedFilterService.EmptyQueryException.class,
                () -> filters.save("Nothing", "Acme", "?project=Acme&view=list", "Ana"));
    }

    @Test
    void listsProjectAndGlobalFiltersByName() {
        filters.save("Zed", "Acme", "severity=LOW", "Ana");
        filters.save("Any", null, "severity=HIGH", "Ana");
        filters.save("Other", "Beta", "severity=HIGH", "Ana");
        List<String> names = filters.forProject("acme").stream().map(SavedFilter::getName).toList();
        assertEquals(List.of("Any", "Zed"), names);
    }

    @Test
    void onlyTheCreatorCanDelete() {
        SavedFilter f = filters.save("Mine", "Acme", "severity=HIGH", "Ana");
        assertThrows(SavedFilterService.NotOwnerException.class, () -> filters.delete(f.getId(), "Bo"));
        filters.delete(f.getId(), "ana");
        assertTrue(repository.findById(f.getId()).isEmpty());
    }
}
