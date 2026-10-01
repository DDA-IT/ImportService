package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.IssueCaseEventRepository;
import be.dda.catalogimport.dao.IssueCaseRepository;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.IssueCase;
import be.dda.catalogimport.domain.IssueCaseEvent;
import be.dda.catalogimport.domain.IssueCaseEventKind;
import be.dda.catalogimport.domain.IssueCaseEventSource;
import be.dda.catalogimport.domain.IssueCaseStatus;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.service.testsupport.NoOpTransactionManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IssueCaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:15:30Z");
    private static final ActorIdentity ACTOR = new ActorIdentity("jan", "sub-123");

    private IssueCaseRepository cases;
    private IssueCaseEventRepository events;
    private ImportDefinitionRevisionRepository revisions;
    private IssueCase issueCase;
    private IssueCaseService service;

    @BeforeEach
    void setUp() {
        cases = mock(IssueCaseRepository.class);
        events = mock(IssueCaseEventRepository.class);
        revisions = mock(ImportDefinitionRevisionRepository.class);
        issueCase = mock(IssueCase.class);
        ImportLink link = mock(ImportLink.class);
        ImportDefinition definition = mock(ImportDefinition.class);
        when(definition.getId()).thenReturn(7L);
        when(link.getImportDefinition()).thenReturn(definition);
        when(issueCase.getImportLink()).thenReturn(link);
        when(issueCase.getId()).thenReturn(42L);
        when(issueCase.getStatus()).thenReturn(IssueCaseStatus.AWAITING_REVIEW);
        when(revisions.findByImportDefinitionIdAndStatus(7L, RevisionStatus.ACTIVE)).thenReturn(Optional.empty());
        when(cases.findByIdForUpdate(42L)).thenReturn(Optional.of(issueCase));
        when(cases.saveAndFlush(any(IssueCase.class))).thenAnswer(call -> call.getArgument(0));
        service = new IssueCaseService(cases, events, revisions, new NoOpTransactionManager(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void successWritesExactlyOneEventWithActorAndClockTime() {
        IssueCaseService.IssueCaseDecision decision = service.changeStatus(42L, IssueCaseStatus.CORRECTED,
                IssueCaseStatus.AWAITING_REVIEW, "  fixed upstream  ", ACTOR);

        assertThat(decision.id()).isEqualTo(42L);
        verify(issueCase).recordHumanDecision(IssueCaseStatus.CORRECTED, "fixed upstream", "jan", "sub-123",
                null, NOW);
        ArgumentCaptor<IssueCaseEvent> captor = ArgumentCaptor.forClass(IssueCaseEvent.class);
        verify(events).saveAndFlush(captor.capture());
        IssueCaseEvent event = captor.getValue();
        assertThat(event.getEventKind()).isEqualTo(IssueCaseEventKind.STATUS_CHANGE);
        assertThat(event.getSource()).isEqualTo(IssueCaseEventSource.HUMAN);
        assertThat(event.getPreviousStatus()).isEqualTo(IssueCaseStatus.AWAITING_REVIEW);
        assertThat(event.getNewStatus()).isEqualTo(IssueCaseStatus.CORRECTED);
        assertThat(event.getReason()).isEqualTo("fixed upstream");
        assertThat(event.getChangedBy()).isEqualTo("jan");
        assertThat(event.getChangedBySubject()).isEqualTo("sub-123");
        assertThat(event.getChangedAt()).isEqualTo(NOW);
        assertThat(event.getObservationBatch()).isNull();
    }

    @Test
    void domainIllegalStateBecomesTransitionNotAllowedAndNothingIsSaved() {
        doThrow(new IllegalStateException("not allowed")).when(issueCase)
                .recordHumanDecision(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.changeStatus(42L, IssueCaseStatus.AUTO_RESOLVED,
                IssueCaseStatus.AWAITING_REVIEW, "why", ACTOR))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getCode()).isEqualTo(IssueCaseService.CODE_TRANSITION_NOT_ALLOWED));
        verify(cases, never()).saveAndFlush(any());
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void differingExpectedStatusGivesStatusChangedAndNothingIsSaved() {
        assertThatThrownBy(() -> service.changeStatus(42L, IssueCaseStatus.CORRECTED,
                IssueCaseStatus.REJECTED, "why", ACTOR))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getCode()).isEqualTo(IssueCaseService.CODE_STATUS_CHANGED));
        verify(issueCase, never()).recordHumanDecision(any(), any(), any(), any(), any(), any());
        verify(cases, never()).saveAndFlush(any());
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void unknownCaseGivesCaseNotFound() {
        when(cases.findByIdForUpdate(eq(99L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(99L, IssueCaseStatus.CORRECTED,
                IssueCaseStatus.AWAITING_REVIEW, "why", ACTOR))
                .isInstanceOfSatisfying(NotFoundException.class, e ->
                        assertThat(e.getCode()).isEqualTo(IssueCaseService.CODE_CASE_NOT_FOUND));
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void missingReasonIsBadRequest() {
        for (String reason : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.changeStatus(42L, IssueCaseStatus.CORRECTED,
                    IssueCaseStatus.AWAITING_REVIEW, reason, ACTOR))
                    .isInstanceOfSatisfying(BadRequestException.class, e ->
                            assertThat(e.getCode()).isEqualTo(IssueCaseService.CODE_REASON_REQUIRED));
        }
        verify(cases, never()).findByIdForUpdate(any());
    }

    @Test
    void missingNewStatusIsBadRequest() {
        assertThatThrownBy(() -> service.changeStatus(42L, null, IssueCaseStatus.AWAITING_REVIEW, "why", ACTOR))
                .isInstanceOfSatisfying(BadRequestException.class, e ->
                        assertThat(e.getCode()).isEqualTo(IssueCaseService.CODE_STATUS_REQUIRED));
    }

    @Test
    void missingExpectedStatusOrActorIsIllegalArgument() {
        assertThatThrownBy(() -> service.changeStatus(42L, IssueCaseStatus.CORRECTED, null, "why", ACTOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Missing expectedStatus");
        assertThatThrownBy(() -> service.changeStatus(42L, IssueCaseStatus.CORRECTED,
                IssueCaseStatus.AWAITING_REVIEW, "why", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Missing changedBy");
    }
}
