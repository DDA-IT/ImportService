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
import java.util.ArrayList;
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
     * @param defaultValue       enkel toe te passen bij een werkelijk ontbrekende waarde (R-REC-03)
     * @param priceComponentCode gevuld voor een prijscomponent; eigenaar is dan {@code PRICE_CONTROL}
     * @param referenceType      gevuld voor een kritieke referentie; eigenaar is dan
     *                           {@code CRITICAL_REFERENCE} en niet wisselbaar
     */
    public record FieldMapping(int sequenceNumber, String targetFieldCode, String targetFieldName,
                               FieldValueKind valueKind, String sourceReference, Integer expectedPosition,
                               String fixedValue, String defaultValue, FieldDataType dataType,
                               boolean required, Integer maxLength, Integer decimalScale,
                               boolean zeroAllowed, boolean negativeAllowed,
                               FieldTransformKind transformKind, String transformConfig,
                               FieldOwner fieldOwner, IdentityClass identityClass,
                               String priceComponentCode, String referenceType) {

        /** Een veld dat de identiteit, de prijs of een kritieke referentie draagt. */
        public boolean isSemanticallyCritical() {
            return priceComponentCode != null || referenceType != null
                    || identityClass == IdentityClass.STRONG
                    || identityClass == IdentityClass.ARTICLE_REFERENCE;
        }
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
