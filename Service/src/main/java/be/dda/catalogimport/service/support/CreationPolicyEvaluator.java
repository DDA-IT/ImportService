package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.CreationOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Het <b>creatiebeleid</b> van één levering (ontwerp fase 3 par. 15.2, R-THR-01, beslissingslog
 * 20/09): mogen de nieuwe aanbiedingen zonder menselijke tussenkomst aangemaakt worden?
 * <p>
 * Pure rekenkunde, geen database en geen toestand — net als {@link PriceDeviationEvaluator}. De
 * aanroeper ({@code DeliveryScreeningService}, pass E4b) haalt de twee getallen op en legt de
 * uitkomst vast.
 *
 * <h2>De regel</h2>
 * <ul>
 *   <li><b>Geen enkele kandidaat</b> ⇒ {@link CreationOutcome#AUTOMATIC}. Ook bij een lege bronstaat:
 *       een levering die niets aanmaakt, vraagt niets te beslissen.</li>
 *   <li><b>Lege bronstaat met kandidaten</b> ⇒ {@link CreationOutcome#INITIAL_LOAD}. Dit is de eerste
 *       levering van de koppeling; zonder bestaande omvang bestaat er geen percentage, dus wordt er
 *       nooit óók een bulkcreatie gemeld (initialisatie wint van de creatiedrempel).</li>
 *   <li><b>Boven de drempel</b> ⇒ {@link CreationOutcome#THRESHOLD_EXCEEDED}, met de vergelijking
 *       {@code kandidaten × 100 > percentage × omvang}. Bewust een vermenigvuldiging en geen deling:
 *       een deling zou moeten afronden en juist op de grens het verkeerde antwoord kunnen geven.
 *       <b>Exact op de grens is niet overschreden</b> (100 van 10.000 bij 1% gaat door, 101 niet).</li>
 *   <li>Anders {@link CreationOutcome#AUTOMATIC}.</li>
 * </ul>
 * Er is <b>geen absolute grens</b> meer ({@code creation_threshold_absolute} blijft bestaan maar wordt
 * niet meer gebruikt): elke drempel is altijd een percentage van de omvang (beslissingslog 20/09). Bij
 * een kleine koppeling werkt dat grof — 1% van tien aanbiedingen is 0,1 — en dat is de bewuste keuze:
 * wie daar automatisch wil laten creëren, zet {@code creation_threshold_share_percent} van die revisie
 * hoger. Nooit met drijvende komma, nooit een stille nul.
 */
public final class CreationPolicyEvaluator {

    /** Schaal van elk percentage in dit project (ontwerp fase 3 par. 3.4). */
    private static final int PERCENTAGE_SCALE = 12;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * De uitkomst van het beleid, met alles wat nodig is om ze te verantwoorden.
     *
     * @param scope      het aantal actieve aanbiedingen van de koppeling (de noemer)
     * @param candidates het aantal regels dat na goedkeuring een creatie zou worden (de teller)
     * @param sharePercent het aandeel, of {@code null} wanneer de omvang 0 is — dan bestaat er geen
     *                     percentage en wordt er niets verzonnen (geen 0%, geen 100%)
     * @param issueCode  de foutcode die bij deze uitkomst hoort, of {@code null} bij
     *                   {@link CreationOutcome#AUTOMATIC}: dan is er niets te melden
     */
    public record Decision(CreationOutcome outcome, long scope, long candidates,
                           BigDecimal sharePercent, BigDecimal thresholdPercent, String issueCode) {

        /** De reden die op de wachtende {@code CREATE}-mutaties komt; {@code null} bij AUTOMATIC. */
        public String statusReason() {
            return issueCode;
        }
    }

    private CreationPolicyEvaluator() {
    }

    /**
     * @param scope            het aantal actieve {@code catalog_source_state}-rijen van de koppeling
     * @param candidates       het aantal creatiekandidaten van deze batch
     * @param thresholdPercent {@code creation_threshold_share_percent} van de revisie; {@code null}
     *                         wordt behandeld als de standaard 1%, nooit als "geen drempel"
     */
    public static Decision evaluate(long scope, long candidates, BigDecimal thresholdPercent) {
        BigDecimal threshold = thresholdPercent == null ? BigDecimal.ONE : thresholdPercent;
        BigDecimal share = share(candidates, scope);
        if (candidates <= 0) {
            return new Decision(CreationOutcome.AUTOMATIC, scope, candidates, share, threshold, null);
        }
        if (scope <= 0) {
            return new Decision(CreationOutcome.INITIAL_LOAD, scope, candidates, share, threshold,
                    ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL);
        }
        if (exceeds(candidates, scope, threshold)) {
            return new Decision(CreationOutcome.THRESHOLD_EXCEEDED, scope, candidates, share, threshold,
                    ImportIssueCatalog.BULK_CREATION_INCIDENT);
        }
        return new Decision(CreationOutcome.AUTOMATIC, scope, candidates, share, threshold, null);
    }

    /** De melding bij een uitkomst die goedkeuring vraagt; met de aantallen, nooit enkel een oordeel. */
    public static String message(Decision decision) {
        if (decision.outcome() == CreationOutcome.INITIAL_LOAD) {
            return "creationCandidates: '" + decision.candidates() + "' offers of this delivery would be "
                    + "created while import link has no accepted offer yet; this is the first delivery of "
                    + "the link, so every creation waits for approval. No percentage applies: without an "
                    + "existing scope there is no share to compare";
        }
        return "creationCandidates: '" + decision.candidates() + "' offers of this delivery would be "
                + "created, against " + decision.scope() + " active offers of the import link ("
                + (decision.sharePercent() == null ? "" : decision.sharePercent().toPlainString() + "% ")
                + "of the existing scope); that is above the creation threshold of "
                + decision.thresholdPercent().toPlainString() + "% of the scope, so every creation waits "
                + "for approval. Updates of existing offers are not affected";
    }

    /**
     * Het aandeel in de bestaande omvang, schaal {@value #PERCENTAGE_SCALE}. {@code null} wanneer de
     * omvang 0 is: een percentage zonder noemer bestaat niet.
     */
    private static BigDecimal share(long candidates, long scope) {
        if (scope <= 0) {
            return null;
        }
        return BigDecimal.valueOf(candidates).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(scope), PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }

    /** {@code kandidaten × 100 > percentage × omvang}; exact op de grens is niet overschreden. */
    private static boolean exceeds(long candidates, long scope, BigDecimal thresholdPercent) {
        return BigDecimal.valueOf(candidates).multiply(HUNDRED)
                .compareTo(thresholdPercent.multiply(BigDecimal.valueOf(scope))) > 0;
    }
}
