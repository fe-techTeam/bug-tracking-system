package com.bugtracking.service;

import com.bugtracking.config.EmailProperties;
import com.bugtracking.model.Bug;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Project;
import com.bugtracking.model.Severity;
import com.bugtracking.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicIntakeServiceTest {

    ProjectRepository projects = mock(ProjectRepository.class);
    BugService bugs = mock(BugService.class);
    AttachmentService attachments = mock(AttachmentService.class);
    NotificationService notifications = mock(NotificationService.class);
    ProjectService projectService = mock(ProjectService.class);
    EmailProperties mail = new EmailProperties();
    GuestRateLimit limit = new GuestRateLimit();

    PublicIntakeService intake;
    Project acme;

    @BeforeEach
    void setUp() {
        acme = new Project("Acme");
        acme.setId(7L);
        acme.setPublicToken("tok-acme");
        when(projects.findByPublicTokenAndActiveTrue("tok-acme")).thenReturn(Optional.of(acme));
        when(projects.findByPublicTokenAndActiveTrue("nope")).thenReturn(Optional.empty());
        when(projects.findById(7L)).thenReturn(Optional.of(acme));
        when(projects.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bugs.save(any(Bug.class), anyString())).thenAnswer(inv -> {
            Bug b = inv.getArgument(0);
            b.setId(42L);
            return b;
        });
        when(projectService.memberNamesOf("Acme")).thenReturn(List.of("Ana", "Bo"));
        mail.setBaseUrl("https://bugs.example.com/");
        intake = new PublicIntakeService(projects, bugs, attachments, notifications, projectService, mail, limit);
    }

    private PublicReport report() {
        PublicReport r = new PublicReport();
        r.setReporterName("  Priya Nair ");
        r.setReporterEmail("Priya@Example.com");
        r.setTitle(" Sign-in swallows the first click ");
        r.setDescription("It does.");
        r.setSeverity(Severity.HIGH);
        r.setEnvironment(Environment.PRODUCTION);
        return r;
    }

    @Test
    void raisesAnUnassignedExternalBugOnTheTokenProject() {
        GuestService.Filed filed = intake.raise("tok-acme", report(), null, "10.0.0.1", "Mozilla/5.0");

        ArgumentCaptor<Bug> saved = ArgumentCaptor.forClass(Bug.class);
        verify(bugs).save(saved.capture(), eq("Priya Nair"));
        Bug bug = saved.getValue();
        assertEquals(42L, filed.bugId());
        assertNull(filed.rejected());
        assertEquals("Acme", bug.getProject());
        assertEquals("Priya Nair", bug.getReportedBy());
        assertEquals("priya@example.com", bug.getReporterEmail());
        assertEquals("Sign-in swallows the first click", bug.getTitle());
        assertTrue(bug.isViaPublic());
        assertFalse(bug.isViaGuest());
        assertNull(bug.getGuestId());
        assertTrue(bug.getAssignees().isEmpty());
        assertTrue(bug.getDescription().endsWith("Browser: Mozilla/5.0"));
    }

    @Test
    void aMaxLengthDescriptionStillFitsWithTheBrowserLine() {
        PublicReport r = report();
        r.setDescription("x".repeat(4000));

        intake.raise("tok-acme", r, null, "10.0.0.1", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)");

        ArgumentCaptor<Bug> saved = ArgumentCaptor.forClass(Bug.class);
        verify(bugs).save(saved.capture(), anyString());
        assertEquals(4000, saved.getValue().getDescription().length());
        assertTrue(saved.getValue().getDescription().startsWith("x".repeat(3000)));
    }

    @Test
    void aNearlyFullDescriptionKeepsWhatFitsOfTheBrowserLine() {
        PublicReport r = report();
        r.setDescription("x".repeat(3970));

        intake.raise("tok-acme", r, null, "10.0.0.1", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)");

        ArgumentCaptor<Bug> saved = ArgumentCaptor.forClass(Bug.class);
        verify(bugs).save(saved.capture(), anyString());
        String description = saved.getValue().getDescription();
        assertEquals(4000, description.length());
        assertTrue(description.contains("\n\nBrowser: Mozilla"));
    }

    @Test
    void tellsTheProjectTeam() {
        intake.raise("tok-acme", report(), null, "10.0.0.1", null);
        verify(notifications).notify(eq(42L), eq("public"), eq("Ana"),
                eq("Priya Nair (external) raised BUG-42 on Acme: Sign-in swallows the first click"));
        verify(notifications).notify(eq(42L), eq("public"), eq("Bo"), anyString());
    }

    @Test
    void refusesAnUnknownToken() {
        assertThrows(PublicIntakeService.UnknownLinkException.class,
                () -> intake.raise("nope", report(), null, "10.0.0.1", null));
        assertTrue(intake.byToken("nope").isEmpty());
    }

    @Test
    void limitsOneAddressToTwelveAnHour() {
        for (int i = 0; i < 12; i++) {
            intake.raise("tok-acme", report(), null, "10.0.0.9", null);
        }
        assertThrows(GuestRateLimit.TooOftenException.class,
                () -> intake.raise("tok-acme", report(), null, "10.0.0.9", null));
        intake.raise("tok-acme", report(), null, "10.0.0.10", null);
    }

    @Test
    void regenerateWritesAFreshToken() {
        String fresh = intake.regenerate(7L);
        assertEquals(32, fresh.length());
        assertEquals(fresh, acme.getPublicToken());
        verify(projects).save(acme);
    }

    @Test
    void publicUrlJoinsBaseAndToken() {
        assertEquals("https://bugs.example.com/public/tok-acme", intake.publicUrl(acme));
    }
}
