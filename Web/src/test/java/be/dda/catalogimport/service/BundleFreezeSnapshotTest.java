package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.PsimportPreviewDao;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationBundleSnapshotDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
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
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
 * Bouwstap 5P-2 (docs/design/fase5-pub-design.md par. 1 en par. 6; docs/decisions.md 2026-09-26
 * "5-PUB (deel a): ontwerp bindend"): het <b>vullen</b> van de bundelsnapshot bij het bevriezen, tegen
 * de echte services, DAO's en database.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Elke te publiceren mutatie krijgt exact één snapshotrij met haar herkomst, haar omschrijving
 *       en haar prijscomponenten, letterlijk gekopieerd uit de kandidaatstaging.</li>
 *   <li>{@code description_state} onderscheidt {@code VALUE}, {@code EMPTY} (gemapt maar leeg) en
 *       {@code NOT_MAPPED} (geen omschrijvingsveld op de revisie).</li>
 *   <li>Enkel {@code READY_FOR_PUBLICATION}-mutaties met {@code action_type} CREATE/UPDATE komen erin —
 *       exact de scope van {@code PsimportPreviewDao}.</li>
 *   <li>Ontbreekt de staging van één mutatie, dan wordt de <b>hele</b> bevriezing geweigerd met 409
 *       {@code SNAPSHOT_SOURCE_MISSING} en blijft er geen enkel spoor achter.</li>
 *   <li><b>Regressie:</b> {@code publication_bundle.content_hash} is byte-identiek aan een
 *       onafhankelijke herberekening van de in {@code PublicationBundleDao.computeContentHash}
 *       gedocumenteerde serialisatie — die serialisatie leest de snapshottabellen dus niet en is door
 *       5P-2 niet verschoven.</li>
 *   <li>{@code snapshot_hash}/{@code snapshot_spec_version} blijven {@code null}: die komen in 5P-3.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class BundleFreezeSnapshotTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** Vier kolommen: geen omschrijvingskolom, dus ook geen gemapte omschrijving. */
    private static final String HEADER_WITHOUT_DESCRIPTION = "LEVERANCIER;GROEP;REFERENTIE;PRIJS\n";
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String HEADER_WITH_COMPONENTS = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;AKP;VKP1\n";

    private static final String CREATOR = "jan.peeters@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String FREEZE_REASON = "Prijsronde september goedgekeurd";

    /** Scheidt twee velden binnen één mutatie in de bundelhash (unit separator). */
    private static final char FIELD_SEPARATOR = '\u001F';
    /** Sluit één mutatie af in de bundelhash (record separator). */
    private static final char RECORD_SEPARATOR = '\u001E';
    /** Vaste tekst voor een ontbrekende waarde in de bundelhash. */
    private static final String HASH_NULL = "null";

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
    private BundleDecisionService decisions;
    @Autowired
    private BundleFreezeService freezeService;
    @Autowired
    private PublicationBundleDao dao;
    @Autowired
    private PublicationBundleSnapshotDao snapshotDao;
    @Autowired
    private PsimportPreviewDao previewDao;
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
    private ImportMutationRepository mutations;
    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private JdbcTemplate jdbc;

    // --- (a) Het normale geval ---------------------------------------------------------------------

    /**
     * Het kernbewijs: n te publiceren mutaties leveren n snapshotrijen op, met de omschrijving uit de
     * bron en de volledige herkomst (batch, koppeling, bronregelnummer).
     */
    @Test
    void freezingWritesOneSnapshotRowPerPublishableMutationWithTheSourceDescription() {
        Scenario scenario = readyBundle(fixture("HAPPY", true, false), HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Schroevendraaier",
                "ACME;G1;R3;3,75;Hamer");
        List<ImportMutation> content = contentMutations(scenario.batchId());
        assertThat(content).hasSize(3);

        BundleFreezeView view = freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);

        assertThat(view.status()).isEqualTo("FROZEN");
        List<Map<String, Object>> rows = snapshotRows(scenario.bundleId());
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(row -> row.get("description"))
                .containsExactly("Boormachine", "Schroevendraaier", "Hamer");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("description_state")).isEqualTo("VALUE");
            assertThat(number(row, "batch_id")).isEqualTo(scenario.batchId());
            assertThat(number(row, "import_link_id")).isEqualTo(scenario.fixture().linkId());
            assertThat(row.get("created_at")).isNotNull();
        });
        // Eén snapshotrij per mutatie, gekoppeld op het bronregelnummer van die mutatie.
        assertThat(rows).extracting(row -> number(row, "mutation_id"))
                .containsExactlyInAnyOrderElementsOf(content.stream().map(ImportMutation::getId).toList());
        for (ImportMutation mutation : content) {
            Map<String, Object> row = rows.stream()
                    .filter(candidate -> number(candidate, "mutation_id") == mutation.getId())
                    .findFirst().orElseThrow();
            assertThat(number(row, "source_row_number")).isEqualTo(mutation.getSourceRowNumber());
        }

        // De scope is exact die van de PSIMPORT-preview; divergeert die ooit, dan valt dit om.
        assertThat(snapshotDao.countInScope(scenario.bundleId())).isEqualTo(previewDao.count(scenario.bundleId()));
        assertThat(snapshotDao.countSnapshotRows(scenario.bundleId())).isEqualTo(3L);

        // 5P-3: de snapshothash is gezet (details in de snapshot-hash-tests hieronder).
        PublicationBundle frozen = bundles.findById(scenario.bundleId()).orElseThrow();
        assertThat(frozen.getSnapshotHash()).hasSize(32);
        assertThat(frozen.getSnapshotSpecVersion()).isEqualTo("1");
    }

    // --- (b) De afleiding van description_state ----------------------------------------------------

    /** Gemapt met waarde en gemapt maar leeg zijn twee verschillende uitspraken van de bron. */
    @Test
    void anEmptySourceDescriptionBecomesEmptyAndAFilledOneBecomesValue() {
        Scenario scenario = readyBundle(fixture("EMPTY", true, false), HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;",
                "ACME;G1;R3;3,75;   ");

        freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);

        List<Map<String, Object>> rows = snapshotRows(scenario.bundleId());
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(row -> row.get("description_state"))
                .containsExactly("VALUE", "EMPTY", "EMPTY");
        // De bewaarde tekst blijft de bronwaarde; enkel de toestand wordt afgeleid.
        assertThat(rows.get(0).get("description")).isEqualTo("Boormachine");
        assertThat(rows.get(1).get("description")).isEqualTo("");
        assertThat(rows.get(2).get("description")).isEqualTo("");
    }

    /** Geen omschrijvingsveld op de revisie: de bron doet er geen uitspraak over, dus NOT_MAPPED. */
    @Test
    void anUnmappedDescriptionBecomesNotMappedAndNeverAnEmptyString() {
        Scenario scenario = readyBundle(fixture("NOTMAP", false, false), HEADER_WITHOUT_DESCRIPTION,
                "ACME;G1;R1;1,00",
                "ACME;G1;R2;2,50");

        freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);

        List<Map<String, Object>> rows = snapshotRows(scenario.bundleId());
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("description_state")).isEqualTo("NOT_MAPPED");
            assertThat(row.get("description")).isNull();
        });
    }

    // --- (c) Prijscomponenten ----------------------------------------------------------------------

    /**
     * Elke prijscomponent van de bronregel gaat mee, verbatim: hetzelfde bedrag, hetzelfde percentage
     * op schaal 12, dezelfde munt en dezelfde status. Nergens een herberekening, nergens een afronding
     * en nergens een stille 0.
     * <p>
     * De {@code BASE_PRICE}-component gaat bewust <b>niet</b> mee (ontwerp par. 1, "Niet gekopieerd:
     * basisprijs ... dupliceren zou een tweede bron van waarheid voor een bedrag zijn"): die staat al
     * op {@code import_mutation.after_base_price}, dat nooit opgeruimd wordt.
     */
    @Test
    void copiesEveryPriceComponentVerbatimWithoutTheBasePriceRow() {
        Scenario scenario = readyBundle(fixture("PRICE", true, true), HEADER_WITH_COMPONENTS,
                "ACME;G1;R1;100,00;Boormachine;80,00;120,00",
                "ACME;G1;R2;50,00;Hamer;40,00;60,00");

        freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);

        List<Map<String, Object>> prices = snapshotPriceRows(scenario.bundleId());
        // Twee regels x twee componenten; de BASE_PRICE-rij uit de staging komt er niet in.
        assertThat(prices).hasSize(4);
        assertThat(prices).extracting(row -> row.get("component_code"))
                .containsExactly("AKP", "VKP1", "AKP", "VKP1");
        assertThat(prices).extracting(row -> row.get("status")).containsOnly("OK");

        // Letterlijk hetzelfde als in import_candidate_price, inclusief de schaal: geen herberekening,
        // geen afronding, en nooit 0 waar de bron niets gaf.
        List<Map<String, Object>> staged = stagedPriceRows(scenario.batchId());
        assertThat(prices).hasSameSizeAs(staged);
        for (int index = 0; index < prices.size(); index++) {
            Map<String, Object> copy = prices.get(index);
            Map<String, Object> source = staged.get(index);
            assertThat(number(copy, "source_row_number")).isEqualTo(number(source, "source_row_number"));
            assertThat(copy.get("component_code")).isEqualTo(source.get("component_code"));
            assertThat(copy.get("currency")).isEqualTo(source.get("currency"));
            assertThat(copy.get("status")).isEqualTo(source.get("status"));
            // isEqualTo op BigDecimal is schaalgevoelig: dat is hier de bedoeling.
            assertThat(copy.get("source_amount")).isEqualTo(source.get("source_amount"));
            assertThat(copy.get("percentage")).isEqualTo(source.get("percentage"));
        }
        assertThat((BigDecimal) prices.get(0).get("percentage")).isEqualByComparingTo("80.000000000000");
        assertThat(((BigDecimal) prices.get(0).get("percentage")).scale()).isEqualTo(12);
        assertThat((BigDecimal) prices.get(0).get("source_amount")).isEqualByComparingTo("80.00");
        assertThat((BigDecimal) prices.get(1).get("percentage")).isEqualByComparingTo("120.000000000000");
        // Valuta-standaard (bouwstap V-2): geen muntveld en geen vaste valuta op de koppeling, dus de
        // systeemstandaard EUR. De snapshot kopieert die letterlijk uit import_candidate_price.
        assertThat(prices).allSatisfy(row -> assertThat(row.get("currency")).isEqualTo("EUR"));

        assertThat(jdbc.queryForObject("select count(*) from publication_bundle_snapshot_price p "
                        + "join publication_bundle_snapshot sn on sn.id = p.snapshot_id "
                        + "where sn.bundle_id = ? and p.component_code = 'BASE_PRICE'", Long.class,
                scenario.bundleId())).isZero();
    }

    /**
     * Grensgeval: een revisie zonder gemapte prijscomponenten schrijft helemaal geen
     * {@code import_candidate_price}-rijen. Dat is een geldige toestand, geen ontbrekende bron: de
     * snapshotrij bestaat, ze heeft alleen geen prijsrijen, en het bevriezen slaagt gewoon.
     */
    @Test
    void aMutationWithoutPriceComponentsGetsASnapshotRowWithoutPriceRowsAndNoFailure() {
        Scenario scenario = readyBundle(fixture("NOCOMP", true, false), HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Hamer");
        assertThat(jdbc.queryForObject("select count(*) from import_candidate_price where batch_id = ?",
                Long.class, scenario.batchId())).isZero();

        BundleFreezeView view = freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);

        assertThat(view.status()).isEqualTo("FROZEN");
        assertThat(snapshotRows(scenario.bundleId())).hasSize(2);
        assertThat(snapshotPriceRows(scenario.bundleId())).isEmpty();
    }

    // --- (d) Enkel READY_FOR_PUBLICATION in scope ---------------------------------------------------

    /**
     * Een afgekeurde en een vastgehouden ({@code BLOCKED}) mutatie gaan nooit naar Prodis en horen dus
     * niet in de snapshot — exact dezelfde afbakening als de PSIMPORT-preview.
     */
    @Test
    void rejectedAndBlockedMutationsGetNoSnapshotRow() {
        Fixture fixture = fixture("SCOPE", true, false);
        long batchId = screenedBatch(fixture, "REF-1", HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Schroevendraaier",
                "ACME;G1;R3;3,75;Hamer",
                "ACME;G1;R4;4,00;Zaag");
        long bundleId = bundleWith(fixture, batchId);
        List<ImportMutation> content = contentMutations(batchId);
        assertThat(content).hasSize(4);

        // Zoals pass E5/3f een vastgehouden regel zou schrijven; 5P-2 bouwt die controle niet na.
        long blocked = content.get(0).getId();
        jdbc.update("update import_mutation set status = 'BLOCKED', status_reason = ? where id = ?",
                "IDENTITY_REFERENCE_INCIDENT", blocked);
        long rejected = content.get(1).getId();
        decisions.reject(bundleId, rejected, DECIDER, "Referentie bestaat niet bij de leverancier");
        decisions.decideGroup(bundleId, BundleDecisionKind.APPROVE, DECIDER, "Rest nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null, null));

        freezeService.freeze(bundleId, FREEZER, FREEZE_REASON);

        List<Long> snapshotted = snapshotRows(bundleId).stream().map(row -> number(row, "mutation_id")).toList();
        assertThat(snapshotted).containsExactlyInAnyOrder(content.get(2).getId(), content.get(3).getId());
        assertThat(snapshotted).doesNotContain(blocked, rejected);
        // De IMPORT_MARKER is geen voorstel en krijgt dus ook geen snapshotrij.
        assertThat(snapshotted).doesNotContain(markerOf(batchId));
        assertThat(snapshotDao.countInScope(bundleId)).isEqualTo(previewDao.count(bundleId));
    }

    // --- (e) Staging weg = blokkeren ----------------------------------------------------------------

    /**
     * Ontwerp par. 1, "Staging weg = blokkeren". De kandidaatstaging van de batch is opgeruimd; de
     * snapshot zou dan stil leeg blijven. In plaats daarvan rolt de <b>hele</b> bevriezing terug: geen
     * snapshotrij, geen FREEZE-beslissingsregel, geen {@code frozen_by}, geen bulkgoedkeuring, en de
     * bundel staat nog {@code ASSEMBLING}.
     */
    @Test
    void missingCandidateStagingBlocksTheFreezeAndLeavesNothingBehind() {
        Scenario scenario = readyBundle(fixture("NOSTAGE", true, false), HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Schroevendraaier");
        long decisionsBefore = decisionCount(scenario.bundleId());
        List<Long> mutationIds = contentMutations(scenario.batchId()).stream().map(ImportMutation::getId).toList();
        // import_candidate_price hangt met on delete cascade aan de staging vast (changeset 004-5).
        jdbc.update("delete from import_candidate_stage where batch_id = ?", scenario.batchId());

        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_SNAPSHOT_SOURCE_MISSING)
                .hasMessageContaining("2 mutation(s) ready for publication")
                .hasMessageContaining("only 0 could be snapshotted");

        PublicationBundle bundle = bundles.findById(scenario.bundleId()).orElseThrow();
        assertThat(bundle.getStatus()).isEqualTo(PublicationBundleStatus.ASSEMBLING);
        assertThat(bundle.getFrozenBy()).isNull();
        assertThat(bundle.getFrozenAt()).isNull();
        assertThat(bundle.getContentHash()).isNull();
        assertThat(bundle.getSnapshotHash()).isNull();
        assertThat(snapshotDao.countSnapshotRows(scenario.bundleId())).isZero();
        assertThat(decisionCount(scenario.bundleId())).isEqualTo(decisionsBefore);
        assertThat(jdbc.queryForObject("select count(*) from publication_decision where bundle_id = ? "
                + "and decision_kind = 'FREEZE'", Long.class, scenario.bundleId())).isZero();
        // De mutaties staan nog exact zoals vóór de poging.
        for (Long mutationId : mutationIds) {
            assertThat(mutations.findById(mutationId).orElseThrow().getStatus())
                    .isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        }
    }

    /**
     * Ook een <b>gedeeltelijk</b> opgeruimde staging blokkeert: één ontbrekende bronregel is genoeg.
     * Nooit een snapshot die er volledig uitziet maar het niet is.
     */
    @Test
    void oneMissingStagedRowIsEnoughToBlockTheWholeFreeze() {
        Scenario scenario = readyBundle(fixture("PARTIAL", true, false), HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Schroevendraaier",
                "ACME;G1;R3;3,75;Hamer");
        Long orphan = contentMutations(scenario.batchId()).get(0).getSourceRowNumber();
        jdbc.update("delete from import_candidate_stage where batch_id = ? and row_number = ?",
                scenario.batchId(), orphan);

        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_SNAPSHOT_SOURCE_MISSING)
                .hasMessageContaining("only 2 could be snapshotted");

        assertThat(snapshotDao.countSnapshotRows(scenario.bundleId())).isZero();
        assertThat(bundles.findById(scenario.bundleId()).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.ASSEMBLING);
    }

    // --- (f) Herhaling en idempotentie ---------------------------------------------------------------

    /**
     * Bevriezen is niet idempotent maar wél veilig herhaalbaar: een tweede poging is het bestaande 409
     * {@code BUNDLE_NOT_ASSEMBLING} en raakt de snapshot niet aan. Er ontstaat dus nooit een tweede
     * snapshotrij voor dezelfde mutatie (de unieke constraint op {@code mutation_id} is de harde
     * zekering daaronder).
     */
    @Test
    void aSecondFreezeIsRefusedAndTheSnapshotRowCountStaysTheSame() {
        Scenario scenario = readyBundle(fixture("TWICE", true, false), HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Schroevendraaier");
        freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);
        long afterFirst = snapshotDao.countSnapshotRows(scenario.bundleId());
        assertThat(afterFirst).isEqualTo(2L);

        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), FREEZER, "Nog eens"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", BundleFreezeService.CODE_BUNDLE_NOT_ASSEMBLING);

        assertThat(snapshotDao.countSnapshotRows(scenario.bundleId())).isEqualTo(afterFirst);
    }

    /**
     * Een bundel waarin niets te publiceren valt (alles afgekeurd) blijft zich gedragen zoals vóór
     * 5P-2: 0 verwacht, 0 geschreven, geen {@code SNAPSHOT_SOURCE_MISSING}.
     */
    @Test
    void aBundleWithNothingPublishableFreezesWithoutASnapshotRowAndWithoutFailing() {
        Fixture fixture = fixture("NONE", true, false);
        long batchId = screenedBatch(fixture, "REF-1", HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Schroevendraaier");
        long bundleId = bundleWith(fixture, batchId);
        decisions.decideGroup(bundleId, BundleDecisionKind.REJECT, DECIDER, "Levering niet bevestigd",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null, null));

        BundleFreezeView view = freezeService.freeze(bundleId, FREEZER, FREEZE_REASON);

        assertThat(view.status()).isEqualTo("FROZEN");
        assertThat(snapshotDao.countSnapshotRows(bundleId)).isZero();
        assertThat(previewDao.count(bundleId)).isZero();
        // 5P-3: zonder snapshotrijen blijven hash en specversie null.
        PublicationBundle frozen = bundles.findById(bundleId).orElseThrow();
        assertThat(frozen.getSnapshotHash()).isNull();
        assertThat(frozen.getSnapshotSpecVersion()).isNull();
        assertThat(view.snapshotHash()).isNull();
        assertThat(view.snapshotSpecVersion()).isNull();
        assertThat(frozen.getContentHash()).hasSize(32);
    }

    // --- (h) 5P-3: snapshot_hash ------------------------------------------------------------------------

    /** Hash gezet, spec '1', gelijk aan een onafhankelijke nabouw; content_hash ongewijzigd; view toont hex. */
    @Test
    void snapshotHashIsStoredWithSpecVersionAndEqualsAnIndependentRecomputation() throws Exception {
        Scenario scenario = readyBundle(fixture("SNAPHASH", true, true), HEADER_WITH_COMPONENTS,
                "ACME;G1;R1;100,00;Boormachine;80,00;120,00",
                "ACME;G1;R2;50,00;;40,00;60,00");

        BundleFreezeView view = freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);

        PublicationBundle frozen = bundles.findById(scenario.bundleId()).orElseThrow();
        byte[] stored = frozen.getSnapshotHash();
        assertThat(stored).hasSize(32);
        assertThat(frozen.getSnapshotSpecVersion()).isEqualTo("1");
        assertThat(view.snapshotHash()).isEqualTo(HexFormat.of().formatHex(stored));
        assertThat(view.snapshotSpecVersion()).isEqualTo("1");
        assertThat(independentSnapshotHash(scenario.bundleId())).isEqualTo(stored);
        assertThat(snapshotDao.computeSnapshotHash(scenario.bundleId())).isEqualTo(stored);
        // content_hash blijft de onafhankelijke herberekening van de oude serialisatie.
        assertThat(independentContentHash(scenario.bundleId())).isEqualTo(frozen.getContentHash());
    }

    /** Verandert de snapshotinhoud, dan verandert de herberekende hash; terugzetten geeft de oude terug. */
    @Test
    void snapshotHashChangesWhenSnapshotContentChanges() {
        Scenario scenario = readyBundle(fixture("SNAPCHG", true, true), HEADER_WITH_COMPONENTS,
                "ACME;G1;R1;100,00;Boormachine;80,00;120,00");
        freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);
        byte[] original = bundles.findById(scenario.bundleId()).orElseThrow().getSnapshotHash();
        assertThat(snapshotDao.computeSnapshotHash(scenario.bundleId())).isEqualTo(original);

        jdbc.update("update publication_bundle_snapshot set description = 'Andere' where bundle_id = ?",
                scenario.bundleId());
        byte[] afterDescription = snapshotDao.computeSnapshotHash(scenario.bundleId());
        assertThat(afterDescription).isNotEqualTo(original);

        jdbc.update("update publication_bundle_snapshot set description = 'Boormachine' where bundle_id = ?",
                scenario.bundleId());
        assertThat(snapshotDao.computeSnapshotHash(scenario.bundleId())).isEqualTo(original);

        jdbc.update("update publication_bundle_snapshot_price set percentage = 81 where snapshot_id in "
                + "(select id from publication_bundle_snapshot where bundle_id = ?) and component_code = 'AKP'",
                scenario.bundleId());
        assertThat(snapshotDao.computeSnapshotHash(scenario.bundleId())).isNotEqualTo(original);
    }

    /** De hash hangt niet af van de fysieke invoegvolgorde: alle rijen worden verwijderd en omgekeerd terug ingevoegd. */
    @Test
    void snapshotHashIsIndependentOfInsertionOrder() {
        Scenario scenario = readyBundle(fixture("SNAPORD", true, true), HEADER_WITH_COMPONENTS,
                "ACME;G1;R1;100,00;Boormachine;80,00;120,00",
                "ACME;G1;R2;50,00;Hamer;40,00;60,00");
        freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);
        byte[] original = snapshotDao.computeSnapshotHash(scenario.bundleId());

        List<Map<String, Object>> rows = jdbc.queryForList("select * from publication_bundle_snapshot "
                + "where bundle_id = ? order by mutation_id", scenario.bundleId());
        List<Map<String, Object>> prices = snapshotPriceRowsWithSnapshotId(scenario.bundleId());
        jdbc.update("delete from publication_bundle_snapshot_price where snapshot_id in "
                + "(select id from publication_bundle_snapshot where bundle_id = ?)", scenario.bundleId());
        jdbc.update("delete from publication_bundle_snapshot where bundle_id = ?", scenario.bundleId());
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> row = rows.get(i);
            jdbc.update("insert into publication_bundle_snapshot (id, bundle_id, mutation_id, batch_id, "
                            + "import_link_id, source_row_number, description, description_state, created_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", row.get("id"), row.get("bundle_id"),
                    row.get("mutation_id"), row.get("batch_id"), row.get("import_link_id"),
                    row.get("source_row_number"), row.get("description"), row.get("description_state"),
                    row.get("created_at"));
        }
        for (int i = prices.size() - 1; i >= 0; i--) {
            Map<String, Object> p = prices.get(i);
            jdbc.update("insert into publication_bundle_snapshot_price (snapshot_id, component_code, "
                            + "source_amount, percentage, currency, status) values (?, ?, ?, ?, ?, ?)",
                    p.get("snapshot_id"), p.get("component_code"), p.get("source_amount"), p.get("percentage"),
                    p.get("currency"), p.get("status"));
        }

        assertThat(snapshotDao.computeSnapshotHash(scenario.bundleId())).isEqualTo(original);
    }

    /** Retry na een geweigerde tweede freeze verandert de hash niet. */
    @Test
    void aRefusedSecondFreezeLeavesTheSnapshotHashUnchanged() {
        Scenario scenario = readyBundle(fixture("SNAPRETRY", true, false), HEADER,
                "ACME;G1;R1;1,00;Boormachine");
        freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);
        byte[] first = bundles.findById(scenario.bundleId()).orElseThrow().getSnapshotHash();

        assertThatThrownBy(() -> freezeService.freeze(scenario.bundleId(), FREEZER, "Nog eens"))
                .isInstanceOf(ConflictException.class);

        assertThat(bundles.findById(scenario.bundleId()).orElseThrow().getSnapshotHash()).isEqualTo(first);
    }

    /** Een bevroren bundel zonder snapshot (van voor 5-PUB) heeft geen snapshothash. */
    @Test
    void aFrozenBundleCreatedWithoutSnapshotHasNullSnapshotHash() {
        Fixture fixture = fixture("LEGACY", true, false);
        BundleReference ref = bundleService.createBundle("BND-" + fixture.unique(), null,
                PublicationTargetMode.SIMULATION, null, null, CREATOR);
        PublicationBundle bundle = bundles.findById(ref.id()).orElseThrow();
        bundle.recordFreeze(FREEZER, java.time.Instant.now(), "Oud", new byte[32]);
        bundles.saveAndFlush(bundle);

        PublicationBundle reloaded = bundles.findById(ref.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PublicationBundleStatus.FROZEN);
        assertThat(reloaded.getSnapshotHash()).isNull();
        assertThat(reloaded.getSnapshotSpecVersion()).isNull();
    }

    private List<Map<String, Object>> snapshotPriceRowsWithSnapshotId(long bundleId) {
        return jdbc.queryForList("select p.snapshot_id, p.component_code, p.source_amount, p.percentage, "
                + "p.currency, p.status from publication_bundle_snapshot_price p "
                + "join publication_bundle_snapshot sn on sn.id = p.snapshot_id where sn.bundle_id = ? "
                + "order by p.snapshot_id, p.component_code", bundleId);
    }

    /** Onafhankelijke nabouw van "snapshot spec versie 1" uit de Javadoc van computeSnapshotHash. */
    private byte[] independentSnapshotHash(long bundleId) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Map<String, Object>> rows = jdbc.queryForList("select id, mutation_id, description_state, "
                + "description from publication_bundle_snapshot where bundle_id = ? order by mutation_id", bundleId);
        for (Map<String, Object> row : rows) {
            StringBuilder line = new StringBuilder();
            field(line, Long.toString(number(row, "mutation_id")));
            field(line, (String) row.get("description_state"));
            field(line, (String) row.get("description"));
            List<Map<String, Object>> comps = new java.util.ArrayList<>(jdbc.queryForList(
                    "select component_code, source_amount, percentage, currency, status "
                            + "from publication_bundle_snapshot_price where snapshot_id = ?", row.get("id")));
            comps.sort((a, b) -> ((String) a.get("component_code")).compareTo((String) b.get("component_code")));
            for (Map<String, Object> c : comps) {
                field(line, (String) c.get("component_code"));
                field(line, plain((BigDecimal) c.get("source_amount")));
                field(line, plain((BigDecimal) c.get("percentage")));
                field(line, (String) c.get("currency"));
                field(line, (String) c.get("status"));
            }
            line.append(RECORD_SEPARATOR);
            digest.update(line.toString().getBytes(StandardCharsets.UTF_8));
        }
        return digest.digest();
    }

    // --- (g) Regressie: content_hash blijft byte-identiek ---------------------------------------------

    /**
     * De sterkste haalbare regressie: de bewaarde {@code content_hash} wordt vergeleken met een
     * <b>onafhankelijke</b> herberekening in deze test, die de in
     * {@code PublicationBundleDao.computeContentHash} gedocumenteerde serialisatie letterlijk nabouwt
     * uit {@code import_mutation} — acht velden, U+001F/U+001E, {@code null} als vaste tekst,
     * {@code stripTrailingZeros().toPlainString()} voor de bedragen, geordend op {@code m.id}.
     * <p>
     * Die herberekening kent de snapshottabellen niet. Zou 5P-2 ooit een kolom aan de hashquery
     * toevoegen of de volgorde wijzigen, dan wijken beide af en valt deze test om — precies de garantie
     * die het ontwerp eist ("{@code computeContentHash} blijft byte-identiek").
     */
    @Test
    void theContentHashIsByteIdenticalToAnIndependentRecomputationAfterTheSnapshot() throws Exception {
        Scenario scenario = readyBundle(fixture("HASH", true, true), HEADER_WITH_COMPONENTS,
                "ACME;G1;R1;100,00;Boormachine;80,00;120,00",
                "ACME;G1;R2;50,00;Hamer;40,00;60,00");

        BundleFreezeView view = freezeService.freeze(scenario.bundleId(), FREEZER, FREEZE_REASON);

        byte[] stored = bundles.findById(scenario.bundleId()).orElseThrow().getContentHash();
        assertThat(stored).hasSize(32);
        assertThat(view.contentHash()).isEqualTo(HexFormat.of().formatHex(stored));
        // (1) De DAO blijft dezelfde waarde opleveren nu de snapshot bestaat.
        assertThat(dao.computeContentHash(scenario.bundleId())).isEqualTo(stored);
        // (2) En die waarde is die van de gedocumenteerde serialisatie, onafhankelijk nagerekend.
        assertThat(independentContentHash(scenario.bundleId())).isEqualTo(stored);
        assertThat(snapshotDao.countSnapshotRows(scenario.bundleId())).isEqualTo(2L);
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    /**
     * Onafhankelijke nabouw van de bundelhash uit de documentatie van
     * {@code PublicationBundleDao.computeContentHash}. Bewust geen enkele gedeelde hulpmethode met de
     * DAO: een gedeelde implementatie zou samen met de DAO mee verschuiven en dus niets bewijzen.
     */
    private byte[] independentContentHash(long bundleId) throws Exception {
        HexFormat hex = HexFormat.of();
        List<String> lines = jdbc.query("select m.batch_id, m.id, m.action_type, m.status, m.decision_id, "
                        + "       m.identity_hash, m.before_base_price, m.after_base_price "
                        + "from import_mutation m "
                        + "join publication_bundle_batch pbb "
                        + "  on pbb.batch_id = m.batch_id and pbb.bundle_id = ? and pbb.active_marker is not null "
                        + "order by m.id",
                (rs, rowNum) -> {
                    StringBuilder line = new StringBuilder(160);
                    field(line, Long.toString(rs.getLong(1)));
                    field(line, Long.toString(rs.getLong(2)));
                    field(line, rs.getString(3));
                    field(line, rs.getString(4));
                    long decisionId = rs.getLong(5);
                    field(line, rs.wasNull() ? null : Long.toString(decisionId));
                    byte[] identityHash = rs.getBytes(6);
                    field(line, identityHash == null ? null : hex.formatHex(identityHash));
                    field(line, plain(rs.getBigDecimal(7)));
                    field(line, plain(rs.getBigDecimal(8)));
                    line.append(RECORD_SEPARATOR);
                    return line.toString();
                }, bundleId);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String line : lines) {
            digest.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return digest.digest();
    }

    private static void field(StringBuilder line, String value) {
        line.append(value == null ? HASH_NULL : value).append(FIELD_SEPARATOR);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private List<Map<String, Object>> snapshotRows(long bundleId) {
        return jdbc.queryForList("select id, mutation_id, batch_id, import_link_id, source_row_number, "
                + "description, description_state, created_at from publication_bundle_snapshot "
                + "where bundle_id = ? order by source_row_number", bundleId);
    }

    private List<Map<String, Object>> snapshotPriceRows(long bundleId) {
        return jdbc.queryForList("select sn.source_row_number, p.component_code, p.source_amount, "
                + "p.percentage, p.currency, p.status "
                + "from publication_bundle_snapshot_price p "
                + "join publication_bundle_snapshot sn on sn.id = p.snapshot_id "
                + "where sn.bundle_id = ? order by sn.source_row_number, p.component_code", bundleId);
    }

    /** Dezelfde kolommen en dezelfde sortering als {@link #snapshotPriceRows}, maar uit de staging. */
    private List<Map<String, Object>> stagedPriceRows(long batchId) {
        return jdbc.queryForList("select p.row_number as source_row_number, p.component_code, p.source_amount, "
                + "p.percentage, p.currency, p.status from import_candidate_price p "
                + "where p.batch_id = ? and p.component_code <> 'BASE_PRICE' "
                + "order by p.row_number, p.component_code", batchId);
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private long decisionCount(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) from publication_decision where bundle_id = ?",
                Long.class, bundleId);
        return count == null ? 0L : count;
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

    /**
     * Eén levering, gescreend, in een eigen bundel, met alle wachtende mutaties goedgekeurd — dus
     * allemaal {@code READY_FOR_PUBLICATION}. Nog niet bevroren.
     */
    private Scenario readyBundle(Fixture fixture, String header, String... rows) {
        long batchId = screenedBatch(fixture, "REF-1", header, rows);
        long bundleId = bundleWith(fixture, batchId);
        decisions.decideGroup(bundleId, BundleDecisionKind.APPROVE, DECIDER, "Eerste levering nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null, null));
        return new Scenario(bundleId, batchId, fixture);
    }

    private long bundleWith(Fixture fixture, long batchId) {
        BundleReference bundle = bundleService.createBundle("BND-" + fixture.unique(), null,
                PublicationTargetMode.SIMULATION, null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);
        return bundle.id();
    }

    private long screenedBatch(Fixture fixture, String reference, String header, String... rows) {
        StringBuilder csv = new StringBuilder(header);
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        var delivery = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null, "levering.csv",
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))).delivery();
        long batchId = delivery.batch().batchId();
        screening.screen(batchId);
        return batchId;
    }

    /**
     * @param descriptionMapped {@code false} laat {@code recordDescriptionField} leeg: de bron doet
     *                          geen uitspraak over de omschrijving ({@code NOT_MAPPED})
     * @param priceComponents   {@code true} mapt AKP en VKP1 als prijscomponent (canonicalisatie
     *                          versie 2), zoals {@code PriceComponentScreeningFlowTest}
     */
    private Fixture fixture(String prefix, boolean descriptionMapped, boolean priceComponents) {
        String unique = "BFS" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        if (descriptionMapped) {
            revision.setRecordDescriptionField("OMSCHRIJVING");
        }
        // Zoals de bestaande fase 4-tests: deze test gaat niet over de drempel op records ter beoordeling.
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        if (priceComponents) {
            revision.setRecordCanonicalisationVersion(2);
        }
        revision.setStatus(RevisionStatus.ACTIVE);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        if (priceComponents) {
            fieldMappings.saveAndFlush(priceMapping(stored, 1, "AKP_PCT", "AKP"));
            fieldMappings.saveAndFlush(priceMapping(stored, 2, "VKP1_PCT", "VKP1"));
        }
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), unique);
    }

    /** De mapping gebruikt de geseede catalogusvelden; de test verzint geen eigen prijscomponenten. */
    private ImportFieldMapping priceMapping(ImportDefinitionRevision revision, int sequenceNumber, String code,
                                            String sourceReference) {
        ImportFieldCatalogEntry target = fieldCatalog.findById(code).orElseThrow();
        ImportFieldMapping mapping = new ImportFieldMapping(revision, sequenceNumber, target,
                FieldValueKind.SOURCE_FIELD, target.getDataType(), target.getDefaultOwner(),
                target.getIdentityClass());
        mapping.setSourceReference(sourceReference);
        mapping.setPriceComponentCode(target.getPriceComponentCode());
        return mapping;
    }

    private record Fixture(long taskId, long linkId, String unique) {
    }

    private record Scenario(long bundleId, long batchId, Fixture fixture) {
    }
}
