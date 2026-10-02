package be.dda.catalogimport.service;

/**
 * De verwerkingsclaim van een batch is niet meer van deze worker (stap 4, beslissingslog 2026-10-01): de
 * fencing-update vond geen rij met de eigen token, omdat de lease verlopen was en een andere worker of het
 * opstartherstel de claim overnam of wiste.
 * <p>
 * Wie dit vangt, <b>stopt</b>: de lopende transactie rolt terug en er wordt niets meer geschreven, ook geen
 * {@code FAILED}-opruiming — de batch is van een ander. Naar buiten wordt het een {@link ConflictException} met
 * code {@link BatchProcessingClaims#CODE_BATCH_BEING_PROCESSED}.
 */
public class ClaimLostException extends RuntimeException {

    private final long batchId;

    public ClaimLostException(long batchId) {
        super("The processing claim on batch " + batchId + " was lost; another worker owns this batch now");
        this.batchId = batchId;
    }

    public long getBatchId() {
        return batchId;
    }
}
