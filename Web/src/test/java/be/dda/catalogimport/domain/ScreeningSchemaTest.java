package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.ImportRowIssueRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Fase 2a (docs/design/fase2-screening-design.md) en fase 3a (docs/design/fase3-rules-design.md
 * par. 2): bewijst dat changeset 002 en 004 migreren, dat Hibernate de entiteiten met
 * {@code ddl-auto: validate} aanvaardt en dat de databaseconstraints van het screeningschema
 * werkelijk afdwingen wat ze beloven.
 * {@code import_candidate_stage} en {@code catalog_source_state} hebben geen entiteit en worden
 * via JdbcTemplate getest. Codes zijn per test uniek omdat de H2-database gedeeld is.
 */
@SpringBootTest
@ActiveProfiles("local")
class ScreeningSchemaTest {

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
    private DeliveryRepository deliveries;
    @Autowired
    private DeliveryFileRepository deliveryFiles;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private ImportMutationRepository mutations;
    @Autowired
    private ImportRowIssueRepository rowIssues;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Nieuwe revisiekolommen ----------------------------------------------------------------

    @Test
    void persistsTheSourceConfigurationColumnsOnAnImportDefinitionRevision() {
        Scenario s = scenario("REVCFG");
        ImportDefinitionRevision revision = s.revision();
        revision.setStructureCharset("ISO-8859-1");
        revision.setStructureQuoteChar(null);
        revision.setStructureHeaderLineNumber(3);
        revision.setStructureExpectedColumnCount(7);
        revision.setAccessDeliverySetKind("FULL_SNAPSHOT");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setRecordCanonicalisationVersion(1);
        revisions.saveAndFlush(revision);

        ImportDefinitionRevision found = revisions.findById(revision.getId()).orElseThrow();
        assertThat(found.getStructureFormat()).isEqualTo("CSV");
        assertThat(found.getStructureCharset()).isEqualTo("ISO-8859-1");
        assertThat(found.getStructureDelimiter()).isEqualTo(";");
        assertThat(found.getStructureQuoteChar()).isNull();
        assertThat(found.isStructureHasHeader()).isTrue();
        assertThat(found.getStructureHeaderLineNumber()).isEqualTo(3);
        assertThat(found.getStructureFieldReferenceKind()).isEqualTo("HEADER_NAME");
        assertThat(found.getStructureExpectedColumnCount()).isEqualTo(7);
        assertThat(found.getAccessDeliverySetKind()).isEqualTo("FULL_SNAPSHOT");
        assertThat(found.getRecordBasePriceField()).isEqualTo("PRIJS");
        assertThat(found.getRecordDescriptionField()).isEqualTo("OMSCHRIJVING");
        assertThat(found.getRecordCanonicalisationVersion()).isEqualTo(1);
    }

    @Test
    void appliesTheDocumentedDefaultsForTheOptionalSourceConfiguration() {
        ImportDefinitionRevision revision = scenario("REVDEF").revision();

        assertThat(revision.getStructureFormat()).isEqualTo("CSV");
        assertThat(revision.getStructureCharset()).isEqualTo("UTF-8");
        assertThat(revision.getStructureQuoteChar()).isEqualTo("\"");
        assertThat(revision.isStructureHasHeader()).isTrue();
        assertThat(revision.getStructureHeaderLineNumber()).isEqualTo(1);
        assertThat(revision.getStructureFieldReferenceKind()).isEqualTo("HEADER_NAME");
        assertThat(revision.getStructureExpectedColumnCount()).isNull();
        assertThat(revision.getAccessDeliverySetKind()).isEqualTo("UNDECLARED");
        assertThat(revision.getRecordBasePriceField()).isNull();
        assertThat(revision.getRecordDescriptionField()).isNull();
        assertThat(revision.getRecordCanonicalisationVersion()).isEqualTo(1);
    }

    @Test
    void requiresAnExplicitDelimiterBecauseTheMigrationDefaultIsDropped() {
        ImportDefinition definition = definition("REVDELIM");
        ImportDefinitionRevision revision = newRevision(definition, 1);
        revision.setStructureDelimiter(null);

        assertThatThrownBy(() -> revisions.saveAndFlush(revision))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Ook op databaseniveau: geen default meer, dus een insert zonder scheidingsteken faalt.
        assertThatThrownBy(() -> jdbc.update(
                "insert into import_definition_revision (import_definition_id, revision_number, status, "
                        + "access_version, access_config_hash, structure_version, structure_config_hash, "
                        + "record_rules_version, record_rules_config_hash, composite_config_hash, "
                        + "identity_profile_kind, identity_supplier_field, identity_supplier_group_field, "
                        + "identity_supplier_reference_field, created_at, created_by, updated_at) "
                        + "values (?, 9, 'DRAFT', 1, 'a', 1, 'b', 1, 'c', 'd', 'THREE_PART', 'S', 'G', 'R', "
                        + "current_timestamp, 'test', current_timestamp)", definition.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAHeaderlessRevisionThatStillReferencesFieldsByHeaderName() {
        ImportDefinitionRevision revision = newRevision(definition("REVNOHDR"), 1);
        revision.setStructureHasHeader(false);
        revision.setStructureFieldReferenceKind("HEADER_NAME");

        assertThatThrownBy(() -> revisions.saveAndFlush(revision))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsAHeaderlessRevisionThatReferencesFieldsByColumnIndex() {
        ImportDefinitionRevision revision = newRevision(definition("REVIDX"), 1);
        revision.setStructureHasHeader(false);
        revision.setStructureFieldReferenceKind("COLUMN_INDEX");
        revision.setIdentitySupplierField("1");
        revision.setIdentitySupplierGroupField("2");
        revision.setIdentitySupplierReferenceField("3");

        assertThatCode(() -> revisions.saveAndFlush(revision)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnUnsupportedFormatAndUnknownEnumeratedSourceConfiguration() {
        ImportDefinition definition = definition("REVFMT");
        ImportDefinitionRevision xlsx = newRevision(definition, 1);
        xlsx.setStructureFormat("XLSX");
        ImportDefinitionRevision badReference = newRevision(definition, 2);
        badReference.setStructureFieldReferenceKind("NONSENSE");
        ImportDefinitionRevision badSet = newRevision(definition, 3);
        badSet.setAccessDeliverySetKind("NONSENSE");

        assertThatThrownBy(() -> revisions.saveAndFlush(xlsx)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> revisions.saveAndFlush(badReference))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> revisions.saveAndFlush(badSet)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- import_batch --------------------------------------------------------------------------

    @Test
    void persistsAnImportBatchWithUnknownCountersLeftNull() {
        Scenario s = scenario("BATCH");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));

        ImportBatch found = batches.findById(batch.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ImportBatchStatus.RECEIVED);
        assertThat(found.getOpenMarker()).isTrue();
        assertThat(found.getStagedRowCount()).isZero();
        assertThat(found.getMutationProgressRowNumber()).isZero();
        assertThat(found.getRawRecordCount()).isNull();
        assertThat(found.getValidRecordCount()).isNull();
        assertThat(found.getContentMutationCount()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(batches.findByDeliveryIdAndOpenMarkerIsNotNull(s.delivery().getId())).isPresent();
    }

    @Test
    void allowsOnlyOneOpenBatchPerDeliveryAndReleasesTheMarkerOnATerminalStatus() {
        Scenario s = scenario("BOPEN");
        ImportBatch first = batches.saveAndFlush(s.newBatch(1));

        // Tweede open batch op dezelfde levering (andere poging) -> uk_import_batch_open.
        assertThatThrownBy(() -> batches.saveAndFlush(s.newBatch(2)))
                .isInstanceOf(DataIntegrityViolationException.class);

        for (ImportBatchStatus open : new ImportBatchStatus[] {
                ImportBatchStatus.SCREENING, ImportBatchStatus.MUTATING}) {
            first.setStatus(open);
            batches.saveAndFlush(first);
            assertThat(first.getOpenMarker()).isTrue();
        }

        first.setStatus(ImportBatchStatus.FAILED);
        batches.saveAndFlush(first);
        assertThat(first.getOpenMarker()).isNull();
        assertThat(batches.findByDeliveryIdAndOpenMarkerIsNotNull(s.delivery().getId())).isEmpty();

        // Na een terminale status is een nieuwe poging weer toegestaan.
        ImportBatch second = batches.saveAndFlush(s.newBatch(2));
        assertThat(second.getOpenMarker()).isTrue();

        // Meerdere terminale batches naast elkaar botsen niet op de open-constraint.
        second.setStatus(ImportBatchStatus.BLOCKED);
        batches.saveAndFlush(second);
        assertThat(batches.findByDeliveryIdOrderByAttemptNoAsc(s.delivery().getId())).hasSize(2);
    }

    @Test
    void releasesTheOpenMarkerForEveryTerminalStatus() {
        for (ImportBatchStatus status : ImportBatchStatus.values()) {
            Scenario s = scenario("BTERM-" + status.name());
            ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
            batch.setStatus(status);
            batches.saveAndFlush(batch);

            assertThat(batch.getOpenMarker()).as(status.name())
                    .isEqualTo(status.isTerminal() ? null : Boolean.TRUE);
        }
        assertThat(ImportBatchStatus.SCREENED.isTerminal()).isTrue();
        assertThat(ImportBatchStatus.BLOCKED.isTerminal()).isTrue();
        assertThat(ImportBatchStatus.FAILED.isTerminal()).isTrue();
        assertThat(ImportBatchStatus.BASELINE_ACCEPTED.isTerminal()).isTrue();
        assertThat(ImportBatchStatus.RECEIVED.isTerminal()).isFalse();
        assertThat(ImportBatchStatus.SCREENING.isTerminal()).isFalse();
        assertThat(ImportBatchStatus.MUTATING.isTerminal()).isFalse();
    }

    @Test
    void rejectsTheSameAttemptNumberTwiceForOneDeliveryAndRevision() {
        Scenario s = scenario("BATT");
        ImportBatch first = batches.saveAndFlush(s.newBatch(1));
        first.setStatus(ImportBatchStatus.SCREENED);
        batches.saveAndFlush(first);

        // Geen open batch meer, maar (levering, revisie, poging) is nog steeds bezet.
        assertThatThrownBy(() -> batches.saveAndFlush(s.newBatch(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> batches.saveAndFlush(s.newBatch(2))).doesNotThrowAnyException();
    }

    // --- import_mutation -----------------------------------------------------------------------

    @Test
    void rejectsADuplicateMutationIdempotencyKey() {
        ImportBatch batch = batches.saveAndFlush(scenario("MIDEM").newBatch(1));
        mutations.saveAndFlush(createMutation(batch, "MIDEM:1:HASH:OFFER"));

        assertThatThrownBy(() -> mutations.saveAndFlush(createMutation(batch, "MIDEM:1:HASH:OFFER")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> mutations.saveAndFlush(createMutation(batch, "MIDEM:1:OTHER:OFFER")))
                .doesNotThrowAnyException();
        assertThat(mutations.findByIdempotencyKey("MIDEM:1:HASH:OFFER")).isPresent();
    }

    @Test
    void acceptsAWellFormedMarkerAndAWellFormedCreateMutation() {
        ImportBatch batch = batches.saveAndFlush(scenario("MOK").newBatch(1));

        ImportMutation marker = mutations.saveAndFlush(marker(batch, "MOK:1:MARKER"));
        ImportMutation create = createMutation(batch, "MOK:1:H1:OFFER");
        create.setBeforeBasePrice(null);
        create.setAfterBasePrice(new BigDecimal("12.340000"));
        create.setDomainMask("PRICE");
        create.setIdentityDiscountState(DiscountCodeState.NOT_USED);
        mutations.saveAndFlush(create);

        assertThat(marker.getStatus()).isEqualTo(MutationStatus.RECORDED);
        assertThat(mutations.countByBatchIdAndActionType(batch.getId(), MutationActionType.IMPORT_MARKER))
                .isEqualTo(1);
        assertThat(mutations.findByBatchIdAndActionType(batch.getId(), MutationActionType.CREATE,
                PageRequest.of(0, 10)).getContent())
                .singleElement()
                .satisfies(found -> {
                    assertThat(found.getAfterBasePrice()).isEqualByComparingTo("12.34");
                    assertThat(found.getBasePriceCurrency()).isNull();
                    assertThat(found.getStatus()).isEqualTo(MutationStatus.PLANNED);
                });
        assertThat(mutations.findByBatchId(batch.getId(), PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
    }

    @Test
    void rejectsAMarkerThatCarriesAnIdentity() {
        ImportBatch batch = batches.saveAndFlush(scenario("MMID").newBatch(1));
        ImportMutation marker = marker(batch, "MMID:1:MARKER");
        marker.setIdentitySupplier("LEV");
        marker.setIdentitySupplierGroup("GRP");
        marker.setIdentitySupplierReference("REF");

        assertThatThrownBy(() -> mutations.saveAndFlush(marker)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAMarkerWithTheWrongDomainOrStatus() {
        ImportBatch batch = batches.saveAndFlush(scenario("MMWR").newBatch(1));

        ImportMutation wrongDomain = new ImportMutation(batch, MutationActionType.IMPORT_MARKER,
                MutationTargetDomain.OFFER, MutationStatus.RECORDED, "MMWR:1:MARKER-A");
        ImportMutation wrongStatus = new ImportMutation(batch, MutationActionType.IMPORT_MARKER,
                MutationTargetDomain.IMPORT, MutationStatus.PLANNED, "MMWR:1:MARKER-B");

        assertThatThrownBy(() -> mutations.saveAndFlush(wrongDomain))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> mutations.saveAndFlush(wrongStatus))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAContentMutationWithoutACompleteIdentityOrTargetingTheImportDomain() {
        ImportBatch batch = batches.saveAndFlush(scenario("MCRE").newBatch(1));

        ImportMutation noIdentity = new ImportMutation(batch, MutationActionType.CREATE,
                MutationTargetDomain.OFFER, MutationStatus.PLANNED, "MCRE:1:NOID:OFFER");
        ImportMutation partialIdentity = createMutation(batch, "MCRE:1:PART:OFFER");
        partialIdentity.setIdentitySupplierReference(null);
        ImportMutation importDomain = new ImportMutation(batch, MutationActionType.UPDATE,
                MutationTargetDomain.IMPORT, MutationStatus.PLANNED, "MCRE:1:DOM:OFFER");
        importDomain.setIdentitySupplier("LEV");
        importDomain.setIdentitySupplierGroup("GRP");
        importDomain.setIdentitySupplierReference("REF");

        assertThatThrownBy(() -> mutations.saveAndFlush(noIdentity))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> mutations.saveAndFlush(partialIdentity))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> mutations.saveAndFlush(importDomain))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- import_row_issue ----------------------------------------------------------------------

    @Test
    void persistsAndPagesRowIssuesPerBatch() {
        Scenario s = scenario("ISSUE");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        DeliveryFile file = s.file();
        ImportRowIssue issue = new ImportRowIssue(batch, file, 7L, "PRICE_UNREADABLE", "Prijs onleesbaar");
        issue.setFieldName("PRIJS");
        issue.setSourceValue("12,3x");
        rowIssues.saveAndFlush(issue);
        rowIssues.saveAndFlush(new ImportRowIssue(batch, file, 9L, "IDENTITY_COMPONENT_EMPTY", "Leeg"));

        assertThat(rowIssues.countByBatchId(batch.getId())).isEqualTo(2);
        assertThat(rowIssues.findByBatchId(batch.getId(), PageRequest.of(0, 1)).getContent()).hasSize(1);
        assertThat(rowIssues.findById(issue.getId()).orElseThrow().getSeverity()).isEqualTo(RowIssueSeverity.ERROR);
    }

    // --- Fase 3a, changeset 004-12: import_row_issue draagt alle drie de controleniveaus ---------

    /**
     * Een probleem op leverings- of structuurniveau hangt aan geen enkele bronregel en aan geen
     * enkel bestand. Beide kolommen moeten daarom {@code null} kunnen zijn; een verzonnen
     * regelnummer 0 zou dat verschil verbergen.
     */
    @Test
    void acceptsAnIssueWithoutARowNumberAndWithoutADeliveryFile() {
        Scenario s = scenario("ISSNULL");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));

        ImportRowIssue deliveryIssue = new ImportRowIssue(batch, null, null, "SOURCE_FILE_EMPTY",
                "The delivery file contains no lines");
        deliveryIssue.setSeverity(RowIssueSeverity.BLOCKING);
        deliveryIssue.setIssueDomain(IssueDomain.STRUCTURE_DATASET);
        deliveryIssue.setControlLevel(ControlLevel.DELIVERY);
        deliveryIssue.setImpactScope(ImpactScope.DELIVERY);
        deliveryIssue.setExpectedValue("1");
        rowIssues.saveAndFlush(deliveryIssue);

        ImportRowIssue found = rowIssues.findById(deliveryIssue.getId()).orElseThrow();
        assertThat(found.getRowNumber()).isNull();
        assertThat(found.getDeliveryFile()).isNull();
        assertThat(found.getSeverity()).isEqualTo(RowIssueSeverity.BLOCKING);
        assertThat(found.getIssueDomain()).isEqualTo(IssueDomain.STRUCTURE_DATASET);
        assertThat(found.getControlLevel()).isEqualTo(ControlLevel.DELIVERY);
        assertThat(found.getImpactScope()).isEqualTo(ImpactScope.DELIVERY);
        assertThat(found.getHandlingStatus()).isEqualTo(IssueHandlingStatus.DETECTED);
        assertThat(found.getExpectedValue()).isEqualTo("1");
        assertThat(found.getIssueGroupId()).isNull();
        assertThat(found.getOccurrenceSeq()).isNull();
        assertThat(found.getRuleConfigVersion()).isNull();
    }

    /**
     * Changeset 004-12 breidt de bestaande tabel uit in plaats van ze te vervangen: een rij die
     * enkel de fase 2-kolommen invult - zoals elke rij die vóór de migratie bestond - blijft geldig
     * en krijgt de gedocumenteerde defaults.
     */
    @Test
    void appliesTheDocumentedDefaultsSoPhaseTwoRowsStayValid() {
        Scenario s = scenario("ISSDEF");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));

        jdbc.update("insert into import_row_issue (batch_id, issue_code, message, created_at) "
                + "values (?, 'PRICE_UNREADABLE', 'legacy row', ?)", batch.getId(), OffsetDateTime.now());

        assertThat(rowIssues.findByBatchId(batch.getId(), PageRequest.of(0, 10)).getContent())
                .singleElement()
                .satisfies(found -> {
                    assertThat(found.getSeverity()).isEqualTo(RowIssueSeverity.ERROR);
                    assertThat(found.getIssueDomain()).isEqualTo(IssueDomain.MAPPING_VALIDATION);
                    assertThat(found.getControlLevel()).isEqualTo(ControlLevel.RECORD);
                    assertThat(found.getImpactScope()).isEqualTo(ImpactScope.RECORD);
                    assertThat(found.getHandlingStatus()).isEqualTo(IssueHandlingStatus.DETECTED);
                });
    }

    @Test
    void refusesAnIssueThatPointsToAnUnknownIssueGroup() {
        Scenario s = scenario("ISSFK");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));

        assertThatThrownBy(() -> jdbc.update("insert into import_row_issue (batch_id, issue_code, message, "
                        + "issue_group_id, created_at) values (?, 'PRICE_UNREADABLE', 'orphan', ?, ?)",
                batch.getId(), 999_999_999L, OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Fase 3a, changeset 004-4: import_issue_group (JDBC-only, gevuld vanaf bouwstap 3g) ------

    @Test
    void keepsOneIssueGroupPerSignatureAndAppliesItsDefaults() {
        Scenario s = scenario("GRP");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));

        insertIssueGroup(batch.getId(), "PRICE_UNREADABLE", "PRIJS|unreadable", 500L, 200);

        // uk_import_issue_group_signature: dezelfde signatuur is één groep, geen tweede rij.
        assertThatThrownBy(() -> insertIssueGroup(batch.getId(), "PRICE_UNREADABLE", "PRIJS|unreadable",
                500L, 200)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertIssueGroup(batch.getId(), "PRICE_UNREADABLE", "BEDRAG|unreadable",
                3L, 3)).doesNotThrowAnyException();

        Map<String, Object> group = jdbc.queryForMap("select incident_kind, is_bulk_incident, "
                        + "handling_status, occurrence_count, recorded_sample_count from import_issue_group "
                        + "where batch_id = ? and signature = ?", batch.getId(), "PRIJS|unreadable");
        assertThat(group.get("incident_kind")).isEqualTo("GENERIC");
        assertThat(group.get("is_bulk_incident")).isEqualTo(false);
        assertThat(group.get("handling_status")).isEqualTo("DETECTED");
        // Het volledige aantal en het aantal bewaarde voorbeelden lopen bewust uiteen (R-ISS-03).
        assertThat(((Number) group.get("occurrence_count")).longValue()).isEqualTo(500L);
        assertThat(((Number) group.get("recorded_sample_count")).intValue()).isEqualTo(200);
    }

    @Test
    void rejectsAnUnknownIncidentKindOnAnIssueGroup() {
        Scenario s = scenario("GRPKIND");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));

        assertThatThrownBy(() -> jdbc.update("insert into import_issue_group (batch_id, issue_code, "
                        + "signature, severity, issue_domain, control_level, impact_scope, incident_kind, "
                        + "occurrence_count, recorded_sample_count) "
                        + "values (?, 'PRICE_UNREADABLE', 'x', 'ERROR', 'PRICE', 'RECORD', 'RECORD', "
                        + "'NONSENSE', 1, 1)", batch.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertIssueGroup(Long batchId, String issueCode, String signature, long occurrences,
                                  int samples) {
        jdbc.update("insert into import_issue_group (batch_id, issue_code, signature, severity, "
                        + "issue_domain, control_level, impact_scope, occurrence_count, recorded_sample_count, "
                        + "first_detected_at, last_detected_at) "
                        + "values (?, ?, ?, 'ERROR', 'PRICE', 'RECORD', 'RECORD', ?, ?, ?, ?)",
                batchId, issueCode, signature, occurrences, samples, OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    // --- import_candidate_stage (JDBC-only) ----------------------------------------------------

    @Test
    void rejectsADuplicatePhysicalRowInTheStageButAllowsTheSameIdentityOnAnotherRow() {
        Scenario s = scenario("STAGE");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        DeliveryFile file = s.file();
        byte[] identity = sha256("stage-identity");

        insertStage(batch.getId(), file.getId(), 2, identity);

        // pk_import_candidate_stage (batch_id, row_number).
        assertThatThrownBy(() -> insertStage(batch.getId(), file.getId(), 2, sha256("other")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // De identiteitsindex is bewust niet unique: duplicaten moeten gestaged en gemeld kunnen worden.
        assertThatCode(() -> insertStage(batch.getId(), file.getId(), 3, identity)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
                "select count(*) from import_candidate_stage where batch_id = ? and identity_hash = ?",
                Long.class, batch.getId(), identity)).isEqualTo(2L);
    }

    @Test
    void rejectsAStageRowWithoutABasePriceInsteadOfDefaultingToZero() {
        Scenario s = scenario("STNULL");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));

        assertThatThrownBy(() -> jdbc.update(
                "insert into import_candidate_stage (batch_id, row_number, delivery_file_id, identity_supplier, "
                        + "identity_supplier_group, identity_supplier_reference, identity_discount_state, "
                        + "identity_hash, base_price, article_fingerprint, price_fingerprint, "
                        + "combined_fingerprint, mutation_key_prefix, created_at) "
                        + "values (?, 1, ?, 'L', 'G', 'R', 'NOT_USED', ?, null, ?, ?, ?, 'p', ?)",
                batch.getId(), s.file().getId(), sha256("x"), sha256("a"), sha256("b"), sha256("c"),
                OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- catalog_source_state (JDBC-only) ------------------------------------------------------

    @Test
    void rejectsADuplicateIdentityInTheSourceStateOfOneLinkButNotAcrossLinks() {
        Scenario a = scenario("CSSA");
        Scenario b = scenario("CSSB");
        ImportBatch batchA = batches.saveAndFlush(a.newBatch(1));
        ImportBatch batchB = batches.saveAndFlush(b.newBatch(1));
        byte[] identity = sha256("shared-supplier-key");

        insertSourceState(a.link().getId(), a.delivery().getId(), batchA.getId(), identity);

        assertThatThrownBy(() -> insertSourceState(a.link().getId(), a.delivery().getId(), batchA.getId(), identity))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Dezelfde leverancierssleutel onder een andere importkoppeling is een andere bronstaat.
        assertThatCode(() -> insertSourceState(b.link().getId(), b.delivery().getId(), batchB.getId(), identity))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from catalog_source_state where identity_hash = ?",
                Long.class, identity)).isEqualTo(2L);
    }

    // --- Helpers -------------------------------------------------------------------------------

    private ImportMutation createMutation(ImportBatch batch, String idempotencyKey) {
        ImportMutation mutation = new ImportMutation(batch, MutationActionType.CREATE,
                MutationTargetDomain.OFFER, MutationStatus.PLANNED, idempotencyKey);
        mutation.setIdentitySupplier("LEV");
        mutation.setIdentitySupplierGroup("GRP");
        mutation.setIdentitySupplierReference("REF-" + idempotencyKey);
        return mutation;
    }

    private ImportMutation marker(ImportBatch batch, String idempotencyKey) {
        ImportMutation marker = new ImportMutation(batch, MutationActionType.IMPORT_MARKER,
                MutationTargetDomain.IMPORT, MutationStatus.RECORDED, idempotencyKey);
        marker.setResultSummary("outcome=SCREENED;completenessProven=false");
        return marker;
    }

    private void insertStage(Long batchId, Long fileId, long rowNumber, byte[] identityHash) {
        jdbc.update("insert into import_candidate_stage (batch_id, row_number, delivery_file_id, identity_supplier, "
                        + "identity_supplier_group, identity_supplier_reference, identity_discount_state, "
                        + "identity_hash, base_price, article_fingerprint, price_fingerprint, "
                        + "combined_fingerprint, mutation_key_prefix, created_at) "
                        + "values (?, ?, ?, 'LEV', 'GRP', 'REF', 'NOT_USED', ?, ?, ?, ?, ?, 'prefix', ?)",
                batchId, rowNumber, fileId, identityHash, new BigDecimal("1.500000"),
                sha256("article"), sha256("price"), sha256("combined"), OffsetDateTime.now());
    }

    private void insertSourceState(Long linkId, Long deliveryId, Long batchId, byte[] identityHash) {
        jdbc.update("insert into catalog_source_state (import_link_id, identity_hash, identity_supplier, "
                        + "identity_supplier_group, identity_supplier_reference, identity_discount_state, "
                        + "identity_profile_kind, article_fingerprint, price_fingerprint, combined_fingerprint, "
                        + "base_price, state_origin, last_change_delivery_id, last_change_batch_id, "
                        + "created_at, updated_at) "
                        + "values (?, ?, 'LEV', 'GRP', 'REF', 'NOT_USED', 'THREE_PART', ?, ?, ?, ?, "
                        + "'BASELINE_ACCEPTED', ?, ?, ?, ?)",
                linkId, identityHash, sha256("article"), sha256("price"), sha256("combined"),
                new BigDecimal("1.500000"), deliveryId, batchId, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ImportDefinition definition(String prefix) {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(prefix + "-ORG", prefix + "-ORG BV", SourceOrganisationType.SUPPLIER));
        return definitions.saveAndFlush(
                new ImportDefinition(organisation, prefix + "-DEF", prefix + " catalogus", "beheerder@example.test"));
    }

    private ImportDefinitionRevision newRevision(ImportDefinition definition, int revisionNumber) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, revisionNumber,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("LEV_GROEP");
        revision.setIdentitySupplierReferenceField("LEV_REFERENTIE");
        revision.setStructureDelimiter(";");
        return revision;
    }

    /** Volledige keten tot en met levering, zodat batches aangemaakt kunnen worden. */
    private Scenario scenario(String prefix) {
        ImportDefinition definition = definition(prefix);
        ImportDefinitionRevision revision = revisions.saveAndFlush(newRevision(definition, 1));
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(prefix + "-SUP", prefix + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(prefix + "-LINK", prefix + "-LINK koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, prefix + "-taak", TaskTriggerType.MANUAL));
        Delivery delivery = deliveries.saveAndFlush(new Delivery(task, "manual:" + prefix, Instant.now()));
        return new Scenario(revision, link, delivery);
    }

    private final class Scenario {
        private final ImportDefinitionRevision revision;
        private final ImportLink link;
        private final Delivery delivery;
        private Optional<DeliveryFile> file = Optional.empty();

        private Scenario(ImportDefinitionRevision revision, ImportLink link, Delivery delivery) {
            this.revision = revision;
            this.link = link;
            this.delivery = delivery;
        }

        ImportDefinitionRevision revision() {
            return revision;
        }

        ImportLink link() {
            return link;
        }

        Delivery delivery() {
            return delivery;
        }

        ImportBatch newBatch(int attemptNo) {
            return new ImportBatch(delivery, link, revision, attemptNo, "tester@example.test");
        }

        DeliveryFile file() {
            if (file.isEmpty()) {
                file = Optional.of(deliveryFiles.saveAndFlush(new DeliveryFile(delivery, 1, "levering.csv",
                        "2026/09/18/" + delivery.getId() + "/levering.csv", "a".repeat(64), 100L)));
            }
            return file.get();
        }
    }
}
