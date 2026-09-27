package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.PsimportPreviewDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationRunRepository;
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
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.PsimportPreviewMapper.Field;
import be.dda.catalogimport.service.PsimportPreviewMapper.Row;
import be.dda.catalogimport.service.PsimportPreviewMapper.State;
import be.dda.catalogimport.service.PsimportPreviewService.PsimportPreview;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import be.dda.catalogimport.service.PublicationRunService.PublicationRunView;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Bouwstap 5P-7 (docs/design/fase5-pub-design.md par. 3, 4 en 5; docs/decisions.md 2026-09-26 "5-PUB
 * (deel a): ontwerp bindend"): de publicatierun in modus {@code SIMULATION} en het artefact op het
 * bestandssysteem, tegen de echte services, DAO's, database en schijf.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>Normaal pad:</b> de run wordt {@code SIMULATED}, het artefactbestand bestaat, de bewaarde
 *       SHA-256 en grootte komen overeen met het bestand, {@code row_count} is het aantal publiceerbare
 *       mutaties, {@code incomplete_row_count} is 0 voor een bundel mét snapshot, de marker is vrij en de
 *       view toont noch het subject, noch het serverpad.</li>
 *   <li><b>Artefact:</b> banner met {@code simulationOnly=true}/{@code writesToProdis=false}/
 *       {@code snapshotHash}, de vaste header, één rij per mutatie, en de rijen zijn letterlijk die van
 *       de bestaande {@code toCsv}-preview.</li>
 *   <li><b>Streaming == toCsv</b> (database-vrij): de nieuwe overload levert exact dezelfde tekst op als
 *       de ongewijzigde {@code toCsv}.</li>
 *   <li><b>Foutvolgorde</b> uit ontwerp par. 4, inclusief {@code TRIAL_LIBRARY}/{@code PRODUCTION} op een
 *       onbekende bundel.</li>
 *   <li><b>Onvolledigheid wordt getoond:</b> een bundel zonder snapshot draait wél, maar met
 *       {@code incomplete_row_count == row_count > 0} — niets wordt stil ingevuld of op 0 gezet.</li>
 *   <li><b>Faalpad:</b> een run blijft nooit actief hangen; er blijft geen artefactbestand achter en een
 *       volgende aanvraag slaagt weer.</li>
 *   <li><b>Geen operationeel effect:</b> de bundel blijft {@code FROZEN} en mutaties, batches en
 *       {@code catalog_source_state} zijn na de run byte-voor-byte dezelfde; annuleren blijft mogelijk.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class PublicationRunSimulationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String HEADER_WITH_COMPONENTS = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;AKP;VKP1\n";

    private static final String CREATOR = "jan.peeters@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String REQUESTER = "els.verhoeven@example.test";
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
    private PublicationBundleService bundleService;
    @Autowired
    private BundleDecisionService decisions;
    @Autowired
    private BundleFreezeService freezeService;
    @Autowired
    private BundleCancellationService cancellationService;
    @Autowired
    private PublicationRunService runService;
    @Autowired
    private PsimportPreviewService previewService;
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
    private PublicationBundleRepository bundles;
    @Autowired
    private PublicationRunRepository runs;
    @Autowired
    private JdbcTemplate jdbc;

    // --- (a) Het normale geval ---------------------------------------------------------------------

    @Test
    void aSimulationRunWritesTheArtifactAndRecordsItsHashSizeAndCounters() throws Exception {
        long bundleId = frozenBundle("HAPPY", HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Schroevendraaier",
                "ACME;G1;R3;3,75;Hamer");

        PublicationRunView view = runService.requestRun(bundleId, "SIMULATION", REQUESTER);

        assertThat(view.status()).isEqualTo("SIMULATED");
        assertThat(view.targetMode()).isEqualTo("SIMULATION");
        assertThat(view.attempt()).isEqualTo(1);
        assertThat(view.requestedBy()).isEqualTo(REQUESTER);
        assertThat(view.requestedAt()).isNotNull();
        assertThat(view.startedAt()).isNotNull();
        assertThat(view.finishedAt()).isNotNull();
        assertThat(view.rowCount()).isEqualTo(3L);
        assertThat(view.incompleteRowCount()).isZero();
        assertThat(view.rowCount()).isEqualTo(previewDao.count(bundleId));
        assertThat(view.failureCode()).isNull();
        assertThat(view.failureMessage()).isNull();
        // De niet-contractuele vlaggen: dit heeft NIETS gepubliceerd.
        assertThat(view.simulationOnly()).isTrue();
        assertThat(view.writesToProdis()).isFalse();
        assertThat(view.contractStatus()).isEqualTo("UNVERIFIED_FIELD_INVENTORY");
        assertThat(view.previewSpecVersion()).isEqualTo("2");
        assertThat(view.snapshotSpecVersion()).isEqualTo("1");

        Map<String, Object> row = runRow(view.id());
        assertThat(row.get("status")).isEqualTo("SIMULATED");
        assertThat(row.get("active_marker")).isNull();
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("requested_by")).isEqualTo(REQUESTER);
        // Rechtstreekse Service-aanroep = geen geverifieerde identiteit; nooit afgeleid uit de naam.
        assertThat(row.get("requested_by_subject")).isNull();
        assertThat(row.get("idempotency_key")).isEqualTo("run:" + bundleId + ":SIMULATION:1");

        String reference = (String) row.get("artifact_reference");
        assertThat(reference).startsWith("publication-runs/").endsWith("/psimport-preview.csv")
                .contains("/" + view.id() + "/");
        Path artifact = archiveRoot.resolve(reference);
        assertThat(Files.isRegularFile(artifact)).isTrue();
        byte[] bytes = Files.readAllBytes(artifact);
        assertThat(view.artifactByteSize()).isEqualTo(bytes.length);
        assertThat(view.artifactSha256()).isEqualTo(sha256Hex(bytes));
        // payload_hash is dezelfde waarde, binair.
        assertThat(view.payloadHash()).isEqualTo(view.artifactSha256());
        assertThat(view.bundleContentHash())
                .isEqualTo(HexFormat.of().formatHex(bundles.findById(bundleId).orElseThrow().getContentHash()));
        assertThat(view.snapshotHash())
                .isEqualTo(HexFormat.of().formatHex(bundles.findById(bundleId).orElseThrow().getSnapshotHash()));
    }

    /** Het subject wordt bewaard maar komt in geen enkel antwoord; het serverpad evenmin. */
    @Test
    void theSubjectIsStoredOnTheRowButNeverExposedAndTheViewCarriesNoFilePath() {
        long bundleId = frozenBundle("SUBJECT", HEADER, "ACME;G1;R1;1,00;Boormachine");

        PublicationRunView view = runService.requestRun(bundleId, PublicationTargetMode.SIMULATION,
                new ActorIdentity(REQUESTER, "oidc-subject-42"));

        assertThat(runRow(view.id()).get("requested_by_subject")).isEqualTo("oidc-subject-42");
        List<String> components = Arrays.stream(PublicationRunView.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(components).doesNotContain("requestedBySubject", "subject", "artifactReference", "reference");
        assertThat(view.toString()).doesNotContain("oidc-subject-42")
                .doesNotContain("publication-runs");
    }

    // --- (b) De inhoud van het artefact -------------------------------------------------------------

    @Test
    void theArtifactCarriesTheSimulationBannerTheFixedHeaderAndTheRowsOfTheExistingPreview() throws Exception {
        long bundleId = frozenBundle("CSV", HEADER_WITH_COMPONENTS, true,
                "ACME;G1;R1;100,00;Boormachine;80,00;120,00",
                "ACME;G1;R2;50,00;Hamer;40,00;60,00");

        PublicationRunView view = runService.requestRun(bundleId, "SIMULATION", REQUESTER);

        List<String> lines = artifactLines(view.id());
        String banner = lines.get(0);
        assertThat(banner).startsWith("# PREVIEW previewOnly=true")
                .contains("contractStatus=UNVERIFIED_FIELD_INVENTORY")
                .contains("previewSpecVersion=2")
                .contains("bundleContentHash=" + view.bundleContentHash())
                .contains("simulationOnly=true")
                .contains("writesToProdis=false")
                .contains("snapshotSpecVersion=1")
                .contains("snapshotHash=" + view.snapshotHash());

        // Header en datarijen zijn letterlijk die van de bestaande preview-CSV (banner uitgezonderd).
        String[] previewCsv = PsimportPreviewCsvSerializer
                .toCsv(previewService.preview(bundleId, 0, 200)).split("\n", -1);
        assertThat(lines.get(1)).isEqualTo(previewCsv[1]);
        assertThat(lines.get(1)).startsWith("batchId,mutationId,actionType,complete")
                .contains("DESCRIPTION,DESCRIPTION.state").contains("VKP1_PCT,VKP1_PCT.state");
        assertThat(lines).hasSize(4); // banner + header + twee mutaties
        assertThat(lines.subList(2, lines.size()))
                .containsExactly(previewCsv[2], previewCsv[3]);
        // De rijvolgorde volgt de mutatie-id's, niet de invoegvolgorde: toets de verzameling.
        assertThat(String.join("\n", lines.subList(2, lines.size()))).contains("Boormachine").contains("Hamer");
    }

    /** Een bevroren bundel zonder publiceerbare mutaties geeft banner + header en geen enkele datarij. */
    @Test
    void anEmptyBundleStillProducesABannerAndAHeader() throws Exception {
        Fixture fixture = fixture("EMPTY", true, false);
        long batchId = screenedBatch(fixture, HEADER, "ACME;G1;R1;1,00;Boormachine");
        long bundleId = bundleWith(fixture, batchId);
        decisions.decideGroup(bundleId, BundleDecisionKind.REJECT, DECIDER, "Levering niet bevestigd",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null, null));
        freezeService.freeze(bundleId, FREEZER, FREEZE_REASON);

        PublicationRunView view = runService.requestRun(bundleId, "SIMULATION", REQUESTER);

        assertThat(view.status()).isEqualTo("SIMULATED");
        assertThat(view.rowCount()).isZero();
        assertThat(view.incompleteRowCount()).isZero();
        List<String> lines = artifactLines(view.id());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).isEqualTo("batchId,mutationId,actionType,complete");
        // Zonder snapshotrijen draagt de bundel geen snapshothash; dat wordt getoond, niet verzonnen.
        assertThat(view.snapshotHash()).isNull();
        assertThat(lines.get(0)).contains("snapshotHash=(null)").contains("snapshotSpecVersion=(null)");
    }

    // --- (c) Streaming == toCsv (database-vrij) ------------------------------------------------------

    @Test
    void theStreamingOverloadProducesExactlyTheSameTextAsToCsv() throws IOException {
        List<Row> rows = List.of(
                new Row(7L, 42L, "CREATE", true, List.of(
                        new Field("SUPPLIER", "Leverancier", "ACME", State.VALUE),
                        new Field("BASE_PRICE", "Basisprijs", "1.00", State.VALUE),
                        new Field("DESCRIPTION", "Omschrijving NED", "Boor, met \"kop\"", State.VALUE))),
                new Row(7L, 43L, "UPDATE", false, List.of(
                        new Field("SUPPLIER", "Leverancier", "-ACME", State.VALUE),
                        new Field("BASE_PRICE", "Basisprijs", null, State.UNKNOWN),
                        new Field("DESCRIPTION", "Omschrijving NED", null, State.NOT_SNAPSHOTTED))));
        PsimportPreview preview = new PsimportPreview(true, "UNVERIFIED_FIELD_INVENTORY", "2", 1L, "abcd", "1",
                "beef", Instant.now(), rows, 0, 2, 2, 1);

        assertThat(stream(preview)).isEqualTo(PsimportPreviewCsvSerializer.toCsv(preview));

        PsimportPreview empty = new PsimportPreview(true, "UNVERIFIED_FIELD_INVENTORY", "2", 1L, null, null,
                null, Instant.now(), List.of(), 0, 50, 0, 0);
        assertThat(stream(empty)).isEqualTo(PsimportPreviewCsvSerializer.toCsv(empty));
        // En de artefactbanner is de preview-banner met vier velden erachter; de preview-banner zelf is
        // dus ongewijzigd.
        StringWriter artifactBanner = new StringWriter();
        PsimportPreviewCsvSerializer.writeArtifactBanner(artifactBanner, preview);
        assertThat(artifactBanner.toString())
                .startsWith(PsimportPreviewCsvSerializer.toCsv(preview).split("\n")[0])
                .endsWith(" simulationOnly=true writesToProdis=false snapshotSpecVersion=1 snapshotHash=beef\n");
    }

    /** Dezelfde bouwstenen als het artefact, maar met een preview die in het geheugen past. */
    private static String stream(PsimportPreview preview) throws IOException {
        StringWriter out = new StringWriter();
        PsimportPreviewCsvSerializer.writeBanner(out, preview);
        PsimportPreviewCsvSerializer.writeHeader(out,
                preview.content().isEmpty() ? List.of() : preview.content().get(0).fields());
        for (Row row : preview.content()) {
            PsimportPreviewCsvSerializer.writeRow(out, row);
        }
        return out.toString();
    }

    // --- (d) Foutvolgorde ----------------------------------------------------------------------------

    /** De modus wordt vóór de bundel gecontroleerd: een gesloten modus lekt niets over het bestaan van een bundel. */
    @Test
    void trialLibraryAndProductionAreAlwaysRefusedEvenForAnUnknownBundle() {
        for (PublicationTargetMode mode : List.of(PublicationTargetMode.TRIAL_LIBRARY,
                PublicationTargetMode.PRODUCTION)) {
            assertThatThrownBy(() -> runService.requestRun(-1L, mode.name(), REQUESTER))
                    .isInstanceOf(ConflictException.class)
                    .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_PUBLICATION_MODE_NOT_ENABLED);
        }
        long bundleId = frozenBundle("MODE", HEADER, "ACME;G1;R1;1,00;Boormachine");
        assertThatThrownBy(() -> runService.requestRun(bundleId, "PRODUCTION", REQUESTER))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_PUBLICATION_MODE_NOT_ENABLED);
        assertThat(runs.findByBundleIdOrderByIdAsc(bundleId)).isEmpty();
    }

    @Test
    void aMissingOrUnknownModeIsARequestError() {
        assertThatThrownBy(() -> runService.requestRun(-1L, (String) null, REQUESTER))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_PUBLICATION_MODE_REQUIRED);
        assertThatThrownBy(() -> runService.requestRun(-1L, "   ", REQUESTER))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_PUBLICATION_MODE_REQUIRED);
        assertThatThrownBy(() -> runService.requestRun(-1L, "SIMULATIE", REQUESTER))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_PUBLICATION_MODE_UNKNOWN);
        assertThatThrownBy(() -> runService.requestRun(-1L, (PublicationTargetMode) null,
                ActorIdentity.unverified(REQUESTER)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_PUBLICATION_MODE_REQUIRED);
    }

    @Test
    void anUnknownBundleIsNotFoundAndAnAssemblingBundleIsRefused() {
        assertThatThrownBy(() -> runService.requestRun(-1L, "SIMULATION", REQUESTER))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_BUNDLE_NOT_FOUND);

        Fixture fixture = fixture("OPEN", true, false);
        long batchId = screenedBatch(fixture, HEADER, "ACME;G1;R1;1,00;Boormachine");
        long bundleId = bundleWith(fixture, batchId);

        assertThatThrownBy(() -> runService.requestRun(bundleId, "SIMULATION", REQUESTER))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_BUNDLE_NOT_FROZEN);
        assertThat(runs.findByBundleIdOrderByIdAsc(bundleId)).isEmpty();
    }

    /** Hoogstens één niet-terminale run per bundel; de nette melding komt vóór de databaseconstraint. */
    @Test
    void anActiveRunBlocksASecondRequest() {
        long bundleId = frozenBundle("INPROGRESS", HEADER, "ACME;G1;R1;1,00;Boormachine");
        jdbc.update("insert into publication_run (bundle_id, target_mode, status, requested_by, requested_at, "
                        + "bundle_content_hash, idempotency_key, active_marker) values (?, ?, ?, ?, ?, ?, ?, ?)",
                bundleId, "SIMULATION", "REQUESTED", REQUESTER, java.sql.Timestamp.from(Instant.now()),
                new byte[32], "run:" + bundleId + ":SIMULATION:1", true);

        assertThatThrownBy(() -> runService.requestRun(bundleId, "SIMULATION", REQUESTER))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_PUBLICATION_RUN_IN_PROGRESS);

        assertThat(runs.findByBundleIdOrderByIdAsc(bundleId)).hasSize(1);
    }

    /**
     * De inhoud van de bundel is na het bevriezen gewijzigd (hier rechtstreeks via JDBC, zoals enkel een
     * fout of een ingreep buiten de applicatie zou doen). Dan wordt er niets gepubliceerd en blijft er
     * geen runrij achter — een artefact dat niet overeenkomt met wat getekend is, mag niet bestaan.
     */
    @Test
    void contentChangedSinceTheFreezeIsRefusedAndLeavesNoRunRow() {
        long bundleId = frozenBundle("CHANGED", HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Hamer");
        long mutationId = publishableMutationIds(bundleId).get(0);
        // coalesce: ook een mutatie zonder bedrag moet aantoonbaar van de bewaarde hash afwijken.
        jdbc.update("update import_mutation set after_base_price = coalesce(after_base_price, 0) + 1 "
                + "where id = ?", mutationId);

        assertThatThrownBy(() -> runService.requestRun(bundleId, "SIMULATION", REQUESTER))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code",
                        PublicationRunService.CODE_BUNDLE_CONTENT_CHANGED_SINCE_FREEZE);

        assertThat(runs.findByBundleIdOrderByIdAsc(bundleId)).isEmpty();
        assertThat(bundles.findById(bundleId).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.FROZEN);
    }

    // --- (e) Herhaling -------------------------------------------------------------------------------

    /** Een tweede run is geen duplicaat maar poging 2, met een eigen idempotentiesleutel en eigen artefact. */
    @Test
    void aSecondRunIsAttemptTwoWithItsOwnKeyAndItsOwnArtifact() throws Exception {
        long bundleId = frozenBundle("TWICE", HEADER, "ACME;G1;R1;1,00;Boormachine");

        PublicationRunView first = runService.requestRun(bundleId, "SIMULATION", REQUESTER);
        PublicationRunView second = runService.requestRun(bundleId, "SIMULATION", REQUESTER);

        assertThat(first.attempt()).isEqualTo(1);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(first.status()).isEqualTo("SIMULATED");
        assertThat(second.status()).isEqualTo("SIMULATED");
        assertThat(runRow(first.id()).get("idempotency_key")).isEqualTo("run:" + bundleId + ":SIMULATION:1");
        assertThat(runRow(second.id()).get("idempotency_key")).isEqualTo("run:" + bundleId + ":SIMULATION:2");
        assertThat(runRow(first.id()).get("active_marker")).isNull();
        assertThat(runRow(second.id()).get("active_marker")).isNull();
        // Twee bestanden, gelijke inhoud: dezelfde bevroren bundel levert dezelfde bytes.
        assertThat(runRow(first.id()).get("artifact_reference"))
                .isNotEqualTo(runRow(second.id()).get("artifact_reference"));
        assertThat(second.artifactSha256()).isEqualTo(first.artifactSha256());
        assertThat(artifactLines(first.id())).isEqualTo(artifactLines(second.id()));
        List<Long> listed = runService.listRuns(bundleId).stream().map(PublicationRunView::id).toList();
        assertThat(listed).containsExactly(first.id(), second.id());
        assertThat(runService.getRun(second.id()).attempt()).isEqualTo(2);
    }

    // --- (f) Oude bundel zonder snapshot -------------------------------------------------------------

    /**
     * Keuze mens 2026-09-26: een bundel die bevroren werd vóór de bundelsnapshot bestond, mag een
     * SIMULATION-run krijgen — maar met zichtbare onvolledigheid. Niets wordt stil ingevuld of op 0
     * gezet.
     */
    @Test
    void aBundleWithoutSnapshotRunsButEveryRowIsIncomplete() throws Exception {
        long bundleId = frozenBundle("LEGACY", HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Hamer");
        jdbc.update("delete from publication_bundle_snapshot_price where snapshot_id in "
                + "(select id from publication_bundle_snapshot where bundle_id = ?)", bundleId);
        jdbc.update("delete from publication_bundle_snapshot where bundle_id = ?", bundleId);
        jdbc.update("update publication_bundle set snapshot_hash = null, snapshot_spec_version = null "
                + "where id = ?", bundleId);

        PublicationRunView view = runService.requestRun(bundleId, "SIMULATION", REQUESTER);

        assertThat(view.status()).isEqualTo("SIMULATED");
        assertThat(view.rowCount()).isEqualTo(2L);
        assertThat(view.incompleteRowCount()).isEqualTo(view.rowCount());
        assertThat(view.incompleteRowCount()).isPositive();
        assertThat(view.snapshotHash()).isNull();
        assertThat(view.snapshotSpecVersion()).isNull();
        List<String> lines = artifactLines(view.id());
        assertThat(lines.get(0)).contains("snapshotHash=(null)");
        assertThat(lines.subList(2, lines.size())).allSatisfy(line ->
                assertThat(line).contains("NOT_SNAPSHOTTED").contains(",false,"));
    }

    // --- (g) Faalpad ---------------------------------------------------------------------------------

    /**
     * De artefactmap wordt geblokkeerd met een <b>bestand</b> waar een map moet komen. De run mag dan
     * niet actief blijven hangen: {@code FAILED} met een foutcode, marker vrij, geen artefactbestand, en
     * de boodschap draagt geen serverpad. Daarna slaagt een volgende aanvraag gewoon.
     */
    @Test
    void aFailingArtifactStoreEndsTheRunAsFailedWithoutLeavingAnythingBehind() throws Exception {
        long bundleId = frozenBundle("FAIL", HEADER, "ACME;G1;R1;1,00;Boormachine");
        Path artifactsRoot = archiveRoot.resolve("publication-runs");
        PublicationRunView failed;
        try {
            deleteRecursively(artifactsRoot);
            Files.createFile(artifactsRoot); // een bestand waar de store een map verwacht
            failed = runService.requestRun(bundleId, "SIMULATION", REQUESTER);
        } finally {
            Files.deleteIfExists(artifactsRoot);
        }

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureCode()).isEqualTo(PublicationRunService.FAILURE_ARTIFACT_WRITE_FAILED);
        assertThat(failed.failureMessage()).isNotBlank().hasSizeLessThanOrEqualTo(1000)
                .doesNotContain(archiveRoot.toString()).doesNotContain("publication-runs")
                .doesNotContain("\n");
        assertThat(failed.finishedAt()).isNotNull();
        assertThat(failed.artifactSha256()).isNull();
        assertThat(failed.rowCount()).isNull();
        Map<String, Object> row = runRow(failed.id());
        assertThat(row.get("active_marker")).isNull();
        assertThat(row.get("artifact_reference")).isNull();
        assertThat(Files.exists(artifactsRoot)).isFalse();
        // De bundel is niets opgeschoven en blijft publiceerbaar.
        assertThat(bundles.findById(bundleId).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.FROZEN);

        PublicationRunView retry = runService.requestRun(bundleId, "SIMULATION", REQUESTER);
        assertThat(retry.status()).isEqualTo("SIMULATED");
        assertThat(retry.attempt()).isEqualTo(2);
        assertThat(Files.isRegularFile(archiveRoot.resolve((String) runRow(retry.id()).get("artifact_reference"))))
                .isTrue();
    }

    // --- (h) Geen operationeel effect ----------------------------------------------------------------

    /** SIMULATED raakt niets: geen mutatiestatus, geen batch, geen bronstaat, en de bundel blijft FROZEN. */
    @Test
    void aSuccessfulRunChangesNoMutationNoBatchAndNoSourceState() {
        long bundleId = frozenBundle("NOEFFECT", HEADER,
                "ACME;G1;R1;1,00;Boormachine",
                "ACME;G1;R2;2,50;Hamer");
        long batchId = batchOf(bundleId);
        List<Map<String, Object>> mutationsBefore = mutationSnapshot(batchId);
        List<Map<String, Object>> batchBefore = batchSnapshot(batchId);
        List<Map<String, Object>> sourceStateBefore = sourceStateSnapshot();

        PublicationRunView view = runService.requestRun(bundleId, "SIMULATION", REQUESTER);

        assertThat(view.status()).isEqualTo("SIMULATED");
        assertThat(bundles.findById(bundleId).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.FROZEN);
        assertThat(mutationSnapshot(batchId)).isEqualTo(mutationsBefore);
        assertThat(batchSnapshot(batchId)).isEqualTo(batchBefore);
        assertThat(sourceStateSnapshot()).isEqualTo(sourceStateBefore);
    }

    /**
     * Keuze mens 2026-09-26: een geslaagde SIMULATION-run blokkeert het annuleren NIET, en de runs blijven
     * als auditspoor staan. {@link BundleCancellationService} is hiervoor niet gewijzigd.
     */
    @Test
    void cancellingTheBundleStillWorksAfterASuccessfulRunAndTheRunRowSurvives() {
        long bundleId = frozenBundle("CANCEL", HEADER, "ACME;G1;R1;1,00;Boormachine");
        PublicationRunView view = runService.requestRun(bundleId, "SIMULATION", REQUESTER);
        assertThat(view.status()).isEqualTo("SIMULATED");

        cancellationService.cancel(bundleId, FREEZER, "Levering ingetrokken door de leverancier");

        assertThat(bundles.findById(bundleId).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.CANCELLED);
        assertThat(runs.findByBundleIdOrderByIdAsc(bundleId)).hasSize(1);
        assertThat(runService.getRun(view.id()).status()).isEqualTo("SIMULATED");
    }

    // --- (i) Query-methodes ---------------------------------------------------------------------------

    @Test
    void unknownRunsAreReportedAndTheArtifactOfASimulatedRunIsReadable() {
        assertThatThrownBy(() -> runService.getRun(-1L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_RUN_NOT_FOUND);
        assertThatThrownBy(() -> runService.listRuns(-1L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", PublicationRunService.CODE_BUNDLE_NOT_FOUND);

        long bundleId = frozenBundle("OPENART", HEADER, "ACME;G1;R1;1,00;Boormachine");
        PublicationRunView view = runService.requestRun(bundleId, "SIMULATION", REQUESTER);
        assertThatThrownBy(() -> runService.openArtifact(-1L)).isInstanceOf(NotFoundException.class);

        // Het artefact van een geslaagde run is leesbaar en byte-identiek aan het bestand.
        byte[] streamed;
        try (var in = runService.openArtifact(view.id())) {
            streamed = in.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        assertThat(sha256Hex(streamed)).isEqualTo(view.artifactSha256());
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private Map<String, Object> runRow(long runId) {
        return jdbc.queryForMap("select status, active_marker, finished_at, requested_by, "
                + "requested_by_subject, idempotency_key, artifact_reference, artifact_sha256, "
                + "artifact_byte_size, row_count, incomplete_row_count, failure_code, failure_message "
                + "from publication_run where id = ?", runId);
    }

    /** De regels van het artefact, zonder de lege regel na de laatste newline. */
    private List<String> artifactLines(long runId) throws IOException {
        String reference = (String) runRow(runId).get("artifact_reference");
        String text = Files.readString(archiveRoot.resolve(reference), StandardCharsets.UTF_8);
        return Arrays.stream(text.split("\n", -1)).filter(line -> !line.isEmpty()).toList();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                entry.toFile().setWritable(true);
                Files.deleteIfExists(entry);
            }
        }
    }

    private List<Map<String, Object>> mutationSnapshot(long batchId) {
        return jdbc.queryForList("select id, status, status_reason, action_type, decision_id, decided_by, "
                + "before_base_price, after_base_price from import_mutation where batch_id = ? order by id",
                batchId);
    }

    private List<Map<String, Object>> batchSnapshot(long batchId) {
        return jdbc.queryForList("select id, status, content_mutation_count from import_batch where id = ?",
                batchId);
    }

    private List<Map<String, Object>> sourceStateSnapshot() {
        return jdbc.queryForList("select count(*) as total, coalesce(max(id), 0) as highest "
                + "from catalog_source_state");
    }

    private List<Long> publishableMutationIds(long bundleId) {
        return previewDao.findPage(bundleId, 200, 0).stream().map(PsimportPreviewDao.SourceRow::mutationId)
                .toList();
    }

    private long batchOf(long bundleId) {
        return jdbc.queryForObject("select batch_id from publication_bundle_batch where bundle_id = ? "
                + "and active_marker is not null order by batch_id", Long.class, bundleId);
    }

    /** Eén levering, gescreend, goedgekeurd en bevroren: klaar voor een publicatierun. */
    private long frozenBundle(String prefix, String header, String... rows) {
        return frozenBundle(prefix, header, false, rows);
    }

    private long frozenBundle(String prefix, String header, boolean priceComponents, String... rows) {
        Fixture fixture = fixture(prefix, true, priceComponents);
        long batchId = screenedBatch(fixture, header, rows);
        long bundleId = bundleWith(fixture, batchId);
        decisions.decideGroup(bundleId, BundleDecisionKind.APPROVE, DECIDER, "Eerste levering nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null, null));
        freezeService.freeze(bundleId, FREEZER, FREEZE_REASON);
        return bundleId;
    }

    private long bundleWith(Fixture fixture, long batchId) {
        BundleReference bundle = bundleService.createBundle("BND-" + fixture.unique(), null,
                PublicationTargetMode.SIMULATION, null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);
        return bundle.id();
    }

    private long screenedBatch(Fixture fixture, String header, String... rows) {
        StringBuilder csv = new StringBuilder(header);
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        var delivery = intake.intake(fixture.taskId(), "REF-1", "tester@example.test", null, null, "levering.csv",
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))).delivery();
        long batchId = delivery.batch().batchId();
        screening.screen(batchId);
        // De fixture kent geen valutakolom; een mutatie zonder valuta is terecht onvolledig (nooit EUR aannemen).
        // Zoals PsimportPreviewHttpTest geven we de mutaties daarom expliciet een valuta, vóór er bevroren wordt.
        jdbc.update("update import_mutation set base_price_currency = 'EUR' where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", batchId);
        return batchId;
    }

    /** Zelfde fixture als {@code BundleFreezeSnapshotTest}: eigen organisatie, definitie, revisie en taak. */
    private Fixture fixture(String prefix, boolean descriptionMapped, boolean priceComponents) {
        String unique = "PRS" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
}
