package be.dda.catalogimport.service;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Vertaalt een mislukte {@code NOWAIT}-vergrendeling (S5-a, beslissingslog 2026-10-01 "Stap 4 en 5 uitgewerkt") naar
 * een {@link ConflictException} met een door de aanroeper opgegeven code.
 * <p>
 * <b>Businessregel.</b> Een rij die door een andere transactie vastgehouden wordt, geeft meteen een 409; de aanvraag
 * wacht niet.
 * <p>
 * <b>Waarom buiten de transactie.</b> PostgreSQL breekt een transactie af zodra een statement faalt (SQLState
 * {@code 55P03} lock_not_available, {@code 40P01} deadlock_detected): elk volgend statement in dezelfde transactie
 * geeft {@code 25P02}. De vertaling moet dus gebeuren in een wrapper <b>rond</b> de
 * {@code TransactionTemplate.execute}-aanroep, nooit binnen de callback: een in de callback opgevangen lock-fout
 * laat de transactie ongemerkt afgebroken achter.
 * <pre>{@code
 * return LockFailures.translate(() -> transaction.execute(status -> ...), CODE, "Batch " + id + " is busy");
 * }</pre>
 * Andere uitzonderingen (ook andere {@link ConflictException}s) gaan ongewijzigd door.
 */
public final class LockFailures {

    /** PostgreSQL lock_not_available (NOWAIT of lock_timeout). */
    static final String SQLSTATE_LOCK_NOT_AVAILABLE = "55P03";
    /** PostgreSQL deadlock_detected. */
    static final String SQLSTATE_DEADLOCK = "40P01";

    private LockFailures() {
    }

    /**
     * Voert {@code action} uit (de volledige transactie-aanroep) en vertaalt een lock- of deadlockfout naar
     * {@link ConflictException}{@code (code, message)}.
     */
    public static <T> T translate(Supplier<T> action, String code, String message) {
        try {
            return action.get();
        } catch (RuntimeException failure) {
            if (isLockFailure(failure)) {
                throw new ConflictException(code, message);
            }
            throw failure;
        }
    }

    /**
     * Is dit een mislukte vergrendeling: een {@link PessimisticLockingFailureException} (omvat
     * {@code CannotAcquireLockException} en {@code DeadlockLoserDataAccessException}) of een oorzaak met SQLState
     * 55P03/40P01?
     */
    static boolean isLockFailure(Throwable failure) {
        int depth = 0;
        for (Throwable current = failure; current != null && depth < 20; current = current.getCause(), depth++) {
            if (current instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (current instanceof SQLException sql && isLockSqlState(sql.getSQLState())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static boolean isLockSqlState(String sqlState) {
        return SQLSTATE_LOCK_NOT_AVAILABLE.equals(sqlState) || SQLSTATE_DEADLOCK.equals(sqlState);
    }
}
