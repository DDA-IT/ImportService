package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.AcquisitionConfigEventRepository;
import be.dda.catalogimport.dao.ConnectionProfileVersionRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationFileConditionRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationVersionRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.domain.AcquisitionConfigEvent;
import be.dda.catalogimport.domain.AcquisitionConfigEventKind;
import be.dda.catalogimport.domain.AcquisitionConfigEventSource;
import be.dda.catalogimport.domain.ConnectionProfileVersion;
import be.dda.catalogimport.domain.DeliveryConfigurationFileCondition;
import be.dda.catalogimport.domain.DeliveryConfigurationVersion;
import be.dda.catalogimport.domain.DeliverySelectionMode;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.domain.ExternalCredentialStatus;
import be.dda.catalogimport.service.SftpConnector.HostKey;
import be.dda.catalogimport.service.SftpConnector.Listing;
import be.dda.catalogimport.service.SftpConnector.PasswordSource;
import be.dda.catalogimport.service.SftpConnector.PinnedHostKey;
import be.dda.catalogimport.service.SftpConnector.RemoteFile;
import be.dda.catalogimport.service.support.HostNames;
import be.dda.catalogimport.service.support.RemoteFileSelection;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hostsleutelscan en verbindingstest (bouwstap K-4a; {@code docs/design/leveringsconfiguratie-design.md} par. 5;
 * beslissingslog 2026-09-29 L4, L5, L7, A12, A16-A18).
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Scan</b> ({@link #scanHostKey}): verbindt zonder aanmelding met {@code host:port} en geeft algoritme en
 *       SHA-256-vingerafdruk. De mens vergelijkt die met de waarde die de leverancier via een ander kanaal gaf en neemt
 *       ze over in een profielversie (L5). De scan bewaart niets behalve het auditevent.</li>
 *   <li><b>Profieltest</b> ({@link #testProfileVersion}): verbinding, vastgepinde hostsleutel en aanmelding.</li>
 *   <li><b>DC-test</b> ({@link #testDeliveryConfigurationVersion}): idem, plus de map lijsten en de voorwaarden
 *       toepassen; toont hoogstens {@value #MAX_MATCHED_FILES} bestanden (nieuwste eerst) met {@code truncated}.
 *       <b>Geen download.</b> Minimale ouderdom en maximale grootte zijn beslissingen van de ophaalrun (K-4b) en tellen
 *       hier niet.</li>
 *   <li>Een test is <b>nooit</b> verplicht voor activatie (A16). Elk resultaat, geslaagd of niet, schrijft één
 *       {@code acquisition_config_event} ({@code HOST_KEY_SCANNED} of {@code CONNECTION_TESTED}, HUMAN, actor uit het
 *       token, {@code outcome_code} = {@code OK} of de foutcode, {@code detail} zonder secret of serverbanner).</li>
 * </ul>
 *
 * <h2>Foutafhandeling</h2>
 * Een fout aan de kant van de externe server is geen HTTP-fout (design par. 4.1/5): het antwoord is 200 met
 * {@code outcome = FAILED} en een {@code failureCode} uit {@link FetchOutcomeCodes}. Uitzonderingen, allemaal vóór er
 * iets gecontacteerd wordt en zonder event, in deze volgorde:
 * <ul>
 *   <li>400 {@code CONNECTION_PROFILE_HOST_INVALID}/{@code _PORT_INVALID} (enkel de scan heeft invoer; de aanvraag zelf
 *       wordt eerst beoordeeld, zoals bij de servermap);</li>
 *   <li>404 {@code FETCH_NOT_CONFIGURED}: de allowlist is niet gezet (L4b) - de functie bestaat dan niet; bij de tests
 *       vóór de bestaanscontrole van de versie;</li>
 *   <li>404 {@code CONNECTION_PROFILE_VERSION_NOT_FOUND}/{@code DELIVERY_CONFIGURATION_VERSION_NOT_FOUND} (test);</li>
 *   <li>409 {@code SECRETS_NOT_CONFIGURED} (test): zonder sleutelring kan geen wachtwoord ontsleuteld worden (V7).</li>
 * </ul>
 * <b>Scan van een host buiten de allowlist</b> (of een naam die naar een verboden adres resolvet) is 409
 * {@code FETCH_HOST_NOT_ALLOWED} <b>met</b> een event: de host komt rechtstreeks uit de aanvraag, en een geweigerde
 * poging hoort in de audit (SSRF-poging). Bij een <b>test</b> is dezelfde weigering een 200-uitkomst
 * ({@code FAILED}/{@code FETCH_HOST_NOT_ALLOWED}), zoals par. 5 voorschrijft.
 *
 * <h2>Volgorde van een test</h2>
 * allowlist (zonder DNS) → credential ingetrokken ({@code CREDENTIAL_REVOKED}, niets gecontacteerd) → één DNS-opzoeking
 * en adrescontrole → verbinden en hostsleutel → pas dan ontsleutelen ({@code CREDENTIAL_UNDECRYPTABLE}) → aanmelden →
 * (DC) map en voorwaarden.
 *
 * <h2>Transacties</h2>
 * Nooit een databasetransactie open tijdens netwerkverkeer: eerst een korte leestransactie die een momentopname
 * maakt, dan de verbinding, dan een eigen transactie voor het event.
 */
@Service
public class ConnectionTestService {

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_FAILED = "FAILED";
    /** Bovengrens van {@code matchedFiles} in het antwoord van de DC-test (design par. 5). */
    public static final int MAX_MATCHED_FILES = 100;

    public static final String CODE_PROFILE_VERSION_NOT_FOUND = DeliveryConfigurationService.CODE_PROFILE_VERSION_NOT_FOUND;
    public static final String CODE_DC_VERSION_NOT_FOUND = TaskDeliveryConfigurationService.CODE_VERSION_NOT_FOUND;

    static final int MAX_DETAIL_LENGTH = 500;
    static final int MAX_ACTOR_LENGTH = 100;

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionTestService.class);

    /** Antwoord van de scan; bij {@code FAILED} zijn algoritme en vingerafdruk {@code null}. */
    public record HostKeyScanView(String outcome, String failureCode, String hostKeyAlgorithm,
                                  String hostKeyFingerprintSha256) {
    }

    /** Eén bestand in het antwoord van de DC-test. */
    public record MatchedFileView(String name, long size, Instant modifiedAt) {
    }

    /**
     * Antwoord van beide tests (design par. 5). {@code presentedHostKeyFingerprint} is de vingerafdruk die de server
     * toonde (ook bij {@code SFTP_HOST_KEY_MISMATCH}), anders {@code null}. {@code matchedFiles} is {@code null} bij de
     * profieltest en bij een mislukte DC-test.
     */
    public record ConnectionTestView(String outcome, String failureCode, String presentedHostKeyFingerprint,
                                     List<MatchedFileView> matchedFiles, boolean truncated) {
    }

    /** Momentopname van een profielversie en haar credential; {@code toString} zonder ciphertext. */
    private record ProfileSnapshot(long versionId, String host, int port, String username, String hostKeyAlgorithm,
                                   String hostKeyFingerprint, UUID credentialRef, ExternalCredentialSecretKind secretKind,
                                   ExternalCredentialStatus credentialStatus, String ciphertext) {
        @Override
        public String toString() {
            return "ProfileSnapshot[versionId=" + versionId + ", credentialStatus=" + credentialStatus + "]";
        }
    }

    private record DeliveryConfigurationSnapshot(long versionId, ProfileSnapshot profile, String remoteDirectory,
                                                 RemoteFileSelection selection) {
    }

    /** Uitkomst van één poging, vóór ze een antwoord en een event wordt. */
    private record Attempt(String code, String detail, String presentedFingerprint, Listing listing) {

        boolean ok() {
            return FetchOutcomeCodes.OK.equals(code);
        }
    }

    private final FetchHostPolicy hostPolicy;
    private final SftpConnector connector;
    private final SecretsService secrets;
    private final ConnectionProfileVersionRepository profileVersions;
    private final DeliveryConfigurationVersionRepository deliveryConfigurationVersions;
    private final DeliveryConfigurationFileConditionRepository conditions;
    private final ExternalCredentialRepository credentials;
    private final AcquisitionConfigEventRepository events;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public ConnectionTestService(FetchHostPolicy hostPolicy, SftpConnector connector, SecretsService secrets,
                                 ConnectionProfileVersionRepository profileVersions,
                                 DeliveryConfigurationVersionRepository deliveryConfigurationVersions,
                                 DeliveryConfigurationFileConditionRepository conditions,
                                 ExternalCredentialRepository credentials, AcquisitionConfigEventRepository events,
                                 PlatformTransactionManager transactionManager, Clock clock) {
        this.hostPolicy = hostPolicy;
        this.connector = connector;
        this.secrets = secrets;
        this.profileVersions = profileVersions;
        this.deliveryConfigurationVersions = deliveryConfigurationVersions;
        this.conditions = conditions;
        this.credentials = credentials;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    // --- Scan ------------------------------------------------------------------------------------------------

    /**
     * Hostsleutelscan van {@code host:port} ({@code port} {@code null} = 22).
     *
     * @throws NotFoundException   404 {@code FETCH_NOT_CONFIGURED}
     * @throws BadRequestException 400 {@code CONNECTION_PROFILE_HOST_INVALID}, {@code CONNECTION_PROFILE_PORT_INVALID}
     * @throws ConflictException   409 {@code FETCH_HOST_NOT_ALLOWED} (met event)
     */
    public HostKeyScanView scanHostKey(String host, Integer port, ActorIdentity actor) {
        String by = requireActor(actor);
        // Eerst de aanvraag zelf (400), dan de omgeving (404): zelfde volgorde als de servermap (LocalSourceDisabledTest).
        String normalized = HostNames.normalize(host);
        if (normalized == null) {
            throw new BadRequestException(ConnectionProfileService.CODE_HOST_INVALID, "host is not a valid host name "
                    + "(not empty, at most " + HostNames.MAX_LENGTH + " characters, only ASCII letters, digits and "
                    + ". - _ : [ ])");
        }
        int effectivePort = port == null ? ConnectionProfileService.DEFAULT_PORT : port;
        if (effectivePort < 1 || effectivePort > 65535) {
            throw new BadRequestException(ConnectionProfileService.CODE_PORT_INVALID, "port must be between 1 and 65535");
        }
        hostPolicy.requireConfigured();
        String where = normalized + ":" + effectivePort;

        FetchTarget target;
        try {
            target = hostPolicy.resolve(normalized, effectivePort);
        } catch (FetchFailureException refused) {
            record(AcquisitionConfigEventKind.HOST_KEY_SCANNED, null, null, refused.getCode(),
                    "Host key scan of " + where + " refused: " + refused.getMessage(), by, actor);
            LOG.info("Host key scan by {}: {}", by, refused.getCode());
            if (FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED.equals(refused.getCode())) {
                throw new ConflictException(FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED, refused.getMessage());
            }
            return new HostKeyScanView(OUTCOME_FAILED, refused.getCode(), null, null);
        }
        try {
            HostKey key = connector.scanHostKey(target);
            record(AcquisitionConfigEventKind.HOST_KEY_SCANNED, null, null, FetchOutcomeCodes.OK,
                    "Host key scan of " + where + ": " + key.algorithm() + " " + key.fingerprintSha256(), by, actor);
            LOG.info("Host key scan by {}: {}", by, FetchOutcomeCodes.OK);
            return new HostKeyScanView(OUTCOME_OK, null, key.algorithm(), key.fingerprintSha256());
        } catch (FetchFailureException failed) {
            record(AcquisitionConfigEventKind.HOST_KEY_SCANNED, null, null, failed.getCode(),
                    "Host key scan of " + where + " failed: " + failed.getMessage(), by, actor);
            LOG.info("Host key scan by {}: {}", by, failed.getCode());
            return new HostKeyScanView(OUTCOME_FAILED, failed.getCode(), null, null);
        }
    }

    // --- Tests -----------------------------------------------------------------------------------------------

    /**
     * Test van een profielversie: verbinding, hostsleutel, aanmelding.
     *
     * @throws NotFoundException 404 {@code FETCH_NOT_CONFIGURED}, {@link #CODE_PROFILE_VERSION_NOT_FOUND}
     * @throws ConflictException 409 {@code SECRETS_NOT_CONFIGURED}
     */
    public ConnectionTestView testProfileVersion(long profileVersionId, ActorIdentity actor) {
        String by = requireActor(actor);
        hostPolicy.requireConfigured();
        ProfileSnapshot profile = readTransaction.execute(status -> profileSnapshot(profileVersions
                .findById(profileVersionId)
                .orElseThrow(() -> new NotFoundException(CODE_PROFILE_VERSION_NOT_FOUND,
                        "Connection profile version " + profileVersionId + " not found"))));
        requireSecretsConfigured();

        Attempt attempt = attempt(profile, null);
        record(AcquisitionConfigEventKind.CONNECTION_TESTED, null, profile.versionId(), attempt.code(),
                "Profile version test: " + attempt.detail(), by, actor);
        LOG.info("Connection test of profile version {} by {}: {}", profile.versionId(), by, attempt.code());
        return new ConnectionTestView(attempt.ok() ? OUTCOME_OK : OUTCOME_FAILED, attempt.ok() ? null : attempt.code(),
                attempt.presentedFingerprint(), null, false);
    }

    /**
     * Test van een Leveringsconfiguratie-versie: zoals de profieltest, plus map lijsten en voorwaarden toepassen.
     *
     * @throws NotFoundException 404 {@code FETCH_NOT_CONFIGURED}, {@link #CODE_DC_VERSION_NOT_FOUND}
     * @throws ConflictException 409 {@code SECRETS_NOT_CONFIGURED}
     */
    public ConnectionTestView testDeliveryConfigurationVersion(long versionId, ActorIdentity actor) {
        String by = requireActor(actor);
        hostPolicy.requireConfigured();
        DeliveryConfigurationSnapshot configuration = readTransaction.execute(status -> {
            DeliveryConfigurationVersion version = deliveryConfigurationVersions.findById(versionId)
                    .orElseThrow(() -> new NotFoundException(CODE_DC_VERSION_NOT_FOUND,
                            "Delivery configuration version " + versionId + " not found"));
            return new DeliveryConfigurationSnapshot(version.getId(), profileSnapshot(version.getConnectionProfileVersion()),
                    version.getRemoteDirectory(), selectionOf(version));
        });
        requireSecretsConfigured();

        Attempt attempt = attempt(configuration.profile(), configuration);
        record(AcquisitionConfigEventKind.CONNECTION_TESTED, configuration.versionId(),
                configuration.profile().versionId(), attempt.code(), "Delivery configuration version test: "
                        + attempt.detail(), by, actor);
        LOG.info("Connection test of delivery configuration version {} by {}: {}", configuration.versionId(), by,
                attempt.code());
        if (!attempt.ok()) {
            return new ConnectionTestView(OUTCOME_FAILED, attempt.code(), attempt.presentedFingerprint(), null, false);
        }
        // Nieuwste eerst, zonder tijd achteraan, bij gelijke tijd op naam (stabiel). Expliciet getypeerd voor javac.
        Comparator<RemoteFile> order = Comparator.comparing(RemoteFile::modifiedAt,
                Comparator.<Instant>nullsLast(Comparator.<Instant>reverseOrder()));
        order = order.thenComparing(RemoteFile::name);
        List<MatchedFileView> newestFirst = attempt.listing().matchedFiles().stream()
                .sorted(order)
                .map(file -> new MatchedFileView(file.name(), file.size(), file.modifiedAt()))
                .toList();
        boolean truncated = newestFirst.size() > MAX_MATCHED_FILES;
        return new ConnectionTestView(OUTCOME_OK, null, attempt.presentedFingerprint(),
                truncated ? List.copyOf(newestFirst.subList(0, MAX_MATCHED_FILES)) : newestFirst, truncated);
    }

    // --- Eén poging ------------------------------------------------------------------------------------------

    private Attempt attempt(ProfileSnapshot profile, DeliveryConfigurationSnapshot configuration) {
        if (!hostPolicy.isAllowed(profile.host())) {
            return new Attempt(FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED,
                    "the host is not in catalogimport.fetch.allowed-hosts of this environment", null, null);
        }
        if (profile.credentialStatus() != ExternalCredentialStatus.ACTIVE) {
            return new Attempt(FetchOutcomeCodes.CREDENTIAL_REVOKED, "the credential of this profile version is "
                    + "revoked; give it a new value first (nothing was contacted)", null, null);
        }
        PinnedHostKey pinned = new PinnedHostKey(profile.hostKeyAlgorithm(), profile.hostKeyFingerprint());
        // Pas aangeroepen na een geslaagde hostsleutelcontrole; de waarde leeft enkel in deze sessie.
        PasswordSource password = () -> secrets.decrypt(profile.ciphertext(), profile.credentialRef(),
                profile.secretKind().name());
        try {
            FetchTarget target = hostPolicy.resolve(profile.host(), profile.port());
            if (configuration == null) {
                HostKey key = connector.verifyLogin(target, profile.username(), pinned, password);
                return new Attempt(FetchOutcomeCodes.OK, "connection, host key and login OK", key.fingerprintSha256(),
                        null);
            }
            Listing listing = connector.listDirectory(target, profile.username(), pinned, password,
                    configuration.remoteDirectory(), configuration.selection());
            return new Attempt(FetchOutcomeCodes.OK, "connection, host key and login OK; " + listing.entryCount()
                    + " entries listed, " + listing.matchedFiles().size() + " matching",
                    listing.presentedHostKeyFingerprint(), listing);
        } catch (FetchFailureException failed) {
            String detail = failed.getMessage();
            if (FetchOutcomeCodes.SFTP_HOST_KEY_MISMATCH.equals(failed.getCode())) {
                detail = detail + ". Presented " + failed.getPresentedHostKeyFingerprint() + ", pinned "
                        + profile.hostKeyAlgorithm() + " " + profile.hostKeyFingerprint();
            }
            return new Attempt(failed.getCode(), detail, failed.getPresentedHostKeyFingerprint(), null);
        }
    }

    // --- Hulp ------------------------------------------------------------------------------------------------

    /** Binnen een (lees)transactie: de credential wordt apart geladen (de versie kent enkel haar id). */
    private ProfileSnapshot profileSnapshot(ConnectionProfileVersion version) {
        ExternalCredential credential = credentials.findById(version.getCredentialId())
                .orElseThrow(() -> new IllegalStateException("The credential of connection profile version "
                        + version.getId() + " is missing"));
        return new ProfileSnapshot(version.getId(), version.getHost(), version.getPort(), version.getUsername(),
                version.getHostKeyAlgorithm(), version.getHostKeyFingerprintSha256(), credential.getCredentialRef(),
                credential.getSecretKind(), credential.getStatus(), credential.getCiphertext());
    }

    private RemoteFileSelection selectionOf(DeliveryConfigurationVersion version) {
        return selectionOf(version, conditions);
    }

    /**
     * De bestandsselectie van een DC-versie (EN binnen een groep, OF tussen groepen; {@code ALL_FILES} = alles). Gedeeld
     * met de ophaalrun (K-4b), zodat test en ophaling exact dezelfde bestanden kiezen. Binnen een transactie aanroepen.
     */
    static RemoteFileSelection selectionOf(DeliveryConfigurationVersion version,
                                           DeliveryConfigurationFileConditionRepository conditions) {
        if (version.getSelectionMode() == DeliverySelectionMode.ALL_FILES) {
            return RemoteFileSelection.allFiles();
        }
        List<DeliveryConfigurationFileCondition> rows = conditions
                .findByDeliveryConfigurationVersionIdOrderByGroupNumberAscSequenceNumberAsc(version.getId());
        if (rows.isEmpty()) {
            // LC-2 laat dit niet toe; zonder voorwaarden zou een test stil niets (of alles) tonen.
            throw new IllegalStateException("Delivery configuration version " + version.getId()
                    + " has selection mode CONDITIONS but no conditions");
        }
        return RemoteFileSelection.conditions(rows.stream()
                .map(row -> new RemoteFileSelection.Condition(row.getGroupNumber(), row.getConditionKind(),
                        row.getCompareValue(), row.isCaseSensitive()))
                .toList());
    }

    private void record(AcquisitionConfigEventKind kind, Long deliveryConfigurationVersionId, Long profileVersionId,
                        String outcomeCode, String detail, String by, ActorIdentity actor) {
        transaction.executeWithoutResult(status -> {
            DeliveryConfigurationVersion configuration = deliveryConfigurationVersionId == null ? null
                    : deliveryConfigurationVersions.getReferenceById(deliveryConfigurationVersionId);
            ConnectionProfileVersion profile = profileVersionId == null ? null
                    : profileVersions.getReferenceById(profileVersionId);
            events.saveAndFlush(new AcquisitionConfigEvent(kind, null, configuration, profile, outcomeCode,
                    limit(detail), null, AcquisitionConfigEventSource.HUMAN, by, actor.subject(), now()));
        });
    }

    private void requireSecretsConfigured() {
        if (!secrets.configured()) {
            throw new SecretsNotConfiguredException();
        }
    }

    /** Nooit stil fout: een te lange detailtekst wordt zichtbaar ingekort (enkel audittekst, geen businessgegeven). */
    static String limit(String detail) {
        if (detail == null || detail.length() <= MAX_DETAIL_LENGTH) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL_LENGTH - 3) + "...";
    }

    private static String requireActor(ActorIdentity actor) {
        if (actor == null) {
            throw new IllegalArgumentException("Missing actor");
        }
        return ActorNames.requireActorName(actor.username(), "actor", MAX_ACTOR_LENGTH);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
