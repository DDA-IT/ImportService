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
import be.dda.catalogimport.dao.SourceStateDao;
import be.dda.catalogimport.dao.SourceStateDao.AcceptanceContext;
import be.dda.catalogimport.domain.CatalogImportTask;
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
import be.dda.catalogimport.service.support.ImportValueRules;
import be.dda.catalogimport.service.support.PriceRules;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Fase 3d (ontwerp fase 3, R-PRI-02..R-PRI-09): de volledige screening van een levering met
 * <b>prijscomponenten</b>, tegen de echte service, DAO's, het bestandsarchief, {@code accept-baseline}
 * en H2.
 * <p>
 * <b>Wat hier bewezen wordt.</b>
 * <ul>
 *   <li>Elke geldige regel levert een {@code BASE_PRICE}-rij én een rij per gemapte component op, met
 *       het percentage op schaal 12 — en nergens een stil ingevulde 0.</li>
 *   <li>Een onleesbare componentprijs of een basisprijs 0 verwerpt <b>enkel dat record</b>; de rest van
 *       de levering wordt gewoon verwerkt.</li>
 *   <li>Na een aanvaarde nulmeting levert een identieke herlevering nul mutaties op en blijft
 *       {@code catalog_source_state_price} — inclusief {@code updated_at} — ongemoeid.</li>
 *   <li>Een gewijzigd <b>percentage</b> bij een ongelijke basisprijs levert exact één {@code UPDATE}
 *       met {@code domain_mask=PRICE:AKP} op, en een gewijzigde <b>basisprijs</b> bij gelijke
 *       percentages exact één {@code UPDATE} met {@code domain_mask=PRICE} (R-PRI-09).</li>
 *   <li>Een revisie <b>zonder</b> prijscomponenten schrijft geen enkele prijsrij en houdt haar
 *       vingerafdrukken byte voor byte.</li>
 * </ul>
 * De microbatch- en chunkgrootte staan op 2, zodat elk scenario meerdere commits doorloopt. Elke test
 * bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=2",
        "catalogimport.screening.mutation-chunk-size=2"})
@ActiveProfiles("local")
class PriceComponentScreeningFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;AKP;VKP1\n";
    private static final String PLAIN_HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";

    /** Basisprijs 100/200/50 met een aankoopprijs van 80% en een verkoopprijs 1 van 120%. */
    private static final String[] ROWS = {
            "ACME;G1;R1;100,00;Boormachine;80,00;120,00",
            "ACME;G1;R2;200,00;Schroevendraaier;160,00;240,00",
            "ACME;G1;R3;50,00;Hamer;40,00;60,00"};

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
    private SourceStateDao sourceState;
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

    // --- Staging van de prijscomponenten -----------------------------------------------------------

    @Test
    void writesABasePriceRowAndOnePercentageRowPerMappedComponent() {
        Fixture fixture = fixture("PRCOK", true);
        Delivered delivered = deliver(fixture, "REF-1", csv(HEADER, ROWS));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validRecordCount()).isEqualTo(3L);
        assertThat(outcome.rejectedRecordCount()).isZero();
        assertThat(outcome.newCount()).isEqualTo(3L);

        // Drie regels x (basisprijs + twee componenten).
        assertThat(priceRows(delivered.batchId())).hasSize(9);
        assertThat(priceRow(delivered.batchId(), 2L, "BASE_PRICE")).satisfies(row -> {
            assertThat((BigDecimal) row.get("source_amount")).isEqualByComparingTo("100.00");
            // De basisprijs is haar eigen basis; een percentage zou hier enkel verwarring stichten.
            assertThat(row.get("percentage")).isNull();
            assertThat(row.get("status")).isEqualTo("OK");
            // Geen muntveld op deze revisie: onbekend, en nooit stil EUR.
            assertThat(row.get("currency")).isNull();
        });
        // 80,00 van 100,00 is exact 80%, op schaal 12.
        assertThat(percentageOf(delivered.batchId(), 2L, "AKP")).isEqualTo("80.000000000000");
        assertThat(percentageOf(delivered.batchId(), 2L, "VKP1")).isEqualTo("120.000000000000");
        // 40,00 van 50,00 is dezelfde verhouding bij een andere basisprijs.
        assertThat(percentageOf(delivered.batchId(), 4L, "AKP")).isEqualTo("80.000000000000");
        assertThat(jdbc.queryForObject("select count(*) from import_candidate_price where batch_id = ? "
                + "and status <> 'OK'", Long.class, delivered.batchId())).isZero();
    }

    /**
     * Een prijsfout is een <b>recordfout</b>: alleen die regel sneuvelt. Regel 3 heeft een onleesbare
     * aankoopprijs, regel 4 een basisprijs 0 die deze revisie niet toelaat; regel 2 en 5 gaan door.
     */
    @Test
    void rejectsOnlyTheRowsWithAnUnusableComponentPriceOrAForbiddenZeroBasePrice() {
        Fixture fixture = fixture("PRCREJ", true);
        Delivered delivered = deliver(fixture, "REF-1", csv(HEADER,
                "ACME;G1;R1;100,00;Boormachine;80,00;120,00",
                "ACME;G1;R2;200,00;Schroevendraaier;12,3x;240,00",
                "ACME;G1;R3;0,00;Hamer;40,00;60,00",
                "ACME;G1;R4;50,00;Zaag;40,00;60,00"));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rawRecordCount()).isEqualTo(4L);
        assertThat(outcome.validRecordCount()).isEqualTo(2L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
        assertThat(stagedReferences(delivered.batchId())).containsExactly("R1", "R4");

        // Nergens een stille 0: de verworpen regels hebben helemaal geen prijsrij.
        assertThat(priceRows(delivered.batchId())).hasSize(6);
        assertThat(jdbc.queryForObject("select count(*) from import_candidate_price where batch_id = ? "
                + "and (source_amount = 0 or percentage = 0)", Long.class, delivered.batchId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from import_candidate_stage where batch_id = ? "
                + "and base_price = 0", Long.class, delivered.batchId())).isZero();

        Map<String, String> issues = issueCodesByReference(delivered.batchId());
        assertThat(issues.get("3")).isEqualTo(ImportValueRules.CODE_PRICE_UNREADABLE);
        assertThat(issues.get("4")).isEqualTo(PriceRules.CODE_PRICE_ZERO_NOT_ALLOWED);
        // Enkel de regelproblemen: sinds bouwstap 3h-3 laat de eerste levering van een koppeling ook
        // één melding op leveringsniveau achter (INITIAL_LOAD_REQUIRES_APPROVAL, ontwerp par. 15.2).
        assertThat(rowIssues.findByBatchId(delivered.batchId(), PageRequest.of(0, 10)).getContent())
                .filteredOn(issue -> issue.getRowNumber() != null)
                .hasSize(2)
                .allSatisfy(issue -> assertThat(issue.getSeverity()).isEqualTo(RowIssueSeverity.ERROR));
        // De melding draagt de logische veldnaam uit de catalogus en de bronwaarde (par. 15.12).
        assertThat(issueOn(delivered.batchId(), 3L).getFieldName())
                .isEqualTo("Aankoopprijs in procent van de basisprijs");
        assertThat(issueOn(delivered.batchId(), 3L).getSourceValue()).isEqualTo("12,3x");
        assertThat(issueOn(delivered.batchId(), 4L).getSourceValue()).isEqualTo("0,00");
    }

    // --- Bronstaat en delta ------------------------------------------------------------------------

    @Test
    void anIdenticalRedeliveryAfterABaselineLeavesTheAcceptedPricesUntouched() {
        Fixture fixture = fixture("PRCSAME", true);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER, ROWS));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), "tester@example.test", "nulmeting voor de regressietest");

        List<Map<String, Object>> accepted = acceptedPrices(fixture.linkId());
        assertThat(accepted).hasSize(9);
        assertThat(accepted).anySatisfy(row -> {
            assertThat(row.get("component_code")).isEqualTo("AKP");
            assertThat((BigDecimal) row.get("percentage")).isEqualByComparingTo("80.000000000000");
        });

        Delivered second = deliver(fixture, "REF-2", csv(HEADER, ROWS));
        ScreeningOutcome outcome = screening.screen(second.batchId());
        baseline.acceptBaseline(second.batchId(), "tester@example.test", "identieke herlevering");

        assertThat(outcome.unchangedCount()).isEqualTo(3L);
        assertThat(outcome.changedCount()).isZero();
        assertThat(outcome.newCount()).isZero();
        assertThat(outcome.contentMutationCount()).isZero();
        // UNCHANGED raakt de bronstaat nooit aan, ook updated_at niet.
        assertThat(acceptedPrices(fixture.linkId())).isEqualTo(accepted);
    }

    /**
     * Een onderbroken acceptatie wordt door de aanroeper herhaald (design par. 9): de chunkschrijfactie
     * moet dan niets verdubbelen en {@code updated_at} niet verzetten van wat al klaar was.
     */
    @Test
    void repeatingTheAcceptanceChunkWritesNoSecondPriceRow() {
        Fixture fixture = fixture("PRCIDEM", true);
        Delivered delivered = deliver(fixture, "REF-1", csv(HEADER, ROWS));
        screening.screen(delivered.batchId());
        baseline.acceptBaseline(delivered.batchId(), "tester@example.test", "nulmeting");

        List<Map<String, Object>> accepted = acceptedPrices(fixture.linkId());
        assertThat(accepted).hasSize(9);

        // Exact wat een hervatte acceptatie doet: dezelfde chunk nog een keer.
        AcceptanceContext context = new AcceptanceContext(fixture.linkId(), delivered.batchId(),
                delivered.deliveryId(), "PSARF050", IdentityProfileKind.THREE_PART.name(),
                "BASELINE_ACCEPTED", "tester@example.test", Instant.now(), Instant.now());
        int written = sourceState.insertNewPricesFromStage(context, 0L, 999L);

        assertThat(written).isZero();
        assertThat(acceptedPrices(fixture.linkId())).isEqualTo(accepted);
    }

    /**
     * R-PRI-09, de kern van bouwstap 3d: de aankoopprijs verandert van 80% naar 85% terwijl de
     * basisprijs 100,00 blijft. Zonder de prijscomponenten in de vingerafdruk zou deze levering
     * "ongewijzigd" heten.
     */
    @Test
    void aChangedPercentageAtAnUnchangedBasePriceBecomesOneUpdateWithAComponentDomainMask() {
        Fixture fixture = fixture("PRCPCT", true);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER, ROWS));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), "tester@example.test", "nulmeting voor de regressietest");

        String[] changed = ROWS.clone();
        changed[0] = "ACME;G1;R1;100,00;Boormachine;85,00;120,00";
        Delivered second = deliver(fixture, "REF-2", csv(HEADER, changed));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.changedCount()).isEqualTo(1L);
        assertThat(outcome.unchangedCount()).isEqualTo(2L);
        assertThat(outcome.contentMutationCount()).isEqualTo(1L);
        assertThat(mutations(second.batchId())).singleElement().satisfies(mutation -> {
            assertThat(mutation.actionType()).isEqualTo("UPDATE");
            assertThat(mutation.identitySupplierReference()).isEqualTo("R1");
            // Enkel deze ene component wijzigde; de basisprijs en het artikel niet.
            assertThat(mutation.domainMask()).isEqualTo("PRICE:AKP");
            assertThat(mutation.beforeBasePrice()).isEqualByComparingTo("100.00");
            assertThat(mutation.afterBasePrice()).isEqualByComparingTo("100.00");
        });
        // De voor-waarde blijft herleidbaar via de bronstaat, de na-waarde via de kandidaat.
        assertThat(percentageOf(second.batchId(), 2L, "AKP")).isEqualTo("85.000000000000");
        assertThat(acceptedPercentage(fixture.linkId(), "R1", "AKP")).isEqualByComparingTo("80");

        // Pas de aanvaarding verschuift de bronstaat: de gewijzigde component wordt vervangen, de
        // andere componenten van dezelfde aanbieding blijven bestaan en er komt geen rij bij.
        baseline.acceptBaseline(second.batchId(), "tester@example.test", "tweede nulmeting");
        assertThat(acceptedPercentage(fixture.linkId(), "R1", "AKP")).isEqualByComparingTo("85");
        assertThat(acceptedPercentage(fixture.linkId(), "R1", "VKP1")).isEqualByComparingTo("120");
        assertThat(acceptedPrices(fixture.linkId())).hasSize(9);
    }

    @Test
    void aChangedBasePriceAtUnchangedPercentagesBecomesOneUpdateWithThePriceDomainMask() {
        Fixture fixture = fixture("PRCBASE", true);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER, ROWS));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), "tester@example.test", "nulmeting voor de regressietest");

        String[] changed = ROWS.clone();
        // Basisprijs verdubbelt; 160/240 zijn nog steeds 80% en 120%.
        changed[0] = "ACME;G1;R1;200,00;Boormachine;160,00;240,00";
        Delivered second = deliver(fixture, "REF-2", csv(HEADER, changed));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.changedCount()).isEqualTo(1L);
        assertThat(outcome.contentMutationCount()).isEqualTo(1L);
        assertThat(mutations(second.batchId())).singleElement().satisfies(mutation -> {
            assertThat(mutation.domainMask()).isEqualTo("PRICE");
            assertThat(mutation.beforeBasePrice()).isEqualByComparingTo("100.00");
            assertThat(mutation.afterBasePrice()).isEqualByComparingTo("200.00");
        });
    }

    @Test
    void aChangedBasePriceAndComponentShowBothPartsInTheDomainMask() {
        Fixture fixture = fixture("PRCBOTH", true);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER, ROWS));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), "tester@example.test", "nulmeting voor de regressietest");

        String[] changed = ROWS.clone();
        // Basisprijs 200 met een aankoopprijs van 150 (75%) en een verkoopprijs 1 van 240 (120%).
        changed[0] = "ACME;G1;R1;200,00;Boorhamer;150,00;240,00";
        Delivered second = deliver(fixture, "REF-2", csv(HEADER, changed));
        screening.screen(second.batchId());

        assertThat(mutations(second.batchId())).singleElement().satisfies(mutation ->
                // Vaste, deterministische volgorde: artikel, basisprijs, daarna de componenten op code.
                assertThat(mutation.domainMask()).isEqualTo("ARTICLE,PRICE,PRICE:AKP"));
    }

    // --- Regressie: een revisie zonder prijscomponenten -------------------------------------------

    @Test
    void aRevisionWithoutPriceComponentsWritesNoPriceRowsAndKeepsItsFingerprints() {
        Fixture fixture = fixture("PRCV1", false);
        Delivered delivered = deliver(fixture, "REF-1", csv(PLAIN_HEADER,
                "ACME;G1;R1;1,50;Boormachine",
                "ACME;G1;R2;2,25;Schroevendraaier"));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validRecordCount()).isEqualTo(2L);
        assertThat(outcome.newCount()).isEqualTo(2L);
        // Geen enkele prijsrij: byte-neutraal voor elke bestaande revisie.
        assertThat(priceRows(delivered.batchId())).isEmpty();
        // Exact de prijsvingerafdruk van fase 2, onafhankelijk berekend uit de canonieke vorm
        // (versie 1, basisprijs 1.500000, munt niet gemapt).
        assertThat(jdbc.queryForObject("select price_fingerprint from import_candidate_stage "
                        + "where batch_id = ? and row_number = 2", byte[].class, delivered.batchId()))
                .isEqualTo(hex("a69bed13cadfe6a3868f53f3fb3f90f8eea9c1bcd2240b77fb45c010de227f39"));

        baseline.acceptBaseline(delivered.batchId(), "tester@example.test", "nulmeting zonder componenten");
        assertThat(acceptedPrices(fixture.linkId())).isEmpty();
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private static byte[] csv(String header, String... rows) {
        return (header + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private List<Map<String, Object>> priceRows(long batchId) {
        return jdbc.queryForList("select row_number, component_code, source_amount, percentage, currency, "
                + "status from import_candidate_price where batch_id = ? "
                + "order by row_number, component_code", batchId);
    }

    private Map<String, Object> priceRow(long batchId, long rowNumber, String componentCode) {
        return jdbc.queryForMap("select source_amount, percentage, currency, status "
                        + "from import_candidate_price where batch_id = ? and row_number = ? "
                        + "and component_code = ?", batchId, rowNumber, componentCode);
    }

    private String percentageOf(long batchId, long rowNumber, String componentCode) {
        BigDecimal percentage = jdbc.queryForObject("select percentage from import_candidate_price "
                        + "where batch_id = ? and row_number = ? and component_code = ?", BigDecimal.class,
                batchId, rowNumber, componentCode);
        return percentage == null ? null : percentage.toPlainString();
    }

    private List<Map<String, Object>> acceptedPrices(long importLinkId) {
        return jdbc.queryForList("select state.identity_supplier_reference, price.component_code, "
                + "price.amount, price.percentage, price.currency, price.updated_at "
                + "from catalog_source_state_price price "
                + "join catalog_source_state state on state.id = price.source_state_id "
                + "where state.import_link_id = ? "
                + "order by state.identity_supplier_reference, price.component_code", importLinkId);
    }

    private BigDecimal acceptedPercentage(long importLinkId, String reference, String componentCode) {
        return jdbc.queryForObject("select price.percentage from catalog_source_state_price price "
                        + "join catalog_source_state state on state.id = price.source_state_id "
                        + "where state.import_link_id = ? and state.identity_supplier_reference = ? "
                        + "and price.component_code = ?", BigDecimal.class, importLinkId, reference,
                componentCode);
    }

    private List<String> stagedReferences(long batchId) {
        return jdbc.queryForList("select identity_supplier_reference from import_candidate_stage "
                + "where batch_id = ? order by row_number", String.class, batchId);
    }

    private Map<String, String> issueCodesByReference(long batchId) {
        return jdbc.query("select row_number, issue_code from import_row_issue where batch_id = ?",
                resultSet -> {
                    Map<String, String> codes = new java.util.LinkedHashMap<>();
                    while (resultSet.next()) {
                        codes.put(String.valueOf(resultSet.getLong(1)), resultSet.getString(2));
                    }
                    return codes;
                }, batchId);
    }

    private ImportRowIssue issueOn(long batchId, long rowNumber) {
        return rowIssues.findByBatchId(batchId, PageRequest.of(0, 20)).getContent().stream()
                .filter(issue -> issue.getRowNumber() != null && issue.getRowNumber() == rowNumber)
                .findFirst().orElseThrow();
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
                               BigDecimal beforeBasePrice, BigDecimal afterBasePrice) {
    }

    private Delivered deliver(Fixture fixture, String reference, byte[] content) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(content)).delivery();
        return new Delivered(deliveries.findById(view.deliveryId()).orElseThrow().getId(),
                view.batch().batchId());
    }

    /**
     * @param withPriceComponents {@code true} bouwt een revisie op canonicalisatieversie 2 met twee
     *                            gemapte prijscomponenten; {@code false} bouwt exact de fase 2-revisie
     */
    private Fixture fixture(String prefix, boolean withPriceComponents) {
        String unique = "PC" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        if (withPriceComponents) {
            revision.setRecordCanonicalisationVersion(2);
        }
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        if (withPriceComponents) {
            fieldMappings.saveAndFlush(priceMapping(stored, 1, "AKP_PCT", "AKP"));
            fieldMappings.saveAndFlush(priceMapping(stored, 2, "VKP1_PCT", "VKP1"));
        }

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId());
    }

    /** De mapping gebruikt de geseede catalogusvelden; de test verzint geen eigen prijscomponenten. */
    private ImportFieldMapping priceMapping(ImportDefinitionRevision revision, int sequenceNumber,
                                            String code, String sourceReference) {
        ImportFieldCatalogEntry target = fieldCatalog.findById(code).orElseThrow();
        ImportFieldMapping mapping = new ImportFieldMapping(revision, sequenceNumber, target,
                FieldValueKind.SOURCE_FIELD, target.getDataType(), target.getDefaultOwner(),
                target.getIdentityClass());
        mapping.setSourceReference(sourceReference);
        mapping.setPriceComponentCode(target.getPriceComponentCode());
        return mapping;
    }

    private record Fixture(long taskId, long linkId, long revisionId) {
    }

    private record Delivered(long deliveryId, long batchId) {
    }
}
