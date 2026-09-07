package com.bugtracking.service;

import com.bugtracking.model.TeamMember;
import com.bugtracking.model.ThemePreference;
import com.bugtracking.repository.TeamMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
class TeamMemberThemeTest {

    @Autowired
    TeamMemberRepository members;

    @Test
    void defaultsToSystem() {
        TeamMember m = members.save(new TeamMember("Ana", "ana@example.com"));
        assertEquals(ThemePreference.SYSTEM, members.findById(m.getId()).orElseThrow().getTheme());
        assertNull(ThemePreference.SYSTEM.attr());
        assertEquals("dark", ThemePreference.DARK.attr());
    }

    @Test
    void findsByNameIgnoringCase() {
        members.save(new TeamMember("Ana Lopes", "ana@example.com"));
        assertEquals("Ana Lopes", members.findFirstByNameIgnoreCase("ana lopes").orElseThrow().getName());
    }
}
