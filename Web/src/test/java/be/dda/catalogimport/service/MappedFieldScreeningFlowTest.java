package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRowIssueRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ControlLevel;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRowIssue;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.FieldValueMapper;
import be.dda.catalogimport.service.support.ImportValueRules;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fase 3c (ontwerp fase 3, R-REC-01..R-REC-09 en par. 3.5): de volledige screening van een levering
 * waarvan de revisie <b>veldmappings</b> heeft, tegen de echte service, DAO's, het bestandsarchief,
 * {@code accept-baseline} en H2.
 * <p>
 * <b>Wat hier bewezen wordt.</b>
 * <ul>
 *   <li>Een fout in een gemapt veld verwerpt <b>enkel die regel</b> — met haar foutcode, regelnummer,
 *       logische veldnaam en de bronwaarde — en blokkeert de levering niet.</li>
 *   <li>De gemapte velden zitten in de artikelvingerafdruk (canonicalisatieversie 2): een identieke
 *       herlevering na een aanvaarde nulmeting levert nul mutaties op, en een gewijzigd gemapt veld
 *       levert exact één {@code UPDATE} met {@code domain_mask=ARTICLE} op. Zonder versie 2 zou die
 *       wijziging onzichtbaar blijven.</li>
 *   <li>Een revisie <b>zonder</b> mappings draait nog exact zoals in fase 2: dezelfde
 *       vingerafdrukken, byte voor byte, en geen referentiedeel.</li>
 * </ul>
 * De microbatch- en chunkgrootte staan op 2, zodat elk scenario meerdere commits doorloopt. Elke test
 * bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=2",
        "catalogimport.screening.mutation-chunk-size=2"})
@ActiveProfiles("local")
class MappedFieldScreeningFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String MAPPED_HEADER =
            "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;E_LEV;BARCODE\n";
    private static final String PLAIN_HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";

    /** De barcode mag hoogstens 8 tekens lang zijn; regel 4 levert er negen. */
    private static final String[] MAPPED_ROWS = {
            "ACME;G1;R1;1,50;Boormachine;LEV-001;1234567",
            "ACME;G1;R2;2,25;Schroevendraaier;LEV-002;2234567",
            "ACME;G1;R3;3,00;Hamer;LEV-003;3234567",
            "ACME;G1;R4;4,00;Zaag;LEV-004;412345678"};

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private SourceStateBaselineService baseline;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportFieldCatalogRepository fieldCatalog;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private ImportRowIssueRepository rowIssues;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Recordvalidatie van de gemapte velden ----------------------------------------------------

    @Test
    void rejectsOnlyTheRowWhoseMappedFieldIsUnusableAndCreatesTheRest() {
        Fixture fixture = fixture("MAPREJ", true);
        Delivered delivered = deliver(fixture, "REF-1", csv(MAPPED_HEADER, MAPPED_ROWS));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rawRecordCount()).isEqualTo(4L);
        assertThat(outcome.validRecordCount()).isEqualTo(3L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(1L);
        assertThat(outcome.newCount()).isEqualTo(3L);
        assertThat(outcome.contentMutationCount()).isEqualTo(3L);
        assertThat(outcome.blockedCode()).isNull();

        assertThat(stagedReferences(delivered)).containsExactly("R1", "R2", "R3");
        assertThat(mutationReferences(delivered)).containsExactly("R1", "R2", "R3");

        // De melding draagt de logische veldnaam uit de catalogus en de bronwaarde (meldingsstijl
        // par. 15.12), niet een kolomnummer waar niemand iets aan heeft.
        assertThat(issues(delivered)).singleElement().satisfies(issue -> {
            assertThat(issue.getIssueCode()).isEqualTo(FieldValueMapper.CODE_VALUE_TOO_LONG);
            assertThat(issue.getSeverity()).isEqualTo(RowIssueSeverity.ERROR);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.RECORD);
            assertThat(issue.getRowNumber()).isEqualTo(5L);
            assertThat(issue.getFieldName()).isEqualTo("Leveranciersbarcode");
            assertThat(issue.getSourceValue()).isEqualTo("412345678");
        });

        // Canonicalisatieversie 2 vult het referentiedeel al, ook zonder kritieke referenties.
        assertThat(referenceFingerprints(delivered)).hasSize(3).allSatisfy(fingerprint ->
                assertThat(fingerprint).isNotNull());
    }

    // --- Delta op een gemapt catalogusveld ---------------------------------------------------------

    @Test
    void anIdenticalRedeliveryAfterABaselineProducesNoMutationsUnderVersionTwo() {
        Fixture fixture = fixture("MAPSAME", true);
        Delivered first = deliver(fixture, "REF-1", csv(MAPPED_HEADER, MAPPED_ROWS));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), "tester@example.test", "nulmeting voor de regressietest");

        Delivered second = deliver(fixture, "REF-2", csv(MAPPED_HEADER, MAPPED_ROWS));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.unchangedCount()).isEqualTo(3L);
        assertThat(outcome.newCount()).isZero();
        assertThat(outcome.changedCount()).isZero();
        assertThat(outcome.contentMutationCount()).isZero();
        // De bronstaat draagt het referentiedeel van versie 2; anders zou de gecombineerde hash niet
        // meer uit haar vier delen te herleiden zijn.
        assertThat(jdbc.queryForObject("select count(*) from catalog_source_state "
                        + "where import_link_id = ? and reference_fingerprint is not null", Long.class,
                fixture.linkId())).isEqualTo(3L);
    }

    @Test
    void aChangedMappedCatalogueFieldBecomesExactlyOneUpdateWithAnArticleDomainMask() {
        Fixture fixture = fixture("MAPUPD", true);
        Delivered first = deliver(fixture, "REF-1", csv(MAPPED_HEADER, MAPPED_ROWS));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), "tester@example.test", "nulmeting voor de regressietest");

        String[] changed = MAPPED_ROWS.clone();
        // Enkel de gemapte leveranciersidentiteit wijzigt; prijs en omschrijving blijven gelijk.
        changed[1] = "ACME;G1;R2;2,25;Schroevendraaier;LEV-999;2234567";
        Delivered second = deliver(fixture, "REF-2", csv(MAPPED_HEADER, changed));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.changedCount()).isEqualTo(1L);
        assertThat(outcome.unchangedCount()).isEqualTo(2L);
        assertThat(outcome.contentMutationCount()).isEqualTo(1L);
        assertThat(mutations(second.batchId())).singleElement().satisfies(mutation -> {
            assertThat(mutation.actionType()).isEqualTo("UPDATE");
            assertThat(mutation.identitySupplierReference()).isEqualTo("R2");
            // De prijs is niet gewijzigd: enkel het artikeldeel staat in het masker.
            assertThat(mutation.domainMask()).isEqualTo("ARTICLE");
            assertThat(mutation.beforeBasePrice()).isEqualByComparingTo("2.25");
            assertThat(mutation.afterBasePrice()).isEqualByComparingTo("2.25");
        });
    }

    // --- Regressie: een revisie zonder mappings blijft fase 2 -------------------------------------

    @Test
    void aRevisionWithoutMappingsKeepsItsVersionOneFingerprintsByteForByte() {
        Fixture fixture = fixture("V1REG", false);
        Delivered delivered = deliver(fixture, "REF-1", csv(PLAIN_HEADER,
                "ACME;G1;R1;1,50;Boormachine",
                "ACME;G1;R2;2,25;Schroevendraaier"));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validRecordCount()).isEqualTo(2L);
        assertThat(outcome.rejectedRecordCount()).isZero();
        assertThat(outcome.newCount()).isEqualTo(2L);

        // Exact de vingerafdrukken van fase 2: de artikelhash dekt enkel de omschrijving en er is geen
        // referentiedeel. Een afwijking hier zou elke bestaande bronstaat als CHANGED laten uitkomen.
        byte[] expectedArticle = ImportValueRules.sha256Utf8(ImportValueRules.canonical(1, "Boormachine"));
        assertThat(jdbc.queryForObject("select article_fingerprint from import_candidate_stage "
                        + "where batch_id = ? and row_number = 2", byte[].class, delivered.batchId()))
                .isEqualTo(expectedArticle);
        assertThat(referenceFingerprints(delivered)).hasSize(2).allSatisfy(fingerprint ->
                assertThat(fingerprint).isNull());
        assertThat(issues(delivered)).isEmpty();
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private static byte[] csv(String header, String... rows) {
        return (header + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private List<ImportRowIssue> issues(Delivered delivered) {
        return rowIssues.findByBatchId(delivered.batchId(), PageRequest.of(0, 100)).getContent();
    }

    private List<String> stagedReferences(Delivered delivered) {
        return jdbc.queryForList("select identity_supplier_reference from import_candidate_stage "
                + "where batch_id = ? order by row_number", String.class, delivered.batchId());
    }

    private List<byte[]> referenceFingerprints(Delivered delivered) {
        return jdbc.query("select reference_fingerprint from import_candidate_stage where batch_id = ? "
                        + "order by row_number", (resultSet, index) -> resultSet.getBytes(1),
                delivered.batchId());
    }

    private List<String> mutationReferences(Delivered delivered) {
        return jdbc.queryForList("select identity_supplier_reference from import_mutation "
                        + "where batch_id = ? and action_type <> 'IMPORT_MARKER' order by source_row_number",
                String.class, delivered.batchId());
    }

    private List<MutationRow> mutations(long batchId) {
        return jdbc.query("select action_type, identity_supplier_reference, domain_mask, "
                        + "before_base_price, after_base_price from import_mutation "
                        + "where batch_id = ? and action_type <> 'IMPORT_MARKER' order by id",
                (resultSet, index) -> new MutationRow(resultSet.getString(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getBigDecimal(4), resultSet.getBigDecimal(5)),
                batchId);
    }

    private record MutationRow(String actionType, String identitySupplierReference, String domainMask,
                               java.math.BigDecimal beforeBasePrice, java.math.BigDecimal afterBasePrice) {
    }

    private Delivered deliver(Fixture fixture, String reference, byte[] content) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(content)).delivery();
        return new Delivered(deliveries.findById(view.deliveryId()).orElseThrow().getId(),
                view.batch().batchId());
    }

    /**
     * @param withMappings {@code true} bouwt een revisie op canonicalisatieversie 2 met twee gemapte
     *                     catalogusvelden; {@code false} bouwt exact de fase 2-revisie
     */
    private Fixture fixture(String prefix, boolean withMappings) {
        String unique = "MF" + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation,
                unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setStatus(RevisionStatus.ACTIVE);
        if (withMappings) {
            revision.setRecordCanonicalisationVersion(2);
        }
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        if (withMappings) {
            fieldMappings.saveAndFlush(mapping(stored, 1, "E_SUPPLIER", "E_LEV", null));
            fieldMappings.saveAndFlush(mapping(stored, 2, "SUPPLIER_BARCODE", "BARCODE", 8));
        }

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId());
    }

    /** De mapping gebruikt de geseede catalogusvelden; de test verzint geen eigen doelvelden. */
    private ImportFieldMapping mapping(ImportDefinitionRevision revision, int sequenceNumber, String code,
                                       String sourceReference, Integer maxLength) {
        ImportFieldCatalogEntry target = fieldCatalog.findById(code).orElseThrow();
        ImportFieldMapping mapping = new ImportFieldMapping(revision, sequenceNumber, target,
                FieldValueKind.SOURCE_FIELD, target.getDataType(), target.getDefaultOwner(),
                target.getIdentityClass());
        mapping.setSourceReference(sourceReference);
        mapping.setMaxLength(maxLength);
        return mapping;
    }

    private record Fixture(long taskId, long linkId, long revisionId) {
    }

    private record Delivered(long deliveryId, long batchId) {
    }
}
