package com.bugtracking.controller;

import com.bugtracking.config.SecurityConfig;
import com.bugtracking.repository.TeamMemberRepository;
import com.bugtracking.service.ProjectService;
import com.bugtracking.service.PublicIntakeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProjectController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {GlobalModelAttributes.class, GlobalExceptionHandler.class}))
@Import(SecurityConfig.class)
class ProjectPublicLinkRouteTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ProjectService projects;
    @MockitoBean
    PublicIntakeService intake;
    @MockitoBean
    TeamMemberRepository team;

    @Test
    void aMemberIsRefused() throws Exception {
        mvc.perform(post("/projects/7/public-link").with(csrf()).with(user("bo").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminRegeneratesAndReturnsToSettings() throws Exception {
        when(intake.regenerate(7L)).thenReturn("fresh-token-fresh-token-fresh-tok");
        mvc.perform(post("/projects/7/public-link").with(csrf()).with(user("ana").roles("ADMIN", "USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"));
        verify(intake).regenerate(7L);
    }
}
