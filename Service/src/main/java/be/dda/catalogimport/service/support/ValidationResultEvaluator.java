package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.ValidationResult;
import java.util.Collection;
import java.util.Map;

/**
 * Het <b>eindoordeel</b> over één levering (bouwstap 3h-5, ontwerp fase 3 par. 15.3, beslissingslog
 * 20/09): {@code import_batch.validation_result}, de statusas naast {@code import_batch.status}.
 * <p>
 * Pure rekenkunde, geen database en geen toestand — net als {@link ThresholdEvaluator} en
 * {@link CreationPolicyEvaluator}. De aanroeper ({@code DeliveryScreeningService}, stap F) meet de
 * ingrediënten en legt de uitkomst vast.
 *
 * <h2>De beslissingstabel (par. 15.3)</h2>
 * <pre>
 * BLOCKING            als de batch geblokkeerd is of er ≥1 issue met effect BLOCK bestaat
 * REVIEW_REQUIRED     anders, als criticalRecordCount &gt; 0 (kritieke lijnen + vastgehouden
 *                     identiteitsincidenten), of ≥1 issue met effect REVIEW, of
 *                     awaitingApprovalCount &gt; 0
 * VALID_WITH_WARNINGS anders, als er ≥1 issue van ernst ERROR of WARNING is (INFO telt niet mee)
 * VALID               anders
 * </pre>
 *
 * <h2>Waarom niet meer uit de ernst</h2>
 * Tot bouwstap 3g volgde het oordeel uit {@link RowIssueSeverity#isBlockingForBatch()}. Daardoor
 * zette elke kritieke vaststelling — een gewijzigde EAN, een bulkincident, een eerste levering — het
 * oordeel op {@code BLOCKING}, terwijl de batch gewoon op {@code SCREENED} eindigde met haar
 * volledige mutatielijst. Het oordeel zei dan "onbruikbaar" over een levering die enkel een
 * <b>beoordeling</b> vroeg. Sinds deze bouwstap komt het uit {@link DeliveryEffect} per foutcode,
 * dat die twee vragen uit elkaar houdt.
 *
 * <h2>Regels die hier hard zijn</h2>
 * <ul>
 *   <li><b>Een verworpen regel op een niet-kritieke kolom is nooit {@code VALID}</b>
 *       (beslissingslog 20/09): ze is een {@code ERROR} en levert dus minstens
 *       {@code VALID_WITH_WARNINGS} op. Een beoordeling vraagt ze niet.</li>
 *   <li><b>Eén kritieke lijn is genoeg voor een review</b>, ook zonder waarschuwing en ook ver onder
 *       de drempel: de gebruiker heeft die kolom zelf als kritiek aangemerkt.</li>
 *   <li><b>{@code INFO} telt nooit mee.</b> Een toegepaste standaardwaarde of een voorgestelde
 *       artikelkoppeling verandert het oordeel niet.</li>
 *   <li><b>Onbekend is niet 0.</b> Een {@code null}-teller wordt nooit als "geen" gelezen, maar ook
 *       nooit als "wel": ze levert enkel geen review op. Een technisch mislukte batch krijgt
 *       helemaal geen oordeel ({@code validation_result} blijft {@code null}) — dat regelt de
 *       aanroeper, niet deze klasse.</li>
 * </ul>
 */
public final class ValidationResultEvaluator {

    private ValidationResultEvaluator() {
    }

    /**
     * @param blocked              of de batch op {@code BLOCKED} eindigt
     * @param effects              het effect van élke foutcode die in deze batch voorkomt; de
     *                             aanroeper haalt de voorkomende codes uit de database en laat ze
     *                             door {@link ImportIssueCatalog} classificeren. "≥1 issue met
     *                             effect X" is dus het <b>bestaan</b> van een code met dat effect —
     *                             aantallen doen hier niet ter zake, en de voorbeeldcap laat per code
     *                             altijd minstens één rij staan
     * @param severityCounts       het aantal issuerijen per ernst (voorbeeldrijen volstaan: ook hier
     *                             gaat het om bestaan, niet om volume)
     * @param criticalRecordCount  kritieke lijnen + vastgehouden identiteitsincidenten, of
     *                             {@code null} wanneer dat niet vastgesteld is
     * @param awaitingApprovalCount het aantal {@code CREATE}/{@code UPDATE}-mutaties dat op
     *                             goedkeuring wacht, of {@code null} wanneer dat niet gemeten is
     */
    public static ValidationResult evaluate(boolean blocked, Collection<DeliveryEffect> effects,
                                            Map<RowIssueSeverity, Long> severityCounts,
                                            Long criticalRecordCount, Long awaitingApprovalCount) {
        if (blocked || effects.contains(DeliveryEffect.BLOCK)) {
            return ValidationResult.BLOCKING;
        }
        if (isPositive(criticalRecordCount) || effects.contains(DeliveryEffect.REVIEW)
                || isPositive(awaitingApprovalCount)) {
            return ValidationResult.REVIEW_REQUIRED;
        }
        if (count(severityCounts, RowIssueSeverity.ERROR) > 0
                || count(severityCounts, RowIssueSeverity.WARNING) > 0) {
            return ValidationResult.VALID_WITH_WARNINGS;
        }
        return ValidationResult.VALID;
    }

    /** Onbekend ({@code null}) is geen reden voor een review, maar wordt ook nooit als 0 bewaard. */
    private static boolean isPositive(Long count) {
        return count != null && count > 0;
    }

    private static long count(Map<RowIssueSeverity, Long> counts, RowIssueSeverity severity) {
        Long count = counts.get(severity);
        return count == null ? 0L : count;
    }
}
