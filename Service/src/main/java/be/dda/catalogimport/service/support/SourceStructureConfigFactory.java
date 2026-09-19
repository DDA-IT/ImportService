package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Leest en valideert de bronconfiguratie van een bevroren revisie (design par. 7) tot een
 * {@link SourceStructureConfig}.
 * <p>
 * <b>Businessgedrag.</b> Onvolledige of tegenstrijdige configuratie is geen regelfout maar een
 * contractfout: de volledige levering wordt geblokkeerd met een {@code CONFIG_*}-code, vóórdat er
 * ook maar één byte geparsed is. Zo krijgt de beheerder één duidelijke melding in plaats van een
 * miljoen identieke rijfouten.
 * <p>
 * Deze klasse leest de revisie uitsluitend via getters en geeft een momentopname terug; ze wordt
 * daarom binnen de openende transactie aangeroepen en het resultaat wordt daarbuiten gebruikt.
 */
@Component
public class SourceStructureConfigFactory {

    public static final String CODE_FORMAT_UNSUPPORTED = "CONFIG_FORMAT_UNSUPPORTED";
    public static final String CODE_CHARSET_UNKNOWN = "CONFIG_CHARSET_UNKNOWN";
    public static final String CODE_DELIMITER_MISSING = "CONFIG_DELIMITER_MISSING";
    public static final String CODE_DELIMITER_INVALID = "CONFIG_DELIMITER_INVALID";
    public static final String CODE_QUOTE_INVALID = "CONFIG_QUOTE_INVALID";
    public static final String CODE_HEADER_LINE_INVALID = "CONFIG_HEADER_LINE_INVALID";
    public static final String CODE_FIELD_REFERENCE_KIND_INVALID = "CONFIG_FIELD_REFERENCE_KIND_INVALID";
    public static final String CODE_HEADER_REFERENCE_INCONSISTENT = "CONFIG_HEADER_REFERENCE_INCONSISTENT";
    public static final String CODE_IDENTITY_FIELD_MISSING = "CONFIG_IDENTITY_FIELD_MISSING";
    public static final String CODE_DISCOUNT_FIELD_MISSING = "CONFIG_DISCOUNT_FIELD_MISSING";
    public static final String CODE_PRICE_FIELD_MISSING = "CONFIG_PRICE_FIELD_MISSING";
    public static final String CODE_COLUMN_COUNT_INVALID = "CONFIG_COLUMN_COUNT_INVALID";
    public static final String CODE_FIELD_REFERENCE_INVALID = "CONFIG_FIELD_REFERENCE_INVALID";
    public static final String CODE_CANONICALISATION_VERSION_UNSUPPORTED =
            "CONFIG_CANONICALISATION_VERSION_UNSUPPORTED";

    /**
     * De canonicalisatieversies die deze build kent (ontwerp fase 3, par. 3.5, aanname A15).
     * <ul>
     *   <li><b>1</b> — fase 2: de artikelvingerafdruk dekt de omschrijving, de prijsvingerafdruk de
     *       basisprijs en de munt. Dit pad blijft byte-identiek: bestaande bronstaten mogen nooit
     *       onterecht als gewijzigd uit de delta komen.</li>
     *   <li><b>2</b> — fase 3: de artikelvingerafdruk dekt daarnaast élk gemapt catalogusveld,
     *       gesorteerd op doelveldcode, en er komt een aparte referentievingerafdruk bij. Een revisie
     *       met veldmappings <b>moet</b> versie 2 declareren.</li>
     * </ul>
     * Een gewijzigde canonicalisatieregel vereist een nieuwe definitieversie en een bewuste
     * herbaselining (businessanalyse par. 14.23.4); daarom staat het versienummer vooraan in de
     * canonieke tekst en levert versie 2 met zekerheid andere hashes op dan versie 1.
     */
    public static final Set<Integer> SUPPORTED_CANONICALISATION_VERSIONS = Set.of(1, 2);

    /**
     * @throws ScreeningBlockedException bij ontbrekende of tegenstrijdige bronconfiguratie
     */
    public SourceStructureConfig from(ImportDefinitionRevision revision) {
        String format = revision.getStructureFormat();
        if (!"CSV".equals(format)) {
            throw blocked(CODE_FORMAT_UNSUPPORTED, "Source format " + format + " is not supported");
        }

        Charset charset = charset(revision.getStructureCharset());
        char delimiter = delimiter(revision.getStructureDelimiter());
        Character quote = quote(revision.getStructureQuoteChar(), delimiter);

        boolean hasHeader = revision.isStructureHasHeader();
        int headerLineNumber = revision.getStructureHeaderLineNumber();
        if (hasHeader && headerLineNumber < 1) {
            throw blocked(CODE_HEADER_LINE_INVALID,
                    "Header line number must be 1 or higher but is " + headerLineNumber);
        }

        FieldReferenceKind referenceKind = referenceKind(revision.getStructureFieldReferenceKind());
        if (!hasHeader && referenceKind == FieldReferenceKind.HEADER_NAME) {
            throw blocked(CODE_HEADER_REFERENCE_INCONSISTENT,
                    "A source without a header cannot reference fields by header name");
        }

        Integer expectedColumnCount = revision.getStructureExpectedColumnCount();
        if (expectedColumnCount != null && expectedColumnCount < 1) {
            throw blocked(CODE_COLUMN_COUNT_INVALID,
                    "Expected column count must be 1 or higher but is " + expectedColumnCount);
        }

        IdentityProfileKind identityProfileKind = revision.getIdentityProfileKind();
        String supplier = identityField(revision.getIdentitySupplierField(), "identity_supplier_field");
        String group = identityField(revision.getIdentitySupplierGroupField(), "identity_supplier_group_field");
        String reference = identityField(revision.getIdentitySupplierReferenceField(),
                "identity_supplier_reference_field");

        // THREE_PART leest de kortingscodekolom bewust niet, ook niet wanneer ze gevuld zou zijn.
        String discount = null;
        if (identityProfileKind == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE) {
            discount = trimToNull(revision.getIdentityDiscountCodeField());
            if (discount == null) {
                throw blocked(CODE_DISCOUNT_FIELD_MISSING,
                        "Identity profile FOUR_PART_WITH_DISCOUNT_CODE requires identity_discount_code_field");
            }
        }

        String price = trimToNull(revision.getRecordBasePriceField());
        if (price == null) {
            throw blocked(CODE_PRICE_FIELD_MISSING, "No base price field configured on the revision");
        }
        String description = trimToNull(revision.getRecordDescriptionField());
        PricePolicy pricePolicy = pricePolicy(revision);

        int canonicalisationVersion = revision.getRecordCanonicalisationVersion();
        if (!SUPPORTED_CANONICALISATION_VERSIONS.contains(canonicalisationVersion)) {
            throw blocked(CODE_CANONICALISATION_VERSION_UNSUPPORTED,
                    "Canonicalisation version " + canonicalisationVersion + " is not supported by this build; "
                            + "supported versions are " + SUPPORTED_CANONICALISATION_VERSIONS);
        }

        SourceStructureConfig config = new SourceStructureConfig(format, charset, delimiter, quote, hasHeader,
                headerLineNumber, referenceKind, expectedColumnCount, identityProfileKind, supplier, group,
                reference, discount, price, description, canonicalisationVersion, pricePolicy);

        if (referenceKind == FieldReferenceKind.COLUMN_INDEX) {
            for (String field : config.declaredFields()) {
                requireColumnIndex(field, expectedColumnCount);
            }
        }
        return config;
    }

    /**
     * Het prijsbeleid van deze revisie (fase 3, R-PRI-02/R-PRI-03/R-PRI-06/R-PRI-07). De tolerantie is
     * een {@code not null}-kolom met default 0,01; staat er tóch niets, dan geldt de normatieve
     * default in plaats van "geen tolerantie" — dat laatste zou elk reconstructieverschil aanvaarden.
     * Een negatieve tolerantie is een configuratiefout en blokkeert de levering.
     */
    private static PricePolicy pricePolicy(ImportDefinitionRevision revision) {
        BigDecimal tolerance = revision.getPriceDerivationTolerance();
        if (tolerance == null) {
            tolerance = PriceRules.DEFAULT_DERIVATION_TOLERANCE;
        }
        if (tolerance.signum() < 0) {
            throw blocked(CODE_PRICE_FIELD_MISSING, "price_derivation_tolerance is negative ("
                    + tolerance.toPlainString() + "); a tolerance is a distance and is never negative");
        }
        return new PricePolicy(trimToNull(revision.getRecordCurrencyField()),
                revision.isBasePriceZeroAllowed(), revision.isBasePriceNegativeAllowed(), tolerance);
    }

    private static Charset charset(String name) {
        if (name == null || name.isBlank()) {
            throw blocked(CODE_CHARSET_UNKNOWN, "No charset configured; the source encoding is never guessed");
        }
        try {
            return Charset.forName(name.trim());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException unknown) {
            throw blocked(CODE_CHARSET_UNKNOWN, "Unsupported charset " + name);
        }
    }

    private static char delimiter(String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            throw blocked(CODE_DELIMITER_MISSING, "No column delimiter configured on the revision");
        }
        if (delimiter.length() != 1) {
            throw blocked(CODE_DELIMITER_INVALID, "Column delimiter must be exactly one character");
        }
        return delimiter.charAt(0);
    }

    private static Character quote(String quote, char delimiter) {
        if (quote == null || quote.isEmpty()) {
            return null;
        }
        if (quote.length() != 1) {
            throw blocked(CODE_QUOTE_INVALID, "Quote character must be exactly one character");
        }
        if (quote.charAt(0) == delimiter) {
            throw blocked(CODE_QUOTE_INVALID, "Quote character must differ from the column delimiter");
        }
        return quote.charAt(0);
    }

    private static FieldReferenceKind referenceKind(String kind) {
        try {
            return FieldReferenceKind.valueOf(String.valueOf(kind));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw blocked(CODE_FIELD_REFERENCE_KIND_INVALID, "Unknown field reference kind " + kind);
        }
    }

    private static String identityField(String value, String column) {
        String field = trimToNull(value);
        if (field == null) {
            throw blocked(CODE_IDENTITY_FIELD_MISSING, "No source field configured for " + column);
        }
        return field;
    }

    private static void requireColumnIndex(String field, Integer expectedColumnCount) {
        int index;
        try {
            index = Integer.parseInt(field);
        } catch (NumberFormatException notAnIndex) {
            throw blocked(CODE_FIELD_REFERENCE_INVALID,
                    "Field reference " + field + " must be a 1-based column index");
        }
        if (index < 1) {
            throw blocked(CODE_FIELD_REFERENCE_INVALID,
                    "Field reference " + field + " must be a 1-based column index");
        }
        if (expectedColumnCount != null && index > expectedColumnCount) {
            throw blocked(CODE_FIELD_REFERENCE_INVALID,
                    "Field reference " + field + " is beyond the expected column count " + expectedColumnCount);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ScreeningBlockedException blocked(String code, String reason) {
        return new ScreeningBlockedException(code, reason);
    }
}
