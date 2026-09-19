package be.dda.catalogimport.domain;

/**
 * De uitkomst van de referentiecontrole voor één kritieke koppelreferentie van één kandidaatregel
 * (ontwerp fase 3, R-ID-03/R-ID-04 en R-REF-02..R-REF-06); wordt bewaard in
 * {@code import_candidate_reference.match_result}.
 *
 * <h2>Afbakening tussen de uitkomsten</h2>
 * De controle vergelijkt de <b>genormaliseerde</b> waarde van de levering met twee dingen: de actieve
 * waarde van <i>deze</i> aanbieding voor <i>dit</i> referentietype, en de aanbieding die diezelfde
 * waarde op dit moment actief draagt binnen <i>dezelfde bibliotheek</i>. De uitkomsten worden in
 * deze volgorde van zwaarte bepaald — de zwaarste die van toepassing is, wint:
 * <ol>
 *   <li>{@link #AMBIGUOUS} — er is niet één aanbieding aan te wijzen: de referenties van deze regel
 *       wijzen naar twee of meer verschillende bestaande aanbiedingen, of deze aanbieding heeft zelf
 *       al twee actieve waarden voor hetzelfde type. Welke koppeling bedoeld is, valt niet vast te
 *       stellen, dus wordt er niets gecreëerd, samengevoegd of bijgewerkt (R-ID-04, R-REF-05).</li>
 *   <li>{@link #REUSED} — er is wél precies één aanbieding aan te wijzen, maar dat is een
 *       <b>andere</b> dan deze: de geleverde waarde staat actief bij een ander artikel in dezelfde
 *       bibliotheek (R-REF-04). Het verschil met {@link #AMBIGUOUS} is dus niet de ernst maar de
 *       <i>aanwijsbaarheid</i>: bij REUSED weet een beslisser exact welke twee aanbiedingen om de
 *       referentie vechten, bij AMBIGUOUS niet.</li>
 *   <li>{@link #CHANGED} — deze aanbieding heeft een actieve waarde en levert een andere, die
 *       nergens anders actief is (R-REF-02).</li>
 *   <li>{@link #REMOVED} — het veld is gemapt maar leeg terwijl er een actieve waarde is (R-REF-03).
 *       Een niet-gemapt veld levert géén rij en dus géén uitspraak.</li>
 *   <li>{@link #SAME} of {@link #NEW} — geen incident.</li>
 * </ol>
 * <b>Eén uitzondering, bewust.</b> Voor een kandidaat die als {@link CandidateClassification#NEW}
 * geclassificeerd is, betekent precies één eenduidige treffer op een bestaande aanbieding géén
 * hergebruik maar een <i>ander aanbod voor hetzelfde artikel</i>: de nieuwe aanbieding wordt volgens
 * het gewone creatiebeleid gemaakt, de bestaande aanbiedingsidentiteit wordt nooit vervangen en er
 * wordt enkel een informatieve {@code REFERENCE_LINK_PROPOSED} vastgelegd (R-ID-03, matchingstap 2).
 * Die regel geldt uitsluitend voor NEW; een bestaande aanbieding die de referentie van een andere
 * aanbieding opeist, is wél {@link #REUSED}.
 */
public enum ReferenceMatchResult {

    /** Nog niet eerder vastgelegd en nergens anders actief: eerste vastlegging (R-REF-06). */
    NEW,

    /** Gelijk aan de actieve waarde van deze aanbieding; er verandert niets. */
    SAME,

    /** Andere waarde dan de actieve waarde van deze aanbieding (R-REF-02). */
    CHANGED,

    /** De waarde staat actief bij een andere aanbieding in dezelfde bibliotheek (R-REF-04). */
    REUSED,

    /** De koppeling is niet eenduidig vast te stellen (R-ID-04, R-REF-05). */
    AMBIGUOUS,

    /** Gemapt maar leeg terwijl er een actieve waarde is (R-REF-03). */
    REMOVED;

    /**
     * Levert deze uitkomst een kritiek identiteitsincident op? {@link #NEW} en {@link #SAME} niet;
     * alle andere wel — en dan wordt het record vastgehouden (R-REF-09).
     */
    public boolean isIncident() {
        return this != NEW && this != SAME;
    }
}
