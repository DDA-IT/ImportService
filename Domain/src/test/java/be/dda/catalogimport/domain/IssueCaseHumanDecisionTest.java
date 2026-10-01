package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IssueCaseHumanDecisionTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-02-01T00:00:00Z");

    private static IssueCase newCase() {
        return new IssueCase(null, "CODE", "sig", null, null, null, null, null, null, null,
                1L, 1L, CREATED, CREATED, null, null, null, CREATED);
    }

    /** Brengt een nieuw geval via toegestane menselijke beslissingen in de gevraagde status (AUTO_RESOLVED kan niet). */
    private static IssueCase caseIn(IssueCaseStatus status) {
        IssueCase issueCase = newCase();
        switch (status) {
            case AWAITING_REVIEW -> { }
            case CORRECTED -> issueCase.recordHumanDecision(IssueCaseStatus.CORRECTED, "r", "u", null, null, AT);
            case REJECTED -> issueCase.recordHumanDecision(IssueCaseStatus.REJECTED, "r", "u", null, null, AT);
            default -> throw new IllegalArgumentException(status.name());
        }
        return issueCase;
    }

    @Test
    void newCaseAwaitsReviewWithZeroReopenCount() {
        IssueCase issueCase = newCase();
        assertThat(issueCase.getStatus()).isEqualTo(IssueCaseStatus.AWAITING_REVIEW);
        assertThat(issueCase.getReopenCount()).isZero();
        assertThat(issueCase.getUpdatedAt()).isEqualTo(CREATED);
    }

    @Test
    void awaitingReviewCanBeCorrectedOrRejectedWithoutCountingReopen() {
        for (IssueCaseStatus target : new IssueCaseStatus[] {IssueCaseStatus.CORRECTED, IssueCaseStatus.REJECTED}) {
            IssueCase issueCase = newCase();
            issueCase.recordHumanDecision(target, "reden", "alice", "sub-1", null, AT);
            assertThat(issueCase.getStatus()).isEqualTo(target);
            assertThat(issueCase.getStatusReason()).isEqualTo("reden");
            assertThat(issueCase.getStatusChangedBy()).isEqualTo("alice");
            assertThat(issueCase.getStatusChangedBySubject()).isEqualTo("sub-1");
            assertThat(issueCase.getStatusChangedAt()).isEqualTo(AT);
            assertThat(issueCase.getUpdatedAt()).isEqualTo(AT);
            assertThat(issueCase.getReopenCount()).isZero();
        }
    }

    @Test
    void correctedAndRejectedCanReopenAndEachReopeningCounts() {
        for (IssueCaseStatus from : new IssueCaseStatus[] {IssueCaseStatus.CORRECTED, IssueCaseStatus.REJECTED}) {
            IssueCase issueCase = caseIn(from);
            issueCase.recordHumanDecision(IssueCaseStatus.AWAITING_REVIEW, "heropend", "bob", null, null, AT);
            assertThat(issueCase.getStatus()).isEqualTo(IssueCaseStatus.AWAITING_REVIEW);
            assertThat(issueCase.getReopenCount()).isEqualTo(1);
            issueCase.recordHumanDecision(IssueCaseStatus.REJECTED, "weer af", "bob", null, null, AT);
            issueCase.recordHumanDecision(IssueCaseStatus.AWAITING_REVIEW, "opnieuw", "bob", null, null, AT);
            assertThat(issueCase.getReopenCount()).isEqualTo(2);
        }
    }

    @Test
    void forbiddenTransitionsThrowAndLeaveStateUnchanged() {
        IssueCaseStatus[] reachable = {IssueCaseStatus.AWAITING_REVIEW, IssueCaseStatus.CORRECTED,
                IssueCaseStatus.REJECTED};
        for (IssueCaseStatus from : reachable) {
            for (IssueCaseStatus to : IssueCaseStatus.values()) {
                if (isAllowed(from, to)) {
                    continue;
                }
                IssueCase issueCase = caseIn(from);
                Instant updatedBefore = issueCase.getUpdatedAt();
                String reasonBefore = issueCase.getStatusReason();
                String changedByBefore = issueCase.getStatusChangedBy();
                int reopenBefore = issueCase.getReopenCount();
                assertThatThrownBy(() -> issueCase.recordHumanDecision(to, "x", "mallory", "s", null, AT))
                        .as("%s -> %s", from, to)
                        .isInstanceOf(IllegalStateException.class);
                assertThat(issueCase.getStatus()).isEqualTo(from);
                assertThat(issueCase.getUpdatedAt()).isEqualTo(updatedBefore);
                assertThat(issueCase.getStatusReason()).isEqualTo(reasonBefore);
                assertThat(issueCase.getStatusChangedBy()).isEqualTo(changedByBefore);
                assertThat(issueCase.getReopenCount()).isEqualTo(reopenBefore);
            }
        }
    }

    private static boolean isAllowed(IssueCaseStatus from, IssueCaseStatus to) {
        return switch (from) {
            case AWAITING_REVIEW -> to == IssueCaseStatus.CORRECTED || to == IssueCaseStatus.REJECTED;
            case CORRECTED, REJECTED, AUTO_RESOLVED -> to == IssueCaseStatus.AWAITING_REVIEW;
        };
    }
}
