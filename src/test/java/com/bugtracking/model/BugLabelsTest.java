package com.bugtracking.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BugLabelsTest {

    @Test
    void trimsBlanksAndDeduplicatesIgnoringCase() {
        Bug bug = new Bug();
        bug.setLabels(Arrays.asList(" login ", "", null, "Login", "payments"));
        assertEquals(List.of("login", "payments"), bug.getLabels());
    }

    @Test
    void capsEachLabelAtFortyCharacters() {
        Bug bug = new Bug();
        bug.setLabels(List.of("a".repeat(50)));
        assertEquals(40, bug.getLabels().get(0).length());
    }

    @Test
    void labelsTextRoundTripsThroughCommas() {
        Bug bug = new Bug();
        bug.setLabelsText("login, payments ,,  ui");
        assertEquals(List.of("login", "payments", "ui"), bug.getLabels());
        assertEquals("login, payments, ui", bug.getLabelsText());
        assertEquals("login, payments, ui", bug.getLabelsLabel());
    }

    @Test
    void emptyLabelsReadAsEmptyTextAndNullLabel() {
        Bug bug = new Bug();
        bug.setLabelsText("  ");
        assertTrue(bug.getLabels().isEmpty());
        assertEquals("", bug.getLabelsText());
        assertNull(bug.getLabelsLabel());
    }
}
