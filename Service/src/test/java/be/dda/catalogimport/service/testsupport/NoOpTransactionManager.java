package be.dda.catalogimport.service.testsupport;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Transactiemanager zonder effect, voor services die intern een TransactionTemplate gebruiken (unit-tests). */
public class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // geen echte transactie
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        // geen echte transactie
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        // geen echte transactie
    }
}
