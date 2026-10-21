package be.dda.catalogimport.service;

import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterStage;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.MissingColumnBehaviour;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedFilter;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedMapping;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedRevision;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * De inhoudshash van wat de legacy-migratie op een revisie zet (beslissingslog 2026-10-20 "Legacy-config S2", V2 = C):
 * de door legacy bepaalde revisievelden (structuur, identiteit, basisprijs, omschrijving), <b>alle</b> mappings met al
 * hun attributen en <b>alle</b> recordfilters. Drempels, prijsbeleid, kritiek-overrules en bookmarks tellen niet mee:
 * die zijn van de mens en gaan bij een opvolgende DRAFT letterlijk mee.
 * <p>
 * Dezelfde berekening op twee bronnen, zodat ze vergelijkbaar zijn:
 * <ul>
 *   <li>{@link #ofPlanned}: wat APPLY zou schrijven, met de standaardwaarden die {@code SetupService.addMapping} en
 *       {@code addFilter} zetten;</li>
 *   <li>{@link #ofRevision}: wat er nu in de database staat.</li>
 * </ul>
 * APPLY controleert na het schrijven dat beide gelijk zijn; zo kan een verschil tussen plan en uitvoering nooit stil
 * een verkeerde {@code DEFINITION_UNCHANGED} of {@code MANUAL_CHANGES_SINCE_MIGRATION} veroorzaken.
 * <p>
 * Canonieke opbouw via {@link RevisionConfigHashes#hash(String, String...)} (laag {@value #LAYER}).
 */
final class LegacyRevisionContent {

    static final String LAYER = "legacy-migrated-content-v1";

    private LegacyRevisionContent() {
    }

    /**
     * @param criticalityOf de standaardkritiek van een doelveld, zoals {@link ImportFieldMapping#getCriticality()}
     *                      die afleidt uit de veldcatalogus (referentie of prijscomponent = CRITICAL)
     */
    static String ofPlanned(PlannedRevision planned, String format, Function<String, Criticality> criticalityOf) {
        List<String> parts = scalars(format, planned.charset(), planned.delimiter(), planned.hasHeader(),
                planned.headerLineNumber(), LegacyConfigTranslator.FIELD_REFERENCE_KIND,
                String.valueOf(planned.identityProfileKind()), planned.supplierField(), planned.groupField(),
                planned.referenceField(), planned.discountField(), planned.basePriceField(),
                planned.descriptionField());
        parts.addAll(identityExtensions(planned.supplierFixedValue(), planned.groupFixedValue(),
                planned.identityUppercase()));
        planned.mappings().stream().sorted(Comparator.comparing(PlannedMapping::targetCode)).forEach(mapping ->
                parts.addAll(mapping(mapping.targetCode(), String.valueOf(mapping.valueKind()),
                        mapping.sourceReference(), mapping.fixedValue(), String.valueOf(mapping.transformKind()),
                        mapping.transformConfig(), null, null, false, null, null, false, false,
                        String.valueOf(criticalityOf.apply(mapping.targetCode())), true, null)));
        planned.filters().stream().sorted(Comparator.comparingInt(PlannedFilter::sequence)).forEach(filter ->
                parts.addAll(filter(filter.sequence(), FilterStage.SOURCE_FIELD.name(), filter.sourceReference(),
                        filter.operator().name(), filter.compareValue(), filter.outcome().name(),
                        filter.caseSensitive(), true, FilterNullBehaviour.EXCLUDE.name(),
                        MissingColumnBehaviour.BLOCK.name())));
        return RevisionConfigHashes.hash(LAYER, parts.toArray(String[]::new));
    }

    static String ofRevision(ImportDefinitionRevision revision, List<ImportFieldMapping> mappings,
                             List<ImportRecordFilter> filters) {
        List<String> parts = scalars(revision.getStructureFormat(), revision.getStructureCharset(),
                revision.getStructureDelimiter(), revision.isStructureHasHeader(),
                revision.getStructureHeaderLineNumber(), revision.getStructureFieldReferenceKind(),
                String.valueOf(revision.getIdentityProfileKind()), revision.getIdentitySupplierField(),
                revision.getIdentitySupplierGroupField(), revision.getIdentitySupplierReferenceField(),
                revision.getIdentityDiscountCodeField(), revision.getRecordBasePriceField(),
                revision.getRecordDescriptionField());
        parts.addAll(identityExtensions(revision.getIdentitySupplierFixedValue(),
                revision.getIdentitySupplierGroupFixedValue(), revision.isIdentityUppercase()));
        mappings.stream().sorted(Comparator.comparing(row -> row.getTargetField().getCode())).forEach(row ->
                parts.addAll(mapping(row.getTargetField().getCode(), String.valueOf(row.getValueKind()),
                        row.getSourceReference(), row.getFixedValue(), String.valueOf(row.getTransformKind()),
                        row.getTransformConfig(), row.getExpectedPosition(), row.getDefaultValue(), row.isRequired(),
                        row.getMaxLength(), row.getDecimalScale(), row.isZeroAllowed(), row.isNegativeAllowed(),
                        String.valueOf(row.getCriticality()), row.isActive(), row.getBookmarkName())));
        filters.stream().sorted(Comparator.comparingInt(ImportRecordFilter::getSequenceNumber)).forEach(row ->
                parts.addAll(filter(row.getSequenceNumber(), String.valueOf(row.getFilterStage()),
                        row.getSourceReference(), String.valueOf(row.getOperator()), row.getCompareValue(),
                        String.valueOf(row.getOutcome()), row.isCaseSensitive(), row.isTrimBeforeCompare(),
                        String.valueOf(row.getNullBehaviour()), String.valueOf(row.getMissingColumnBehaviour()))));
        return RevisionConfigHashes.hash(LAYER, parts.toArray(String[]::new));
    }

    private static List<String> scalars(String format, String charset, String delimiter, boolean hasHeader,
                                        int headerLineNumber, String fieldReferenceKind, String profile,
                                        String supplier, String group, String reference, String discount,
                                        String basePrice, String description) {
        List<String> parts = new ArrayList<>();
        parts.add("S");
        parts.add(String.valueOf(format));
        parts.add(String.valueOf(charset));
        parts.add(String.valueOf(delimiter));
        parts.add(String.valueOf(hasHeader));
        parts.add(String.valueOf(headerLineNumber));
        parts.add(String.valueOf(fieldReferenceKind));
        parts.add(profile);
        parts.add(String.valueOf(supplier));
        parts.add(String.valueOf(group));
        parts.add(String.valueOf(reference));
        parts.add(String.valueOf(discount));
        parts.add(String.valueOf(basePrice));
        parts.add(String.valueOf(description));
        return parts;
    }

    /**
     * Legacy-config S6: vaste leverancier, vaste groep en hoofdletters, <b>enkel</b> als ze niet de default hebben
     * ({@code null}/{@code false}), met hun naam als voorvoegsel - hetzelfde principe als
     * {@code RevisionConfigHashes}. Zo blijft de {@code migrated_content_hash} van elke revisie die vóór S6 gemigreerd
     * werd geldig: een ongewijzigde legacy-definitie geeft na S6 wél een andere geplande inhoud (altijd hoofdletters),
     * dus een opvolgende DRAFT met {@code IDENTITY_CHANGE}, en nooit onterecht {@code MANUAL_CHANGES_SINCE_MIGRATION}.
     */
    private static List<String> identityExtensions(String supplierFixedValue, String groupFixedValue,
                                                   boolean uppercase) {
        List<String> parts = new ArrayList<>(3);
        if (supplierFixedValue != null) {
            parts.add("identitySupplierFixedValue=" + supplierFixedValue);
        }
        if (groupFixedValue != null) {
            parts.add("identitySupplierGroupFixedValue=" + groupFixedValue);
        }
        if (uppercase) {
            parts.add("identityUppercase=true");
        }
        return parts;
    }

    private static List<String> mapping(String target, String valueKind, String source, String fixedValue,
                                        String transformKind, String transformConfig, Integer expectedPosition,
                                        String defaultValue, boolean required, Integer maxLength,
                                        Integer decimalScale, boolean zeroAllowed, boolean negativeAllowed,
                                        String criticality, boolean active, String bookmarkName) {
        return List.of("M", target, valueKind, String.valueOf(source), String.valueOf(fixedValue), transformKind,
                String.valueOf(transformConfig), String.valueOf(expectedPosition), String.valueOf(defaultValue),
                String.valueOf(required), String.valueOf(maxLength), String.valueOf(decimalScale),
                String.valueOf(zeroAllowed), String.valueOf(negativeAllowed), criticality, String.valueOf(active),
                String.valueOf(bookmarkName));
    }

    private static List<String> filter(int sequence, String stage, String source, String operator, String compare,
                                       String outcome, boolean caseSensitive, boolean trim, String nullBehaviour,
                                       String missingColumn) {
        return List.of("F", String.valueOf(sequence), stage, String.valueOf(source), operator,
                String.valueOf(compare), outcome, String.valueOf(caseSensitive), String.valueOf(trim), nullBehaviour,
                missingColumn);
    }
}
