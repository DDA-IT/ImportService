package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationFileConditionRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.FetchFileObservationRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConnectionProfileService;
import be.dda.catalogimport.service.ConnectionProfileService.NewConnectionProfile;
import be.dda.catalogimport.service.CredentialService;
import be.dda.catalogimport.service.DeliveryArchiveStore;
import be.dda.catalogimport.service.DeliveryConfigurationService;
import be.dda.catalogimport.service.DeliveryConfigurationService.NewDeliveryConfiguration;
import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.DeliveryReceptionService;
import be.dda.catalogimport.service.FetchHostPolicy;
import be.dda.catalogimport.service.FetchRunService;
import be.dda.catalogimport.service.SecretsService;
import be.dda.catalogimport.service.SftpConnector;
import be.dda.catalogimport.service.TaskDeliveryConfigurationService;
import be.dda.catalogimport.service.TaskRunQueryService;
import be.dda.catalogimport.testsupport.SftpTestServer;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Gedeelde opzet van de K-4b-tests ({@link FetchRunTest}, {@link FetchIdempotencyTest}): één Spring-context (sleutelring,
 * allowlist {@code 127.0.0.1} met loopback-toelating, korte time-outs), per testklasse een embedded MINA SFTP-server, en
 * fixtures die de hele keten aanmaken zoals een mens dat doet (koppeling met actieve revisie, taak, credential,
 * profiel met vastgepinde sleutel, Leveringsconfiguratie, taakkoppeling).
 * <p>
 * Archief- en servermap zijn vaste tijdelijke mappen (niet per klasse), zodat beide klassen dezelfde, gecachte context
 * kunnen delen. De sleutel staat enkel hier (V6), onder een eigen sleutel-ID met eigen materiaal.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false",
        "catalogimport.fetch.recovery-on-startup=false",
        "catalogimport.secrets.keys=" + FetchTestSupport.KEY_ID + ":" + FetchTestSupport.KEY,
        "catalogimport.secrets.active-key-id=" + FetchTestSupport.KEY_ID,
        "catalogimport.fetch.allowed-hosts=127.0.0.1",
        "catalogimport.fetch.allow-loopback=true",
        "catalogimport.fetch.connect-timeout=PT3S",
        "catalogimport.fetch.auth-timeout=PT5S",
        "catalogimport.fetch.idle-timeout=PT10S"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
abstract class FetchTestSupport {

    static final String KEY_ID = "k4b-fetch-test";
    /** 32 bytes met waarde 43, base64. Enkel in de testsources (V6). */
    static final String KEY = "KysrKysrKysrKysrKysrKysrKysrKysrKysrKysrKys=";

    static final String API = "/api/catalog-import";
    static final String USER = "an.janssens@example.test";
    static final ActorIdentity ACTOR = new ActorIdentity(USER, "test-sub-" + USER);
    /** Waarden die nergens toevallig voorkomen, zodat elke vondst een echt lek is. */
    static final String PASSWORD = "K4b-Fetch-Wachtw00rd-7d2a!";
    static final String WRONG_PASSWORD = "K4b-Fout-Wachtw00rd-5e8c!";
    static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    static final AtomicInteger SEQUENCE = new AtomicInteger();

    static final Path ARCHIVE_ROOT = temporaryDirectory("k4b-archive");
    static final Path LOCAL_SOURCE_ROOT = temporaryDirectory("k4b-local-source");

    static Path sftpRoot;
    static KeyPair hostKey;
    static SftpTestServer server;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", ARCHIVE_ROOT::toString);
        registry.add("catalogimport.local-source.directory", LOCAL_SOURCE_ROOT::toString);
    }

    @BeforeAll
    static void startServer() throws Exception {
        sftpRoot = temporaryDirectory("k4b-sftp");
        hostKey = SftpTestServer.primaryHostKey();
        server = SftpTestServer.start(sftpRoot, PASSWORD, hostKey);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.close();
        }
        deleteRecursively(sftpRoot);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SourceOrganisationRepository sourceOrganisations;
    @Autowired
    ImportDefinitionRepository definitions;
    @Autowired
    ImportDefinitionRevisionRepository revisions;
    @Autowired
    ImportLinkRepository links;
    @Autowired
    CatalogImportTaskRepository tasks;
    @Autowired
    TaskRunRepository runs;
    @Autowired
    DeliveryRepository deliveries;
    @Autowired
    ExternalCredentialRepository credentials;
    @Autowired
    DeliveryConfigurationFileConditionRepository conditions;
    @Autowired
    FetchFileObservationRepository observations;
    @Autowired
    CredentialService credentialService;
    @Autowired
    ConnectionProfileService profileService;
    @Autowired
    DeliveryConfigurationService deliveryConfigurationService;
    @Autowired
    TaskDeliveryConfigurationService bindings;
    @Autowired
    FetchHostPolicy hostPolicy;
    @Autowired
    SecretsService secrets;
    @Autowired
    DeliveryArchiveStore archive;
    @Autowired
    DeliveryIntakeService intake;
    @Autowired
    DeliveryReceptionService reception;
    @Autowired
    TaskRunQueryService runQueries;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    Clock clock;

    /** Een volledige keten: taak met Leveringsconfiguratie op de SFTP-map {@code directory}. */
    record Chain(long taskId, long dcVersionId, long profileVersionId, UUID credentialRef, String directory) {
    }

    // --- Fixtures ----------------------------------------------------------------------------------------------------

    /** Keten met het juiste wachtwoord, de juiste hostsleutel en de DC-defaults (300 s, 1 GB). */
    Chain chain(String prefix) throws IOException {
        return chain(prefix, PASSWORD, fingerprint(), null, null);
    }

    Chain chain(String prefix, String password, String pinnedFingerprint, Integer minFileAgeSeconds,
                Long maxFileBytes) throws IOException {
        String directory = "/" + unique(prefix).toLowerCase();
        Files.createDirectories(sftpRoot.resolve(directory.substring(1)));
        CatalogImportTask task = task(link(unique(prefix)));
        UUID credentialRef = credentialService.create("K4b " + SEQUENCE.incrementAndGet(), "SFTP_PASSWORD",
                "127.0.0.1", password, "Ophaaltest", ACTOR).credentialRef();
        long profileVersionId = profileVersion(credentialRef, pinnedFingerprint);
        long dcVersionId = dcVersion(profileVersionId, directory, minFileAgeSeconds, maxFileBytes);
        bindings.bind(task.getId(), dcVersionId, "Automatisch ophalen", ACTOR);
        return new Chain(task.getId(), dcVersionId, profileVersionId, credentialRef, directory);
    }

    long profileVersion(UUID credentialRef, String pinnedFingerprint) {
        return profileService.create(new NewConnectionProfile(unique("CP"), "Ophaaltest", "127.0.0.1", server.port(),
                SftpTestServer.USERNAME, "PASSWORD", credentialRef.toString(), SftpTestServer.algorithmOf(hostKey),
                pinnedFingerprint, "Ophaaltest"), ACTOR).versions().get(0).id();
    }

    long dcVersion(long profileVersionId, String directory, Integer minFileAgeSeconds, Long maxFileBytes) {
        return deliveryConfigurationService.create(new NewDeliveryConfiguration(unique("DC"), "Ophaaltest",
                profileVersionId, directory, "ALL_FILES", null, minFileAgeSeconds, maxFileBytes, "Ophaaltest"), ACTOR)
                .versions().get(0).id();
    }

    CatalogImportTask task(ImportLink link) {
        return tasks.saveAndFlush(new CatalogImportTask(link, unique("taak"), TaskTriggerType.MANUAL));
    }

    ImportLink link(String unique) {
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
        return links.saveAndFlush(new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier,
                "PSARF050"));
    }

    /** Een geldige CSV-levering met één regel; {@code n} maakt de inhoud uniek. */
    static String csv(int n) {
        return HEADER + "ACME;G1;R" + n + ";10,00;Artikel " + n + "\n";
    }

    /** Schrijft een bestand in de SFTP-map van de keten met deze wijzigingstijd (op de seconde). */
    static void remoteFile(Chain chain, String name, Instant modified, String content) throws IOException {
        Path file = sftpRoot.resolve(chain.directory().substring(1)).resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(modified.truncatedTo(ChronoUnit.SECONDS)));
    }

    static Path remotePath(Chain chain, String name) {
        return sftpRoot.resolve(chain.directory().substring(1)).resolve(name);
    }

    static Instant hoursAgo(int hours) {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(hours, ChronoUnit.HOURS);
    }

    static Instant minutesAgo(int minutes) {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(minutes, ChronoUnit.MINUTES);
    }

    /** De sleutel zoals A4 hem voorschrijft, onafhankelijk van de productiecode berekend. */
    static String expectedKey(Chain chain, String name) throws Exception {
        Path file = remotePath(chain, name);
        String location = "127.0.0.1:" + server.port() + ":" + chain.directory() + "/" + name;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(location.getBytes(StandardCharsets.UTF_8));
        return "sftp:" + HexFormat.of().formatHex(digest).substring(0, 32) + ":"
                + Files.getLastModifiedTime(file).toInstant().getEpochSecond() + ":" + Files.size(file);
    }

    // --- HTTP --------------------------------------------------------------------------------------------------------

    ResultActions fetch(long taskId) throws Exception {
        return mockMvc.perform(post(API + "/tasks/{id}/fetch-runs", taskId).with(as(USER, Permission.MANAGE)));
    }

    String fetchBody(long taskId) throws Exception {
        return fetch(taskId).andReturn().getResponse().getContentAsString();
    }

    ResultActions runList(long taskId) throws Exception {
        return mockMvc.perform(get(API + "/tasks/{id}/runs", taskId).with(as(USER, Permission.READ)));
    }

    ResultActions runDetail(long runId) throws Exception {
        return mockMvc.perform(get(API + "/task-runs/{id}", runId).with(as(USER, Permission.READ)));
    }

    /** Naam → beslissing uit {@code $.observations}. */
    static Map<String, String> decisions(String body) {
        List<Map<String, Object>> rows = JsonPath.read(body, "$.observations");
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("remoteFileName"), (String) row.get("decision"));
        }
        return result;
    }

    static Object readOrNull(String body, String path) {
        try {
            return JsonPath.read(body, path);
        } catch (PathNotFoundException absent) {
            return null;
        }
    }

    static long longAt(String body, String path) {
        return ((Number) JsonPath.read(body, path)).longValue();
    }

    // --- Database ----------------------------------------------------------------------------------------------------

    long runCount(long taskId) {
        return jdbc.queryForObject("select count(*) from task_run where task_id = ?", Long.class, taskId);
    }

    long deliveryCount(long taskId) {
        return jdbc.queryForObject("select count(*) from delivery where task_id = ?", Long.class, taskId);
    }

    static long archivedFileCount() {
        try (Stream<Path> walk = Files.walk(ARCHIVE_ROOT)) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    FetchRunService serviceWith(SftpConnector connector) {
        return serviceWith(hostPolicy, secrets, connector);
    }

    FetchRunService serviceWith(FetchHostPolicy policy, SecretsService secretsService, SftpConnector connector) {
        return new FetchRunService(policy, connector, secretsService, archive, intake, reception, runQueries, tasks,
                runs, deliveries, credentials, conditions, observations, transactionManager, clock);
    }

    static SftpConnector newConnector() {
        return new SftpConnector(Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10), 10_000);
    }

    // --- Hulp --------------------------------------------------------------------------------------------------------

    static String fingerprint() {
        return SftpTestServer.expectedFingerprint(hostKey.getPublic());
    }

    static String randomFingerprint() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    static String unique(String prefix) {
        return "K4B" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
    }

    private static Path temporaryDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | UncheckedIOException ignored) {
            // Best effort: een achtergebleven tijdelijke map is onschuldig.
        }
    }
}
