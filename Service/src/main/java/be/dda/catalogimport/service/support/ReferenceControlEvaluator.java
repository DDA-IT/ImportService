package be.dda.catalogimport.service.support;

import be.dda.catalogimport.dao.ReferenceControlDao.ReferenceCandidate;
import be.dda.catalogimport.domain.ReferenceMatchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Beoordeelt de kritieke koppelreferenties van één kandidaatregel (ontwerp fase 3, R-ID-03/R-ID-04 en
 * R-REF-02..R-REF-06). Pure klasse: geen Spring, geen database, geen tijd- of
 * omgevingsafhankelijkheid — dezelfde invoer levert altijd hetzelfde oordeel. De gegevens komen
 * set-based per chunk uit {@code ReferenceControlDao}; deze klasse doet uitsluitend het oordeel, zodat
 * de identiteitsregel exact één keer in de code staat.
 *
 * <h2>De vaste matchingvolgorde (businessanalyse par. 14.23.7)</h2>
 * Stap 1 (exacte aanbiedingsidentiteit) is al gebeurd: {@code ownStateId} is gevuld wanneer deze
 * identiteit al in de bronstaat van deze koppeling bestaat. Wat hier beoordeeld wordt, is stap 2 en 3
 * plus de kritieke-referentieregels:
 * <ol>
 *   <li><b>Stap 2 (R-ID-03)</b> — geen aanbiedingsmatch, maar precies één eenduidige kritieke match op
 *       een bestaande aanbieding binnen dezelfde bibliotheek: dat is een <i>ander aanbod voor hetzelfde
 *       artikel</i>. De nieuwe aanbieding wordt volgens het gewone creatiebeleid gemaakt, de bestaande
 *       aanbiedingsidentiteit wordt <b>nooit</b> vervangen en de referentie blijft waar ze staat. Er
 *       komt enkel een informatieve vaststelling ({@code REFERENCE_LINK_PROPOSED}).</li>
 *   <li><b>Stap 3 (R-ID-04)</b> — meerdere of tegenstrijdige kritieke matches: geen creatie, geen
 *       samenvoeging, geen update. Kritiek incident, kind {@link ReferenceMatchResult#AMBIGUOUS}.</li>
 *   <li><b>Stap 4</b> (ondersteunende matching op omschrijving, merk, prijs) wordt bewust <b>niet</b>
 *       uitgevoerd (ontwerp fase 3, aanname A20): ondersteunende gelijkenis koppelt nooit automatisch.</li>
 *   <li><b>Stap 6</b> (onvolledige identiteit) is al eerder afgehandeld: zo'n regel wordt bij het
 *       normaliseren verworpen met {@code IDENTITY_COMPONENT_EMPTY} en bereikt deze pass niet.</li>
 * </ol>
 *
 * <h2>Volgorde van zwaarte, en waarom REUSED en AMBIGUOUS uit elkaar gehouden worden</h2>
 * Per referentietype geldt de zwaarste uitkomst die van toepassing is; zie
 * {@link ReferenceMatchResult}. De grens tussen de twee zwaarste is de <b>aanwijsbaarheid</b>:
 * <ul>
 *   <li>{@link ReferenceMatchResult#AMBIGUOUS} — er is géén enkele bestaande aanbieding aan te wijzen.
 *       Dat gebeurt in twee gevallen: (a) de referenties van deze ene regel wijzen naar <b>twee of meer
 *       verschillende</b> bestaande aanbiedingen (bijvoorbeeld de EAN naar aanbieding A en de CAB-ID
 *       naar aanbieding B) — de tegenstrijdige kritieke match van R-ID-04; of (b) de aanbieding draagt
 *       zelf al twee actieve waarden voor hetzelfde referentietype, zodat zelfs "de" actieve waarde
 *       niet bestaat. In beide gevallen zou elke keuze een gok zijn.</li>
 *   <li>{@link ReferenceMatchResult#REUSED} — er is precies één aanbieding aan te wijzen, maar het is
 *       een <b>andere</b> dan deze: de geleverde waarde staat actief bij een ander artikel in dezelfde
 *       bibliotheek (R-REF-04). Een beslisser ziet exact welke twee aanbiedingen om de referentie
 *       vechten.</li>
 * </ul>
 * Bij twijfel wint de zwaarste uitkomst: er wordt nooit stil doorgelaten. Beide zijn kritiek en houden
 * het record vast; het verschil bepaalt uitsluitend wat de melding en het incident tonen.
 *
 * <h2>De ene uitzondering: een nieuwe aanbieding</h2>
 * Voor een kandidaat zonder aanbiedingsmatch ({@code ownStateId == null}) is precies één treffer géén
 * hergebruik maar de indirecte koppeling van par. 14.23.3: twee leveranciersaanbiedingen voor hetzelfde
 * artikel. Die aanbieding wordt dus gewoon aangemaakt. Pas vanaf twee verschillende treffers is het
 * R-ID-04.
 *
 * <h2>Wat hier bewust géén incident is</h2>
 * <ul>
 *   <li>Een referentietype dat <b>niet gemapt</b> is: daarover doet deze levering geen uitspraak; er is
 *       geen {@code ReferenceCandidate} en er wordt niets beoordeeld (R-REF-03).</li>
 *   <li>Een <b>eerste vastlegging</b>: de aanbieding had nog geen actieve waarde voor dit type en de
 *       geleverde waarde is nergens anders actief. Dat is precies het geval dat R-REF-06 toelaat —
 *       genormaliseerd, uniek binnen de bibliotheek en niet aan een ander artikel gekoppeld. Zonder
 *       deze uitzondering zou de eerste levering ná het inschakelen van referentiemappings élke
 *       bestaande aanbieding tot incident maken.</li>
 * </ul>
 */
public final class ReferenceControlEvaluator {

    private ReferenceControlEvaluator() {
    }

    /**
     * Het oordeel over één referentietype van één regel.
     *
     * @param matchedSourceStateId de bronstaatrij waarnaar deze referentie verwijst: de aanbieding die
     *                             de waarde vandaag draagt, of {@code null} wanneer niemand ze draagt
     * @param beforeValue          de actieve waarde vóór deze levering, of {@code null}
     * @param afterValue           de genormaliseerde waarde uit de levering; {@code null} betekent
     *                             "gemapt maar leeg"
     */
    public record ReferenceOutcome(String referenceType, ReferenceMatchResult result,
                                   Long matchedSourceStateId, String beforeValue, String afterValue) {

        public boolean isIncident() {
            return result != null && result.isIncident();
        }
    }

    /**
     * Het oordeel over één regel.
     *
     * @param linkProposed          matchingstap 2 (R-ID-03) is van toepassing: deze nieuwe aanbieding
     *                              hoort bij hetzelfde artikel als een bestaande aanbieding. Dit is een
     *                              informatieve vaststelling, geen incident
     * @param proposedSourceStateId de bestaande aanbieding waarnaar de koppeling verwijst
     */
    public record RecordOutcome(long rowNumber, List<ReferenceOutcome> references, boolean linkProposed,
                                Long proposedSourceStateId) {

        public RecordOutcome {
            references = List.copyOf(references);
        }

        /** Draagt deze regel minstens één kritiek incident en wordt ze dus vastgehouden (R-REF-09)? */
        public boolean hasIncident() {
            return references.stream().anyMatch(ReferenceOutcome::isIncident);
        }

        public List<ReferenceOutcome> incidents() {
            return references.stream().filter(ReferenceOutcome::isIncident).toList();
        }
    }

    /**
     * Beoordeelt alle referenties van één regel.
     *
     * @param candidates alle rijen van {@code ReferenceControlDao} voor exact één regelnummer; er
     *                   kunnen meerdere rijen per referentietype zijn wanneer de aanbieding onverhoopt
     *                   meer dan één actieve waarde draagt
     */
    public static RecordOutcome evaluate(long rowNumber, List<ReferenceCandidate> candidates) {
        if (candidates.isEmpty()) {
            return new RecordOutcome(rowNumber, List.of(), false, null);
        }
        Long ownStateId = candidates.get(0).ownStateId();
        Map<String, Grouped> byType = group(candidates);

        // De aanbiedingen (andere dan deze) waarnaar de geleverde waarden van deze ene regel wijzen.
        // Twee of meer verschillende: de koppeling is niet eenduidig vast te stellen (R-ID-04).
        Set<Long> foreignHolders = new LinkedHashSet<>();
        for (Grouped grouped : byType.values()) {
            if (grouped.holderStateId != null && !grouped.holderStateId.equals(ownStateId)) {
                foreignHolders.add(grouped.holderStateId);
            }
        }
        boolean contradictory = foreignHolders.size() >= 2;

        List<ReferenceOutcome> outcomes = new ArrayList<>(byType.size());
        for (Grouped grouped : byType.values()) {
            ReferenceOutcome outcome = decide(grouped, ownStateId, contradictory);
            if (outcome != null) {
                outcomes.add(outcome);
            }
        }
        boolean linkProposed = ownStateId == null && foreignHolders.size() == 1
                && outcomes.stream().noneMatch(ReferenceOutcome::isIncident);
        Long proposed = linkProposed ? foreignHolders.iterator().next() : null;
        return new RecordOutcome(rowNumber, outcomes, linkProposed, proposed);
    }

    /**
     * Het oordeel over één referentietype. Volgorde van zwaarte: tegenstrijdige match, meervoudige
     * eigen waarde, hergebruik, wijziging, verwijdering; pas daarna "in orde".
     */
    private static ReferenceOutcome decide(Grouped grouped, Long ownStateId, boolean contradictory) {
        String type = grouped.referenceType;
        String delivered = grouped.valueNormalised;
        String active = grouped.singleActiveValue();

        if (grouped.activeValues.size() > 1) {
            // De bestaande koppeling is zelf al dubbelzinnig: er bestaat geen "de" actieve waarde.
            return new ReferenceOutcome(type, ReferenceMatchResult.AMBIGUOUS, grouped.holderStateId,
                    String.join(", ", grouped.activeValues), delivered);
        }
        if (grouped.empty) {
            // R-REF-03: gemapt maar leeg. Zonder actieve waarde valt er niets te verwijderen en doet
            // deze levering geen uitspraak die iets wijzigt.
            return active == null ? null
                    : new ReferenceOutcome(type, ReferenceMatchResult.REMOVED, ownStateId, active, null);
        }
        if (contradictory && grouped.holderStateId != null
                && !grouped.holderStateId.equals(ownStateId)) {
            return new ReferenceOutcome(type, ReferenceMatchResult.AMBIGUOUS, grouped.holderStateId,
                    active, delivered);
        }
        if (grouped.holderStateId != null && grouped.holderStateId.equals(ownStateId)) {
            // De aanbieding draagt deze waarde zelf al actief: er verandert niets.
            return new ReferenceOutcome(type, ReferenceMatchResult.SAME, ownStateId, active, delivered);
        }
        if (ownStateId == null) {
            // Nieuwe aanbieding: precies één treffer is de indirecte koppeling van matchingstap 2
            // (R-ID-03) - de referentie blijft bij de bestaande aanbieding en er wordt niets nieuws
            // vastgelegd. Geen treffer is een eerste vastlegging (R-REF-06).
            return new ReferenceOutcome(type,
                    grouped.holderStateId == null ? ReferenceMatchResult.NEW : ReferenceMatchResult.SAME,
                    grouped.holderStateId, null, delivered);
        }
        if (grouped.holderStateId != null) {
            // R-REF-04: de waarde staat actief bij een ander artikel in dezelfde bibliotheek.
            return new ReferenceOutcome(type, ReferenceMatchResult.REUSED, grouped.holderStateId,
                    active, delivered);
        }
        if (active == null) {
            // R-REF-06: eerste vastlegging op een bestaande aanbieding - genormaliseerd, uniek binnen
            // de bibliotheek en niet aan een ander artikel gekoppeld.
            return new ReferenceOutcome(type, ReferenceMatchResult.NEW, null, null, delivered);
        }
        // R-REF-02: een andere waarde dan de actieve. Nooit een gewone update.
        return new ReferenceOutcome(type, ReferenceMatchResult.CHANGED, ownStateId, active, delivered);
    }

    /**
     * De melding in de stijl van par. 15.12: {@code <logische veldnaam>: '<bronwaarde>' <wat er mis is>},
     * met oude en nieuwe waarde.
     *
     * @param fieldName de logische veldnaam uit {@code import_field_catalog}
     */
    public static String message(ReferenceOutcome outcome, String fieldName) {
        String before = outcome.beforeValue() == null ? "(geen)" : outcome.beforeValue();
        String after = outcome.afterValue() == null ? "(leeg)" : outcome.afterValue();
        String what = switch (outcome.result()) {
            case CHANGED -> "differs from the active critical reference '" + before
                    + "'; a critical reference is never changed by an ordinary update";
            case REMOVED -> "is mapped but empty while '" + before
                    + "' is the active critical reference; removing it is never automatic";
            case REUSED -> "is already the active critical reference of another offer in this library"
                    + (outcome.matchedSourceStateId() == null ? ""
                    : " (source state " + outcome.matchedSourceStateId() + ")")
                    + "; the previous value of this offer was '" + before + "'";
            case AMBIGUOUS -> "points to more than one existing offer in this library; the active value "
                    + "was '" + before + "' and no single link can be determined";
            case NEW, SAME -> "is not an incident";
        };
        return fieldName + ": '" + after + "' " + what;
    }

    private static Map<String, Grouped> group(List<ReferenceCandidate> candidates) {
        Map<String, Grouped> byType = new LinkedHashMap<>();
        for (ReferenceCandidate candidate : candidates) {
            Grouped grouped = byType.computeIfAbsent(candidate.referenceType(),
                    type -> new Grouped(type, candidate.valueNormalised(), candidate.empty()));
            if (candidate.activeValue() != null) {
                grouped.activeValues.add(candidate.activeValue());
            }
            if (candidate.holderStateId() != null) {
                grouped.holderStateId = candidate.holderStateId();
            }
        }
        return byType;
    }

    /** Alles wat over één referentietype van één regel bekend is, samengevoegd over de bronrijen. */
    private static final class Grouped {
        private final String referenceType;
        private final String valueNormalised;
        private final boolean empty;
        /** Gesorteerd, zodat een dubbelzinnige melding deterministisch is. */
        private final Set<String> activeValues = new TreeSet<>();
        private Long holderStateId;

        private Grouped(String referenceType, String valueNormalised, boolean empty) {
            this.referenceType = referenceType;
            this.valueNormalised = valueNormalised;
            this.empty = empty;
        }

        private String singleActiveValue() {
            return activeValues.isEmpty() ? null : activeValues.iterator().next();
        }
    }
}
