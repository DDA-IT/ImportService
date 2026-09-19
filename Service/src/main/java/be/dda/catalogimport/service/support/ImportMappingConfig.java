package be.dda.catalogimport.service.support;

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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * De gevalideerde veldmapping en recordfilters van één bevroren {@code ImportDefinitionRevision}
 * (ontwerp fase 3, par. 3.1 stap B').
 * <p>
 * Net als {@link SourceStructureConfig} is dit een <b>momentopname</b>, geen JPA-entiteit: de parser,
 * de filterevaluatie en de normalisatie draaien buiten elke transactie en mogen nooit een lazy
 * associatie aanraken. Ze wordt exact één keer per batch gebouwd, vóór er één byte gelezen is; er is
 * dus geen enkele query per bronregel.
 * <p>
 * <b>Grens van bouwstap 3b.</b> De mappings worden hier gelezen en volledig gevalideerd, maar nog
 * niet toegepast: het omzetten van bronwaarden naar doelvelden (transformaties, defaults, types,
 * lengtes) is bouwstap 3c en de prijscomponenten zijn 3d. De <b>recordfilters werken wél</b> vanaf
 * deze bouwstap. Een revisie met prijscomponent- of referentiemappings wordt daarom geblokkeerd met
 * {@code CONFIG_CANONICALISATION_VERSION_REQUIRED}: zulke mappings horen bij canonicalisatieversie 2,
 * en die is in deze build nog niet ondersteund. Zo kan er nooit een levering verwerkt worden waarvan
 * de vingerafdruk de gemapte velden niet dekt.
 *
 * @param canonicalisationVersion de versie die de revisie declareert; bepaalt welke velden in de
 *                                vingerafdrukken meetellen
 */
public record ImportMappingConfig(int canonicalisationVersion, List<FieldMapping> fields,
                                  List<RecordFilter> filters) {

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
                               String priceComponentCode, String referenceType) {

        /** Een veld dat de identiteit, de prijs of een kritieke referentie draagt. */
        public boolean isSemanticallyCritical() {
            return priceComponentCode != null || referenceType != null
                    || identityClass == IdentityClass.STRONG
                    || identityClass == IdentityClass.ARTICLE_REFERENCE;
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
