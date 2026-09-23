package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.RevisionCriticalityField;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Integriteit van de bookmarkdeclaratie van één sjabloonrevisie (sjabloon-materialisatie-design.md §4
 * fase C, checks C1-C4).
 * <p>
 * <b>Waarom dit vandaag al nodig is, zonder een declaratie-endpoint.</b> Het "Important technical
 * constraint discovered"-blok in §13 van het ontwerp legt uit dat de regel "een DEFINITION-scope
 * bookmark mag nooit een per-leverancier verschillende waarde dragen" bij het *declareren* afgedwongen
 * hoort te worden (beslissingslog 23/09 keuze 2), maar dat er nog geen declaratiepad bestaat en de
 * database de regel niet kan uitdrukken. Deze klasse is daarom bewust **twee keer** aan te roepen zodra
 * de aanroepers er zijn (bouwstap 5b: bij declareren; 5c: defensief bij materialiseren) — één
 * implementatie, twee aanroepplaatsen. In bouwstap 5a bestaat nog geen aanroeper.
 * <p>
 * <b>Niet-werpend.</b> {@link #findProblems} geeft alle gevonden problemen terug in plaats van bij het
 * eerste probleem te stoppen: het ontwerp eist dat {@code GET .../bookmarks} de declaratie "leesbaar
 * zonder te werpen" toont (§5), terwijl een defensieve aanroeper bij materialisatie zelf beslist om op
 * het eerste probleem een {@code CONFIG_*}-fout te werpen.
 * <p>
 * Package-private: enkel bedoeld voor Service-klassen in dit pakket, geen publiek contract.
 */
final class BookmarkDeclarations {

    static final String CODE_SCOPE_PLACE_CONFLICT = "CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT";
    static final String CODE_WITHOUT_PLACE = "CONFIG_BOOKMARK_WITHOUT_PLACE";
    static final String CODE_PLACE_UNRESOLVED = "CONFIG_BOOKMARK_PLACE_UNRESOLVED";
    static final String CODE_PLACE_NOT_SUPPORTED = "CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED";

    /**
     * De plaatsen die deze bouwstap ondersteunt (C4). {@link BookmarkUsagePlace#REVISION_PRICE_POLICY}
     * ontbreekt bewust: het ontwerp §9 punt 4 weigert die plaats expliciet
     * ({@code CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED}) omdat er geen beleidsprofiel-entiteit bestaat.
     * {@code DELIVERY_FILE_SELECTION} komt in {@link BookmarkUsagePlace} niet eens voor (§9 punt 3).
     */
    private static final Set<BookmarkUsagePlace> SUPPORTED_PLACES = EnumSet.complementOf(
            EnumSet.of(BookmarkUsagePlace.REVISION_PRICE_POLICY));

    private static final Set<BookmarkUsagePlace> LINK_PLACES = EnumSet.of(
            BookmarkUsagePlace.LINK_LIBRARY_CODE, BookmarkUsagePlace.LINK_SEARCH_SUPPLIER,
            BookmarkUsagePlace.LINK_SUPPLIER_ORGANISATION);

    private BookmarkDeclarations() {
        // Enkel statische helpers.
    }

    /** Eén bevinding: de foutcode, de bookmark waarop ze slaat, en een leesbare boodschap. */
    record Problem(String code, String bookmarkName, String message) {
    }

    /**
     * Toetst de declaratie van één sjabloonrevisie tegen checks C1-C4. De aanroeper levert de
     * bookmarks en hun usage-rijen van díe revisie aan (al gelezen; deze klasse doet geen eigen
     * queries), samen met wat er in diezelfde revisie al bestaat om een plaats tegen te resolven (C3):
     * de gemapte doelveldcodes en de gebruikte filtervolgnummers.
     *
     * @param bookmarks              de bookmarkdeclaraties van de revisie
     * @param usages                 de usage-rijen van diezelfde bookmarks (van eender welke bookmark
     *                               in {@code bookmarks}, in eender welke volgorde); {@code usage.getBookmark()}
     *                               moet dezelfde objectreferentie zijn als de bijhorende rij in
     *                               {@code bookmarks} — de groepering gebeurt op identiteit, niet op
     *                               {@code id}, zodat deze klasse ook zonder een persistentiecontext
     *                               (nog geen gegenereerde id) getest kan worden
     * @param mappedTargetFieldCodes de doelveldcodes die in deze revisie al een
     *                               {@code import_field_mapping}-rij hebben
     * @param filterSequenceNumbers  de volgnummers die in deze revisie al een
     *                               {@code import_record_filter}-rij hebben
     * @return alle gevonden problemen, leeg wanneer de declaratie in orde is
     */
    static List<Problem> findProblems(List<ImportDefinitionBookmark> bookmarks,
                                      List<ImportDefinitionBookmarkUsage> usages,
                                      Set<String> mappedTargetFieldCodes,
                                      Set<Integer> filterSequenceNumbers) {
        List<Problem> problems = new ArrayList<>();
        Map<ImportDefinitionBookmark, List<ImportDefinitionBookmarkUsage>> usagesByBookmark =
                new IdentityHashMap<>();
        for (ImportDefinitionBookmarkUsage usage : usages) {
            usagesByBookmark.computeIfAbsent(usage.getBookmark(), key -> new ArrayList<>()).add(usage);
        }
        for (ImportDefinitionBookmark bookmark : bookmarks) {
            List<ImportDefinitionBookmarkUsage> own = usagesByBookmark.getOrDefault(bookmark, List.of());
            if (own.isEmpty()) {
                // C2 - een bookmark zonder plaats is een invulveld dat niets doet.
                problems.add(new Problem(CODE_WITHOUT_PLACE, bookmark.getName(),
                        "Bookmark '" + bookmark.getName() + "' has no usage; it would do nothing"));
                continue;
            }
            for (ImportDefinitionBookmarkUsage usage : own) {
                checkOneUsage(bookmark, usage, mappedTargetFieldCodes, filterSequenceNumbers, problems);
            }
        }
        return problems;
    }

    private static void checkOneUsage(ImportDefinitionBookmark bookmark, ImportDefinitionBookmarkUsage usage,
                                      Set<String> mappedTargetFieldCodes, Set<Integer> filterSequenceNumbers,
                                      List<Problem> problems) {
        BookmarkUsagePlace place = usage.getPlaceKind();
        // C4 - een niet-ondersteunde plaats wordt niet verder beoordeeld: haar doel bestaat per
        // definitie niet.
        if (!SUPPORTED_PLACES.contains(place)) {
            problems.add(new Problem(CODE_PLACE_NOT_SUPPORTED, bookmark.getName(),
                    "Place '" + place + "' is not supported yet for bookmark '" + bookmark.getName() + "'"));
            return;
        }
        // C1 - een koppelingskolom is per constructie per koppeling; DEFINITION daarop declareren zet
        // een leveranciersafhankelijke waarde vast in een gedeelde definitie.
        if (bookmark.getValueScope() == BookmarkValueScope.DEFINITION && LINK_PLACES.contains(place)) {
            problems.add(new Problem(CODE_SCOPE_PLACE_CONFLICT, bookmark.getName(),
                    "Bookmark '" + bookmark.getName() + "' is DEFINITION-scope but usage place '" + place
                            + "' is a per-link column"));
        }
        // C3 - de usage-rij moet naar een bestaand doel in déze revisie wijzen.
        if (!resolvesToExistingTarget(place, usage.getTargetHint(), mappedTargetFieldCodes,
                filterSequenceNumbers)) {
            problems.add(new Problem(CODE_PLACE_UNRESOLVED, bookmark.getName(),
                    "Bookmark '" + bookmark.getName() + "' usage on '" + place + "' targets '"
                            + usage.getTargetHint() + "', which does not exist in this revision"));
        }
    }

    private static boolean resolvesToExistingTarget(BookmarkUsagePlace place, String targetHint,
                                                     Set<String> mappedTargetFieldCodes,
                                                     Set<Integer> filterSequenceNumbers) {
        return switch (place) {
            case FIELD_MAPPING_FIXED_VALUE -> mappedTargetFieldCodes.contains(targetHint);
            case RECORD_FILTER_COMPARE_VALUE ->
                    parseSequenceNumber(targetHint).map(filterSequenceNumbers::contains).orElse(false);
            case REVISION_IDENTITY_FIELD -> RevisionCriticalityField.byKey(targetHint).isPresent();
            // De drie LINK_*-plaatsen wijzen naar een kolom op ImportLink zelf, niet naar een rij in
            // deze revisie: er is geen "bestaand doel" om tegen te resolven.
            case LINK_LIBRARY_CODE, LINK_SEARCH_SUPPLIER, LINK_SUPPLIER_ORGANISATION -> true;
            case REVISION_PRICE_POLICY -> false; // onbereikbaar: al door C4 tegengehouden.
        };
    }

    private static Optional<Integer> parseSequenceNumber(String targetHint) {
        try {
            return Optional.of(Integer.parseInt(targetHint));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }
}
