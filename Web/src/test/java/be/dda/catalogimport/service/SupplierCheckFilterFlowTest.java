package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.RecordFilterEvaluator;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * De leverancierskaart als controle op de screening (beslissing 2026-10-23 "Leverancierskaart als controle: uitwerking
 * (SC)"): de revisie die {@code LegacyConfigApplyService} voor een CALC 7 op PSIMPDEF 9 schrijft, heeft een vaste
 * leverancier (INDET {@code Supplier}, hoofdletters) en een laatste REJECT-filterrij {@code NOT_EQUALS} op de kolom van
 * regel 9 met null-gedrag REJECT. Hier met kolomnamen in plaats van indexen; het filtermechanisme is hetzelfde.
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>1 sleutel k: lijnen met k worden onder de leverancier gestaged; een andere, onbekende of lege code (en de
 *       leverancier zelf) is {@code FILTER_RECORD_REJECTED}: zichtbaar, niet gestaged, geen kritieke lijn.</li>
 *   <li>Hetzelfde bestand twee keer: dezelfde identiteitshashes, na een nulmeting geen enkele mutatie.</li>
 *   <li>Een dubbele identiteit blijft {@code DUPLICATE_IDENTITY_IN_DELIVERY}.</li>
 *   <li>0 sleutels: de leverancier zelf, hoofdletterongevoelig en getrimd ({@code "02079"} en {@code "02079 "}).</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=2",
        "catalogimport.screening.mutation-chunk-size=2",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class SupplierCheckFilterFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "CODE;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String SUPPLIER = "02079";

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
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportRecordFilterRepository recordFilters;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aOneKeyCardAcceptsOnlyThatCodeAndRejectsEverythingElseVisibly() {
        Fixture fixture = fixture("ONEKEY", "0042", true);
        String csv = HEADER
                + "0042;G1;R1;1,50;Boormachine\n"
                + "0042;G1;R2;2,25;Schroevendraaier\n"
                + "0043;G1;R3;3,00;Andere leverancier\n"   // een andere code
                + "02079;G1;R4;4,00;De leverancier zelf\n"  // strenger dan legacy: de leverancier is geen sleutel
                + "9999;G1;R5;5,00;Onbekende code\n"
                + ";G1;R6;6,00;Lege code\n";

        Delivered first = deliver(fixture, "REF-1", csv);
        ScreeningOutcome outcome = screening.screen(first.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rawRecordCount()).isEqualTo(6L);
        assertThat(outcome.validRecordCount()).isEqualTo(2L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(4L);
        assertThat(outcome.criticalLineCount()).isZero();
        assertThat(outcome.duplicateIdentityCount()).isZero();
        assertThat(jdbc.queryForList("select identity_supplier || '/' || identity_supplier_reference from "
                + "import_candidate_stage where batch_id = ? order by row_number", String.class, first.batchId()))
                .containsExactly(SUPPLIER + "/R1", SUPPLIER + "/R2");
        // Elke verworpen lijn is zichtbaar met de filtercode; geen enkele lijn is stil overgeslagen.
        assertThat(jdbc.queryForList("select row_number from import_row_issue where batch_id = ? and issue_code = ? "
                + "order by row_number", Long.class, first.batchId(),
                RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED)).containsExactly(4L, 5L, 6L, 7L);

        // Hetzelfde bestand opnieuw: dezelfde identiteitshashes, na de nulmeting geen enkele mutatie.
        acceptBaseline(fixture, first);
        Delivered second = deliver(fixture, "REF-2", csv);
        ScreeningOutcome again = screening.screen(second.batchId());
        assertThat(again.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(stagedHashes(second.batchId())).isEqualTo(stagedHashes(first.batchId())).hasSize(2);
        assertThat(again.unchangedCount()).isEqualTo(2L);
        assertThat(again.newCount()).isZero();
        assertThat(again.changedCount()).isZero();
        assertThat(again.contentMutationCount()).isZero();
        assertThat(again.rejectedRecordCount()).isEqualTo(4L);
        assertThat(again.criticalLineCount()).isZero();
    }

    @Test
    void aRepeatedIdentityStillBlocksTheDelivery() {
        Fixture fixture = fixture("DUP", "0042", true);

        Delivered delivered = deliver(fixture, "REF-1", HEADER
                + "0042;G1;R1;1,50;Boormachine\n"
                + "0042;G1;R1;9,99;Boormachine met korting\n"
                + "0043;G1;R3;3,00;Andere leverancier\n");
        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(DeliveryScreeningService.CODE_DUPLICATE_IDENTITY_IN_DELIVERY);
        assertThat(outcome.duplicateIdentityCount()).isEqualTo(2L);
        assertThat(outcome.contentMutationCount()).isZero();
    }

    @Test
    void aZeroKeyCardAcceptsTheSupplierItselfCaseInsensitiveAndTrimmed() {
        Fixture fixture = fixture("NOKEY", SUPPLIER, false);
        Delivered delivered = deliver(fixture, "REF-1", HEADER
                + "02079;G1;R1;1,50;Boormachine\n"
                + "02079 ;G1;R2;2,25;Schroevendraaier\n"
                + "03000;G1;R3;3,00;Andere leverancier\n"
                + ";G1;R4;4,00;Lege code\n");

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validRecordCount()).isEqualTo(2L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
        assertThat(outcome.criticalLineCount()).isZero();
        assertThat(jdbc.queryForList("select identity_supplier || '/' || identity_supplier_reference from "
                + "import_candidate_stage where batch_id = ? order by row_number", String.class, delivered.batchId()))
                .containsExactly(SUPPLIER + "/R1", SUPPLIER + "/R2");
        assertThat(jdbc.queryForList("select row_number from import_row_issue where batch_id = ? and issue_code = ? "
                + "order by row_number", Long.class, delivered.batchId(),
                RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED)).containsExactly(4L, 5L);
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    private List<String> stagedHashes(long batchId) {
        return jdbc.queryForList("select cast(identity_hash as varchar) from import_candidate_stage where batch_id = ? "
                + "order by row_number", String.class, batchId);
    }

    /** Zoals {@code accept-baseline}: de bronstaat uit de staging van de gescreende batch. */
    private void acceptBaseline(Fixture fixture, Delivered delivered) {
        jdbc.update("insert into catalog_source_state (import_link_id, identity_hash, identity_supplier, "
                + "identity_supplier_group, identity_supplier_reference, identity_discount_code, "
                + "identity_discount_state, identity_profile_kind, article_fingerprint, price_fingerprint, "
                + "combined_fingerprint, base_price, base_price_currency, state_origin, "
                + "last_change_delivery_id, last_change_batch_id, active, accepted_by, accepted_at, "
                + "created_at, updated_at) "
                + "select cast(? as bigint), identity_hash, identity_supplier, identity_supplier_group, "
                + "identity_supplier_reference, identity_discount_code, identity_discount_state, "
                + "'THREE_PART', article_fingerprint, price_fingerprint, combined_fingerprint, base_price, "
                + "base_price_currency, 'BASELINE_ACCEPTED', cast(? as bigint), cast(? as bigint), true, "
                + "cast(? as varchar(100)), cast(? as timestamp with time zone), "
                + "cast(? as timestamp with time zone), cast(? as timestamp with time zone) "
                + "from import_candidate_stage where batch_id = ?",
                fixture.linkId(), delivered.deliveryId(), delivered.batchId(), "tester@example.test",
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC), delivered.batchId());
    }

    private Delivered deliver(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        return new Delivered(view.deliveryId(), view.batch().batchId());
    }

    /**
     * Een revisie zoals APPLY ze voor een leverancierskaart schrijft: vaste leverancier {@value #SUPPLIER}, hoofdletters,
     * en als laatste filterrij REJECT {@code NOT_EQUALS} op {@code CODE} (null = REJECT, ontbrekende kolom = BLOCK).
     */
    private Fixture fixture(String prefix, String compareValue, boolean caseSensitive) {
        String unique = "SC" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField(null);
        revision.setIdentitySupplierFixedValue(SUPPLIER);
        revision.setIdentityUppercase(true);
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        ImportRecordFilter check = new ImportRecordFilter(stored, 1, "CODE", FilterOperator.NOT_EQUALS, compareValue,
                FilterOutcome.REJECT);
        check.setCaseSensitive(caseSensitive);
        check.setNullBehaviour(FilterNullBehaviour.REJECT);
        recordFilters.saveAndFlush(check);

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId());
    }

    private record Fixture(long taskId, long linkId) {
    }

    private record Delivered(long deliveryId, long batchId) {
    }
}
