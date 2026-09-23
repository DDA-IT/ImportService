package be.dda.catalogimport.domain;

/**
 * Op welk niveau een bookmark van een importsjabloon ingevuld wordt (businessanalyse §14.16-§14.20,
 * beslissingslog 23/09 keuze 1).
 * <p>
 * Dit is de enige lezing waarin §14.16 (het sjabloon levert per leverancier een eigen definitie op) en
 * §14.17/§14.19/§14.20 (de koppeling draagt de ingevulde waarden) elkaar niet tegenspreken: de scope
 * zegt per bookmark welk van de twee geldt. Er is bewust <b>geen</b> standaardwaarde — een verkeerd
 * geraden scope zet een leveranciersafhankelijke waarde vast in een gedeelde definitie.
 */
public enum BookmarkValueScope {

    /**
     * Wordt bij materialisatie in de afgeleide {@link ImportDefinitionRevision} vastgezet en geldt
     * voor élke koppeling die die definitie gebruikt. Een definitie mag gedeeld blijven door meerdere
     * koppelingen (beslissingslog 23/09 keuze 2), dus een waarde die per leverancier zou moeten
     * verschillen hoort hier nooit thuis — die moet {@link #LINK} zijn.
     */
    DEFINITION,

    /** Wordt per {@link ImportLink} ingevuld; twee koppelingen op dezelfde definitie mogen verschillen. */
    LINK
}
