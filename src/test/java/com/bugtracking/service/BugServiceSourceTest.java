package com.bugtracking.service;

import com.bugtracking.model.Bug;
import com.bugtracking.model.BugSource;
import com.bugtracking.model.Severity;
import com.bugtracking.repository.BugRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BugServiceSourceTest {

    private BugRepository repository;
    private BugService bugs;

    @BeforeEach
    void setUp() {
        repository = mock(BugRepository.class);
        BoardColumnService columns = mock(BoardColumnService.class);
        when(columns.keyOn(any(), any())).thenReturn("OPEN");
        when(columns.snapshot()).thenReturn(mock(BoardColumns.class));
        when(repository.save(any(Bug.class))).thenAnswer(i -> i.getArgument(0));
        bugs = new BugService(repository, columns, mock(BugHistoryService.class),
                mock(NotificationService.class), mock(CommentService.class),
                mock(AttachmentService.class), mock(SupportingDocService.class));
    }

    private Bug bug(boolean guest, boolean external, BugSource claimed) {
        Bug b = new Bug();
        b.setTitle("Sign-in swallows the first click");
        b.setProject("Acme");
        b.setSeverity(Severity.MEDIUM);
        b.setStatus("OPEN");
        b.setViaGuest(guest);
        b.setViaPublic(external);
        b.setSource(claimed);
        return b;
    }

    @Test
    void theFlagsDecideTheSourceOnSave() {
        assertEquals(BugSource.CLIENT, bugs.save(bug(true, false, BugSource.EXTERNAL)).getSource());
        assertEquals(BugSource.EXTERNAL, bugs.save(bug(false, true, BugSource.INTERNAL)).getSource());
        assertEquals(BugSource.INTERNAL, bugs.save(bug(false, false, BugSource.CLIENT)).getSource());
    }

    @Test
    void anUpdateCannotChangeWhereABugCameFrom() {
        Bug existing = bug(true, false, BugSource.EXTERNAL);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        Bug changes = bug(false, false, BugSource.INTERNAL);
        assertEquals(BugSource.CLIENT, bugs.update(7L, changes).getSource());
    }
}
