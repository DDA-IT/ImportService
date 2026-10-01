package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ConnectionProfileVersionRepository;
import be.dda.catalogimport.dao.ExternalCredentialEventRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialEvent;
import be.dda.catalogimport.domain.ExternalCredentialEventKind;
import be.dda.catalogimport.domain.ExternalCredentialEventSource;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.domain.ExternalCredentialStatus;
import be.dda.catalogimport.service.support.HostNames;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Beheer van versleutelde credentials van externe bronnen: bouwstap K-3 van
 * {@code docs/design/leveringsconfiguratie-design.md} par. 6/10 en {@code docs/design/credentials-sleutelbeheer-design.md}
 * par. 5-6; beslissingslog 2026-09-29 (V1, V2, V7, L4a, L7, A5, A8, K-2a-nazorg (b) en "K-3: een ingetrokken
 * credential mag heractiveerd worden").
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Aanmaken:</b> {@code credential_ref} is een server-side UUID (A19); {@code bound_host} wordt genormaliseerd
 *       ({@link HostNames}); de waarde wordt versleuteld met de actieve sleutel vóór de entiteit bestaat; één
 *       {@code CREATED}-event. {@code secret_updated_*} = de aanmaker.</li>
 *   <li><b>Vervangen</b> ({@code ACTIVE}): nieuwe ciphertext met de actieve sleutel, {@code REPLACED}-event met
 *       {@code previous_key_id} en {@code new_key_id}.</li>
 *   <li><b>Heractiveren</b> ({@code REVOKED}, zelfde endpoint als vervangen): status terug {@code ACTIVE},
 *       {@code revoked_*} leeg, {@code REPLACED}-event met {@code previous_key_id = null} (de oude ciphertext is
 *       gewist). {@code credential_ref}, {@code secret_kind} en {@code bound_host} veranderen nooit.</li>
 *   <li><b>Intrekken:</b> crypto-shred (ciphertext en sleutel-ID {@code null}), status {@code REVOKED},
 *       {@code revoked_*} met verplichte reden, {@code REVOKED}-event met het gewiste sleutel-ID als
 *       {@code previous_key_id}. Altijd toegestaan (A8), ook zonder sleutelconfiguratie: er wordt niets versleuteld.
 *       Een tweede intrekking is 409 {@link #CODE_ALREADY_REVOKED} zonder nieuw event (conventie
 *       {@code BUNDLE_NOT_CANCELLABLE} bij een tweede annulering).</li>
 *   <li>Elke schrijfactie vraagt een niet-lege {@code reason}; de actor komt altijd uit het token.</li>
 *   <li><b>Geen sleutelconfiguratie</b> (V7): aanmaken en vervangen/heractiveren geven 409
 *       {@code SECRETS_NOT_CONFIGURED}; lezen en intrekken werken.</li>
 * </ul>
 *
 * <h2>Wat nooit naar buiten gaat (V1)</h2>
 * Geen secret, geen ciphertext, geen {@code encryption_key_id} (ook niet de sleutel-ID's van events) en geen subject:
 * de views tonen hoogstens {@code secretSet}, {@code secretUpdatedAt} en {@code secretUpdatedBy}. Geen foutmelding en
 * geen logregel van deze klasse noemt de waarde; ook de vrije-tekstreden wordt niet gelogd (zie
 * {@code ExternalCredentialEvent#toString}).
 *
 * <h2>Transacties en gelijktijdigheid</h2>
 * Patroon {@link IssueCaseService}: één {@link TransactionTemplate} per schrijfactie, met
 * {@link ExternalCredentialRepository#findByCredentialRefForUpdate} als serialisatiepunt. Foutvolgorde binnen de
 * service: eerst 400 (invoer), dan 404 (onbekende ref), dan 409; de 403 van het recht komt er in de Web-laag vóór.
 */
@Service
public class CredentialService {

    public static final String CODE_CREDENTIAL_NOT_FOUND = "CREDENTIAL_NOT_FOUND";
    public static final String CODE_LABEL_REQUIRED = "CREDENTIAL_LABEL_REQUIRED";
    public static final String CODE_SECRET_KIND_INVALID = "CREDENTIAL_SECRET_KIND_INVALID";
    /** {@code SSH_PRIVATE_KEY} is gereserveerd: v1 kent enkel wachtwoorden (A5). */
    public static final String CODE_SECRET_KIND_NOT_SUPPORTED = "CREDENTIAL_SECRET_KIND_NOT_SUPPORTED";
    public static final String CODE_HOST_INVALID = "CREDENTIAL_HOST_INVALID";
    public static final String CODE_SECRET_REQUIRED = "CREDENTIAL_SECRET_REQUIRED";
    public static final String CODE_SECRET_TOO_LONG = "CREDENTIAL_SECRET_TOO_LONG";
    public static final String CODE_REASON_REQUIRED = "CREDENTIAL_REASON_REQUIRED";
    public static final String CODE_ALREADY_REVOKED = "CREDENTIAL_ALREADY_REVOKED";

    /** {@code external_credential.label}: varchar(200). */
    static final int MAX_LABEL_LENGTH = 200;
    /** {@code revoked_reason} en {@code external_credential_event.reason}: varchar(500). */
    static final int MAX_REASON_LENGTH = 500;
    /** {@code *_by}: varchar(100). */
    static final int MAX_ACTOR_LENGTH = 100;
    /** Bovengrens voor een wachtwoord; nooit afgekapt, een langere waarde wordt geweigerd. */
    public static final int MAX_SECRET_LENGTH = 1024;

    private static final Logger LOG = LoggerFactory.getLogger(CredentialService.class);

    /**
     * Wat een API over een credential mag tonen (V1, L7b, design par. 6). Bewust zonder ciphertext, sleutel-ID,
     * subject en aanmaak-/intrekkingsdetails. {@code profileVersionCount} is het gebruik: het aantal
     * verbindingsprofielversies dat naar deze credential verwijst (additief in LC-2, K-3 afwijking 2; A8: ook na
     * intrekken zichtbaar, zodat de gevolgen van een intrekking leesbaar zijn).
     */
    public record CredentialView(UUID credentialRef, String label, String secretKind, String boundHost, String status,
                                 boolean secretSet, Instant secretUpdatedAt, String secretUpdatedBy,
                                 long profileVersionCount) {

        static CredentialView of(ExternalCredential credential, long profileVersionCount) {
            return new CredentialView(credential.getCredentialRef(), credential.getLabel(),
                    credential.getSecretKind().name(), credential.getBoundHost(), credential.getStatus().name(),
                    credential.isSecretSet(), credential.getSecretUpdatedAt(), credential.getSecretUpdatedBy(),
                    profileVersionCount);
        }
    }

    /** Eén auditregel, oplopend op {@code id}; zonder sleutel-ID's en zonder subject. */
    public record CredentialEventView(long id, String eventKind, String reason, String source, String changedBy,
                                      Instant changedAt) {

        static CredentialEventView of(ExternalCredentialEvent event) {
            return new CredentialEventView(event.getId(), event.getEventKind().name(), event.getReason(),
                    event.getSource().name(), event.getChangedBy(), event.getChangedAt());
        }
    }

    private final SecretsService secrets;
    private final ExternalCredentialRepository credentials;
    private final ExternalCredentialEventRepository events;
    private final ConnectionProfileVersionRepository profileVersions;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public CredentialService(SecretsService secrets, ExternalCredentialRepository credentials,
                             ExternalCredentialEventRepository events,
                             ConnectionProfileVersionRepository profileVersions,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.secrets = secrets;
        this.credentials = credentials;
        this.events = events;
        this.profileVersions = profileVersions;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    // --- Schrijven -------------------------------------------------------------------------------------------

    /**
     * Maakt een credential aan.
     *
     * @throws BadRequestException           400 label, soort, host, waarde of reden ongeldig
     * @throws IllegalArgumentException      400 te lange label/reden, ongeldige actor
     * @throws SecretsNotConfiguredException 409 geen sleutelring
     */
    public CredentialView create(String label, String secretKind, String boundHost, String secret, String reason,
                                 ActorIdentity actor) {
        String by = requireActor(actor);
        String name = requireLabel(label);
        ExternalCredentialSecretKind kind = requireKind(secretKind);
        String host = requireHost(boundHost);
        requireSecret(secret);
        String motivation = requireReason(reason);
        requireConfigured();

        UUID ref = UUID.randomUUID();
        String ciphertext = secrets.encrypt(secret, ref, kind.name());
        String keyId = keyIdOf(ciphertext);
        return transaction.execute(status -> {
            Instant now = now();
            ExternalCredential saved = credentials.saveAndFlush(new ExternalCredential(ref, name, kind, host,
                    ciphertext, keyId, by, actor.subject(), now));
            events.saveAndFlush(new ExternalCredentialEvent(saved, ExternalCredentialEventKind.CREATED, motivation,
                    ExternalCredentialEventSource.HUMAN, by, actor.subject(), now, null, keyId));
            LOG.info("Credential {} ({}) created by {}", ref, kind, by);
            return CredentialView.of(saved, 0);
        });
    }

    /**
     * Vervangt de waarde ({@code ACTIVE}) of heractiveert een ingetrokken credential ({@code REVOKED}); beide met
     * een {@code REPLACED}-event.
     *
     * @throws BadRequestException           400 waarde of reden ongeldig
     * @throws NotFoundException             404 {@link #CODE_CREDENTIAL_NOT_FOUND}
     * @throws SecretsNotConfiguredException 409 geen sleutelring
     */
    public CredentialView replaceSecret(String credentialRef, String secret, String reason, ActorIdentity actor) {
        String by = requireActor(actor);
        requireSecret(secret);
        String motivation = requireReason(reason);
        UUID ref = parseRef(credentialRef);
        requireConfigured();

        return transaction.execute(status -> {
            ExternalCredential credential = lockedCredential(ref);
            String kind = credential.getSecretKind().name();
            String ciphertext = secrets.encrypt(secret, credential.getCredentialRef(), kind);
            String keyId = keyIdOf(ciphertext);
            Instant now = now();
            String previousKeyId;
            boolean reactivated = credential.getStatus() == ExternalCredentialStatus.REVOKED;
            if (reactivated) {
                previousKeyId = null;
                credential.recordReactivation(ciphertext, keyId, by, actor.subject(), now);
            } else {
                previousKeyId = credential.getEncryptionKeyId();
                credential.recordReplacement(ciphertext, keyId, by, actor.subject(), now);
            }
            ExternalCredential saved = credentials.saveAndFlush(credential);
            events.saveAndFlush(new ExternalCredentialEvent(saved, ExternalCredentialEventKind.REPLACED, motivation,
                    ExternalCredentialEventSource.HUMAN, by, actor.subject(), now, previousKeyId, keyId));
            LOG.info("Credential {} {} by {}", ref, reactivated ? "reactivated with a new value" : "value replaced",
                    by);
            return view(saved);
        });
    }

    /**
     * Trekt een credential in (crypto-shred). Werkt ook zonder sleutelconfiguratie (A8).
     *
     * @throws BadRequestException 400 reden ontbreekt
     * @throws NotFoundException   404 {@link #CODE_CREDENTIAL_NOT_FOUND}
     * @throws ConflictException   409 {@link #CODE_ALREADY_REVOKED}
     */
    public CredentialView revoke(String credentialRef, String reason, ActorIdentity actor) {
        String by = requireActor(actor);
        String motivation = requireReason(reason);
        UUID ref = parseRef(credentialRef);

        return transaction.execute(status -> {
            ExternalCredential credential = lockedCredential(ref);
            if (credential.getStatus() == ExternalCredentialStatus.REVOKED) {
                throw new ConflictException(CODE_ALREADY_REVOKED, "Credential " + ref + " is already revoked");
            }
            String shreddedKeyId = credential.getEncryptionKeyId();
            Instant now = now();
            credential.recordRevocation(by, actor.subject(), now, motivation);
            ExternalCredential saved = credentials.saveAndFlush(credential);
            events.saveAndFlush(new ExternalCredentialEvent(saved, ExternalCredentialEventKind.REVOKED, motivation,
                    ExternalCredentialEventSource.HUMAN, by, actor.subject(), now, shreddedKeyId, null));
            LOG.info("Credential {} revoked by {} (stored value erased)", ref, by);
            return view(saved);
        });
    }

    // --- Lezen (werkt ook zonder sleutelconfiguratie) --------------------------------------------------------

    /** Het gebruik van alle credentials in één query (geen query per rij). */
    public List<CredentialView> list() {
        return readTransaction.execute(status -> {
            Map<Long, Long> usage = new HashMap<>();
            for (Object[] row : profileVersions.countPerCredentialId()) {
                usage.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
            return credentials.findAllByOrderByLabelAscIdAsc().stream()
                    .map(c -> CredentialView.of(c, usage.getOrDefault(c.getId(), 0L))).toList();
        });
    }

    /** @throws NotFoundException 404 {@link #CODE_CREDENTIAL_NOT_FOUND} */
    public CredentialView get(String credentialRef) {
        UUID ref = parseRef(credentialRef);
        return readTransaction.execute(status -> view(existing(ref)));
    }

    /** @throws NotFoundException 404 {@link #CODE_CREDENTIAL_NOT_FOUND} */
    public List<CredentialEventView> events(String credentialRef) {
        UUID ref = parseRef(credentialRef);
        return readTransaction.execute(status -> events.findByCredentialIdOrderByIdAsc(existing(ref).getId())
                .stream().map(CredentialEventView::of).toList());
    }

    // --- Hulp ------------------------------------------------------------------------------------------------

    private CredentialView view(ExternalCredential credential) {
        return CredentialView.of(credential, profileVersions.countByCredentialId(credential.getId()));
    }

    private ExternalCredential existing(UUID ref) {
        return credentials.findByCredentialRef(ref).orElseThrow(() -> notFound(ref));
    }

    private ExternalCredential lockedCredential(UUID ref) {
        return credentials.findByCredentialRefForUpdate(ref).orElseThrow(() -> notFound(ref));
    }

    private static NotFoundException notFound(Object ref) {
        return new NotFoundException(CODE_CREDENTIAL_NOT_FOUND, "Credential " + ref + " not found");
    }

    /** Een ref die geen UUID is, kan niet bestaan: 404, net als een onbekende UUID. */
    private static UUID parseRef(String raw) {
        if (raw == null || raw.isBlank()) {
            throw notFound("(empty)");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw new NotFoundException(CODE_CREDENTIAL_NOT_FOUND, "Credential not found");
        }
    }

    private void requireConfigured() {
        if (!secrets.configured()) {
            throw new SecretsNotConfiguredException();
        }
    }

    /** Het sleutel-ID uit de zojuist gemaakte waarde {@code v1:<keyId>:<base64>}: exact de sleutel die versleutelde. */
    private static String keyIdOf(String ciphertext) {
        return ciphertext.split(":", 3)[1];
    }

    /** Microseconden, zoals PostgreSQL bewaart: het antwoord van een schrijfactie is gelijk aan een latere GET. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireActor(ActorIdentity actor) {
        if (actor == null) {
            throw new IllegalArgumentException("Missing actor");
        }
        return ActorNames.requireActorName(actor.username(), "actor", MAX_ACTOR_LENGTH);
    }

    private static String requireLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new BadRequestException(CODE_LABEL_REQUIRED, "Missing label");
        }
        return ActorNames.requireText(label, "label", MAX_LABEL_LENGTH);
    }

    private static ExternalCredentialSecretKind requireKind(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException(CODE_SECRET_KIND_INVALID, "Missing secretKind");
        }
        ExternalCredentialSecretKind kind;
        try {
            kind = ExternalCredentialSecretKind.valueOf(raw.trim());
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException(CODE_SECRET_KIND_INVALID, "Unknown secretKind; allowed: SFTP_PASSWORD");
        }
        if (kind != ExternalCredentialSecretKind.SFTP_PASSWORD) {
            throw new BadRequestException(CODE_SECRET_KIND_NOT_SUPPORTED,
                    "secretKind " + kind + " is reserved and not supported yet; use SFTP_PASSWORD");
        }
        return kind;
    }

    /** De melding noemt de host niet: enkel wat er aan de vorm schort. */
    private static String requireHost(String raw) {
        String host = HostNames.normalize(raw);
        if (host == null) {
            throw new BadRequestException(CODE_HOST_INVALID, "boundHost is not a valid host name (not empty, at most "
                    + HostNames.MAX_LENGTH + " characters, only ASCII letters, digits and . - _ : [ ])");
        }
        return host;
    }

    /**
     * De waarde wordt nooit getrimd of anders aangepast (een wachtwoord mag spaties bevatten); leeg of enkel witruimte
     * is een invoerfout. De melding noemt de waarde nooit.
     */
    private static void requireSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new BadRequestException(CODE_SECRET_REQUIRED, "Missing secret");
        }
        if (secret.length() > MAX_SECRET_LENGTH) {
            throw new BadRequestException(CODE_SECRET_TOO_LONG, "secret exceeds " + MAX_SECRET_LENGTH + " characters");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(CODE_REASON_REQUIRED, "Missing reason");
        }
        return ActorNames.requireText(reason, "reason", MAX_REASON_LENGTH);
    }

    @Override
    public String toString() {
        return "CredentialService[configured=" + secrets.configured() + "]";
    }
}
