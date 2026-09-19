package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CandidatePriceDao;
import be.dda.catalogimport.dao.CandidateReferenceDao;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.PriceObservationDao;
import be.dda.catalogimport.dao.PriceObservationDao.ObservationContext;
import be.dda.catalogimport.dao.SourceStateDao;
import be.dda.catalogimport.dao.SourceStateDao.AcceptanceContext;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.SourceStateOrigin;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * De geauditeerde actie {@code accept-baseline}: een gescreende levering ({@code SCREENED}) wordt als
 * nulmeting van de bronstaat aanvaard (beslissingslog 18/09, "Fase 2: baseline-acceptatie"). Fase 2
 * kent geen publicatie, en de bronstaat mag volgens de businessanalyse enkel na publicatie bijgewerkt
 * worden; deze actie is de bewuste, vastgelegde uitzondering die het bewijs mogelijk maakt dat een
 * identieke herlevering nul mutaties oplevert. Fase 5 vult haar aan of vervangt ze door de echte
 * publicatieroute ({@code state_origin = PUBLISHED}).
 * <p>
 * <b>Businessgedrag.</b>
 * <ul>
 *   <li>Enkel vanuit {@code SCREENED} (anders 409 {@link #CODE_BATCH_NOT_ACCEPTABLE}); een tweede
 *       acceptatie van dezelfde batch is dus een conflict.</li>
 *   <li>{@code reason} en {@code acceptedBy} zijn verplicht; {@code acceptedBy} mag niet {@code system}
 *       zijn (hoofdletterongevoelig): een aanvaarding is altijd van een mens.</li>
 *   <li>{@code NEW} wordt een nieuwe bronstaatrij ({@code state_origin=BASELINE_ACCEPTED}), {@code CHANGED}
 *       werkt de bestaande rij bij, {@code UNCHANGED} raakt de bronstaat niet aan (ook {@code updated_at}
 *       niet).</li>
 *   <li><b>Een vastgehouden regel wordt nooit aanvaard</b> (fase 3f, R-REF-09). Een regel met
 *       classificatie {@code IDENTITY_INCIDENT} komt niet in {@code catalog_source_state} en niet in
 *       {@code catalog_reference_state}; haar inhoudelijke mutatie blijft {@code BLOCKED} en het
 *       bijhorende {@code IDENTITY_REFERENCE_INCIDENT} blijft {@code AWAITING_APPROVAL}. Een
 *       aanvaarding van de nulmeting is geen goedkeuring van een identiteitswijziging.</li>
 *   <li>De kritieke koppelreferenties van de aanvaarde regels worden vastgelegd in
 *       {@code catalog_reference_state} (fase 3, R-REF-06): genormaliseerd én ruw, met
 *       {@code accepted_by}/{@code accepted_at}, {@code active_marker = TRUE}, en uitsluitend wanneer
 *       de waarde binnen de bibliotheek nog niet actief is. Een waarde die al bij een andere
 *       aanbieding actief staat, blijft daar: dat is de indirecte artikelkoppeling van matchingstap 2
 *       (R-ID-03) en nooit een overname.</li>
 *   <li>De prijscomponenten van die aanbiedingen gaan mee naar {@code catalog_source_state_price}
 *       (fase 3, R-PRI-09): nieuwe rijen erbij, gewijzigde rijen vervangen, ongewijzigde rijen
 *       onaangeroerd. Dat is de "voor"-waarde waartegen de volgende levering een percentagewijziging
 *       bij een ongewijzigde basisprijs kan vaststellen.</li>
 *   <li>Diezelfde aanvaarde waarden gaan als <b>goedgekeurde dagwaarde</b> naar
 *       {@code catalog_price_observation} (fase 3, R-PRI-13): één rij per identiteit + component per
 *       kalenderdag, append-only. Dat is de historiek waartegen de afwijkingscontrole van een volgende
 *       levering haar gemiddelden berekent (R-PRI-10). Twee aanvaardingen op dezelfde dag laten de
 *       <b>eerste</b> waarde staan (aanname A16); een {@code UNCHANGED}-regel levert géén observatie
 *       op, en de kandidaatprijs van een nog niet aanvaarde screening komt er nooit in.
 *       <p>
 *       <b>Gevolg voor de gemiddelden, bewust zo:</b> de vensters van 50 en 200 lopen over de laatste
 *       N <i>vastgelegde</i> goedgekeurde dagwaarden, niet over N kalenderdagen. Een prijs die een
 *       jaar lang niet wijzigt levert één observatie op, geen 365; het gemiddelde is dus het
 *       gemiddelde van de laatste N <i>wijzigingen</i> die aanvaard zijn.</li>
 *   <li>De kalenderdag van een observatie wordt bepaald in UTC
 *       ({@code PriceObservationDao.OBSERVATION_ZONE}) op basis van een injecteerbare {@link Clock},
 *       niet op de tijdzone van de server: anders zou "hoogstens één waarde per dag" per omgeving
 *       iets anders betekenen.</li>
 *   <li>De inhoudelijke mutaties van de batch worden {@code SKIPPED} met reden
 *       {@link #SKIPPED_REASON}; de {@code IMPORT_MARKER} blijft {@code RECORDED}. De batch gaat naar
 *       {@code BASELINE_ACCEPTED} (terminaal).</li>
 *   <li>Audit is persistent: {@code accepted_by}/{@code accepted_at} op elke geschreven bronstaatrij,
 *       en wie/wanneer/waarom op de batch (changeset 003).</li>
 *   <li>Een batch die gescreend werd tegen een inmiddels gewijzigde bronstaat (bv. een andere batch van
 *       dezelfde koppeling werd eerst aanvaard) wordt geweigerd ({@link #CODE_SOURCE_STATE_CHANGED}) in
 *       plaats van stilzwijgend overschreven of overgeslagen.</li>
 * </ul>
 * <b>Transacties.</b> Deze orchestrator is niet {@code @Transactional}. Eerst één korte transactie die
 * de voorwaarden controleert, dan één transactie per chunk
 * ({@code catalogimport.screening.mutation-chunk-size}) die de bronstaat schrijft, en tot slot één
 * atomaire transactie die de batch vergrendelt, de status opnieuw controleert, de mutaties op
 * {@code SKIPPED} zet en de batch naar {@code BASELINE_ACCEPTED} brengt.
 * <p>
 * <b>Hervatbaar.</b> Elke chunkschrijfactie is idempotent (insert met {@code not exists}, update enkel
 * bij afwijkende vingerafdruk). Valt de verwerking halverwege weg, dan staat de batch nog op
 * {@code SCREENED} en herhaalt de aanroeper de actie; er ontstaan geen dubbele of foutieve rijen. Rijen
 * die een eerdere poging al schreef, behouden hun {@code accepted_by}/{@code accepted_at}.
 */
@Service
public class SourceStateBaselineService {

    /** De batch staat niet in {@code SCREENED} en kan dus niet als nulmeting aanvaard worden. */
    public static final String CODE_BATCH_NOT_ACCEPTABLE = "BATCH_NOT_ACCEPTABLE";
    /** De bronstaat van de koppeling is gewijzigd sinds deze batch gescreend werd. */
    public static final String CODE_SOURCE_STATE_CHANGED = "SOURCE_STATE_CHANGED_SINCE_SCREENING";
    /** {@code import_mutation.status_reason} van de mutaties die door een aanvaarding overgeslagen worden. */
    public static final String SKIPPED_REASON = "BASELINE_ACCEPTED_WITHOUT_PUBLICATION";
    /**
     * Een kritieke koppelreferentie van deze levering werd tussen de screening en deze aanvaarding
     * door een andere batch actief gemaakt voor een andere aanbieding in dezelfde bibliotheek
     * (R-REF-06). De unieke constraint {@code uk_catalog_reference_state_active} vangt die race op;
     * de chunk wordt volledig teruggedraaid, zodat er nooit half werk blijft staan. De levering moet
     * opnieuw gescreend worden tegen de gewijzigde bibliotheek.
     */
    public static final String CODE_REFERENCE_ALREADY_ACTIVE = "REFERENCE_ALREADY_ACTIVE_FOR_OTHER_OFFER";

    /** {@code catalog_source_state.accepted_by} en {@code import_batch.baseline_accepted_by}: varchar(100). */
    static final int MAX_ACCEPTED_BY_LENGTH = 100;
    /** {@code import_batch.baseline_accept_reason}: varchar(500). */
    static final int MAX_REASON_LENGTH = 500;
    private static final String SYSTEM_USER = "system";

    private static final Logger LOG = LoggerFactory.getLogger(SourceStateBaselineService.class);

    /**
     * Resultaat van een aanvaarding. De tellers zijn die van de screening ({@code null} = onbekend);
     * {@code skippedMutationCount} is het aantal inhoudelijke mutaties dat door déze aanroep op
     * {@code SKIPPED} gezet is.
     */
    public record BaselineAcceptance(long batchId, String status, String acceptedBy, Instant acceptedAt,
                                     String reason, Long newCount, Long changedCount, Long unchangedCount,
                                     int skippedMutationCount) {
    }

    /** Wat buiten een transactie nodig is; bewust geen JPA-entiteiten. */
    private record Prepared(long batchId, long deliveryId, long importLinkId, String libraryCode,
                            String identityProfileKind) {
    }

    private final SourceStateDao sourceState;
    private final CandidatePriceDao candidatePrices;
    private final CandidateReferenceDao candidateReferences;
    private final PriceObservationDao observations;
    private final MutationDao mutations;
    private final ImportBatchRepository batches;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public SourceStateBaselineService(SourceStateDao sourceState, CandidatePriceDao candidatePrices,
                                      CandidateReferenceDao candidateReferences,
                                      PriceObservationDao observations, MutationDao mutations,
                                      ImportBatchRepository batches,
                                      PlatformTransactionManager transactionManager, Clock clock) {
        this.sourceState = sourceState;
        this.candidatePrices = candidatePrices;
        this.candidateReferences = candidateReferences;
        this.observations = observations;
        this.mutations = mutations;
        this.batches = batches;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Aanvaardt de gescreende batch als nulmeting van de bronstaat van haar importkoppeling.
     *
     * @throws IllegalArgumentException ontbrekende of ongeldige {@code acceptedBy}/{@code reason}
     * @throws NotFoundException        onbekende batch ({@code BATCH_NOT_FOUND})
     * @throws ConflictException        {@link #CODE_BATCH_NOT_ACCEPTABLE} (status is niet {@code SCREENED}),
     *                                  {@link #CODE_SOURCE_STATE_CHANGED}
     */
    public BaselineAcceptance acceptBaseline(long batchId, String acceptedBy, String reason) {
        String user = requireAcceptedBy(acceptedBy);
        String motivation = requireText(reason, "reason", MAX_REASON_LENGTH);

        Instant acceptedAt = clock.instant();
        // Eén keer per aanvaarding bepaald en niet per chunk: een verwerking die over middernacht
        // heen loopt, mag haar observaties nooit over twee kalenderdagen verspreiden (R-PRI-13).
        LocalDate observationDate = LocalDate.ofInstant(acceptedAt, PriceObservationDao.OBSERVATION_ZONE);
        Prepared prepared = transaction.execute(status -> prepare(batchId));

        // Chunkgewijs: elke chunk is één transactie en volledig idempotent (design par. 9 stap E).
        AcceptanceContext context = new AcceptanceContext(prepared.importLinkId(), prepared.batchId(),
                prepared.deliveryId(), prepared.libraryCode(), prepared.identityProfileKind(),
                SourceStateOrigin.BASELINE_ACCEPTED.name(), user, acceptedAt, acceptedAt);
        ObservationContext observationContext = new ObservationContext(prepared.importLinkId(),
                prepared.batchId(), observationDate, user, acceptedAt);
        // Draagt deze levering prijscomponenten? Zo niet, blijven catalog_source_state_price-rijen
        // volledig ongemoeid. Een revisie die haar componentmappings verloren heeft, wist zo nooit
        // stilzwijgend eerder aanvaarde verhoudingen: dat vraagt een bewuste herbaselining.
        boolean withPriceComponents = candidatePrices.countByBatchId(batchId) > 0;
        // Draagt deze levering kritieke koppelreferenties? Zo niet, blijft catalog_reference_state
        // volledig ongemoeid en kost de aanvaarding geen enkele extra query.
        boolean withReferences = candidateReferences.hasReferences(batchId);
        long from = 0L;
        Long boundary;
        while ((boundary = sourceState.nextChunkBoundary(batchId, from)) != null) {
            long chunkFrom = from;
            long chunkTo = boundary;
            transaction.executeWithoutResult(status -> {
                sourceState.insertNewFromStage(context, chunkFrom, chunkTo);
                sourceState.updateChangedFromStage(context, chunkFrom, chunkTo);
                if (withPriceComponents) {
                    // Ná de bronstaatrijen zelf: de prijscomponenten hangen eraan met een foreign key.
                    sourceState.insertNewPricesFromStage(context, chunkFrom, chunkTo);
                    sourceState.replaceChangedPricesFromStage(context, chunkFrom, chunkTo);
                }
                if (withReferences) {
                    // R-REF-06: eerste vastlegging van een kritieke koppelreferentie, enkel na
                    // normalisatie, enkel wanneer ze binnen de bibliotheek nog niet actief is en enkel
                    // voor regels die werkelijk aanvaard worden. Een vastgehouden regel
                    // (IDENTITY_INCIDENT) komt hier nooit langs.
                    acceptReferences(context, chunkFrom, chunkTo);
                }
                // De goedgekeurde dagwaarden (R-PRI-13): append-only, in dezelfde transactie als de
                // bronstaatrij waarnaar ze verwijzen. UNCHANGED levert bewust geen observatie op - de
                // historiek bevat vastgelegde goedgekeurde waarden, geen doorgetrokken kalenderdagen.
                observations.insertBasePriceObservations(observationContext, chunkFrom, chunkTo);
                if (withPriceComponents) {
                    observations.insertComponentObservations(observationContext, chunkFrom, chunkTo);
                }
            });
            from = chunkTo;
        }

        BaselineAcceptance result = transaction.execute(status ->
                finish(batchId, user, acceptedAt, motivation));
        LOG.info("Batch {} accepted as baseline by {} ({} mutations skipped): {}", batchId, user,
                result.skippedMutationCount(), motivation);
        return result;
    }

    /**
     * Legt de kritieke koppelreferenties van één chunk vast en zet een gelijktijdige claim door een
     * andere batch om in een duidelijk conflict (R-REF-06).
     * <p>
     * De {@code not exists}-controle en de insert zitten in dezelfde statement maar niet in dezelfde
     * grendel: een andere batch kan de waarde er tussenin actief maken. De unieke constraint
     * {@code uk_catalog_reference_state_active} vangt dat op. Omdat de volledige chunk in één
     * transactie zit, wordt alles van die chunk teruggedraaid — er blijft nooit half werk staan, en de
     * batch blijft op {@code SCREENED} zodat ze opnieuw gescreend kan worden tegen de gewijzigde
     * bibliotheek. Stilzwijgend overslaan zou betekenen dat de aanbieding zonder referentie in de
     * bronstaat belandt en dat het conflict nooit gezien wordt.
     */
    private void acceptReferences(AcceptanceContext context, long fromExclusive, long toInclusive) {
        try {
            sourceState.insertReferencesFromStage(context, fromExclusive, toInclusive);
        } catch (DuplicateKeyException race) {
            throw new ConflictException(CODE_REFERENCE_ALREADY_ACTIVE, "A critical reference of batch "
                    + context.batchId() + " became active for another offer in library "
                    + context.libraryCode() + " while this baseline was being accepted; nothing of this "
                    + "chunk was written. Screen the delivery again before accepting a baseline");
        }
    }

    /** Controleert de voorwaarden; schrijft niets, zodat een afgewezen aanroep nooit iets achterlaat. */
    private Prepared prepare(long batchId) {
        ImportBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new NotFoundException("BATCH_NOT_FOUND", "Batch " + batchId + " not found"));
        requireScreened(batch);
        long importLinkId = batch.getImportLink().getId();
        long stale = sourceState.countRowsStaleSinceScreening(batchId, importLinkId);
        if (stale > 0) {
            throw new ConflictException(CODE_SOURCE_STATE_CHANGED, "The source state of import link "
                    + importLinkId + " changed after batch " + batchId + " was screened (" + stale
                    + " offers affected); screen the delivery again before accepting a baseline");
        }
        return new Prepared(batchId, batch.getDelivery().getId(), importLinkId,
                batch.getImportLink().getLibraryCode(),
                batch.getDefinitionRevision().getIdentityProfileKind().name());
    }

    /**
     * De atomaire eindtransitie: vergrendel de batch, controleer de status opnieuw (een gelijktijdige
     * tweede aanvaarding is dan al klaar), zet de mutaties op {@code SKIPPED} en de batch op
     * {@code BASELINE_ACCEPTED}, met de audit.
     */
    private BaselineAcceptance finish(long batchId, String user, Instant acceptedAt, String reason) {
        ImportBatch batch = batches.findByIdForUpdate(batchId)
                .orElseThrow(() -> new NotFoundException("BATCH_NOT_FOUND", "Batch " + batchId + " not found"));
        requireScreened(batch);
        int skipped = mutations.skipPlannedContentMutations(batchId, SKIPPED_REASON);
        batch.setStatus(ImportBatchStatus.BASELINE_ACCEPTED);
        batch.recordBaselineAcceptance(user, acceptedAt, reason);
        batches.saveAndFlush(batch);
        return new BaselineAcceptance(batchId, batch.getStatus().name(), user, acceptedAt, reason,
                batch.getNewCount(), batch.getChangedCount(), batch.getUnchangedCount(), skipped);
    }

    private static void requireScreened(ImportBatch batch) {
        if (batch.getStatus() != ImportBatchStatus.SCREENED) {
            throw new ConflictException(CODE_BATCH_NOT_ACCEPTABLE, "Batch " + batch.getId() + " is in status "
                    + batch.getStatus() + "; only a batch in SCREENED can be accepted as baseline");
        }
    }

    private static String requireAcceptedBy(String acceptedBy) {
        String user = requireText(acceptedBy, "acceptedBy", MAX_ACCEPTED_BY_LENGTH);
        if (SYSTEM_USER.equalsIgnoreCase(user)) {
            throw new IllegalArgumentException("acceptedBy must be a person, not '" + SYSTEM_USER + "'");
        }
        return user;
    }

    /** Nooit stil afkappen: een te lange waarde wordt geweigerd. */
    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return trimmed;
    }
}
