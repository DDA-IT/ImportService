package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.LegacyConfigReceiptRepository;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.LegacyConfigReceipt;
import be.dda.catalogimport.domain.LegacyConfigReceiptStatus;
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
 * Ontvangst van legacy-config-bundels, slice S1 van {@code docs/design/legacy-config-bundle-design.md} (par. 2, 4 en
 * 8); contract {@code docs/design/legacy-config-bundle-v1.md}; beslissingslog 2026-10-20 (M2 en "Legacy-config S1:
 * idempotentie tegenover de laatste ontvangst").
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Validatie:</b> {@link LegacyBundleParser} (400 {@code LEGACY_BUNDLE_INVALID},
 *       {@code LEGACY_BUNDLE_VERSION_UNSUPPORTED}, {@code LEGACY_BUNDLE_HASH_MISMATCH}).</li>
 *   <li><b>Secret (enkel S1):</b> een bundel met een niet-null {@code secret} wordt volledig geweigerd met 400
 *       {@value #CODE_SECRET_NOT_SUPPORTED}; er wordt niets bewaard. De credentialovername volgt in S4.</li>
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
 *       de herhaling ziet de winnaar als laatste ontvangst.</li>
 *   <li><b>Bewaard:</b> de bundel zonder secret ({@code bundle_json}), de herberekende hashes, de exporttijd en de
 *       technische client ({@code svc:<azp>} + {@code sub}). Nooit de ruwe body.</li>
 * </ul>
 * Gelogd wordt enkel id, herkomst, interfacecode, hash en uitkomst — nooit de bundel.
 */
@Service
public class LegacyConfigReceiptService {

    /** 400 (enkel S1): de bundel draagt een secret, wat deze ontvanger nog niet aanvaardt. */
    public static final String CODE_SECRET_NOT_SUPPORTED = "LEGACY_BUNDLE_SECRET_NOT_SUPPORTED";
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

    static final String UNIQUE_OPEN = "uk_legacy_config_receipt_open";
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
     */
    public record ReceiptDetail(ReceiptRow receipt, String secretOutcome, UUID credentialRef, JsonNode bundle) {
    }

    private final LegacyConfigReceiptRepository receipts;
    private final ExternalCredentialRepository credentials;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;
    private final LegacyBundleParser parser = new LegacyBundleParser();
    /** Eigen mapper (niet die van Spring MVC) voor het bewaren en teruglezen van {@code bundle_json}. */
    private final ObjectMapper json = new ObjectMapper();

    public LegacyConfigReceiptService(LegacyConfigReceiptRepository receipts, ExternalCredentialRepository credentials,
                                      PlatformTransactionManager transactionManager, Clock clock) {
        this.receipts = receipts;
        this.credentials = credentials;
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
     * @throws BadRequestException 400 (zie {@link LegacyBundleParser} en {@value #CODE_SECRET_NOT_SUPPORTED})
     */
    public PushResult receive(byte[] body, ActorIdentity client) {
        String receivedBy = requireServiceActor(client);
        LegacyBundle bundle = parser.parse(body);
        if (bundle.secret() != null) {
            // S1: het secret wordt nog niet aanvaard; de waarde wordt nergens bewaard of gelogd.
            LOG.info("Legacy-config push refused for {}/{}/{} contentHash={}: {}", bundle.environment(),
                    bundle.dossier(), bundle.interfaceCode(), bundle.contentHash(), CODE_SECRET_NOT_SUPPORTED);
            throw new BadRequestException(CODE_SECRET_NOT_SUPPORTED, "This receiver does not accept a secret yet; "
                    + "send the bundle with \"secret\": null");
        }
        String bundleJson = serialize(bundle);
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> store(bundle, bundleJson, receivedBy, client.subject()));
            } catch (DataIntegrityViolationException violation) {
                if (!violates(violation, UNIQUE_OPEN)) {
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

    private PushResult store(LegacyBundle bundle, String bundleJson, String receivedBy, String subject) {
        // Eerst het slot op de open ontvangst (serialisatiepunt), dan pas de laatste ontvangst lezen: wie op het slot
        // wachtte, ziet daarna de ontvangst van de winnaar als laatste (READ COMMITTED, nieuw statement).
        Optional<LegacyConfigReceipt> open = receipts.findOpenForUpdate(bundle.environment(), bundle.dossier(),
                bundle.interfaceCode());
        Optional<LegacyConfigReceipt> latest = receipts
                .findFirstByOriginEnvironmentAndOriginDossierAndInterfaceCodeOrderByIdDesc(bundle.environment(),
                        bundle.dossier(), bundle.interfaceCode());
        if (latest.isPresent() && latest.get().getContentHash().equals(bundle.contentHash())) {
            LegacyConfigReceipt same = latest.get();
            LOG.info("Legacy-config receipt {} UNCHANGED ({}) for {}/{}/{} contentHash={}", same.getId(),
                    same.getStatus(), bundle.environment(), bundle.dossier(), bundle.interfaceCode(),
                    bundle.contentHash());
            return new PushResult(same.getId(), OUTCOME_UNCHANGED, same.getStatus().name(), same.getContentHash(),
                    null);
        }
        open.ifPresent(previous -> {
            previous.markSuperseded();
            receipts.saveAndFlush(previous);
        });
        LegacyConfigReceipt receipt = receipts.saveAndFlush(new LegacyConfigReceipt(bundle.environment(),
                bundle.dossier(), bundle.interfaceCode(), bundle.version(), bundle.contentHash(),
                bundle.definitionContentHash(), bundle.definitionCode(), bundleJson,
                bundle.exportedAt().toInstant(), clock.instant(), receivedBy, subject));
        Long supersededId = null;
        if (open.isPresent()) {
            LegacyConfigReceipt previous = open.get();
            previous.linkSuccessor(receipt.getId());
            receipts.saveAndFlush(previous);
            supersededId = previous.getId();
        }
        LOG.info("Legacy-config receipt {} RECEIVED for {}/{}/{} contentHash={} by {}{}", receipt.getId(),
                bundle.environment(), bundle.dossier(), bundle.interfaceCode(), bundle.contentHash(), receivedBy,
                supersededId == null ? "" : " (supersedes receipt " + supersededId + ")");
        return new PushResult(receipt.getId(), OUTCOME_RECEIVED, receipt.getStatus().name(), receipt.getContentHash(),
                supersededId);
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
                    readBundle(receipt));
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
