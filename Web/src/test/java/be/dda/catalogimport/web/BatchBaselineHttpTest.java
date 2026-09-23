package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.SourceStateDao;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryIntakeService;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Fase 2e, end-to-end via HTTP tegen de echte controllers, services, DAO's, het archief en H2: de
 * geauditeerde {@code accept-baseline}-actie (met bewijs (b) uit het design), het hervatten van een
 * onderbroken mutatiegeneratie via {@code continue} en de leesendpoints voor batch, mutaties en
 * regelproblemen.
 * <p>
 * De microbatch- én chunkgrootte staan op 2, zodat een handvol regels meerdere chunk-commits doorloopt.
 * De opstartrecovery staat uit: die heeft haar eigen test en mag hier niet aan gedeelde testdata komen.
 * Elke test bouwt een eigen keten met unieke codes omdat de H2-database gedeeld is.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=2",
        "catalogimport.screening.mutation-chunk-size=2",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BatchBaselineHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String[] FIVE_ROWS = {
            "ACME;G1;R1;1,50;Boormachine",
            "ACME;G1;R2;2,25;Schroevendraaier",
            "ACME;G1;R3;3,00;Hamer",
            "ACME;G1;R4;4,00;Zaag",
            "ACME;G1;R5;5,00;Beitel"};
    private static final String ACCEPTED_BY = "jan.peeters@example.test";
    private static final String REASON = "Eerste nulmeting van de leverancierscatalogus";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @MockitoSpyBean
    private SourceStateDao sourceState;
    @MockitoSpyBean
    private MutationDao mutations;
    @Autowired
    private DeliveryIntakeService intake;
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
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Bewijs (b): een identieke herlevering na een aanvaarde nulmeting levert niets op ------------

    @Test
    void anIdenticalRedeliveryAfterAcceptingTheBaselineProducesNoContentMutationsAndLeavesTheSourceStateUntouched()
            throws Exception {
        Fixture f = fixture("PROOF", false);
        Uploaded first = upload(f, "REF-1", csv(false, FIVE_ROWS));
        assertThat(first.status()).isEqualTo("SCREENED");
        assertThat(first.newCount()).isEqualTo(5L);
        // De screening zelf schrijft nooit in de bronstaat.
        assertThat(stateRows(f)).isEmpty();

        String body = accept(first.batchId(), ACCEPTED_BY, REASON)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BASELINE_ACCEPTED"))
                .andExpect(jsonPath("$.acceptedBy").value(ACCEPTED_BY))
                .andExpect(jsonPath("$.skippedMutationCount").value(5))
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(body, "$.batchId")).longValue()).isEqualTo(first.batchId());

        // Batch: terminaal, met de audit persistent op de batch.
        ImportBatch batch = batches.findById(first.batchId()).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.BASELINE_ACCEPTED);
        assertThat(batch.getOpenMarker()).isNull();
        assertThat(batch.getBaselineAcceptedBy()).isEqualTo(ACCEPTED_BY);
        assertThat(batch.getBaselineAcceptReason()).isEqualTo(REASON);
        assertThat(batch.getBaselineAcceptedAt()).isNotNull();
        // De screening-uitkomst blijft leesbaar en de blokkeervelden worden niet misbruikt.
        assertThat(batch.getNewCount()).isEqualTo(5L);
        assertThat(batch.getBlockedCode()).isNull();
        assertThat(batch.getBlockedReason()).isNull();

        // Bronstaat: vijf rijen uit de staging, met herkomst, audit en profiel uit de revisie.
        List<StateRow> state = stateRows(f);
        assertThat(state).hasSize(5);
        assertThat(state).extracting(StateRow::reference).containsExactly("R1", "R2", "R3", "R4", "R5");
        assertThat(state).allSatisfy(row -> {
            assertThat(row.origin()).isEqualTo("BASELINE_ACCEPTED");
            assertThat(row.active()).isTrue();
            assertThat(row.acceptedBy()).isEqualTo(ACCEPTED_BY);
            assertThat(row.acceptedAt()).isNotNull();
            assertThat(row.profileKind()).isEqualTo("THREE_PART");
            assertThat(row.lastChangeBatchId()).isEqualTo(first.batchId());
            assertThat(row.lastChangeDeliveryId()).isEqualTo(first.deliveryId());
            assertThat(row.createdAt()).isNotNull();
            assertThat(row.updatedAt()).isNotNull();
            assertThat(row.basePrice()).isNotNull().isNotEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row.discountState()).isEqualTo("NOT_USED");
            assertThat(row.discountCode()).isNull();
        });
        assertThat(state.get(0).basePrice()).isEqualByComparingTo("1.50");

        // Mutaties: overgeslagen met de juiste reden; de marker blijft RECORDED en onaangeroerd.
        assertThat(jdbc.queryForList("select status || '/' || status_reason from import_mutation "
                + "where batch_id = ? and action_type <> 'IMPORT_MARKER'", String.class, first.batchId()))
                .hasSize(5).containsOnly("SKIPPED/BASELINE_ACCEPTED_WITHOUT_PUBLICATION");
        assertThat(jdbc.queryForList("select status || '/' || result_summary from import_mutation "
                + "where batch_id = ? and action_type = 'IMPORT_MARKER'", String.class, first.batchId()))
                .singleElement().satisfies(marker -> assertThat(marker)
                        .startsWith("RECORDED/outcome=SCREENED;completenessProven=false"));

        // Een tweede acceptatie van dezelfde batch is een conflict en verandert niets.
        accept(first.batchId(), ACCEPTED_BY, "Nog eens").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_ACCEPTABLE"));
        assertThat(batches.findById(first.batchId()).orElseThrow().getBaselineAcceptReason()).isEqualTo(REASON);

        // Dezelfde inhoud als nieuwe levering (de vorige run is afgerond, dus geen nieuwe taak nodig).
        List<Instant> updatedBefore = state.stream().map(StateRow::updatedAt).toList();
        Uploaded second = upload(f, "REF-2", csv(false, FIVE_ROWS));

        assertThat(second.status()).isEqualTo("SCREENED");
        assertThat(second.unchangedCount()).isEqualTo(5L);
        assertThat(second.newCount()).isZero();
        assertThat(second.changedCount()).isZero();
        assertThat(second.contentMutationCount()).isZero();
        assertThat(contentMutationCount(second.batchId())).isZero();
        assertThat(markerCount(second.batchId())).isEqualTo(1L);
        assertThat(stateRows(f)).extracting(StateRow::updatedAt).isEqualTo(updatedBefore);
        assertThat(stateRows(f)).hasSize(5);
    }

    @Test
    void aPriceChangeAfterTheBaselineIsOneUpdateAndAcceptingItOnlyUpdatesThatOneSourceStateRow() throws Exception {
        Fixture f = fixture("PRICE", false);
        Uploaded first = upload(f, "REF-1", csv(false, FIVE_ROWS));
        accept(first.batchId(), ACCEPTED_BY, REASON).andExpect(status().isOk());
        List<StateRow> before = stateRows(f);

        String[] changed = FIVE_ROWS.clone();
        changed[2] = "ACME;G1;R3;9,95;Hamer";
        Uploaded second = upload(f, "REF-2", csv(false, changed));

        assertThat(second.status()).isEqualTo("SCREENED");
        assertThat(second.changedCount()).isEqualTo(1L);
        assertThat(second.unchangedCount()).isEqualTo(4L);
        assertThat(second.contentMutationCount()).isEqualTo(1L);
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", second.batchId())
                        .param("actionType", "UPDATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actionType").value("UPDATE"))
                .andExpect(jsonPath("$.content[0].status").value("PLANNED"))
                .andExpect(jsonPath("$.content[0].domainMask").value("PRICE"))
                .andExpect(jsonPath("$.content[0].beforeBasePrice").value(3.0))
                .andExpect(jsonPath("$.content[0].afterBasePrice").value(9.95))
                .andExpect(jsonPath("$.content[0].identitySupplierReference").value("R3"));

        accept(second.batchId(), "an.janssens@example.test", "Prijsverhoging Hamer aanvaard")
                .andExpect(status().isOk()).andExpect(jsonPath("$.skippedMutationCount").value(1));

        List<StateRow> after = stateRows(f);
        assertThat(after).hasSize(5);
        for (int i = 0; i < 5; i++) {
            StateRow was = before.get(i);
            StateRow is = after.get(i);
            if (is.reference().equals("R3")) {
                assertThat(is.basePrice()).isEqualByComparingTo("9.95");
                assertThat(is.lastChangeBatchId()).isEqualTo(second.batchId());
                assertThat(is.lastChangeDeliveryId()).isEqualTo(second.deliveryId());
                assertThat(is.acceptedBy()).isEqualTo("an.janssens@example.test");
                assertThat(is.origin()).isEqualTo("BASELINE_ACCEPTED");
                assertThat(is.updatedAt()).isAfter(was.updatedAt());
                assertThat(is.id()).isEqualTo(was.id());
                assertThat(is.createdAt()).isEqualTo(was.createdAt());
            } else {
                // Ongewijzigd: niets geraakt, ook updated_at niet.
                assertThat(is).isEqualTo(was);
            }
        }
        assertThat(jdbc.queryForObject("select status from import_mutation where batch_id = ? "
                + "and action_type = 'UPDATE'", String.class, second.batchId())).isEqualTo("SKIPPED");
    }

    @Test
    void theIdentityProfileKindOfTheSourceStateComesFromTheRevisionAndIsNeverHardcoded() throws Exception {
        Fixture f = fixture("FOUR", true);
        Uploaded first = upload(f, "REF-1", csv(true,
                "ACME;G1;R1;1,50;Boormachine;KORT10",
                "ACME;G1;R2;2,25;Schroevendraaier;"));
        assertThat(first.status()).isEqualTo("SCREENED");

        accept(first.batchId(), ACCEPTED_BY, REASON).andExpect(status().isOk());

        List<StateRow> state = stateRows(f);
        assertThat(state).extracting(StateRow::profileKind)
                .containsOnly("FOUR_PART_WITH_DISCOUNT_CODE");
        // Gemapt-maar-leeg ("") en een echte waarde blijven verschillende toestanden.
        assertThat(state).extracting(StateRow::discountState).containsExactly("VALUE", "EMPTY");
        assertThat(state).extracting(StateRow::discountCode).containsExactly("KORT10", "");
    }

    // --- Afgewezen aanroepen -----------------------------------------------------------------------------

    @Test
    void anAcceptOnAnyStatusOtherThanScreenedIsRefusedWithoutWritingAnything() throws Exception {
        // RECEIVED: enkel geregistreerd, nog niet gescreend.
        Fixture received = fixture("REC", false);
        long receivedBatch = intake.intake(received.taskId(), "REF-1", "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv(false, FIVE_ROWS))).delivery().batch().batchId();
        assertRefusedAndNothingWritten(received, receivedBatch, "RECEIVED");

        // BLOCKED: dubbele identiteit.
        Fixture blocked = fixture("BLK", false);
        Uploaded duplicate = upload(blocked, "REF-1", csv(false,
                "ACME;G1;R1;1,50;Boormachine", "ACME;G1;R1;2,00;Nog een boormachine"));
        assertThat(duplicate.status()).isEqualTo("BLOCKED");
        assertRefusedAndNothingWritten(blocked, duplicate.batchId(), "BLOCKED");

        // FAILED: een gescreende batch die achteraf als mislukt staat.
        Fixture failed = fixture("FAIL", false);
        Uploaded screened = upload(failed, "REF-1", csv(false, FIVE_ROWS));
        ImportBatch batch = batches.findById(screened.batchId()).orElseThrow();
        batch.setStatus(ImportBatchStatus.FAILED);
        batches.saveAndFlush(batch);
        assertRefusedAndNothingWritten(failed, screened.batchId(), "FAILED");
    }

    @Test
    void anUnknownBatchIsA404() throws Exception {
        accept(999_999_999L, ACCEPTED_BY, REASON).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
        mockMvc.perform(get("/api/catalog-import/batches/{id}", 999_999_999L)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", 999_999_999L))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/catalog-import/batches/{id}/issues", 999_999_999L))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/catalog-import/batches/{id}/continue", 999_999_999L))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
    }

    @Test
    void anAcceptWithoutAReasonOrWithoutAPersonIsA400AndWritesNothing() throws Exception {
        Fixture f = fixture("BADREQ", false);
        Uploaded first = upload(f, "REF-1", csv(false, FIVE_ROWS));

        accept(first.batchId(), ACCEPTED_BY, "").andExpect(status().isBadRequest());
        accept(first.batchId(), ACCEPTED_BY, "   ").andExpect(status().isBadRequest());
        acceptRaw(first.batchId(), "{\"acceptedBy\":\"" + ACCEPTED_BY + "\"}").andExpect(status().isBadRequest());
        accept(first.batchId(), "", REASON).andExpect(status().isBadRequest());
        accept(first.batchId(), "  ", REASON).andExpect(status().isBadRequest());
        accept(first.batchId(), "system", REASON).andExpect(status().isBadRequest());
        accept(first.batchId(), "SYSTEM", REASON).andExpect(status().isBadRequest());
        accept(first.batchId(), "SyStEm", REASON).andExpect(status().isBadRequest());
        accept(first.batchId(), " System ", REASON).andExpect(status().isBadRequest());
        acceptRaw(first.batchId(), "{\"reason\":\"" + REASON + "\"}").andExpect(status().isBadRequest());
        acceptRaw(first.batchId(), "").andExpect(status().isBadRequest());
        accept(first.batchId(), "x".repeat(101), REASON).andExpect(status().isBadRequest());
        accept(first.batchId(), ACCEPTED_BY, "x".repeat(501)).andExpect(status().isBadRequest());

        assertThat(stateRows(f)).isEmpty();
        assertThat(batches.findById(first.batchId()).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(batches.findById(first.batchId()).orElseThrow().getBaselineAcceptedBy()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and status = 'SKIPPED'", Long.class, first.batchId())).isZero();

        // Met geldige velden slaagt dezelfde batch nog steeds: de afgewezen aanroepen lieten niets achter.
        accept(first.batchId(), ACCEPTED_BY, REASON).andExpect(status().isOk());
    }

    @Test
    void aBatchScreenedAgainstAnOutdatedSourceStateIsRefusedInsteadOfBeingOverwrittenSilently() throws Exception {
        Fixture f = fixture("STALE", false);
        Uploaded older = upload(f, "REF-1", csv(false, FIVE_ROWS));
        String[] changed = FIVE_ROWS.clone();
        changed[2] = "ACME;G1;R3;9,95;Hamer";
        // Ook de tweede levering werd gescreend tegen een nog lege bronstaat: alles NEW.
        Uploaded newer = upload(f, "REF-2", csv(false, changed));

        accept(newer.batchId(), ACCEPTED_BY, REASON).andExpect(status().isOk());
        List<StateRow> afterNewer = stateRows(f);

        // De oudere batch wil R3 opnieuw als NEW (3,00) aanmaken terwijl de bronstaat inmiddels 9,95 bevat.
        accept(older.batchId(), ACCEPTED_BY, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_STATE_CHANGED_SINCE_SCREENING"));
        assertThat(stateRows(f)).isEqualTo(afterNewer);
        assertThat(batches.findById(older.batchId()).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
    }

    // --- Onderbroken en herhaalde acceptatie ---------------------------------------------------------------

    @Test
    void anAcceptanceThatIsInterruptedAfterTheFirstChunkCanBeRepeatedWithoutDuplicateOrWrongRows()
            throws Exception {
        Fixture f = fixture("CRASHNEW", false);
        Uploaded first = upload(f, "REF-1", csv(false, FIVE_ROWS));
        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 2) {
                throw new UncheckedIOException(new IOException("simulated crash after the first chunk"));
            }
            return invocation.callRealMethod();
        }).when(sourceState).insertNewFromStage(any(), anyLong(), anyLong());

        assertThatThrownBy(() -> accept(first.batchId(), ACCEPTED_BY, REASON))
                .hasRootCauseInstanceOf(IOException.class);

        // Chunk 1 (twee regels) is gecommit, de rest niet; de batch is niet aanvaard en niets is overgeslagen.
        assertThat(stateRows(f)).hasSize(2);
        assertThat(batches.findById(first.batchId()).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(batches.findById(first.batchId()).orElseThrow().getBaselineAcceptedBy()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and status = 'SKIPPED'", Long.class, first.batchId())).isZero();

        Mockito.reset(sourceState);
        accept(first.batchId(), ACCEPTED_BY, REASON).andExpect(status().isOk());

        List<StateRow> state = stateRows(f);
        assertThat(state).extracting(StateRow::reference).containsExactly("R1", "R2", "R3", "R4", "R5");
        assertThat(jdbc.queryForObject("select count(distinct identity_hash) from catalog_source_state "
                + "where import_link_id = ?", Long.class, f.linkId())).isEqualTo(5L);
        assertThat(state).allSatisfy(row -> assertThat(row.lastChangeBatchId()).isEqualTo(first.batchId()));
        assertThat(batches.findById(first.batchId()).orElseThrow().getStatus())
                .isEqualTo(ImportBatchStatus.BASELINE_ACCEPTED);
        assertThat(jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and status = 'SKIPPED'", Long.class, first.batchId())).isEqualTo(5L);
    }

    @Test
    void anInterruptedAcceptanceOfChangedRowsIsRepeatableAndOnlyTouchesTheChangedRowsOnce() throws Exception {
        Fixture f = fixture("CRASHUPD", false);
        Uploaded first = upload(f, "REF-1", csv(false, FIVE_ROWS));
        accept(first.batchId(), ACCEPTED_BY, REASON).andExpect(status().isOk());
        List<StateRow> baseline = stateRows(f);

        String[] changed = FIVE_ROWS.clone();
        changed[0] = "ACME;G1;R1;1,75;Boormachine";
        changed[1] = "ACME;G1;R2;2,50;Schroevendraaier";
        changed[2] = "ACME;G1;R3;3,25;Hamer";
        Uploaded second = upload(f, "REF-2", csv(false, changed));
        assertThat(second.changedCount()).isEqualTo(3L);

        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 2) {
                throw new UncheckedIOException(new IOException("simulated crash after the first chunk"));
            }
            return invocation.callRealMethod();
        }).when(sourceState).updateChangedFromStage(any(), anyLong(), anyLong());
        assertThatThrownBy(() -> accept(second.batchId(), ACCEPTED_BY, REASON))
                .hasRootCauseInstanceOf(IOException.class);

        List<StateRow> interrupted = stateRows(f);
        assertThat(interrupted.get(0).basePrice()).isEqualByComparingTo("1.75");
        assertThat(interrupted.get(1).basePrice()).isEqualByComparingTo("2.50");
        assertThat(interrupted.get(2).basePrice()).isEqualByComparingTo("3.00"); // chunk 2 nog niet gecommit
        assertThat(batches.findById(second.batchId()).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENED);

        Mockito.reset(sourceState);
        accept(second.batchId(), ACCEPTED_BY, REASON).andExpect(status().isOk());

        List<StateRow> after = stateRows(f);
        assertThat(after).hasSize(5);
        assertThat(after.get(0).basePrice()).isEqualByComparingTo("1.75");
        assertThat(after.get(1).basePrice()).isEqualByComparingTo("2.50");
        assertThat(after.get(2).basePrice()).isEqualByComparingTo("3.25");
        // Wat de eerste (onderbroken) poging al klaar had, is bij de herhaling niet nogmaals aangeraakt.
        assertThat(after.get(0)).isEqualTo(interrupted.get(0));
        assertThat(after.get(1)).isEqualTo(interrupted.get(1));
        assertThat(after.get(2).lastChangeBatchId()).isEqualTo(second.batchId());
        // R4 en R5 zijn ongewijzigd en blijven onaangeroerd.
        assertThat(after.get(3)).isEqualTo(baseline.get(3));
        assertThat(after.get(4)).isEqualTo(baseline.get(4));
    }

    // --- Hervatten via HTTP -------------------------------------------------------------------------------------

    @Test
    void continueResumesAnInterruptedMutationGenerationWithoutDuplicatesAndFreesTheTask() throws Exception {
        Fixture f = fixture("CONT", false);
        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 2) {
                throw new UncheckedIOException(new IOException("simulated crash after the first chunk"));
            }
            return invocation.callRealMethod();
        }).when(mutations).insertContentMutations(any(), any(), any(), anyLong(), anyLong(), any());

        // De upload antwoordt met de tussenstand: de batch is hervatbaar, niet FAILED.
        Uploaded interrupted = upload(f, "REF-1", csv(false, FIVE_ROWS));
        assertThat(interrupted.status()).isEqualTo("MUTATING");
        assertThat(contentMutationCount(interrupted.batchId())).isEqualTo(2L);
        assertThat(markerCount(interrupted.batchId())).isZero();
        // De taak blijft bezet zolang de run niet afgesloten is.
        uploadRequest(f.taskId(), "REF-2", csv(false, FIVE_ROWS)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_RUN_IN_PROGRESS"));

        Mockito.reset(mutations);
        mockMvc.perform(post("/api/catalog-import/batches/{id}/continue", interrupted.batchId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(interrupted.batchId()))
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.newCount").value(5))
                .andExpect(jsonPath("$.contentMutationCount").value(5));

        assertThat(contentMutationCount(interrupted.batchId())).isEqualTo(5L);
        assertThat(jdbc.queryForObject("select count(distinct idempotency_key) from import_mutation "
                + "where batch_id = ?", Long.class, interrupted.batchId())).isEqualTo(6L);
        assertThat(markerCount(interrupted.batchId())).isEqualTo(1L);
        assertThat(runs.findByTaskIdAndConcurrencyTokenIsNotNull(f.taskId())).isEmpty();
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.taskId()).get(0).getStatus())
                .isEqualTo(TaskRunStatus.COMPLETED);

        // De taak is weer uploadbaar.
        uploadRequest(f.taskId(), "REF-2", csv(false, FIVE_ROWS)).andExpect(status().isCreated());
    }

    @Test
    void continueIsOnlyAllowedFromMutating() throws Exception {
        Fixture screened = fixture("CONTOK", false);
        Uploaded done = upload(screened, "REF-1", csv(false, FIVE_ROWS));
        mockMvc.perform(post("/api/catalog-import/batches/{id}/continue", done.batchId()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_NOT_RESUMABLE"));

        Fixture blocked = fixture("CONTBLK", false);
        Uploaded duplicate = upload(blocked, "REF-1", csv(false,
                "ACME;G1;R1;1,50;Boormachine", "ACME;G1;R1;2,00;Nog een boormachine"));
        assertThat(duplicate.status()).isEqualTo("BLOCKED");
        mockMvc.perform(post("/api/catalog-import/batches/{id}/continue", duplicate.batchId()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_NOT_RESUMABLE"));

        Fixture received = fixture("CONTREC", false);
        long receivedBatch = intake.intake(received.taskId(), "REF-1", "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv(false, FIVE_ROWS))).delivery().batch().batchId();
        mockMvc.perform(post("/api/catalog-import/batches/{id}/continue", receivedBatch))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_NOT_RESUMABLE"));
        // Een geweigerde hervatting laat niets achter.
        assertThat(batches.findById(receivedBatch).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.RECEIVED);
    }

    // --- Leesendpoints ----------------------------------------------------------------------------------------------

    @Test
    void theBatchEndpointShowsStatusAllCountersAndTheBlockedReason() throws Exception {
        Fixture f = fixture("GETB", false);
        Uploaded ok = upload(f, "REF-1", csv(false, FIVE_ROWS));

        mockMvc.perform(get("/api/catalog-import/batches/{id}", ok.batchId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(ok.batchId()))
                .andExpect(jsonPath("$.deliveryId").value(ok.deliveryId()))
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.rawRecordCount").value(5))
                .andExpect(jsonPath("$.validRecordCount").value(5))
                .andExpect(jsonPath("$.rejectedRecordCount").value(0))
                .andExpect(jsonPath("$.duplicateIdentityCount").value(0))
                .andExpect(jsonPath("$.newCount").value(5))
                .andExpect(jsonPath("$.changedCount").value(0))
                .andExpect(jsonPath("$.unchangedCount").value(0))
                .andExpect(jsonPath("$.contentMutationCount").value(5))
                .andExpect(jsonPath("$.stagedRowCount").value(5))
                .andExpect(jsonPath("$.blockedCode").doesNotExist())
                .andExpect(jsonPath("$.baselineAcceptedBy").doesNotExist());

        Fixture blocked = fixture("GETBLK", false);
        Uploaded duplicate = upload(blocked, "REF-1", csv(false,
                "ACME;G1;R1;1,50;Boormachine", "ACME;G1;R1;2,00;Nog een boormachine"));
        mockMvc.perform(get("/api/catalog-import/batches/{id}", duplicate.batchId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.blockedCode").value("DUPLICATE_IDENTITY_IN_DELIVERY"))
                .andExpect(jsonPath("$.blockedReason").exists())
                .andExpect(jsonPath("$.duplicateIdentityCount").value(2))
                .andExpect(jsonPath("$.contentMutationCount").value(0));
    }

    @Test
    void theMutationListIsPagedFilterableAndNeverExposesTheBinaryHashColumns() throws Exception {
        Fixture f = fixture("GETM", false);
        Uploaded first = upload(f, "REF-1", csv(false, FIVE_ROWS));

        // Vijf CREATE's en de marker = zes regels; pagina's van twee.
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId())
                        .param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].sourceRowNumber").value(2));
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId())
                        .param("size", "2").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId())
                        .param("size", "2").param("page", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        // Filter op actionType.
        String creates = mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId())
                        .param("actionType", "CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[0].actionType").value("CREATE"))
                .andExpect(jsonPath("$.content[0].targetDomain").value("OFFER"))
                // Eerste levering van de koppeling: sinds bouwstap 3h-3 een initialisatie, dus wachten
                // de creaties op goedkeuring (ontwerp par. 15.2) in plaats van PLANNED te zijn.
                .andExpect(jsonPath("$.content[0].status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.content[0].statusReason")
                        .value("INITIAL_LOAD_REQUIRES_APPROVAL"))
                .andExpect(jsonPath("$.content[0].identitySupplier").value("ACME"))
                .andExpect(jsonPath("$.content[0].afterBasePrice").value(1.5))
                .andExpect(jsonPath("$.content[0].idempotencyKey").exists())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId())
                        .param("actionType", "IMPORT_MARKER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("RECORDED"))
                .andExpect(jsonPath("$.content[0].resultSummary").exists());
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId())
                        .param("actionType", "UPDATE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        // Geen binaire hashkolommen in de respons.
        assertThat(creates).doesNotContainIgnoringCase("identityHash").doesNotContainIgnoringCase("fingerprint");

        // Standaardgrootte en bovengrens; ongeldige waarden zijn een 400.
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId()))
                .andExpect(jsonPath("$.size").value(50));
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId()).param("size", "5000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(200));
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId()).param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId()).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", first.batchId())
                        .param("actionType", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theIssueListShowsRowNumberCodeFieldAndSourceValueAndIsPaged() throws Exception {
        Fixture f = fixture("GETI", false);
        Uploaded first = upload(f, "REF-1", csv(false,
                "ACME;G1;R1;1,50;Boormachine",
                "ACME;G1;R2;12,3x;Schroevendraaier",
                "ACME;G1;R3;;Hamer",
                "ACME;G1;R4;4,00;Zaag"));
        assertThat(first.status()).isEqualTo("SCREENED");

        // Twee regelproblemen plus, sinds bouwstap 3h-3, één melding op leveringsniveau: deze eerste
        // levering van de koppeling is een initialisatie (ontwerp par. 15.2). De lijst wordt op
        // regelnummer gesorteerd en een leveringsmelding heeft er geen; waar die in de volgorde belandt
        // hangt van de database af (H2 zet NULL vooraan, PostgreSQL achteraan). De assertie zoekt haar
        // daarom op foutcode in plaats van op positie.
        mockMvc.perform(get("/api/catalog-import/batches/{id}/issues", first.batchId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.issueCode=='PRICE_UNREADABLE')].rowNumber").value(3))
                .andExpect(jsonPath("$.content[?(@.issueCode=='PRICE_UNREADABLE')].fieldName")
                        .value("PRIJS"))
                .andExpect(jsonPath("$.content[?(@.issueCode=='PRICE_UNREADABLE')].sourceValue")
                        .value("12,3x"))
                .andExpect(jsonPath("$.content[?(@.issueCode=='PRICE_UNREADABLE')].severity")
                        .value("ERROR"))
                .andExpect(jsonPath("$.content[?(@.issueCode=='PRICE_UNREADABLE')].message").exists())
                .andExpect(jsonPath("$.content[?(@.issueCode=='PRICE_MISSING')].rowNumber").value(4))
                .andExpect(jsonPath("$.content[?(@.issueCode=='INITIAL_LOAD_REQUIRES_APPROVAL')]"
                        + ".rowNumber").value(org.hamcrest.Matchers.contains((Object) null)));
        mockMvc.perform(get("/api/catalog-import/batches/{id}/issues", first.batchId())
                        .param("size", "1").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/catalog-import/batches/{id}/issues", first.batchId()).param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private void assertRefusedAndNothingWritten(Fixture f, long batchId, String expectedStatus) throws Exception {
        long mutationsSkippedBefore = jdbc.queryForObject("select count(*) from import_mutation "
                + "where batch_id = ? and status = 'SKIPPED'", Long.class, batchId);
        accept(batchId, ACCEPTED_BY, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_ACCEPTABLE"))
                .andExpect(jsonPath("$.error").exists());
        assertThat(stateRows(f)).as("source state after refused accept of %s batch", expectedStatus).isEmpty();
        ImportBatch batch = batches.findById(batchId).orElseThrow();
        assertThat(batch.getStatus().name()).isEqualTo(expectedStatus);
        assertThat(batch.getBaselineAcceptedBy()).isNull();
        assertThat(batch.getBaselineAcceptReason()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and status = 'SKIPPED'", Long.class, batchId)).isEqualTo(mutationsSkippedBefore);
    }

    private ResultActions accept(long batchId, String acceptedBy, String reason) throws Exception {
        return acceptRaw(batchId, "{\"acceptedBy\":" + quote(acceptedBy) + ",\"reason\":" + quote(reason) + "}");
    }

    private ResultActions acceptRaw(long batchId, String json) throws Exception {
        return mockMvc.perform(post("/api/catalog-import/batches/{id}/accept-baseline", batchId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Uploaded upload(Fixture f, String reference, byte[] content) throws Exception {
        String body = uploadRequest(f.taskId(), reference, content).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Uploaded(((Number) JsonPath.read(body, "$.deliveryId")).longValue(),
                ((Number) JsonPath.read(body, "$.batchId")).longValue(), JsonPath.read(body, "$.status"),
                number(body, "$.newCount"), number(body, "$.changedCount"), number(body, "$.unchangedCount"),
                number(body, "$.contentMutationCount"));
    }

    private static Long number(String body, String path) {
        Number value = JsonPath.read(body, path);
        return value == null ? null : value.longValue();
    }

    private ResultActions uploadRequest(long taskId, String reference, byte[] content) throws Exception {
        return mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", taskId)
                .file(new MockMultipartFile("file", "levering.csv", "text/csv", content))
                .param("deliveryReference", reference)
                .param("uploadedBy", "tester@example.test"));
    }

    private static byte[] csv(boolean withDiscount, String... rows) {
        String header = withDiscount ? "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;KORTING\n" : HEADER;
        return (header + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private long contentMutationCount(long batchId) {
        return jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and action_type <> 'IMPORT_MARKER'", Long.class, batchId);
    }

    private long markerCount(long batchId) {
        return jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and action_type = 'IMPORT_MARKER'", Long.class, batchId);
    }

    /** Leest via kolomposities: H2 en PostgreSQL geven kolomnamen in een andere schrijfwijze terug. */
    private List<StateRow> stateRows(Fixture f) {
        return jdbc.query("select id, identity_supplier_reference, identity_discount_code, "
                        + "identity_discount_state, identity_profile_kind, base_price, state_origin, "
                        + "last_change_delivery_id, last_change_batch_id, active, accepted_by, accepted_at, "
                        + "created_at, updated_at from catalog_source_state where import_link_id = ? "
                        + "order by identity_supplier_reference",
                (rs, index) -> new StateRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6), rs.getString(7), rs.getLong(8), rs.getLong(9),
                        rs.getBoolean(10), rs.getString(11), instant(rs.getObject(12, OffsetDateTime.class)),
                        instant(rs.getObject(13, OffsetDateTime.class)),
                        instant(rs.getObject(14, OffsetDateTime.class))),
                f.linkId());
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record StateRow(long id, String reference, String discountCode, String discountState,
                            String profileKind, BigDecimal basePrice, String origin, long lastChangeDeliveryId,
                            long lastChangeBatchId, boolean active, String acceptedBy, Instant acceptedAt,
                            Instant createdAt, Instant updatedAt) {

        /** {@code BigDecimal.equals} is schaalgevoelig; H2 geeft steeds schaal 6 terug, dus dit volstaat. */
    }

    private record Uploaded(long deliveryId, long batchId, String status, Long newCount, Long changedCount,
                            Long unchangedCount, Long contentMutationCount) {
    }

    private Fixture fixture(String prefix, boolean fourPart) {
        String unique = "BF" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
                fourPart ? IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE : IdentityProfileKind.THREE_PART,
                "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        if (fourPart) {
            revision.setIdentityDiscountCodeField("KORTING");
        }
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        // Bouwstap 3h-4: deze test gaat niet over de drempel op de records ter beoordeling. Met de
        // productiedefault van 1% zou een kleine fixture met een enkele kritieke lijn of een
        // vastgehouden identiteit nu geblokkeerd worden; het percentage wordt daarom PER TEST op 100
        // gezet, zodat hier exact het gedrag van vóór bouwstap 3h-4 geldt. De productiedefault zelf
        // blijft 1 procent - ThresholdBlockingTest bewijst die.
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        revisions.saveAndFlush(revision);
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
}
