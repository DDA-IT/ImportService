package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.DiscountCodeState;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.HexFormat;

/**
 * Zet één geparste bronregel om in een kandidaat met haar aanbiedingsidentiteit en vingerafdrukken
 * (design par. 8). Pure klasse: geen Spring, geen database, geen tijd-, volgorde- of
 * omgevingsafhankelijkheid — dezelfde invoer levert altijd byte-identieke hashes.
 * <p>
 * <b>Businessgedrag.</b>
 * <ul>
 *   <li>De identiteit is leverancier + leveranciersgroep + leveranciersreferentie, uitgebreid met
 *       de kortingscode wanneer de revisie die mapt (beslissingslog 18/09). Bibliotheek en
 *       bronorganisatie zijn scope en zitten nooit in de sleutel.</li>
 *   <li>Bij {@link IdentityProfileKind#THREE_PART} wordt de kortingscodekolom <b>niet gelezen</b>,
 *       ook niet wanneer ze in het bestand staat: waarde {@code null}, toestand
 *       {@link DiscountCodeState#NOT_USED}. Bij een vierdelige identiteit is een lege bronwaarde
 *       een geldige, betekenisvolle waarde ({@code ""} / {@link DiscountCodeState#EMPTY}). Een
 *       driedelige sleutel kan daardoor nooit dezelfde hash opleveren als een vierdelige met lege
 *       kortingscode.</li>
 *   <li>Een lege leverancier, groep of referentie verwerpt de regel
 *       ({@link #CODE_IDENTITY_COMPONENT_EMPTY}); er wordt nooit een placeholder ingevuld.</li>
 *   <li>Een ontbrekende, onleesbare of te precieze prijs verwerpt de regel; de prijs wordt
 *       <b>nooit</b> 0 of {@code null} (businessanalyse par. 16.5).</li>
 * </ul>
 * <b>Vingerafdrukken.</b> {@code article_fingerprint} dekt de omschrijving,
 * {@code price_fingerprint} de basisprijs en munt, {@code combined_fingerprint} is de SHA-256 over
 * de binaire aaneenschakeling van identiteit, artikel en prijs. Zo kan de delta in fase 2d op
 * hashniveau bepalen of een aanbieding gewijzigd is, zonder de bronregel te bewaren.
 * <p>
 * <b>Grens van fase 2.</b> Er is nog geen muntveld in de bronconfiguratie: {@code basePriceCurrency}
 * blijft {@code null} ("onbekend"). Er wordt nooit stilzwijgend EUR verondersteld.
 */
public final class CandidateNormaliser {

    /** Leverancier, groep of referentie is leeg; de regel wordt verworpen. */
    public static final String CODE_IDENTITY_COMPONENT_EMPTY = "IDENTITY_COMPONENT_EMPTY";
    /** De vierdelige identiteit mist haar kortingscodekolom; blokkeert de levering. */
    public static final String CODE_CONFIG_DISCOUNT_FIELD_MISSING = "CONFIG_DISCOUNT_FIELD_MISSING";
    /** Een gedeclareerd veld is in dit bestand niet oplosbaar; blokkeert de levering. */
    public static final String CODE_CONFIG_FIELD_NOT_RESOLVED = "CONFIG_FIELD_NOT_RESOLVED";
    /** Een bronwaarde past niet in de doelkolom; afkappen zou data stil wijzigen. */
    public static final String CODE_VALUE_TOO_LONG = "VALUE_TOO_LONG";
    /** De prijs heeft meer cijfers dan {@code numeric(24,6)} kan bewaren. */
    public static final String CODE_PRICE_OUT_OF_RANGE = "PRICE_OUT_OF_RANGE";

    /** {@code identity_*} kolommen in {@code import_candidate_stage} zijn varchar(200). */
    public static final int MAX_IDENTITY_LENGTH = 200;
    /** {@code description} is varchar(1000). */
    public static final int MAX_DESCRIPTION_LENGTH = 1000;
    /** {@code base_price} is numeric(24,6): hoogstens 18 cijfers vóór de komma. */
    public static final int MAX_PRICE_INTEGER_DIGITS = 18;

    /** Resultaat van één regel: ofwel een kandidaat, ofwel precies één verwerpingsreden. */
    public sealed interface Result permits NormalisedCandidate, RowIssue {
    }

    /** Een gevalideerde kandidaat, klaar om gestaged te worden. Hashes zijn binair (32 bytes). */
    public record NormalisedCandidate(
            long rowNumber,
            String supplier,
            String supplierGroup,
            String supplierReference,
            String discountCode,
            DiscountCodeState discountState,
            byte[] identityHash,
            BigDecimal basePrice,
            String basePriceCurrency,
            String description,
            byte[] articleFingerprint,
            byte[] priceFingerprint,
            byte[] combinedFingerprint) implements Result {

        /**
         * De idempotentievoorvoegsel van de mutaties van deze kandidaat (design par. 2 en par. 4):
         * {@code <deliveryId>:<revisionId>:<identity_hash_hex>}. De hex komt uit de staging en nooit
         * uit een databasefunctie, zodat de sleutel op elke database identiek is.
         */
        public String mutationKeyPrefix(long deliveryId, long definitionRevisionId) {
            return deliveryId + ":" + definitionRevisionId + ":" + HexFormat.of().formatHex(identityHash);
        }
    }

    /** Eén verworpen regel met de reden; wordt een {@code import_row_issue}. */
    public record RowIssue(long rowNumber, String code, String fieldName, String sourceValue, String message)
            implements Result {
    }

    /**
     * Normaliseert één regel.
     *
     * @throws ScreeningBlockedException wanneer de configuratie zelf niet op dit bestand past; dat
     *                                   blokkeert de levering in plaats van elke regel afzonderlijk
     *                                   te verwerpen
     */
    public Result normalise(ParsedRow row, SourceStructureConfig config) {
        try {
            String supplier = identityComponent(row, config.supplierField(), row.value(
                    position(row, config.supplierField())));
            String group = identityComponent(row, config.supplierGroupField(), row.value(
                    position(row, config.supplierGroupField())));
            String reference = identityComponent(row, config.supplierReferenceField(), row.value(
                    position(row, config.supplierReferenceField())));

            String discountCode = null;
            DiscountCodeState discountState = DiscountCodeState.NOT_USED;
            if (config.identityProfileKind() == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE) {
                String field = config.discountCodeField();
                if (field == null) {
                    throw new ScreeningBlockedException(CODE_CONFIG_DISCOUNT_FIELD_MISSING,
                            "Identity profile FOUR_PART_WITH_DISCOUNT_CODE requires a mapped discount code field");
                }
                Integer discountPosition = row.positions().position(field);
                if (discountPosition == null || discountPosition >= row.values().size()) {
                    throw new ScreeningBlockedException(CODE_CONFIG_DISCOUNT_FIELD_MISSING,
                            "Discount code column '" + field + "' is not present in the source");
                }
                String raw = trim(row.value(discountPosition));
                requireLength(raw, field, MAX_IDENTITY_LENGTH);
                discountCode = raw;
                discountState = raw.isEmpty() ? DiscountCodeState.EMPTY : DiscountCodeState.VALUE;
            }

            String priceField = config.basePriceField();
            String rawPrice = row.value(position(row, priceField));
            BigDecimal basePrice = ImportValueRules.decimal(rawPrice, priceField);
            if (basePrice.precision() - basePrice.scale() > MAX_PRICE_INTEGER_DIGITS) {
                throw new ImportValueException(CODE_PRICE_OUT_OF_RANGE, priceField, rawPrice,
                        "Base price has more than " + MAX_PRICE_INTEGER_DIGITS + " digits before the decimal point");
            }
            String currency = null; // Fase 2 kent geen muntveld; nooit stil EUR veronderstellen.

            String description = null;
            if (config.descriptionField() != null) {
                description = trim(row.value(position(row, config.descriptionField())));
                requireLength(description, config.descriptionField(), MAX_DESCRIPTION_LENGTH);
            }

            int version = config.canonicalisationVersion();
            String canonicalIdentity = config.identityProfileKind() == IdentityProfileKind.THREE_PART
                    ? ImportValueRules.canonical(version, supplier, group, reference)
                    : ImportValueRules.canonical(version, supplier, group, discountCode, reference);
            byte[] identityHash = ImportValueRules.sha256Utf8(canonicalIdentity);
            byte[] articleFingerprint = ImportValueRules.sha256Utf8(
                    ImportValueRules.canonical(version, description));
            byte[] priceFingerprint = ImportValueRules.sha256Utf8(
                    ImportValueRules.canonical(version, basePrice.toPlainString(), currency));
            byte[] combinedFingerprint = ImportValueRules.sha256(
                    concat(identityHash, articleFingerprint, priceFingerprint));

            return new NormalisedCandidate(row.lineNumber(), supplier, group, reference, discountCode,
                    discountState, identityHash, basePrice, currency, description, articleFingerprint,
                    priceFingerprint, combinedFingerprint);
        } catch (ImportValueException rejected) {
            return new RowIssue(row.lineNumber(), rejected.getCode(), rejected.getField(),
                    rejected.getRawValue(), rejected.getMessage());
        }
    }

    private static int position(ParsedRow row, String field) {
        Integer position = row.positions().position(field);
        if (position == null || position >= row.values().size()) {
            throw new ScreeningBlockedException(CODE_CONFIG_FIELD_NOT_RESOLVED,
                    "Declared field '" + field + "' cannot be resolved to a column in the source");
        }
        return position;
    }

    private static String identityComponent(ParsedRow row, String field, String raw) {
        String value = trim(raw);
        if (value.isEmpty()) {
            throw new ImportValueException(CODE_IDENTITY_COMPONENT_EMPTY, field, raw,
                    "Identity component '" + field + "' is empty on line " + row.lineNumber());
        }
        requireLength(value, field, MAX_IDENTITY_LENGTH);
        return value;
    }

    private static void requireLength(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new ImportValueException(CODE_VALUE_TOO_LONG, field, value,
                    "Value for '" + field + "' is longer than " + maxLength + " characters");
        }
    }

    /** Canonicalisatieversie 1: enkel trimmen, geen case-folding en geen Unicode-normalisatie. */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        for (byte[] part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }
}
