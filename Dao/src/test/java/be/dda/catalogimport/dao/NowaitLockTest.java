package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportLink;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S5-a: {@code findByIdForUpdateNowait} wacht niet op een bezet rijslot maar faalt meteen ({@code FOR UPDATE NOWAIT}).
 * <p>
 * Twee echte verbindingen: thread A houdt het rijslot in een eigen, open transactie; de testthread vraagt hetzelfde
 * slot met NOWAIT en moet meteen een lock-fout krijgen. De test zelf draait niet-transactioneel
 * ({@code NOT_SUPPORTED}) omdat de fixtures gecommit moeten zijn om voor de andere verbinding zichtbaar te zijn; ze
 * ruimt zelf op.
 */
@DaoTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NowaitLockTest {

    private static final Duration MUST_FAIL_WITHIN = Duration.ofSeconds(2);

    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private DaoFixtures fixtures;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private Long batchId;
    private Long linkId;
    private Long deliveryId;
    private Long taskId;
    private Long revisionId;
    private Long definitionId;
    private Long organisationId;

    private void createChain() {
        DaoFixtures.Chain chain = fixtures.chain();
        ImportBatch batch = batches.saveAndFlush(chain.newBatch(1));
        batchId = batch.getId();
        ImportLink link = chain.link();
        linkId = link.getId();
        deliveryId = chain.delivery().getId();
        taskId = chain.delivery().getTask().getId();
        revisionId = chain.revision().getId();
        definitionId = link.getImportDefinition().getId();
        organisationId = link.getSupplierOrganisation().getId();
    }

    @AfterEach
    void cleanUp() {
        if (batchId == null) {
            return;
        }
        jdbc.update("delete from import_batch where id = ?", batchId);
        jdbc.update("delete from delivery where id = ?", deliveryId);
        jdbc.update("delete from catalog_import_task where id = ?", taskId);
        jdbc.update("delete from import_link where id = ?", linkId);
        jdbc.update("delete from import_definition_revision where id = ?", revisionId);
        jdbc.update("delete from import_definition where id = ?", definitionId);
        jdbc.update("delete from source_organisation where id = ?", organisationId);
    }

    @Test
    void aBusyBatchRowFailsImmediatelyInsteadOfWaiting() throws Exception {
        createChain();
        holdingRowLock(() -> batches.findByIdForUpdate(batchId), () -> {
            long millis = timeToFailure(() -> batches.findByIdForUpdateNowait(batchId));
            assertThat(millis).isLessThan(MUST_FAIL_WITHIN.toMillis());
        });
    }

    @Test
    void aBusyLinkRowFailsImmediatelyInsteadOfWaiting() throws Exception {
        createChain();
        holdingRowLock(() -> jdbc.queryForList("select id from import_link where id = ? for update", linkId), () -> {
            long millis = timeToFailure(() -> links.findByIdForUpdateNowait(linkId));
            assertThat(millis).isLessThan(MUST_FAIL_WITHIN.toMillis());
        });
    }

    @Test
    void aFreeRowIsLockedAndReturned() {
        createChain();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long foundBatch = tx.execute(s -> batches.findByIdForUpdateNowait(batchId).orElseThrow().getId());
        Long foundLink = tx.execute(s -> links.findByIdForUpdateNowait(linkId).orElseThrow().getId());
        assertThat(foundBatch).isEqualTo(batchId);
        assertThat(foundLink).isEqualTo(linkId);
        Boolean missing = tx.execute(s -> batches.findByIdForUpdateNowait(-1L).isEmpty());
        assertThat(missing).isTrue();
    }

    /** Verwacht een lock-fout en geeft de verstreken tijd tot die fout terug. */
    private long timeToFailure(Runnable nowaitLock) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long start = System.nanoTime();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> nowaitLock.run()))
                .isInstanceOf(PessimisticLockingFailureException.class);
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /**
     * Thread A neemt het slot in een eigen open transactie en houdt het vast terwijl {@code whileHeld} (op de
     * testthread, een andere verbinding) draait; daarna gaat het slot vrij.
     */
    private void holdingRowLock(Runnable lockInOwnTransaction, Runnable whileHeld) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> a = holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                lockInOwnTransaction.run();
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("thread A took the row lock").isTrue();
            try {
                whileHeld.run();
            } finally {
                release.countDown();
            }
            a.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }
}
