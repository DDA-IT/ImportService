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
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleBatch;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.BundleCancellationService.BundleCancelView;
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
 * Bouwstap 4f (docs/design/fase4-publication-bundle-design.md par. 1 R-FRZ-10, par. 3, par. 4, par. 6
 * stap 4f; beslissingslog 22/09 keuze 4): het annuleren van een Publicatiebundel, tegen de echte
 * services, DAO's en H2.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Annuleren mag vanuit {@code ASSEMBLING} <b>en</b> vanuit {@code FROZEN} — de enige bundelactie
 *       met twee toegestane bronstatussen.</li>
 *   <li>Elke nog niet-terminale inhoudelijke mutatie ({@code PLANNED}, {@code AWAITING_APPROVAL},
 *       {@code READY_FOR_PUBLICATION}) wordt {@code EXPIRED}; {@code REJECTED} blijft {@code REJECTED},
 *       de {@code IMPORT_MARKER} blijft {@code RECORDED} (R-FRZ-10).</li>
 *   <li>Alle actieve batchlidmaatschappen worden vrijgegeven en de batch is daarna weer normaal
 *       bruikbaar: {@code accept-baseline} werkt weer, en dezelfde batch kan aan een nieuwe bundel
 *       toegevoegd worden (R-BAS-03, R-BND-03).</li>
 *   <li>Financiële velden blijven ook bij {@code EXPIRED} byte-identiek.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class BundleCancelTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String CANCELLER = "lieve.maes@example.test";
    private static final String FREEZE_REASON = "Prijsronde september goedgekeurd";
    private static final String CANCEL_REASON = "Leverancier heeft de levering ingetrokken";

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
    private BundleCancellationService cancellationService;
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
    private JdbcTemplate jdbc;

    // --- (a) Annuleren vanuit ASSEMBLING met een gemengde inhoud -----------------------------------

    @Test
    void cancellingFromAssemblingExpiresOpenMutationsLeavesRejectedAloneAndFreesTheBatch() {
        Scenario scenario = creationScenario("ASM", 5);
        List<ImportMutation> content = contentMutations(scenario.batchId());
        long rejected = content.get(0).getId();
        decisions.reject(scenario.bundleId(), rejected, DECIDER, "Referentie niet gevonden");
        // De overige vier blijven AWAITING_APPROVAL (eerste levering van de koppeling: initialisatie).
        long decisionsBefore = decisionCount(scenario.bundleId());

        BundleCancelView view = cancellationService.cancel(scenario.bundleId(), CANCELLER, CANCEL_REASON);

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.expiredMutationCount()).isEqualTo(4L);
        assertThat(view.releasedBatchCount()).isEqualTo(1);
        assertThat(view.cancelledBy()).isEqualTo(CANCELLER);
        assertThat(view.cancelledReason()).isEqualTo(CANCEL_REASON);

        // De afgekeurde mutatie blijft precies zoals ze stond.
        ImportMutation stillRejected = mutations.findById(rejected).orElseThrow();
        assertThat(stillRejected.getStatus()).isEqualTo(MutationStatus.REJECTED);
        assertThat(stillRejected.getDecidedBy()).isEqualTo(DECIDER);

        // De vier openstaande mutaties zijn EXPIRED, met hun eigen audit op naam van de annuleerder.
        for (ImportMutation mutation : content) {
            if (mutation.getId() == rejected) {
                continue;
            }
            ImportMutation stored = mutations.findById(mutation.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(MutationStatus.EXPIRED);
            assertThat(stored.getDecidedBy()).isEqualTo(CANCELLER);
            assertThat(stored.getDecidedFromStatus()).isEqualTo(MutationStatus.AWAITING_APPROVAL);
            assertThat(stored.getDecisionId()).isNotNull();
        }
        // De IMPORT_MARKER blijft RECORDED en onaangeroerd.
        ImportMutation marker = mutations.findById(markerOf(scenario.batchId())).orElseThrow();
        assertThat(marker.getStatus()).isEqualTo(MutationStatus.RECORDED);

        // Eén nieuwe CANCEL-regel in het register.
        List<DecisionRow> register = queries.getBundleDecisions(scenario.bundleId(), 0, 50).content();
        assertThat(register).hasSize((int) decisionsBefore + 1);
        assertThat(register).anySatisfy(row -> {
            assertThat(row.decisionKind()).isEqualTo("CANCEL");
            assertThat(row.decisionScope()).isEqualTo("BUNDLE");
            assertThat(row.mutationId()).isNull();
            assertThat(row.affectedCount()).isEqualTo(4L);
            assertThat(row.previousStatus()).isEqualTo("ASSEMBLING");
            assertThat(row.newStatus()).isEqualTo("CANCELLED");
            assertThat(row.decidedBy()).isEqualTo(CANCELLER);
            assertThat(row.reason()).isEqualTo(CANCEL_REASON);
        });

        // De bundel zelf.
        PublicationBundle cancelled = bundles.findById(scenario.bundleId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(PublicationBundleStatus.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo(CANCELLER);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getCancelledReason()).isEqualTo(CANCEL_REASON);

        // Het lidmaatschap is vrijgegeven.
        List<PublicationBundleBatch> memberships = bundleBatches.findByBundleId(scenario.bundleId());
        assertThat(memberships).hasSize(1);
        assertThat(memberships.get(0).getActiveMarker()).isNull();
        assertThat(memberships.get(0).getRemovedBy()).isEqualTo(CANCELLER);
        assertThat(memberships.get(0).getRemovedAt()).isNotNull();
        assertThat(memberships.get(0).getRemovedReason()).contains(CANCEL_REASON);
        assertThat(bundleBatches.findByBatchIdAndActiveMarkerIsNotNull(scenario.batchId())).isEmpty();
    }

    // --- (b) Annuleren vanuit FROZEN ----------------------------------------------------------------

    @Test
    void cancellingAFrozenBundleExpiresTheApprovedMutationsAndLeavesTheMarkerAlone() {
        Scenario scenario = updateScenario("FRZ");
        List<ImportMutation> content = contentMutations(scenario.batchId());
        BundleFreezeView frozen = freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);
        assertThat(frozen.status()).isEqualTo("FROZEN");
        for (ImportMutation mutation : content) {
            assertThat(mutations.findById(mutation.getId()).orElseThrow().getStatus())
                    .isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        }

        BundleCancelView view = cancellationService.cancel(scenario.bundleId(), CANCELLER, CANCEL_REASON);

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.expiredMutationCount()).isEqualTo(5L);
        for (ImportMutation mutation : content) {
            ImportMutation stored = mutations.findById(mutation.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(MutationStatus.EXPIRED);
            assertThat(stored.getDecidedFromStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
            assertThat(stored.getDecidedBy()).isEqualTo(CANCELLER);
        }
        ImportMutation marker = mutations.findById(markerOf(scenario.batchId())).orElseThrow();
        assertThat(marker.getStatus()).isEqualTo(MutationStatus.RECORDED);

        List<DecisionRow> register = queries.getBundleDecisions(scenario.bundleId(), 0, 50).content();
        assertThat(register).anySatisfy(row -> {
            assertThat(row.decisionKind()).isEqualTo("CANCEL");
            assertThat(row.previousStatus()).isEqualTo("FROZEN");
            assertThat(row.newStatus()).isEqualTo("CANCELLED");
            assertThat(row.affectedCount()).isEqualTo(5L);
        });
    }

    // --- (c) Ontbrekende gegevens --------------------------------------------------------------------

    @Test
    void cancellingWithoutAnActorOrReasonIsRefusedAndWritesNothing() {
        Scenario scenario = creationScenario("BADINPUT", 3);
        long decisionsBefore = decisionCount(scenario.bundleId());

        assertThatThrownBy(() -> cancellationService.cancel(scenario.bundleId(), null, CANCEL_REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cancellationService.cancel(scenario.bundleId(), "   ", CANCEL_REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cancellationService.cancel(scenario.bundleId(), "system", CANCEL_REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cancellationService.cancel(scenario.bundleId(), CANCELLER, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cancellationService.cancel(scenario.bundleId(), CANCELLER, "  "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(bundles.findById(scenario.bundleId()).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.ASSEMBLING);
        assertThat(decisionCount(scenario.bundleId())).isEqualTo(decisionsBefore);
        assertThat(bundleBatches.findByBatchIdAndActiveMarkerIsNotNull(scenario.batchId())).isPresent();
    }

    // --- (d) Een tweede annulering, en een bundel die niet annuleerbaar is --------------------------

    @Test
    void aSecondCancellationAndACancellationOfAnAlreadyCancelledBundleAreBothRefused() {
        Scenario scenario = creationScenario("TWICE", 3);
        cancellationService.cancel(scenario.bundleId(), CANCELLER, CANCEL_REASON);
        long decisionsAfterFirst = decisionCount(scenario.bundleId());

        assertThatThrownBy(() -> cancellationService.cancel(scenario.bundleId(), CANCELLER, "Nog eens"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleCancellationService.CODE_BUNDLE_NOT_CANCELLABLE);

        assertThat(decisionCount(scenario.bundleId())).isEqualTo(decisionsAfterFirst);
        assertThat(bundles.findById(scenario.bundleId()).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.CANCELLED);
    }

    @Test
    void anUnknownBundleIsNotFound() {
        assertThatThrownBy(() -> cancellationService.cancel(9_999_999L, CANCELLER, CANCEL_REASON))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", BundleCancellationService.CODE_BUNDLE_NOT_FOUND);
    }

    @Test
    void aBundleInPublishingOrPublishedStatusIsNotCancellable() {
        Scenario scenario = creationScenario("PUBSTATE", 2);
        jdbc.update("update publication_bundle set status = 'PUBLISHED' where id = ?", scenario.bundleId());

        assertThatThrownBy(() -> cancellationService.cancel(scenario.bundleId(), CANCELLER, CANCEL_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleCancellationService.CODE_BUNDLE_NOT_CANCELLABLE);
    }

    // --- (e) Na annulering: accept-baseline werkt weer, end-to-end ----------------------------------

    @Test
    void afterCancellationAcceptBaselineWorksAgainOnTheFreedBatch() {
        Scenario scenario = creationScenario("BASELINE", 3);
        // Terwijl de batch nog een actief lidmaatschap heeft, is accept-baseline een conflict (R-BAS-02).
        assertThatThrownBy(() -> baseline.acceptBaseline(scenario.batchId(), CREATOR, "Nulmeting"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", SourceStateBaselineService.CODE_BATCH_IN_PUBLICATION_BUNDLE);

        cancellationService.cancel(scenario.bundleId(), CANCELLER, CANCEL_REASON);

        SourceStateBaselineService.BaselineAcceptance acceptance =
                baseline.acceptBaseline(scenario.batchId(), CREATOR, "Nulmeting na annulering");
        assertThat(acceptance.status()).isEqualTo("BASELINE_ACCEPTED");
        assertThat(batches.findById(scenario.batchId()).orElseThrow().getStatus())
                .isEqualTo(ImportBatchStatus.BASELINE_ACCEPTED);
    }

    // --- (f) Na annulering: dezelfde batch kan opnieuw gebundeld worden -----------------------------

    /**
     * De batch komt vrij (R-BND-03/{@code uk_publication_bundle_batch_active}: het oude lidmaatschap
     * heeft nu {@code active_marker = NULL}), en de nieuwe bundel kan normaal bevroren worden. De
     * mutaties van de batch bleven wel {@code EXPIRED} (terminaal, R-FRZ-10) — annuleren wekt geen
     * dode voorstellen weer tot leven; dit bewijst dat het lidmaatschap, niet de mutatie-inhoud, de
     * herbruikbaarheid van de batch bepaalt.
     */
    @Test
    void afterCancellationTheSameBatchCanJoinAndFreezeANewBundle() {
        Scenario scenario = creationScenario("REBUNDLE", 3);
        cancellationService.cancel(scenario.bundleId(), CANCELLER, CANCEL_REASON);

        BundleReference secondBundle = bundleService.createBundle("BND-REBUNDLE-" + SEQUENCE.incrementAndGet(), null,
                PublicationTargetMode.SIMULATION, null, null, CREATOR);
        bundleService.addBatches(secondBundle.id(), List.of(scenario.batchId()), CREATOR);
        assertThat(bundleBatches.findByBatchIdAndActiveMarkerIsNotNull(scenario.batchId()))
                .hasValueSatisfying(m -> assertThat(m.getBundle().getId()).isEqualTo(secondBundle.id()));

        BundleFreezeView refrozen = freezeService.freeze(secondBundle.id(), FREEZER, "Tweede ronde, niets meer open");

        assertThat(refrozen.status()).isEqualTo("FROZEN");
        BundleDetail detail = queries.getBundle(secondBundle.id());
        assertThat(detail.batchCount()).isEqualTo(1L);
        assertThat(detail.contentMutationCount()).isEqualTo(3L);
        assertThat(detail.expiredCount()).isEqualTo(3L);
        assertThat(detail.readyCount()).isZero();
    }

    // --- (g) Financiële onveranderlijkheid, ook bij EXPIRED ------------------------------------------

    @Test
    void financialFieldsStayByteIdenticalWhenAMutationBecomesExpired() {
        Scenario scenario = updateScenario("FIN");
        List<ImportMutation> before = contentMutations(scenario.batchId());
        Map<Long, Map<String, Object>> financialBefore = financialSnapshots(before);

        cancellationService.cancel(scenario.bundleId(), CANCELLER, CANCEL_REASON);

        for (ImportMutation mutation : before) {
            ImportMutation stored = mutations.findById(mutation.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(MutationStatus.EXPIRED);
            assertThat(financialSnapshot(stored.getId())).isEqualTo(financialBefore.get(stored.getId()));
        }
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

    private long decisionCount(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) from publication_decision where bundle_id = ?",
                Long.class, bundleId);
        return count == null ? 0L : count;
    }

    private Map<Long, Map<String, Object>> financialSnapshots(List<ImportMutation> content) {
        return content.stream().collect(java.util.stream.Collectors.toMap(ImportMutation::getId,
                m -> financialSnapshot(m.getId())));
    }

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
        String unique = "BCN" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
