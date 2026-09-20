package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.DeviationDirection;
import be.dda.catalogimport.domain.IssueIncidentKind;

/**
 * De <b>foutsignatuur</b>: wat twee vaststellingen "gelijksoortig" maakt, zodat ze tot één
 * {@code import_issue_group} samengevat kunnen worden (ontwerp fase 3, changeset 004-4 en R-THR-04).
 * Pure klasse: geen Spring, geen database, geen tijd — dezelfde invoer levert altijd exact dezelfde
 * signatuur op.
 *
 * <h2>Drie soorten, drie sleutels</h2>
 * <ul>
 *   <li>{@link IssueIncidentKind#GENERIC} — foutcode + logisch veld. "Honderd keer een onleesbare
 *       prijs in kolom PRIJS" is één verschijnsel; dezelfde foutcode op een ander veld is een
 *       ander verschijnsel en mag er niet mee samenvallen.</li>
 *   <li>{@link IssueIncidentKind#PRICE} — foutcode + prijscomponent + <b>richting</b> (R-PRI-14).
 *       Honderd stijgingen en honderd dalingen zijn niet hetzelfde: het eerste kan een
 *       prijsverhoging zijn, het tweede een verkeerd geplaatste decimaal. Ze samenvoegen zou de
 *       enige aanwijzing wegpoetsen.</li>
 *   <li>{@link IssueIncidentKind#IDENTITY} — foutcode + referentietype + soort incident
 *       (R-REF-07). Honderd gewijzigde EAN's is één patroon (vermoedelijk een bulktransformatie bij
 *       de leverancier); honderd hergebruikte EAN's is een heel ander probleem.</li>
 * </ul>
 * De foutcode zelf staat <b>niet</b> in de signatuurtekst: ze is al de tweede kolom van
 * {@code uk_import_issue_group_signature (batch_id, issue_code, signature)}. De tekst blijft zo
 * leesbaar voor een mens die de groepenlijst bekijkt.
 *
 * <h2>Wat hier bewust niet gebeurt</h2>
 * Patroonherkenning van bulktransformaties (ontwerp fase 3, aanname A20) — "alle EAN's kregen er
 * hetzelfde voorvoegsel bij" — hoort niet in fase 3. {@code dominant_factor} en
 * {@code pattern_description} van de groep blijven daarom leeg; ze worden nooit met een geraden
 * waarde ingevuld.
 */
public final class IssueSignature {

    /** {@code import_issue_group.signature} en {@code import_row_issue.signature} zijn varchar(300). */
    public static final int MAX_LENGTH = 300;

    /** Plaatsvervanger voor een ontbrekende veldnaam; nooit een lege signatuur. */
    public static final String NO_FIELD = "-";

    /**
     * Eén signatuur, met de onderdelen die de groep gedenormaliseerd bewaart.
     *
     * @param value              de signatuurtekst, hoogstens {@value #MAX_LENGTH} tekens
     * @param priceComponentCode enkel bij {@link IssueIncidentKind#PRICE}
     * @param direction          enkel bij {@link IssueIncidentKind#PRICE}
     * @param referenceType      enkel bij {@link IssueIncidentKind#IDENTITY}
     */
    public record Signature(IssueIncidentKind kind, String value, String priceComponentCode,
                            DeviationDirection direction, String referenceType) {

        public Signature {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("An issue signature is never empty; without it two "
                        + "unrelated findings would end up in the same group");
            }
            value = value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH);
        }
    }

    private IssueSignature() {
    }

    /**
     * De signatuur van een samenvattende melding <b>over</b> een foutcode, zoals
     * {@code ROW_ISSUE_RECORDING_CAPPED}. Ze hoort bij geen enkele groep, maar maakt de melding wél
     * precies één keer per batch en foutcode schrijfbaar: bestaat de rij al, dan komt er geen tweede
     * deelmelding bij na een hervatting.
     */
    public static Signature forIssueCode(String issueCode) {
        if (issueCode == null || issueCode.isBlank()) {
            throw new IllegalArgumentException("A per-code notice always names the code it summarises");
        }
        return new Signature(IssueIncidentKind.GENERIC, "CODE=" + issueCode, null, null, null);
    }

    /** Een gewone regelfout: dezelfde foutcode op hetzelfde logische veld. */
    public static Signature generic(String fieldName) {
        String field = fieldName == null || fieldName.isBlank() ? NO_FIELD : fieldName;
        return new Signature(IssueIncidentKind.GENERIC, "FIELD=" + field, null, null, null);
    }

    /** Een prijsafwijking: zelfde component, zelfde richting (R-PRI-14). */
    public static Signature price(String priceComponentCode, DeviationDirection direction) {
        if (priceComponentCode == null || direction == null) {
            throw new IllegalArgumentException("A price incident signature needs both the component and "
                    + "the direction; grouping price rises together with price drops would hide the "
                    + "difference between a price increase and a misplaced decimal");
        }
        return new Signature(IssueIncidentKind.PRICE,
                "COMPONENT=" + priceComponentCode + "|DIRECTION=" + direction.name(),
                priceComponentCode, direction, null);
    }

    /**
     * Een incident op een kritieke koppelreferentie: zelfde referentietype, zelfde soort incident
     * (R-REF-07).
     *
     * @param incidentKind {@code CHANGED}, {@code REMOVED}, {@code REUSED}, {@code AMBIGUOUS} of
     *                     {@code DUPLICATE}
     */
    public static Signature identity(String referenceType, String incidentKind) {
        if (referenceType == null || incidentKind == null || incidentKind.isBlank()) {
            throw new IllegalArgumentException("An identity incident signature needs both the reference "
                    + "type and the kind of incident; a changed EAN and a reused EAN are different "
                    + "problems and are never counted as one");
        }
        return new Signature(IssueIncidentKind.IDENTITY,
                "TYPE=" + referenceType + "|KIND=" + incidentKind, null, null, referenceType);
    }
}
