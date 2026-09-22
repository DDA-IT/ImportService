package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
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
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.BundleFreezeService.BundleFreezeView;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
 * Bouwstap 4e, de conflictregels bij het bevriezen (docs/design/fase4-publication-bundle-design.md
 * par. 1 R-FRZ-03/04, par. 3.8, par. 9 "Important business rule discovered") en de blokkerende
 * baselinecontrole (R-BND-06).
 *
 * <h2>De regel in één zin</h2>
 * Twee publiceerbare voorstellen voor <b>dezelfde aanbieding van dezelfde importkoppeling</b> mogen
 * nooit samen bevroren raken: Prodis zou er dan in onbepaalde volgorde één als laatste verwerken en de
 * andere wijziging zou spoorloos verdwijnen ("laatste import wint", verboden — businessanalyse r.1412).
 * Dat geldt binnen één bundel (R-FRZ-03) én tussen bundels (R-FRZ-04).
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>De sleutel is {@code (import_link_id, identity_hash)} en <b>niet</b> {@code identity_hash}
 *       alleen: twee verschillende koppelingen met exact dezelfde leverancierssleutel — en dus exact
 *       dezelfde identiteitshash — botsen niet, want dat zijn twee verschillende aanbiedingen in twee
 *       verschillende leveranciersbibliotheken.</li>
 *   <li>Dezelfde aanbieding uit twee batches van één bundel blokkeert het bevriezen, en het conflict
 *       verdwijnt zodra één kant afgekeurd is.</li>
 *   <li>Hetzelfde geldt wanneer de tweede kant in een andere, nog niet geannuleerde bundel zit.</li>
 *   <li>Een bronstaat die na het toevoegen verschoven is, blokkeert het bevriezen (R-BND-06).</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class BundleConflictTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String FREEZE_REASON = "Levering nagekeken en goedgekeurd";

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

    // --- (a) Twee koppelingen met dezelfde leverancierssleutel botsen niet -------------------------

    /**
     * De identiteitshash wordt berekend uit leverancier + groep + referentie, zonder de koppeling. Twee
     * verschillende koppelingen die dezelfde bestandsinhoud leveren, dragen dus <b>letterlijk dezelfde
     * identiteitshash</b> — en mogen toch samen in één bundel. Zou de conflictregel op de hash alleen
     * staan, dan zou elke tweede leverancier met dezelfde artikelnummering onbevriesbaar worden.
     */
    @Test
    void twoDifferentImportLinksWithTheSameSupplierKeyDoNotConflict() {
        Fixture left = fixture("LINKA");
        Fixture right = fixture("LINKB");
        long leftBatch = screenedBatch(left, "REF-1", rows(3, 100));
        long rightBatch = screenedBatch(right, "REF-1", rows(3, 100));
        // Het bewijs dat deze test werkelijk over dezelfde identiteit gaat.
        assertThat(identityHashes(leftBatch)).isEqualTo(identityHashes(rightBatch));

        long bundleId = bundleWith(left, List.of(leftBatch, rightBatch));
        approveEverythingWaiting(bundleId);

        BundleFreezeView view = freezeService.freeze(bundleId, FREEZER, FREEZE_REASON);

        assertThat(view.status()).isEqualTo("FROZEN");
        assertThat(contentMutations(leftBatch)).allSatisfy(m ->
                assertThat(m.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION));
        assertThat(contentMutations(rightBatch)).allSatisfy(m ->
                assertThat(m.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION));
    }

    // --- (b) Dezelfde aanbieding uit twee batches van één bundel -----------------------------------

    /**
     * R-FRZ-03 en het "Important business rule discovered"-blok uit ontwerp par. 9: twee opeenvolgende
     * leveringen van dezelfde bron die dezelfde aanbieding dragen. Omdat er tussen beide screenings
     * geen nulmeting aanvaard is, ziet de tweede screening de aanbieding opnieuw als nieuw — en staan
     * er twee publiceerbare voorstellen voor dezelfde aanbieding.
     */
    @Test
    void theSameOfferInTwoBatchesOfOneBundleBlocksTheFreezeUntilOneSideIsRejected() {
        Fixture f = fixture("INBUNDLE");
        long first = screenedBatch(f, "REF-1", rows(3, 100));
        long second = screenedBatch(f, "REF-2", rows(3, 150));
        assertThat(identityHashes(first)).isEqualTo(identityHashes(second));
        long bundleId = bundleWith(f, List.of(first, second));
        approveEverythingWaiting(bundleId);

        assertThatThrownBy(() -> freezeService.freeze(bundleId, FREEZER, FREEZE_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_BUNDLE_OFFER_CONFLICT)
                .hasMessageContaining("ACME/G1/R1")
                .hasMessageContaining(String.valueOf(first))
                .hasMessageContaining(String.valueOf(second));

        // De geweigerde bevriezing heeft niets veranderd.
        assertThat(bundles.findById(bundleId).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.ASSEMBLING);
        assertThat(bundles.findById(bundleId).orElseThrow().getContentHash()).isNull();

        // Eén kant afkeuren (individueel, 4c: een herziening van een ondertekende beslissing gaat nooit
        // en masse) laat exact één publiceerbaar voorstel per aanbieding over.
        for (ImportMutation mutation : contentMutations(second)) {
            decisions.reject(bundleId, mutation.getId(), DECIDER, "Vervangen door de eerste levering");
        }

        BundleFreezeView view = freezeService.freeze(bundleId, FREEZER, FREEZE_REASON);

        assertThat(view.status()).isEqualTo("FROZEN");
        assertThat(contentMutations(first)).allSatisfy(m ->
                assertThat(m.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION));
        assertThat(contentMutations(second)).allSatisfy(m ->
                assertThat(m.getStatus()).isEqualTo(MutationStatus.REJECTED));
    }

    // --- (c) Dezelfde aanbieding in een andere bundel ----------------------------------------------

    /** R-FRZ-04: twee goedkeuringsdossiers over dezelfde aanbieding is hetzelfde probleem, verspreid. */
    @Test
    void theSameOfferPublishableInAnotherBundleBlocksTheFreeze() {
        Fixture f = fixture("CROSS");
        long first = screenedBatch(f, "REF-1", rows(3, 100));
        long second = screenedBatch(f, "REF-2", rows(3, 150));
        long bundleOne = bundleWith(f, List.of(first));
        long bundleTwo = bundleWith(f, List.of(second));
        approveEverythingWaiting(bundleOne);
        approveEverythingWaiting(bundleTwo);

        assertThatThrownBy(() -> freezeService.freeze(bundleOne, FREEZER, FREEZE_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_OFFER_ALREADY_IN_ANOTHER_BUNDLE)
                .hasMessageContaining("ACME/G1/R1")
                .hasMessageContaining("bundle " + bundleTwo);
        // Symmetrisch: de andere bundel ziet exact hetzelfde conflict.
        assertThatThrownBy(() -> freezeService.freeze(bundleTwo, FREEZER, FREEZE_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_OFFER_ALREADY_IN_ANOTHER_BUNDLE);

        assertThat(bundles.findById(bundleOne).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.ASSEMBLING);

        // De tweede bundel afkeuren maakt de eerste bevriesbaar.
        for (ImportMutation mutation : contentMutations(second)) {
            decisions.reject(bundleTwo, mutation.getId(), DECIDER, "Wordt via de eerste bundel gepubliceerd");
        }

        assertThat(freezeService.freeze(bundleOne, FREEZER, FREEZE_REASON).status()).isEqualTo("FROZEN");
    }

    /**
     * Een bevroren bundel telt wél mee: haar mutaties staan nog op {@code READY_FOR_PUBLICATION} en
     * wachten op Fase 5. Pas een annulering (4f) geeft ze vrij.
     */
    @Test
    void anAlreadyFrozenBundleStillBlocksASecondBundleWithTheSameOffer() {
        Fixture f = fixture("CROSSFROZEN");
        long first = screenedBatch(f, "REF-1", rows(3, 100));
        long second = screenedBatch(f, "REF-2", rows(3, 150));
        long bundleOne = bundleWith(f, List.of(first));
        approveEverythingWaiting(bundleOne);
        freezeService.freeze(bundleOne, FREEZER, FREEZE_REASON);

        long bundleTwo = bundleWith(f, List.of(second));
        approveEverythingWaiting(bundleTwo);

        assertThatThrownBy(() -> freezeService.freeze(bundleTwo, FREEZER, FREEZE_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_OFFER_ALREADY_IN_ANOTHER_BUNDLE)
                .hasMessageContaining("bundle " + bundleOne);
    }

    // --- (d) De bronstaat verschoof na het toevoegen ------------------------------------------------

    /**
     * R-BND-06. Een andere batch van dezelfde koppeling wordt als nulmeting aanvaard nadat deze bundel
     * al samengesteld was. De {@code CREATE}-voorstellen in de bundel gaan er nog van uit dat de
     * aanbieding nog niet bestaat — dat klopt niet meer, dus bevriezen wordt geweigerd in plaats van
     * een aanmaak te publiceren op iets wat er al staat.
     */
    @Test
    void aSourceStateThatShiftedAfterScreeningBlocksTheFreeze() {
        Fixture f = fixture("STALE");
        long inBundle = screenedBatch(f, "REF-1", rows(3, 100));
        long elsewhere = screenedBatch(f, "REF-2", rows(3, 100));
        long bundleId = bundleWith(f, List.of(inBundle));
        approveEverythingWaiting(bundleId);

        // De accept-baseline-route blijft open voor een batch zonder bundellidmaatschap (R-BAS-02) en
        // schrijft catalog_source_state - precies de vergelijkingsbasis van de bundel.
        baseline.acceptBaseline(elsewhere, CREATOR, "Nulmeting van een andere levering");

        assertThatThrownBy(() -> freezeService.freeze(bundleId, FREEZER, FREEZE_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_SOURCE_STATE_CHANGED)
                .hasMessageContaining("3 mutation(s)");

        assertThat(bundles.findById(bundleId).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.ASSEMBLING);
        assertThat(bundles.findById(bundleId).orElseThrow().getContentHash()).isNull();
        // De mutaties van de bundel blijven exact staan waar ze stonden.
        assertThat(contentMutations(inBundle)).allSatisfy(m ->
                assertThat(m.getStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION));
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private long bundleWith(Fixture f, List<Long> batchIds) {
        BundleReference bundle = bundleService.createBundle("BND-" + f.unique() + "-" + SEQUENCE.incrementAndGet(),
                null, PublicationTargetMode.SIMULATION, null, null, CREATOR);
        bundleService.addBatches(bundle.id(), batchIds, CREATOR);
        return bundle.id();
    }

    /** Alle wachtende creaties van de bundel goedkeuren; anders blokkeert R-FRZ-02 het bevriezen. */
    private void approveEverythingWaiting(long bundleId) {
        decisions.decideGroup(bundleId, BundleDecisionKind.APPROVE, DECIDER, "Levering nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null));
        decisions.decideGroup(bundleId, BundleDecisionKind.APPROVE, DECIDER, "Levering nagekeken",
                new DecisionFilter(null, MutationStatus.PLANNED, null, null));
    }

    private List<String> identityHashes(long batchId) {
        return jdbc.queryForList("select identity_hash from import_mutation where batch_id = ? "
                        + "and identity_hash is not null order by id", byte[].class, batchId).stream()
                .map(hash -> java.util.HexFormat.of().formatHex(hash))
                .toList();
    }

    private List<ImportMutation> contentMutations(long batchId) {
        return mutations.findByBatchId(batchId, PageRequest.of(0, 200)).getContent().stream()
                .filter(m -> m.getActionType() == MutationActionType.CREATE
                        || m.getActionType() == MutationActionType.UPDATE)
                .toList();
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
        String unique = "BCF" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
