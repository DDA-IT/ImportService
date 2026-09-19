package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.DiscountCodeState;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.RevisionOwnedField;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.FieldValueMapper.MappedRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

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
 *   <li>Een basisprijs van 0 of een negatieve basisprijs verwerpt de regel, tenzij de revisie ze
 *       uitdrukkelijk toelaat ({@code base_price_zero_allowed} / {@code base_price_negative_allowed},
 *       R-PRI-02/R-PRI-03). Dat is een <b>wijziging t.o.v. fase 2</b>, waarin een basisprijs 0
 *       gewoon gestaged werd.</li>
 *   <li>Elke gemapte prijscomponent krijgt haar verhouding tot de basisprijs
 *       ({@link PriceRules}); loopt dat mis, dan wordt <b>enkel die regel</b> verworpen en nooit een
 *       percentage geraden of een prijs stil gecorrigeerd.</li>
 * </ul>
 * <b>Vingerafdrukken.</b> Ze hangen af van de canonicalisatieversie die de revisie declareert
 * (ontwerp fase 3, par. 3.5); het versienummer staat vooraan in elke canonieke tekst, zodat twee
 * versies met zekerheid andere hashes opleveren.
 * <ul>
 *   <li><b>Versie 1</b> (fase 2, ongewijzigd): {@code article_fingerprint} dekt de omschrijving,
 *       {@code price_fingerprint} de basisprijs en de munt, {@code combined_fingerprint} is de
 *       SHA-256 over de aaneenschakeling van identiteit, artikel en prijs. Dit pad blijft
 *       <b>byte-identiek</b>: een bestaande bronstaat mag nooit onterecht als gewijzigd uit de delta
 *       komen.</li>
 *   <li><b>Versie 2</b> (fase 3): {@code article_fingerprint} dekt daarnaast élk gemapt catalogusveld
 *       — gesorteerd op doelveldcode, met de doelveldcode zelf in de canonieke tekst, zodat twee
 *       velden nooit van plaats kunnen wisselen zonder dat de hash verandert. Er komt een
 *       {@code reference_fingerprint} bij en {@code combined_fingerprint} is de SHA-256 over
 *       identiteit ‖ artikel ‖ prijs ‖ referenties.</li>
 * </ul>
 * <b>Prijs en referenties onder versie 2.</b> De prijsvingerafdruk dekt de basisprijs, de munt en élke
 * gemapte prijscomponent met haar verhouding. Zonder {@code record_currency_field} blijft
 * {@code basePriceCurrency} {@code null} ("onbekend"); er wordt nooit stilzwijgend EUR verondersteld.
 * De referentievingerafdruk dekt élke gemapte kritieke koppelreferentie met haar <b>genormaliseerde</b>
 * waarde, gesorteerd op referentietype (bouwstap 3f, R-REF-01). Een revisie zónder prijscomponenten
 * en zónder referentiemappings houdt exact de vingerafdrukken van bouwstap 3c — byte voor byte.
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
    /**
     * {@code import_candidate_reference.value_raw}/{@code value_normalised} en
     * {@code catalog_reference_state} zijn varchar(200). Een langere referentie wordt <b>verworpen</b>
     * en nooit afgekapt: een afgekapte EAN zou een andere aanbieding kunnen aanwijzen.
     */
    public static final int MAX_REFERENCE_LENGTH = 200;
    /** {@code base_price} is numeric(24,6): hoogstens 18 cijfers vóór de komma. */
    public static final int MAX_PRICE_INTEGER_DIGITS = 18;

    /**
     * De canonicalisatieversie waarvan de artikelvingerafdruk élk gemapt veld dekt en waarin een
     * aparte referentievingerafdruk bestaat (ontwerp fase 3, par. 3.5).
     */
    public static final int CANONICALISATION_VERSION_WITH_FIELDS =
            ImportMappingConfigFactory.CANONICALISATION_VERSION_WITH_COMPONENTS;

    /** De doelveldcode van de omschrijving; die wordt door de revisiekolom bepaald (R-STR-06). */
    private static final String DESCRIPTION_FIELD_CODE = RevisionOwnedField.DESCRIPTION.name();

    private final FieldValueMapper mapper = new FieldValueMapper();

    /** Resultaat van één regel: ofwel een kandidaat, ofwel precies één verwerpingsreden. */
    public sealed interface Result permits NormalisedCandidate, RowIssue {
    }

    /**
     * Eén gemapte kritieke koppelreferentie van één bronregel (R-REF-01). De ruwe waarde blijft naast
     * de genormaliseerde bewaard; {@code valueNormalised} is {@code null} wanneer het veld gemapt maar
     * leeg is — dat is een uitspraak van de leverancier (R-REF-03) en iets anders dan een niet-gemapt
     * veld, dat helemaal geen {@code ReferenceValue} oplevert.
     */
    public record ReferenceValue(String referenceType, String valueRaw, String valueNormalised) {

        /** Gemapt maar leeg: de leverancier laat deze referentie weg. */
        public boolean isEmpty() {
            return valueNormalised == null;
        }
    }

    /**
     * Een gevalideerde kandidaat, klaar om gestaged te worden. Hashes zijn binair (32 bytes).
     *
     * @param references           de gemapte kritieke koppelreferenties, gesorteerd op referentietype;
     *                             leeg wanneer de revisie er geen mapt — dan wordt er geen enkele
     *                             {@code import_candidate_reference}-rij geschreven en blijft het
     *                             gedrag exact dat van bouwstap 3c/3d
     * @param referenceFingerprint de deelvingerafdruk over de kritieke referenties; {@code null} bij
     *                             canonicalisatieversie 1, die geen referentiedeel kent
     * @param priceComponents      de rijen voor {@code import_candidate_price}: de basisprijs zelf en
     *                             elke gemapte prijscomponent met haar verhouding (R-PRI-04). Leeg
     *                             wanneer de revisie geen enkele prijscomponent mapt — dan verandert er
     *                             niets aan het fase 2/3c-gedrag en wordt er geen enkele prijsrij
     *                             geschreven
     * @param notices              informatieve vaststellingen over deze regel (vandaag: toegepaste
     *                             standaardwaarden). Ze verwerpen de regel niet en tellen dus niet in
     *                             {@code rejected_record_count}, maar ze moeten wél zichtbaar zijn:
     *                             een ingevulde default is een afwijking van wat de leverancier stuurde
     */
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
            byte[] referenceFingerprint,
            byte[] combinedFingerprint,
            List<PriceRules.PriceComponent> priceComponents,
            List<ReferenceValue> references,
            List<RowIssue> notices) implements Result {

        public NormalisedCandidate {
            priceComponents = List.copyOf(priceComponents);
            references = List.copyOf(references);
            notices = List.copyOf(notices);
        }

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
     * Normaliseert één regel zonder veldmapping; exact het fase 2-gedrag. Uitsluitend voor revisies
     * die geen enkele mapping hebben — die draaien altijd op canonicalisatieversie 1.
     *
     * @throws ScreeningBlockedException wanneer de configuratie zelf niet op dit bestand past; dat
     *                                   blokkeert de levering in plaats van elke regel afzonderlijk
     *                                   te verwerpen
     */
    public Result normalise(ParsedRow row, SourceStructureConfig config) {
        return normalise(row, config, null);
    }

    /**
     * Normaliseert één regel, inclusief de gemapte doelvelden van de revisie (ontwerp fase 3,
     * par. 3.1 stap C).
     * <p>
     * Een fout in een gemapt veld verwerpt <b>enkel deze regel</b>, net als een onleesbare prijs: de
     * levering loopt door en de regel telt in {@code rejected_record_count}. Een fout in de definitie
     * zelf blokkeert de volledige levering.
     *
     * @param mappingConfig de gevalideerde veldmapping, of {@code null} wanneer er geen is
     * @throws ScreeningBlockedException wanneer de configuratie zelf niet op dit bestand past
     */
    public Result normalise(ParsedRow row, SourceStructureConfig config,
                            ImportMappingConfig mappingConfig) {
        try {
            // Ontwerp par. 3.1 stap C: mapping en transformatie draaien ná het recordfilter en vóór de
            // identiteit. Zo wordt een regel die de definitie niet kan invullen, verworpen vóórdat er
            // een aanbiedingsidentiteit uit afgeleid wordt.
            MappedRecord mapped = mapper.map(row, mappingConfig);

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
            // R-PRI-02/R-PRI-03: 0 en negatief zijn geleverde, betekenisvolle waarden en worden enkel
            // doorgelaten wanneer de revisie ze uitdrukkelijk toelaat.
            basePrice = PriceRules.basePrice(basePrice, priceField, rawPrice, config.pricePolicy());
            // Geen muntveld ⇒ munt onbekend (null). Nooit stil EUR veronderstellen (aanname A22).
            String currency = null;
            if (config.currencyField() != null) {
                String rawCurrency = row.value(position(row, config.currencyField()));
                currency = PriceRules.currency(rawCurrency, config.currencyField());
            }
            List<PriceRules.PriceComponent> priceComponents =
                    priceComponents(row, basePrice, currency, mapped, mappingConfig, config);
            List<ReferenceValue> references = references(row, mapped, mappingConfig);

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
                    version == CANONICALISATION_VERSION_WITH_FIELDS
                            ? canonicalArticle(description, mapped, mappingConfig)
                            : ImportValueRules.canonical(version, description));
            byte[] priceFingerprint = ImportValueRules.sha256Utf8(
                    canonicalPrice(version, basePrice, currency, priceComponents));
            byte[] referenceFingerprint = version == CANONICALISATION_VERSION_WITH_FIELDS
                    ? ImportValueRules.sha256Utf8(canonicalReferences(references))
                    : null;
            byte[] combinedFingerprint = referenceFingerprint == null
                    ? ImportValueRules.sha256(concat(identityHash, articleFingerprint, priceFingerprint))
                    : ImportValueRules.sha256(concat(identityHash, articleFingerprint, priceFingerprint,
                            referenceFingerprint));

            return new NormalisedCandidate(row.lineNumber(), supplier, group, reference, discountCode,
                    discountState, identityHash, basePrice, currency, description, articleFingerprint,
                    priceFingerprint, referenceFingerprint, combinedFingerprint, priceComponents,
                    references, notices(row, mapped));
        } catch (ImportValueException rejected) {
            return new RowIssue(row.lineNumber(), rejected.getCode(), rejected.getField(),
                    rejected.getRawValue(), rejected.getMessage());
        }
    }

    /**
     * De canonieke artikeltekst van versie 2 (ontwerp par. 3.5): de omschrijving en élk gemapt
     * catalogusveld, gesorteerd op doelveldcode. Elk onderdeel bestaat uit de <b>doelveldcode</b> en
     * de waarde, zodat twee velden nooit stilzwijgend van plaats kunnen wisselen en een later
     * toegevoegd veld de hash aantoonbaar wijzigt. Een veld zonder waarde levert de
     * "niet gemapt"-markering ({@code U+0000}) op en blijft daardoor verschillend van een gemapt maar
     * leeg veld.
     */
    private static String canonicalArticle(String description, MappedRecord mapped,
                                           ImportMappingConfig mappingConfig) {
        List<String> parts = new ArrayList<>();
        parts.add(DESCRIPTION_FIELD_CODE);
        parts.add(description);
        if (mappingConfig != null) {
            for (ImportMappingConfig.FieldMapping field : mappingConfig.articleFingerprintFields()) {
                if (DESCRIPTION_FIELD_CODE.equals(field.targetFieldCode())) {
                    // De revisiekolom en een mapping kunnen nooit samen de omschrijving bepalen
                    // (R-STR-06); de gemapte waarde vervangt dan de (lege) revisiewaarde.
                    parts.set(1, mapped.value(field.targetFieldCode()));
                    continue;
                }
                parts.add(field.targetFieldCode());
                parts.add(mapped.value(field.targetFieldCode()));
            }
        }
        return ImportValueRules.canonical(CANONICALISATION_VERSION_WITH_FIELDS,
                parts.toArray(String[]::new));
    }

    /**
     * De prijscomponenten van deze bronregel (R-PRI-04..R-PRI-08). Uitsluitend de mappings met een
     * {@code price_component_code}; de basisprijs zelf komt uit de revisiekolom en krijgt in
     * {@link PriceRules#components} haar eigen rij.
     * <p>
     * <b>Een revisie zonder prijscomponenten levert een lege lijst</b> — dan wordt er geen enkele
     * {@code import_candidate_price}-rij geschreven en blijft de prijsvingerafdruk exact die van
     * bouwstap 3c. Dat is bewust: een aparte basisprijsrij per regel zou voor elke bestaande levering
     * een miljoen rijen toevoegen zonder iets te bewijzen, want {@code import_candidate_stage.base_price}
     * blijft de basisprijs dragen.
     * <p>
     * <b>Een component zonder waarde levert geen rij.</b> Ontbrekend ({@code null}) en leeg
     * ({@code ""}) betekenen dat de leverancier deze component niet meegaf; dat is iets anders dan 0 en
     * wordt dus nooit als 0 bewaard. Wie een component verplicht wil maken, zet {@code required} op de
     * mapping — dan verwerpt {@link FieldValueMapper} de regel al met {@code VALUE_MISSING}.
     */
    private static List<PriceRules.PriceComponent> priceComponents(ParsedRow row, BigDecimal basePrice,
                                                                   String currency, MappedRecord mapped,
                                                                   ImportMappingConfig mappingConfig,
                                                                   SourceStructureConfig config) {
        if (mappingConfig == null || !mappingConfig.hasPriceComponents()) {
            return List.of();
        }
        List<PriceRules.ComponentInput> inputs = new ArrayList<>();
        for (ImportMappingConfig.FieldMapping field : mappingConfig.priceComponentFields()) {
            String value = mapped.value(field.targetFieldCode());
            if (value == null || value.isEmpty()) {
                continue;
            }
            inputs.add(new PriceRules.ComponentInput(field.priceComponentCode(), field.targetFieldName(),
                    sourceValue(row, field, value), new BigDecimal(value), null, field.zeroAllowed(),
                    field.negativeAllowed(), field.maxPercentage()));
        }
        List<PriceRules.PriceComponent> components =
                PriceRules.components(basePrice, currency, inputs, config.pricePolicy());
        // R-PRI-05: zonder bruikbare basisprijs bestaat er geen verhouding; die regel wordt verworpen
        // in plaats van met een verzonnen percentage of een stille 0 gestaged te worden.
        PriceRules.requireComputable(components, config.basePriceField());
        return components;
    }

    /**
     * De gemapte kritieke koppelreferenties van deze bronregel (R-REF-01/R-REF-03), gesorteerd op
     * referentietype.
     * <p>
     * <b>Drie toestanden die nooit door elkaar mogen lopen.</b>
     * <ul>
     *   <li><b>niet gemapt</b> — er komt geen enkel element in de lijst en dus geen rij in
     *       {@code import_candidate_reference}. Over dat referentietype doet deze levering geen
     *       uitspraak, en de referentiecontrole beoordeelt het niet;</li>
     *   <li><b>gemapt maar leeg</b> — een element met {@code valueNormalised = null}. Dat is wél een
     *       uitspraak: de leverancier laat de referentie weg, wat voor een bestaande aanbieding een
     *       kritiek incident oplevert (R-REF-03);</li>
     *   <li><b>gemapt met waarde</b> — de ruwe bronwaarde én haar vergelijkingswaarde.</li>
     * </ul>
     * <b>Een revisie zonder referentiemappings levert een lege lijst</b> — dan is de
     * referentievingerafdruk byte-identiek aan die van bouwstap 3c.
     */
    private static List<ReferenceValue> references(ParsedRow row, MappedRecord mapped,
                                                   ImportMappingConfig mappingConfig) {
        if (mappingConfig == null || !mappingConfig.hasReferences()) {
            return List.of();
        }
        List<ReferenceValue> references = new ArrayList<>();
        for (ImportMappingConfig.FieldMapping field : mappingConfig.referenceFields()) {
            String mappedValue = mapped.value(field.targetFieldCode());
            String raw = mappedValue == null ? null : sourceValue(row, field, mappedValue);
            // Genormaliseerd wordt op de GEMAPTE waarde (na transformatie), niet op de ruwe kolom:
            // anders zou een transformatie de vergelijkingswaarde niet bereiken.
            String normalised = ReferenceNormaliser.normalise(mappedValue);
            requireLength(raw, field.targetFieldName(), MAX_REFERENCE_LENGTH);
            requireLength(normalised, field.targetFieldName(), MAX_REFERENCE_LENGTH);
            references.add(new ReferenceValue(field.referenceType(), raw, normalised));
        }
        return references;
    }

    /**
     * De canonieke referentietekst van versie 2 (ontwerp par. 3.5): per gemapte referentie het
     * <b>referentietype</b> en de genormaliseerde waarde, gesorteerd op referentietype.
     * <p>
     * Een gemapt maar leeg veld krijgt in plaats van een waarde de "niet gemapt"-markering
     * ({@code U+0000}, zie {@link ImportValueRules#canonical}). Dat is een eigen marker die geen
     * enkele echte waarde kan aannemen: een genormaliseerde waarde is nooit leeg en nooit
     * {@code null}. Zo blijven "gemapt maar leeg" en "gemapt met waarde" aantoonbaar verschillend,
     * terwijl "niet gemapt" helemaal geen onderdeel bijdraagt.
     * <p>
     * <b>Een lege lijst levert exact {@code canonical(2)}</b> — precies de tekst van bouwstap 3c. Een
     * revisie zonder referentiemappings houdt haar vingerafdruk dus byte voor byte, en een bestaande
     * bronstaat komt nooit onterecht als gewijzigd uit de delta.
     */
    private static String canonicalReferences(List<ReferenceValue> references) {
        if (references.isEmpty()) {
            return ImportValueRules.canonical(CANONICALISATION_VERSION_WITH_FIELDS);
        }
        List<String> parts = new ArrayList<>(references.size() * 2);
        for (ReferenceValue reference : references) {
            parts.add(reference.referenceType());
            parts.add(reference.valueNormalised());
        }
        return ImportValueRules.canonical(CANONICALISATION_VERSION_WITH_FIELDS,
                parts.toArray(String[]::new));
    }

    /** De ruwe bronwaarde voor de melding; valt terug op de gemapte waarde bij een afgeleid veld. */
    private static String sourceValue(ParsedRow row, ImportMappingConfig.FieldMapping field,
                                      String mappedValue) {
        if (field.sourceReference() == null) {
            return mappedValue;
        }
        Integer position = row.positions().position(field.sourceReference());
        if (position == null) {
            return mappedValue;
        }
        String raw = row.value(position);
        return raw == null ? mappedValue : raw;
    }

    /**
     * De canonieke prijstekst. Versie 1 (fase 2, byte-identiek): de basisprijs en de munt. Versie 2
     * (ontwerp par. 3.5): daarnaast elke afgeleide component als {@code componentcode, percentage},
     * gesorteerd op componentcode, op schaal 12.
     * <p>
     * De basisprijsrij zelf staat <b>niet</b> in de lijst — de basisprijs is al het eerste onderdeel.
     * Een revisie zonder componenten levert daardoor exact dezelfde tekst (en dus dezelfde hash) als
     * bouwstap 3c: een bestaande bronstaat komt nooit onterecht als gewijzigd uit de delta.
     */
    private static String canonicalPrice(int version, BigDecimal basePrice, String currency,
                                         List<PriceRules.PriceComponent> components) {
        String amount = basePrice.setScale(PriceRules.AMOUNT_SCALE, RoundingMode.UNNECESSARY)
                .toPlainString();
        if (version != CANONICALISATION_VERSION_WITH_FIELDS || components.isEmpty()) {
            return ImportValueRules.canonical(version, amount, currency);
        }
        List<String> parts = new ArrayList<>();
        parts.add(amount);
        parts.add(currency);
        for (PriceRules.PriceComponent component : components) {
            if (component.isBase()) {
                continue;
            }
            parts.add(component.componentCode());
            // Een component zonder verhouding (NO_BASE_PRICE) bereikt de staging niet; mocht een latere
            // bouwstap haar wél bewaren, dan is "geen percentage" hier U+0000 en nooit 0.
            parts.add(component.percentage() == null ? null
                    : component.percentage().setScale(PriceRules.PERCENTAGE_SCALE,
                            RoundingMode.UNNECESSARY).toPlainString());
        }
        return ImportValueRules.canonical(version, parts.toArray(String[]::new));
    }

    /** Informatieve vaststellingen van de mapping, als regelproblemen met ernst INFO. */
    private static List<RowIssue> notices(ParsedRow row, MappedRecord mapped) {
        if (mapped.notices().isEmpty()) {
            return List.of();
        }
        List<RowIssue> notices = new ArrayList<>(mapped.notices().size());
        for (FieldValueMapper.Notice notice : mapped.notices()) {
            notices.add(new RowIssue(notice.rowNumber(), notice.code(), notice.fieldName(),
                    notice.sourceValue(), notice.message()));
        }
        return notices;
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
