package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourcePrice;
import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourceRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleIdentity;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Projectie van één bundelsnapshotrij naar één publicatie-item voor de Prodis-database (design
 * {@code publicatie-prodis-db-direct-design.md} par. 2.5, 2.6 en 6, slice P2b; beslissingen 2026-10-22 P-V3, P-V4, P-V5,
 * Q2). Pure klasse: geen Spring, geen database, geen tijd — dezelfde bronrij levert altijd hetzelfde item en dezelfde
 * {@link Item#requestHash()}.
 *
 * <h2>Wat het item draagt</h2>
 * <ul>
 *   <li><b>Identiteit</b> in Prodis-businesscodes: {@code supplier.number}, {@code supplier_group.legacy_code}
 *       ({@code lev|grp|korting}, zoals de Prodis-datasync hem opbouwt), {@code supplier_reference_stripped} en
 *       {@code supplier_catalog.scope_key} (= {@code import_link.library_code}). Nooit een Prodis-id.</li>
 *   <li><b>Actie</b> CREATE of UPDATE; de planner maakt van CREATE in v1 {@code NOT_SENT} (P-V3).</li>
 *   <li><b>Velden</b> basisprijs, VKP1..5-percentages en NL-omschrijving, elk {@link FieldState#VALUE} (schrijven) of
 *       {@link FieldState#KEEP} (de Prodis-waarde behouden). Een niet-gemapt veld ontbreekt (geen uitspraak).</li>
 *   <li><b>Expected</b> (P-V4): de waarde die we in Prodis verwachten, enkel voor {@code VALUE}-velden van een UPDATE.</li>
 * </ul>
 *
 * <h2>Regels (in deze volgorde, elk met een {@link Notice})</h2>
 * <ol>
 *   <li>Identiteit onvolledig of onbekende kortingscodetoestand → {@code IDENTITY_INCOMPLETE} (blokkerend).</li>
 *   <li>Actie anders dan CREATE/UPDATE → {@code ACTION_UNSUPPORTED} (blokkerend).</li>
 *   <li>Munt van de basisprijs of van een VKP-component ≠ {@code EUR} (ook ontbrekend: nooit EUR aannemen) →
 *       {@code CURRENCY_NOT_SUPPORTED_BY_PRODIS} (blokkerend; Prodis heeft geen muntkolom).</li>
 *   <li>Leeg ({@code null}, of omschrijving {@code EMPTY}) → {@code KEEP} + {@code EMPTY_KEEPS_EXISTING} (P-V5).</li>
 *   <li>Expliciete 0 voor basisprijs of VKP% → {@code KEEP} + {@code ZERO_KEEPS_EXISTING} (Q2, zoals Prg_1179).</li>
 *   <li>UPDATE zonder bruikbare expected → {@code KEEP} + {@code EXPECTED_NOT_AVAILABLE}: basisprijs zonder
 *       {@code before_base_price}; een percentage zonder {@code before_percentage} of uit een snapshot spec &lt; 2
 *       (par. 2.5; sinds P4b vult het bevriezen {@code before_percentage} en krijgen nieuwe bundels spec 2 — bundels
 *       bevroren vóór P4b blijven spec 1 en schrijven dus nooit percentages).</li>
 *   <li>Precisie (par. 2.6) op elk {@code VALUE}-bedrag: basisprijs &gt; 6 decimalen of &gt; 15 cijfers vóór de komma,
 *       percentage &gt; 15 significante cijfers → {@code PRECISION_LOSS} (blokkerend); basisprijs &gt; 3 of percentage
 *       &gt; 2 decimalen → {@code EXCEEDS_LEGACY_PRECISION} (waarschuwing, legacy {@code #6.#3} / {@code N###.##}).</li>
 *   <li>Een omschrijving met toestand {@code VALUE} zonder tekst, of een onbekende toestand →
 *       {@code SNAPSHOT_INCOMPLETE} (blokkerend).</li>
 * </ol>
 * Er wordt nooit een waarde aangepast, afgerond of ingevuld: elke afwijking is een melding.
 */
public final class ProdisPublicationProjection {

    /** Versie van de vorm en van de canonieke tekst achter {@link Item#requestHash()}. */
    public static final int PAYLOAD_SPEC_VERSION = 1;

    /** De enige munt die Prodis kan dragen ({@code catalog_article_price} heeft geen muntkolom). */
    public static final String EUR = "EUR";

    public static final String CODE_IDENTITY_INCOMPLETE = "IDENTITY_INCOMPLETE";
    public static final String CODE_ACTION_UNSUPPORTED = "ACTION_UNSUPPORTED";
    public static final String CODE_CURRENCY_NOT_SUPPORTED_BY_PRODIS = "CURRENCY_NOT_SUPPORTED_BY_PRODIS";
    public static final String CODE_EMPTY_KEEPS_EXISTING = "EMPTY_KEEPS_EXISTING";
    public static final String CODE_ZERO_KEEPS_EXISTING = "ZERO_KEEPS_EXISTING";
    public static final String CODE_EXPECTED_NOT_AVAILABLE = "EXPECTED_NOT_AVAILABLE";
    public static final String CODE_PRECISION_LOSS = "PRECISION_LOSS";
    public static final String CODE_EXCEEDS_LEGACY_PRECISION = "EXCEEDS_LEGACY_PRECISION";
    public static final String CODE_SNAPSHOT_INCOMPLETE = "SNAPSHOT_INCOMPLETE";

    public static final String FIELD_IDENTITY = "IDENTITY";
    public static final String FIELD_ACTION = "ACTION";
    public static final String FIELD_CURRENCY = "BASE_PRICE_CURRENCY";
    public static final String FIELD_BASE_PRICE = "BASE_PRICE";
    public static final String FIELD_DESCRIPTION = "DESCRIPTION";

    /** De taal van de gepubliceerde omschrijving ({@code language.code}). */
    public static final String DESCRIPTION_LANGUAGE = "NL";

    /** Basisprijs: {@code numeric(21,6)} in Prodis. */
    static final int BASE_PRICE_MAX_DECIMALS = 6;
    static final int BASE_PRICE_MAX_INTEGER_DIGITS = 15;
    /** Percentages: {@code double precision} in Prodis; tot 15 significante cijfers gaat dat verliesvrij heen en terug. */
    static final int PERCENTAGE_MAX_SIGNIFICANT_DIGITS = 15;
    /** Legacy-velden: bedrag {@code #6.#3}, percentage {@code N###.##}. */
    static final int LEGACY_AMOUNT_DECIMALS = 3;
    static final int LEGACY_PERCENTAGE_DECIMALS = 2;

    static final int FIRST_CODE = 1;
    static final int LAST_CODE = 5;

    private static final String DISCOUNT_VALUE = "VALUE";
    private static final String DISCOUNT_EMPTY = "EMPTY";
    private static final String DISCOUNT_NOT_USED = "NOT_USED";
    private static final String DESCRIPTION_VALUE = "VALUE";
    private static final String DESCRIPTION_EMPTY = "EMPTY";
    private static final String DESCRIPTION_NOT_MAPPED = "NOT_MAPPED";
    private static final int FIRST_SPEC_WITH_BEFORE_PERCENTAGE = 2;

    private ProdisPublicationProjection() {
    }

    /** De veldnaam van VKP-percentage {@code n} (zelfde code als de PSIMPORT-preview). */
    public static String percentageField(int code) {
        return "VKP" + code + "_PCT";
    }

    public enum Action {
        CREATE,
        UPDATE
    }

    /** {@code VALUE}: schrijven; {@code KEEP}: de bestaande Prodis-waarde behouden (niet geschreven, niet bewaakt). */
    public enum FieldState {
        VALUE,
        KEEP
    }

    /** {@code BLOCKING}: het item wordt niet toegepast (REJECTED, CONFLICT of NOT_SENT); {@code WARNING}: zichtbaar, niet blokkerend. */
    public enum Severity {
        WARNING,
        BLOCKING
    }

    /**
     * @param field het betrokken veld ({@code FIELD_*} of {@link #percentageField(int)}), of {@code null} voor het hele item
     */
    public record Notice(String code, Severity severity, String field, String message) {
        public Notice {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(severity, "severity");
        }

        public boolean blocking() {
            return severity == Severity.BLOCKING;
        }
    }

    /**
     * De identiteit in Prodis-businesscodes.
     *
     * @param discountCode    de kortingscode zoals ze in {@code legacy_code} staat ({@code ""} = geen)
     * @param groupLegacyCode {@code supplier_group.legacy_code} = {@code lev|grp|korting}
     */
    public record Identity(String supplierNumber, String supplierGroup, String discountCode, String groupLegacyCode,
                           String supplierReference, String scopeKey) {

        public boolean complete() {
            return supplierNumber != null && groupLegacyCode != null && supplierReference != null && scopeKey != null;
        }

        /** De sleutel van de leesbron ({@code ProdisReferenceService.lookupCatalogArticles}). */
        public CatalogArticleIdentity toLookupIdentity() {
            if (!complete()) {
                throw new IllegalStateException("identity is incomplete");
            }
            return new CatalogArticleIdentity(supplierNumber, groupLegacyCode, scopeKey, supplierReference);
        }
    }

    /** Basisprijs of percentage; {@code value} is {@code null} bij {@code KEEP}. */
    public record AmountField(FieldState state, BigDecimal value) {
        public AmountField {
            Objects.requireNonNull(state, "state");
            if ((state == FieldState.VALUE) != (value != null)) {
                throw new IllegalArgumentException("VALUE needs a value, KEEP has none");
            }
        }

        static AmountField keep() {
            return new AmountField(FieldState.KEEP, null);
        }
    }

    /** NL-omschrijving; {@code value} is {@code null} bij {@code KEEP}. */
    public record DescriptionField(FieldState state, String value) {
        public DescriptionField {
            Objects.requireNonNull(state, "state");
            if ((state == FieldState.VALUE) != (value != null)) {
                throw new IllegalArgumentException("VALUE needs a value, KEEP has none");
            }
        }
    }

    /**
     * De verwachte Prodis-waarden (P-V4), enkel voor {@code VALUE}-velden van een UPDATE.
     *
     * @param basePrice   {@code import_mutation.before_base_price}, of {@code null}
     * @param percentages per VKP-code de {@code before_percentage}
     */
    public record Expected(BigDecimal basePrice, Map<Integer, BigDecimal> percentages) {
        public Expected {
            percentages = Collections.unmodifiableMap(new TreeMap<>(percentages));
        }
    }

    /**
     * Eén publicatie-item.
     *
     * @param action           {@code null} enkel bij {@code ACTION_UNSUPPORTED}
     * @param basePrice        nooit {@code null} (een mutatie draagt altijd een basisprijsuitspraak)
     * @param salesPercentages enkel de gemapte VKP-codes 1..5
     * @param description      {@code null} = niet gemapt (geen uitspraak)
     * @param sourceAmounts    per VKP-code het bronbedrag uit de snapshot (voor {@code PRICE_ROUNDING_DIFFERENCE})
     * @param notices          in vaste volgorde (identiteit, actie, munt, basisprijs, VKP1..5, omschrijving)
     * @param requestHash      SHA-256 (hex) over de canonieke vorm, zie {@link #canonical(Item)}
     */
    public record Item(int payloadSpecVersion, long bundleId, long mutationId, Action action, Identity identity,
                       String currency, AmountField basePrice, Map<Integer, AmountField> salesPercentages,
                       DescriptionField description, Expected expected, Map<Integer, BigDecimal> sourceAmounts,
                       List<Notice> notices, String requestHash) {
        public Item {
            salesPercentages = Collections.unmodifiableMap(new TreeMap<>(salesPercentages));
            sourceAmounts = Collections.unmodifiableMap(new TreeMap<>(sourceAmounts));
            notices = List.copyOf(notices);
        }

        /** {@code true} als de projectie zelf het item al blokkeert (REJECTED, ongeacht wat Prodis bevat). */
        public boolean rejected() {
            return notices.stream().anyMatch(Notice::blocking);
        }

        /** De eerste blokkerende melding, of {@code null}. */
        public Notice rejection() {
            return notices.stream().filter(Notice::blocking).findFirst().orElse(null);
        }
    }

    /** Projecteert één snapshotrij. Gooit nooit voor slechte data: die wordt een blokkerende melding. */
    public static Item project(SourceRow row) {
        Objects.requireNonNull(row, "row");
        List<Notice> notices = new ArrayList<>();

        Identity identity = identity(row, notices);
        Action action = action(row.actionType(), notices);
        boolean update = action == Action.UPDATE;
        currency(row, notices);

        BigDecimal expectedBase = null;
        AmountField basePrice = basePrice(row.afterBasePrice(), update ? row.beforeBasePrice() : null, update, notices);
        if (update && basePrice.state() == FieldState.VALUE) {
            expectedBase = row.beforeBasePrice();
        }

        Map<Integer, AmountField> percentages = new TreeMap<>();
        Map<Integer, BigDecimal> expectedPercentages = new TreeMap<>();
        Map<Integer, BigDecimal> sourceAmounts = new TreeMap<>();
        boolean beforePercentagesInSnapshot = specVersionAtLeast(row.snapshotSpecVersion(), FIRST_SPEC_WITH_BEFORE_PERCENTAGE);
        for (int code = FIRST_CODE; code <= LAST_CODE; code++) {
            SourcePrice component = component(row, code);
            if (component == null) {
                continue;
            }
            if (component.sourceAmount() != null) {
                sourceAmounts.put(code, component.sourceAmount());
            }
            BigDecimal before = beforePercentagesInSnapshot ? component.beforePercentage() : null;
            AmountField field = percentage(code, component.percentage(), before, update, notices);
            percentages.put(code, field);
            if (update && field.state() == FieldState.VALUE) {
                expectedPercentages.put(code, before);
            }
        }

        DescriptionField description = description(row.description(), row.descriptionState(), notices);

        Expected expected = new Expected(expectedBase, expectedPercentages);
        Item draft = new Item(PAYLOAD_SPEC_VERSION, row.bundleId(), row.mutationId(), action, identity,
                row.basePriceCurrency(), basePrice, percentages, description, expected, sourceAmounts, notices, null);
        return new Item(draft.payloadSpecVersion(), draft.bundleId(), draft.mutationId(), draft.action(), draft.identity(),
                draft.currency(), draft.basePrice(), draft.salesPercentages(), draft.description(), draft.expected(),
                draft.sourceAmounts(), draft.notices(), sha256Hex(canonical(draft)));
    }

    // --- regels ------------------------------------------------------------------------------------------------------

    private static Identity identity(SourceRow row, List<Notice> notices) {
        String discount = switch (row.identityDiscountState() == null ? "" : row.identityDiscountState()) {
            case DISCOUNT_VALUE -> row.identityDiscountCode();
            // Zoals de Prodis-datasync (trimToEmpty): geen kortingscode = lege derde component. Een identiteit zonder
            // gemapte kortingscode (NOT_USED) vindt dus enkel een groep zonder kortingscode; een artikel in een groep
            // met kortingscode wordt dan ARTICLE_NOT_FOUND, nooit een gegokte groep.
            case DISCOUNT_EMPTY, DISCOUNT_NOT_USED -> "";
            default -> null;
        };
        String legacyCode = row.identitySupplier() == null || row.identitySupplierGroup() == null || discount == null
                ? null : row.identitySupplier() + "|" + row.identitySupplierGroup() + "|" + discount;
        Identity identity = new Identity(row.identitySupplier(), row.identitySupplierGroup(), discount, legacyCode,
                row.identitySupplierReference(), row.libraryCode());
        if (!identity.complete()) {
            List<String> missing = new ArrayList<>();
            if (row.identitySupplier() == null) {
                missing.add("supplier");
            }
            if (row.identitySupplierGroup() == null) {
                missing.add("supplier group");
            }
            if (discount == null) {
                missing.add("discount code (state " + row.identityDiscountState() + ")");
            }
            if (row.identitySupplierReference() == null) {
                missing.add("supplier reference");
            }
            if (row.libraryCode() == null) {
                missing.add("library code");
            }
            notices.add(new Notice(CODE_IDENTITY_INCOMPLETE, Severity.BLOCKING, FIELD_IDENTITY,
                    "identity is incomplete: " + String.join(", ", missing)));
        }
        return identity;
    }

    private static Action action(String actionType, List<Notice> notices) {
        if ("CREATE".equals(actionType)) {
            return Action.CREATE;
        }
        if ("UPDATE".equals(actionType)) {
            return Action.UPDATE;
        }
        notices.add(new Notice(CODE_ACTION_UNSUPPORTED, Severity.BLOCKING, FIELD_ACTION,
                "action '" + actionType + "' cannot be published to Prodis (only CREATE and UPDATE)"));
        return null;
    }

    private static void currency(SourceRow row, List<Notice> notices) {
        if (!EUR.equals(row.basePriceCurrency())) {
            notices.add(new Notice(CODE_CURRENCY_NOT_SUPPORTED_BY_PRODIS, Severity.BLOCKING, FIELD_CURRENCY,
                    row.basePriceCurrency() == null
                            ? "the currency of the base price is unknown; Prodis only holds EUR and EUR is never assumed"
                            : "currency " + row.basePriceCurrency() + " cannot be published: Prodis only holds EUR "
                                    + "(no conversion)"));
        }
        for (int code = FIRST_CODE; code <= LAST_CODE; code++) {
            SourcePrice component = component(row, code);
            if (component != null && component.currency() != null && !EUR.equals(component.currency())) {
                notices.add(new Notice(CODE_CURRENCY_NOT_SUPPORTED_BY_PRODIS, Severity.BLOCKING, percentageField(code),
                        "currency " + component.currency() + " of sales price " + code
                                + " cannot be published: Prodis only holds EUR (no conversion)"));
            }
        }
    }

    private static AmountField basePrice(BigDecimal after, BigDecimal before, boolean update, List<Notice> notices) {
        if (after == null) {
            notices.add(new Notice(CODE_EMPTY_KEEPS_EXISTING, Severity.WARNING, FIELD_BASE_PRICE,
                    "the base price is empty: the Prodis value is kept"));
            return AmountField.keep();
        }
        if (after.signum() == 0) {
            notices.add(new Notice(CODE_ZERO_KEEPS_EXISTING, Severity.WARNING, FIELD_BASE_PRICE,
                    "the base price is 0: the Prodis value is kept (as legacy Prg_1179)"));
            return AmountField.keep();
        }
        if (update && before == null) {
            notices.add(new Notice(CODE_EXPECTED_NOT_AVAILABLE, Severity.WARNING, FIELD_BASE_PRICE,
                    "no previous base price is known to verify the Prodis value against: the Prodis value is kept"));
            return AmountField.keep();
        }
        if (decimals(after) > BASE_PRICE_MAX_DECIMALS || integerDigits(after) > BASE_PRICE_MAX_INTEGER_DIGITS) {
            notices.add(new Notice(CODE_PRECISION_LOSS, Severity.BLOCKING, FIELD_BASE_PRICE,
                    "base price " + after.toPlainString() + " does not fit Prodis numeric(21,6) without loss"));
        } else if (decimals(after) > LEGACY_AMOUNT_DECIMALS) {
            notices.add(new Notice(CODE_EXCEEDS_LEGACY_PRECISION, Severity.WARNING, FIELD_BASE_PRICE,
                    "base price " + after.toPlainString() + " has more than " + LEGACY_AMOUNT_DECIMALS
                            + " decimals; legacy holds " + LEGACY_AMOUNT_DECIMALS));
        }
        return new AmountField(FieldState.VALUE, after);
    }

    private static AmountField percentage(int code, BigDecimal percentage, BigDecimal before, boolean update,
                                          List<Notice> notices) {
        String field = percentageField(code);
        if (percentage == null) {
            notices.add(new Notice(CODE_EMPTY_KEEPS_EXISTING, Severity.WARNING, field,
                    "sales percentage " + code + " is empty: the Prodis value is kept"));
            return AmountField.keep();
        }
        if (percentage.signum() == 0) {
            notices.add(new Notice(CODE_ZERO_KEEPS_EXISTING, Severity.WARNING, field,
                    "sales percentage " + code + " is 0: the Prodis value is kept (as legacy Prg_1179)"));
            return AmountField.keep();
        }
        if (update && before == null) {
            notices.add(new Notice(CODE_EXPECTED_NOT_AVAILABLE, Severity.WARNING, field,
                    "no previous sales percentage " + code + " is known in this snapshot to verify the Prodis value "
                            + "against: the Prodis value is kept"));
            return AmountField.keep();
        }
        BigDecimal stripped = percentage.stripTrailingZeros();
        if (stripped.precision() > PERCENTAGE_MAX_SIGNIFICANT_DIGITS) {
            notices.add(new Notice(CODE_PRECISION_LOSS, Severity.BLOCKING, field,
                    "sales percentage " + code + " (" + percentage.toPlainString() + ") has more than "
                            + PERCENTAGE_MAX_SIGNIFICANT_DIGITS + " significant digits: Prodis double precision loses it"));
        } else if (decimals(percentage) > LEGACY_PERCENTAGE_DECIMALS) {
            notices.add(new Notice(CODE_EXCEEDS_LEGACY_PRECISION, Severity.WARNING, field,
                    "sales percentage " + code + " (" + percentage.toPlainString() + ") has more than "
                            + LEGACY_PERCENTAGE_DECIMALS + " decimals; legacy holds " + LEGACY_PERCENTAGE_DECIMALS));
        }
        return new AmountField(FieldState.VALUE, percentage);
    }

    private static DescriptionField description(String description, String state, List<Notice> notices) {
        if (DESCRIPTION_NOT_MAPPED.equals(state)) {
            return null;
        }
        if (DESCRIPTION_EMPTY.equals(state)) {
            notices.add(new Notice(CODE_EMPTY_KEEPS_EXISTING, Severity.WARNING, FIELD_DESCRIPTION,
                    "the description is empty: the Prodis description is kept"));
            return new DescriptionField(FieldState.KEEP, null);
        }
        if (DESCRIPTION_VALUE.equals(state) && description != null && !description.isBlank()) {
            return new DescriptionField(FieldState.VALUE, description);
        }
        notices.add(new Notice(CODE_SNAPSHOT_INCOMPLETE, Severity.BLOCKING, FIELD_DESCRIPTION,
                "the snapshot description has state '" + state + "'" + (description == null ? " without text" : "")
                        + "; the item cannot be published"));
        return null;
    }

    // --- canonieke vorm en hash ------------------------------------------------------------------------------------

    /**
     * De canonieke tekst achter {@link Item#requestHash()} (payload spec 1), in de stijl van de bundelhashes: elk veld
     * afgesloten met U+001F, het item afgesloten met U+001E, {@code null} als de tekst {@code null}, bedragen als
     * {@code stripTrailingZeros().toPlainString()}. Volgorde: kop, actie, identiteit, munt, basisprijs, gemapte
     * VKP-codes oplopend, omschrijving, expected basisprijs, expected percentages oplopend. Meldingen en bronbedragen
     * zitten er bewust niet in: ze volgen uit de inhoud en worden niet geschreven.
     */
    static String canonical(Item item) {
        StringBuilder line = new StringBuilder(256);
        field(line, "prodis-publication");
        field(line, Integer.toString(item.payloadSpecVersion()));
        field(line, Long.toString(item.mutationId()));
        field(line, item.action() == null ? null : item.action().name());
        Identity identity = item.identity();
        field(line, identity.supplierNumber());
        field(line, identity.groupLegacyCode());
        field(line, identity.supplierReference());
        field(line, identity.scopeKey());
        field(line, item.currency());
        field(line, FIELD_BASE_PRICE);
        field(line, item.basePrice().state().name());
        field(line, plain(item.basePrice().value()));
        item.salesPercentages().forEach((code, value) -> {
            field(line, percentageField(code));
            field(line, value.state().name());
            field(line, plain(value.value()));
        });
        field(line, FIELD_DESCRIPTION);
        field(line, item.description() == null ? null : item.description().state().name());
        field(line, item.description() == null ? null : item.description().value());
        field(line, "EXPECTED_" + FIELD_BASE_PRICE);
        field(line, plain(item.expected().basePrice()));
        item.expected().percentages().forEach((code, value) -> {
            field(line, "EXPECTED_" + percentageField(code));
            field(line, plain(value));
        });
        line.append('\u001E');
        return line.toString();
    }

    private static void field(StringBuilder line, String value) {
        line.append(value == null ? "null" : value).append('\u001F');
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    // --- hulp --------------------------------------------------------------------------------------------------------

    private static SourcePrice component(SourceRow row, int code) {
        String componentCode = "VKP" + code;
        for (SourcePrice price : row.prices()) {
            if (componentCode.equals(price.componentCode())) {
                return price;
            }
        }
        return null;
    }

    private static boolean specVersionAtLeast(String version, int minimum) {
        if (version == null || !version.matches("[0-9]{1,9}")) {
            return false;
        }
        return Integer.parseInt(version) >= minimum;
    }

    static int decimals(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    static int integerDigits(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return Math.max(0, stripped.precision() - stripped.scale());
    }
}
