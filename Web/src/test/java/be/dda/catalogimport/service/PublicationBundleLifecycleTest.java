package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.PublicationBundleBatchRepository;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationDecisionRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.BundleDecisionScope;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleBatch;
import be.dda.catalogimport.domain.PublicationDecision;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.BundleQueryService.BundleDetail;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import be.dda.catalogimport.service.PublicationBundleService.Membership;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Bouwstap 4b (docs/design/fase4-publication-bundle-design.md par. 3 en 6): de levenscyclus van een
 * Publicatiebundel tot en met batchbeheer — nog geen beslissingen, bevriezing of annulering (4c-4f).
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class PublicationBundleLifecycleTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";

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
    private PublicationBundleService bundleService;
    @Autowired
    private BundleQueryService queries;
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
    private ImportBatchRepository batches;
    @Autowired
    private ImportMutationRepository mutations;
    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private PublicationBundleBatchRepository bundleBatches;
    @Autowired
    private PublicationDecisionRepository decisions;
    @Autowired
    private SourceStateBaselineService baselineService;

    // --- Aanmaken: idempotent op bundleReference ------------------------------------------------

    @Test
    void createIsIdempotentOnBundleReferenceWithTheSameScope() {
        String idemRef = bundleRef("IDEM");
        BundleReference first = bundleService.createBundle(idemRef, "Eerste beschrijving",
                PublicationTargetMode.SIMULATION, null, null, CREATOR);
        BundleReference second = bundleService.createBundle(idemRef, "Eerste beschrijving",
                PublicationTargetMode.SIMULATION, null, null, "an.janssels@example.test");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(bundles.findByBundleReference(idemRef)).hasValueSatisfying(
                bundle -> assertThat(bundle.getCreatedBy()).isEqualTo(CREATOR));
    }

    @Test
    void aSecondCreateWithTheSameReferenceButADifferentScopeIsAConflict() {
        String scopeRef = bundleRef("SCOPE");
        bundleService.createBundle(scopeRef, "Beschrijving A", PublicationTargetMode.SIMULATION, null, null,
                CREATOR);

        assertThatThrownBy(() -> bundleService.createBundle(scopeRef, "Beschrijving A",
                PublicationTargetMode.TRIAL_LIBRARY, null, null, CREATOR))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationBundleService.CODE_REFERENCE_REUSED);

        assertThatThrownBy(() -> bundleService.createBundle(scopeRef, "Beschrijving B",
                PublicationTargetMode.SIMULATION, null, null, CREATOR))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationBundleService.CODE_REFERENCE_REUSED);
    }

    @Test
    void createRefusesAMissingTargetModeAndAnInvalidCreatedBy() {
        assertThatThrownBy(() -> bundleService.createBundle(bundleRef("BAD"), null, null, null, null, CREATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bundleService.createBundle(bundleRef("BAD2"), null, PublicationTargetMode.SIMULATION,
                null, null, "system"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bundleService.createBundle(bundleRef("BAD3"), null, PublicationTargetMode.SIMULATION,
                null, null, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Batches toevoegen -----------------------------------------------------------------------

    @Test
    void addsTwoDifferentImportLinksToOneBundle() {
        Fixture a = fixture("MULTI-A");
        Fixture b = fixture("MULTI-B");
        long batchA = screenedBatch(a, "REF-1", fiveRows());
        long batchB = screenedBatch(b, "REF-1", fiveRows());
        BundleReference bundle = bundleService.createBundle(bundleRef("MULTI"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);

        List<Membership> added = bundleService.addBatches(bundle.id(), List.of(batchA, batchB), CREATOR);

        assertThat(added).hasSize(2);
        assertThat(added).allSatisfy(m -> assertThat(m.active()).isTrue());
        assertThat(bundleBatches.findByBundleId(bundle.id())).hasSize(2)
                .extracting(PublicationBundleBatch::getBatch).extracting(ImportBatch::getId)
                .containsExactlyInAnyOrder(batchA, batchB);
    }

    @Test
    void aBatchThatIsAlreadyActiveInAnotherBundleIsRefused() {
        Fixture f = fixture("ACTIVE");
        long batchId = screenedBatch(f, "REF-1", fiveRows());
        BundleReference bundleA = bundleService.createBundle(bundleRef("ACT-A"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);
        BundleReference bundleB = bundleService.createBundle(bundleRef("ACT-B"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);
        bundleService.addBatches(bundleA.id(), List.of(batchId), CREATOR);

        assertThatThrownBy(() -> bundleService.addBatches(bundleB.id(), List.of(batchId), CREATOR))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationBundleService.CODE_BATCH_ALREADY_IN_BUNDLE);
        // Alles-of-niets: de bundel die de weigering veroorzaakte kreeg geen enkel lid.
        assertThat(bundleBatches.findByBundleId(bundleB.id())).isEmpty();
    }

    @Test
    void aBatchWithABlockingOrUnestablishedValidationResultIsRefused() {
        Fixture blocked = fixture("BLK");
        // Dubbele identiteit in dezelfde levering -> batch BLOCKED, geen SCREENED.
        long blockedBatch = deliverAndScreen(blocked, "REF-1", HEADER
                + "ACME;G1;R1;1,50;Boormachine\n" + "ACME;G1;R1;2,00;Nog een boormachine\n").batchId();
        assertThat(batches.findById(blockedBatch).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.BLOCKED);

        BundleReference bundle = bundleService.createBundle(bundleRef("BLK"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);
        assertThatThrownBy(() -> bundleService.addBatches(bundle.id(), List.of(blockedBatch), CREATOR))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationBundleService.CODE_BATCH_NOT_BUNDLEABLE);
    }

    @Test
    void aBaselineAcceptedBatchCannotJoinABundle() {
        Fixture f = fixture("BLA");
        long batchId = screenedBatch(f, "REF-1", fiveRows());
        SourceStateBaselineService baseline = baselineService();
        baseline.acceptBaseline(batchId, CREATOR, "Nulmeting");
        assertThat(batches.findById(batchId).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.BASELINE_ACCEPTED);

        BundleReference bundle = bundleService.createBundle(bundleRef("BLA"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);
        assertThatThrownBy(() -> bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationBundleService.CODE_BATCH_NOT_BUNDLEABLE);
    }

    @Test
    void aScreenedBatchWithOnlyTheImportMarkerAndNoContentMutationsMayStillJoin() {
        Fixture f = fixture("MARKERONLY");
        long firstBatch = screenedBatch(f, "REF-1", fiveRows());
        baselineService().acceptBaseline(firstBatch, CREATOR, "Nulmeting");
        // Herlevering met exact dezelfde inhoud: 0 inhoudelijke mutaties, wel een IMPORT_MARKER.
        long secondBatch = screenedBatch(f, "REF-2", fiveRows());
        assertThat(batches.findById(secondBatch).orElseThrow().getContentMutationCount()).isZero();

        BundleReference bundle = bundleService.createBundle(bundleRef("MARKER"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);
        List<Membership> added = bundleService.addBatches(bundle.id(), List.of(secondBatch), CREATOR);

        assertThat(added).singleElement().satisfies(m -> assertThat(m.batchId()).isEqualTo(secondBatch));
    }

    // --- Batches verwijderen -----------------------------------------------------------------------

    @Test
    void removingAMembershipWithoutDecisionsWorksAndFreesTheBatch() {
        Fixture f = fixture("REMOVE-OK");
        long batchId = screenedBatch(f, "REF-1", fiveRows());
        BundleReference bundle = bundleService.createBundle(bundleRef("REMOVE-OK"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);

        Membership removed = bundleService.removeBatch(bundle.id(), batchId, "remover@example.test",
                "Foutieve batch geselecteerd");

        assertThat(removed.active()).isFalse();
        assertThat(bundleBatches.findByBatchIdAndActiveMarkerIsNotNull(batchId)).isEmpty();

        // De batch is weer vrij: ze kan nu aan een andere bundel toegevoegd worden.
        BundleReference other = bundleService.createBundle(bundleRef("REMOVE-OTHER"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);
        List<Membership> added = bundleService.addBatches(other.id(), List.of(batchId), CREATOR);
        assertThat(added).singleElement().satisfies(m -> assertThat(m.active()).isTrue());
    }

    /**
     * Bouwstap 4b kan een echte beslissing nog niet via de service zetten (dat is 4c); deze test
     * schrijft daarom rechtstreeks via de repository's een {@code publication_decision} en koppelt haar
     * aan een mutatie, om te bewijzen dat {@code removeBatch} zo'n batch weigert. Voorbereiding op 4c,
     * duidelijk gemarkeerd.
     */
    @Test
    void removingABatchWithADecidedMutationIsRefused() {
        Fixture f = fixture("REMOVE-DEC");
        long batchId = screenedBatch(f, "REF-1", fiveRows());
        BundleReference bundle = bundleService.createBundle(bundleRef("REMOVE-DEC"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);

        ImportMutation mutation = mutations.findByBatchId(batchId,
                        org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0);
        PublicationBundle bundleEntity = bundles.findById(bundle.id()).orElseThrow();
        PublicationDecision decision = decisions.saveAndFlush(new PublicationDecision(bundleEntity, mutation,
                BundleDecisionKind.APPROVE, BundleDecisionScope.MUTATION, 1, "decider@example.test",
                "Vooraf ingevoegde beslissing (voorbereiding op 4c)"));
        mutation.recordDecision("decider@example.test", Instant.now(), mutation.getStatus(), decision.getId());
        mutations.saveAndFlush(mutation);

        assertThatThrownBy(() -> bundleService.removeBatch(bundle.id(), batchId, "remover@example.test", "Toch weg"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationBundleService.CODE_BATCH_HAS_DECIDED_MUTATIONS);
    }

    // --- Kandidaten en leesmodel -------------------------------------------------------------------

    @Test
    void candidatesShowOnlyBundleableBatchesAndDisappearOnceActive() {
        Fixture f = fixture("CAND");
        long candidateBatch = screenedBatch(f, "REF-1", fiveRows());

        PageResult<PublicationBundleService.BundleCandidate> before =
                bundleService.candidates(f.linkId(), 0, 50);
        assertThat(before.content()).extracting(PublicationBundleService.BundleCandidate::batchId)
                .contains(candidateBatch);

        BundleReference bundle = bundleService.createBundle(bundleRef("CAND"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(candidateBatch), CREATOR);

        PageResult<PublicationBundleService.BundleCandidate> after =
                bundleService.candidates(f.linkId(), 0, 50);
        assertThat(after.content()).extracting(PublicationBundleService.BundleCandidate::batchId)
                .doesNotContain(candidateBatch);
    }

    @Test
    void getBundleShowsLiveCountersWhileAssembling() {
        Fixture f = fixture("LIVE");
        long batchId = screenedBatch(f, "REF-1", fiveRows());
        BundleReference bundle = bundleService.createBundle(bundleRef("LIVE"), null, PublicationTargetMode.SIMULATION,
                null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);

        BundleDetail detail = queries.getBundle(bundle.id());

        assertThat(detail.status()).isEqualTo("ASSEMBLING");
        assertThat(detail.batchCount()).isEqualTo(1L);
        // Eerste levering van de koppeling: initialisatie, dus AWAITING_APPROVAL, geen READY_FOR_PUBLICATION.
        assertThat(detail.contentMutationCount()).isEqualTo(5L);
        assertThat(detail.readyCount()).isZero();
        assertThat(detail.rejectedCount()).isZero();
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private SourceStateBaselineService baselineService() {
        return baselineService;
    }

    private String[] fiveRows() {
        return new String[] {
                "ACME;G1;R1;1,50;Boormachine",
                "ACME;G1;R2;2,25;Schroevendraaier",
                "ACME;G1;R3;3,00;Hamer",
                "ACME;G1;R4;4,00;Zaag",
                "ACME;G1;R5;5,00;Beitel"};
    }

    private long screenedBatch(Fixture f, String reference, String[] rows) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        return deliverAndScreen(f, reference, csv.toString()).batchId();
    }

    private ScreeningResult deliverAndScreen(Fixture f, String reference, String csv) {
        var delivery = intake.intake(f.taskId(), reference, "tester@example.test", null, null, "levering.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        ScreeningOutcome outcome = screening.screen(delivery.batch().batchId());
        return new ScreeningResult(delivery.batch().batchId(), outcome);
    }

    private record ScreeningResult(long batchId, ScreeningOutcome outcome) {
    }

    private String bundleRef(String prefix) {
        return "BND-" + prefix + "-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet();
    }

    private Fixture fixture(String prefix) {
        String unique = "PBL" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1, IdentityProfileKind.THREE_PART,
                "beheerder@example.test");
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
        // Deze tests gaan niet over de drempel op records ter beoordeling; op 100 gezet zoals de
        // bestaande fase 3/4-tests (AcceptBaselineReviewFlowTest, BatchBaselineHttpTest).
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        var task = tasks.saveAndFlush(new be.dda.catalogimport.domain.CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId());
    }

    private record Fixture(long taskId, long linkId) {
    }
}
