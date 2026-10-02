package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** {@code uk_import_batch_open} (hoogstens één open batch per levering) en {@code uk_import_batch_attempt}. */
@DaoTest
class ImportBatchConstraintTest {

    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private DaoFixtures fixtures;

    @Test
    void aSecondOpenBatchOnTheSameDeliveryIsRefused() {
        DaoFixtures.Chain chain = fixtures.chain();
        batches.saveAndFlush(chain.newBatch(1));

        // De transactie is hierna afgebroken: geen verdere databasetoegang in deze test.
        assertThatThrownBy(() -> batches.saveAndFlush(chain.newBatch(2)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_import_batch_open");
    }

    @Test
    void aNewOpenBatchIsAcceptedOnceTheFirstOneIsFailed() {
        DaoFixtures.Chain chain = fixtures.chain();
        ImportBatch first = batches.saveAndFlush(chain.newBatch(1));
        assertThat(first.getOpenMarker()).isTrue();

        first.setStatus(ImportBatchStatus.FAILED);
        batches.saveAndFlush(first);
        assertThat(first.getOpenMarker()).isNull();

        ImportBatch second = batches.saveAndFlush(chain.newBatch(2));
        assertThat(second.getOpenMarker()).isTrue();
        assertThat(batches.findByDeliveryIdOrderByAttemptNoAsc(chain.delivery().getId())).hasSize(2);
    }

    @Test
    void theSameAttemptNumberTwiceForOneDeliveryAndRevisionIsRefusedEvenWhenNoBatchIsOpen() {
        DaoFixtures.Chain chain = fixtures.chain();
        ImportBatch first = batches.saveAndFlush(chain.newBatch(1));
        first.setStatus(ImportBatchStatus.FAILED);
        batches.saveAndFlush(first);

        assertThatThrownBy(() -> batches.saveAndFlush(chain.newBatch(1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_import_batch_attempt");
    }
}
