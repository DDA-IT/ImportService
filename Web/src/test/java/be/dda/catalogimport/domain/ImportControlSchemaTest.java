package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * Fase 1 (AGENT.md §3): bewijst dat het Liquibase-schema migreert, dat Hibernate het met
 * {@code ddl-auto: validate} aanvaardt, en dat de databaseconstraints die als langetermijnkeuze
 * gemaakt zijn (§2 principe 6) werkelijk op databaseniveau afdwingen wat ze beloven.
 * Er wordt bewust geen business logic getest — die bestaat in deze fase nog niet.
 */
@SpringBootTest
@ActiveProfiles("local")
class ImportControlSchemaTest {

    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private TaskRunRepository runs;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private DeliveryFileRepository deliveryFiles;

    // --- Normaal scenario ---------------------------------------------------------------------

    @Test
    void persistsTheCompleteChainFromSourceOrganisationToDeliveryFile() {
        SourceOrganisation association = organisation("HAPPY-VROOAM", SourceOrganisationType.PURCHASING_ASSOCIATION);
        SourceOrganisation supplier = organisation("HAPPY-SUP-A", SourceOrganisationType.SUPPLIER);
        ImportDefinition definition = definition(association, "HAPPY-DEF");
        ImportDefinitionRevision revision = revision(definition, 1, IdentityProfileKind.THREE_PART, null);
        ImportLink link = link("HAPPY-LINK", definition, supplier, "PSARF012");
        CatalogImportTask task = task(link, "Dagelijkse catalogus");
        TaskRun run = runs.saveAndFlush(new TaskRun(task, Instant.now(), "importer@example.test"));
        Delivery delivery = delivery(task, "sftp://vrooam/export|ABP4.csv|2026-09-18T04:00:00Z");
        delivery.setTaskRun(run);
        delivery.setExpectedFileCount(1);
        delivery.setActualFileCount(1);
        delivery.setExpectedRecordCount(1_000L);
        delivery.setActualRecordCount(1_000L);
        delivery.setCompletenessProven(true);
        deliveries.saveAndFlush(delivery);
        DeliveryFile file = deliveryFiles.saveAndFlush(new DeliveryFile(delivery, 1, "ABP4.csv",
                "s3://catalog-archive/2026/09/18/ABP4.csv", "a".repeat(64), 5_242_880L));

        assertThat(sourceOrganisations.findByCode("HAPPY-VROOAM"))
                .hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(association.getId()));
        assertThat(definitions.findBySourceOrganisationIdAndCode(association.getId(), "HAPPY-DEF")).isPresent();
        assertThat(revisions.findByImportDefinitionIdAndRevisionNumber(definition.getId(), 1)).isPresent();
        assertThat(links.findByCode("HAPPY-LINK")).isPresent();
        assertThat(tasks.findByImportLinkIdAndName(link.getId(), "Dagelijkse catalogus")).isPresent();
        assertThat(runs.findByTaskIdAndConcurrencyTokenIsNotNull(task.getId())).isPresent();
        assertThat(deliveries.findByTaskIdAndIdempotencyKey(task.getId(), delivery.getIdempotencyKey())).isPresent();
        assertThat(deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(delivery.getId()))
                .singleElement()
                .satisfies(found -> assertThat(found.getId()).isEqualTo(file.getId()));
        // Auditvelden worden automatisch gezet, niet door de aanroeper.
        assertThat(revision.getCreatedAt()).isNotNull();
        assertThat(revision.getUpdatedAt()).isNotNull();
        assertThat(file.getHashAlgorithm()).isEqualTo("SHA-256");
    }

    // --- Identiteitsprofiel: "niet gemapt" (null) versus "gemapt maar leeg" ("") ---------------

    @Test
    void storesAFourPartIdentityProfileWithAMappedDiscountCodeField() {
        ImportDefinition definition = definition(organisation("ID4-ORG", SourceOrganisationType.SUPPLIER), "ID4-DEF");
        ImportDefinitionRevision revision =
                revision(definition, 1, IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, "KORTINGSCODE");

        assertThat(revision.getIdentityDiscountCodeField()).isEqualTo("KORTINGSCODE");
        assertThat(revision.getIdentityProfileKind()).isEqualTo(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE);
    }

    @Test
    void rejectsAThreePartProfileThatStillMapsADiscountCodeField() {
        ImportDefinition definition = definition(organisation("ID3-ORG", SourceOrganisationType.SUPPLIER), "ID3-DEF");

        assertThatThrownBy(() -> revision(definition, 1, IdentityProfileKind.THREE_PART, "KORTINGSCODE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAFourPartProfileWithoutAMappedDiscountCodeField() {
        ImportDefinition definition = definition(organisation("ID4B-ORG", SourceOrganisationType.SUPPLIER), "ID4B-DEF");

        assertThatThrownBy(() ->
                revision(definition, 1, IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Hoogstens een actieve revisie per definitie -------------------------------------------

    @Test
    void allowsManyNonActiveRevisionsButOnlyOneActiveRevisionPerDefinition() {
        ImportDefinition definition = definition(organisation("REV-ORG", SourceOrganisationType.SUPPLIER), "REV-DEF");
        ImportDefinitionRevision first = revision(definition, 1, IdentityProfileKind.THREE_PART, null);
        ImportDefinitionRevision second = revision(definition, 2, IdentityProfileKind.THREE_PART, null);
        ImportDefinitionRevision third = revision(definition, 3, IdentityProfileKind.THREE_PART, null);

        first.setStatus(RevisionStatus.ACTIVE);
        revisions.saveAndFlush(first);
        second.setStatus(RevisionStatus.ACTIVE);

        assertThatThrownBy(() -> revisions.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
        // Twee niet-actieve revisies naast elkaar blijven wel toegestaan.
        third.setStatus(RevisionStatus.SUPERSEDED);
        assertThatCode(() -> revisions.saveAndFlush(third)).doesNotThrowAnyException();
        assertThat(revisions.findByImportDefinitionIdAndStatus(definition.getId(), RevisionStatus.ACTIVE))
                .isPresent();
    }

    @Test
    void rejectsADuplicateRevisionNumberWithinTheSameDefinition() {
        ImportDefinition definition = definition(organisation("REVN-ORG", SourceOrganisationType.SUPPLIER), "REVN-DEF");
        revision(definition, 1, IdentityProfileKind.THREE_PART, null);

        assertThatThrownBy(() -> revision(definition, 1, IdentityProfileKind.THREE_PART, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Concurrency: hoogstens een lopende run per taak ---------------------------------------

    @Test
    void preventsASecondConcurrentRunAndReleasesTheTokenWhenTheRunFinishes() {
        CatalogImportTask task = task(link("RUN-LINK",
                definition(organisation("RUN-ORG", SourceOrganisationType.SUPPLIER), "RUN-DEF"),
                organisation("RUN-SUP", SourceOrganisationType.SUPPLIER), "PSARF020"), "Run-taak");
        TaskRun running = runs.saveAndFlush(new TaskRun(task, Instant.now(), "user-a"));

        assertThatThrownBy(() -> runs.saveAndFlush(new TaskRun(task, Instant.now(), "user-b")))
                .isInstanceOf(DataIntegrityViolationException.class);

        running.setStatus(TaskRunStatus.COMPLETED);
        running.setFinishedAt(Instant.now());
        runs.saveAndFlush(running);
        assertThat(running.getConcurrencyToken()).isNull();

        // Na afronding mag een volgende run starten; twee afgeronde runs botsen niet.
        TaskRun next = runs.saveAndFlush(new TaskRun(task, Instant.now(), "user-b"));
        next.setStatus(TaskRunStatus.FAILED);
        runs.saveAndFlush(next);
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(task.getId())).hasSize(2);
        assertThat(runs.findByTaskIdAndConcurrencyTokenIsNotNull(task.getId())).isEmpty();
    }

    @Test
    void allowsConcurrentRunsWhenTheTaskExplicitlyPermitsThem() {
        CatalogImportTask task = task(link("PAR-LINK",
                definition(organisation("PAR-ORG", SourceOrganisationType.SUPPLIER), "PAR-DEF"),
                organisation("PAR-SUP", SourceOrganisationType.SUPPLIER), "PSARF021"), "Parallelle taak");
        task.setPreventConcurrentRuns(false);
        tasks.saveAndFlush(task);

        runs.saveAndFlush(new TaskRun(task, Instant.now(), "user-a"));
        assertThatCode(() -> runs.saveAndFlush(new TaskRun(task, Instant.now(), "user-b")))
                .doesNotThrowAnyException();
    }

    // --- Levering: retry versus echte herlevering ----------------------------------------------

    @Test
    void rejectsATechnicalRetryOfTheSameDeliveryRegistration() {
        CatalogImportTask task = deliveryTask("RETRY");
        delivery(task, "sftp://vrooam|ABP4.csv|etag-1");

        assertThatThrownBy(() -> delivery(task, "sftp://vrooam|ABP4.csv|etag-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(task.getId())).hasSize(1);
    }

    @Test
    void acceptsAnIdenticalRedeliveryAsANewDeliveryInsteadOfSkippingIt() {
        CatalogImportTask task = deliveryTask("REDELIVERY");
        String identicalContentHash = "b".repeat(64);
        Delivery yesterday = delivery(task, "sftp://vrooam|ABP4.csv|etag-1");
        deliveryFiles.saveAndFlush(new DeliveryFile(yesterday, 1, "ABP4.csv",
                "s3://archive/day1/ABP4.csv", identicalContentHash, 100L));

        // Zelfde inhoud, nieuwe levering: dit mag nooit stil overgeslagen worden.
        Delivery today = delivery(task, "sftp://vrooam|ABP4.csv|etag-2");
        today.setSupersedesDelivery(yesterday);
        deliveries.saveAndFlush(today);
        deliveryFiles.saveAndFlush(new DeliveryFile(today, 1, "ABP4.csv",
                "s3://archive/day2/ABP4.csv", identicalContentHash, 100L));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(task.getId())).hasSize(2);
        assertThat(deliveryFiles.findByContentHash(identicalContentHash)).hasSize(2);
        assertThat(today.getSupersedesDelivery()).isSameAs(yesterday);
    }

    @Test
    void rejectsTheSameFileRegisteredTwiceWithinOneDelivery() {
        Delivery delivery = delivery(deliveryTask("DUPFILE"), "sftp://vrooam|ABP4.csv|etag-3");
        deliveryFiles.saveAndFlush(new DeliveryFile(delivery, 1, "ABP4.csv",
                "s3://archive/ABP4.csv", "c".repeat(64), 10L));

        assertThatThrownBy(() -> deliveryFiles.saveAndFlush(new DeliveryFile(delivery, 2, "ABP4.csv",
                "s3://archive/ABP4-copy.csv", "c".repeat(64), 10L)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> deliveryFiles.saveAndFlush(new DeliveryFile(delivery, 1, "OTHER.csv",
                "s3://archive/OTHER.csv", "d".repeat(64), 10L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leavesUnknownCompletenessCountsNullInsteadOfSilentlyZero() {
        Delivery delivery = delivery(deliveryTask("COMPLETENESS"), "sftp://vrooam|ABP4.csv|etag-4");

        assertThat(delivery.getExpectedRecordCount()).isNull();
        assertThat(delivery.getActualRecordCount()).isNull();
        assertThat(delivery.isCompletenessProven()).isFalse();
    }

    // --- Overige uniciteit ---------------------------------------------------------------------

    @Test
    void rejectsADuplicateSourceOrganisationCode() {
        organisation("DUP-ORG", SourceOrganisationType.SUPPLIER);

        assertThatThrownBy(() -> organisation("DUP-ORG", SourceOrganisationType.PURCHASING_ASSOCIATION))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTheSameDefinitionCodeUnderADifferentSourceOrganisation() {
        SourceOrganisation first = organisation("SCOPE-ORG-A", SourceOrganisationType.PURCHASING_ASSOCIATION);
        SourceOrganisation second = organisation("SCOPE-ORG-B", SourceOrganisationType.PURCHASING_ASSOCIATION);
        definition(first, "SHARED-CODE");

        assertThatCode(() -> definition(second, "SHARED-CODE")).doesNotThrowAnyException();
        assertThatThrownBy(() -> definition(first, "SHARED-CODE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAScheduledTaskWithoutATriggerExpression() {
        ImportLink link = link("TRIG-LINK",
                definition(organisation("TRIG-ORG", SourceOrganisationType.SUPPLIER), "TRIG-DEF"),
                organisation("TRIG-SUP", SourceOrganisationType.SUPPLIER), "PSARF030");
        CatalogImportTask scheduled = new CatalogImportTask(link, "Geplande taak", TaskTriggerType.SCHEDULED);

        assertThatThrownBy(() -> tasks.saveAndFlush(scheduled))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Helpers -------------------------------------------------------------------------------

    private SourceOrganisation organisation(String code, SourceOrganisationType type) {
        return sourceOrganisations.saveAndFlush(new SourceOrganisation(code, code + " BV", type));
    }

    private ImportDefinition definition(SourceOrganisation organisation, String code) {
        return definitions.saveAndFlush(
                new ImportDefinition(organisation, code, code + " catalogus", "beheerder@example.test"));
    }

    private ImportDefinitionRevision revision(ImportDefinition definition, int revisionNumber,
                                              IdentityProfileKind kind, String discountCodeField) {
        ImportDefinitionRevision revision =
                new ImportDefinitionRevision(definition, revisionNumber, kind, "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("LEV_GROEP");
        revision.setIdentitySupplierReferenceField("LEV_REFERENTIE");
        revision.setIdentityDiscountCodeField(discountCodeField);
        // Fase 2: het scheidingsteken is verplicht en heeft na migratie geen databasedefault meer.
        revision.setStructureDelimiter(";");
        return revisions.saveAndFlush(revision);
    }

    private ImportLink link(String code, ImportDefinition definition, SourceOrganisation supplier, String library) {
        return links.saveAndFlush(new ImportLink(code, code + " koppeling", definition, supplier, library));
    }

    private CatalogImportTask task(ImportLink link, String name) {
        return tasks.saveAndFlush(new CatalogImportTask(link, name, TaskTriggerType.MANUAL));
    }

    private CatalogImportTask deliveryTask(String prefix) {
        return task(link(prefix + "-LINK",
                definition(organisation(prefix + "-ORG", SourceOrganisationType.SUPPLIER), prefix + "-DEF"),
                organisation(prefix + "-SUP", SourceOrganisationType.SUPPLIER), "PSARF040"), prefix + "-taak");
    }

    private Delivery delivery(CatalogImportTask task, String idempotencyKey) {
        return deliveries.saveAndFlush(new Delivery(task, idempotencyKey, Instant.now()));
    }
}
