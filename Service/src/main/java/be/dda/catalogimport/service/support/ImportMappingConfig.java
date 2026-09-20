package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.FieldDataType;
import be.dda.catalogimport.domain.FieldOwner;
import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityClass;
import be.dda.catalogimport.domain.MissingColumnBehaviour;
import be.dda.catalogimport.service.support.HeaderExpectations.ExpectedField;
import be.dda.catalogimport.service.support.ImportValueRules.DecimalFormat;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * De gevalideerde veldmapping en recordfilters van één bevroren {@code ImportDefinitionRevision}
 * (ontwerp fase 3, par. 3.1 stap B').
 * <p>
 * Net als {@link SourceStructureConfig} is dit een <b>momentopname</b>, geen JPA-entiteit: de parser,
 * de filterevaluatie en de normalisatie draaien buiten elke transactie en mogen nooit een lazy
 * associatie aanraken. Ze wordt exact één keer per batch gebouwd, vóór er één byte gelezen is; er is
 * dus geen enkele query per bronregel.
 * <p>
 * <b>Stand sinds bouwstap 3f.</b> Doelvelden (3c), afgeleide prijscomponenten (3d) én kritieke
 * koppelreferenties (3f) worden gelezen, gevalideerd én toegepast; ze zitten in de artikel-, prijs-
 * respectievelijk referentievingerafdruk van canonicalisatieversie 2. Een revisie die één van die
 * velden mapt en toch canonicalisatieversie 1 declareert, wordt geblokkeerd met
 * {@code CONFIG_CANONICALISATION_VERSION_REQUIRED}: zo kan er nooit een levering verwerkt worden
 * waarvan de vingerafdruk de gemapte velden niet dekt.
 *
 * <b>Kritiek-vlag per kolom (bouwstap 3h-1).</b> De configuratie draagt ook, per veldnaam, of een fout
 * op die kolom kritiek is ({@link #criticalityOf(String)}). De vlag wordt hier enkel geladen en
 * bevraagbaar gemaakt; er wordt in deze bouwstap nergens mee geteld of beslist.
 *
 * @param canonicalisationVersion de versie die de revisie declareert; bepaalt welke velden in de
 *                                vingerafdrukken meetellen
 * @param criticalities           per veldnaam de kritiek-vlag: de bronreferenties van de revisie-eigen
 *                                velden en de logische veldnaam van elke mapping, samengevoegd volgens
 *                                {@link #criticalityMap(Map, List)}
 */
public record ImportMappingConfig(int canonicalisationVersion, List<FieldMapping> fields,
                                  List<RecordFilter> filters, Map<String, Criticality> criticalities) {

    /**
     * Eén gevalideerde doelveldmapping.
     *
     * @param targetFieldName    de logische veldnaam uit de catalogus; dit is de naam die in
     *                           meldingen getoond wordt (meldingsstijl par. 15.12)
     * @param sourceReference    headernaam of 1-gebaseerde kolomindex; {@code null} bij een vaste waarde
     * @param expectedPosition   1-gebaseerde positie voor de headercontrole, of {@code null}
     * @param bookmarkName       de sjabloonwaarde die dit veld vult; nog niet invulbaar (3b/3c blokkeren)
     * @param defaultValue       enkel toe te passen bij een werkelijk ontbrekende waarde (R-REC-03)
     * @param priceComponentCode gevuld voor een prijscomponent; eigenaar is dan {@code PRICE_CONTROL}
     * @param referenceType      gevuld voor een kritieke referentie; eigenaar is dan
     *                           {@code CRITICAL_REFERENCE} en niet wisselbaar
     * @param maxPercentage      de uitdrukkelijk geconfigureerde semantische bovengrens van deze
     *                           prijscomponent ({@code maxPercentage=} in {@code transform_config}), of
     *                           {@code null}. Er is bewust géén algemene kunstmatige bovengrens
     *                           (R-PRI-08): een verhouding van 900% kan legitiem zijn
     * @param criticality        is een fout op deze kolom kritiek (par. 15.1)? Wordt nog nergens
     *                           gebruikt om te tellen of te beslissen (bouwstap 3h-1)
     */
    public record FieldMapping(int sequenceNumber, String targetFieldCode, String targetFieldName,
                               FieldValueKind valueKind, String sourceReference, Integer expectedPosition,
                               String fixedValue, String bookmarkName, String defaultValue,
                               FieldDataType dataType,
                               boolean required, Integer maxLength, Integer decimalScale,
                               boolean zeroAllowed, boolean negativeAllowed,
                               FieldTransformKind transformKind, String transformConfig,
                               FieldTransform transform, ValueFormat valueFormat,
                               FieldOwner fieldOwner, IdentityClass identityClass,
                               String priceComponentCode, String referenceType,
                               BigDecimal maxPercentage, Criticality criticality) {

        /**
         * Ontbreekt de vlag, dan geldt de standaard van de soort (referentie- en prijscomponentmapping
         * kritiek, al het andere niet), exact zoals de backfill van changeset 004-2b.
         */
        public FieldMapping {
            if (criticality == null) {
                criticality = priceComponentCode != null || referenceType != null
                        ? Criticality.CRITICAL : Criticality.NON_CRITICAL;
            }
        }

        /**
         * De mapping zonder uitdrukkelijke kritiek-vlag (de vorm van vóór bouwstap 3h-1): de standaard
         * van de soort geldt.
         */
        public FieldMapping(int sequenceNumber, String targetFieldCode, String targetFieldName,
                            FieldValueKind valueKind, String sourceReference, Integer expectedPosition,
                            String fixedValue, String bookmarkName, String defaultValue,
                            FieldDataType dataType,
                            boolean required, Integer maxLength, Integer decimalScale,
                            boolean zeroAllowed, boolean negativeAllowed,
                            FieldTransformKind transformKind, String transformConfig,
                            FieldTransform transform, ValueFormat valueFormat,
                            FieldOwner fieldOwner, IdentityClass identityClass,
                            String priceComponentCode, String referenceType,
                            BigDecimal maxPercentage) {
            this(sequenceNumber, targetFieldCode, targetFieldName, valueKind, sourceReference,
                    expectedPosition, fixedValue, bookmarkName, defaultValue, dataType, required, maxLength,
                    decimalScale, zeroAllowed, negativeAllowed, transformKind, transformConfig, transform,
                    valueFormat, fieldOwner, identityClass, priceComponentCode, referenceType,
                    maxPercentage, null);
        }

        /** Een veld dat de identiteit, de prijs of een kritieke referentie draagt. */
        public boolean isSemanticallyCritical() {
            return priceComponentCode != null || referenceType != null
                    || identityClass == IdentityClass.STRONG
                    || identityClass == IdentityClass.ARTICLE_REFERENCE;
        }

        /** Een veld dat een afgeleide prijscomponent draagt (eigenaar {@code PRICE_CONTROL}). */
        public boolean isPriceComponent() {
            return priceComponentCode != null;
        }

        /**
         * Een veld dat in de <b>artikelvingerafdruk</b> hoort (ontwerp fase 3, par. 3.5): eigenaar
         * {@code CATALOG_SOURCE}, geen prijscomponent en geen kritieke referentie. Prijs en
         * referenties hebben hun eigen deelvingerafdruk, zodat de mutatielijst kan tonen wélk domein
         * gewijzigd is.
         */
        public boolean isArticleField() {
            return fieldOwner == FieldOwner.CATALOG_SOURCE && priceComponentCode == null
                    && referenceType == null;
        }

        /**
         * Een veld waarvan de schrijfwijze betekenis draagt: artikelnummer, leveranciersnummer, groep,
         * referentie, barcode, PIM/CAB (R-REC-01). Zo'n veld blijft tekst — voorloopnullen, lengte en
         * hoofdletters blijven bewaard.
         */
        public boolean isIdentifyingText() {
            return referenceType != null || identityClass != IdentityClass.NONE;
        }
    }

    /**
     * De verklaarde notatie van één veld: hoe een decimale waarde gelezen wordt (R-REC-04) en volgens
     * welk formaat en welke tijdzone een datum of tijdstip gelezen wordt (R-REC-05). Beide komen uit
     * {@code transform_config} en worden één keer per batch geparsed; er is dus geen formatter of
     * parse per bronregel.
     *
     * @param dateFormatter {@code null} wanneer de revisie geen bronformaat verklaart; een datum die
     *                      niet onmiskenbaar ISO-8601 is, is dan <b>ambigu</b> en wordt geweigerd in
     *                      plaats van geraden ({@code DATE_AMBIGUOUS})
     * @param zone          de tijdzone van een {@code DATETIME}-bronwaarde; {@code null} betekent dat
     *                      een lokale tijdstempel niet naar een tijdstip omgezet kan worden
     */
    public record ValueFormat(DecimalFormat decimal, DateTimeFormatter dateFormatter, String datePattern,
                              ZoneId zone) {

        /** Fase 2-notatie: schaal 6, komma en punt als decimaalteken, geen verklaard datumformaat. */
        public static final ValueFormat DEFAULT =
                new ValueFormat(DecimalFormat.DEFAULT, null, null, null);
    }

    /**
     * Eén gevalideerde filterrij. De evaluatiesemantiek staat in {@link RecordFilterEvaluator}.
     *
     * @param sourceReference        headernaam of 1-gebaseerde kolomindex van de bronkolom
     * @param nullBehaviour          wat een ontbrekende of lege <b>waarde</b> betekent
     * @param missingColumnBehaviour wat een ontbrekende <b>kolom</b> betekent
     */
    public record RecordFilter(int sequenceNumber, String sourceReference, FilterOperator operator,
                               String compareValue, FilterOutcome outcome, boolean caseSensitive,
                               boolean trimBeforeCompare, FilterNullBehaviour nullBehaviour,
                               MissingColumnBehaviour missingColumnBehaviour) {
    }

    public ImportMappingConfig {
        fields = List.copyOf(fields);
        filters = List.copyOf(filters);
        criticalities = Map.copyOf(criticalities);
    }

    /**
     * De configuratie zonder revisie-eigen velden in de kritiek-map: enkel de mappings tellen mee. Voor
     * aanroepers die geen bronstructuur hebben (de vorm van vóór bouwstap 3h-1); de fabriek bouwt altijd
     * de volledige map.
     */
    public ImportMappingConfig(int canonicalisationVersion, List<FieldMapping> fields,
                               List<RecordFilter> filters) {
        this(canonicalisationVersion, fields, filters, criticalityMap(Map.of(), fields));
    }

    /**
     * Voegt de kritiek-vlaggen van de revisie-eigen velden (per bronreferentie) en van de mappings (per
     * logische veldnaam) samen tot één map.
     * <p>
     * <b>Twee namespaces, één map.</b> Een issue op een revisie-eigen veld draagt de bronreferentie als
     * veldnaam (bijvoorbeeld de headernaam {@code PRIJS}); een issue op een gemapt veld draagt de
     * logische veldnaam uit de catalogus (bijvoorbeeld {@code Omschrijving}). Botsen beide op dezelfde
     * sleutel, dan wint de strengste ({@link Criticality#CRITICAL}): een onduidelijke koppeling tussen
     * fout en kolom mag nooit een kritieke fout als niet-kritiek laten tellen.
     *
     * @param revisionOwnFields per bronreferentie de vlag van een revisie-eigen veld
     */
    public static Map<String, Criticality> criticalityMap(Map<String, Criticality> revisionOwnFields,
                                                          List<FieldMapping> fields) {
        Map<String, Criticality> merged = new HashMap<>();
        revisionOwnFields.forEach((name, criticality) -> {
            if (name != null && criticality != null) {
                merged.merge(name, criticality, Criticality::strictest);
            }
        });
        for (FieldMapping field : fields) {
            if (field.targetFieldName() != null) {
                merged.merge(field.targetFieldName(), field.criticality(), Criticality::strictest);
            }
        }
        return merged;
    }

    /**
     * Is een fout op deze kolom kritiek? {@code fieldName} is de veldnaam zoals ze in een issue staat
     * ({@code ImportValueException.getField()}): de bronreferentie van een revisie-eigen veld of de
     * logische veldnaam van een mapping.
     * <p>
     * <b>Fail-safe:</b> een onbekende sleutel of {@code null} is {@link Criticality#CRITICAL}. Een fout
     * die niet aan een bekende kolom te koppelen is, mag nooit als niet-kritiek doorgaan.
     */
    public Criticality criticalityOf(String fieldName) {
        if (fieldName == null) {
            return Criticality.CRITICAL;
        }
        return criticalities.getOrDefault(fieldName, Criticality.CRITICAL);
    }

    /** Een revisie zonder filters draait exact zoals in fase 2. */
    public boolean hasFilters() {
        return !filters.isEmpty();
    }

    public boolean hasFields() {
        return !fields.isEmpty();
    }

    /**
     * De velden die in de artikelvingerafdruk van canonicalisatieversie 2 meetellen, <b>gesorteerd op
     * {@code target_field_code}</b> (ontwerp fase 3, par. 3.5). De sortering is bewust op de
     * doelveldcode en niet op het volgnummer van de mapping: het hernummeren van de mappings mag
     * nooit de vingerafdruk van een ongewijzigde catalogus veranderen.
     */
    public List<FieldMapping> articleFingerprintFields() {
        return fields.stream()
                .filter(FieldMapping::isArticleField)
                .sorted(Comparator.comparing(FieldMapping::targetFieldCode))
                .toList();
    }

    /**
     * De gemapte afgeleide prijscomponenten, <b>gesorteerd op componentcode</b> (ontwerp fase 3,
     * par. 3.5): dat is de volgorde waarin ze in de prijsvingerafdruk staan. Sorteren op de
     * componentcode en niet op het volgnummer van de mapping, zodat het hernummeren van de mappings
     * nooit een ongewijzigde prijs als gewijzigd laat uitkomen.
     */
    public List<FieldMapping> priceComponentFields() {
        return fields.stream()
                .filter(FieldMapping::isPriceComponent)
                .sorted(Comparator.comparing(FieldMapping::priceComponentCode))
                .toList();
    }

    /** Heeft deze revisie afgeleide prijscomponenten? Zo niet, blijft het prijspad exact dat van 3c. */
    public boolean hasPriceComponents() {
        return fields.stream().anyMatch(FieldMapping::isPriceComponent);
    }

    /**
     * De gemapte kritieke koppelreferenties, <b>gesorteerd op referentietype</b> (ontwerp fase 3,
     * par. 3.5): dat is de volgorde waarin ze in de referentievingerafdruk staan. Sorteren op het
     * referentietype en niet op het volgnummer van de mapping, zodat het hernummeren van de mappings
     * nooit een ongewijzigde aanbieding als gewijzigd laat uitkomen.
     */
    public List<FieldMapping> referenceFields() {
        return fields.stream()
                .filter(field -> field.referenceType() != null)
                .sorted(Comparator.comparing(FieldMapping::referenceType))
                .toList();
    }

    /**
     * Mapt deze revisie kritieke koppelreferenties? Zo niet, wordt er geen enkele
     * {@code import_candidate_reference}-rij geschreven, draait de referentiecontrole niet en blijft
     * de referentievingerafdruk exact die van bouwstap 3c — byte voor byte.
     */
    public boolean hasReferences() {
        return fields.stream().anyMatch(field -> field.referenceType() != null);
    }

    /**
     * De kolommen die deze configuratie in de header verwacht: de bronkolommen van de mappings (met
     * hun verwachte positie) en die van de filters (zonder positie, want een filterkolom heeft haar
     * eigen {@code missing_column_behaviour}).
     */
    public HeaderExpectations headerExpectations() {
        List<ExpectedField> expected = new ArrayList<>(fields.size() + filters.size());
        for (FieldMapping field : fields) {
            if (field.valueKind() == FieldValueKind.SOURCE_FIELD && field.sourceReference() != null) {
                expected.add(new ExpectedField(field.sourceReference(), field.expectedPosition(),
                        field.isSemanticallyCritical(), true));
            }
        }
        for (RecordFilter filter : filters) {
            expected.add(new ExpectedField(filter.sourceReference(), null, false, false));
        }
        return HeaderExpectations.of(expected);
    }
}
