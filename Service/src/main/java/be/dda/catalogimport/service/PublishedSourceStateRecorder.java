package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CandidatePriceDao;
import be.dda.catalogimport.dao.PriceObservationDao;
import be.dda.catalogimport.dao.PriceObservationDao.ObservationContext;
import be.dda.catalogimport.dao.SourceStateDao;
import be.dda.catalogimport.dao.SourceStateDao.LockedState;
import be.dda.catalogimport.dao.SourceStateDao.PublicationSource;
import be.dda.catalogimport.dao.SourceStateDao.PublicationWrite;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.SourceStateOrigin;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legt na een geslaagde publicatie naar Prodis de <b>bronwaarden</b> van de mutatie vast als nieuwe bronstaat (P5c;
 * beslissing 2026-10-22 Q3, herroept aanname A7): {@code catalog_source_state}, {@code catalog_source_state_price} en
 * {@code catalog_price_observation}, met {@code state_origin = PUBLISHED}. Zonder dit stelt elke volgende levering dezelfde
 * mutatie opnieuw voor.
 *
 * <h2>Business rule → technische implementatie</h2>
 * <ul>
 *   <li><b>Wanneer:</b> enkel bij {@code APPLIED} en {@code UNCHANGED}, in <b>dezelfde</b> CI-transactie als het
 *       itemresultaat ({@link ProdisPublicationService}, CI-transactie 2). Bij {@code REJECTED}/{@code CONFLICT}/{@code
 *       NOT_SENT} wordt deze klasse niet aangeroepen: niets.</li>
 *   <li><b>Wat:</b> de bronwaarden uit de stagerij van de mutatie ({@code batch_id} + {@code source_row_number}) —
 *       vingerafdrukken, basisprijs, munt, prijscomponenten — precies zoals {@link SourceStateBaselineService} een
 *       gewijzigde regel aanvaardt. <b>Nooit</b> de door Prodis berekende waarden (VKP-bedragen, afrondingen): de bronstaat is
 *       "wat de leverancier laatst leverde en wij aanvaardden", de vergelijkingsbasis van de volgende screening.</li>
 *   <li><b>Veiligheid:</b> de bronstaatrij wordt vergrendeld en enkel bijgewerkt als ze nog de "voor"-toestand van de
 *       mutatie is ({@code before_combined_fingerprint}); is ze al de stagewaarde, dan is er niets te doen. Elke andere
 *       toestand (intussen door een andere aanvaarding gewijzigd, verdwenen, stagerij opgeruimd, een CREATE, een component
 *       zonder verhouding) schrijft <b>niets</b> en geeft een uitkomst {@code NOT_UPDATED_*} terug die de aanroeper zichtbaar
 *       maakt (melding {@value #NOTICE_SOURCE_STATE_NOT_UPDATED} op het item, logregel). Nooit stil overschrijven: de
 *       Prodis-uitkomst blijft wat ze is.</li>
 *   <li><b>Observaties:</b> enkel bij een echte bijwerking (zoals de aanvaarding: "UNCHANGED levert geen observatie"), één
 *       per component per kalenderdag (UTC, {@link PriceObservationDao#OBSERVATION_ZONE}), de eerste van de dag wint.</li>
 *   <li><b>Niet:</b> {@code catalog_reference_state} (kritieke referenties) — Q3 noemt enkel de drie tabellen hierboven.</li>
 * </ul>
 * Een databasefout gaat naar boven: de aanroeper draait dan de hele CI-transactie terug (het item wordt
 * {@code RESULT_UNKNOWN}, beslissing P5b punt 6); een nieuwe poging vindt in Prodis de doelwaarde ({@code UNCHANGED}) en
 * schrijft de bronstaat alsnog.
 */
@Service
public class PublishedSourceStateRecorder {

    /** WARNING op het item ({@code warnings_json}): de bronstaat werd na een geslaagde publicatie niet bijgewerkt. */
    public static final String NOTICE_SOURCE_STATE_NOT_UPDATED = "SOURCE_STATE_NOT_UPDATED";

    private static final Logger LOG = LoggerFactory.getLogger(PublishedSourceStateRecorder.class);

    /** De uitkomst; enkel {@link #UPDATED} en {@link #ALREADY_CURRENT} laten de bronstaat op de gepubliceerde waarde. */
    public enum Outcome {
        /** Bronstaat, prijscomponenten en dagwaarden bijgewerkt naar de bronwaarden van de mutatie. */
        UPDATED,
        /** De bronstaat was al de bronwaarde van de mutatie; niets geschreven. */
        ALREADY_CURRENT,
        /** Geen stagerij (opgeruimd of nooit gestaged), of ze hoort bij een andere identiteit; niets geschreven. */
        NOT_UPDATED_STAGE_NOT_AVAILABLE,
        /** De bronstaat bestaat niet (meer) voor deze identiteit; niets geschreven. */
        NOT_UPDATED_SOURCE_STATE_MISSING,
        /** De bronstaat wijzigde sinds de screening (een andere aanvaarding/publicatie); niets overschreven. */
        NOT_UPDATED_SOURCE_STATE_CHANGED,
        /** Een prijscomponent van de regel heeft geen verhouding (de bronstaat aanvaardt dat niet); niets geschreven. */
        NOT_UPDATED_COMPONENT_WITHOUT_PERCENTAGE,
        /** Geen UPDATE (CREATE volgt pas met P8); niets geschreven. */
        NOT_UPDATED_ACTION_NOT_SUPPORTED;

        public boolean current() {
            return this == UPDATED || this == ALREADY_CURRENT;
        }
    }

    /**
     * @param sourceStateId de betrokken bronstaatrij (CatalogImport-id), {@code null} als er geen is
     * @param message       leesbare uitleg bij een {@code NOT_UPDATED_*}-uitkomst, anders {@code null}
     */
    public record Result(Outcome outcome, Long sourceStateId, int observationsWritten, String message) {
    }

    private final SourceStateDao sourceState;
    private final CandidatePriceDao candidatePrices;
    private final PriceObservationDao observations;

    public PublishedSourceStateRecorder(SourceStateDao sourceState, CandidatePriceDao candidatePrices,
                                        PriceObservationDao observations) {
        this.sourceState = sourceState;
        this.candidatePrices = candidatePrices;
        this.observations = observations;
    }

    /**
     * Zie de klassebeschrijving. Moet binnen de transactie van het itemresultaat lopen ({@link Propagation#MANDATORY}).
     *
     * @param publishedBy de verzender ({@code accepted_by}); nooit {@code system}
     * @param publishedAt het antwoordtijdstip ({@code accepted_at}, {@code updated_at}, dag van de observatie)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result recordPublished(long mutationId, String publishedBy, Instant publishedAt) {
        PublicationSource source = sourceState.findPublicationSource(mutationId).orElseThrow(
                () -> new IllegalStateException("Mutation " + mutationId + " not found"));
        if (!MutationActionType.UPDATE.name().equals(source.actionType())) {
            return notUpdated(source, Outcome.NOT_UPDATED_ACTION_NOT_SUPPORTED, null, "Mutation " + mutationId
                    + " is a " + source.actionType() + "; only an UPDATE updates the source state in v1");
        }
        if (source.stageRowNumber() == null || !Arrays.equals(source.stageIdentityHash(), source.identityHash())) {
            return notUpdated(source, Outcome.NOT_UPDATED_STAGE_NOT_AVAILABLE, null, "The staged row of mutation "
                    + mutationId + " (batch " + source.batchId() + ", row " + source.sourceRowNumber()
                    + ") is not available; the source state was not updated");
        }
        Optional<LockedState> locked = sourceState.lockStateForPublication(source.importLinkId(),
                source.identityHash());
        if (locked.isEmpty()) {
            return notUpdated(source, Outcome.NOT_UPDATED_SOURCE_STATE_MISSING, null, "No source state exists for "
                    + "the offer of mutation " + mutationId + "; the source state was not updated");
        }
        LockedState state = locked.get();
        if (Arrays.equals(state.combinedFingerprint(), source.stageCombinedFingerprint())) {
            return new Result(Outcome.ALREADY_CURRENT, state.id(), 0, null);
        }
        if (!Arrays.equals(state.combinedFingerprint(), source.beforeCombinedFingerprint())) {
            return notUpdated(source, Outcome.NOT_UPDATED_SOURCE_STATE_CHANGED, state.id(), "The source state of "
                    + "the offer of mutation " + mutationId + " changed after the delivery was screened (another "
                    + "acceptance or publication); it was not overwritten");
        }
        boolean withPriceComponents = candidatePrices.countByBatchId(source.batchId()) > 0;
        if (withPriceComponents && sourceState.countStagedComponentsWithoutPercentage(source.batchId(),
                source.stageRowNumber()) > 0) {
            return notUpdated(source, Outcome.NOT_UPDATED_COMPONENT_WITHOUT_PERCENTAGE, state.id(), "A price "
                    + "component of mutation " + mutationId + " has no percentage; the source state was not updated");
        }

        PublicationWrite write = new PublicationWrite(SourceStateOrigin.PUBLISHED.name(), publishedBy, publishedAt);
        if (sourceState.updatePublishedFromStage(state.id(), source, write) != 1) {
            throw new IllegalStateException("Source state " + state.id() + " of mutation " + mutationId
                    + " was not updated");
        }
        if (withPriceComponents) {
            // Zelfde regel als de aanvaarding: een batch zonder componenten wist nooit eerder aanvaarde verhoudingen.
            sourceState.replacePublishedPrices(state.id(), source, publishedAt);
        }
        LocalDate observationDate = LocalDate.ofInstant(publishedAt, PriceObservationDao.OBSERVATION_ZONE);
        int written = observations.insertPublishedObservations(new ObservationContext(source.importLinkId(),
                source.batchId(), observationDate, publishedBy, publishedAt), state.id(), source.stageRowNumber());
        LOG.info("Mutation {}: source state {} updated to the published source values (state_origin PUBLISHED, {} "
                + "observation(s))", mutationId, state.id(), written);
        return new Result(Outcome.UPDATED, state.id(), written, null);
    }

    private static Result notUpdated(PublicationSource source, Outcome outcome, Long stateId, String message) {
        LOG.warn("Mutation {} was published but its source state was NOT updated ({}): {}", source.mutationId(),
                outcome, message);
        return new Result(outcome, stateId, 0, message);
    }
}
