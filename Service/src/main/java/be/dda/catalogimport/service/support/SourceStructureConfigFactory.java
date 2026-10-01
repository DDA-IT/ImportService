package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.service.support.ConfigProblemCollector.Outcome;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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
     * De vaste valuta van de koppeling ({@code import_link.default_currency}) heeft geen
     * ISO-4217-vorm. De setup-API (V-4) en de databasecheck van changeset 010-4b/010-4c weren zo'n
     * waarde al; komt ze hier tóch binnen, dan blokkeert de levering in plaats van een verzonnen of
     * stil gecorrigeerde munt op élke prijs van deze koppeling te zetten.
     */
    public static final String CODE_LINK_CURRENCY_INVALID = "CONFIG_LINK_CURRENCY_INVALID";

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

    /** Dezelfde syntactische ISO-4217-vorm als {@code PriceRules}: exact drie hoofdletters. */
    private static final Pattern LINK_DEFAULT_CURRENCY = Pattern.compile("[A-Z]{3}");

    // Revisiekolommen van de bevindingen (NT-14a par. 3): de sleutels van het woordenlijstdomein
    // "revisionField" in de frontend. Enkel in het collect-resultaat; de exception blijft ongewijzigd.
    private static final String REVISION_FIELD_FORMAT = "structureFormat";
    private static final String REVISION_FIELD_CHARSET = "charset";
    private static final String REVISION_FIELD_DELIMITER = "delimiter";
    private static final String REVISION_FIELD_QUOTE = "quoteChar";
    private static final String REVISION_FIELD_HEADER_LINE_NUMBER = "headerLineNumber";
    private static final String REVISION_FIELD_REFERENCE_KIND = "fieldReferenceKind";
    private static final String REVISION_FIELD_EXPECTED_COLUMN_COUNT = "expectedColumnCount";
    private static final String REVISION_FIELD_SUPPLIER = "supplierField";
    private static final String REVISION_FIELD_SUPPLIER_GROUP = "supplierGroupField";
    private static final String REVISION_FIELD_SUPPLIER_REFERENCE = "supplierReferenceField";
    private static final String REVISION_FIELD_DISCOUNT_CODE = "discountCodeField";
    private static final String REVISION_FIELD_BASE_PRICE = "basePriceField";
    private static final String REVISION_FIELD_DESCRIPTION = "descriptionField";
    private static final String REVISION_FIELD_CURRENCY = "currencyField";
    private static final String REVISION_FIELD_TOLERANCE = "priceDerivationTolerance";
    private static final String REVISION_FIELD_CANONICALISATION_VERSION = "canonicalisationVersion";

    /**
     * De bronconfiguratie van een revisie <b>zonder</b> vaste valuta van de koppeling. Gedrag exact als
     * vóór de valuta-standaard: zonder muntveld geldt de systeemstandaard.
     *
     * @throws ScreeningBlockedException bij ontbrekende of tegenstrijdige bronconfiguratie
     */
    public SourceStructureConfig from(ImportDefinitionRevision revision) {
        return from(revision, null);
    }

    /**
     * Dezelfde bronconfiguratie, aangevuld met de vaste valuta van de <b>koppeling</b>
     * ({@code import_link.default_currency}, ontwerp valuta-standaard par. 1 en 2). Additieve overload:
     * enkel de screening van een batch kent een koppeling; de definitievalidatie van de setup- en
     * materialisatie-API's blijft de eenargumentvariant gebruiken.
     *
     * @param linkDefaultCurrency de vaste valuta van de koppeling, of {@code null}
     * @throws ScreeningBlockedException bij ontbrekende of tegenstrijdige bronconfiguratie, of bij een
     *                                   vaste valuta die geen ISO-4217-vorm heeft
     */
    public SourceStructureConfig from(ImportDefinitionRevision revision, String linkDefaultCurrency) {
        return read(revision, linkDefaultCurrency, ConfigProblemCollector.first()).config();
    }

    /**
     * Alle onafhankelijk te beoordelen fouten in de bronconfiguratie tegelijk (NT-14a). Dezelfde regels
     * en dezelfde volgorde als {@link #from(ImportDefinitionRevision, String)}: de eerste bevinding is
     * exact wat {@code from} zou gooien, en zonder worp is de lijst leeg. Gooit zelf nooit een
     * {@link ScreeningBlockedException}.
     *
     * @param linkDefaultCurrency de vaste valuta van de koppeling, of {@code null}
     */
    public ConfigCheckReport collect(ImportDefinitionRevision revision, String linkDefaultCurrency) {
        ConfigProblemCollector problems = ConfigProblemCollector.all();
        collectInto(revision, linkDefaultCurrency, problems);
        return problems.toReport();
    }

    /**
     * Dezelfde controle in een gedeelde collector, zodat de mappingcontrole er in dezelfde lijst achter
     * kan volgen ({@link ImportMappingConfigFactory#collectInto}).
     *
     * @return wat de mappingcontrole van deze bronstructuur moet weten, inclusief welke gegevens
     *         onbetrouwbaar zijn
     */
    public StructureFacts collectInto(ImportDefinitionRevision revision, String linkDefaultCurrency,
                                      ConfigProblemCollector problems) {
        return read(revision, linkDefaultCurrency, problems).facts();
    }

    /** De opgebouwde configuratie (enkel in de stand FIRST) en de feiten voor de mappingcontrole. */
    private record Reading(SourceStructureConfig config, StructureFacts facts) {
    }

    /**
     * De regels zelf, in vaste volgorde (S1-S15). Elke werpplaats is ongewijzigd; ze staat in een tak
     * van de collector. In de stand ALL wordt de {@link SourceStructureConfig} nooit opgebouwd: de
     * recordconstructor gooit bij onvolledige gegevens een {@link IllegalArgumentException}.
     */
    private static Reading read(ImportDefinitionRevision revision, String linkDefaultCurrency,
                                ConfigProblemCollector problems) {
        // S1
        String format = revision.getStructureFormat();
        problems.check(REVISION_FIELD_FORMAT, () -> {
            if (!"CSV".equals(format)) {
                throw blocked(CODE_FORMAT_UNSUPPORTED, "Source format " + format + " is not supported");
            }
        });

        // S2, S3, S4 (de gelijkheid met het scheidingsteken steunt op S3)
        Charset charset = problems.branch(REVISION_FIELD_CHARSET,
                () -> charset(revision.getStructureCharset())).value();
        Outcome<Character> delimiter = problems.branch(REVISION_FIELD_DELIMITER,
                () -> delimiter(revision.getStructureDelimiter()));
        Character quote = problems.branch(REVISION_FIELD_QUOTE,
                () -> quote(revision.getStructureQuoteChar(), delimiter, problems)).value();

        // S5
        boolean hasHeader = revision.isStructureHasHeader();
        int headerLineNumber = revision.getStructureHeaderLineNumber();
        problems.check(REVISION_FIELD_HEADER_LINE_NUMBER, () -> {
            if (hasHeader && headerLineNumber < 1) {
                throw blocked(CODE_HEADER_LINE_INVALID,
                        "Header line number must be 1 or higher but is " + headerLineNumber);
            }
        });

        // S6, S7 (steunt op S6)
        Outcome<FieldReferenceKind> kind = problems.branch(REVISION_FIELD_REFERENCE_KIND,
                () -> referenceKind(revision.getStructureFieldReferenceKind()));
        FieldReferenceKind referenceKind = kind.value();
        if (kind.failed()) {
            problems.skip(kind.failedCode());
        } else {
            problems.check(REVISION_FIELD_REFERENCE_KIND, () -> {
                if (!hasHeader && referenceKind == FieldReferenceKind.HEADER_NAME) {
                    throw blocked(CODE_HEADER_REFERENCE_INCONSISTENT,
                            "A source without a header cannot reference fields by header name");
                }
            });
        }

        // S8
        Integer expectedColumnCount = revision.getStructureExpectedColumnCount();
        Outcome<Void> columnCount = problems.check(REVISION_FIELD_EXPECTED_COLUMN_COUNT, () -> {
            if (expectedColumnCount != null && expectedColumnCount < 1) {
                throw blocked(CODE_COLUMN_COUNT_INVALID,
                        "Expected column count must be 1 or higher but is " + expectedColumnCount);
            }
        });

        // S9: elke identiteitskolom apart
        IdentityProfileKind identityProfileKind = revision.getIdentityProfileKind();
        Outcome<String> supplier = problems.branch(REVISION_FIELD_SUPPLIER,
                () -> identityField(revision.getIdentitySupplierField(), "identity_supplier_field"));
        Outcome<String> group = problems.branch(REVISION_FIELD_SUPPLIER_GROUP,
                () -> identityField(revision.getIdentitySupplierGroupField(), "identity_supplier_group_field"));
        Outcome<String> reference = problems.branch(REVISION_FIELD_SUPPLIER_REFERENCE,
                () -> identityField(revision.getIdentitySupplierReferenceField(),
                        "identity_supplier_reference_field"));

        // S10. THREE_PART leest de kortingscodekolom bewust niet, ook niet wanneer ze gevuld zou zijn.
        Outcome<String> discount = Outcome.passed(null);
        if (identityProfileKind == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE) {
            discount = problems.branch(REVISION_FIELD_DISCOUNT_CODE, () -> {
                String field = trimToNull(revision.getIdentityDiscountCodeField());
                if (field == null) {
                    throw blocked(CODE_DISCOUNT_FIELD_MISSING,
                            "Identity profile FOUR_PART_WITH_DISCOUNT_CODE requires identity_discount_code_field");
                }
                return field;
            });
        }

        // S11
        Outcome<String> price = problems.branch(REVISION_FIELD_BASE_PRICE, () -> {
            String field = trimToNull(revision.getRecordBasePriceField());
            if (field == null) {
                throw blocked(CODE_PRICE_FIELD_MISSING, "No base price field configured on the revision");
            }
            return field;
        });
        String description = trimToNull(revision.getRecordDescriptionField());

        // S12, S13: het prijsbeleid (twee onafhankelijke takken)
        BigDecimal tolerance = problems.branch(REVISION_FIELD_TOLERANCE,
                () -> derivationTolerance(revision)).value();
        String currency = trimToNull(revision.getRecordCurrencyField());
        // De vaste valuta hoort bij de koppeling, niet bij de revisie: geen revisiekolom.
        String linkCurrency = problems.branch(null, () -> linkDefaultCurrency(linkDefaultCurrency)).value();
        PricePolicy pricePolicy = problems.collectsAll() ? null
                : new PricePolicy(currency, revision.isBasePriceZeroAllowed(),
                        revision.isBasePriceNegativeAllowed(), tolerance, linkCurrency);

        // S14
        int canonicalisationVersion = revision.getRecordCanonicalisationVersion();
        Outcome<Void> canonicalisation = problems.check(REVISION_FIELD_CANONICALISATION_VERSION, () -> {
            if (!SUPPORTED_CANONICALISATION_VERSIONS.contains(canonicalisationVersion)) {
                throw blocked(CODE_CANONICALISATION_VERSION_UNSUPPORTED,
                        "Canonicalisation version " + canonicalisationVersion + " is not supported by this build; "
                                + "supported versions are " + SUPPORTED_CANONICALISATION_VERSIONS);
            }
        });

        SourceStructureConfig config = null;
        if (!problems.collectsAll()) {
            config = new SourceStructureConfig(format, charset, delimiter.value(), quote, hasHeader,
                    headerLineNumber, referenceKind, expectedColumnCount, identityProfileKind, supplier.value(),
                    group.value(), reference.value(), discount.value(), price.value(), description,
                    canonicalisationVersion, pricePolicy);
        }

        // S15: steunt op S6; de bovengrens steunt op S8. Elke gedeclareerde kolom apart, in de volgorde
        // van SourceStructureConfig#declaredFields().
        if (kind.failed()) {
            problems.skip(kind.failedCode());
        } else if (referenceKind == FieldReferenceKind.COLUMN_INDEX) {
            if (columnCount.failed()) {
                problems.skip(columnCount.failedCode());
            }
            Integer upperBound = columnCount.failed() ? null : expectedColumnCount;
            for (DeclaredField field : declaredFields(identityProfileKind, supplier, group, reference, discount,
                    price, description, currency)) {
                if (field.reference().failed()) {
                    problems.skip(field.reference().failedCode());
                    continue;
                }
                problems.check(field.revisionField(),
                        () -> requireColumnIndex(field.reference().value(), upperBound));
            }
        }

        StructureFacts facts = new StructureFacts(referenceKind, expectedColumnCount, !columnCount.failed(),
                !supplier.failed() && !group.failed() && !reference.failed(), !canonicalisation.failed());
        return new Reading(config, facts);
    }

    /** Eén gedeclareerde bronkolom met de revisiekolom waaruit ze komt. */
    private record DeclaredField(String revisionField, Outcome<String> reference) {
    }

    /**
     * Dezelfde verzameling en volgorde als {@link SourceStructureConfig#declaredFields()}, maar zonder
     * het record op te bouwen: in de stand ALL kan de structuur fouten bevatten. Een verplichte kolom die
     * zelf faalde, blijft erin (als mislukte uitkomst) zodat haar indexcontrole als overgeslagen gemeld
     * wordt.
     */
    private static List<DeclaredField> declaredFields(IdentityProfileKind identityProfileKind,
                                                      Outcome<String> supplier, Outcome<String> group,
                                                      Outcome<String> reference, Outcome<String> discount,
                                                      Outcome<String> price, String description,
                                                      String currency) {
        List<DeclaredField> fields = new ArrayList<>(7);
        fields.add(new DeclaredField(REVISION_FIELD_SUPPLIER, supplier));
        fields.add(new DeclaredField(REVISION_FIELD_SUPPLIER_GROUP, group));
        fields.add(new DeclaredField(REVISION_FIELD_SUPPLIER_REFERENCE, reference));
        if (identityProfileKind == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE
                && (discount.failed() || discount.value() != null)) {
            fields.add(new DeclaredField(REVISION_FIELD_DISCOUNT_CODE, discount));
        }
        fields.add(new DeclaredField(REVISION_FIELD_BASE_PRICE, price));
        if (description != null) {
            fields.add(new DeclaredField(REVISION_FIELD_DESCRIPTION, Outcome.passed(description)));
        }
        if (currency != null) {
            fields.add(new DeclaredField(REVISION_FIELD_CURRENCY, Outcome.passed(currency)));
        }
        return fields;
    }

    /**
     * De afleidingstolerantie van het prijsbeleid van deze revisie (fase 3, R-PRI-02/R-PRI-03/
     * R-PRI-06/R-PRI-07). De tolerantie is een {@code not null}-kolom met default 0,01; staat er tóch
     * niets, dan geldt de normatieve default in plaats van "geen tolerantie" — dat laatste zou elk
     * reconstructieverschil aanvaarden. Een negatieve tolerantie is een configuratiefout en blokkeert
     * de levering.
     */
    private static BigDecimal derivationTolerance(ImportDefinitionRevision revision) {
        BigDecimal tolerance = revision.getPriceDerivationTolerance();
        if (tolerance == null) {
            tolerance = PriceRules.DEFAULT_DERIVATION_TOLERANCE;
        }
        if (tolerance.signum() < 0) {
            throw blocked(CODE_PRICE_FIELD_MISSING, "price_derivation_tolerance is negative ("
                    + tolerance.toPlainString() + "); a tolerance is a distance and is never negative");
        }
        return tolerance;
    }

    /**
     * De vaste valuta van de koppeling, defensief gecontroleerd op de ISO-4217-<b>vorm</b> — dezelfde
     * regel als {@link PriceRules#currency(String, String)} en als de databasecheck van changeset
     * 010-4b/010-4c. Nooit upper-casen en nooit terugvallen op de systeemstandaard: een onleesbare
     * vaste valuta zou anders stil als EUR op élke prijs van deze koppeling belanden. De eigenlijke
     * invoervalidatie gebeurt bij het aanmaken van de koppeling (bouwstap V-4); dit is het vangnet.
     */
    private static String linkDefaultCurrency(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (!LINK_DEFAULT_CURRENCY.matcher(trimmed).matches()) {
            throw blocked(CODE_LINK_CURRENCY_INVALID, "import_link.default_currency '" + value
                    + "' is not an ISO 4217 currency code (exactly three capital letters); a fixed "
                    + "currency is never guessed, corrected or replaced by the system default");
        }
        return trimmed;
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

    /**
     * S4. De lengte wordt altijd beoordeeld; de gelijkheid met het scheidingsteken enkel wanneer dat
     * scheidingsteken zelf geldig is (S3) — anders wordt ze als overgeslagen gemeld.
     */
    private static Character quote(String quote, Outcome<Character> delimiter, ConfigProblemCollector problems) {
        if (quote == null || quote.isEmpty()) {
            return null;
        }
        if (quote.length() != 1) {
            throw blocked(CODE_QUOTE_INVALID, "Quote character must be exactly one character");
        }
        if (delimiter.failed()) {
            problems.skip(delimiter.failedCode());
            return quote.charAt(0);
        }
        if (quote.charAt(0) == delimiter.value()) {
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
