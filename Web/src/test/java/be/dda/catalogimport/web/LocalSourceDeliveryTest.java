package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.LocalSourceDirectory;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Tweede ontvangstweg: een levering <b>inlezen uit een beheerde servermap</b> naast de browser-upload
 * (bindend ontwerp {@code docs/decisions.md} 2026-09-27, Q1/Q2 en D1-D9). Volledige keten via de echte
 * controller, service en database.
 *
 * <ul>
 *   <li><b>Regel (Q1):</b> de server hasht het bronbestand zelf en leidt dezelfde referentie af als de
 *       browser. <b>Implementatie:</b> {@code DeliveryReferences.derive}; <b>data:</b>
 *       {@code delivery.idempotency_key = "manual:" + referentie}; <b>uitzondering:</b> gewijzigd tussen de
 *       hashpas en de intake = 409 {@code LOCAL_SOURCE_FILE_CHANGED} en niets geregistreerd.</li>
 *   <li><b>Regel (Q2):</b> de herkomst blijft permanent auditeerbaar. <b>Data:</b>
 *       {@code delivery.source_kind = LOCAL_DIRECTORY}.</li>
 *   <li><b>Regel (D7):</b> het bronbestand blijft ongemoeid — niets wordt verplaatst of verwijderd.</li>
 *   <li><b>Beveiliging:</b> enkel een kale bestandsnaam; padtekens, {@code ..}, absolute paden, NUL,
 *       verborgen bestanden, submappen en symlinks worden geweigerd zonder dat er ook maar één
 *       delivery-rij ontstaat, en <b>geen antwoord bevat ooit een absoluut pad</b>.</li>
 * </ul>
 * Elke test maakt haar eigen taak met unieke codes: de database is gedeeld.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalSourceDeliveryTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PATH = "/api/catalog-import/tasks/{id}/deliveries/local-source";
    private static final String LIST = "/api/catalog-import/local-source/files";

    private static final byte[] CSV = ("LEVERANCIER;GROEP;REFERENTIE;PRIJS\n"
            + "ACME;G1;R1;1,50\n"
            + "ACME;G1;R2;2,25\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_CSV = ("LEVERANCIER;GROEP;REFERENTIE;PRIJS\n"
            + "ACME;G1;R1;9,99\n").getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path archiveRoot;
    /** De beheerde servermap; bewust een andere boom dan het archief (D3 verbiedt overlap). */
    @TempDir
    static Path sourceRoot;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> sourceRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    /** Enkel gebruikt om het bronbestand kunstmatig te laten wijzigen tijdens één verzoek. */
    @MockitoSpyBean
    private LocalSourceDirectory localSource;
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
    private JdbcTemplate jdbc;

    // --- Happy path ----------------------------------------------------------------------------

    @Test
    void readsTheFileFromTheServerDirectoryArchivesItAndLeavesTheSourceUntouched() throws Exception {
        Fixture f = fixture("HAPPY");
        Path source = writeSource("levering-happy.csv", CSV);

        String body = read(f.task().getId(), "{\"fileName\":\"levering-happy.csv\"}")
                .andExpect(status().isCreated())
                // Zelfde synchrone screening als de upload (D5).
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.newCount").value(2))
                .andExpect(jsonPath("$.deliveryReference").value("levering-happy.csv#" + shortHash(CSV)))
                .andReturn().getResponse().getContentAsString();
        long deliveryId = ((Number) JsonPath.read(body, "$.deliveryId")).longValue();

        // Q1: dezelfde referentie als de browser zou afleiden, onder hetzelfde manual:-voorvoegsel.
        Delivery delivery = deliveries.findByTaskIdAndIdempotencyKey(f.task().getId(),
                "manual:levering-happy.csv#" + shortHash(CSV)).orElseThrow();
        assertThat(delivery.getId()).isEqualTo(deliveryId);
        assertThat(delivery.getActualByteSize()).isEqualTo(CSV.length);
        // Q2: de herkomst staat in de database, niet enkel in een logregel.
        assertThat(jdbc.queryForObject("select source_kind from delivery where id = ?", String.class, deliveryId))
                .isEqualTo("LOCAL_DIRECTORY");

        assertThat(deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(deliveryId)).singleElement()
                .satisfies(file -> {
                    assertThat(file.getFileName()).isEqualTo("levering-happy.csv");
                    assertThat(file.getContentHash()).isEqualTo(sha256Hex(CSV));
                    assertThat(file.getByteSize()).isEqualTo(CSV.length);
                });
        String archiveReference = jdbc.queryForObject(
                "select archive_reference from delivery_file where delivery_id = ?", String.class, deliveryId);
        assertThat(archiveRoot.resolve(archiveReference)).hasBinaryContent(CSV);

        // D7: het bronbestand blijft ongemoeid - geen move, geen delete, geen wijziging.
        assertThat(source).exists().hasBinaryContent(CSV);
    }

    /** Het leesmodel toont de ontvangstweg; een gewone upload blijft {@code UPLOAD} (zie DeliveryUploadTest). */
    @Test
    void exposesTheSourceKindOnTheDelivery() throws Exception {
        Fixture f = fixture("VIEW");
        writeSource("levering-view.csv", CSV);
        String body = read(f.task().getId(), "{\"fileName\":\"levering-view.csv\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long deliveryId = ((Number) JsonPath.read(body, "$.deliveryId")).longValue();

        mockMvc.perform(get("/api/catalog-import/deliveries/{id}", deliveryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceKind").value("LOCAL_DIRECTORY"))
                .andExpect(jsonPath("$.files[0].fileName").value("levering-view.csv"))
                .andExpect(jsonPath("$.files[0].archiveReference").doesNotExist());
    }

    @Test
    void acceptsAnExplicitReferenceAndTheOptionalExpectedCounts() throws Exception {
        Fixture f = fixture("EXP");
        writeSource("levering-exp.csv", CSV);

        read(f.task().getId(), "{\"fileName\":\"levering-exp.csv\",\"deliveryReference\":\"REF-EXP\","
                + "\"expectedRecordCount\":2,\"expectedByteSize\":" + CSV.length + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryReference").value("REF-EXP"))
                .andExpect(jsonPath("$.status").value("SCREENED"));

        Delivery delivery = deliveries.findByTaskIdAndIdempotencyKey(f.task().getId(), "manual:REF-EXP")
                .orElseThrow();
        assertThat(delivery.getExpectedRecordCount()).isEqualTo(2L);
        assertThat(delivery.getExpectedByteSize()).isEqualTo((long) CSV.length);
    }

    /** De bestaande volledigheidsblokkade werkt ook langs deze weg: nooit stil een afwijkende grootte. */
    @Test
    void anExpectedByteSizeThatDoesNotMatchBlocksTheBatch() throws Exception {
        Fixture f = fixture("SIZE");
        writeSource("levering-size.csv", CSV);

        read(f.task().getId(), "{\"fileName\":\"levering-size.csv\",\"expectedByteSize\":" + (CSV.length + 10) + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.blockedCode").value("BYTE_SIZE_MISMATCH"));
    }

    // --- Idempotentie en conflicten -------------------------------------------------------------

    @Test
    void readingTheSameFileTwiceIsAnIdempotentRetryWithoutASecondBatchOrRun() throws Exception {
        Fixture f = fixture("RETRY");
        writeSource("levering-retry.csv", CSV);
        String first = read(f.task().getId(), "{\"fileName\":\"levering-retry.csv\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long archivedBefore = archivedFileCount();

        String second = read(f.task().getId(), "{\"fileName\":\"levering-retry.csv\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(((Number) JsonPath.read(second, "$.deliveryId")).longValue())
                .isEqualTo(((Number) JsonPath.read(first, "$.deliveryId")).longValue());
        assertThat(((Number) JsonPath.read(second, "$.batchId")).longValue())
                .isEqualTo(((Number) JsonPath.read(first, "$.batchId")).longValue());
        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).hasSize(1);
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.task().getId())).hasSize(1);
        assertThat(batches.findByDeliveryIdOrderByAttemptNoAsc(
                ((Number) JsonPath.read(first, "$.deliveryId")).longValue())).hasSize(1);
        assertThat(archivedFileCount()).isEqualTo(archivedBefore); // het retry-object is opgeruimd
    }

    /**
     * Zelfde naam, andere inhoud: de afgeleide referentie verandert mee, dus is dit een <b>nieuwe</b>
     * levering — precies de reden waarom de referentie uit de inhoud komt en niet uit een tijdstempel.
     */
    @Test
    void correctedContentUnderTheSameNameBecomesANewDelivery() throws Exception {
        Fixture f = fixture("CORRECT");
        Path source = writeSource("levering-correct.csv", CSV);
        read(f.task().getId(), "{\"fileName\":\"levering-correct.csv\"}").andExpect(status().isCreated());

        Files.write(source, OTHER_CSV);
        read(f.task().getId(), "{\"fileName\":\"levering-correct.csv\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryReference")
                        .value("levering-correct.csv#" + shortHash(OTHER_CSV)));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).hasSize(2);
    }

    /** Met een handmatig hergebruikte referentie is andere inhoud wél een conflict, net als bij de upload. */
    @Test
    void reusingAnExplicitReferenceWithOtherContentIsRejected() throws Exception {
        Fixture f = fixture("REUSE");
        Path source = writeSource("levering-reuse.csv", CSV);
        read(f.task().getId(), "{\"fileName\":\"levering-reuse.csv\",\"deliveryReference\":\"REF-REUSE\"}")
                .andExpect(status().isCreated());
        long archivedBefore = archivedFileCount();

        Files.write(source, OTHER_CSV);
        read(f.task().getId(), "{\"fileName\":\"levering-reuse.csv\",\"deliveryReference\":\"REF-REUSE\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT"));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).hasSize(1);
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    /**
     * Wijzigt het bestand tussen de hashpas en de intake-stream, dan wordt er <b>niets</b> geregistreerd:
     * een levering half uit oude en half uit nieuwe bytes mag niet bestaan (Q1).
     */
    @Test
    void refusesTheIntakeWhenTheFileChangesWhileItIsBeingRead() throws Exception {
        Fixture f = fixture("CHANGED");
        Path source = writeSource("levering-changed.csv", CSV);
        byte[] longer = ("LEVERANCIER;GROEP;REFERENTIE;PRIJS\n"
                + "ACME;G1;R1;1,50\nACME;G1;R2;2,25\nACME;G1;R3;3,00\n").getBytes(StandardCharsets.UTF_8);
        AtomicBoolean rewritten = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            // Vóór het openen: zo is er nooit een geopende stream op een bestand dat we overschrijven.
            if (rewritten.compareAndSet(false, true)) {
                Files.write(source, longer);
            }
            return invocation.callRealMethod();
        }).when(localSource).open("levering-changed.csv");
        long archivedBefore = archivedFileCount();

        read(f.task().getId(), "{\"fileName\":\"levering-changed.csv\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCAL_SOURCE_FILE_CHANGED"));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.task().getId())).isEmpty();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    // --- Beveiliging: padtraversal, symlinks, verborgen bestanden -------------------------------

    /**
     * De volledige traversalbatterij. Elke poging is 400 of 404, er ontstaat <b>geen enkele</b>
     * delivery-rij, er wordt niets gearchiveerd, en geen enkel antwoord bevat een absoluut pad.
     */
    @Test
    void refusesEveryNameThatIsNotAPlainFileNameAndRegistersNothing() throws Exception {
        Fixture f = fixture("TRAVERSAL");
        // Een echt bestand net buiten de map, en een echte submap: als de weigering niet zou werken, zou
        // een van beide ingelezen raken.
        Path outside = Files.write(sourceRoot.getParent().resolve("secret-traversal.csv"), CSV);
        Path subdirectory = Files.createDirectories(sourceRoot.resolve("sub-traversal"));
        Files.write(subdirectory.resolve("f.csv"), CSV);
        Files.write(sourceRoot.resolve(".verborgen-traversal.csv"), CSV);
        long archivedBefore = archivedFileCount();

        // De waarden staan hier als JSON-tekst (dus al ge-escapeerd), zodat de server de échte tekens ziet
        // en niet Jackson op een ongeldige escape struikelt.
        List<String> refusedAsJson = List.of(
                "../secret-traversal.csv",          // klassieke traversal -> ..
                "..\\\\secret-traversal.csv",       // Windows-variant (JSON: ..\secret-traversal.csv)
                "..",                               // de map zelf
                "sub-traversal/f.csv",              // submap (D9: geen recursie)
                "/etc/passwd",                      // absoluut pad
                "C:/Windows/win.ini",               // absoluut pad met driveletter
                "levering\\u0000.csv",              // NUL-teken
                "na:me.csv",                        // driveletter-/streamscheiding
                ".verborgen-traversal.csv",         // verborgen bestand: niet aangeboden, niet leesbaar
                " ");                               // blanco

        for (String name : refusedAsJson) {
            var response = read(f.task().getId(), "{\"fileName\":\"" + name + "\"}")
                    .andReturn().getResponse();
            assertThat(response.getStatus()).as("status voor '%s'", name).isEqualTo(400);
            assertThat(response.getContentAsString()).as("foutcode voor '%s'", name)
                    .contains("LOCAL_SOURCE_FILE_NAME_INVALID")
                    // Nooit een absoluut pad in het antwoord: ook niet de naam van de tijdelijke map.
                    .doesNotContain(sourceRoot.getFileName().toString());
        }

        // Een geldige naam die naar een map wijst is geen bestand: 409, en ook hier geen pad in het antwoord.
        var directoryResponse = read(f.task().getId(), "{\"fileName\":\"sub-traversal\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCAL_SOURCE_FILE_NOT_REGULAR"))
                .andReturn().getResponse();
        assertThat(directoryResponse.getContentAsString())
                .doesNotContain(sourceRoot.getFileName().toString());

        // Een ontbrekende body is een gewone 400 en registreert evenmin iets.
        read(f.task().getId(), "{}").andExpect(status().isBadRequest());

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.task().getId())).isEmpty();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
        assertThat(outside).hasBinaryContent(CSV);
    }

    /** Een symlink binnen de map wordt altijd geweigerd, ook al zou hij binnen de map blijven. */
    @Test
    void refusesASymbolicLink() throws Exception {
        Fixture f = fixture("SYMLINK");
        Path outside = Files.write(sourceRoot.getParent().resolve("secret-symlink.csv"), CSV);
        Path link = sourceRoot.resolve("link-symlink.csv");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException noRights) {
            Assumptions.abort("Dit platform/account mag geen symlinks maken: " + noRights.getMessage());
        }

        read(f.task().getId(), "{\"fileName\":\"link-symlink.csv\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCAL_SOURCE_FILE_NOT_REGULAR"));

        // En hij wordt ook niet aangeboden: wat niet in de lijst staat, is niet in te lezen.
        String listing = mockMvc.perform(get(LIST)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain("link-symlink.csv");
        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
    }

    @Test
    void answers404ForAFileThatIsNotInTheDirectory() throws Exception {
        Fixture f = fixture("UNKNOWN");

        read(f.task().getId(), "{\"fileName\":\"bestaat-niet.csv\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCAL_SOURCE_FILE_NOT_FOUND"));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
    }

    // --- Taak en rechten ------------------------------------------------------------------------

    @Test
    void refusesATaskThatIsNotManual() throws Exception {
        Fixture f = fixture("SCHED", task -> {
            task.setTriggerType(TaskTriggerType.SCHEDULED);
            task.setTriggerExpression("0 0 4 * * *");
        });
        writeSource("levering-sched.csv", CSV);
        long archivedBefore = archivedFileCount();

        read(f.task().getId(), "{\"fileName\":\"levering-sched.csv\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_MANUAL"));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    @Test
    void refusesAUserWithoutManage() throws Exception {
        Fixture f = fixture("PERM");
        writeSource("levering-perm.csv", CSV);

        mockMvc.perform(post(PATH, f.task().getId()).with(as("leest.alleen@example.test", Permission.READ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"levering-perm.csv\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
    }

    // --- Lijst-endpoint -------------------------------------------------------------------------

    @Test
    void listsTheVisibleFilesMostRecentFirstWithoutHiddenFilesOrSubdirectories() throws Exception {
        clearSourceDirectory();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        touch(writeSource("oud.csv", CSV), now.minusSeconds(3600));
        touch(writeSource("nieuw.csv", CSV), now.minusSeconds(60));
        touch(writeSource("midden.csv", CSV), now.minusSeconds(600));
        Files.write(sourceRoot.resolve(".verborgen.csv"), CSV);
        Files.createDirectories(sourceRoot.resolve("submap"));

        mockMvc.perform(get(LIST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.files.length()").value(3))
                .andExpect(jsonPath("$.files[0].fileName").value("nieuw.csv"))
                .andExpect(jsonPath("$.files[0].byteSize").value(CSV.length))
                .andExpect(jsonPath("$.files[0].lastModifiedAt").exists())
                .andExpect(jsonPath("$.files[1].fileName").value("midden.csv"))
                .andExpect(jsonPath("$.files[2].fileName").value("oud.csv"))
                // Geen pad in het antwoord, enkel namen.
                .andExpect(jsonPath("$.files[0].path").doesNotExist());
    }

    @Test
    void capsTheListingAndSaysThatItIsTruncated() throws Exception {
        clearSourceDirectory();
        for (int i = 0; i <= CatalogImportDeliveryController.LOCAL_SOURCE_LIST_CAP; i++) {
            Files.write(sourceRoot.resolve(String.format("bulk-%04d.csv", i)), CSV);
        }

        mockMvc.perform(get(LIST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.files.length()")
                        .value(CatalogImportDeliveryController.LOCAL_SOURCE_LIST_CAP));

        clearSourceDirectory(); // laat de map schoon achter voor andere tests in deze klasse
    }

    // --- Helpers --------------------------------------------------------------------------------

    private ResultActions read(long taskId, String body) throws Exception {
        return mockMvc.perform(post(PATH, taskId).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Path writeSource(String fileName, byte[] content) throws Exception {
        return Files.write(sourceRoot.resolve(fileName), content);
    }

    private static void touch(Path file, Instant moment) throws Exception {
        Files.setLastModifiedTime(file, FileTime.from(moment));
    }

    private void clearSourceDirectory() throws Exception {
        try (var walk = Files.walk(sourceRoot)) {
            walk.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(sourceRoot)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: een achterblijvend bestand maakt de volgende assert zichtbaar stuk.
                }
            });
        }
    }

    private long archivedFileCount() throws Exception {
        try (var walk = Files.walk(archiveRoot)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** De eerste twaalf hex-tekens: exact wat de browserregel in de referentie zet. */
    private static String shortHash(byte[] content) throws Exception {
        return sha256Hex(content).substring(0, 12);
    }

    private Fixture fixture(String prefix) {
        return fixture(prefix, null);
    }

    /** Volledige keten tot en met een MANUAL-taak met een ACTIEVE revisie met prijsveld. */
    private Fixture fixture(String prefix, Consumer<CatalogImportTask> taskCustomiser) {
        String unique = "LS" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        revision.setStatus(RevisionStatus.ACTIVE);
        revision = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL);
        if (taskCustomiser != null) {
            taskCustomiser.accept(task);
        }
        task = tasks.saveAndFlush(task);
        return new Fixture(task, link, revision);
    }

    private record Fixture(CatalogImportTask task, ImportLink link, ImportDefinitionRevision revision) {
    }
}
