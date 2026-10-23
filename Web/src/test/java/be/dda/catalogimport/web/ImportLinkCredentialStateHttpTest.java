package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.LegacyConfigOriginRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ExternalCredentialEventSource;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.LegacyConfigOrigin;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConnectionProfileService;
import be.dda.catalogimport.service.ConnectionProfileService.NewConnectionProfile;
import be.dda.catalogimport.service.CredentialService;
import be.dda.catalogimport.service.CredentialService.CredentialView;
import be.dda.catalogimport.service.DeliveryConfigurationService;
import be.dda.catalogimport.service.DeliveryConfigurationService.NewDeliveryConfiguration;
import be.dda.catalogimport.service.TaskDeliveryConfigurationService;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * B3, beslissingslog 2026-10-23 "API-uitbreiding voor de inrichtingsschermen (ontbrekende gegevens)" punt 3:
 * {@code GET /import-links} geeft per koppeling {@code credentialState} (nooit {@code null}).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>Geen credential</b> (geen legacy-herkomst met credential, geen taak met leveringsconfiguratie) →
 *       {@code NONE}.</li>
 *   <li><b>Legacy-secret overgenomen</b> ({@code legacy_config_origin.credential_id}, ACTIVE) → {@code STORED};
 *       <b>na intrekken</b> → {@code REVOKED}.</li>
 *   <li><b>Taak → leveringsconfiguratieversie → verbindingsprofielversie → credential</b> (ACTIVE) →
 *       {@code STORED}; ook via een uitgeschakelde taak (aanname: "gekoppeld" kijkt niet naar taak-actief).</li>
 *   <li><b>Eén ingetrokken + één actieve</b> → {@code STORED} (ACTIVE wint); <b>enkel ingetrokken, ook dubbel</b>
 *       (twee herkomsten op dezelfde koppeling) → {@code REVOKED}.</li>
 *   <li><b>Per koppeling:</b> een credential van de ene koppeling kleurt een andere koppeling op dezelfde definitie
 *       niet; een lege pagina geeft geen fout.</li>
 *   <li><b>Lekvrij (L7b, READ):</b> de rij bevat exact de bekende velden; geen {@code credentialRef}, label of
 *       host, ook niet als waarde ergens in het antwoord.</li>
 * </ul>
 * Fixtures rechtstreeks via repositories en de bestaande services (zelfde aanpak als {@link FetchTestSupport}),
 * opvragen met enkel {@code READ}. Elke fixture heeft een eigen definitie, zodat de filter
 * {@code importDefinitionId} de koppeling(en) los van de gedeelde database terugvindt.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false",
        // Zelfde testsleutel als CredentialHttpTest (secret_key_check legt het materiaal per sleutel-ID vast).
        "catalogimport.secrets.keys=" + CredentialHttpTest.KEY_ID + ":" + CredentialHttpTest.KEY,
        "catalogimport.secrets.active-key-id=" + CredentialHttpTest.KEY_ID})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportLinkCredentialStateHttpTest {

    private static final String API = "/api/catalog-import/import-links";
    private static final String USER = "an.janssens@example.test";
    private static final ActorIdentity ACTOR = new ActorIdentity(USER, "test-sub-" + USER);
    private static final ActorIdentity LEGACY_JOB = new ActorIdentity("svc:b3-legacy-job", "b3-legacy-job-sub");
    private static final String SECRET = "B3-Wachtw00rd-9f1c!";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @TempDir
    static Path archiveRoot;
    @TempDir
    static Path localSourceRoot;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> localSourceRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private LegacyConfigOriginRepository origins;
    @Autowired
    private ExternalCredentialRepository credentials;
    @Autowired
    private CredentialService credentialService;
    @Autowired
    private ConnectionProfileService profileService;
    @Autowired
    private DeliveryConfigurationService deliveryConfigurationService;
    @Autowired
    private TaskDeliveryConfigurationService bindings;

    // --- NONE ---------------------------------------------------------------------------------------------------

    @Test
    void aLinkWithoutAnyCredentialIsNone() throws Exception {
        Fixture f = fixture("NONE");

        assertThat(state(f)).isEqualTo("NONE");
    }

    @Test
    void aLegacyOriginWithoutCredentialIsStillNone() throws Exception {
        Fixture f = fixture("ORGNOCRED");
        LegacyConfigOrigin origin = new LegacyConfigOrigin("B3TEST", f.unique(), "IF1", Instant.now());
        origin.attachChain(f.link().getId(), f.task().getId(), Instant.now());
        origins.saveAndFlush(origin);

        assertThat(state(f)).isEqualTo("NONE");
    }

    // --- Legacy-herkomst ----------------------------------------------------------------------------------------

    @Test
    void aStoredLegacySecretIsStoredAndBecomesRevokedAfterRevocation() throws Exception {
        Fixture f = fixture("LEGACY");
        CredentialView credential = legacyCredential(f, "IF1");

        assertThat(state(f)).isEqualTo("STORED");

        credentialService.revoke(credential.credentialRef().toString(), "B3-test: ingetrokken", ACTOR);

        assertThat(state(f)).isEqualTo("REVOKED");
    }

    @Test
    void twoRevokedLegacyCredentialsOnTheSameLinkAreRevokedOnce() throws Exception {
        Fixture f = fixture("LEGACY2REV");
        CredentialView first = legacyCredential(f, "IF1");
        CredentialView second = legacyCredential(f, "IF2");
        credentialService.revoke(first.credentialRef().toString(), "B3-test: ingetrokken", ACTOR);
        credentialService.revoke(second.credentialRef().toString(), "B3-test: ingetrokken", ACTOR);

        List<Object> rows = rows(f.definitionId());
        assertThat(rows).hasSize(1);
        assertThat(JsonPath.<String>read(rows.get(0), "$.credentialState")).isEqualTo("REVOKED");
    }

    // --- Taak → leveringsconfiguratie → profiel ----------------------------------------------------------------

    @Test
    void aTaskWithADeliveryConfigurationWhoseProfileHasAnActiveCredentialIsStored() throws Exception {
        Fixture f = fixture("CHAIN");
        CredentialView credential = chainCredential(f);

        assertThat(state(f)).isEqualTo("STORED");

        credentialService.revoke(credential.credentialRef().toString(), "B3-test: ingetrokken", ACTOR);

        assertThat(state(f)).isEqualTo("REVOKED");
    }

    @Test
    void aDisabledTaskWithADeliveryConfigurationStillCounts() throws Exception {
        Fixture f = fixture("CHAINOFF");
        chainCredential(f);
        CatalogImportTask task = tasks.findById(f.task().getId()).orElseThrow();
        task.setActive(false);
        tasks.saveAndFlush(task);

        assertThat(state(f)).isEqualTo("STORED");
    }

    // --- Gemengd ------------------------------------------------------------------------------------------------

    @Test
    void oneRevokedLegacyCredentialAndOneActiveChainCredentialIsStored() throws Exception {
        Fixture f = fixture("MIXED");
        CredentialView legacy = legacyCredential(f, "IF1");
        credentialService.revoke(legacy.credentialRef().toString(), "B3-test: ingetrokken", ACTOR);
        chainCredential(f);

        assertThat(state(f)).isEqualTo("STORED");
    }

    @Test
    void oneActiveLegacyCredentialAndOneRevokedChainCredentialIsStored() throws Exception {
        Fixture f = fixture("MIXED2");
        legacyCredential(f, "IF1");
        CredentialView chain = chainCredential(f);
        credentialService.revoke(chain.credentialRef().toString(), "B3-test: ingetrokken", ACTOR);

        assertThat(state(f)).isEqualTo("STORED");
    }

    @Test
    void theCredentialOfOneLinkDoesNotColourAnotherLinkOfTheSameDefinition() throws Exception {
        Fixture f = fixture("ISOL");
        legacyCredential(f, "IF1");
        SourceOrganisation otherSupplier = sourceOrganisations.saveAndFlush(new SourceOrganisation(
                f.unique() + "-SUP2", f.unique() + "-SUP2 BV", SourceOrganisationType.SUPPLIER));
        ImportLink other = links.saveAndFlush(new ImportLink(f.unique() + "-LINK2", f.unique() + " koppeling 2",
                f.link().getImportDefinition(), otherSupplier, "PSARF050"));

        List<Object> rows = rows(f.definitionId());
        assertThat(rows).hasSize(2);
        assertThat(stateOf(rows, f.link().getId())).isEqualTo("STORED");
        assertThat(stateOf(rows, other.getId())).isEqualTo("NONE");
    }

    @Test
    void anEmptyPageHasNoRowsAndNoError() throws Exception {
        mockMvc.perform(get(API).param("importDefinitionId", "999999999").with(as(USER, Permission.READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // --- Lekvrij ------------------------------------------------------------------------------------------------

    @Test
    void theRowShowsOnlyTheStateNeverTheRefLabelOrHost() throws Exception {
        Fixture f = fixture("LEAK");
        CredentialView legacy = legacyCredential(f, "IF1");
        CredentialView chain = chainCredential(f);

        String body = body(f.definitionId());
        List<Map<String, Object>> rows = JsonPath.read(body, "$.content");
        assertThat(rows).singleElement().satisfies(row -> {
            // CD-B2 voegde de aanmaakstandaard toe (additief, geen credentialgegevens).
            assertThat(row.keySet()).containsExactlyInAnyOrder("id", "code", "name", "supplierCode",
                    "supplierName", "libraryCode", "active", "importDefinitionId", "credentialState",
                    "defaultVatCode", "defaultUnitCode", "creationDefaultsOrigin", "creationDefaultsUpdatedAt",
                    "creationDefaultsUpdatedBy");
            assertThat(row.get("credentialState")).isEqualTo("STORED");
        });
        for (CredentialView credential : List.of(legacy, chain)) {
            assertThat(body).doesNotContain(credential.credentialRef().toString())
                    .doesNotContain(credential.label())
                    .doesNotContain(credential.boundHost());
        }
        assertThat(body).doesNotContain("credentialRef").doesNotContain("boundHost").doesNotContain("label")
                .doesNotContain(SECRET);
    }

    // --- Helpers ------------------------------------------------------------------------------------------------

    /** Een SYSTEM-credential zoals de legacy-overname (S4) ze aanmaakt, gehangen aan een nieuwe herkomst. */
    private CredentialView legacyCredential(Fixture f, String interfaceCode) {
        CredentialView credential = credentialService.create("Legacy " + f.unique() + "/" + interfaceCode,
                "SFTP_PASSWORD", host(f), SECRET, "B3-test: overgenomen", LEGACY_JOB,
                ExternalCredentialEventSource.SYSTEM);
        Long credentialId = credentialIdOf(credential);
        LegacyConfigOrigin origin = new LegacyConfigOrigin("B3TEST", f.unique(), interfaceCode, Instant.now());
        origin.attachChain(f.link().getId(), f.task().getId(), Instant.now());
        origin.attachCredential(credentialId, Instant.now());
        origins.saveAndFlush(origin);
        return credential;
    }

    /** Credential → profiel → leveringsconfiguratie → taakkoppeling, zoals een beheerder dat doet (LC-2). */
    private CredentialView chainCredential(Fixture f) {
        String host = host(f);
        CredentialView credential = credentialService.create("B3 keten " + f.unique(), "SFTP_PASSWORD", host,
                SECRET, "B3-test", ACTOR);
        long profileVersionId = profileService.create(new NewConnectionProfile(f.unique() + "-CP", "B3 profiel",
                host, 22, "b3user", "PASSWORD", credential.credentialRef().toString(), "ssh-ed25519",
                randomFingerprint(), "B3-test"), ACTOR).versions().get(0).id();
        long dcVersionId = deliveryConfigurationService.create(new NewDeliveryConfiguration(f.unique() + "-DC",
                "B3 levering", profileVersionId, "/in", "ALL_FILES", null, null, null, "B3-test"), ACTOR)
                .versions().get(0).id();
        bindings.bind(f.task().getId(), dcVersionId, "B3-test", ACTOR);
        return credential;
    }

    private Long credentialIdOf(CredentialView credential) {
        return credentials.findByCredentialRef(credential.credentialRef()).orElseThrow().getId();
    }

    private String state(Fixture f) throws Exception {
        List<Object> rows = rows(f.definitionId());
        return stateOf(rows, f.link().getId());
    }

    private static String stateOf(List<Object> rows, long linkId) {
        return rows.stream().filter(row -> ((Number) JsonPath.read(row, "$.id")).longValue() == linkId)
                .map(row -> JsonPath.<String>read(row, "$.credentialState"))
                .findFirst().orElseThrow(() -> new AssertionError("link " + linkId + " not in the list"));
    }

    private List<Object> rows(long definitionId) throws Exception {
        return JsonPath.read(body(definitionId), "$.content");
    }

    private String body(long definitionId) throws Exception {
        return mockMvc.perform(get(API).param("importDefinitionId", String.valueOf(definitionId))
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String host(Fixture f) {
        return f.unique().toLowerCase() + ".b3.example.test";
    }

    private static String randomFingerprint() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    private Fixture fixture(String prefix) {
        // Persistente lokale database: nanoTime erbij zodat een herhaalde run niet botst met codes van een vorige.
        String unique = "B3" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return new Fixture(link, task, definition.getId(), unique);
    }

    private record Fixture(ImportLink link, CatalogImportTask task, long definitionId, String unique) {
    }
}
