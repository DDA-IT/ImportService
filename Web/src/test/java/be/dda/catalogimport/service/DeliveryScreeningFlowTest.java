package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRowIssueRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.DeliveryFile;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRowIssue;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.CsvRecordStreamer;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.ImportValueRules;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Fase 2d: de volledige screening van een manuele levering tegen de echte service, DAO's, het
 * bestandsarchief en H2 — duplicaatdetectie, hashcollisie, delta tegen de bronstaat, de centrale
 * mutatielijst, de {@code IMPORT_MARKER} en de eindstatus (design par. 4 en par. 9 stap D, E en F).
 * <p>
 * De microbatch- én chunkgrootte staan op 2, zodat elk scenario met een handvol regels meerdere
 * chunk-commits doorloopt en het hervatpunt aantoonbaar meebeweegt.
 * <p>
 * De bronstaat wordt hier rechtstreeks met JdbcTemplate gevuld, precies zoals de
 * {@code accept-baseline}-actie uit bouwstap 2e dat zal doen: de screening zelf schrijft nooit in
 * {@code catalog_source_state}, en dat is juist wat bewezen moet worden.
 * <p>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=2",
        "catalogimport.screening.mutation-chunk-size=2"})
@ActiveProfiles("local")
class DeliveryScreeningFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String[] FIVE_ROWS = {
            "ACME;G1;R1;1,50;Boormachine",
            "ACME;G1;R2;2,25;Schroevendraaier",
            "ACME;G1;R3;3,00;Hamer",
            "ACME;G1;R4;4,00;Zaag",
            "ACME;G1;R5;5,00;Beitel"};

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @MockitoSpyBean
    private MutationDao mutations;
    @Autowired
    private CandidateStageDao stage;
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
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private TaskRunRepository runs;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private DeliveryFileRepository deliveryFiles;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private ImportRowIssueRepository rowIssues;
    @Autowired
    private JdbcTemplate jdbc;

    // --- (a) Eerste levering ---------------------------------------------------------------------

    @Test
    void aFirstDeliveryBecomesOneCreatePerRowPlusExactlyOneImportMarker() {
        Fixture fixture = fixture("FIRST");
        Delivered delivered = deliver(fixture, "REF-1", csv(FIVE_ROWS));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rawRecordCount()).isEqualTo(5L);
        assertThat(outcome.validRecordCount()).isEqualTo(5L);
        assertThat(outcome.rejectedRecordCount()).isZero();
        assertThat(outcome.newCount()).isEqualTo(5L);
        assertThat(outcome.changedCount()).isZero();
        assertThat(outcome.unchangedCount()).isZero();
        assertThat(outcome.duplicateIdentityCount()).isZero();
        assertThat(outcome.contentMutationCount()).isEqualTo(5L);
        assertThat(outcome.blockedCode()).isNull();

        List<MutationRow> content = contentMutations(delivered.batchId());
        assertThat(content).hasSize(5);
        assertThat(content).extracting(MutationRow::sourceRowNumber).containsExactly(2L, 3L, 4L, 5L, 6L);
        assertThat(content).allSatisfy(row -> {
            assertThat(row.actionType()).isEqualTo("CREATE");
            assertThat(row.targetDomain()).isEqualTo("OFFER");
            // Sinds bouwstap 3h-3 (ontwerp par. 15.2) is de eerste levering van een koppeling een
            // initialisatie: de mutaties bestaan volledig, maar wachten op goedkeuring in plaats van
            // PLANNED te zijn. accept-baseline zet ze daarna alsnog op SKIPPED.
            assertThat(row.status()).isEqualTo(MutationStatus.AWAITING_APPROVAL.name());
            assertThat(row.statusReason())
                    .isEqualTo(ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL);
            assertThat(row.identitySupplier()).isEqualTo("ACME");
            assertThat(row.identityHash()).isNotNull();
            // Een nieuwe aanbieding heeft geen voorgeschiedenis; niets wordt stil op 0 gezet.
            assertThat(row.beforeBasePrice()).isNull();
            assertThat(row.beforeCombinedFingerprint()).isNull();
            assertThat(row.domainMask()).isNull();
            assertThat(row.sourceStateId()).isNull();
            assertThat(row.afterBasePrice()).isNotNull().isNotEqualTo(BigDecimal.ZERO);
            assertThat(row.deliveryFileId()).isEqualTo(delivered.deliveryFileId());
            assertThat(row.taskRunId()).isEqualTo(delivered.taskRunId());
        });
        assertThat(content.get(0).afterBasePrice()).isEqualByComparingTo("1.50");

        byte[] firstIdentity = ImportValueRules.sha256Utf8(ImportValueRules.canonical(1, "ACME", "G1", "R1"));
        assertThat(content.get(0).idempotencyKey()).isEqualTo(delivered.deliveryId() + ":"
                + fixture.revisionId() + ":" + HexFormat.of().formatHex(firstIdentity) + ":OFFER");

        List<MutationRow> markers = markers(delivered.batchId());
        assertThat(markers).singleElement().satisfies(marker -> {
            assertThat(marker.actionType()).isEqualTo("IMPORT_MARKER");
            assertThat(marker.targetDomain()).isEqualTo("IMPORT");
            assertThat(marker.status()).isEqualTo("RECORDED");
            assertThat(marker.identitySupplier()).isNull();
            assertThat(marker.identityHash()).isNull();
            assertThat(marker.idempotencyKey())
                    .isEqualTo(delivered.deliveryId() + ":" + fixture.revisionId() + ":MARKER");
            assertThat(marker.resultSummary())
                    .contains("outcome=SCREENED")
                    .contains("completenessProven=false")
                    .contains("completenessReason=PHASE2_NO_COMPLETENESS_CONTRACT")
                    .contains("fileSha256=" + archivedFile(delivered).getContentHash());
        });

        // Het archiefobject draagt de hash en de omvang waarop de marker zich beroept.
        DeliveryFile file = archivedFile(delivered);
        assertThat(archiveRoot.resolve(file.getArchiveReference())).hasBinaryContent(csv(FIVE_ROWS));
        assertThat(file.getByteSize()).isEqualTo(csv(FIVE_ROWS).length);
        assertThat(file.getContentHash()).isEqualTo(ImportValueRules.sha256Hex(csv(FIVE_ROWS)));
        assertThat(deliveries.findById(delivered.deliveryId()).orElseThrow().isCompletenessProven()).isFalse();

        ImportBatch batch = batches.findById(delivered.batchId()).orElseThrow();
        assertThat(batch.getMutationProgressRowNumber()).isEqualTo(6L);
        assertThat(batch.getFinishedAt()).isNotNull();
        assertThat(batch.getOpenMarker()).isNull();
        assertThat(runs.findById(delivered.taskRunId()).orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.COMPLETED);
        // De screening raakt de bronstaat nooit aan: die blijft leeg tot accept-baseline (2e).
        assertThat(sourceStateCount(fixture.linkId())).isZero();
    }

    // --- (b') Herlevering na een aanvaarde nulmeting ----------------------------------------------

    @Test
    void anIdenticalRedeliveryAfterABaselineProducesNoContentMutationsAndLeavesTheSourceStateUntouched() {
        Fixture fixture = fixture("SAME");
        Delivered first = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        screening.screen(first.batchId());
        acceptBaseline(fixture, first);
        List<Instant> updatedBefore = sourceStateUpdatedAt(fixture.linkId());

        Delivered second = deliver(fixture, "REF-2", csv(FIVE_ROWS));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.unchangedCount()).isEqualTo(5L);
        assertThat(outcome.newCount()).isZero();
        assertThat(outcome.changedCount()).isZero();
        assertThat(outcome.contentMutationCount()).isZero();
        assertThat(contentMutations(second.batchId())).isEmpty();
        assertThat(markers(second.batchId())).hasSize(1);
        // Een ongewijzigde regel raakt de bronstaat nooit aan.
        assertThat(sourceStateUpdatedAt(fixture.linkId())).isEqualTo(updatedBefore);
        assertThat(sourceStateCount(fixture.linkId())).isEqualTo(5L);
    }

    @Test
    void aChangedPriceBecomesOneUpdateWithItsBeforeAndAfterPriceAndAPriceDomainMask() {
        Fixture fixture = fixture("PRICE");
        Delivered first = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        screening.screen(first.batchId());
        acceptBaseline(fixture, first);

        String[] changed = FIVE_ROWS.clone();
        changed[2] = "ACME;G1;R3;9,95;Hamer";
        Delivered second = deliver(fixture, "REF-2", csv(changed));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.changedCount()).isEqualTo(1L);
        assertThat(outcome.unchangedCount()).isEqualTo(4L);
        assertThat(outcome.newCount()).isZero();
        assertThat(outcome.contentMutationCount()).isEqualTo(1L);

        assertThat(contentMutations(second.batchId())).singleElement().satisfies(update -> {
            assertThat(update.actionType()).isEqualTo("UPDATE");
            assertThat(update.status()).isEqualTo("PLANNED");
            assertThat(update.identitySupplierReference()).isEqualTo("R3");
            assertThat(update.domainMask()).isEqualTo("PRICE");
            assertThat(update.beforeBasePrice()).isEqualByComparingTo("3.00");
            assertThat(update.afterBasePrice()).isEqualByComparingTo("9.95");
            assertThat(update.beforeCombinedFingerprint()).isNotNull();
            assertThat(update.afterCombinedFingerprint()).isNotNull()
                    .isNotEqualTo(update.beforeCombinedFingerprint());
            assertThat(update.sourceStateId()).isNotNull();
        });
    }

    @Test
    void aChangedDescriptionAndPriceTogetherBecomeAnArticleAndPriceDomainMask() {
        Fixture fixture = fixture("BOTH");
        Delivered first = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        screening.screen(first.batchId());
        acceptBaseline(fixture, first);

        String[] changed = FIVE_ROWS.clone();
        changed[0] = "ACME;G1;R1;1,75;Boormachine XL";
        changed[1] = "ACME;G1;R2;2,25;Schroevendraaier PH2";
        Delivered second = deliver(fixture, "REF-2", csv(changed));
        screening.screen(second.batchId());

        List<MutationRow> content = contentMutations(second.batchId());
        assertThat(content).hasSize(2);
        assertThat(content).extracting(MutationRow::identitySupplierReference).containsExactly("R1", "R2");
        assertThat(content).extracting(MutationRow::domainMask).containsExactly("ARTICLE,PRICE", "ARTICLE");
    }

    // --- (c) Dubbele identiteit --------------------------------------------------------------------

    @Test
    void aRepeatedOfferIdentityBlocksTheWholeDeliveryWithAnIssuePerInvolvedRow() {
        Fixture fixture = fixture("DUP");
        Delivered delivered = deliver(fixture, "REF-1", csv(
                "ACME;G1;R1;1,50;Boormachine",
                "ACME;G1;R2;2,25;Schroevendraaier",
                "ACME;G1;R1;9,99;Boormachine met korting",
                "ACME;G1;R3;3,00;Hamer"));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode())
                .isEqualTo(DeliveryScreeningService.CODE_DUPLICATE_IDENTITY_IN_DELIVERY);
        assertThat(outcome.duplicateIdentityCount()).isEqualTo(2L);
        assertThat(outcome.contentMutationCount()).isZero();
        assertThat(contentMutations(delivered.batchId())).isEmpty();

        // Nooit "laatste wint": beide regels worden gemeld, met het regelnummer van de eerste.
        List<ImportRowIssue> issues = rowIssues.findByBatchId(delivered.batchId(), PageRequest.of(0, 20))
                .getContent().stream()
                .filter(issue -> issue.getIssueCode()
                        .equals(DeliveryScreeningService.CODE_DUPLICATE_IDENTITY_IN_DELIVERY))
                .toList();
        assertThat(issues).hasSize(2);
        assertThat(issues).extracting(ImportRowIssue::getRowNumber).containsExactlyInAnyOrder(2L, 4L);
        assertThat(issues).allSatisfy(issue ->
                assertThat(issue.getMessage()).contains("first occurrence on line 2"));

        assertThat(classifications(delivered.batchId()))
                .containsExactly("DUPLICATE_IN_DELIVERY", null, "DUPLICATE_IN_DELIVERY", null);
        assertThat(markers(delivered.batchId())).singleElement().satisfies(marker ->
                assertThat(marker.resultSummary()).contains("outcome=BLOCKED")
                        .contains("blockedCode=DUPLICATE_IDENTITY_IN_DELIVERY"));
        assertThat(runs.findById(delivered.taskRunId()).orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.COMPLETED);
    }

    // --- (d) Ongeldige regels en geblokkeerde leveringen -------------------------------------------

    @Test
    void anUnreadableOrEmptyPriceRejectsOnlyThoseRowsAndNeverProducesAZeroPrice() {
        Fixture fixture = fixture("BADPRICE");
        Delivered delivered = deliver(fixture, "REF-1", csv(
                "ACME;G1;R1;1,50;Boormachine",
                "ACME;G1;R2;12,3x;Schroevendraaier",
                "ACME;G1;R3;;Hamer",
                "ACME;G1;R4;4,00;Zaag"));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
        assertThat(outcome.newCount()).isEqualTo(2L);
        assertThat(outcome.contentMutationCount()).isEqualTo(2L);
        assertThat(contentMutations(delivered.batchId()))
                .extracting(MutationRow::identitySupplierReference).containsExactly("R1", "R4");
        // Nergens een prijs 0 als gevolg van een parsefout.
        assertThat(jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and (after_base_price = 0 or before_base_price = 0)", Long.class, delivered.batchId()))
                .isZero();
    }

    @Test
    void aDeliveryBlockedDuringStagingAlsoRecordsExactlyOneMarkerAndNoMutations() {
        Fixture fixture = fixture("EMPTY");
        Delivered delivered = deliver(fixture, "REF-1", new byte[0]);

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(CsvRecordStreamer.CODE_SOURCE_FILE_EMPTY);
        assertThat(outcome.contentMutationCount()).isZero();
        assertThat(contentMutations(delivered.batchId())).isEmpty();
        assertThat(markers(delivered.batchId())).singleElement().satisfies(marker ->
                assertThat(marker.resultSummary()).contains("outcome=BLOCKED")
                        .contains("blockedCode=" + CsvRecordStreamer.CODE_SOURCE_FILE_EMPTY));
        assertThat(runs.findById(delivered.taskRunId()).orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.COMPLETED);
    }

    // --- Hashcollisie -------------------------------------------------------------------------------

    @Test
    void anIdentityHashThatMatchesAKnownOfferWithOtherComponentsBlocksTheDelivery() {
        Fixture fixture = fixture("COLL");
        Delivered first = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        screening.screen(first.batchId());
        acceptBaseline(fixture, first);
        // Forceer een collisie: zelfde hash, andere sleutelcomponenten.
        jdbc.update("update catalog_source_state set identity_supplier_reference = 'ANDERE' "
                + "where import_link_id = ? and identity_supplier_reference = 'R3'", fixture.linkId());

        Delivered second = deliver(fixture, "REF-2", csv(FIVE_ROWS));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(DeliveryScreeningService.CODE_IDENTITY_HASH_COLLISION);
        assertThat(outcome.contentMutationCount()).isZero();
        assertThat(contentMutations(second.batchId())).isEmpty();
        assertThat(markers(second.batchId())).hasSize(1);
    }

    /**
     * Twee regels met dezelfde hash maar andere sleutelcomponenten kunnen niet uit de normalisatie
     * komen (dat zou een SHA-256-collisie vereisen), dus wordt de detectiequery hier rechtstreeks op
     * een gemanipuleerde staging losgelaten. Zonder deze controle zou zo'n paar als "duplicaat"
     * gerapporteerd worden terwijl het twee verschillende aanbiedingen zijn.
     */
    @Test
    void aSharedIdentityHashWithDifferentComponentsInsideOneDeliveryIsRecognisedAsACollision() {
        Fixture fixture = fixture("COLLIN");
        Delivered delivered = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        screening.screen(delivered.batchId());
        assertThat(stage.findIdentityHashCollisionRow(delivered.batchId())).isEmpty();

        jdbc.update("update import_candidate_stage set identity_hash = "
                + "(select identity_hash from import_candidate_stage where batch_id = ? and row_number = 2) "
                + "where batch_id = ? and row_number = 4", delivered.batchId(), delivered.batchId());

        assertThat(stage.findIdentityHashCollisionRow(delivered.batchId())).hasValue(2L);
        // Een echt duplicaat (identieke componenten) blijft een duplicaat, geen collisie.
        assertThat(stage.countDuplicateRows(delivered.batchId())).isEqualTo(2L);
    }

    // --- Idempotentie en hervatten -------------------------------------------------------------------

    @Test
    void screeningTheSameDeliveryUnderTheSameRevisionTwiceIsRejected() {
        Fixture fixture = fixture("TWICE");
        Delivered delivered = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        screening.screen(delivered.batchId());

        assertThatThrownBy(() -> screening.screen(delivered.batchId()))
                .isInstanceOf(ConflictException.class)
                .extracting(failure -> ((ConflictException) failure).getCode())
                .isEqualTo(DeliveryScreeningService.CODE_ALREADY_SCREENED);

        assertThat(contentMutations(delivered.batchId())).hasSize(5);
        assertThat(markers(delivered.batchId())).hasSize(1);
    }

    @Test
    void aRedeliveryOfIdenticalContentUnderANewDeliveryIsScreenedAgainWithItsOwnKeys() {
        Fixture fixture = fixture("REDELIV");
        Delivered first = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        screening.screen(first.batchId());

        // Geen baseline: de bronstaat is nog leeg, dus dezelfde inhoud is opnieuw volledig nieuw.
        Delivered second = deliver(fixture, "REF-2", csv(FIVE_ROWS));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.newCount()).isEqualTo(5L);
        assertThat(contentMutations(second.batchId())).hasSize(5);
        assertThat(markers(second.batchId())).hasSize(1);
        assertThat(contentMutations(second.batchId()).get(0).idempotencyKey())
                .isNotEqualTo(contentMutations(first.batchId()).get(0).idempotencyKey());
    }

    @Test
    void runningTheMutationGenerationOfTheSameChunkTwiceNeverDuplicatesAMutation() {
        Fixture fixture = fixture("CHUNK2X");
        Delivered delivered = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        screening.screen(delivered.batchId());

        // Geen prijscomponenten op deze revisie: het domeinmasker blijft exact dat van fase 2. De
        // creatiereden (bouwstap 3h-3) doet hier niet ter zake: geen enkele rij wordt nog geschreven.
        int repeated = mutations.insertContentMutations(new MutationDao.MutationContext(delivered.batchId(),
                delivered.deliveryId(), fixture.linkId(), fixture.revisionId(), delivered.taskRunId(),
                delivered.deliveryFileId()), List.of(), null, 0L, 999L, Instant.now());

        assertThat(repeated).isZero();
        assertThat(contentMutations(delivered.batchId())).hasSize(5);
    }

    @Test
    void aBatchThatBreaksHalfwayThroughTheMutationGenerationStaysResumable() {
        Fixture fixture = fixture("RESUME");
        Delivered delivered = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 2) {
                throw new UncheckedIOException(new IOException("simulated failure halfway the generation"));
            }
            return invocation.callRealMethod();
        }).when(mutations).insertContentMutations(any(), any(), any(), anyLong(), anyLong(), any());

        assertThatThrownBy(() -> screening.screen(delivered.batchId()))
                .isInstanceOf(UncheckedIOException.class);

        // De eerste chunk is vastgelegd met zijn hervatpunt; de batch is niet FAILED maar hervatbaar.
        ImportBatch interrupted = batches.findById(delivered.batchId()).orElseThrow();
        assertThat(interrupted.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        assertThat(interrupted.getMutationProgressRowNumber()).isEqualTo(3L);
        assertThat(contentMutations(delivered.batchId())).hasSize(2);
        assertThat(markers(delivered.batchId())).isEmpty();

        Mockito.reset(mutations);
        ScreeningOutcome outcome = screening.continueMutating(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.newCount()).isEqualTo(5L);
        assertThat(outcome.contentMutationCount()).isEqualTo(5L);
        assertThat(contentMutations(delivered.batchId())).hasSize(5);
        assertThat(contentMutations(delivered.batchId())).extracting(MutationRow::idempotencyKey)
                .doesNotHaveDuplicates();
        assertThat(markers(delivered.batchId())).hasSize(1);
        assertThat(runs.findById(delivered.taskRunId()).orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.COMPLETED);
    }

    @Test
    void aBatchThatIsNotMutatingCannotBeResumed() {
        Fixture fixture = fixture("NORESUME");
        Delivered delivered = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        screening.screen(delivered.batchId());

        assertThatThrownBy(() -> screening.continueMutating(delivered.batchId()))
                .isInstanceOf(ConflictException.class)
                .extracting(failure -> ((ConflictException) failure).getCode())
                .isEqualTo(DeliveryScreeningService.CODE_BATCH_NOT_RESUMABLE);
    }

    /** De databaseconstraint is de harde garantie, niet de applicatiecontrole erboven. */
    @Test
    void aSecondMutationWithTheSameIdempotencyKeyIsRefusedByTheDatabase() {
        Fixture fixture = fixture("UKEY");
        Delivered delivered = deliver(fixture, "REF-1", csv(FIVE_ROWS));
        screening.screen(delivered.batchId());
        String existing = contentMutations(delivered.batchId()).get(0).idempotencyKey();

        assertThatThrownBy(() -> jdbc.update("insert into import_mutation (batch_id, delivery_id, "
                        + "import_link_id, definition_revision_id, action_type, target_domain, status, "
                        + "identity_supplier, identity_supplier_group, identity_supplier_reference, "
                        + "idempotency_key, created_at) values (?, ?, ?, ?, 'CREATE', 'OFFER', 'PLANNED', "
                        + "'ACME', 'G1', 'R1', ?, ?)",
                delivered.batchId(), delivered.deliveryId(), fixture.linkId(), fixture.revisionId(),
                existing, OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Scheiding tussen importkoppelingen -----------------------------------------------------------

    @Test
    void twoImportLinksWithTheSameSupplierKeyNeverSeeEachOthersSourceState() {
        Fixture left = fixture("LINKA");
        Fixture right = fixture("LINKB");
        Delivered onLeft = deliver(left, "REF-1", csv(FIVE_ROWS));
        screening.screen(onLeft.batchId());
        acceptBaseline(left, onLeft);

        Delivered onRight = deliver(right, "REF-1", csv(FIVE_ROWS));
        ScreeningOutcome outcome = screening.screen(onRight.batchId());

        // Dezelfde leverancierssleutel, maar een andere koppeling: geen kruisupdate.
        assertThat(outcome.newCount()).isEqualTo(5L);
        assertThat(outcome.unchangedCount()).isZero();
        assertThat(outcome.changedCount()).isZero();
        assertThat(contentMutations(onRight.batchId())).allSatisfy(row -> {
            assertThat(row.actionType()).isEqualTo("CREATE");
            assertThat(row.sourceStateId()).isNull();
        });
        assertThat(sourceStateCount(right.linkId())).isZero();
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private static byte[] csv(String... rows) {
        return (HEADER + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private DeliveryFile archivedFile(Delivered delivered) {
        return deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(delivered.deliveryId()).get(0);
    }

    /**
     * Vult de bronstaat vanuit de staging van een gescreende batch — precies wat de
     * {@code accept-baseline}-actie uit bouwstap 2e zal doen, zodat deze tests de delta al kunnen
     * bewijzen zonder die actie vooruit te lopen.
     */
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

    private long sourceStateCount(long importLinkId) {
        Long count = jdbc.queryForObject("select count(*) from catalog_source_state where import_link_id = ?",
                Long.class, importLinkId);
        return count == null ? 0L : count;
    }

    private List<Instant> sourceStateUpdatedAt(long importLinkId) {
        return jdbc.query("select updated_at from catalog_source_state where import_link_id = ? "
                        + "order by identity_supplier_reference",
                (resultSet, index) -> resultSet.getObject(1, OffsetDateTime.class).toInstant(), importLinkId);
    }

    private List<String> classifications(long batchId) {
        return jdbc.queryForList("select classification from import_candidate_stage where batch_id = ? "
                + "order by row_number", String.class, batchId);
    }

    private List<MutationRow> contentMutations(long batchId) {
        return mutationRows(batchId, "action_type <> 'IMPORT_MARKER'");
    }

    private List<MutationRow> markers(long batchId) {
        return mutationRows(batchId, "action_type = 'IMPORT_MARKER'");
    }

    /** Leest via kolomposities: H2 en PostgreSQL geven kolomnamen in een andere schrijfwijze terug. */
    private List<MutationRow> mutationRows(long batchId, String filter) {
        return jdbc.query("select action_type, target_domain, status, identity_supplier, "
                        + "identity_supplier_group, identity_supplier_reference, identity_hash, "
                        + "before_combined_fingerprint, after_combined_fingerprint, domain_mask, "
                        + "before_base_price, after_base_price, source_state_id, delivery_file_id, "
                        + "source_row_number, result_summary, idempotency_key, task_run_id, status_reason "
                        + "from import_mutation where batch_id = ? and " + filter + " order by id",
                (resultSet, index) -> new MutationRow(resultSet.getString(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getString(4), resultSet.getString(5),
                        resultSet.getString(6), resultSet.getBytes(7), resultSet.getBytes(8),
                        resultSet.getBytes(9), resultSet.getString(10), resultSet.getBigDecimal(11),
                        resultSet.getBigDecimal(12), (Long) resultSet.getObject(13),
                        (Long) resultSet.getObject(14), (Long) resultSet.getObject(15),
                        resultSet.getString(16), resultSet.getString(17), (Long) resultSet.getObject(18),
                        resultSet.getString(19)),
                batchId);
    }

    private record MutationRow(String actionType, String targetDomain, String status, String identitySupplier,
                               String identitySupplierGroup, String identitySupplierReference,
                               byte[] identityHash, byte[] beforeCombinedFingerprint,
                               byte[] afterCombinedFingerprint, String domainMask, BigDecimal beforeBasePrice,
                               BigDecimal afterBasePrice, Long sourceStateId, Long deliveryFileId,
                               Long sourceRowNumber, String resultSummary, String idempotencyKey,
                               Long taskRunId, String statusReason) {
    }

    private Delivered deliver(Fixture fixture, String reference, byte[] content) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(content)).delivery();
        return new Delivered(view.deliveryId(), view.batch().batchId(),
                deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(view.deliveryId()).get(0).getId(),
                runs.findByTaskIdAndConcurrencyTokenIsNotNull(fixture.taskId()).orElseThrow().getId());
    }

    private Fixture fixture(String prefix) {
        String unique = "FL" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
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
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId());
    }

    private record Fixture(long taskId, long linkId, long revisionId) {
    }

    private record Delivered(long deliveryId, long batchId, long deliveryFileId, long taskRunId) {
    }
}
