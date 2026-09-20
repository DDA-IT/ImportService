package be.dda.catalogimport.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * De twee <b>leveringsdrempels</b> van één levering (bouwstap 3h-4, ontwerp fase 3 par. 15.2/15.3,
 * beslissingslog 20/09): hoeveel records mogen beoordeling vragen, en hoeveel regels mogen verworpen
 * worden, voordat de levering als geheel onbetrouwbaar is?
 * <p>
 * Pure rekenkunde, geen database en geen toestand — net als {@link CreationPolicyEvaluator} en
 * {@link PriceDeviationEvaluator}. De aanroeper ({@code DeliveryScreeningService}, pass E4b) haalt de
 * tellers uit de database, laat hier oordelen en blokkeert de levering via het bestaande
 * blokkeerpad.
 *
 * <h2>De twee regels</h2>
 * <ul>
 *   <li><b>Records ter beoordeling</b> ({@code criticalRecordCount = critical_line_count +
 *       identity_incident_count}): {@code 0} ⇒ er valt niets te beoordelen; {@code > 0} maar niet
 *       boven {@code max_critical_share_percent} ⇒ de levering gaat door en vraagt een review (deze
 *       bouwstap wijzigt {@code validation_result} daarvoor nog niet — dat is 3h-5); boven de drempel
 *       ⇒ de hele levering wordt geblokkeerd met
 *       {@link ImportIssueCatalog#CRITICAL_RECORD_THRESHOLD_EXCEEDED}.</li>
 *   <li><b>Verworpen regels</b> ({@code rejected_record_count}): enkel wanneer
 *       {@code max_rejected_share_percent} geconfigureerd is; boven de drempel ⇒ geblokkeerd met
 *       {@link ImportIssueCatalog#REJECTED_RECORD_THRESHOLD_EXCEEDED}.</li>
 * </ul>
 * De scope (de noemer) is voor allebei dezelfde: de records die binnen de importscope van de levering
 * vielen, {@code raw_record_count − filtered_out_count}. Een uitgefilterd record is nooit
 * gecontroleerd en hoort dus niet in de noemer (R-FLT-02/R-FLT-04).
 *
 * <h2>Rekenregels die hier hard zijn</h2>
 * <ul>
 *   <li><b>Altijd een percentage, nooit een vast aantal</b> (beslissingslog 20/09). De absolute
 *       kolommen {@code max_critical_records} en {@code max_rejected_records} bestaan nog in het
 *       schema, maar worden door geen enkele verwerking meer gelezen; ze zijn bewust niet hernoemd of
 *       verwijderd.</li>
 *   <li><b>Vermenigvuldigen, nooit delen:</b> {@code aantal × 100 > percentage × scope}. Een deling
 *       zou moeten afronden en juist op de grens het verkeerde antwoord geven. Alles is
 *       {@link BigDecimal}; er komt nergens een {@code double} aan te pas.</li>
 *   <li><b>Exact op de grens is niet overschreden:</b> 2 van 200 bij 1% gaat door, 3 niet.</li>
 *   <li><b>Een niet-geconfigureerde drempel ({@code null}) wordt nooit overschreden.</b> Ze wordt
 *       nooit als 0 gelezen — dat zou betekenen dat één enkel geval de levering stopt.</li>
 *   <li><b>Een onbekende teller of scope ({@code null}) levert geen oordeel op</b>
 *       ({@link Outcome#UNDETERMINED}), nooit een stille 0. Dat gebeurt wanneer het bronbestand niet
 *       volledig gelezen is; zo'n levering is dan al om een andere reden gestrand.</li>
 *   <li><b>Scope 0 met records ter beoordeling ⇒ overschreden</b> (fail-safe). Zonder noemer bestaat
 *       er geen percentage, maar er staat wél vast dat élk record in scope beoordeling vraagt — er is
 *       immers geen enkel record in scope. Dat kan alleen wanneer recordfilters alles uitgefilterd
 *       hebben terwijl er vóór het filter al onherleidbare fouten waren. Doorlaten zou hier betekenen
 *       dat de strengste vaststelling van de levering stilzwijgend verdwijnt.</li>
 * </ul>
 */
public final class ThresholdEvaluator {

    /** Schaal van elk percentage in dit project (ontwerp fase 3 par. 3.4). */
    private static final int PERCENTAGE_SCALE = 12;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Wat er over één drempel vastgesteld is. */
    public enum Outcome {
        /** Niets vastgesteld: de teller staat op 0, of de drempel is niet geconfigureerd. */
        NOT_APPLICABLE,
        /** Geen oordeel mogelijk: de teller of de scope is onbekend ({@code null}). */
        UNDETERMINED,
        /** Er is iets vastgesteld, maar het blijft binnen de drempel: de levering gaat door. */
        WITHIN,
        /** Boven de drempel: de volledige levering wordt geblokkeerd. */
        EXCEEDED
    }

    /**
     * Het oordeel over één drempel, met alles wat nodig is om het te verantwoorden.
     *
     * @param count            de teller waarover geoordeeld is, of {@code null} als ze onbekend was
     * @param scope            de records in scope van de levering, of {@code null} als die onbekend was
     * @param sharePercent     het aandeel van {@code count} in {@code scope}, of {@code null} wanneer
     *                         de scope 0 of onbekend is — dan bestaat er geen percentage en wordt er
     *                         niets verzonnen (geen 0%, geen 100%)
     * @param thresholdPercent de gehanteerde drempel, of {@code null} wanneer ze niet geconfigureerd is
     * @param issueCode        de foutcode die bij een overschrijding hoort; ook gevuld wanneer er
     *                         (nog) geen overschrijding is, zodat de aanroeper één code per drempel
     *                         kent
     */
    public record Judgement(Outcome outcome, Long count, Long scope, BigDecimal sharePercent,
                            BigDecimal thresholdPercent, String issueCode) {

        /** Blokkeert deze drempel de levering? */
        public boolean blocks() {
            return outcome == Outcome.EXCEEDED;
        }
    }

    private ThresholdEvaluator() {
    }

    /**
     * De records die beoordeling vragen: kritieke lijnen plus vastgehouden identiteitsincidenten
     * (ontwerp par. 15.2). De twee verzamelingen zijn strikt disjunct — een kritieke lijn wordt nooit
     * gestaged en een vastgehouden regel is juist wél geldig gelezen — dus optellen telt niets dubbel.
     * <p>
     * <b>Is één van beide onbekend ({@code null}), dan is het totaal onbekend</b> en niet "de andere".
     * Een onbekende teller als 0 lezen zou een levering met honderd incidenten stilzwijgend kunnen
     * doorlaten; geen oordeel is dan het eerlijke antwoord, en de levering strandt in dat geval toch
     * al op de reden waardoor de teller onbekend bleef.
     *
     * @return het totaal, of {@code null} wanneer een van beide tellers onbekend is
     */
    public static Long criticalRecordCount(Long criticalLineCount, Long identityIncidentCount) {
        if (criticalLineCount == null || identityIncidentCount == null) {
            return null;
        }
        return criticalLineCount + identityIncidentCount;
    }

    /**
     * De drempel op de records ter beoordeling.
     *
     * @param criticalRecordCount uit {@link #criticalRecordCount(Long, Long)}; {@code null} is onbekend
     * @param scope               {@code raw_record_count − filtered_out_count}; {@code null} is onbekend
     * @param thresholdPercent    {@code max_critical_share_percent} van de revisie
     */
    public static Judgement evaluateCritical(Long criticalRecordCount, Long scope,
                                             BigDecimal thresholdPercent) {
        return evaluate(criticalRecordCount, scope, thresholdPercent,
                ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
    }

    /**
     * De drempel op de verworpen regels.
     *
     * @param rejectedRecordCount {@code import_batch.rejected_record_count}; {@code null} is onbekend
     * @param scope               {@code raw_record_count − filtered_out_count}; {@code null} is onbekend
     * @param thresholdPercent    {@code max_rejected_share_percent} van de revisie; {@code null} is
     *                            "niet geconfigureerd" en wordt dus nooit overschreden
     */
    public static Judgement evaluateRejected(Long rejectedRecordCount, Long scope,
                                             BigDecimal thresholdPercent) {
        return evaluate(rejectedRecordCount, scope, thresholdPercent,
                ImportIssueCatalog.REJECTED_RECORD_THRESHOLD_EXCEEDED);
    }

    /**
     * De melding bij een overschrijding, met de aantallen, het percentage en de scope erin
     * (meldingsstijl par. 15.12) — nooit enkel een oordeel. Blijft ruim onder de 500 tekens van
     * {@code import_row_issue.message}.
     *
     * @param criticalLineCount     enkel voor de kritieke drempel, om de twee bestanddelen te tonen
     * @param identityIncidentCount idem
     */
    public static String criticalMessage(Judgement judgement, Long criticalLineCount,
                                         Long identityIncidentCount) {
        return "criticalRecords: '" + judgement.count() + "' records of this delivery need review ("
                + criticalLineCount + " critical lines + " + identityIncidentCount
                + " held identity incidents), against " + judgement.scope()
                + " records in scope" + share(judgement) + "; that is above the review threshold of "
                + threshold(judgement) + "% of the scope, so the whole delivery is blocked. No content "
                + "mutation was generated; the staging and every recorded issue are kept as evidence";
    }

    /** Idem voor de drempel op de verworpen regels. */
    public static String rejectedMessage(Judgement judgement) {
        return "rejectedRecords: '" + judgement.count() + "' source lines of this delivery were "
                + "rejected, against " + judgement.scope() + " records in scope" + share(judgement)
                + "; that is above the rejection threshold of " + threshold(judgement)
                + "% of the scope, so the whole delivery is blocked. No content mutation was "
                + "generated; the staging and every recorded issue are kept as evidence";
    }

    private static Judgement evaluate(Long count, Long scope, BigDecimal thresholdPercent,
                                      String issueCode) {
        if (count == null || scope == null) {
            // Geen oordeel: een onbekende teller of noemer wordt nooit stil 0.
            return new Judgement(Outcome.UNDETERMINED, count, scope, null, thresholdPercent, issueCode);
        }
        BigDecimal share = share(count, scope);
        if (count <= 0) {
            return new Judgement(Outcome.NOT_APPLICABLE, count, scope, share, thresholdPercent,
                    issueCode);
        }
        if (thresholdPercent == null) {
            // Niet geconfigureerd is nooit overschreden (aanname A18), nooit 0.
            return new Judgement(Outcome.WITHIN, count, scope, share, null, issueCode);
        }
        if (scope <= 0) {
            // Fail-safe: er is wél iets vastgesteld, maar er is geen enkel record in scope om het
            // tegen af te wegen. Doorlaten zou de strengste vaststelling laten verdwijnen.
            return new Judgement(Outcome.EXCEEDED, count, scope, null, thresholdPercent, issueCode);
        }
        Outcome outcome = exceeds(count, scope, thresholdPercent) ? Outcome.EXCEEDED : Outcome.WITHIN;
        return new Judgement(outcome, count, scope, share, thresholdPercent, issueCode);
    }

    /**
     * Het aandeel in de scope, schaal {@value #PERCENTAGE_SCALE}. {@code null} wanneer de scope 0 is:
     * een percentage zonder noemer bestaat niet.
     */
    private static BigDecimal share(long count, long scope) {
        if (scope <= 0) {
            return null;
        }
        return BigDecimal.valueOf(count).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(scope), PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }

    /** {@code aantal × 100 > percentage × scope}; exact op de grens is niet overschreden. */
    private static boolean exceeds(long count, long scope, BigDecimal thresholdPercent) {
        return BigDecimal.valueOf(count).multiply(HUNDRED)
                .compareTo(thresholdPercent.multiply(BigDecimal.valueOf(scope))) > 0;
    }

    private static String share(Judgement judgement) {
        return judgement.sharePercent() == null ? ""
                : " (" + judgement.sharePercent().toPlainString() + "% of the scope)";
    }

    private static String threshold(Judgement judgement) {
        return judgement.thresholdPercent() == null ? "-" : judgement.thresholdPercent().toPlainString();
    }
}
