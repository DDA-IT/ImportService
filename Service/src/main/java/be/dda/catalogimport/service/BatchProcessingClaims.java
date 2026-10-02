package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.domain.ImportBatch;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verwerkingsclaim per batch met lease en fencing (stap 4, beslissingslog 2026-10-01 "Stap 4 en 5 uitgewerkt";
 * schema changeset 017, domein {@link ImportBatch#claimProcessing}).
 * <p>
 * <b>Businessregel.</b> Een batch wordt nooit door twee workers tegelijk gescreend of hervat. Zolang een worker
 * actief is, weigert een tweede start of hervatting met 409 {@link #CODE_BATCH_BEING_PROCESSED}.
 * <p>
 * <b>Technische invulling.</b>
 * <ul>
 *   <li>Een claim is levend als de token gezet is, de heartbeat niet ouder is dan de lease
 *       ({@code catalogimport.screening.claim-lease}, default {@value #DEFAULT_LEASE}), en ze niet van een
 *       <i>vorige</i> boot van deze instantie komt ({@link ImportBatch#isProcessingClaimAlive}).</li>
 *   <li>Eigenaar = {@code <instanceId>/<bootId>}: {@code catalogimport.instance-id} (default de hostnaam,
 *       <b>verplicht uniek per instantie</b>) en een UUID die bij elke JVM-start nieuw is. Zo herkent een herstarte
 *       instantie haar eigen verweesde claims meteen als dood, zonder op de lease te wachten.</li>
 *   <li>Elke schrijvende transactie na de start begint met {@link #touch}: een fencing-update op de eigen token.
 *       Vindt die geen rij, dan is de claim verloren ({@link ClaimLostException}) en stopt de worker.</li>
 *   <li>Tijd komt uit de gedeelde {@link Clock}-bean.</li>
 * </ul>
 * <b>Configuratie (fail-fast).</b> Een ongeldige of niet-positieve lease, een instance-id met een {@code /}, met
 * controletekens, leeg of langer dan {@value #MAX_INSTANCE_ID_LENGTH} tekens laat de applicatie niet opstarten
 * (zelfde patroon als {@code catalogimport.fetch.stuck-after}, K-4c).
 */
@Component
public class BatchProcessingClaims {

    /** 409: de batch wordt op dit moment door een andere worker verwerkt (of de eigen claim ging verloren). */
    public static final String CODE_BATCH_BEING_PROCESSED = "BATCH_BEING_PROCESSED";

    static final String DEFAULT_LEASE = "PT60M";
    /** {@code import_batch.processing_claimed_by} is varchar(100). */
    static final int MAX_CLAIMED_BY_LENGTH = 100;
    /** Een bootId is een UUID in tekstvorm (36 tekens). */
    private static final int BOOT_ID_LENGTH = 36;
    /** instanceId + {@code /} + bootId moet in {@link #MAX_CLAIMED_BY_LENGTH} passen. */
    static final int MAX_INSTANCE_ID_LENGTH = MAX_CLAIMED_BY_LENGTH - 1 - BOOT_ID_LENGTH;

    private static final Logger LOG = LoggerFactory.getLogger(BatchProcessingClaims.class);

    private final ImportBatchRepository batches;
    private final Clock clock;
    private final Duration lease;
    private final String instanceId;
    private final String bootId;
    private final String claimedBy;

    @Autowired
    public BatchProcessingClaims(ImportBatchRepository batches, Clock clock,
                                 @Value("${catalogimport.screening.claim-lease:" + DEFAULT_LEASE + "}") String lease,
                                 @Value("${catalogimport.instance-id:}") String instanceId) {
        this(batches, clock, lease, instanceId, UUID.randomUUID().toString());
    }

    /** Voor tests: een vaste bootId, zodat "vorige boot van deze instantie" na te bootsen is. */
    BatchProcessingClaims(ImportBatchRepository batches, Clock clock, String lease, String instanceId,
                          String bootId) {
        this.batches = batches;
        this.clock = clock;
        this.lease = parseLease(lease);
        this.instanceId = validateInstanceId(instanceId == null || instanceId.isBlank() ? hostname() : instanceId);
        this.bootId = bootId;
        this.claimedBy = this.instanceId + "/" + bootId;
        if (claimedBy.length() > MAX_CLAIMED_BY_LENGTH) {
            throw new IllegalStateException("processing_claimed_by '" + claimedBy + "' is longer than "
                    + MAX_CLAIMED_BY_LENGTH + " characters");
        }
        LOG.info("Batch processing claims: instance '{}', boot {}, lease {}", this.instanceId, bootId, this.lease);
    }

    /** Fail-fast: geen stille terugval op de default bij een fout in de configuratie. */
    static Duration parseLease(String value) {
        Duration parsed;
        try {
            parsed = Duration.parse(value == null ? "" : value.trim());
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException("catalogimport.screening.claim-lease must be an ISO-8601 duration such as "
                    + DEFAULT_LEASE + " (was '" + value + "')", invalid);
        }
        if (parsed.isZero() || parsed.isNegative()) {
            throw new IllegalStateException(
                    "catalogimport.screening.claim-lease must be positive (was '" + value + "')");
        }
        return parsed;
    }

    /**
     * Een instance-id mag geen {@code /} bevatten (dat is het scheidingsteken met de bootId), geen controletekens,
     * niet leeg zijn en niet langer dan {@value #MAX_INSTANCE_ID_LENGTH} tekens.
     */
    static String validateInstanceId(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("catalogimport.instance-id must not be empty");
        }
        if (trimmed.indexOf('/') >= 0) {
            throw new IllegalStateException("catalogimport.instance-id must not contain '/' (was '" + trimmed + "')");
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("catalogimport.instance-id must not contain control characters");
        }
        if (trimmed.length() > MAX_INSTANCE_ID_LENGTH) {
            throw new IllegalStateException("catalogimport.instance-id must be at most " + MAX_INSTANCE_ID_LENGTH
                    + " characters (was " + trimmed.length() + "); set it explicitly if the host name is longer");
        }
        return trimmed;
    }

    /** Default instance-id: de hostnaam; is die niet te bepalen, dan moet de property gezet worden. */
    private static String hostname() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (UnknownHostException unknown) {
            LOG.warn("Cannot resolve the local host name; falling back to the environment", unknown);
        }
        for (String variable : new String[] {"HOSTNAME", "COMPUTERNAME"}) {
            String name = System.getenv(variable);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        throw new IllegalStateException("Cannot determine the host name; set catalogimport.instance-id explicitly");
    }

    public Instant now() {
        return clock.instant();
    }

    public Duration lease() {
        return lease;
    }

    public String instanceId() {
        return instanceId;
    }

    public String bootId() {
        return bootId;
    }

    /** De eigenaar die op een nieuwe claim komt: {@code <instanceId>/<bootId>}. */
    public String claimedBy() {
        return claimedBy;
    }

    /** Is de claim op deze batch nu levend (lease, en niet van een vorige boot van deze instantie)? */
    public boolean isAlive(ImportBatch batch) {
        return batch.isProcessingClaimAlive(now(), lease, instanceId, bootId);
    }

    /**
     * Neemt een nieuwe claim op een batch die de aanroeper onder schrijfslot geladen heeft. Een levende claim wordt
     * nooit overschreven: 409 {@link #CODE_BATCH_BEING_PROCESSED}. Een dode claim (verlopen lease, of een vorige
     * boot van deze instantie) wordt overgenomen met een nieuwe token, wat de vorige houder bij zijn volgende
     * fencing-update buitensluit.
     *
     * @return de nieuwe token
     */
    public UUID claim(ImportBatch batch) {
        if (isAlive(batch)) {
            throw beingProcessed(batch.getId(), batch.getProcessingClaimedBy());
        }
        if (batch.getProcessingClaimToken() != null) {
            LOG.warn("Batch {}: taking over the dead processing claim of {} (last heartbeat {})", batch.getId(),
                    batch.getProcessingClaimedBy(), batch.getProcessingHeartbeatAt());
        }
        UUID token = UUID.randomUUID();
        batch.claimProcessing(token, claimedBy, now());
        return token;
    }

    /**
     * Fencing: ververst het levensteken enkel als de batch nog deze token draagt. Moet het eerste statement van de
     * transactie zijn ({@link ImportBatchRepository#touchProcessingClaim}).
     *
     * @throws ClaimLostException als de claim gewist of door een andere vervangen werd
     */
    public void touch(long batchId, UUID token) {
        if (token == null || batches.touchProcessingClaim(batchId, token, now()) == 0) {
            throw new ClaimLostException(batchId);
        }
    }

    /**
     * De 409 voor een batch die door een andere worker verwerkt wordt. De eigenaar (hostnaam) gaat enkel naar de
     * log, niet in de boodschap: die komt in het HTTP-antwoord terecht.
     */
    public static ConflictException beingProcessed(long batchId, String owner) {
        LOG.info("Batch {} is being processed by {}; request refused with {}", batchId, owner,
                CODE_BATCH_BEING_PROCESSED);
        return new ConflictException(CODE_BATCH_BEING_PROCESSED, "Batch " + batchId
                + " is being processed right now; try again when that processing has finished");
    }
}
