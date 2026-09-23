package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.BatchQueryService.MutationRow;
import be.dda.catalogimport.service.BundleDecisionService.MutationDecisionView;
import be.dda.catalogimport.service.BundleQueryService.DecisionRow;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
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
 * Bouwstap 4c (docs/design/fase4-publication-bundle-design.md par. 1 R-DEC, par. 3
 * "Goedkeuringsalgoritme (individueel)", par. 4): het individueel goedkeuren en afkeuren van één
 * mutatie binnen een Publicatiebundel, tegen de echte services, DAO's en H2.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Een beslissing zet de vier beslisvelden op de mutatie én schrijft exact één regel in het
 *       append-only register, met de juiste {@code previous_status}/{@code new_status}.</li>
 *   <li>Een herhaling door dezelfde beslisser verdubbelt niets; een herziening schrijft een nieuwe
 *       regel en laat de oude ongewijzigd staan.</li>
 *   <li>Vastgehouden ({@code BLOCKED}), identiteits- en markermutaties krijgen geen beslispad.</li>
 *   <li><b>Financiële onveranderlijkheid</b> (AGENT.md par. 2 principe 8): {@code before_base_price},
 *       {@code after_base_price}, {@code domain_mask}, {@code status_reason} en de vingerafdrukken
 *       zijn vóór en na een beslissing byte-identiek. Zou dit ooit breken, dan zou een goedkeuring
 *       stilzwijgend een bedrag kunnen wijzigen — en dan tekent iemand voor iets anders dan wat hij
 *       zag.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class BundleMutationDecisionTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String DECIDER = "an.janssens@example.test";
    private static final String OTHER_DECIDER = "piet.willems@example.test";
    /** Een id die in deze gedeelde H2 nooit bestaat. */
    private static final long UNKNOWN_ID = 9_999_999L;

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
    private PublicationBundleService bundleService;
    @Autowired
    private BundleDecisionService decisions;
    @Autowired
    private BundleQueryService queries;
    @Autowired
    private MutationDao mutationDao;
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
    private ImportMutationRepository mutations;
    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private JdbcTemplate jdbc;

    // --- (a) Goedkeuren van een wachtende mutatie -------------------------------------------------

    @Test
    void approvingAnAwaitingApprovalMutationMakesItReadyAndWritesOneDecision() {
        Scenario scenario = creationScenario("APPROVE");
        long mutationId = scenario.firstContentMutation();
        assertThat(statusOf(mutationId)).isEqualTo(MutationStatus.AWAITING_APPROVAL);

        MutationDecisionView view = decisions.approve(scenario.bundleId(), mutationId, DECIDER,
                "Nieuwe aanbiedingen gecontroleerd");

        assertThat(view.idempotent()).isFalse();
        assertThat(view.mutation().status()).isEqualTo("READY_FOR_PUBLICATION");
        assertThat(view.mutation().decidedBy()).isEqualTo(DECIDER);
        assertThat(view.mutation().decidedFromStatus()).isEqualTo("AWAITING_APPROVAL");
        assertThat(view.mutation().decisionId()).isEqualTo(view.decision().id());
        assertThat(view.mutation().decidedAt()).isEqualTo(view.decision().decidedAt());

        ImportMutation stored = mutations.findById(mutationId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        assertThat(stored.getDecidedBy()).isEqualTo(DECIDER);
        assertThat(stored.getDecidedAt()).isNotNull();
        assertThat(stored.getDecidedFromStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL);
        assertThat(stored.getDecisionId()).isEqualTo(view.decision().id());

        List<DecisionRow> register = queries.getBundleDecisions(scenario.bundleId(), 0, 50).content();
        assertThat(register).singleElement().satisfies(row -> {
            assertThat(row.decisionKind()).isEqualTo("APPROVE");
            assertThat(row.decisionScope()).isEqualTo("MUTATION");
            assertThat(row.mutationId()).isEqualTo(mutationId);
            assertThat(row.affectedCount()).isEqualTo(1L);
            assertThat(row.previousStatus()).isEqualTo("AWAITING_APPROVAL");
            assertThat(row.newStatus()).isEqualTo("READY_FOR_PUBLICATION");
            assertThat(row.decidedBy()).isEqualTo(DECIDER);
            assertThat(row.reason()).isEqualTo("Nieuwe aanbiedingen gecontroleerd");
        });
    }

    /** Zonder reden is een goedkeuring toegestaan; de regel draagt dan de vaste standaardreden. */
    @Test
    void approvingWithoutAReasonIsAllowedAndRecordsTheDefaultReason() {
        Scenario scenario = creationScenario("APPROVE-NOREASON");
        long mutationId = scenario.firstContentMutation();

        MutationDecisionView view = decisions.approve(scenario.bundleId(), mutationId, DECIDER, null);

        assertThat(view.decision().reason()).isEqualTo(BundleDecisionService.DEFAULT_APPROVAL_REASON);
        assertThat(statusOf(mutationId)).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
    }

    // --- (b) Afkeuren vereist altijd een reden ----------------------------------------------------

    @Test
    void rejectingRequiresAReasonAndThenMarksTheMutationRejected() {
        Scenario scenario = updateScenario("REJECT");
        long mutationId = scenario.firstContentMutation();
        assertThat(statusOf(mutationId)).isEqualTo(MutationStatus.PLANNED);

        assertThatThrownBy(() -> decisions.reject(scenario.bundleId(), mutationId, DECIDER, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.reject(scenario.bundleId(), mutationId, DECIDER, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        // Niets geschreven door een geweigerde aanvraag.
        assertThat(statusOf(mutationId)).isEqualTo(MutationStatus.PLANNED);
        assertThat(decisionCount(mutationId)).isZero();

        MutationDecisionView view = decisions.reject(scenario.bundleId(), mutationId, DECIDER,
                "Prijsstijging niet onderbouwd");

        assertThat(view.mutation().status()).isEqualTo("REJECTED");
        assertThat(view.decision().decisionKind()).isEqualTo("REJECT");
        assertThat(view.decision().previousStatus()).isEqualTo("PLANNED");
        assertThat(statusOf(mutationId)).isEqualTo(MutationStatus.REJECTED);
    }

    @Test
    void anInvalidDeciderIsRefusedBeforeAnythingIsWritten() {
        Scenario scenario = creationScenario("DECIDER");
        long mutationId = scenario.firstContentMutation();

        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), mutationId, "system", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), mutationId, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), mutationId, "x".repeat(101), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(statusOf(mutationId)).isEqualTo(MutationStatus.AWAITING_APPROVAL);
        assertThat(decisionCount(mutationId)).isZero();
    }

    // --- (c) Idempotentie --------------------------------------------------------------------------

    @Test
    void aSecondIdenticalApprovalByTheSameDeciderWritesNoSecondDecision() {
        Scenario scenario = creationScenario("IDEM");
        long mutationId = scenario.firstContentMutation();
        MutationDecisionView first = decisions.approve(scenario.bundleId(), mutationId, DECIDER, "Akkoord");

        MutationDecisionView second = decisions.approve(scenario.bundleId(), mutationId, DECIDER, "Akkoord");

        assertThat(second.idempotent()).isTrue();
        assertThat(second.decision().id()).isEqualTo(first.decision().id());
        assertThat(second.mutation().status()).isEqualTo("READY_FOR_PUBLICATION");
        assertThat(decisionCount(mutationId)).isEqualTo(1L);
    }

    /**
     * Dezelfde doelstatus, maar een <b>andere</b> beslisser: bewust géén idempotentie maar een extra
     * regel in het register. Twee mensen die tekenen zijn twee vaststellingen; de eerste regel blijft
     * onaangeroerd staan.
     */
    @Test
    void theSameTargetStatusByAnotherDeciderAddsAnAuditTrailEntry() {
        Scenario scenario = creationScenario("IDEM-OTHER");
        long mutationId = scenario.firstContentMutation();
        MutationDecisionView first = decisions.approve(scenario.bundleId(), mutationId, DECIDER, "Akkoord");

        MutationDecisionView second = decisions.approve(scenario.bundleId(), mutationId, OTHER_DECIDER,
                "Tweede lezing");

        assertThat(second.idempotent()).isFalse();
        assertThat(second.decision().id()).isNotEqualTo(first.decision().id());
        assertThat(second.decision().previousStatus()).isEqualTo("READY_FOR_PUBLICATION");
        assertThat(second.decision().newStatus()).isEqualTo("READY_FOR_PUBLICATION");
        assertThat(decisionCount(mutationId)).isEqualTo(2L);
        assertThat(statusOf(mutationId)).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        assertThat(mutations.findById(mutationId).orElseThrow().getDecidedBy()).isEqualTo(OTHER_DECIDER);
    }

    // --- (d) Herziening ----------------------------------------------------------------------------

    @Test
    void aRevisionWritesANewDecisionAndLeavesTheOldOneUntouched() {
        Scenario scenario = creationScenario("REVISION");
        long mutationId = scenario.firstContentMutation();
        MutationDecisionView approval = decisions.approve(scenario.bundleId(), mutationId, DECIDER, "Akkoord");

        // Een herziening zonder reden wordt geweigerd: wie een ondertekende beslissing omkeert,
        // verantwoordt dat.
        assertThatThrownBy(() -> decisions.reject(scenario.bundleId(), mutationId, DECIDER, null))
                .isInstanceOf(IllegalArgumentException.class);

        MutationDecisionView revision = decisions.reject(scenario.bundleId(), mutationId, DECIDER,
                "Toch geblokkeerd door de leverancier");

        assertThat(revision.idempotent()).isFalse();
        assertThat(revision.decision().previousStatus()).isEqualTo("READY_FOR_PUBLICATION");
        assertThat(revision.decision().newStatus()).isEqualTo("REJECTED");
        assertThat(statusOf(mutationId)).isEqualTo(MutationStatus.REJECTED);

        ImportMutation stored = mutations.findById(mutationId).orElseThrow();
        assertThat(stored.getDecisionId()).isEqualTo(revision.decision().id());
        assertThat(stored.getDecidedFromStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);

        // Append-only: de eerste regel staat er nog, ongewijzigd.
        List<DecisionRow> register = queries.getBundleDecisions(scenario.bundleId(), 0, 50).content();
        assertThat(register).hasSize(2);
        assertThat(register.get(0).id()).isEqualTo(approval.decision().id());
        assertThat(register.get(0).decisionKind()).isEqualTo("APPROVE");
        assertThat(register.get(0).previousStatus()).isEqualTo("AWAITING_APPROVAL");
        assertThat(register.get(0).newStatus()).isEqualTo("READY_FOR_PUBLICATION");
        assertThat(register.get(1).id()).isEqualTo(revision.decision().id());

        // En terug: een afgekeurde mutatie mag opnieuw goedgekeurd worden zolang de bundel ASSEMBLING is.
        MutationDecisionView back = decisions.approve(scenario.bundleId(), mutationId, DECIDER,
                "Leverancier bevestigde alsnog");
        assertThat(back.decision().previousStatus()).isEqualTo("REJECTED");
        assertThat(statusOf(mutationId)).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        assertThat(decisionCount(mutationId)).isEqualTo(3L);
    }

    // --- (e) Mutaties zonder beslispad -------------------------------------------------------------

    @Test
    void aBlockedMutationCannotBeApprovedOrRejected() {
        Scenario scenario = creationScenario("BLOCKED");
        long mutationId = scenario.firstContentMutation();
        // Bouwstap 4c bouwt de referentiecontrole niet na; de status wordt hier rechtstreeks gezet,
        // exact zoals pass E5 ze bij een vastgehouden regel zou schrijven (MutationDao).
        jdbc.update("update import_mutation set status = 'BLOCKED', status_reason = ? where id = ?",
                "IDENTITY_REFERENCE_INCIDENT", mutationId);

        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), mutationId, DECIDER, null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code",
                        BundleDecisionService.CODE_MUTATION_BLOCKED_BY_IDENTITY_INCIDENT);
        assertThatThrownBy(() -> decisions.reject(scenario.bundleId(), mutationId, DECIDER, "Weg ermee"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code",
                        BundleDecisionService.CODE_MUTATION_BLOCKED_BY_IDENTITY_INCIDENT);
        assertThat(decisionCount(mutationId)).isZero();
    }

    @Test
    void anIdentityReferenceIncidentHasNoDecisionPathInPhaseFour() {
        Scenario scenario = creationScenario("IDENTITY");
        long mutationId = scenario.firstContentMutation();
        jdbc.update("update import_mutation set action_type = 'IDENTITY_REFERENCE_INCIDENT', "
                + "reference_type = 'EAN', after_reference_value = '5410000000001' where id = ?", mutationId);

        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), mutationId, DECIDER, null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_IDENTITY_DECISION_NOT_IN_SCOPE);
        assertThat(decisionCount(mutationId)).isZero();
    }

    @Test
    void theImportMarkerAndTerminalStatusesAreNotDecidable() {
        Scenario scenario = creationScenario("MARKER");
        long markerId = mutationsOf(scenario.batchId()).stream()
                .filter(m -> m.getActionType() == MutationActionType.IMPORT_MARKER)
                .findFirst().orElseThrow().getId();

        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), markerId, DECIDER, null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_MUTATION_NOT_DECIDABLE);

        long skipped = scenario.firstContentMutation();
        jdbc.update("update import_mutation set status = 'SKIPPED' where id = ?", skipped);
        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), skipped, DECIDER, null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_MUTATION_NOT_DECIDABLE);
    }

    // --- (f) Lidmaatschap, onbekende ids en een bundel die niets meer aanvaardt ---------------------

    @Test
    void aMutationOfABatchWithoutAnActiveMembershipIsNotFound() {
        Scenario scenario = creationScenario("MEMBERSHIP");
        long mutationId = scenario.firstContentMutation();
        bundleService.removeBatch(scenario.bundleId(), scenario.batchId(), CREATOR, "Toch niet meenemen");

        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), mutationId, DECIDER, null))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_MUTATION_NOT_IN_BUNDLE);
    }

    @Test
    void anUnknownBundleOrMutationIsNotFound() {
        Scenario scenario = creationScenario("UNKNOWN");
        long mutationId = scenario.firstContentMutation();

        assertThatThrownBy(() -> decisions.approve(UNKNOWN_ID, mutationId, DECIDER, null))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_BUNDLE_NOT_FOUND);
        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), UNKNOWN_ID, DECIDER, null))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_MUTATION_NOT_IN_BUNDLE);
    }

    /**
     * Bevriezen komt pas in 4e; de status wordt hier rechtstreeks op de entiteit gezet (met de audit
     * die {@code ck_publication_bundle_frozen} eist) om te bewijzen dat een niet-{@code ASSEMBLING}
     * bundel geen beslissingen meer aanvaardt.
     */
    @Test
    void aBundleThatIsNoLongerAssemblingRefusesDecisions() {
        Scenario scenario = creationScenario("FROZEN");
        long mutationId = scenario.firstContentMutation();
        PublicationBundle bundle = bundles.findById(scenario.bundleId()).orElseThrow();
        bundle.recordFreeze(CREATOR, Instant.now(), "Klaar voor publicatie", new byte[32]);
        bundle.setStatus(PublicationBundleStatus.FROZEN);
        bundles.saveAndFlush(bundle);

        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), mutationId, DECIDER, null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_BUNDLE_NOT_ASSEMBLING);
        assertThat(decisionCount(mutationId)).isZero();
    }

    // --- (g) Financiële onveranderlijkheid ----------------------------------------------------------

    /**
     * Het kernbewijs van deze bouwstap (ontwerp par. 1 R-DEC, AGENT.md par. 2 principe 8): een
     * prijswijziging die op goedkeuring wacht wegens een bulkprijsincident, wordt goedgekeurd zonder
     * dat één financieel veld of de reden waaróm ze wachtte verandert.
     */
    @Test
    void aDecisionNeverTouchesFinancialFieldsOrTheStatusReason() {
        Scenario scenario = updateScenario("MONEY");
        // Pass E5b van de screening (R-PRI-14): elke geplande prijswijziging wacht op goedkeuring.
        int held = mutationDao.holdPlannedPriceUpdates(scenario.batchId(), "BULK_PRICE_INCIDENT");
        assertThat(held).isPositive();

        long mutationId = scenario.firstContentMutation();
        Map<String, Object> before = financialSnapshot(mutationId);
        byte[] identityBefore = hashOf(mutationId, "identity_hash");
        byte[] beforeFingerprint = hashOf(mutationId, "before_combined_fingerprint");
        byte[] afterFingerprint = hashOf(mutationId, "after_combined_fingerprint");
        assertThat(before.get("BEFORE_BASE_PRICE")).isNotNull();
        assertThat(before.get("AFTER_BASE_PRICE")).isNotNull();
        assertThat(before.get("DOMAIN_MASK")).asString().contains("PRICE");
        assertThat(before.get("STATUS_REASON")).isEqualTo("BULK_PRICE_INCIDENT");

        decisions.approve(scenario.bundleId(), mutationId, DECIDER, "Prijsstijging bevestigd door leverancier");

        assertThat(statusOf(mutationId)).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        assertThat(financialSnapshot(mutationId)).isEqualTo(before);
        assertThat(hashOf(mutationId, "identity_hash")).isEqualTo(identityBefore);
        assertThat(hashOf(mutationId, "before_combined_fingerprint")).isEqualTo(beforeFingerprint);
        assertThat(hashOf(mutationId, "after_combined_fingerprint")).isEqualTo(afterFingerprint);
        // De reden waaróm ze wachtte blijft staan: dat is waarvoor iemand tekende.
        assertThat(financialSnapshot(mutationId).get("STATUS_REASON")).isEqualTo("BULK_PRICE_INCIDENT");
    }

    // --- (h) Leesendpoints ---------------------------------------------------------------------------

    @Test
    void theBundleMutationListPagesFiltersAndShowsTheDecisionFields() {
        Scenario scenario = creationScenario("READ");
        long mutationId = scenario.firstContentMutation();
        decisions.approve(scenario.bundleId(), mutationId, DECIDER, "Akkoord");

        PageResult<MutationRow> all = queries.getBundleMutations(scenario.bundleId(), null, null, null, 0, 50);
        // Vijf inhoudelijke mutaties + de IMPORT_MARKER van de screening.
        assertThat(all.totalElements()).isEqualTo(6L);
        assertThat(all.content()).allSatisfy(row -> assertThat(row.batchId()).isEqualTo(scenario.batchId()));
        assertThat(all.content()).filteredOn(row -> row.id() == mutationId).singleElement()
                .satisfies(row -> {
                    assertThat(row.decidedBy()).isEqualTo(DECIDER);
                    assertThat(row.decidedFromStatus()).isEqualTo("AWAITING_APPROVAL");
                    assertThat(row.decidedAt()).isNotNull();
                    assertThat(row.decisionId()).isNotNull();
                });

        PageResult<MutationRow> ready = queries.getBundleMutations(scenario.bundleId(),
                MutationStatus.READY_FOR_PUBLICATION, null, null, 0, 50);
        assertThat(ready.content()).singleElement()
                .satisfies(row -> assertThat(row.id()).isEqualTo(mutationId));

        PageResult<MutationRow> markers = queries.getBundleMutations(scenario.bundleId(), null, null,
                MutationActionType.IMPORT_MARKER, 0, 50);
        assertThat(markers.totalElements()).isEqualTo(1L);

        PageResult<MutationRow> byBatch = queries.getBundleMutations(scenario.bundleId(), null,
                scenario.batchId(), null, 0, 2);
        assertThat(byBatch.content()).hasSize(2);
        assertThat(byBatch.totalElements()).isEqualTo(6L);
        assertThat(byBatch.totalPages()).isEqualTo(3);

        // Een batch die geen (actief) lid is van deze bundel levert een lege pagina op, geen fout.
        assertThat(queries.getBundleMutations(scenario.bundleId(), null, UNKNOWN_ID, null, 0, 50).content())
                .isEmpty();
    }

    @Test
    void theDecisionRegisterIsPagedAndChronological() {
        Scenario scenario = creationScenario("REGISTER");
        List<ImportMutation> content = contentMutations(scenario.batchId());
        decisions.approve(scenario.bundleId(), content.get(0).getId(), DECIDER, "Eerste");
        decisions.reject(scenario.bundleId(), content.get(1).getId(), DECIDER, "Tweede");
        decisions.approve(scenario.bundleId(), content.get(2).getId(), OTHER_DECIDER, "Derde");

        PageResult<DecisionRow> page = queries.getBundleDecisions(scenario.bundleId(), 0, 2);

        assertThat(page.totalElements()).isEqualTo(3L);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).extracting(DecisionRow::reason).containsExactly("Eerste", "Tweede");
        assertThat(queries.getBundleDecisions(scenario.bundleId(), 1, 2).content())
                .extracting(DecisionRow::reason).containsExactly("Derde");
        assertThat(page.content()).extracting(DecisionRow::bundleId)
                .containsOnly(scenario.bundleId());
    }

    @Test
    void readingMutationsOrDecisionsOfAnUnknownBundleIsNotFound() {
        assertThatThrownBy(() -> queries.getBundleMutations(UNKNOWN_ID, null, null, null, 0, 50))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", PublicationBundleService.CODE_BUNDLE_NOT_FOUND);
        assertThatThrownBy(() -> queries.getBundleDecisions(UNKNOWN_ID, 0, 50))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", PublicationBundleService.CODE_BUNDLE_NOT_FOUND);
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    /** Een bundel met één batch vol wachtende creaties (eerste levering van de koppeling). */
    private Scenario creationScenario(String prefix) {
        Fixture f = fixture(prefix);
        long batchId = screenedBatch(f, "REF-1", rows("1,50", "2,25", "3,00", "4,00", "5,00"));
        return bundleWith(f, batchId);
    }

    /**
     * Een bundel met één batch vol geplande prijswijzigingen: eerste levering aanvaard als nulmeting,
     * tweede levering met dezelfde identiteiten en andere prijzen.
     */
    private Scenario updateScenario(String prefix) {
        Fixture f = fixture(prefix);
        long first = screenedBatch(f, "REF-1", rows("1,50", "2,25", "3,00", "4,00", "5,00"));
        baseline.acceptBaseline(first, CREATOR, "Nulmeting");
        long second = screenedBatch(f, "REF-2", rows("1,75", "2,50", "3,25", "4,25", "5,25"));
        return bundleWith(f, second);
    }

    private Scenario bundleWith(Fixture f, long batchId) {
        BundleReference bundle = bundleService.createBundle("BND-" + f.unique(), null,
                PublicationTargetMode.SIMULATION, null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);
        return new Scenario(bundle.id(), batchId, contentMutations(batchId).get(0).getId());
    }

    private record Scenario(long bundleId, long batchId, long firstContentMutation) {
    }

    private List<ImportMutation> mutationsOf(long batchId) {
        return mutations.findByBatchId(batchId, PageRequest.of(0, 100)).getContent();
    }

    private List<ImportMutation> contentMutations(long batchId) {
        return mutationsOf(batchId).stream()
                .filter(m -> m.getActionType() == MutationActionType.CREATE
                        || m.getActionType() == MutationActionType.UPDATE)
                .toList();
    }

    private MutationStatus statusOf(long mutationId) {
        return mutations.findById(mutationId).orElseThrow().getStatus();
    }

    private long decisionCount(long mutationId) {
        Long count = jdbc.queryForObject("select count(*) from publication_decision where mutation_id = ?",
                Long.class, mutationId);
        return count == null ? 0L : count;
    }

    /** Alles wat een beslissing <b>niet</b> mag aanraken, in één keer vergelijkbaar. */
    private Map<String, Object> financialSnapshot(long mutationId) {
        return jdbc.queryForMap("select before_base_price, after_base_price, base_price_currency, domain_mask, "
                + "status_reason, source_row_number, delivery_file_id, source_state_id, identity_supplier, "
                + "identity_supplier_group, identity_supplier_reference, idempotency_key, created_at "
                + "from import_mutation where id = ?", mutationId);
    }

    private byte[] hashOf(long mutationId, String column) {
        return jdbc.queryForObject("select " + column + " from import_mutation where id = ?", byte[].class,
                mutationId);
    }

    private String[] rows(String... prices) {
        String[] descriptions = {"Boormachine", "Schroevendraaier", "Hamer", "Zaag", "Beitel"};
        String[] rows = new String[prices.length];
        for (int index = 0; index < prices.length; index++) {
            rows[index] = "ACME;G1;R" + (index + 1) + ";" + prices[index] + ";" + descriptions[index];
        }
        return rows;
    }

    private long screenedBatch(Fixture f, String reference, String[] rows) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        var delivery = intake.intake(f.taskId(), reference, "tester@example.test", null, null, "levering.csv",
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))).delivery();
        long batchId = delivery.batch().batchId();
        screening.screen(batchId);
        return batchId;
    }

    private Fixture fixture(String prefix) {
        String unique = "BMD" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        // bestaande fase 3/4-tests (PublicationBundleLifecycleTest, BatchBaselineHttpTest).
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), unique);
    }

    private record Fixture(long taskId, long linkId, String unique) {
    }
}
