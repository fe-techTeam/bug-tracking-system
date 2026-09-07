package com.bugtracking.controller;

import com.bugtracking.model.Project;
import com.bugtracking.service.BoardColumnService;
import com.bugtracking.service.BugService;
import com.bugtracking.service.NotificationService;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.PublicIntakeService;
import com.bugtracking.service.TeamMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalModelAttributesPublicLinkTest {

    ProjectService projects = mock(ProjectService.class);
    PublicIntakeService intake = mock(PublicIntakeService.class);

    GlobalModelAttributes attributes;
    Project acme = new Project("Acme");

    @BeforeEach
    void setUp() {
        when(projects.findByName("Acme")).thenReturn(Optional.of(acme));
        when(projects.findByName(null)).thenReturn(Optional.empty());
        when(intake.publicUrl(acme)).thenReturn("http://localhost:8085/public/tok-acme");
        attributes = new GlobalModelAttributes(mock(NotificationService.class), projects,
                mock(BugService.class), mock(BoardColumnService.class),
                mock(TeamMemberService.class), intake);
    }

    @Test
    void theSelectedProjectHasALink() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("project", "Acme");
        assertEquals("http://localhost:8085/public/tok-acme",
                attributes.publicLink(request, new MockHttpSession()));
    }

    @Test
    void allProjectsHasNone() {
        assertNull(attributes.publicLink(new MockHttpServletRequest(), new MockHttpSession()));
    }
}
