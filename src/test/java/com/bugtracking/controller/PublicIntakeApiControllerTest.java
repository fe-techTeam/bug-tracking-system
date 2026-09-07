package com.bugtracking.controller;

import com.bugtracking.config.SecurityConfig;
import com.bugtracking.model.Project;
import com.bugtracking.repository.TeamMemberRepository;
import com.bugtracking.service.GuestRateLimit;
import com.bugtracking.service.GuestService;
import com.bugtracking.service.PublicIntakeService;
import com.bugtracking.service.PublicReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PublicIntakeApiController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {GlobalModelAttributes.class, GlobalExceptionHandler.class}))
@Import(SecurityConfig.class)
class PublicIntakeApiControllerTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    PublicIntakeService intake;
    @MockitoBean
    TeamMemberRepository team;

    private Project acme() {
        Project p = new Project("Acme");
        p.setId(7L);
        p.setPublicToken("tok-acme");
        return p;
    }

    private MockMultipartFile shot() {
        return new MockMultipartFile("files", "shot.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    void anAnonymousReportWithNoCsrfTokenIsAccepted() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        when(intake.raise(eq("tok-acme"), any(PublicReport.class), any(), anyString(), isNull()))
                .thenReturn(new GuestService.Filed(42L, null));
        mvc.perform(multipart("/api/public/tok-acme/bugs")
                        .file(shot())
                        .param("reporterName", "Priya")
                        .param("reporterEmail", "priya@example.com")
                        .param("title", "Broken")
                        .param("description", "It is")
                        .param("severity", "HIGH")
                        .param("environment", "PRODUCTION"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bugId").value(42))
                .andExpect(jsonPath("$.rejected").doesNotExist());
    }

    @Test
    void theRestOfTheApiIsStillClosed() throws Exception {
        mvc.perform(get("/api/bugs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void aMissingEmailIsABadRequestWithTheFieldNamed() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        mvc.perform(multipart("/api/public/tok-acme/bugs")
                        .param("reporterName", "Priya")
                        .param("title", "Broken")
                        .param("description", "It is")
                        .param("severity", "HIGH")
                        .param("environment", "PRODUCTION"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reporterEmail").exists());
    }

    @Test
    void anUnknownTokenIsNotFound() throws Exception {
        when(intake.byToken("nope")).thenReturn(Optional.empty());
        mvc.perform(multipart("/api/public/nope/bugs")
                        .param("reporterName", "Priya")
                        .param("reporterEmail", "priya@example.com")
                        .param("title", "Broken")
                        .param("description", "It is")
                        .param("severity", "HIGH")
                        .param("environment", "PRODUCTION"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("This link is not valid any more."));
    }

    @Test
    void tooManyReportsFromOneAddressIsRefused() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        when(intake.raise(eq("tok-acme"), any(PublicReport.class), any(), anyString(), isNull()))
                .thenThrow(new GuestRateLimit.TooOftenException("Slow down"));
        mvc.perform(multipart("/api/public/tok-acme/bugs")
                        .param("reporterName", "Priya")
                        .param("reporterEmail", "priya@example.com")
                        .param("title", "Broken")
                        .param("description", "It is")
                        .param("severity", "HIGH")
                        .param("environment", "PRODUCTION"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Slow down"));
    }
}
