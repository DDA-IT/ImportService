package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.jpa.JpaSystemException;

/** S5-a: de vertaling van een mislukte NOWAIT-vergrendeling naar een 409 met een door de aanroeper gekozen code. */
class LockFailuresTest {

    private static final String CODE = "SOME_CODE";

    private static ConflictException translated(RuntimeException failure) {
        try {
            LockFailures.translate(() -> {
                throw failure;
            }, CODE, "busy");
        } catch (ConflictException e) {
            return e;
        }
        throw new AssertionError("expected a ConflictException");
    }

    @Test
    void aSuccessfulActionReturnsItsResult() {
        assertThat(LockFailures.translate(() -> 42, CODE, "busy")).isEqualTo(42);
    }

    @Test
    void springLockFailuresBecomeAConflictWithTheGivenCode() {
        for (RuntimeException failure : new RuntimeException[] {
                new CannotAcquireLockException("nowait"),
                new PessimisticLockingFailureException("lock"),
                new DeadlockLoserDataAccessException("deadlock", null)}) {
            ConflictException conflict = translated(failure);
            assertThat(conflict.getCode()).isEqualTo(CODE);
            assertThat(conflict.getMessage()).isEqualTo("busy");
        }
    }

    @Test
    void anUntranslatedExceptionWithSqlState55P03InTheCauseChainIsRecognised() {
        assertThat(translated(new JpaSystemException(new RuntimeException(new SQLException("x", "55P03")))).getCode())
                .isEqualTo(CODE);
    }

    @Test
    void anUntranslatedExceptionWithSqlState40P01InTheCauseChainIsRecognised() {
        assertThat(translated(new IllegalStateException(new SQLException("x", "40P01"))).getCode()).isEqualTo(CODE);
    }

    @Test
    void otherSqlStatesAndOtherExceptionsPassThroughUnchanged() {
        RuntimeException constraint = new IllegalStateException(new SQLException("unique", "23505"));
        assertThatThrownBy(() -> LockFailures.translate(() -> {
            throw constraint;
        }, CODE, "busy")).isSameAs(constraint);

        ConflictException other = new ConflictException("OTHER", "already a conflict");
        assertThatThrownBy(() -> LockFailures.translate(() -> {
            throw other;
        }, CODE, "busy")).isSameAs(other);
    }
}
