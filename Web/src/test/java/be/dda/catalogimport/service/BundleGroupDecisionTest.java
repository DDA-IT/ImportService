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
import be.dda.catalogimport.domain.BundleDecisionKind;
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
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.BundleDecisionService.GroupDecisionView;
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
 * Bouwstap 4d (docs/design/fase4-publication-bundle-design.md par. 1 R-DEC, par. 3 "Groepsactie",
 * par. 6 stap 4d): het in één handeling goedkeuren of afkeuren van een gefilterde selectie mutaties
 * binnen een Publicatiebundel, tegen de echte services, DAO's en H2.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Eén groepsactie raakt N mutaties met <b>één</b> beslissingsregel ({@code decision_scope=GROUP},
 *       {@code affected_count}=N), terwijl elke geraakte mutatie tóch haar eigen
 *       {@code decided_by}/{@code decided_at}/{@code decided_from_status} draagt — ook wanneer
 *       {@code PLANNED} en {@code AWAITING_APPROVAL} door elkaar in dezelfde aanroep zitten.</li>
 *   <li>De harde {@code where}-staart is niet te omzeilen: een {@code BLOCKED} mutatie, een
 *       identiteitsincident, de {@code IMPORT_MARKER} en een al individueel besliste mutatie blijven
 *       aantoonbaar onaangeroerd, ook bij de breedst mogelijke filter.</li>
 *   <li>Herhaling is veilig: dezelfde groepsactie een tweede keer raakt 0 rijen en schrijft géén
 *       tweede beslissingsregel.</li>
 *   <li>Een lege filter wordt geweigerd (400 {@code DECISION_FILTER_REQUIRED}) vóór er iets geschreven
 *       is — {@code {}} mag nooit per ongeluk een hele bundel goedkeuren.</li>
 *   <li><b>Financiële onveranderlijkheid</b> (AGENT.md par. 2 principe 8) geldt ook op de groepsroute:
 *       prijsvelden, {@code domain_mask}, {@code status_reason} en de vingerafdrukken zijn vóór en na
 *       byte-identiek.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class BundleGroupDecisionTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String DECIDER = "an.janssens@example.test";
    private static final String OTHER_DECIDER = "piet.willems@example.test";
    private static final String BULK_PRICE_INCIDENT = "BULK_PRICE_INCIDENT";
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

    // --- (a) Vijfentwintig wachtende mutaties, één groepsactie ------------------------------------

    @Test
    void oneGroupApprovalDecidesEveryMatchingMutationWithItsOwnAuditAndOneDecisionRow() {
        Scenario scenario = creationScenario("BULK25", 25);
        List<ImportMutation> content = contentMutations(scenario.batchId());
        assertThat(content).hasSize(25);
        assertThat(content).allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL));

        GroupDecisionView view = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                "Eerste levering integraal nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null));

        assertThat(view.affectedCount()).isEqualTo(25L);
        assertThat(view.decisionId()).isNotNull();
        assertThat(view.selectionFilter()).isEqualTo("status=AWAITING_APPROVAL");

        // Elke geraakte mutatie draagt haar eigen audit en wijst naar dezelfde ene beslissingsregel.
        for (ImportMutation before : content) {
            ImportMutation stored = mutations.findById(before.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
            assertThat(stored.getDecidedBy()).isEqualTo(DECIDER);
            assertThat(stored.getDecidedAt()).isNotNull();
            assertThat(stored.getDecidedFromStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL);
            assertThat(stored.getDecisionId()).isEqualTo(view.decisionId());
        }

        List<DecisionRow> register = queries.getBundleDecisions(scenario.bundleId(), 0, 50).content();
        assertThat(register).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(view.decisionId());
            assertThat(row.decisionKind()).isEqualTo("APPROVE");
            assertThat(row.decisionScope()).isEqualTo("GROUP");
            assertThat(row.mutationId()).isNull();
            assertThat(row.affectedCount()).isEqualTo(25L);
            assertThat(row.selectionFilter()).isEqualTo("status=AWAITING_APPROVAL");
            assertThat(row.previousStatus()).isEqualTo("AWAITING_APPROVAL");
            assertThat(row.newStatus()).isEqualTo("READY_FOR_PUBLICATION");
            assertThat(row.decidedBy()).isEqualTo(DECIDER);
            assertThat(row.reason()).isEqualTo("Eerste levering integraal nagekeken");
        });
        // De IMPORT_MARKER van de screening is niet meegegaan.
        assertThat(statusOf(markerOf(scenario.batchId()))).isEqualTo(MutationStatus.RECORDED);
    }

    /** Afkeuren in groep vereist een reden, net als individueel (R-DEC). */
    @Test
    void aGroupRejectionRequiresAReasonAndThenRejectsEveryMatchingMutation() {
        Scenario scenario = updateScenario("GROUPREJECT");
        DecisionFilter filter = new DecisionFilter(scenario.batchId(), null, null, null);

        assertThatThrownBy(() -> decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.REJECT, DECIDER,
                null, filter)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.REJECT, DECIDER,
                "   ", filter)).isInstanceOf(IllegalArgumentException.class);
        assertThat(decisionCount(scenario.bundleId())).isZero();
        assertThat(contentMutations(scenario.batchId()))
                .allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(MutationStatus.PLANNED));

        GroupDecisionView view = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.REJECT, DECIDER,
                "Leverancier trekt de prijslijst in", filter);

        assertThat(view.affectedCount()).isEqualTo(5L);
        assertThat(contentMutations(scenario.batchId())).allSatisfy(m -> {
            assertThat(m.getStatus()).isEqualTo(MutationStatus.REJECTED);
            assertThat(m.getDecidedFromStatus()).isEqualTo(MutationStatus.PLANNED);
            assertThat(m.getDecisionId()).isEqualTo(view.decisionId());
        });
        assertThat(queries.getBundleDecisions(scenario.bundleId(), 0, 50).content()).singleElement()
                .satisfies(row -> {
                    assertThat(row.decisionKind()).isEqualTo("REJECT");
                    assertThat(row.selectionFilter()).isEqualTo("batchId=" + scenario.batchId());
                    // Geen bronstatus op de groepsregel: de filter pinde er geen vast.
                    assertThat(row.previousStatus()).isNull();
                });
    }

    /** Zonder reden is een goedkeuring toegestaan; de regel draagt dan dezelfde vaste tekst als 4c. */
    @Test
    void aGroupApprovalWithoutAReasonRecordsTheDefaultReason() {
        Scenario scenario = creationScenario("GROUPNOREASON", 5);

        GroupDecisionView view = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                null, new DecisionFilter(scenario.batchId(), null, null, null));

        assertThat(view.affectedCount()).isEqualTo(5L);
        assertThat(queries.getBundleDecisions(scenario.bundleId(), 0, 50).content()).singleElement()
                .satisfies(row -> assertThat(row.reason())
                        .isEqualTo(BundleDecisionService.DEFAULT_APPROVAL_REASON));
    }

    // --- (b) Herhaling raakt niets meer -----------------------------------------------------------

    @Test
    void repeatingTheSameGroupDecisionAffectsNothingAndWritesNoSecondDecision() {
        Scenario scenario = creationScenario("REPEAT", 5);
        DecisionFilter filter = new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null);
        GroupDecisionView first = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                "Akkoord", filter);
        assertThat(first.affectedCount()).isEqualTo(5L);
        long decisionsAfterFirst = decisionCount(scenario.bundleId());
        assertThat(decisionsAfterFirst).isEqualTo(1L);

        GroupDecisionView second = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                "Akkoord", filter);

        assertThat(second.affectedCount()).isZero();
        assertThat(second.decisionId()).isNull();
        // Geen lege regel in het append-only register: het aantal rijen is onveranderd.
        assertThat(decisionCount(scenario.bundleId())).isEqualTo(decisionsAfterFirst);
        assertThat(contentMutations(scenario.batchId())).allSatisfy(m ->
                assertThat(m.getDecisionId()).isEqualTo(first.decisionId()));

        // Ook een tegengestelde groepsactie raakt ze niet meer: een herziening blijft individueel.
        GroupDecisionView opposite = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.REJECT,
                OTHER_DECIDER, "Toch niet", new DecisionFilter(scenario.batchId(), null, null, null));
        assertThat(opposite.affectedCount()).isZero();
        assertThat(opposite.decisionId()).isNull();
        assertThat(decisionCount(scenario.bundleId())).isEqualTo(decisionsAfterFirst);
        assertThat(contentMutations(scenario.batchId())).allSatisfy(m ->
                assertThat(m.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION));
    }

    // --- (c) Filteren op de reden waarom een mutatie wachtte ---------------------------------------

    @Test
    void filteringOnStatusReasonTouchesExactlyThatSubset() {
        Scenario scenario = updateScenario("REASON");
        // Pass E5b (R-PRI-14): elke geplande prijswijziging wacht door een bulkprijsincident.
        assertThat(mutationDao.holdPlannedPriceUpdates(scenario.batchId(), BULK_PRICE_INCIDENT)).isEqualTo(5);
        List<ImportMutation> content = contentMutations(scenario.batchId());
        long untouchedOne = content.get(0).getId();
        long untouchedTwo = content.get(1).getId();
        jdbc.update("update import_mutation set status_reason = 'OTHER_HOLD_REASON' where id in (?, ?)",
                untouchedOne, untouchedTwo);

        GroupDecisionView view = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                "Prijsstijging bevestigd door de leverancier",
                new DecisionFilter(null, null, BULK_PRICE_INCIDENT, null));

        assertThat(view.affectedCount()).isEqualTo(3L);
        assertThat(view.selectionFilter()).isEqualTo("statusReason=BULK_PRICE_INCIDENT");
        for (long untouched : List.of(untouchedOne, untouchedTwo)) {
            ImportMutation stored = mutations.findById(untouched).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL);
            assertThat(stored.getDecisionId()).isNull();
            assertThat(stored.getDecidedBy()).isNull();
            assertThat(stored.getStatusReason()).isEqualTo("OTHER_HOLD_REASON");
        }
        assertThat(content.stream().skip(2).toList()).allSatisfy(before -> {
            ImportMutation stored = mutations.findById(before.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
            assertThat(stored.getDecisionId()).isEqualTo(view.decisionId());
            // De reden waaróm ze wachtte blijft staan: dat is waarvoor getekend is.
            assertThat(stored.getStatusReason()).isEqualTo(BULK_PRICE_INCIDENT);
        });
    }

    /** Een filterwaarde die de harde staart nooit kan raken, wordt geweigerd in plaats van stil 0. */
    @Test
    void aFilterValueOutsideTheHardTailIsRefused() {
        Scenario scenario = creationScenario("FILTERVALUE", 5);

        assertThatThrownBy(() -> decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                null, new DecisionFilter(null, MutationStatus.READY_FOR_PUBLICATION, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                null, new DecisionFilter(null, null, null, MutationActionType.IMPORT_MARKER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.FREEZE, DECIDER,
                "Bevriezen", new DecisionFilter(scenario.batchId(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, "system",
                null, new DecisionFilter(scenario.batchId(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(decisionCount(scenario.bundleId())).isZero();
        assertThat(contentMutations(scenario.batchId()))
                .allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL));
    }

    // --- (d) Wat een groepsactie nooit raakt -------------------------------------------------------

    /**
     * Het kernbewijs van de harde {@code where}-staart (ontwerp par. 3 "Groepsactie"): met de breedst
     * mogelijke filter (enkel de batch) blijven een {@code BLOCKED} mutatie, een identiteitsincident,
     * de {@code IMPORT_MARKER} en een al individueel besliste mutatie exact staan zoals ze stonden.
     */
    @Test
    void aGroupDecisionNeverTouchesBlockedIncidentMarkerOrAlreadyDecidedMutations() {
        Scenario scenario = creationScenario("HARDTAIL", 5);
        List<ImportMutation> content = contentMutations(scenario.batchId());
        long blocked = content.get(0).getId();
        long incident = content.get(1).getId();
        long alreadyDecided = content.get(2).getId();
        long marker = markerOf(scenario.batchId());

        // Zoals pass E5 ze bij een vastgehouden regel zou schrijven (MutationDao); 4d bouwt de
        // referentiecontrole niet na.
        jdbc.update("update import_mutation set status = 'BLOCKED', status_reason = ? where id = ?",
                "IDENTITY_REFERENCE_INCIDENT", blocked);
        jdbc.update("update import_mutation set action_type = 'IDENTITY_REFERENCE_INCIDENT', "
                + "reference_type = 'EAN', after_reference_value = '5410000000001' where id = ?", incident);
        long individualDecision = decisions.approve(scenario.bundleId(), alreadyDecided, OTHER_DECIDER,
                "Individueel nagekeken").decision().id();

        GroupDecisionView view = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.REJECT, DECIDER,
                "Rest van de levering afgekeurd", new DecisionFilter(scenario.batchId(), null, null, null));

        // Enkel de twee overblijvende inhoudelijke mutaties.
        assertThat(view.affectedCount()).isEqualTo(2L);
        assertThat(content.stream().skip(3).toList()).allSatisfy(before -> {
            ImportMutation stored = mutations.findById(before.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(MutationStatus.REJECTED);
            assertThat(stored.getDecidedFromStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL);
            assertThat(stored.getDecisionId()).isEqualTo(view.decisionId());
        });

        ImportMutation storedBlocked = mutations.findById(blocked).orElseThrow();
        assertThat(storedBlocked.getStatus()).isEqualTo(MutationStatus.BLOCKED);
        assertThat(storedBlocked.getDecisionId()).isNull();
        assertThat(storedBlocked.getDecidedBy()).isNull();
        assertThat(storedBlocked.getStatusReason()).isEqualTo("IDENTITY_REFERENCE_INCIDENT");

        ImportMutation storedIncident = mutations.findById(incident).orElseThrow();
        assertThat(storedIncident.getActionType()).isEqualTo(MutationActionType.IDENTITY_REFERENCE_INCIDENT);
        assertThat(storedIncident.getStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL);
        assertThat(storedIncident.getDecisionId()).isNull();
        assertThat(storedIncident.getDecidedBy()).isNull();

        ImportMutation storedMarker = mutations.findById(marker).orElseThrow();
        assertThat(storedMarker.getStatus()).isEqualTo(MutationStatus.RECORDED);
        assertThat(storedMarker.getDecisionId()).isNull();
        assertThat(storedMarker.getDecidedBy()).isNull();

        // De al ondertekende beslissing blijft staan, met haar eigen beslisser en haar eigen regel.
        ImportMutation storedDecided = mutations.findById(alreadyDecided).orElseThrow();
        assertThat(storedDecided.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        assertThat(storedDecided.getDecidedBy()).isEqualTo(OTHER_DECIDER);
        assertThat(storedDecided.getDecisionId()).isEqualTo(individualDecision);
        assertThat(storedDecided.getDecisionId()).isNotEqualTo(view.decisionId());
    }

    /** Een batch zonder actief lidmaatschap valt buiten elke groepsactie van deze bundel. */
    @Test
    void mutationsOfARemovedBatchAreOutOfReach() {
        Scenario scenario = creationScenario("REMOVED", 5);
        bundleService.removeBatch(scenario.bundleId(), scenario.batchId(), CREATOR, "Toch niet meenemen");

        GroupDecisionView view = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                "Alles goedkeuren", new DecisionFilter(scenario.batchId(), null, null, null));

        assertThat(view.affectedCount()).isZero();
        assertThat(view.decisionId()).isNull();
        assertThat(contentMutations(scenario.batchId())).allSatisfy(m -> {
            assertThat(m.getStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL);
            assertThat(m.getDecisionId()).isNull();
        });
    }

    // --- (e) Een lege filter wordt geweigerd -------------------------------------------------------

    @Test
    void anEmptyFilterIsRefusedAndWritesNothing() {
        Scenario scenario = creationScenario("EMPTYFILTER", 5);

        for (DecisionFilter empty : List.of(new DecisionFilter(null, null, null, null),
                new DecisionFilter(null, null, "   ", null))) {
            assertThatThrownBy(() -> decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE,
                    DECIDER, "Alles", empty))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_DECISION_FILTER_REQUIRED);
        }
        assertThatThrownBy(() -> decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                "Alles", null))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_DECISION_FILTER_REQUIRED);

        assertThat(decisionCount(scenario.bundleId())).isZero();
        assertThat(contentMutations(scenario.batchId())).allSatisfy(m -> {
            assertThat(m.getStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL);
            assertThat(m.getDecisionId()).isNull();
        });
    }

    // --- (f) PLANNED en AWAITING_APPROVAL door elkaar ----------------------------------------------

    /**
     * Eén {@code update} over twee verschillende bronstatussen: beide gaan mee, en élke rij legt haar
     * eigen {@code decided_from_status} vast. Zou dat fout gaan, dan zou de audit achteraf beweren dat
     * een wachtende creatie "gewoon gepland" was — precies het onderscheid waarvoor iemand tekent.
     */
    @Test
    void aMixOfPlannedAndAwaitingApprovalKeepsItsOwnSourceStatusPerMutation() {
        Scenario scenario = creationScenario("MIXED", 5);
        List<ImportMutation> content = contentMutations(scenario.batchId());
        long plannedOne = content.get(0).getId();
        long plannedTwo = content.get(1).getId();
        jdbc.update("update import_mutation set status = 'PLANNED' where id in (?, ?)", plannedOne, plannedTwo);

        GroupDecisionView view = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                "Volledige batch nagekeken", new DecisionFilter(scenario.batchId(), null, null, null));

        assertThat(view.affectedCount()).isEqualTo(5L);
        for (ImportMutation before : content) {
            ImportMutation stored = mutations.findById(before.getId()).orElseThrow();
            MutationStatus expectedSource = stored.getId() == plannedOne || stored.getId() == plannedTwo
                    ? MutationStatus.PLANNED : MutationStatus.AWAITING_APPROVAL;
            assertThat(stored.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
            assertThat(stored.getDecidedFromStatus()).isEqualTo(expectedSource);
            assertThat(stored.getDecisionId()).isEqualTo(view.decisionId());
            assertThat(stored.getDecidedBy()).isEqualTo(DECIDER);
        }
        // Eén regel voor beide bronstatussen, zonder previous_status: die staat per mutatie.
        assertThat(queries.getBundleDecisions(scenario.bundleId(), 0, 50).content()).singleElement()
                .satisfies(row -> {
                    assertThat(row.affectedCount()).isEqualTo(5L);
                    assertThat(row.previousStatus()).isNull();
                    assertThat(row.newStatus()).isEqualTo("READY_FOR_PUBLICATION");
                });

        // En met een filter die één bronstatus vastpint, staat die wél op de regel.
        Scenario pinned = creationScenario("MIXED-PINNED", 3);
        decisions.decideGroup(pinned.bundleId(), BundleDecisionKind.APPROVE, DECIDER, "Enkel de wachtende",
                new DecisionFilter(null, MutationStatus.PLANNED, null, null));
        // Geen enkele PLANNED-mutatie: niets geraakt, dus ook geen regel.
        assertThat(decisionCount(pinned.bundleId())).isZero();
        assertThat(contentMutations(pinned.batchId()))
                .allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL));
    }

    // --- (g) Financiële onveranderlijkheid op de groepsroute ---------------------------------------

    @Test
    void aGroupDecisionNeverTouchesFinancialFieldsOrTheStatusReason() {
        Scenario scenario = updateScenario("GROUPMONEY");
        assertThat(mutationDao.holdPlannedPriceUpdates(scenario.batchId(), BULK_PRICE_INCIDENT)).isEqualTo(5);
        List<Long> ids = contentMutations(scenario.batchId()).stream().map(ImportMutation::getId).toList();
        Map<Long, Map<String, Object>> before = new java.util.HashMap<>();
        Map<Long, byte[]> fingerprintsBefore = new java.util.HashMap<>();
        for (long id : ids) {
            before.put(id, financialSnapshot(id));
            fingerprintsBefore.put(id, hashOf(id, "after_combined_fingerprint"));
        }
        assertThat(before.values()).allSatisfy(snapshot -> {
            assertThat(snapshot.get("BEFORE_BASE_PRICE")).isNotNull();
            assertThat(snapshot.get("AFTER_BASE_PRICE")).isNotNull();
            assertThat(snapshot.get("DOMAIN_MASK")).asString().contains("PRICE");
            assertThat(snapshot.get("STATUS_REASON")).isEqualTo(BULK_PRICE_INCIDENT);
        });

        GroupDecisionView view = decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                "Prijsronde bevestigd", new DecisionFilter(null, null, BULK_PRICE_INCIDENT,
                        MutationActionType.UPDATE));

        assertThat(view.affectedCount()).isEqualTo(5L);
        assertThat(view.selectionFilter()).isEqualTo("statusReason=BULK_PRICE_INCIDENT;actionType=UPDATE");
        for (long id : ids) {
            assertThat(statusOf(id)).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
            assertThat(financialSnapshot(id)).isEqualTo(before.get(id));
            assertThat(hashOf(id, "after_combined_fingerprint")).isEqualTo(fingerprintsBefore.get(id));
        }
    }

    // --- (h) Bundelstatus en onbekende ids ---------------------------------------------------------

    @Test
    void aBundleThatIsNoLongerAssemblingRefusesGroupDecisions() {
        Scenario scenario = creationScenario("GROUPFROZEN", 5);
        PublicationBundle bundle = bundles.findById(scenario.bundleId()).orElseThrow();
        bundle.recordFreeze(CREATOR, Instant.now(), "Klaar voor publicatie", new byte[32]);
        bundle.setStatus(PublicationBundleStatus.FROZEN);
        bundles.saveAndFlush(bundle);

        assertThatThrownBy(() -> decisions.decideGroup(scenario.bundleId(), BundleDecisionKind.APPROVE, DECIDER,
                "Alsnog", new DecisionFilter(scenario.batchId(), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_BUNDLE_NOT_ASSEMBLING);
        assertThat(decisionCount(scenario.bundleId())).isZero();
        assertThat(contentMutations(scenario.batchId()))
                .allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL));
    }

    @Test
    void anUnknownBundleIsNotFound() {
        assertThatThrownBy(() -> decisions.decideGroup(UNKNOWN_ID, BundleDecisionKind.APPROVE, DECIDER, null,
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", BundleDecisionService.CODE_BUNDLE_NOT_FOUND);
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
        return new Scenario(bundle.id(), batchId);
    }

    private record Scenario(long bundleId, long batchId) {
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

    private MutationStatus statusOf(long mutationId) {
        return mutations.findById(mutationId).orElseThrow().getStatus();
    }

    /** Het aantal beslissingsregels van déze bundel; de H2-database is gedeeld met andere tests. */
    private long decisionCount(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) from publication_decision where bundle_id = ?",
                Long.class, bundleId);
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
        String unique = "BGD" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        // bestaande fase 3/4-tests (BundleMutationDecisionTest, PublicationBundleLifecycleTest).
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
