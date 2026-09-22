package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
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
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.BundleFreezeService.BundleFreezeView;
import be.dda.catalogimport.service.BundleQueryService.BundleDetail;
import be.dda.catalogimport.service.BundleQueryService.DecisionRow;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * Bouwstap 4e (docs/design/fase4-publication-bundle-design.md par. 1 R-FRZ, par. 3, par. 4, par. 6
 * stap 4e): het bevriezen van een Publicatiebundel, tegen de echte services, DAO's en H2. De
 * conflictregels R-FRZ-03/04 en de baselinecontrole staan in {@code BundleConflictTest}.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Bevriezen keurt de resterende {@code PLANNED}-mutaties in bulk goed op naam van de bevriezer
 *       (beslissingslog 22/09, keuze 1), met per mutatie haar eigen audit, en schrijft <b>twee</b>
 *       beslissingsregels: de bulkgoedkeuring en de handeling bevriezen zelf.</li>
 *   <li>De tien tellers en de bundelhash worden bij het bevriezen vastgesteld en daarna van de rij
 *       gelezen in plaats van live herberekend.</li>
 *   <li>Eén openstaande {@code AWAITING_APPROVAL}-mutatie blokkeert het bevriezen, en die weigering
 *       laat <b>niets</b> achter: geen beslissingsregel, geen statuswijziging, de bundel nog
 *       {@code ASSEMBLING}.</li>
 *   <li>{@code BLOCKED}-mutaties en identiteitsincidenten beletten het bevriezen niet en blijven staan
 *       zoals ze stonden (ontwerp par. 3.6, beslissingslog 22/09 keuze 3).</li>
 *   <li>Een bevroren bundel is dicht: een tweede bevriezing, batches toevoegen/verwijderen en elke
 *       individuele of groepsbeslissing geven 409.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class BundleFreezeTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String FREEZE_REASON = "Prijsronde september goedgekeurd";

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
    private BundleFreezeService freezeService;
    @Autowired
    private BundleQueryService queries;
    @Autowired
    private PublicationBundleDao dao;
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
    private JdbcTemplate jdbc;

    // --- (a) De gelukte bevriezing ----------------------------------------------------------------

    /**
     * Het kernbewijs: een bundel met enkel geplande wijzigingen wordt in één handeling bevroren. Elke
     * mutatie draagt daarna haar eigen {@code decided_by}/{@code decided_from_status} op naam van de
     * bevriezer, het register draagt twee regels, de tien tellers staan vast en de hash is er.
     */
    @Test
    void freezingBulkApprovesEveryPlannedMutationAndClosesTheBundle() {
        Scenario scenario = updateScenario("HAPPY");
        List<ImportMutation> before = contentMutations(scenario.batchId());
        assertThat(before).hasSize(5);
        assertThat(before).allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(MutationStatus.PLANNED));
        Map<Long, Map<String, Object>> financialBefore = financialSnapshots(before);

        BundleFreezeView view = freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);

        assertThat(view.status()).isEqualTo("FROZEN");
        assertThat(view.autoApprovedCount()).isEqualTo(5L);
        assertThat(view.autoApproveDecisionId()).isNotNull();
        assertThat(view.contentHash()).hasSize(64);

        // Elke mutatie: goedgekeurd, met haar eigen audit op naam van de bevriezer.
        for (ImportMutation candidate : before) {
            ImportMutation stored = mutations.findById(candidate.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
            assertThat(stored.getDecidedBy()).isEqualTo(FREEZER);
            assertThat(stored.getDecidedAt()).isNotNull();
            assertThat(stored.getDecidedFromStatus()).isEqualTo(MutationStatus.PLANNED);
            assertThat(stored.getDecisionId()).isEqualTo(view.autoApproveDecisionId());
            // Financieel byte-identiek: bevriezen beslist, het rekent niet (AGENT.md par. 2 principe 8).
            assertThat(financialSnapshot(stored.getId())).isEqualTo(financialBefore.get(stored.getId()));
        }
        // De IMPORT_MARKER is geen voorstel en wordt niet mee goedgekeurd.
        ImportMutation marker = mutations.findById(markerOf(scenario.batchId())).orElseThrow();
        assertThat(marker.getStatus()).isEqualTo(MutationStatus.RECORDED);
        assertThat(marker.getDecisionId()).isNull();

        // Twee regels: de bulkgoedkeuring en de handeling bevriezen zelf.
        List<DecisionRow> register = queries.getBundleDecisions(scenario.bundleId(), 0, 50).content();
        assertThat(register).hasSize(2);
        assertThat(register).anySatisfy(row -> {
            assertThat(row.decisionKind()).isEqualTo("AUTO_APPROVE_PLANNED");
            assertThat(row.decisionScope()).isEqualTo("BUNDLE");
            assertThat(row.mutationId()).isNull();
            assertThat(row.affectedCount()).isEqualTo(5L);
            assertThat(row.previousStatus()).isEqualTo("PLANNED");
            assertThat(row.newStatus()).isEqualTo("READY_FOR_PUBLICATION");
            assertThat(row.decidedBy()).isEqualTo(FREEZER);
            assertThat(row.reason()).isEqualTo(FREEZE_REASON);
        });
        assertThat(register).anySatisfy(row -> {
            assertThat(row.decisionKind()).isEqualTo("FREEZE");
            assertThat(row.decisionScope()).isEqualTo("BUNDLE");
            assertThat(row.affectedCount()).isEqualTo(1L);
            assertThat(row.previousStatus()).isEqualTo("ASSEMBLING");
            assertThat(row.newStatus()).isEqualTo("FROZEN");
            assertThat(row.decidedBy()).isEqualTo(FREEZER);
            assertThat(row.reason()).isEqualTo(FREEZE_REASON);
        });

        // De bundelrij zelf: status, audit, hash.
        PublicationBundle frozen = bundles.findById(scenario.bundleId()).orElseThrow();
        assertThat(frozen.getStatus()).isEqualTo(PublicationBundleStatus.FROZEN);
        assertThat(frozen.getFrozenBy()).isEqualTo(FREEZER);
        assertThat(frozen.getFrozenAt()).isNotNull();
        assertThat(frozen.getFrozenReason()).isEqualTo(FREEZE_REASON);
        assertThat(frozen.getContentHash()).hasSize(32);

        // De tien tellers komen nu van de rij, niet meer live.
        ImportBatch batch = batches.findById(scenario.batchId()).orElseThrow();
        BundleDetail detail = queries.getBundle(scenario.bundleId());
        assertThat(detail.status()).isEqualTo("FROZEN");
        assertThat(detail.batchCount()).isEqualTo(1L);
        assertThat(detail.contentMutationCount()).isEqualTo(5L);
        assertThat(detail.readyCount()).isEqualTo(5L);
        assertThat(detail.rejectedCount()).isZero();
        assertThat(detail.blockedCount()).isZero();
        assertThat(detail.expiredCount()).isZero();
        assertThat(detail.identityIncidentCount()).isZero();
        // De drie laatste komen uit de screeningtellers van de leden; null zodra de batch ze niet
        // vastgesteld heeft (nooit stil 0).
        assertThat(detail.bulkIncidentCount()).isEqualTo(batch.getBulkIncidentCount());
        assertThat(detail.criticalIssueCount()).isEqualTo(batch.getCriticalIssueCount());
        assertThat(detail.warningCount()).isEqualTo(batch.getWarningCount());
        assertThat(detail.contentHash()).isEqualTo(view.contentHash());
        // De baselinecontrole is bij het bevriezen blokkerend uitgevoerd en daarna niet meer van toepassing.
        assertThat(detail.staleMutationCount()).isNull();
    }

    /** Een bundel zonder één enkele geplande mutatie levert géén lege bulkgoedkeuringsregel op. */
    @Test
    void freezingABundleWithNothingLeftToApproveWritesOnlyTheFreezeDecision() {
        Scenario scenario = creationScenario("NOPLANNED", 3);
        decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER, "Eerste levering nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null));

        BundleFreezeView view = freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);

        assertThat(view.autoApprovedCount()).isZero();
        assertThat(view.autoApproveDecisionId()).isNull();
        List<DecisionRow> register = queries.getBundleDecisions(scenario.bundleId(), 0, 50).content();
        // De groepsactie van hierboven plus de FREEZE-regel; geen lege AUTO_APPROVE_PLANNED ertussen.
        assertThat(register).hasSize(2);
        assertThat(register).noneSatisfy(row -> assertThat(row.decisionKind()).isEqualTo("AUTO_APPROVE_PLANNED"));
        // De beslissers blijven uit elkaar te houden: de goedkeurder is niet de bevriezer.
        assertThat(contentMutations(scenario.batchId())).allSatisfy(m ->
                assertThat(m.getDecidedBy()).isEqualTo(DECIDER));
    }

    // --- (b) Ontbrekende gegevens en een lege bundel -----------------------------------------------

    @Test
    void freezingRefusesAMissingActorOrReason() {
        Scenario scenario = updateScenario("BADINPUT");

        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), null, FREEZE_REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), "   ", FREEZE_REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), "system", FREEZE_REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), FREEZER, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), FREEZER, "  "))
                .isInstanceOf(IllegalArgumentException.class);

        assertNothingHappened(scenario);
    }

    @Test
    void anEmptyBundleCannotBeFrozen() {
        BundleReference bundle = bundleService.createBundle("BND-EMPTY-" + SEQUENCE.incrementAndGet(), null,
                PublicationTargetMode.SIMULATION, null, null, CREATOR);

        assertThatThrownBy(() -> freezeService.freeze(bundle.id(), FREEZER, FREEZE_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_BUNDLE_EMPTY);

        assertThat(bundles.findById(bundle.id()).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.ASSEMBLING);
        assertThat(decisionCount(bundle.id())).isZero();
    }

    /** Een bundel waarvan het enige lidmaatschap verwijderd is, is even leeg als een nieuwe bundel. */
    @Test
    void aBundleWhoseOnlyMembershipWasRemovedIsEmptyAgain() {
        Scenario scenario = updateScenario("REMOVED");
        bundleService.removeBatch(scenario.bundleId(), scenario.batchId(), CREATOR, "Toch niet meenemen");

        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_BUNDLE_EMPTY);
    }

    @Test
    void anUnknownBundleIsNotFound() {
        assertThatThrownBy(() -> freezeService.freeze(9_999_999L, FREEZER, FREEZE_REASON))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_BUNDLE_NOT_FOUND);
    }

    // --- (c) Een bevroren bundel is dicht ----------------------------------------------------------

    /**
     * Na het bevriezen is elke wijziging aan de bundel een conflict — ook de tweede bevriezing zelf.
     * Bevriezen is dus <b>niet</b> idempotent: een tweede geslaagde poging zou een tweede
     * goedkeuringsmoment suggereren dat nooit heeft plaatsgevonden.
     */
    @Test
    void aFrozenBundleRefusesASecondFreezeAndEveryFurtherChange() {
        Scenario scenario = updateScenario("CLOSED");
        long mutationId = contentMutations(scenario.batchId()).get(0).getId();
        long otherBatch = screenedBatch(scenario.fixture(), "REF-EXTRA", rows(3, 200));
        freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);
        long decisionsAfterFreeze = decisionCount(scenario.bundleId());

        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), FREEZER, "Nog eens"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_BUNDLE_NOT_ASSEMBLING);
        assertThatThrownBy(() -> bundleService.addBatches(scenario.bundleId(), List.of(otherBatch), CREATOR))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationBundleService.CODE_BUNDLE_NOT_ASSEMBLING);
        assertThatThrownBy(() -> bundleService.removeBatch(scenario.bundleId(), scenario.batchId(), CREATOR,
                "Toch niet"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationBundleService.CODE_BUNDLE_NOT_ASSEMBLING);
        assertThatThrownBy(() -> decisions.approve(scenario.bundleId(), mutationId, DECIDER, "Alsnog"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_BUNDLE_NOT_ASSEMBLING);
        assertThatThrownBy(() -> decisions.reject(scenario.bundleId(), mutationId, DECIDER, "Toch niet"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_BUNDLE_NOT_ASSEMBLING);
        assertThatThrownBy(() -> decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.REJECT, DECIDER,
                "Toch niet", new DecisionFilter(scenario.batchId(), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_BUNDLE_NOT_ASSEMBLING);

        // Geen van die geweigerde pogingen heeft iets geschreven.
        assertThat(decisionCount(scenario.bundleId())).isEqualTo(decisionsAfterFreeze);
        assertThat(contentMutations(scenario.batchId())).allSatisfy(m -> {
            assertThat(m.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
            assertThat(m.getDecidedBy()).isEqualTo(FREEZER);
        });
    }

    // --- (d) Eén openstaande beoordeling blokkeert alles -------------------------------------------

    /**
     * R-FRZ-02. Eén mutatie die nog op een mens wacht, houdt de hele bevriezing tegen — en de weigering
     * laat niets half afgewerkts achter.
     */
    @Test
    void oneAwaitingApprovalMutationBlocksTheFreezeAndChangesNothing() {
        Scenario scenario = creationScenario("UNDECIDED", 5);
        List<ImportMutation> content = contentMutations(scenario.batchId());
        long stillWaiting = content.get(4).getId();
        // Vier van de vijf beslist; de vijfde blijft wachten.
        for (int index = 0; index < 4; index++) {
            decisions.approve(scenario.bundleId(), content.get(index).getId(), DECIDER, "Nagekeken");
        }
        long decisionsBefore = decisionCount(scenario.bundleId());

        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_BUNDLE_HAS_UNDECIDED_MUTATIONS)
                .hasMessageContaining("1 mutation");

        PublicationBundle bundle = bundles.findById(scenario.bundleId()).orElseThrow();
        assertThat(bundle.getStatus()).isEqualTo(PublicationBundleStatus.ASSEMBLING);
        assertThat(bundle.getFrozenBy()).isNull();
        assertThat(bundle.getContentHash()).isNull();
        assertThat(bundle.getBatchCount()).isNull();
        assertThat(bundle.getReadyCount()).isNull();
        assertThat(decisionCount(scenario.bundleId())).isEqualTo(decisionsBefore);
        ImportMutation waiting = mutations.findById(stillWaiting).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL);
        assertThat(waiting.getDecisionId()).isNull();
        assertThat(waiting.getDecidedBy()).isNull();

        // Zodra ook die ene beslist is, lukt het wel.
        decisions.reject(scenario.bundleId(), stillWaiting, DECIDER, "Referentie bestaat niet");
        BundleFreezeView view = freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);
        assertThat(view.status()).isEqualTo("FROZEN");
        BundleDetail detail = queries.getBundle(scenario.bundleId());
        assertThat(detail.readyCount()).isEqualTo(4L);
        assertThat(detail.rejectedCount()).isEqualTo(1L);
        assertThat(detail.contentMutationCount()).isEqualTo(5L);
    }

    // --- (e) Vastgehouden mutaties beletten het bevriezen niet -------------------------------------

    /**
     * Ontwerp par. 3.6 en beslissingslog 22/09, keuze 3: een {@code BLOCKED} mutatie en een
     * {@code IDENTITY_REFERENCE_INCIDENT} krijgen in Fase 4 geen beslispad. Ze houden het bevriezen dus
     * niet tegen, blijven exact staan zoals ze stonden, en worden door Fase 5 niet gepubliceerd.
     */
    @Test
    void blockedMutationsAndIdentityIncidentsDoNotBlockTheFreezeAndKeepTheirStatus() {
        Scenario scenario = creationScenario("HELDBACK", 5);
        List<ImportMutation> content = contentMutations(scenario.batchId());
        long blocked = content.get(0).getId();
        long incident = content.get(1).getId();
        // Zoals pass E5/3f ze bij een vastgehouden regel zou schrijven; 4e bouwt die controle niet na.
        jdbc.update("update import_mutation set status = 'BLOCKED', status_reason = ? where id = ?",
                "IDENTITY_REFERENCE_INCIDENT", blocked);
        jdbc.update("update import_mutation set action_type = 'IDENTITY_REFERENCE_INCIDENT', "
                + "reference_type = 'EAN', after_reference_value = '5410000000001' where id = ?", incident);
        // De drie gewone mutaties worden beslist; de twee vastgehouden kunnen dat niet en hoeven dat niet.
        decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER, "Rest nagekeken",
                new DecisionFilter(scenario.batchId(), null, null, null));

        BundleFreezeView view = freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);

        assertThat(view.status()).isEqualTo("FROZEN");
        assertThat(view.autoApprovedCount()).isZero();
        ImportMutation storedBlocked = mutations.findById(blocked).orElseThrow();
        assertThat(storedBlocked.getStatus()).isEqualTo(MutationStatus.BLOCKED);
        assertThat(storedBlocked.getDecisionId()).isNull();
        assertThat(storedBlocked.getDecidedBy()).isNull();
        assertThat(storedBlocked.getStatusReason()).isEqualTo("IDENTITY_REFERENCE_INCIDENT");
        ImportMutation storedIncident = mutations.findById(incident).orElseThrow();
        assertThat(storedIncident.getActionType()).isEqualTo(MutationActionType.IDENTITY_REFERENCE_INCIDENT);
        assertThat(storedIncident.getStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL);
        assertThat(storedIncident.getDecisionId()).isNull();

        BundleDetail detail = queries.getBundle(scenario.bundleId());
        assertThat(detail.blockedCount()).isEqualTo(1L);
        assertThat(detail.identityIncidentCount()).isEqualTo(1L);
        // De incidentmutatie telt niet als inhoudelijke mutatie: die vier zijn CREATE/UPDATE.
        assertThat(detail.contentMutationCount()).isEqualTo(4L);
        assertThat(detail.readyCount()).isEqualTo(3L);
    }

    // --- (f) De bundelhash -------------------------------------------------------------------------

    /**
     * De hash is deterministisch over dezelfde rijen en verandert bij elke beslissing die de inhoud van
     * die rijen wijzigt.
     * <p>
     * Bewust géén vergelijking tussen twee onafhankelijk aangemaakte bundels met "dezelfde" inhoud: de
     * mutatie-id en de beslissings-id maken deel uit van de serialisatie (zie
     * {@code PublicationBundleDao.computeContentHash}), dus twee bundels verschillen altijd. Dat is de
     * bedoeling — deze hash bewijst dat <b>deze</b> rijen onveranderd zijn, niet dat er elders
     * gelijkaardige bestaan. Determinisme wordt daarom bewezen op dezelfde bundel.
     */
    @Test
    void theContentHashIsDeterministicAndFollowsEveryDecision() {
        Scenario scenario = updateScenario("HASH");

        byte[] first = dao.computeContentHash(scenario.bundleId());
        byte[] second = dao.computeContentHash(scenario.bundleId());
        assertThat(second).isEqualTo(first);
        assertThat(first).hasSize(32);

        // Eén afkeuring wijzigt status en decision_id van één rij: de hash moet mee wijzigen.
        long rejected = contentMutations(scenario.batchId()).get(0).getId();
        decisions.reject(scenario.bundleId(), rejected, DECIDER, "Prijs niet bevestigd door de leverancier");
        byte[] afterRejection = dao.computeContentHash(scenario.bundleId());
        assertThat(afterRejection).isNotEqualTo(first);
        assertThat(dao.computeContentHash(scenario.bundleId())).isEqualTo(afterRejection);

        // De bulkgoedkeuring bij het bevriezen wijzigt de overige vier: de bewaarde hash is die van ná
        // het bevriezen, niet die van ervoor.
        BundleFreezeView view = freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);
        byte[] stored = bundles.findById(scenario.bundleId()).orElseThrow().getContentHash();
        assertThat(stored).isNotEqualTo(afterRejection);
        assertThat(stored).isEqualTo(dao.computeContentHash(scenario.bundleId()));
        assertThat(view.contentHash()).isEqualTo(java.util.HexFormat.of().formatHex(stored));
    }

    /**
     * Twee bundels met dezelfde leveringen maar een andere afkeuring hebben een verschillende hash. Dat
     * is met de gekozen serialisatie vanzelfsprekend (de id's verschillen al), maar het is wél de
     * eigenschap waarop Fase 5 zal steunen: geen twee goedkeuringsdossiers delen een hash.
     */
    @Test
    void twoBundlesWithADifferentRejectionHaveADifferentHash() {
        Scenario left = updateScenario("HASHA");
        Scenario right = updateScenario("HASHB");
        decisions.reject(left.bundleId(), contentMutations(left.batchId()).get(0).getId(), DECIDER, "Niet ok");
        decisions.reject(right.bundleId(), contentMutations(right.batchId()).get(1).getId(), DECIDER, "Niet ok");

        freezeService.freeze(left.bundleId(), FREEZER, FREEZE_REASON);
        freezeService.freeze(right.bundleId(), FREEZER, FREEZE_REASON);

        byte[] leftHash = bundles.findById(left.bundleId()).orElseThrow().getContentHash();
        byte[] rightHash = bundles.findById(right.bundleId()).orElseThrow().getContentHash();
        assertThat(leftHash).isNotEqualTo(rightHash);
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    /** Een bundel met één batch vol wachtende creaties (eerste levering van de koppeling). */
    private Scenario creationScenario(String prefix, int rowCount) {
        Fixture f = fixture(prefix);
        long batchId = screenedBatch(f, "REF-1", rows(rowCount, 100));
        return bundleWith(f, batchId);
    }

    /**
     * Een bundel met één batch vol geplande prijswijzigingen: eerste levering aanvaard als nulmeting,
     * tweede levering met dezelfde identiteiten en andere prijzen.
     */
    private Scenario updateScenario(String prefix) {
        Fixture f = fixture(prefix);
        long first = screenedBatch(f, "REF-1", rows(5, 100));
        baseline.acceptBaseline(first, CREATOR, "Nulmeting");
        long second = screenedBatch(f, "REF-2", rows(5, 125));
        return bundleWith(f, second);
    }

    private Scenario bundleWith(Fixture f, long batchId) {
        BundleReference bundle = bundleService.createBundle("BND-" + f.unique(), null,
                PublicationTargetMode.SIMULATION, null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);
        return new Scenario(bundle.id(), batchId, f);
    }

    private record Scenario(long bundleId, long batchId, Fixture fixture) {
    }

    /** Niets geschreven: geen beslissingsregel, geen bevroren rij, geen enkele mutatie verschoven. */
    private void assertNothingHappened(Scenario scenario) {
        PublicationBundle bundle = bundles.findById(scenario.bundleId()).orElseThrow();
        assertThat(bundle.getStatus()).isEqualTo(PublicationBundleStatus.ASSEMBLING);
        assertThat(bundle.getFrozenBy()).isNull();
        assertThat(bundle.getContentHash()).isNull();
        assertThat(decisionCount(scenario.bundleId())).isZero();
        assertThat(contentMutations(scenario.batchId())).allSatisfy(m -> {
            assertThat(m.getDecisionId()).isNull();
            assertThat(m.getDecidedBy()).isNull();
        });
    }

    private List<ImportMutation> mutationsOf(long batchId) {
        return mutations.findByBatchId(batchId, PageRequest.of(0, 200)).getContent();
    }

    private List<ImportMutation> contentMutations(long batchId) {
        return mutationsOf(batchId).stream()
                .filter(m -> m.getActionType() == MutationActionType.CREATE
                        || m.getActionType() == MutationActionType.UPDATE)
                .toList();
    }

    private long markerOf(long batchId) {
        return mutationsOf(batchId).stream()
                .filter(m -> m.getActionType() == MutationActionType.IMPORT_MARKER)
                .findFirst().orElseThrow().getId();
    }

    /** Het aantal beslissingsregels van déze bundel; de H2-database is gedeeld met andere tests. */
    private long decisionCount(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) from publication_decision where bundle_id = ?",
                Long.class, bundleId);
        return count == null ? 0L : count;
    }

    private Map<Long, Map<String, Object>> financialSnapshots(List<ImportMutation> content) {
        return content.stream().collect(java.util.stream.Collectors.toMap(ImportMutation::getId,
                m -> financialSnapshot(m.getId())));
    }

    /** Alles wat een bevriezing <b>niet</b> mag aanraken, in één keer vergelijkbaar. */
    private Map<String, Object> financialSnapshot(long mutationId) {
        return jdbc.queryForMap("select before_base_price, after_base_price, base_price_currency, domain_mask, "
                + "status_reason, source_row_number, delivery_file_id, source_state_id, identity_supplier, "
                + "identity_supplier_group, identity_supplier_reference, idempotency_key, created_at "
                + "from import_mutation where id = ?", mutationId);
    }

    /** {@code count} regels met oplopende referenties en een prijs afgeleid van {@code priceCents}. */
    private String[] rows(int count, int priceCents) {
        String[] rows = new String[count];
        for (int index = 0; index < count; index++) {
            int cents = priceCents + index * 25;
            rows[index] = "ACME;G1;R" + (index + 1) + ";" + (cents / 100) + "," + String.format("%02d", cents % 100)
                    + ";Artikel " + (index + 1);
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
        String unique = "BFZ" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        // bestaande fase 4-tests.
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
