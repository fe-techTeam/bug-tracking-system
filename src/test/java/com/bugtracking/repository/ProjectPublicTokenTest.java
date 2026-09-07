package com.bugtracking.repository;

import com.bugtracking.model.Project;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class ProjectPublicTokenTest {

    @Autowired
    ProjectRepository projects;

    @Test
    void aNewProjectGetsAToken() {
        Project saved = projects.save(new Project("Acme"));
        assertNotNull(saved.getPublicToken());
        assertEquals(32, saved.getPublicToken().length());
    }

    @Test
    void findsAnActiveProjectByToken() {
        Project saved = projects.save(new Project("Acme"));
        assertEquals("Acme", projects.findByPublicTokenAndActiveTrue(saved.getPublicToken()).orElseThrow().getName());
    }

    @Test
    void aHiddenProjectIsNotFoundByToken() {
        Project saved = projects.save(new Project("Acme"));
        saved.setActive(false);
        projects.save(saved);
        assertTrue(projects.findByPublicTokenAndActiveTrue(saved.getPublicToken()).isEmpty());
    }
}
