package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.MutationTargetDomain;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** {@code uk_import_mutation_idempotency}: dezelfde mutatiesleutel kan nooit twee keer bestaan. */
@DaoTest
class ImportMutationIdempotencyTest {

    @Autowired
    private ImportMutationRepository mutations;
    @Autowired
    private DaoFixtures fixtures;

    @Test
    void aDuplicateIdempotencyKeyIsRefusedByTheUniqueConstraint() {
        ImportBatch batch = fixtures.batch();
        String key = "IDEM-" + UUID.randomUUID();
        mutations.saveAndFlush(create(batch, key));

        // Na deze exception is de transactie afgebroken: hierna geen databasetoegang meer in deze test.
        assertThatThrownBy(() -> mutations.saveAndFlush(create(batch, key)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_import_mutation_idempotency");
    }

    @Test
    void aDifferentKeyIsAccepted() {
        ImportBatch batch = fixtures.batch();
        String key = "IDEM-" + UUID.randomUUID();
        mutations.saveAndFlush(create(batch, key));
        mutations.saveAndFlush(create(batch, key + "-OTHER"));

        assertThat(mutations.findByIdempotencyKey(key)).isPresent();
        assertThat(mutations.findByIdempotencyKey(key + "-OTHER")).isPresent();
    }

    private static ImportMutation create(ImportBatch batch, String key) {
        ImportMutation mutation = new ImportMutation(batch, MutationActionType.CREATE,
                MutationTargetDomain.OFFER, MutationStatus.PLANNED, key);
        mutation.setIdentitySupplier("LEV");
        mutation.setIdentitySupplierGroup("GRP");
        mutation.setIdentitySupplierReference("REF-" + key);
        return mutation;
    }
}
