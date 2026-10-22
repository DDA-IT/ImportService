package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.LegacyConfigOriginRepository;
import be.dda.catalogimport.dao.LegacyConfigReceiptRepository;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialEventSource;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.domain.LegacyConfigOrigin;
import be.dda.catalogimport.domain.LegacyConfigReceipt;
import be.dda.catalogimport.domain.LegacyConfigReceiptStatus;
import be.dda.catalogimport.domain.LegacyConfigSecretOutcome;
import be.dda.catalogimport.service.support.LegacyFtpLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ontvangst van legacy-config-bundels, slices S1 en S4 van {@code docs/design/legacy-config-bundle-design.md} (par. 2,
 * 4 en 8); contract {@code docs/design/legacy-config-bundle-v1.md}; beslissingslog 2026-10-20 (M2, aannames A1/A2/A7,
 * "secrets leesbaar over TLS" en "Legacy-config S1: idempotentie tegenover de laatste ontvangst").
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Validatie:</b> {@link LegacyBundleParser} (400 {@code LEGACY_BUNDLE_INVALID},
 *       {@code LEGACY_BUNDLE_VERSION_UNSUPPORTED}, {@code LEGACY_BUNDLE_HASH_MISMATCH}); een secret met een ander label
 *       dan {@code FTP_password} is ongeldig.</li>
 *   <li><b>Secret zonder sleutelring</b> (V7): de hele push wordt geweigerd met 409
 *       {@code SECRETS_NOT_CONFIGURED}, ook als hij {@code UNCHANGED} zou zijn; er wordt niets bewaard.</li>
 *   <li><b>Secret bij een nieuwe ontvangst (S4, "Credential bij ontvangst"):</b> heeft de herkomst
 *       ({@code legacy_config_origin}) al een credential, dan {@code DISCARDED_ALREADY_PRESENT} (niets ontsleuteld of
 *       vergeleken; vervangen is een menselijke actie, A1). Geen bestemming → {@code DISCARDED_NO_HOST}. Anders
 *       bepaalt {@link LegacyFtpLocation} het protocol zoals legacy (A7, verfijnd 2026-10-22: schema van
 *       {@code FTP}+{@code FTP2}, {@code FTP/LOCAL/API}, hint {@code SFTP}): lokaal bestand of onduidelijk/tegenstrijdig
 *       → {@code DISCARDED_PROTOCOL_UNSUPPORTED}; geen geldige host → {@code DISCARDED_NO_HOST}; anders een nieuwe
 *       credential van de soort van het protocol ({@code SFTP_PASSWORD}, {@code FTP_PASSWORD} of
 *       {@code API_PASSWORD}; de laatste twee enkel bewaring, beslissingslog 2026-10-22 "LF"), gebonden aan de
 *       genormaliseerde host, eventbron SYSTEM, {@code created_by = svc:<azp>} (A2) → {@code STORED}, met
 *       {@code credential_id} op de ontvangst en op de herkomst (die zo nodig hier ontstaat). Credential en ontvangst
 *       zitten in één transactie.</li>
 *   <li><b>Secret bij {@code UNCHANGED}</b> (S4-vervolg, beslissingslog 2026-10-21; verfijnt A1): dezelfde regels als
 *       hierboven, onder hetzelfde rijslot op de herkomst. Heeft de herkomst al een credential, of is het geen
 *       ondersteund protocol (SFTP, FTP of API) met geldige host, dan wordt het secret weggegooid en niets
 *       geschreven. Anders ontstaat de credential toch (eventbron SYSTEM, reden met "herhaalde push"), komt ze op de herkomst en op de laatste ontvangst (de ontvangst die
 *       {@code UNCHANGED} teruggaf: {@code secret_outcome = STORED}, {@code credential_id}), tenzij die al
 *       {@code STORED} is (ook een ontvangst met {@code DISCARDED_PROTOCOL_UNSUPPORTED} van vóór 2026-10-22 wordt zo
 *       {@code STORED}; haar status blijft ongemoeid). Het antwoord blijft {@code UNCHANGED}. Een bestaande credential
 *       wordt nooit vervangen.</li>
 *   <li><b>Idempotentie tegenover de laatste ontvangst:</b> een push wordt enkel vergeleken met de <b>laatste</b>
 *       ontvangst (hoogste id) van dezelfde herkomst, ongeacht haar status. Zelfde {@code contentHash} = 200
 *       {@value #OUTCOME_UNCHANGED} met dat id en die status; er wordt niets geschreven.</li>
 *   <li><b>Nieuwe ontvangst:</b> elke andere inhoud wordt een nieuwe rij {@code RECEIVED} (201), ook als die inhoud
 *       vroeger al eens binnenkwam (A → B → A geeft een nieuwe ontvangst A'). Een open ontvangst wordt
 *       {@code SUPERSEDED} met een verwijzing naar de nieuwe ({@code supersededReceiptId}); {@code APPLIED} en
 *       {@code DISCARDED} blijven ongemoeid ({@code supersededReceiptId = null}). Invariant: de laatste ontvangst is
 *       nooit {@code SUPERSEDED}.</li>
 *   <li><b>Gelijktijdigheid:</b> het enige serialisatiepunt is {@code uk_legacy_config_receipt_open}: de open
 *       ontvangst wordt eerst met een schrijfslot gelezen, pas daarna de laatste ontvangst. Botsen twee inserts toch
 *       op de open-marker, dan wordt de hele transactie opnieuw geprobeerd (hoogstens {@value #MAX_ATTEMPTS} keer);
 *       de herhaling ziet de winnaar als laatste ontvangst. De herkomstrij wordt bij een secret met een schrijfslot
 *       gelezen; een gelijktijdige eerste insert van de herkomst ({@code uk_legacy_config_origin}) wordt ook herhaald.
 *       Zo ontstaat per herkomst hoogstens één credential.</li>
 *   <li><b>Bewaard:</b> de bundel zonder secret ({@code bundle_json}), de herberekende hashes, de exporttijd, de
 *       technische client ({@code svc:<azp>} + {@code sub}) en {@code secret_received}/{@code secret_outcome}. Nooit
 *       de ruwe body, nooit de waarde van het secret (enkel versleuteld in {@code external_credential}).</li>
 * </ul>
 * Gelogd wordt enkel id, herkomst, interfacecode, hash, uitkomst en eventueel de credentialreferentie — nooit de bundel
 * of het secret.
 */
@Service
public class LegacyConfigReceiptService {

    /** 404: onbekende ontvangst. */
    public static final String CODE_RECEIPT_NOT_FOUND = "LEGACY_RECEIPT_NOT_FOUND";

    /** Uitkomst van een push: nieuw bewaard (201). */
    public static final String OUTCOME_RECEIVED = "RECEIVED";
    /** Uitkomst van een push: deze inhoud was er al voor deze herkomst (200). */
    public static final String OUTCOME_UNCHANGED = "UNCHANGED";

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    /** Prefix van de actornaam van een technische client (M2): {@code svc:<azp>}. */
    public static final String SERVICE_ACTOR_PREFIX = "svc:";

    /** Label van een uit legacy overgenomen credential: {@code Legacy <dossier>/<interface>}. */
    static final String CREDENTIAL_LABEL_PREFIX = "Legacy ";

    static final String UNIQUE_OPEN = "uk_legacy_config_receipt_open";
    static final String UNIQUE_ORIGIN = "uk_legacy_config_origin";
    /** Pogingen bij een botsing op de open-marker (twee gelijktijdige pushes met verschillende inhoud). */
    static final int MAX_ATTEMPTS = 5;
    private static final int MAX_ACTOR_LENGTH = 100;

    private static final Logger LOG = LoggerFactory.getLogger(LegacyConfigReceiptService.class);

    /** Resultaat van een push; {@code status} is de huidige status van de (nieuwe of bestaande) ontvangst. */
    public record PushResult(long receiptId, String outcome, String status, String contentHash,
                             Long supersededReceiptId) {
    }

    /** Eén regel van de lijst, zonder bundel. */
    public record ReceiptRow(long id, String originEnvironment, String originDossier, String interfaceCode,
                             int bundleVersion, String contentHash, String definitionContentHash,
                             String legacyDefinitionCode, String status, boolean secretReceived,
                             String secretOutcome, Instant exportedAt, Instant receivedAt, String receivedBy,
                             Long supersededByReceiptId, Instant appliedAt, String appliedBy) {

        static ReceiptRow of(LegacyConfigReceipt r) {
            return new ReceiptRow(r.getId(), r.getOriginEnvironment(), r.getOriginDossier(), r.getInterfaceCode(),
                    r.getBundleVersion(), r.getContentHash(), r.getDefinitionContentHash(),
                    r.getLegacyDefinitionCode(), r.getStatus().name(), r.isSecretReceived(),
                    r.getSecretOutcome() == null ? null : r.getSecretOutcome().name(), r.getExportedAt(),
                    r.getReceivedAt(), r.getReceivedBy(), r.getSupersededByReceiptId(), r.getAppliedAt(),
                    r.getAppliedBy());
        }
    }

    /**
     * Detail van een ontvangst: de bundel zonder secret ({@code "secret": null}), met {@code secretOutcome} en
     * {@code credentialRef} (vanaf S4). Enkel voor MANAGE: de bundel toont host en login (L7b).
     * <p>
     * G1b (additief): {@code applyReport} is het bewaarde APPLY-rapport ({@code report} uit {@code apply_report_json}, met
     * de id's van wat aangemaakt of hergebruikt werd) van een {@code APPLIED} ontvangst, anders {@code null}; voor de
     * links naar de conceptversie. {@code discardedAt/By/Reason}: de audit van een verworpen ontvangst.
     */
    public record ReceiptDetail(ReceiptRow receipt, String secretOutcome, UUID credentialRef, JsonNode bundle,
                                JsonNode applyReport, Instant discardedAt, String discardedBy,
                                String discardedReason) {
    }

    private final LegacyConfigReceiptRepository receipts;
    private final LegacyConfigOriginRepository origins;
    private final ExternalCredentialRepository credentials;
    private final CredentialService credentialService;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;
    private final LegacyBundleParser parser = new LegacyBundleParser();
    /** Eigen mapper (niet die van Spring MVC) voor het bewaren en teruglezen van {@code bundle_json}. */
    private final ObjectMapper json = new ObjectMapper();

    public LegacyConfigReceiptService(LegacyConfigReceiptRepository receipts, LegacyConfigOriginRepository origins,
                                      ExternalCredentialRepository credentials, CredentialService credentialService,
                                      PlatformTransactionManager transactionManager, Clock clock) {
        this.receipts = receipts;
        this.origins = origins;
        this.credentials = credentials;
        this.credentialService = credentialService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    // --- Push ------------------------------------------------------------------------------------------------

    /**
     * Valideert en bewaart een bundel. De aanroeper (Web) heeft de grootte al begrensd en overschrijft {@code body}
     * na deze aanroep.
     *
     * @param client de technische client: {@code username = svc:<azp>}, {@code subject = sub}
     * @throws BadRequestException           400 (zie {@link LegacyBundleParser})
     * @throws SecretsNotConfiguredException 409: de bundel draagt een secret en er is geen sleutelring
     */
    public PushResult receive(byte[] body, ActorIdentity client) {
        requireServiceActor(client);
        LegacyBundle bundle = parser.parse(body);
        if (bundle.secret() != null && !credentialService.configured()) {
            // V7: zonder sleutelring kan het secret niet versleuteld worden; de hele push wordt geweigerd, niets bewaard.
            LOG.info("Legacy-config push refused for {}/{}/{} contentHash={}: {} (the bundle carries a secret)",
                    bundle.environment(), bundle.dossier(), bundle.interfaceCode(), bundle.contentHash(),
                    SecretsNotConfiguredException.CODE);
            throw new SecretsNotConfiguredException();
        }
        String bundleJson = serialize(bundle);
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> store(bundle, bundleJson, client));
            } catch (DataIntegrityViolationException violation) {
                if (!violates(violation, UNIQUE_OPEN) && !violates(violation, UNIQUE_ORIGIN)) {
                    throw violation;
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException("Legacy-config receipt for " + bundle.interfaceCode()
                            + " could not be stored after " + MAX_ATTEMPTS + " concurrent attempts");
                }
                LOG.info("Legacy-config push for {}/{}/{} collided with a concurrent push; retrying (attempt {})",
                        bundle.environment(), bundle.dossier(), bundle.interfaceCode(), attempt + 1);
            }
        }
    }

    private PushResult store(LegacyBundle bundle, String bundleJson, ActorIdentity client) {
        // Eerst het slot op de open ontvangst (serialisatiepunt), dan pas de laatste ontvangst lezen: wie op het slot
        // wachtte, ziet daarna de ontvangst van de winnaar als laatste (READ COMMITTED, nieuw statement).
        Optional<LegacyConfigReceipt> open = receipts.findOpenForUpdate(bundle.environment(), bundle.dossier(),
                bundle.interfaceCode());
        Optional<LegacyConfigReceipt> latest = receipts
                .findFirstByOriginEnvironmentAndOriginDossierAndInterfaceCodeOrderByIdDesc(bundle.environment(),
                        bundle.dossier(), bundle.interfaceCode());
        if (latest.isPresent() && latest.get().getContentHash().equals(bundle.contentHash())) {
            LegacyConfigReceipt same = latest.get();
            String secretNote = bundle.secret() == null ? "" : secretOfRepeatedPush(bundle, same, client);
            LOG.info("Legacy-config receipt {} UNCHANGED ({}) for {}/{}/{} contentHash={}{}", same.getId(),
                    same.getStatus(), bundle.environment(), bundle.dossier(), bundle.interfaceCode(),
                    bundle.contentHash(), secretNote);
            return new PushResult(same.getId(), OUTCOME_UNCHANGED, same.getStatus().name(), same.getContentHash(),
                    null);
        }
        SecretDecision secret = bundle.secret() == null ? null : decideSecret(bundle);
        open.ifPresent(previous -> {
            previous.markSuperseded();
            receipts.saveAndFlush(previous);
        });
        LegacyConfigReceipt fresh = new LegacyConfigReceipt(bundle.environment(), bundle.dossier(),
                bundle.interfaceCode(), bundle.version(), bundle.contentHash(), bundle.definitionContentHash(),
                bundle.definitionCode(), bundleJson, bundle.exportedAt().toInstant(), clock.instant(),
                client.username(), client.subject());
        if (secret != null) {
            fresh.recordSecretOutcome(secret.outcome());
        }
        LegacyConfigReceipt receipt = receipts.saveAndFlush(fresh);
        UUID credentialRef = null;
        if (secret != null && secret.outcome() == LegacyConfigSecretOutcome.STORED) {
            StoredCredential stored = storeCredential(bundle, receipt.getId(), secret, client, false);
            receipt.linkCredential(stored.id());
            receipts.saveAndFlush(receipt);
            credentialRef = stored.ref();
        }
        Long supersededId = null;
        if (open.isPresent()) {
            LegacyConfigReceipt previous = open.get();
            previous.linkSuccessor(receipt.getId());
            receipts.saveAndFlush(previous);
            supersededId = previous.getId();
        }
        LOG.info("Legacy-config receipt {} RECEIVED for {}/{}/{} contentHash={} by {}{}{}", receipt.getId(),
                bundle.environment(), bundle.dossier(), bundle.interfaceCode(), bundle.contentHash(),
                client.username(), supersededId == null ? "" : " (supersedes receipt " + supersededId + ")",
                secret == null ? "" : "; secretOutcome=" + secret.outcome()
                        + (credentialRef == null ? "" : " credential " + credentialRef));
        return new PushResult(receipt.getId(), OUTCOME_RECEIVED, receipt.getStatus().name(), receipt.getContentHash(),
                supersededId);
    }

    /** Wat er met het secret van een nieuwe ontvangst gebeurt; {@code kind} en {@code host} enkel bij {@code STORED}. */
    private record SecretDecision(LegacyConfigSecretOutcome outcome, LegacyConfigOrigin origin,
                                  ExternalCredentialSecretKind kind, String host) {
    }

    /**
     * Design par. 2 "Credential bij ontvangst", in deze volgorde: de herkomst heeft al een credential (ongeacht haar
     * status en soort) → {@code DISCARDED_ALREADY_PRESENT}, zonder iets te ontsleutelen of te vergelijken (A1); geen
     * bestemming → {@code DISCARDED_NO_HOST}; protocol lokaal of onbekend/tegenstrijdig ({@link LegacyFtpLocation},
     * A7) → {@code DISCARDED_PROTOCOL_UNSUPPORTED}; geen geldige host → {@code DISCARDED_NO_HOST}; anders
     * {@code STORED} met de soort van het protocol (SFTP → {@code SFTP_PASSWORD}, FTP → {@code FTP_PASSWORD}, API →
     * {@code API_PASSWORD}; beslissingslog 2026-10-22 "LF"). De herkomstrij wordt vergrendeld, zodat twee pushes nooit
     * allebei een credential aanmaken.
     */
    private SecretDecision decideSecret(LegacyBundle bundle) {
        LegacyConfigOrigin origin = origins.findForUpdate(bundle.environment(), bundle.dossier(),
                bundle.interfaceCode()).orElse(null);
        if (origin != null && origin.getCredentialId() != null) {
            return new SecretDecision(LegacyConfigSecretOutcome.DISCARDED_ALREADY_PRESENT, origin, null, null);
        }
        LegacyFtpLocation location = LegacyFtpLocation.of(bundle.withoutSecret());
        if (location.protocol() == LegacyFtpLocation.Protocol.NONE) {
            return new SecretDecision(LegacyConfigSecretOutcome.DISCARDED_NO_HOST, origin, null, null);
        }
        ExternalCredentialSecretKind kind = switch (location.protocol()) {
            case SFTP -> ExternalCredentialSecretKind.SFTP_PASSWORD;
            case FTP -> ExternalCredentialSecretKind.FTP_PASSWORD;
            case API -> ExternalCredentialSecretKind.API_PASSWORD;
            case LOCAL, UNKNOWN, NONE -> null;
        };
        if (kind == null) {
            return new SecretDecision(LegacyConfigSecretOutcome.DISCARDED_PROTOCOL_UNSUPPORTED, origin, null, null);
        }
        if (!location.hasValidHost()) {
            return new SecretDecision(LegacyConfigSecretOutcome.DISCARDED_NO_HOST, origin, null, null);
        }
        return new SecretDecision(LegacyConfigSecretOutcome.STORED, origin, kind, location.host());
    }

    /**
     * S4-vervolg (beslissingslog 2026-10-21 "wachtwoord bij UNCHANGED"; verfijnt A1): een identieke push draagt een
     * secret. Dezelfde regels als bij een nieuwe ontvangst ({@link #decideSecret}, onder hetzelfde rijslot op de
     * herkomst): heeft de herkomst nog geen credential en is het een ondersteund protocol (SFTP, FTP of API) met een
     * geldige host, dan wordt het secret toch versleuteld opgeslagen (eventbron SYSTEM, soort volgens het protocol) en
     * op de herkomst gezet. De credential ontstond uit de inhoud van de
     * laatste ontvangst, dus krijgt <b>die</b> ontvangst {@code secret_outcome = STORED} en {@code credential_id}, tenzij
     * ze al {@code STORED} is; zo krijgt ook een ontvangst die vóór 2026-10-22 {@code DISCARDED_PROTOCOL_UNSUPPORTED}
     * kreeg (FTP/API), bij een herhaalde push alsnog {@code STORED}, zonder migratie en zonder statuswijziging. Elke
     * andere uitkomst ({@code DISCARDED_*}) wordt enkel gelogd: niets geschreven, net als
     * vroeger bij {@code UNCHANGED}. Een bestaande credential wordt nooit vervangen (A1).
     *
     * @return de lognotitie (uitkomst en eventueel de credentialreferentie, nooit de waarde)
     */
    private String secretOfRepeatedPush(LegacyBundle bundle, LegacyConfigReceipt same, ActorIdentity client) {
        SecretDecision decision = decideSecret(bundle);
        if (decision.outcome() != LegacyConfigSecretOutcome.STORED) {
            return "; secret discarded (" + decision.outcome() + ")";
        }
        StoredCredential stored = storeCredential(bundle, same.getId(), decision, client, true);
        if (same.getSecretOutcome() != LegacyConfigSecretOutcome.STORED) {
            same.recordCredentialFromRepeatedPush(stored.id());
            receipts.saveAndFlush(same);
        }
        return "; secretOutcome=STORED credential " + stored.ref() + " (repeated push)";
    }

    /** Een zojuist aangemaakte credential: de publieke referentie en het interne id. */
    private record StoredCredential(UUID ref, Long id) {
    }

    /**
     * Versleutelt het secret als nieuwe credential (eventbron SYSTEM, A2) en legt ze vast op de herkomst (die hier
     * ontstaat als ze nog niet bestond); de aanroeper legt ze vast op de ontvangst. Zelfde transactie als de
     * ontvangst: lukt iets niet, dan bestaat geen van beide.
     *
     * @param repeated de credential ontstaat bij een {@code UNCHANGED}-push; dat staat in de reden van het event
     */
    private StoredCredential storeCredential(LegacyBundle bundle, Long receiptId, SecretDecision decision,
                                             ActorIdentity client, boolean repeated) {
        CredentialService.CredentialView created = credentialService.create(
                CREDENTIAL_LABEL_PREFIX + bundle.dossier() + "/" + bundle.interfaceCode(),
                decision.kind().name(), decision.host(), bundle.secret().value(),
                "Overgenomen uit legacy INDET " + bundle.interfaceCode() + " (ontvangst " + receiptId
                        + (repeated ? ", herhaalde push" : "") + ")",
                client, ExternalCredentialEventSource.SYSTEM);
        Long credentialId = credentials.findByCredentialRef(created.credentialRef()).map(ExternalCredential::getId)
                .orElseThrow(() -> new IllegalStateException("Credential " + created.credentialRef()
                        + " was not found right after it was created"));
        Instant now = clock.instant();
        LegacyConfigOrigin origin = decision.origin() != null ? decision.origin()
                : new LegacyConfigOrigin(bundle.environment(), bundle.dossier(), bundle.interfaceCode(), now);
        origin.attachCredential(credentialId, now);
        origins.saveAndFlush(origin);
        return new StoredCredential(created.credentialRef(), credentialId);
    }

    // --- Lezen -----------------------------------------------------------------------------------------------

    /**
     * De lijst, optioneel gefilterd op status en exacte interfacecode; sortering {@code received_at desc, id desc};
     * {@code page} 0-gebaseerd, {@code size} standaard {@value #DEFAULT_PAGE_SIZE}, begrensd tot
     * {@value #MAX_PAGE_SIZE}.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<ReceiptRow> list(LegacyConfigReceiptStatus status, String interfaceCode, Integer page,
                                       Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        String code = (interfaceCode == null || interfaceCode.isBlank()) ? null : interfaceCode.trim();
        return readTransaction.execute(tx -> PageResult.of(receipts.findRows(status, code, pageRequest),
                ReceiptRow::of));
    }

    /** @throws NotFoundException 404 {@value #CODE_RECEIPT_NOT_FOUND} */
    public ReceiptDetail get(long id) {
        return readTransaction.execute(tx -> {
            LegacyConfigReceipt receipt = receipts.findById(id).orElseThrow(() ->
                    new NotFoundException(CODE_RECEIPT_NOT_FOUND, "Legacy-config receipt " + id + " not found"));
            UUID credentialRef = receipt.getCredentialId() == null ? null
                    : credentials.findById(receipt.getCredentialId()).map(ExternalCredential::getCredentialRef)
                    .orElse(null);
            return new ReceiptDetail(ReceiptRow.of(receipt),
                    receipt.getSecretOutcome() == null ? null : receipt.getSecretOutcome().name(), credentialRef,
                    readBundle(receipt), readApplyReport(receipt), receipt.getDiscardedAt(),
                    receipt.getDiscardedBy(), receipt.getDiscardedReason());
        });
    }

    // --- Hulp ------------------------------------------------------------------------------------------------

    private String serialize(LegacyBundle bundle) {
        try {
            return json.writeValueAsString(bundle.withoutSecret());
        } catch (JsonProcessingException impossible) {
            // Een boom zonder POJO's is altijd serialiseerbaar; nooit de melding doorgeven.
            throw new IllegalStateException("The legacy-config bundle could not be serialised");
        }
    }

    private JsonNode readBundle(LegacyConfigReceipt receipt) {
        try {
            return json.readTree(receipt.getBundleJson());
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("bundle_json of legacy-config receipt " + receipt.getId()
                    + " is not readable");
        }
    }

    /** Het {@code report}-deel van {@code apply_report_json}; {@code null} zonder bewaard rapport. */
    private JsonNode readApplyReport(LegacyConfigReceipt receipt) {
        if (receipt.getApplyReportJson() == null) {
            return null;
        }
        try {
            JsonNode report = json.readTree(receipt.getApplyReportJson()).get("report");
            return report == null || report.isNull() ? null : report;
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("apply_report_json of legacy-config receipt " + receipt.getId()
                    + " is not readable");
        }
    }

    private static String requireServiceActor(ActorIdentity client) {
        if (client == null || client.username() == null || !client.username().startsWith(SERVICE_ACTOR_PREFIX)
                || client.username().length() <= SERVICE_ACTOR_PREFIX.length()
                || client.username().length() > MAX_ACTOR_LENGTH) {
            throw new IllegalArgumentException("A legacy-config push needs a technical client identity (svc:<azp>)");
        }
        return client.username();
    }

    private static PageRequest pageRequest(Integer page, Integer size) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE), Sort.unsorted());
    }

    static boolean violates(DataIntegrityViolationException violation, String constraint) {
        String message = NestedExceptionUtils.getMostSpecificCause(violation).getMessage();
        return message != null && message.contains(constraint);
    }
}
