package com.bugtracking.controller;

import com.bugtracking.config.SecurityConfig;
import com.bugtracking.model.Project;
import com.bugtracking.repository.TeamMemberRepository;
import com.bugtracking.service.GuestService;
import com.bugtracking.service.PublicIntakeService;
import com.bugtracking.service.PublicReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = PublicIntakeController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {GlobalModelAttributes.class, GlobalExceptionHandler.class}))
@Import(SecurityConfig.class)
class PublicIntakeControllerTest {

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

    @Test
    void theFormIsOpenWithoutSigningIn() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        mvc.perform(get("/public/tok-acme"))
                .andExpect(status().isOk())
                .andExpect(view().name("public/new"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Acme")));
    }

    @Test
    void anUnknownTokenIsNotFoundAndNotALoginRedirect() throws Exception {
        when(intake.byToken("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/public/nope"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("public/unavailable"));
    }

    @Test
    void aMissingEmailComesBackToTheForm() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        mvc.perform(multipart("/public/tok-acme").with(csrf())
                        .param("reporterName", "Priya")
                        .param("title", "Broken")
                        .param("description", "It is")
                        .param("severity", "HIGH")
                        .param("environment", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(view().name("public/new"));
    }

    @Test
    void aGoodReportRedirectsToDone() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        when(intake.raise(eq("tok-acme"), any(PublicReport.class), any(), anyString(), isNull()))
                .thenReturn(new GuestService.Filed(42L, null));
        mvc.perform(multipart("/public/tok-acme").with(csrf())
                        .param("reporterName", "Priya")
                        .param("reporterEmail", "priya@example.com")
                        .param("title", "Broken")
                        .param("description", "It is")
                        .param("severity", "HIGH")
                        .param("environment", "PRODUCTION"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/public/tok-acme/done?bug=42"));
    }

    @Test
    void doneShowsTheBugNumber() throws Exception {
        when(intake.byToken("tok-acme")).thenReturn(Optional.of(acme()));
        mvc.perform(get("/public/tok-acme/done").param("bug", "42"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BUG-42")));
    }
}
