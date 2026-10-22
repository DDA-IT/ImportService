package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleDescriptionRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleLookup;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticlePriceDetailRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticlePriceRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleRow;
import be.dda.catalogimport.service.ProdisPublicationProjection.Action;
import be.dda.catalogimport.service.ProdisPublicationProjection.FieldState;
import be.dda.catalogimport.service.ProdisPublicationProjection.Item;
import be.dda.catalogimport.service.ProdisPublicationProjection.Notice;
import be.dda.catalogimport.service.ProdisPublicationProjection.Severity;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterMode;
import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula;
import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula.ExistingDetail;
import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula.Outcome;
import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula.Result;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * De pure planner van de publicatie naar de Prodis-database (design {@code publicatie-prodis-db-direct-design.md}
 * par. 2.4 planner-stap, 2.5, 3 en 5, slice P2b; beslissingen 2026-10-22 P-V1..P-V5, Q1 = b, Q2 en P2a punt 8). Geen
 * Spring, geen database, geen tijd: dezelfde invoer levert altijd hetzelfde plan. De parallelrun (P2c) gebruikt hem als
 * dry-run op de leesbron; de writer (P5b) binnen de Prodis-transactie op de vergrendelde rijen.
 *
 * <h2>Invoer</h2>
 * Een {@link Item} uit {@link ProdisPublicationProjection} en de Prodis-toestand van de identiteit uit de leesbron
 * ({@link CatalogArticleLookup}, P1). Een onbereikbare leesbron is nooit "niet gevonden": de aanroeper roept de planner
 * dan niet aan.
 *
 * <h2>Uitkomst (in deze volgorde beslist)</h2>
 * <ol>
 *   <li>CREATE → {@link PlanOutcome#NOT_SENT} ({@code CREATE_NOT_SUPPORTED_IN_V1}, P-V3); bestaat het artikel al, dan
 *       extra {@code ARTICLE_ALREADY_EXISTS} (wordt CONFLICT zodra CREATE gebouwd is, P-V4).</li>
 *   <li>Een door de projectie geblokkeerd item → {@link PlanOutcome#REJECTED} met de eerste blokkerende code.</li>
 *   <li>Identiteit: meer dan één leverancier met dit nummer → {@code SUPPLIER_AMBIGUOUS}; geen leverancier of geen
 *       artikel → {@code ARTICLE_NOT_FOUND}; meer dan één artikel → {@code ARTICLE_AMBIGUOUS} (alle REJECTED).</li>
 *   <li>Vorm (UPDATE-only): het artikel actief, precies één actieve prijsrij, detailcodes "1".."5" elk precies één keer
 *       en actief, precies één actieve NL-omschrijving, en een basisprijs als we die behouden; anders REJECTED
 *       {@code PRODIS_ROW_SHAPE_UNSUPPORTED}.</li>
 *   <li>Prijsberekening met {@link LegacyCatalogPriceFormula} (Q1 = b): prijscode buiten 0/1/2 → REJECTED
 *       {@code PRICE_CODE_UNSUPPORTED}; de meldingen van de formule worden waarschuwingen.</li>
 *   <li>Reconcile-by-state (par. 2.4/2.5): per bewaakt veld — de basisprijs en, bij prijscode 0, de percentages met
 *       {@code VALUE} — moet de Prodis-waarde gelijk zijn aan expected of aan target, anders {@link PlanOutcome#CONFLICT}
 *       ({@code VALUE_CHANGED_IN_PRODIS} per veld). {@code KEEP}-velden en afgeleide prijzen worden niet bewaakt.
 *       De omschrijving geeft nooit CONFLICT, wel {@code DESCRIPTION_OVERWRITTEN} als de Prodis-tekst anders is.</li>
 *   <li>Niets te wijzigen → {@link PlanOutcome#UNCHANGED}; anders {@link PlanOutcome#WOULD_UPDATE} met de te
 *       wijzigen kolommen. Een herhaling op de target-toestand (retry na {@code RESULT_UNKNOWN}) is dus UNCHANGED.</li>
 * </ol>
 *
 * <h2>Vergelijkingen (par. 2.5)</h2>
 * Basisprijs ({@code numeric(21,6)}, gelezen als {@link BigDecimal}): {@code compareTo}. Percentage tegen expected:
 * {@code BigDecimal.valueOf(prodisDouble).compareTo(expected) == 0}. Alles wat als {@code double} geschreven wordt
 * (percentages, VKP-prijzen, bruto): exacte {@code double}-gelijkheid met de target, want de writer schrijft precies die
 * {@code double}.
 *
 * <h2>Financieel</h2>
 * Er wordt niets stil gecorrigeerd: {@code PRICE_ROUNDING_DIFFERENCE} als het bronbedrag van een toegepast percentage
 * afwijkt van de berekende prijs (de berekende waarde wordt geschreven). {@code sales_price_last_updated} schuift
 * enkel als een VKP1..5-prijs echt wijzigt (P2a punt 8), niet bij enkel een bruto-wijziging.
 * <p>
 * <b>Afgeleide prijzen enkel na een eigen wijziging</b> ("alleen eigen velden", par. 2 principe 8; aanbeveling
 * hoofdsessie 2026-10-22, door de mens te herzien): VKP1..5-prijzen, {@code brut_price},
 * {@code brut_price_percentage} en {@code sales_price_last_updated} worden enkel geschreven als de basisprijs of een
 * percentage van het item zelf echt wijzigt. Wijzigt geen van beide, dan blijven de afgeleide kolommen ongemoeid (de
 * target draagt de Prodis-waarden; uitkomst UNCHANGED, of WOULD_UPDATE enkel voor de omschrijving). Zou Prg_1185 ze
 * toch anders berekenen, dan volgt een WARNING {@code DERIVED_PRICE_DRIFT} en staan prior en herberekende waarde ter
 * info in {@link Plan#derivedDrift()}; er wordt niets van geschreven. Een formulemelding {@code PERCENTAGE_MISSING}
 * vervalt dan (er wordt niets herberekend).
 */
public final class ProdisPublicationPlanner {

    public static final String CODE_CREATE_NOT_SUPPORTED_IN_V1 = "CREATE_NOT_SUPPORTED_IN_V1";
    public static final String CODE_ARTICLE_ALREADY_EXISTS = "ARTICLE_ALREADY_EXISTS";
    public static final String CODE_SUPPLIER_AMBIGUOUS = "SUPPLIER_AMBIGUOUS";
    public static final String CODE_ARTICLE_NOT_FOUND = "ARTICLE_NOT_FOUND";
    public static final String CODE_ARTICLE_AMBIGUOUS = "ARTICLE_AMBIGUOUS";
    public static final String CODE_PRODIS_ROW_SHAPE_UNSUPPORTED = "PRODIS_ROW_SHAPE_UNSUPPORTED";
    public static final String CODE_VALUE_CHANGED_IN_PRODIS = "VALUE_CHANGED_IN_PRODIS";
    public static final String CODE_DESCRIPTION_OVERWRITTEN = "DESCRIPTION_OVERWRITTEN";
    public static final String CODE_PRICE_ROUNDING_DIFFERENCE = "PRICE_ROUNDING_DIFFERENCE";
    public static final String CODE_DERIVED_PRICE_DRIFT = "DERIVED_PRICE_DRIFT";

    public static final String FIELD_ARTICLE = "ARTICLE";
    public static final String FIELD_SALES_PRICE_PREFIX = "VKP";

    private static final int FIRST_CODE = LegacyCatalogPriceFormula.FIRST_CODE;
    private static final int LAST_CODE = LegacyCatalogPriceFormula.LAST_CODE;

    private ProdisPublicationPlanner() {
    }

    public enum PlanOutcome {
        WOULD_UPDATE,
        UNCHANGED,
        CONFLICT,
        REJECTED,
        NOT_SENT
    }

    /** Een schrijfbare Prodis-kolom. */
    public enum Column {
        BASE_PRICE("catalog_article_price", "base_price"),
        BRUT_PRICE("catalog_article_price", "brut_price"),
        BRUT_PRICE_PERCENTAGE("catalog_article_price", "brut_price_percentage"),
        DETAIL_PERCENTAGE("catalog_article_price_detail", "percentage"),
        DETAIL_PRICE("catalog_article_price_detail", "price"),
        DESCRIPTION("catalog_article_description", "description");

        private final String table;
        private final String column;

        Column(String table, String column) {
            this.table = table;
            this.column = column;
        }

        public String table() {
            return table;
        }

        public String column() {
            return column;
        }
    }

    /**
     * De Prodis-toestand van één catalogusartikel, zonder Prodis-id's.
     *
     * @param salesPriceLastUpdated in de target altijd {@code null}: de writer zet {@code now()} als
     *                              {@link Plan#salesPriceLastUpdated()} {@code true} is
     */
    public record ArticleState(String priceCode, String brutPricePolitics, BigDecimal basePrice, Double brutPrice,
                               Double brutPricePercentage, Map<Integer, Double> percentages, Map<Integer, Double> prices,
                               String description, LocalDateTime salesPriceLastUpdated) {
        public ArticleState {
            percentages = Collections.unmodifiableMap(new TreeMap<>(percentages));
            prices = Collections.unmodifiableMap(new TreeMap<>(prices));
        }
    }

    /**
     * Eén te wijzigen kolom; {@code prior}/{@code target} als leesbare tekst (bedragen {@code toPlainString}).
     *
     * @param detailCode de VKP-code 1..5 voor {@link Column#DETAIL_PERCENTAGE}/{@link Column#DETAIL_PRICE}, anders {@code null}
     */
    public record Change(Column column, Integer detailCode, String prior, String target) {
    }

    /**
     * Het plan voor één item.
     *
     * @param reasonCode            de eerste blokkerende code bij CONFLICT/REJECTED/NOT_SENT, anders {@code null}
     * @param prior                 de Prodis-toestand, {@code null} als de identiteit niet eenduidig of de vorm niet
     *                              ondersteund is (of bij NOT_SENT/een door de projectie geblokkeerd item)
     * @param target                de toestand na toepassing; ook bij CONFLICT (ter informatie), {@code null} als er niet
     *                              gerekend kon worden
     * @param changes               enkel bij WOULD_UPDATE niet leeg: de kolommen die de writer schrijft, in vaste volgorde
     * @param salesPriceLastUpdated {@code true} enkel bij WOULD_UPDATE met een echte wijziging van een VKP1..5-prijs
     * @param derivedDrift          ter info, nooit geschreven: afgeleide kolommen die Prg_1185 anders zou berekenen terwijl
     *                              het item zelf geen basisprijs of percentage wijzigt ({@code DERIVED_PRICE_DRIFT});
     *                              {@code prior} = Prodis, {@code target} = herberekende waarde
     * @param legacyMaster          de modus van Prodis, doorgegeven voor weergave en {@code publication_run_item}
     * @param notices               eerst die van de projectie, dan die van de planner
     */
    public record Plan(long mutationId, String requestHash, PlanOutcome outcome, String reasonCode,
                       LegacyMasterMode legacyMaster, ArticleState prior, ArticleState target, List<Change> changes,
                       boolean salesPriceLastUpdated, List<Change> derivedDrift, List<Notice> notices) {
        public Plan {
            changes = List.copyOf(changes);
            derivedDrift = List.copyOf(derivedDrift);
            notices = List.copyOf(notices);
        }

        /** {@code true} als de writer de prijsrij moet bijwerken. */
        public boolean updatesPriceRow() {
            return salesPriceLastUpdated || changes.stream().anyMatch(change -> change.column() == Column.BASE_PRICE
                    || change.column() == Column.BRUT_PRICE || change.column() == Column.BRUT_PRICE_PERCENTAGE);
        }

        /** De VKP-codes waarvan de detailrij bijgewerkt moet worden, oplopend. */
        public Set<Integer> detailCodesToUpdate() {
            Set<Integer> codes = new TreeSet<>();
            changes.stream().filter(change -> change.detailCode() != null).forEach(change -> codes.add(change.detailCode()));
            return Collections.unmodifiableSet(codes);
        }

        /** {@code true} als de NL-omschrijving bijgewerkt moet worden. */
        public boolean updatesDescription() {
            return changes.stream().anyMatch(change -> change.column() == Column.DESCRIPTION);
        }
    }

    /**
     * Plant één item.
     *
     * @param lookup       de Prodis-toestand van {@code item.identity()}; verplicht voor een niet-geblokkeerde UPDATE, mag
     *                     {@code null} zijn voor CREATE of een door de projectie geblokkeerd item
     * @param legacyMaster de modus van Prodis ({@code null} = {@link LegacyMasterMode#UNKNOWN}); er wordt niets op
     *                     geweigerd (P-V1)
     * @throws IllegalArgumentException als de lookup ontbreekt waar ze nodig is, of bij een andere identiteit hoort
     */
    public static Plan plan(Item item, CatalogArticleLookup lookup, LegacyMasterMode legacyMaster) {
        Objects.requireNonNull(item, "item");
        LegacyMasterMode mode = legacyMaster == null ? LegacyMasterMode.UNKNOWN : legacyMaster;
        List<Notice> notices = new ArrayList<>(item.notices());

        if (item.action() == Action.CREATE) {
            if (lookup != null && item.identity().complete()) {
                requireSameIdentity(item, lookup);
                if (lookup.supplierCount() == 1 && lookup.articles().size() == 1) {
                    notices.add(new Notice(CODE_ARTICLE_ALREADY_EXISTS, Severity.WARNING, FIELD_ARTICLE,
                            "a catalog article with this identity already exists in Prodis; once CREATE is supported "
                                    + "this item becomes a CONFLICT"));
                }
            }
            notices.add(new Notice(CODE_CREATE_NOT_SUPPORTED_IN_V1, Severity.BLOCKING, FIELD_ARTICLE,
                    "creating catalog articles in Prodis is not supported yet (v1 only updates)"));
            return blocked(item, PlanOutcome.NOT_SENT, CODE_CREATE_NOT_SUPPORTED_IN_V1, mode, null, notices);
        }
        if (item.rejected()) {
            return blocked(item, PlanOutcome.REJECTED, item.rejection().code(), mode, null, notices);
        }
        if (lookup == null) {
            throw new IllegalArgumentException("a Prodis lookup is required to plan an UPDATE (mutation "
                    + item.mutationId() + "); an unreachable read source must never be planned as 'not found'");
        }
        requireSameIdentity(item, lookup);

        // 1. Identiteit
        if (lookup.supplierCount() > 1) {
            return rejected(item, CODE_SUPPLIER_AMBIGUOUS, "supplier number " + item.identity().supplierNumber()
                    + " exists " + lookup.supplierCount() + " times in Prodis", mode, null, notices);
        }
        if (lookup.supplierCount() == 0) {
            return rejected(item, CODE_ARTICLE_NOT_FOUND, "supplier number " + item.identity().supplierNumber()
                    + " does not exist in Prodis", mode, null, notices);
        }
        if (lookup.articles().isEmpty()) {
            return rejected(item, CODE_ARTICLE_NOT_FOUND, "no catalog article with this identity (group "
                    + item.identity().groupLegacyCode() + ", library " + item.identity().scopeKey()
                    + " ACTIVE, reference " + item.identity().supplierReference() + ") exists in Prodis", mode, null,
                    notices);
        }
        if (lookup.articles().size() > 1) {
            return rejected(item, CODE_ARTICLE_AMBIGUOUS, lookup.articles().size()
                    + " catalog articles in Prodis have this identity", mode, null, notices);
        }

        // 2. Vorm
        CatalogArticleRow article = lookup.articles().get(0);
        String shapeProblem = shapeProblem(article);
        if (shapeProblem != null) {
            return rejected(item, CODE_PRODIS_ROW_SHAPE_UNSUPPORTED, shapeProblem, mode, null, notices);
        }
        ArticleState prior = prior(article);
        BigDecimal targetBase = item.basePrice().state() == FieldState.VALUE ? item.basePrice().value() : prior.basePrice();
        if (targetBase == null) {
            return rejected(item, CODE_PRODIS_ROW_SHAPE_UNSUPPORTED, "base_price is empty in Prodis and the item keeps "
                    + "it: there is no base to calculate the sales prices from", mode, prior, notices);
        }

        // 3. Prijsberekening (Prg_1185)
        Map<Integer, BigDecimal> importPercentages = new TreeMap<>();
        item.salesPercentages().forEach((code, field) -> {
            if (field.state() == FieldState.VALUE) {
                importPercentages.put(code, field.value());
            }
        });
        Map<Integer, ExistingDetail> existingDetails = new TreeMap<>();
        for (int code = FIRST_CODE; code <= LAST_CODE; code++) {
            existingDetails.put(code, new ExistingDetail(prior.percentages().get(code), prior.prices().get(code)));
        }
        Result result = LegacyCatalogPriceFormula.calculate(new LegacyCatalogPriceFormula.Input(prior.priceCode(),
                prior.brutPricePolitics(), targetBase, importPercentages, existingDetails, prior.brutPrice(),
                prior.brutPricePercentage()));
        if (result.outcome() == Outcome.REJECTED) {
            return rejected(item, result.rejectionCode(), result.notices().get(0).message(), mode, prior, notices);
        }
        for (LegacyCatalogPriceFormula.Notice notice : result.notices()) {
            notices.add(new Notice(notice.code(), Severity.WARNING, notice.detailCode() == null ? null
                    : ProdisPublicationProjection.percentageField(notice.detailCode()), notice.message()));
        }
        boolean percentagesApplied = LegacyCatalogPriceFormula.PRICE_CODE_PERCENTAGE.equals(prior.priceCode().trim());

        String targetDescription = item.description() != null && item.description().state() == FieldState.VALUE
                ? item.description().value() : prior.description();
        ArticleState target = new ArticleState(prior.priceCode(), prior.brutPricePolitics(), targetBase,
                result.brutPrice(), result.brutPricePercentage(), result.percentages(), result.prices(), targetDescription,
                null);

        // 4. Reconcile-by-state
        List<Notice> conflicts = new ArrayList<>();
        if (item.basePrice().state() == FieldState.VALUE) {
            BigDecimal expected = item.expected().basePrice();
            BigDecimal current = prior.basePrice();
            boolean allowed = current != null && ((expected != null && current.compareTo(expected) == 0)
                    || current.compareTo(targetBase) == 0);
            if (!allowed) {
                conflicts.add(new Notice(CODE_VALUE_CHANGED_IN_PRODIS, Severity.BLOCKING,
                        ProdisPublicationProjection.FIELD_BASE_PRICE, "the Prodis base price (" + plain(current)
                                + ") is neither the expected " + plain(expected) + " nor the target "
                                + plain(targetBase)));
            }
        }
        if (percentagesApplied) {
            for (Map.Entry<Integer, BigDecimal> entry : importPercentages.entrySet()) {
                int code = entry.getKey();
                BigDecimal expected = item.expected().percentages().get(code);
                Double current = prior.percentages().get(code);
                Double targetPercentage = target.percentages().get(code);
                boolean allowed = current != null && ((expected != null
                        && BigDecimal.valueOf(current).compareTo(expected) == 0) || current.equals(targetPercentage));
                if (!allowed) {
                    conflicts.add(new Notice(CODE_VALUE_CHANGED_IN_PRODIS, Severity.BLOCKING,
                            ProdisPublicationProjection.percentageField(code), "the Prodis sales percentage " + code
                                    + " (" + plain(current) + ") is neither the expected " + plain(expected)
                                    + " nor the target " + plain(targetPercentage)));
                }
            }
        }
        if (item.description() != null && item.description().state() == FieldState.VALUE
                && !Objects.equals(prior.description(), item.description().value())) {
            notices.add(new Notice(CODE_DESCRIPTION_OVERWRITTEN, Severity.WARNING,
                    ProdisPublicationProjection.FIELD_DESCRIPTION, "the Prodis description is overwritten"));
        }
        if (percentagesApplied) {
            for (Integer code : importPercentages.keySet()) {
                BigDecimal sourceAmount = item.sourceAmounts().get(code);
                Double calculated = target.prices().get(code);
                if (sourceAmount != null && calculated != null && BigDecimal.valueOf(calculated).compareTo(sourceAmount) != 0) {
                    notices.add(new Notice(CODE_PRICE_ROUNDING_DIFFERENCE, Severity.WARNING,
                            salesPriceField(code), "sales price " + code + " is calculated as "
                                    + plain(calculated) + " (Prg_1185) while the source delivered "
                                    + sourceAmount.stripTrailingZeros().toPlainString() + "; the calculated value is written"));
                }
            }
        }
        if (!conflicts.isEmpty()) {
            notices.addAll(conflicts);
            return new Plan(item.mutationId(), item.requestHash(), PlanOutcome.CONFLICT, CODE_VALUE_CHANGED_IN_PRODIS, mode,
                    prior, target, List.of(), false, List.of(), notices);
        }

        // 5. Wijzigingen: afgeleide prijzen enkel als basisprijs of een percentage van het item zelf echt wijzigt.
        List<Change> changes = changes(prior, target);
        boolean inputsChanged = changes.stream().anyMatch(change -> change.column() == Column.BASE_PRICE
                || change.column() == Column.DETAIL_PERCENTAGE);
        List<Change> derivedDrift = List.of();
        if (!inputsChanged) {
            derivedDrift = changes.stream().filter(change -> isDerived(change.column())).toList();
            changes = changes.stream().filter(change -> !isDerived(change.column())).toList();
            target = new ArticleState(target.priceCode(), target.brutPricePolitics(), target.basePrice(), prior.brutPrice(),
                    prior.brutPricePercentage(), target.percentages(), prior.prices(), target.description(), null);
            notices.removeIf(notice -> LegacyCatalogPriceFormula.CODE_PERCENTAGE_MISSING.equals(notice.code()));
            if (!derivedDrift.isEmpty()) {
                notices.add(new Notice(CODE_DERIVED_PRICE_DRIFT, Severity.WARNING, null, "the derived prices in Prodis "
                        + "do not match its own base price and percentages (Prg_1185 would give " + describe(derivedDrift)
                        + "); they are not written because this item changes neither the base price nor a percentage"));
            }
        }
        boolean salesPriceChanged = changes.stream().anyMatch(change -> change.column() == Column.DETAIL_PRICE);
        PlanOutcome outcome = changes.isEmpty() ? PlanOutcome.UNCHANGED : PlanOutcome.WOULD_UPDATE;
        return new Plan(item.mutationId(), item.requestHash(), outcome, null, mode, prior, target, changes,
                salesPriceChanged, derivedDrift, notices);
    }

    private static boolean isDerived(Column column) {
        return column == Column.DETAIL_PRICE || column == Column.BRUT_PRICE || column == Column.BRUT_PRICE_PERCENTAGE;
    }

    private static String describe(List<Change> drift) {
        List<String> parts = new ArrayList<>();
        for (Change change : drift) {
            parts.add(change.column().column() + (change.detailCode() == null ? "" : " " + change.detailCode()) + " "
                    + change.prior() + " -> " + change.target());
        }
        return String.join(", ", parts);
    }

    // --- vorm en toestand -------------------------------------------------------------------------------------------

    /** {@code null} als de vorm ondersteund is, anders de reden. */
    static String shapeProblem(CatalogArticleRow article) {
        if (!article.active() || article.deleted()) {
            return "the catalog article is inactive or deleted";
        }
        if (article.prices().size() != 1) {
            return "the catalog article has " + article.prices().size() + " price rows (exactly one is supported)";
        }
        CatalogArticlePriceRow price = article.prices().get(0);
        if (!price.active() || price.deleted()) {
            return "the price row is inactive or deleted";
        }
        Set<String> seen = new TreeSet<>();
        for (CatalogArticlePriceDetailRow detail : price.details()) {
            if (!isSupportedCode(detail.code())) {
                return "the price row has a detail with code '" + detail.code() + "' (only 1..5 are supported)";
            }
            if (!seen.add(detail.code())) {
                return "the price row has detail code " + detail.code() + " more than once";
            }
            if (!detail.active() || detail.deleted()) {
                return "price detail " + detail.code() + " is inactive or deleted";
            }
        }
        if (seen.size() != LAST_CODE - FIRST_CODE + 1) {
            return "the price row has details " + seen + " (exactly 1..5 are supported)";
        }
        if (article.descriptions().size() != 1) {
            return "the catalog article has " + article.descriptions().size() + " " + ProdisPublicationProjection.DESCRIPTION_LANGUAGE
                    + " descriptions (exactly one is supported)";
        }
        CatalogArticleDescriptionRow description = article.descriptions().get(0);
        if (!description.active() || description.deleted()) {
            return "the " + ProdisPublicationProjection.DESCRIPTION_LANGUAGE + " description is inactive or deleted";
        }
        return null;
    }

    private static boolean isSupportedCode(String code) {
        if (code == null || code.length() != 1) {
            return false;
        }
        int value = code.charAt(0) - '0';
        return value >= FIRST_CODE && value <= LAST_CODE;
    }

    private static ArticleState prior(CatalogArticleRow article) {
        CatalogArticlePriceRow price = article.prices().get(0);
        Map<Integer, Double> percentages = new TreeMap<>();
        Map<Integer, Double> prices = new TreeMap<>();
        for (CatalogArticlePriceDetailRow detail : price.details()) {
            int code = Integer.parseInt(detail.code());
            percentages.put(code, detail.percentage());
            prices.put(code, detail.price());
        }
        return new ArticleState(price.priceCode() == null ? "" : price.priceCode(), price.brutPricePolitics(),
                price.basePrice(), price.brutPrice(), price.brutPricePercentage(), percentages, prices,
                article.descriptions().get(0).description(), price.salesPriceLastUpdated());
    }

    private static List<Change> changes(ArticleState prior, ArticleState target) {
        List<Change> changes = new ArrayList<>();
        if (!sameAmount(prior.basePrice(), target.basePrice())) {
            changes.add(new Change(Column.BASE_PRICE, null, plain(prior.basePrice()), plain(target.basePrice())));
        }
        if (!Objects.equals(prior.brutPrice(), target.brutPrice())) {
            changes.add(new Change(Column.BRUT_PRICE, null, plain(prior.brutPrice()), plain(target.brutPrice())));
        }
        if (!Objects.equals(prior.brutPricePercentage(), target.brutPricePercentage())) {
            changes.add(new Change(Column.BRUT_PRICE_PERCENTAGE, null, plain(prior.brutPricePercentage()),
                    plain(target.brutPricePercentage())));
        }
        for (int code = FIRST_CODE; code <= LAST_CODE; code++) {
            Double priorPercentage = prior.percentages().get(code);
            Double targetPercentage = target.percentages().get(code);
            if (!Objects.equals(priorPercentage, targetPercentage)) {
                changes.add(new Change(Column.DETAIL_PERCENTAGE, code, plain(priorPercentage), plain(targetPercentage)));
            }
            Double priorPrice = prior.prices().get(code);
            Double targetPrice = target.prices().get(code);
            if (!Objects.equals(priorPrice, targetPrice)) {
                changes.add(new Change(Column.DETAIL_PRICE, code, plain(priorPrice), plain(targetPrice)));
            }
        }
        if (!Objects.equals(prior.description(), target.description())) {
            changes.add(new Change(Column.DESCRIPTION, null, prior.description(), target.description()));
        }
        return changes;
    }

    // --- hulp --------------------------------------------------------------------------------------------------------

    private static void requireSameIdentity(Item item, CatalogArticleLookup lookup) {
        if (!lookup.identity().equals(item.identity().toLookupIdentity())) {
            throw new IllegalArgumentException("the Prodis lookup belongs to another identity than mutation "
                    + item.mutationId());
        }
    }

    private static Plan rejected(Item item, String code, String message, LegacyMasterMode mode, ArticleState prior,
                                 List<Notice> notices) {
        notices.add(new Notice(code, Severity.BLOCKING, FIELD_ARTICLE, message));
        return blocked(item, PlanOutcome.REJECTED, code, mode, prior, notices);
    }

    private static Plan blocked(Item item, PlanOutcome outcome, String code, LegacyMasterMode mode, ArticleState prior,
                                List<Notice> notices) {
        return new Plan(item.mutationId(), item.requestHash(), outcome, code, mode, prior, null, List.of(), false, List.of(),
                notices);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String plain(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** De veldnaam van VKP-prijs {@code n} (niet het percentage), voor meldingen. */
    public static String salesPriceField(int code) {
        return FIELD_SALES_PRICE_PREFIX + code;
    }
}
