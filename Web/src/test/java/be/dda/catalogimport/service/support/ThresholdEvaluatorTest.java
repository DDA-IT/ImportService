package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.service.support.ThresholdEvaluator.Judgement;
import be.dda.catalogimport.service.support.ThresholdEvaluator.Outcome;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Bouwstap 3h-4 (ontwerp fase 3 par. 15.2, beslissingslog 20/09): de rekenregels van de twee
 * leveringsdrempels, zonder database en zonder Spring.
 * <p>
 * Wat hier bewezen wordt, is precies wat er bij een grensgeval fout kan gaan: exact op de grens gaat
 * een levering door, één record erboven niet; een niet-geconfigureerde drempel blokkeert nooit; een
 * onbekende teller levert geen oordeel op in plaats van een stille 0; en een scope van 0 met records
 * die beoordeling vragen wordt fail-safe behandeld.
 */
class ThresholdEvaluatorTest {

    private static final BigDecimal ONE_PERCENT = new BigDecimal("1");

    // --- De grens zelf -----------------------------------------------------------------------

    /** 2 van 200 is exact 1%: de vergelijking is {@code >} en niet {@code >=}. */
    @Test
    void lettingExactlyTheThresholdShareThrough() {
        Judgement judgement = ThresholdEvaluator.evaluateCritical(2L, 200L, ONE_PERCENT);

        assertThat(judgement.outcome()).isEqualTo(Outcome.WITHIN);
        assertThat(judgement.blocks()).isFalse();
        assertThat(judgement.sharePercent()).isEqualByComparingTo("1");
        assertThat(judgement.issueCode())
                .isEqualTo(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
    }

    /** Eén record meer is 1,5% en dus wél boven de drempel. */
    @Test
    void blockingOneRecordAboveTheThreshold() {
        Judgement judgement = ThresholdEvaluator.evaluateCritical(3L, 200L, ONE_PERCENT);

        assertThat(judgement.outcome()).isEqualTo(Outcome.EXCEEDED);
        assertThat(judgement.blocks()).isTrue();
        assertThat(judgement.sharePercent()).isEqualByComparingTo("1.5");
    }

    /**
     * Een kleinere scope tilt hetzelfde aantal over de grens: 2 van 199 is geen 1% meer. Bewust
     * vermenigvuldigd en niet gedeeld — een deling zou moeten afronden en juist hier het verkeerde
     * antwoord geven.
     */
    @Test
    void blockingTheSameCountAgainstASmallerScope() {
        assertThat(ThresholdEvaluator.evaluateCritical(2L, 199L, ONE_PERCENT).outcome())
                .isEqualTo(Outcome.EXCEEDED);
    }

    /** Een drempel met decimalen wordt exact gehanteerd, nooit afgerond via een double. */
    @Test
    void comparingAFractionalThresholdExactly() {
        BigDecimal threshold = new BigDecimal("0.125");

        assertThat(ThresholdEvaluator.evaluateRejected(1L, 800L, threshold).outcome())
                .isEqualTo(Outcome.WITHIN);
        assertThat(ThresholdEvaluator.evaluateRejected(2L, 800L, threshold).outcome())
                .isEqualTo(Outcome.EXCEEDED);
    }

    // --- Niets vast te stellen ------------------------------------------------------------------

    /** Nul records ter beoordeling: er valt niets te oordelen, ook niet "binnen de drempel". */
    @Test
    void judgingNothingWhenNoRecordNeedsReview() {
        Judgement judgement = ThresholdEvaluator.evaluateCritical(0L, 200L, ONE_PERCENT);

        assertThat(judgement.outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(judgement.blocks()).isFalse();
    }

    /**
     * Een niet-geconfigureerde drempel ({@code max_rejected_share_percent} is standaard {@code null})
     * wordt nooit overschreden — ze wordt nooit als 0 gelezen, want dan zou één verworpen regel de
     * hele levering stoppen (aanname A18).
     */
    @Test
    void neverBlockingOnAThresholdThatIsNotConfigured() {
        Judgement judgement = ThresholdEvaluator.evaluateRejected(900L, 1000L, null);

        assertThat(judgement.outcome()).isEqualTo(Outcome.WITHIN);
        assertThat(judgement.blocks()).isFalse();
        assertThat(judgement.thresholdPercent()).isNull();
    }

    /** Ook een niet-geconfigureerde drempel bij een scope van 0 blokkeert niet. */
    @Test
    void neverBlockingOnAnUnconfiguredThresholdEvenWithoutScope() {
        assertThat(ThresholdEvaluator.evaluateRejected(5L, 0L, null).blocks()).isFalse();
    }

    // --- Onbekende getallen ---------------------------------------------------------------------

    /** Een onbekende teller of scope levert geen oordeel op, nooit een stille 0. */
    @Test
    void refusingToJudgeOnUnknownCounters() {
        assertThat(ThresholdEvaluator.evaluateCritical(null, 200L, ONE_PERCENT).outcome())
                .isEqualTo(Outcome.UNDETERMINED);
        assertThat(ThresholdEvaluator.evaluateCritical(5L, null, ONE_PERCENT).outcome())
                .isEqualTo(Outcome.UNDETERMINED);
        assertThat(ThresholdEvaluator.evaluateRejected(null, null, ONE_PERCENT).blocks()).isFalse();
    }

    /**
     * De twee bestanddelen van de teller worden opgeteld; is één ervan onbekend, dan is het totaal
     * onbekend en niet "de andere". Een onbekende teller als 0 lezen zou een levering met honderd
     * incidenten stilzwijgend kunnen doorlaten.
     */
    @Test
    void addingCriticalLinesAndHeldIdentityIncidentsAndKeepingUnknownUnknown() {
        assertThat(ThresholdEvaluator.criticalRecordCount(2L, 3L)).isEqualTo(5L);
        assertThat(ThresholdEvaluator.criticalRecordCount(0L, 0L)).isZero();
        assertThat(ThresholdEvaluator.criticalRecordCount(null, 3L)).isNull();
        assertThat(ThresholdEvaluator.criticalRecordCount(2L, null)).isNull();
        assertThat(ThresholdEvaluator.criticalRecordCount(null, null)).isNull();
    }

    // --- Scope 0 --------------------------------------------------------------------------------

    /**
     * Geen enkel record in scope, maar wél records die beoordeling vragen: dat kan alleen wanneer
     * recordfilters alles uitgefilterd hebben terwijl er vóór het filter al onherleidbare fouten
     * waren. Er bestaat dan geen percentage, en doorlaten zou de strengste vaststelling van de
     * levering laten verdwijnen — dus fail-safe blokkeren, zonder een aandeel te verzinnen.
     */
    @Test
    void blockingFailSafeWhenThereIsNoRecordInScopeAtAll() {
        Judgement judgement = ThresholdEvaluator.evaluateCritical(4L, 0L, ONE_PERCENT);

        assertThat(judgement.outcome()).isEqualTo(Outcome.EXCEEDED);
        assertThat(judgement.sharePercent()).isNull();
        assertThat(judgement.scope()).isZero();
    }

    /** Zonder records ter beoordeling is een scope van 0 gewoon niets: nooit een blokkade. */
    @Test
    void notBlockingOnAnEmptyScopeWhenNothingNeedsReview() {
        assertThat(ThresholdEvaluator.evaluateCritical(0L, 0L, ONE_PERCENT).outcome())
                .isEqualTo(Outcome.NOT_APPLICABLE);
    }

    // --- De melding -----------------------------------------------------------------------------

    /**
     * Een melding draagt de aantallen, het percentage en de scope — nooit enkel een oordeel — en
     * blijft binnen {@code import_row_issue.message} (varchar(500)).
     */
    @Test
    void explainingTheBlockadeWithTheNumbersBehindIt() {
        Judgement critical = ThresholdEvaluator.evaluateCritical(3L, 200L, ONE_PERCENT);
        String message = ThresholdEvaluator.criticalMessage(critical, 2L, 1L);

        assertThat(message).contains("'3'").contains("2 critical lines").contains("1 held identity")
                .contains("200").contains("1.5").contains("blocked");
        assertThat(message.length()).isLessThan(500);

        Judgement rejected = ThresholdEvaluator.evaluateRejected(51L, 1000L, new BigDecimal("5"));
        String rejectedMessage = ThresholdEvaluator.rejectedMessage(rejected);

        assertThat(rejectedMessage).contains("'51'").contains("1000").contains("5.1")
                .contains("rejection threshold of 5%");
        assertThat(rejectedMessage.length()).isLessThan(500);
    }
}
