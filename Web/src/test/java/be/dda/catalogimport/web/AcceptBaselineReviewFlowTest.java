package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.DeliveryScreeningService;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bouwstap 3h-6 (ontwerp fase 3 par. 15.4 en 15.6; beslissingslog 20/09): {@code accept-baseline}
 * na de review-tussenstap, end-to-end via HTTP.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>Eén bevoegde persoon volstaat.</b> Een batch met wachtende creaties (eerste levering), met
 *       een {@code BULK_*}-incident en met {@code validation_result = REVIEW_REQUIRED} wordt met één
 *       {@code acceptedBy} en één {@code reason} aanvaard. Er is geen {@code approvedBy}, geen
 *       tweede-gebruikerscontrole en geen 409 {@code FOUR_EYES_APPROVAL_REQUIRED}; vier-ogen wordt
 *       pas met authenticatie (Fase 5) opnieuw beoordeeld.</li>
 *   <li><b>Bestaand gedrag van onbekende velden.</b> Een request met een extra veld
 *       {@code approvedBy} wordt door de standaard Jackson-configuratie van Spring Boot genegeerd
 *       ({@code FAIL_ON_UNKNOWN_PROPERTIES} staat uit); de waarde wordt nergens bewaard en komt niet
 *       terug in het antwoord.</li>
 *   <li><b>Het volledige gedrag na 3h-3/3h-5</b>: eerste levering, gemengde batch, geblokkeerde batch
 *       en dubbele aanvaarding.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AcceptBaselineReviewFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;EAN\n";
    private static final String ACCEPTED_BY = "jan.peeters@example.test";
    private static final String REASON = "Review afgerond door één bevoegde persoon";
    private static final String USER = "tester@example.test";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
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
    private ImportFieldCatalogRepository fieldCatalog;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;

    // --- (a) Eerste levering: initialisatie met wachtende creaties -----------------------------------

    /**
     * Het bewijs uit Fase 2, nu met de review-tussenstap: N creaties wachten op goedkeuring, één
     * aanvaarding door één persoon sluit ze af, en een identieke herlevering levert niets meer op.
     */
    @Test
    void acceptingAnInitialLoadWithAwaitingCreationsSkipsThemAndMakesARedeliveryEmpty() throws Exception {
        Fixture f = fixture("INIT", revision -> {
        });
        String csv = baseCsv(4);
        Delivered first = deliver(f, "REF-1", csv);
        ScreeningOutcome screened = screening.screen(first.batchId());
        assertThat(screened.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(screened.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
        assertThat(contentStatuses(first.batchId())).hasSize(4).containsOnly("AWAITING_APPROVAL");
        assertThat(stateRowCount(f)).isZero();

        accept(first.batchId(), ACCEPTED_BY, REASON)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BASELINE_ACCEPTED"))
                .andExpect(jsonPath("$.acceptedBy").value(ACCEPTED_BY))
                .andExpect(jsonPath("$.skippedMutationCount").value(4))
                .andExpect(jsonPath("$.approvedBy").doesNotExist());

        assertThat(contentStatuses(first.batchId())).hasSize(4).containsOnly("SKIPPED");
        assertThat(jdbc.queryForList("select distinct status_reason from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", String.class, first.batchId()))
                .containsExactly("BASELINE_ACCEPTED_WITHOUT_PUBLICATION");
        assertThat(markerStatuses(first.batchId())).containsExactly("RECORDED");
        assertThat(jdbc.queryForList("select distinct state_origin from catalog_source_state "
                + "where import_link_id = ?", String.class, f.linkId())).containsExactly("BASELINE_ACCEPTED");
        assertThat(stateRowCount(f)).isEqualTo(4L);
        ImportBatch batch = batches.findById(first.batchId()).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.BASELINE_ACCEPTED);
        assertThat(batch.getBaselineAcceptedBy()).isEqualTo(ACCEPTED_BY);
        assertThat(batch.getBaselineAcceptReason()).isEqualTo(REASON);

        // Een identieke herlevering: niets nieuw, niets gewijzigd, geen wachtende mutaties meer.
        Delivered second = deliver(f, "REF-2", csv);
        ScreeningOutcome again = screening.screen(second.batchId());
        assertThat(again.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(again.unchangedCount()).isEqualTo(4L);
        assertThat(again.newCount()).isZero();
        assertThat(again.changedCount()).isZero();
        assertThat(again.contentMutationCount()).isZero();
        assertThat(contentStatuses(second.batchId())).isEmpty();
        assertThat(stateRowCount(f)).isEqualTo(4L);
    }

    // --- (b) Gemengde batch: bulkprijsincident + vastgehouden identiteit -----------------------------

    /**
     * Tien prijswijzigingen wachten (bulkprijsincident), één update is gepland, één identiteit is
     * vastgehouden. Eén aanvaarding sluit de eerste elf af; de vastgehouden regel en haar incident
     * blijven onaangeroerd en haar nieuwe EAN komt nergens in de bronstaat.
     */
    @Test
    void acceptingABulkPriceBatchSkipsPlannedAndAwaitingButNeverTheHeldIdentity() throws Exception {
        Fixture f = acceptedBaseline("MIXED", 12);
        Delivered second = deliver(f, "REF-NEXT", HEADER + bulkPriceDelivery());
        ScreeningOutcome outcome = screening.screen(second.batchId());
        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
        assertThat(outcome.awaitingApprovalCount()).isEqualTo(10L);
        assertThat(statusOf(second.batchId(), "R1")).isEqualTo("AWAITING_APPROVAL");
        assertThat(statusOf(second.batchId(), "R11")).isEqualTo("PLANNED");
        assertThat(statusOf(second.batchId(), "R12")).isEqualTo("BLOCKED");
        Map<String, Object> heldBefore = stateRow(f, "R12");

        // Eén acceptedBy + één reason volstaat, ondanks het bulkincident en REVIEW_REQUIRED.
        accept(second.batchId(), ACCEPTED_BY, REASON)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BASELINE_ACCEPTED"))
                .andExpect(jsonPath("$.skippedMutationCount").value(11));

        for (int i = 1; i <= 11; i++) {
            assertThat(statusOf(second.batchId(), "R" + i)).as("R%d", i).isEqualTo("SKIPPED");
        }
        assertThat(statusOf(second.batchId(), "R12")).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForList("select status from import_mutation where batch_id = ? "
                + "and action_type = 'IDENTITY_REFERENCE_INCIDENT'", String.class, second.batchId()))
                .containsExactly("AWAITING_APPROVAL");
        assertThat(markerStatuses(second.batchId())).containsExactly("RECORDED");

        // De aanvaarde prijswijzigingen zijn in de bronstaat; de vastgehouden rij is onaangeroerd.
        assertThat(basePriceOf(f, "R1")).isEqualByComparingTo("3.00");
        assertThat(basePriceOf(f, "R10")).isEqualByComparingTo("3.00");
        assertThat(stateRow(f, "R12")).isEqualTo(heldBefore);
        assertThat(jdbc.queryForObject("select count(*) from catalog_reference_state "
                + "where value_normalised = 'E-912' and library_code = ?", Long.class, f.libraryCode())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from catalog_reference_state "
                + "where value_normalised = 'E-12' and library_code = ?", Long.class, f.libraryCode()))
                .isEqualTo(1L);
        assertThat(batches.findById(second.batchId()).orElseThrow().getStatus())
                .isEqualTo(ImportBatchStatus.BASELINE_ACCEPTED);
    }

    /**
     * Een {@code BULK_IDENTITY_INCIDENT}: elke regel heeft een andere EAN, dus elke regel is
     * vastgehouden. Er valt niets in de bronstaat te aanvaarden, maar de aanvaarding zelf lukt met één
     * persoon en laat de vastgehouden mutaties en incidenten staan.
     */
    @Test
    void acceptingABatchWithABulkIdentityIncidentNeedsOnlyOnePersonAndAcceptsNothingHeld() throws Exception {
        Fixture f = acceptedBaseline("BULKID", 12);
        StringBuilder changed = new StringBuilder(HEADER);
        for (int i = 1; i <= 12; i++) {
            changed.append(row("R" + i, "1,50", "Boormachine", "E-9" + i));
        }
        Delivered second = deliver(f, "REF-NEXT", changed.toString());
        ScreeningOutcome outcome = screening.screen(second.batchId());
        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
        assertThat(jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                + "and issue_code = 'BULK_IDENTITY_INCIDENT'", Long.class, second.batchId())).isPositive();
        List<Map<String, Object>> before = stateRows(f);

        accept(second.batchId(), ACCEPTED_BY, REASON)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BASELINE_ACCEPTED"))
                .andExpect(jsonPath("$.skippedMutationCount").value(0));

        assertThat(contentStatuses(second.batchId())).hasSize(12).containsOnly("BLOCKED");
        assertThat(jdbc.queryForList("select distinct status from import_mutation where batch_id = ? "
                + "and action_type = 'IDENTITY_REFERENCE_INCIDENT'", String.class, second.batchId()))
                .containsExactly("AWAITING_APPROVAL");
        assertThat(stateRows(f)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from catalog_reference_state "
                // 'E-9' zelf is de EAN van R9 uit de nulmeting; de nieuwe waarden zijn E-91 ... E-912.
                + "where value_normalised like 'E-9_%' and library_code = ?", Long.class, f.libraryCode()))
                .isZero();
    }

    // --- Geen vier-ogen, geen approvedBy --------------------------------------------------------------

    /**
     * Beslissing 20/09: één bevoegde persoon rondt de review af. Een extra {@code approvedBy} in de
     * body wordt door de bestaande request-deserialisatie genegeerd (Spring Boot laat onbekende velden
     * standaard toe): geen 400, geen 409, en de waarde wordt nergens bewaard of teruggegeven. Er
     * bestaat ook geen {@code baseline_approved_by}-kolom.
     */
    @Test
    void anUnknownApprovedByFieldIsIgnoredAndNeverStored() throws Exception {
        Fixture f = fixture("FOUREYES", revision -> {
        });
        Delivered first = deliver(f, "REF-1", baseCsv(3));
        assertThat(screening.screen(first.batchId()).validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);

        // Ook dezelfde persoon als "goedkeurder" verandert niets: er is geen tweede persoon vereist.
        acceptRaw(first.batchId(), "{\"acceptedBy\":\"" + ACCEPTED_BY + "\",\"reason\":\"" + REASON
                + "\",\"approvedBy\":\"" + ACCEPTED_BY + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BASELINE_ACCEPTED"))
                .andExpect(jsonPath("$.acceptedBy").value(ACCEPTED_BY))
                .andExpect(jsonPath("$.approvedBy").doesNotExist());

        ImportBatch batch = batches.findById(first.batchId()).orElseThrow();
        assertThat(batch.getBaselineAcceptedBy()).isEqualTo(ACCEPTED_BY);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where lower(table_name) = 'import_batch' and lower(column_name) like '%approved_by%'",
                Long.class)).isZero();
    }

    // --- (c) Geblokkeerde batch ------------------------------------------------------------------------

    @Test
    void acceptingABatchBlockedByAThresholdOrByTheStructureIsRefusedAndWritesNothing() throws Exception {
        // Drempel: 3 onleesbare prijzen op 200 records is 1,5% en dus boven de standaard 1%.
        Fixture threshold = fixture("THRESH", revision ->
                revision.setMaxCriticalSharePercent(new BigDecimal("1")));
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 200; i++) {
            csv.append(row("R" + i, i <= 3 ? "onleesbaar" : "1,50", "Boormachine", "E-" + i));
        }
        Delivered byThreshold = deliver(threshold, "REF-1", csv.toString());
        assertThat(screening.screen(byThreshold.batchId()).status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertRefusedAndNothingWritten(threshold, byThreshold.batchId());

        // Structuur: dezelfde identiteit tweemaal in één levering.
        Fixture structure = fixture("STRUCT", revision -> {
        });
        Delivered byStructure = deliver(structure, "REF-1", HEADER
                + row("R1", "1,50", "Boormachine", "E-1") + row("R1", "2,00", "Nog een", "E-2"));
        assertThat(screening.screen(byStructure.batchId()).status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertRefusedAndNothingWritten(structure, byStructure.batchId());
    }

    // --- (d) Tweemaal aanvaarden ------------------------------------------------------------------------

    @Test
    void acceptingTheSameBatchTwiceIsAConflictAndChangesNothing() throws Exception {
        Fixture f = fixture("TWICE", revision -> {
        });
        Delivered first = deliver(f, "REF-1", baseCsv(3));
        screening.screen(first.batchId());
        accept(first.batchId(), ACCEPTED_BY, REASON).andExpect(status().isOk());
        List<Map<String, Object>> stateAfterFirst = stateRows(f);

        accept(first.batchId(), "an.janssens@example.test", "Nog eens")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_ACCEPTABLE"));

        assertThat(batches.findById(first.batchId()).orElseThrow().getBaselineAcceptedBy()).isEqualTo(ACCEPTED_BY);
        assertThat(stateRows(f)).isEqualTo(stateAfterFirst);
        assertThat(contentStatuses(first.batchId())).containsOnly("SKIPPED");
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private void assertRefusedAndNothingWritten(Fixture f, long batchId) throws Exception {
        accept(batchId, ACCEPTED_BY, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_ACCEPTABLE"));
        assertThat(stateRowCount(f)).isZero();
        ImportBatch batch = batches.findById(batchId).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(batch.getBaselineAcceptedBy()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and status = 'SKIPPED'", Long.class, batchId)).isZero();
    }

    private ResultActions accept(long batchId, String acceptedBy, String reason) throws Exception {
        return acceptRaw(batchId, "{\"acceptedBy\":\"" + acceptedBy + "\",\"reason\":\"" + reason + "\"}");
    }

    private ResultActions acceptRaw(long batchId, String json) throws Exception {
        return mockMvc.perform(post("/api/catalog-import/batches/{id}/accept-baseline", batchId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String row(String reference, String price, String description, String ean) {
        return "ACME;G1;" + reference + ';' + price + ';' + description + ';' + ean + '\n';
    }

    private static String baseCsv(int count) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= count; i++) {
            csv.append(row("R" + i, "1,50", "Boormachine", "E-" + i));
        }
        return csv.toString();
    }

    /** Tien prijzen die verdubbelen, één omschrijvingswijziging en één regel met een andere EAN. */
    private static String bulkPriceDelivery() {
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            csv.append(row("R" + i, "3,00", "Boormachine", "E-" + i));
        }
        csv.append(row("R11", "1,50", "Slijpschijf", "E-11"));
        csv.append(row("R12", "1,50", "Boormachine", "E-912"));
        return csv.toString();
    }

    private List<String> contentStatuses(long batchId) {
        return jdbc.queryForList("select status from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", String.class, batchId);
    }

    private List<String> markerStatuses(long batchId) {
        return jdbc.queryForList("select status from import_mutation where batch_id = ? "
                + "and action_type = 'IMPORT_MARKER'", String.class, batchId);
    }

    private String statusOf(long batchId, String reference) {
        return jdbc.queryForObject("select status from import_mutation where batch_id = ? "
                        + "and action_type in ('CREATE', 'UPDATE') and identity_supplier_reference = ?",
                String.class, batchId, reference);
    }

    private long stateRowCount(Fixture f) {
        Long count = jdbc.queryForObject("select count(*) from catalog_source_state where import_link_id = ?",
                Long.class, f.linkId());
        return count == null ? 0L : count;
    }

    private BigDecimal basePriceOf(Fixture f, String reference) {
        return jdbc.queryForObject("select base_price from catalog_source_state where import_link_id = ? "
                + "and identity_supplier_reference = ?", BigDecimal.class, f.linkId(), reference);
    }

    /** Leesbare kolommen van één bronstaatrij (zonder binaire hashes, die zijn niet vergelijkbaar in een Map). */
    private Map<String, Object> stateRow(Fixture f, String reference) {
        return jdbc.queryForMap("select base_price, updated_at, last_change_batch_id, accepted_by, state_origin "
                + "from catalog_source_state where import_link_id = ? and identity_supplier_reference = ?",
                f.linkId(), reference);
    }

    private List<Map<String, Object>> stateRows(Fixture f) {
        return jdbc.queryForList("select identity_supplier_reference, base_price, updated_at, "
                + "last_change_batch_id, accepted_by, state_origin from catalog_source_state "
                + "where import_link_id = ? order by identity_supplier_reference", f.linkId());
    }

    private Fixture acceptedBaseline(String prefix, int rows) throws Exception {
        Fixture f = fixture(prefix, revision -> {
        });
        Delivered base = deliver(f, "REF-BASE", baseCsv(rows));
        assertThat(screening.screen(base.batchId()).status()).isEqualTo(ImportBatchStatus.SCREENED);
        accept(base.batchId(), ACCEPTED_BY, "nulmeting").andExpect(status().isOk());
        return f;
    }

    private Delivered deliver(Fixture f, String reference, String csv) {
        var view = intake.intake(f.taskId(), reference, USER, null, null, "levering.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        return new Delivered(view.deliveryId(), view.batch().batchId());
    }

    /**
     * Eén eigen keten met een gemapte kritieke koppelreferentie (EAN). {@code max_critical_share_percent}
     * staat op 100, tenzij de test dat via {@code tweak} anders wil: deze test gaat over de aanvaarding,
     * niet over de drempel die een levering stopt (die bewijst {@code ThresholdBlockingTest}).
     */
    private Fixture fixture(String prefix, Consumer<ImportDefinitionRevision> tweak) {
        String unique = "AR" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        revision.setRecordCanonicalisationVersion(2);
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        tweak.accept(revision);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);

        ImportFieldCatalogEntry ean = fieldCatalog.findById("EAN").orElseThrow();
        ImportFieldMapping mapping = new ImportFieldMapping(stored, 1, ean, FieldValueKind.SOURCE_FIELD,
                ean.getDataType(), ean.getDefaultOwner(), ean.getIdentityClass());
        mapping.setSourceReference("EAN");
        mapping.setReferenceType(ean.getReferenceType());
        fieldMappings.saveAndFlush(mapping);

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        String libraryCode = unique.substring(0, Math.min(unique.length(), 20));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, libraryCode));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), libraryCode);
    }

    private record Fixture(long taskId, long linkId, String libraryCode) {
    }

    private record Delivered(long deliveryId, long batchId) {
    }
}
