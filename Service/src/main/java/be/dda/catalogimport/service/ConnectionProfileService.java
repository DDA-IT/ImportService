package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ConnectionProfileRepository;
import be.dda.catalogimport.dao.ConnectionProfileVersionRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.domain.ConnectionAuthMethod;
import be.dda.catalogimport.domain.ConnectionProfile;
import be.dda.catalogimport.domain.ConnectionProfileVersion;
import be.dda.catalogimport.domain.ConnectionProtocol;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.domain.ExternalCredentialStatus;
import be.dda.catalogimport.service.support.HostNames;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verbindingsprofielen (SFTP): bouwstap LC-2 van {@code docs/design/leveringsconfiguratie-design.md} par. 3.2, 6 en
 * 10; beslissingslog 2026-09-29 (L3, L4a, L5, L7, A5, A19).
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Aanmaken</b> = kop ({@code connection_profile}) + onveranderlijke versie 1 (L3). Opvolgversies volgen later
 *       (design par. 6).</li>
 *   <li><b>Host</b> wordt genormaliseerd met {@link HostNames} - exact dezelfde normalisatie als de
 *       {@code bound_host} van de credential, anders past de samengestelde FK niet (L4a).</li>
 *   <li><b>Credential</b> (via {@code credentialRef}) moet bestaan (404 {@code CREDENTIAL_NOT_FOUND}), {@code ACTIVE}
 *       zijn, aan <b>dezelfde</b> genormaliseerde host gebonden zijn en van de soort zijn die bij de
 *       authenticatiemethode past (409 met een eigen code). Een credential van een andere host wordt nooit gebruikt:
 *       dat zou het wachtwoord naar een andere server sturen (Ontdekking 2).</li>
 *   <li><b>Authenticatie:</b> v1 kent enkel {@code PASSWORD}; {@code PRIVATE_KEY} geeft 400
 *       {@link #CODE_AUTH_METHOD_NOT_SUPPORTED} (A5).</li>
 *   <li><b>Vastgepinde hostsleutel</b> (L5): algoritme uit {@link #HOST_KEY_ALGORITHMS} en de SHA-256-vingerafdruk
 *       in de OpenSSH-vorm {@code SHA256:<43 tekens base64 zonder opvulling>} (32 bytes; {@code ssh-keygen -lf},
 *       {@code ssh-keyscan | ssh-keygen -lf -}). De mens geeft ze in LC-2 zelf mee; de scan volgt in K-4a.</li>
 *   <li><b>{@code config_hash}</b>: zie {@link #configHash}. Code, naam en reden tellen niet mee.</li>
 *   <li>Een aanmaak schrijft <b>geen</b> {@code acquisition_config_event}: de toegelaten soorten
 *       ({@code TASK_BOUND}, {@code TASK_UNBOUND}, {@code CONNECTION_TESTED}, {@code HOST_KEY_SCANNED},
 *       {@code RETIRED}) kennen geen "aangemaakt". De aanmaak staat in de versierij zelf ({@code created_*},
 *       {@code change_reason}).</li>
 * </ul>
 *
 * <h2>Foutvolgorde binnen de service</h2>
 * Eerst alle 400's (invoer), dan 404 (credential), dan 409 (credentialtoestand, dubbele code). De 403 van het recht
 * komt er in de Web-laag vóór.
 *
 * <h2>Wat nooit naar buiten gaat</h2>
 * Nooit een secret, ciphertext of sleutel-ID: een profielversie draagt enkel een verwijzing naar de credential. De
 * antwoorden bevatten wel host, login en vingerafdruk en zijn daarom enkel voor {@code MANAGE} (L7b).
 */
@Service
public class ConnectionProfileService {

    /** Code ontbreekt, is te lang (&gt; 50) of bevat een stuurteken; idem voor naam en login hieronder. */
    public static final String CODE_CODE_INVALID = "CONNECTION_PROFILE_CODE_INVALID";
    public static final String CODE_NAME_INVALID = "CONNECTION_PROFILE_NAME_INVALID";
    public static final String CODE_HOST_INVALID = "CONNECTION_PROFILE_HOST_INVALID";
    public static final String CODE_PORT_INVALID = "CONNECTION_PROFILE_PORT_INVALID";
    public static final String CODE_USERNAME_INVALID = "CONNECTION_PROFILE_USERNAME_INVALID";
    public static final String CODE_AUTH_METHOD_INVALID = "CONNECTION_PROFILE_AUTH_METHOD_INVALID";
    /** {@code PRIVATE_KEY} is gereserveerd: v1 kent enkel wachtwoorden (A5). */
    public static final String CODE_AUTH_METHOD_NOT_SUPPORTED = "CONNECTION_PROFILE_AUTH_METHOD_NOT_SUPPORTED";
    public static final String CODE_CREDENTIAL_REQUIRED = "CONNECTION_PROFILE_CREDENTIAL_REQUIRED";
    public static final String CODE_HOST_KEY_ALGORITHM_INVALID = "CONNECTION_PROFILE_HOST_KEY_ALGORITHM_INVALID";
    public static final String CODE_HOST_KEY_FINGERPRINT_INVALID = "CONNECTION_PROFILE_HOST_KEY_FINGERPRINT_INVALID";
    public static final String CODE_REASON_REQUIRED = "CONNECTION_PROFILE_REASON_REQUIRED";
    public static final String CODE_CREDENTIAL_NOT_ACTIVE = "CONNECTION_PROFILE_CREDENTIAL_NOT_ACTIVE";
    public static final String CODE_CREDENTIAL_HOST_MISMATCH = "CONNECTION_PROFILE_CREDENTIAL_HOST_MISMATCH";
    public static final String CODE_CREDENTIAL_KIND_MISMATCH = "CONNECTION_PROFILE_CREDENTIAL_KIND_MISMATCH";
    public static final String CODE_CODE_EXISTS = "CONNECTION_PROFILE_CODE_EXISTS";
    public static final String CODE_NOT_FOUND = "CONNECTION_PROFILE_NOT_FOUND";

    /** Standaardpoort van SFTP (changeset 014-2, default 22). */
    public static final int DEFAULT_PORT = 22;

    /**
     * Toegelaten hostsleutelalgoritmen (vaste lijst, exacte schrijfwijze zoals OpenSSH ze noemt). Bewust zonder
     * {@code ssh-rsa} en {@code ssh-dss}.
     */
    public static final Set<String> HOST_KEY_ALGORITHMS = Set.of("ssh-ed25519", "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "rsa-sha2-256", "rsa-sha2-512");

    /** Versie-/laagaanduiding van de canonieke {@code config_hash}-vorm; wijzigt enkel met een nieuwe vorm. */
    static final String HASH_LAYER = "connection_profile_version/v1";

    private static final String FINGERPRINT_PREFIX = "SHA256:";
    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");
    private static final String UNIQUE_CODE_CONSTRAINT = "uk_connection_profile_code";

    static final int MAX_CODE_LENGTH = 50;
    static final int MAX_NAME_LENGTH = 200;
    static final int MAX_USERNAME_LENGTH = 200;
    static final int MAX_REASON_LENGTH = 500;
    static final int MAX_ACTOR_LENGTH = 100;

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionProfileService.class);

    /** Eén regel van de lijst: enkel kopgegevens en de laatste versie, geen host/login/vingerafdruk. */
    public record ConnectionProfileSummary(long id, String code, String name, String protocol, boolean active,
                                           Long latestVersionId, Integer latestVersionNumber, Instant createdAt,
                                           String createdBy) {
    }

    /** Eén onveranderlijke versie; nooit een secret, ciphertext of sleutel-ID. */
    public record ConnectionProfileVersionView(long id, int versionNumber, String host, int port, String username,
                                               String authMethod, UUID credentialRef, String credentialLabel,
                                               String credentialStatus, String hostKeyAlgorithm,
                                               String hostKeyFingerprintSha256, Long basedOnVersionId,
                                               String changeReason, String configHash, Instant createdAt,
                                               String createdBy) {
    }

    /** Kop met alle versies, nieuwste eerst. */
    public record ConnectionProfileDetail(long id, String code, String name, String protocol, boolean active,
                                          Instant createdAt, String createdBy, Instant updatedAt,
                                          List<ConnectionProfileVersionView> versions) {
    }

    /** Invoer van {@link #create}; {@code port} {@code null} = {@value #DEFAULT_PORT}. */
    public record NewConnectionProfile(String code, String name, String host, Integer port, String username,
                                       String authMethod, String credentialRef, String hostKeyAlgorithm,
                                       String hostKeyFingerprintSha256, String reason) {
    }

    private final ConnectionProfileRepository profiles;
    private final ConnectionProfileVersionRepository versions;
    private final ExternalCredentialRepository credentials;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public ConnectionProfileService(ConnectionProfileRepository profiles, ConnectionProfileVersionRepository versions,
                                    ExternalCredentialRepository credentials,
                                    PlatformTransactionManager transactionManager, Clock clock) {
        this.profiles = profiles;
        this.versions = versions;
        this.credentials = credentials;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    // --- Schrijven -------------------------------------------------------------------------------------------

    /**
     * Maakt een profiel met versie 1.
     *
     * @throws BadRequestException 400 invoer ongeldig (codes hierboven)
     * @throws NotFoundException   404 {@code CREDENTIAL_NOT_FOUND}
     * @throws ConflictException   409 credential niet actief, andere host of verkeerde soort; code bestaat al
     */
    public ConnectionProfileDetail create(NewConnectionProfile input, ActorIdentity actor) {
        String by = requireActor(actor);
        if (input == null) {
            throw new BadRequestException(CODE_CODE_INVALID, "Missing request body");
        }
        String code = AcquisitionConfigInput.requireText(input.code(), "code", MAX_CODE_LENGTH, CODE_CODE_INVALID);
        String name = AcquisitionConfigInput.requireText(input.name(), "name", MAX_NAME_LENGTH, CODE_NAME_INVALID);
        String host = requireHost(input.host());
        int port = requirePort(input.port());
        String username = AcquisitionConfigInput.requireText(input.username(), "username", MAX_USERNAME_LENGTH,
                CODE_USERNAME_INVALID);
        ConnectionAuthMethod authMethod = requireAuthMethod(input.authMethod());
        UUID credentialRef = requireCredentialRef(input.credentialRef());
        String algorithm = requireAlgorithm(input.hostKeyAlgorithm());
        String fingerprint = requireFingerprint(input.hostKeyFingerprintSha256());
        String reason = AcquisitionConfigInput.requireText(input.reason(), "reason", MAX_REASON_LENGTH,
                CODE_REASON_REQUIRED);

        return transaction.execute(status -> {
            ExternalCredential credential = credentials.findByCredentialRef(credentialRef)
                    .orElseThrow(() -> credentialNotFound());
            if (credential.getStatus() != ExternalCredentialStatus.ACTIVE) {
                throw new ConflictException(CODE_CREDENTIAL_NOT_ACTIVE,
                        "Credential " + credentialRef + " is not ACTIVE; give it a new value first or choose another");
            }
            if (!credential.getBoundHost().equals(host)) {
                throw new ConflictException(CODE_CREDENTIAL_HOST_MISMATCH, "Credential " + credentialRef
                        + " is bound to another host than this profile; a credential is only ever used for its own host");
            }
            if (credential.getSecretKind() != expectedKind(authMethod)) {
                throw new ConflictException(CODE_CREDENTIAL_KIND_MISMATCH, "Credential " + credentialRef
                        + " is of kind " + credential.getSecretKind() + ", authMethod " + authMethod + " needs "
                        + expectedKind(authMethod));
            }
            if (profiles.findByCode(code).isPresent()) {
                throw codeExists(code);
            }
            Instant now = now();
            String hash = configHash(host, port, username, authMethod, credentialRef, algorithm, fingerprint);
            ConnectionProfile profile;
            ConnectionProfileVersion version;
            try {
                profile = profiles.saveAndFlush(new ConnectionProfile(code, name, ConnectionProtocol.SFTP, by,
                        actor.subject(), now));
                version = versions.saveAndFlush(new ConnectionProfileVersion(profile, 1, host, port, username,
                        authMethod, credential, algorithm, fingerprint, null, reason, hash, by, actor.subject(), now));
            } catch (DataIntegrityViolationException violation) {
                if (violates(violation, UNIQUE_CODE_CONSTRAINT)) {
                    throw codeExists(code);
                }
                throw violation;
            }
            LOG.info("Connection profile {} (id {}) version 1 created by {}", code, profile.getId(), by);
            return detail(profile, List.of(version));
        });
    }

    // --- Lezen -----------------------------------------------------------------------------------------------

    public List<ConnectionProfileSummary> list() {
        return readTransaction.execute(status -> {
            Map<Long, ConnectionProfileVersion> latest = versions.findLatestVersions().stream()
                    .collect(Collectors.toMap(v -> v.getConnectionProfile().getId(), Function.identity()));
            return profiles.findAllByOrderByCodeAscIdAsc().stream().map(profile -> {
                ConnectionProfileVersion version = latest.get(profile.getId());
                return new ConnectionProfileSummary(profile.getId(), profile.getCode(), profile.getName(),
                        profile.getProtocol().name(), profile.isActive(), version == null ? null : version.getId(),
                        version == null ? null : version.getVersionNumber(), profile.getCreatedAt(),
                        profile.getCreatedBy());
            }).toList();
        });
    }

    /** @throws NotFoundException 404 {@link #CODE_NOT_FOUND} */
    public ConnectionProfileDetail get(long id) {
        return readTransaction.execute(status -> {
            ConnectionProfile profile = profiles.findById(id)
                    .orElseThrow(() -> new NotFoundException(CODE_NOT_FOUND, "Connection profile " + id + " not found"));
            return detail(profile, versions.findByConnectionProfileIdOrderByVersionNumberDesc(profile.getId()));
        });
    }

    // --- config_hash ------------------------------------------------------------------------------------------

    /**
     * De functionele configuratiehash van een profielversie (hex, 64 tekens). <b>Canonieke vorm (v1):</b>
     * {@code RevisionConfigHashes.hash("connection_profile_version/v1", host, port, username, authMethod,
     * credentialRef, hostKeyAlgorithm, hostKeyFingerprintSha256)}, dus SHA-256 over de UTF-8-bytes van
     * {@code connection_profile_version/v1} gevolgd door elk onderdeel, telkens voorafgegaan door {@code U+001F}.
     * Waarden zoals opgeslagen: host genormaliseerd, poort decimaal, login en vingerafdruk getrimd, methode als
     * enumnaam, {@code credentialRef} als UUID in kleine letters. Geen onderdeel kan {@code U+001F} bevatten (stuurtekens
     * worden geweigerd), dus de vorm is eenduidig. Code, naam, reden en actor tellen niet mee.
     */
    public static String configHash(String host, int port, String username, ConnectionAuthMethod authMethod,
                                    UUID credentialRef, String hostKeyAlgorithm, String hostKeyFingerprintSha256) {
        return RevisionConfigHashes.hash(HASH_LAYER, host, Integer.toString(port), username, authMethod.name(),
                credentialRef.toString(), hostKeyAlgorithm, hostKeyFingerprintSha256);
    }

    // --- Hulp ------------------------------------------------------------------------------------------------

    private ConnectionProfileDetail detail(ConnectionProfile profile, List<ConnectionProfileVersion> rows) {
        Map<Long, ExternalCredential> byId = credentials.findAllById(rows.stream()
                        .map(ConnectionProfileVersion::getCredentialId).distinct().toList()).stream()
                .collect(Collectors.toMap(ExternalCredential::getId, Function.identity()));
        List<ConnectionProfileVersionView> views = rows.stream()
                .map(v -> versionView(v, byId.get(v.getCredentialId()))).toList();
        return new ConnectionProfileDetail(profile.getId(), profile.getCode(), profile.getName(),
                profile.getProtocol().name(), profile.isActive(), profile.getCreatedAt(), profile.getCreatedBy(),
                profile.getUpdatedAt(), views);
    }

    private static ConnectionProfileVersionView versionView(ConnectionProfileVersion v, ExternalCredential credential) {
        return new ConnectionProfileVersionView(v.getId(), v.getVersionNumber(), v.getHost(), v.getPort(),
                v.getUsername(), v.getAuthMethod().name(), credential == null ? null : credential.getCredentialRef(),
                credential == null ? null : credential.getLabel(),
                credential == null ? null : credential.getStatus().name(), v.getHostKeyAlgorithm(),
                v.getHostKeyFingerprintSha256(), v.getBasedOnVersion() == null ? null : v.getBasedOnVersion().getId(),
                v.getChangeReason(), v.getConfigHash(), v.getCreatedAt(), v.getCreatedBy());
    }

    private static ExternalCredentialSecretKind expectedKind(ConnectionAuthMethod authMethod) {
        return authMethod == ConnectionAuthMethod.PRIVATE_KEY
                ? ExternalCredentialSecretKind.SSH_PRIVATE_KEY : ExternalCredentialSecretKind.SFTP_PASSWORD;
    }

    private static NotFoundException credentialNotFound() {
        return new NotFoundException(CredentialService.CODE_CREDENTIAL_NOT_FOUND, "Credential not found");
    }

    private static ConflictException codeExists(String code) {
        return new ConflictException(CODE_CODE_EXISTS, "A connection profile with code '" + code + "' already exists");
    }

    static boolean violates(DataIntegrityViolationException violation, String constraint) {
        String message = NestedExceptionUtils.getMostSpecificCause(violation).getMessage();
        return message != null && message.contains(constraint);
    }

    private static String requireHost(String raw) {
        String host = HostNames.normalize(raw);
        if (host == null) {
            throw new BadRequestException(CODE_HOST_INVALID, "host is not a valid host name (not empty, at most "
                    + HostNames.MAX_LENGTH + " characters, only ASCII letters, digits and . - _ : [ ])");
        }
        return host;
    }

    private static int requirePort(Integer port) {
        if (port == null) {
            return DEFAULT_PORT;
        }
        if (port < 1 || port > 65535) {
            throw new BadRequestException(CODE_PORT_INVALID, "port must be between 1 and 65535");
        }
        return port;
    }

    private static ConnectionAuthMethod requireAuthMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException(CODE_AUTH_METHOD_INVALID, "Missing authMethod; allowed: PASSWORD");
        }
        ConnectionAuthMethod method;
        try {
            method = ConnectionAuthMethod.valueOf(raw.strip());
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException(CODE_AUTH_METHOD_INVALID, "Unknown authMethod; allowed: PASSWORD");
        }
        if (method != ConnectionAuthMethod.PASSWORD) {
            throw new BadRequestException(CODE_AUTH_METHOD_NOT_SUPPORTED,
                    "authMethod " + method + " is reserved and not supported yet; use PASSWORD");
        }
        return method;
    }

    /** Ontbreekt = 400; geen UUID = 404, net als een onbekende UUID (een ongeldige ref kan niet bestaan). */
    private static UUID requireCredentialRef(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException(CODE_CREDENTIAL_REQUIRED, "Missing credentialRef");
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException notAUuid) {
            throw credentialNotFound();
        }
    }

    private static String requireAlgorithm(String raw) {
        String algorithm = raw == null ? "" : raw.strip();
        if (!HOST_KEY_ALGORITHMS.contains(algorithm)) {
            throw new BadRequestException(CODE_HOST_KEY_ALGORITHM_INVALID, "hostKeyAlgorithm must be one of "
                    + HOST_KEY_ALGORITHMS.stream().sorted().toList());
        }
        return algorithm;
    }

    /**
     * OpenSSH-vorm {@code SHA256:<base64 zonder opvulling>} van exact 32 bytes. De base64 moet canoniek zijn (de
     * opnieuw gecodeerde bytes zijn gelijk aan de invoer); er wordt niets aangepast behalve trimmen aan de rand.
     */
    static String requireFingerprint(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!FINGERPRINT.matcher(value).matches()) {
            throw invalidFingerprint();
        }
        String encoded = value.substring(FINGERPRINT_PREFIX.length());
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded + "=");
        } catch (IllegalArgumentException notBase64) {
            throw invalidFingerprint();
        }
        if (bytes.length != 32 || !Base64.getEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
            throw invalidFingerprint();
        }
        return value;
    }

    private static BadRequestException invalidFingerprint() {
        return new BadRequestException(CODE_HOST_KEY_FINGERPRINT_INVALID, "hostKeyFingerprintSha256 must be in the "
                + "OpenSSH form SHA256:<43 base64 characters without padding> (a SHA-256 of 32 bytes)");
    }

    private static String requireActor(ActorIdentity actor) {
        if (actor == null) {
            throw new IllegalArgumentException("Missing actor");
        }
        return ActorNames.requireActorName(actor.username(), "actor", MAX_ACTOR_LENGTH);
    }

    /** Microseconden, zoals PostgreSQL bewaart: het antwoord van een schrijfactie is gelijk aan een latere GET. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
