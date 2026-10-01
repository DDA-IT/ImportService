package be.dda.catalogimport.service.support;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import be.dda.catalogimport.domain.RevisionStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Het verbatim kopiëren van één {@link ImportDefinitionRevision} naar een nieuwe, nog niet opgeslagen
 * revisie, plus het kopiëren van haar configuratie-kindrijen
 * (revision-successor-design.md &sect;0, &sect;1, &sect;2; bouwstap S1-X-1).
 * <p>
 * <b>Verhuisd uit {@code TemplateMaterialisationService}</b> (r.1107-1270 vóór deze bouwstap), met
 * <b>exact hetzelfde gedrag</b>: veld per veld dezelfde kopie, dezelfde leesqueries in dezelfde volgorde,
 * dezelfde {@code created_by}/{@code created_by_subject}-velden en dezelfde
 * {@link RevisionConfigHashes#applyAll(ImportDefinitionRevision)} op het einde van de scalaire kopie. Dit
 * is dezelfde soort verhuizing, om dezelfde reden, als die van {@link RevisionConfigHashes} uit
 * {@code SetupService}: er komt een tweede aanroeper (de opvolgrevisie, S1-X-2) en twee clone-implementaties
 * naast elkaar zouden op termijn uit elkaar lopen — waarna een "kopie" stil géén kopie meer is.
 *
 * <h2>Wat deze klasse bewust niet beslist</h2>
 * De drie dingen die tussen de twee aanroepers verschillen, zijn <b>parameters</b>, geen aannames:
 * de doeldefinitie (materialisatie: een nieuwe definitie; opvolger: dezelfde definitie), het
 * revisienummer (materialisatie: altijd 1; opvolger: hoogste + 1) en de bronrevisie voor
 * {@code based_on_revision_id}. Ook de <b>wijzigingsreden</b> komt van buiten: materialisatie valt terug
 * op een vaste zin, de opvolgrevisie eist er een in het verzoek
 * ({@code CHANGE_REASON_REQUIRED}, revision-successor-design.md &sect;1).
 * <p>
 * Wat de klasse wél vastlegt omdat het voor beide aanroepers identiek is (&sect;1): de nieuwe revisie is
 * {@link RevisionStatus#DRAFT}, {@code approved_*} blijft {@code null} (een DRAFT is niet goedgekeurd), de
 * drie laagversienummers blijven op hun entiteitsdefault 1, en de vier configuratiehashes worden
 * <i>herberekend</i> in plaats van gekopieerd.
 * <p>
 * De <b>audittekening</b> komt als {@code createdBy} + {@code createdBySubject} mee, twee losse
 * parameters in dezelfde vorm als de domeinconstructors ze al aannemen ({@code null} subject = geen
 * geverifieerde identiteit). Zo blijft {@code service.support} vrij van een import uit {@code service}
 * zelf: de afhankelijkheid loopt in deze codebase altijd van {@code service} naar {@code service.support},
 * nooit terug.
 * <p>
 * <b>Wie welke kindtabel kopieert</b> (revision-successor-design.md &sect;2). De opvolgrevisie kopieert
 * alle vijf configuratie-kindtabellen, de bookmarkwaarden inbegrepen
 * ({@link #copyBookmarkValues}, toegevoegd in S1-X-2). De materialisatiewizard roept die laatste methode
 * bewust <b>niet</b> aan: daar ontstaan de waarderijen uit de ingevulde wizardwaarden op de afgeleide
 * revisie, niet uit een bestaande waarderij op het sjabloon.
 */
@Component
public class RevisionCopier {

    private final ImportFieldMappingRepository fieldMappings;
    private final ImportRecordFilterRepository recordFilters;
    private final ImportRevisionFieldCriticalityRepository fieldCriticalities;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkUsageRepository usages;
    private final ImportDefinitionBookmarkValueRepository bookmarkValues;

    public RevisionCopier(ImportFieldMappingRepository fieldMappings,
                          ImportRecordFilterRepository recordFilters,
                          ImportRevisionFieldCriticalityRepository fieldCriticalities,
                          ImportDefinitionBookmarkRepository bookmarks,
                          ImportDefinitionBookmarkUsageRepository usages,
                          ImportDefinitionBookmarkValueRepository bookmarkValues) {
        this.fieldMappings = fieldMappings;
        this.recordFilters = recordFilters;
        this.fieldCriticalities = fieldCriticalities;
        this.bookmarks = bookmarks;
        this.usages = usages;
        this.bookmarkValues = bookmarkValues;
    }

    /**
     * De scalaire velden van {@code source} in een nieuwe, <b>nog niet opgeslagen</b> revisie van
     * {@code targetDefinition}: {@link RevisionStatus#DRAFT}, {@code based_on_revision_id} naar
     * {@code basedOnRevision}, en verder een volledige kopie van de identiteits-, structuur-, prijs- en
     * drempelvelden (revision-successor-design.md &sect;1).
     * <p>
     * De vier hashkolommen zijn {@code not null}: ze moeten al vóór de eerste insert kloppen, dus
     * {@link RevisionConfigHashes#applyAll} draait hier al één keer. Wie ná deze aanroep nog een
     * revisieveld wijzigt (de materialisatiewizard doet dat met haar bookmarkwaarden), moet
     * {@code applyAll} opnieuw aanroepen — de hash beschrijft de revisie zoals ze op dát moment is.
     *
     * @param revisionNumber     het nummer van de nieuwe revisie: 1 bij een materialisatie naar een nieuwe
     *                           definitie, hoogste + 1 bij een opvolgrevisie binnen dezelfde definitie
     * @param basedOnRevision    de herkomstrevisie ({@code based_on_revision_id})
     * @param changeReason       de wijzigingsreden; komt altijd van de aanroeper, nooit uit de bron
     * @param createdBySubject   het OIDC-subject van de tekenaar, of {@code null} = geen geverifieerde
     *                           identiteit
     */
    public ImportDefinitionRevision copyRevision(ImportDefinitionRevision source,
                                                 ImportDefinition targetDefinition,
                                                 int revisionNumber,
                                                 ImportDefinitionRevision basedOnRevision,
                                                 String changeReason,
                                                 String createdBy,
                                                 String createdBySubject) {
        ImportDefinitionRevision copy = new ImportDefinitionRevision(targetDefinition, revisionNumber,
                source.getIdentityProfileKind(), createdBy);
        copy.setCreatedBySubject(createdBySubject);
        copy.setStatus(RevisionStatus.DRAFT);
        copy.setBasedOnRevision(basedOnRevision);
        copy.setChangeReason(changeReason);
        copy.setIdentitySupplierField(source.getIdentitySupplierField());
        copy.setIdentitySupplierGroupField(source.getIdentitySupplierGroupField());
        copy.setIdentitySupplierReferenceField(source.getIdentitySupplierReferenceField());
        copy.setIdentityDiscountCodeField(source.getIdentityDiscountCodeField());
        copy.setStructureFormat(source.getStructureFormat());
        copy.setStructureCharset(source.getStructureCharset());
        copy.setStructureDelimiter(source.getStructureDelimiter());
        copy.setStructureQuoteChar(source.getStructureQuoteChar());
        copy.setStructureHasHeader(source.isStructureHasHeader());
        copy.setStructureHeaderLineNumber(source.getStructureHeaderLineNumber());
        copy.setStructureFieldReferenceKind(source.getStructureFieldReferenceKind());
        copy.setStructureExpectedColumnCount(source.getStructureExpectedColumnCount());
        copy.setAccessDeliverySetKind(source.getAccessDeliverySetKind());
        copy.setRecordBasePriceField(source.getRecordBasePriceField());
        copy.setRecordDescriptionField(source.getRecordDescriptionField());
        copy.setRecordCurrencyField(source.getRecordCurrencyField());
        copy.setRecordCanonicalisationVersion(source.getRecordCanonicalisationVersion());
        copy.setBasePriceZeroAllowed(source.isBasePriceZeroAllowed());
        copy.setBasePriceNegativeAllowed(source.isBasePriceNegativeAllowed());
        copy.setPriceDeviationPercent(source.getPriceDeviationPercent());
        copy.setPriceDeviationSeverity(source.getPriceDeviationSeverity());
        copy.setPriceDerivationTolerance(source.getPriceDerivationTolerance());
        copy.setPriceAvgShortWindow(source.getPriceAvgShortWindow());
        copy.setPriceAvgLongWindow(source.getPriceAvgLongWindow());
        copy.setPriceControlModel(source.getPriceControlModel());
        copy.setCreationThresholdSharePercent(source.getCreationThresholdSharePercent());
        copy.setMaxCriticalSharePercent(source.getMaxCriticalSharePercent());
        copy.setMaxRejectedSharePercent(source.getMaxRejectedSharePercent());
        copy.setBulkIncidentSharePercent(source.getBulkIncidentSharePercent());
        copyDeprecatedThresholds(copy, source);
        RevisionConfigHashes.applyAll(copy);
        return copy;
    }

    /**
     * De drie drempelkolommen die sinds bouwstap 3h-4 niet meer gelezen worden (beslissingslog 20/09:
     * elke drempel is een percentage). Ze worden tóch meegekopieerd: de nieuwe revisie hoort een
     * getrouwe kopie te zijn, en een stil afwijkende opgeslagen waarde zou later, als iemand die kolom
     * weer zou lezen, een ander gedrag geven dan de bron beschreef.
     */
    @SuppressWarnings("deprecation")
    private static void copyDeprecatedThresholds(ImportDefinitionRevision copy,
                                                 ImportDefinitionRevision source) {
        copy.setCreationThresholdAbsolute(source.getCreationThresholdAbsolute());
        copy.setMaxCriticalRecords(source.getMaxCriticalRecords());
        copy.setMaxRejectedRecords(source.getMaxRejectedRecords());
    }

    /**
     * De veldmappings van {@code source} naar {@code target}.
     *
     * @return de kopieën op doelveldcode — de sleutel waarmee een bookmark haar mapping aanwijst. Ze zijn
     *     <b>niet</b> weggeschreven: de materialisatiewizard zet eerst haar bookmarkwaarde erin en slaat
     *     daarna op, zodat de sjabloon-placeholderwaarde nooit in de database staat.
     */
    public Map<String, ImportFieldMapping> copyMappings(ImportDefinitionRevision target,
                                                        ImportDefinitionRevision source,
                                                        String createdBy, String createdBySubject) {
        Map<String, ImportFieldMapping> copies = new LinkedHashMap<>();
        for (ImportFieldMapping row : fieldMappings.findByRevisionIdWithTargetField(source.getId())) {
            ImportFieldMapping copy = new ImportFieldMapping(target, row.getSequenceNumber(),
                    row.getTargetField(), row.getValueKind(), row.getDataType(), row.getFieldOwner(),
                    row.getIdentityClass());
            copy.setSourceReference(row.getSourceReference());
            copy.setExpectedPosition(row.getExpectedPosition());
            copy.setFixedValue(row.getFixedValue());
            copy.setBookmarkName(row.getBookmarkName());
            copy.setDefaultValue(row.getDefaultValue());
            copy.setRequired(row.isRequired());
            copy.setMaxLength(row.getMaxLength());
            copy.setDecimalScale(row.getDecimalScale());
            copy.setZeroAllowed(row.isZeroAllowed());
            copy.setNegativeAllowed(row.isNegativeAllowed());
            copy.setTransformKind(row.getTransformKind());
            copy.setTransformConfig(row.getTransformConfig());
            copy.setPriceComponentCode(row.getPriceComponentCode());
            copy.setReferenceType(row.getReferenceType());
            copy.setCriticality(row.getCriticality());
            copy.setActive(row.isActive());
            copy.setCreatedBy(createdBy);
            copy.setCreatedBySubject(createdBySubject);
            copies.put(row.getTargetField().getCode(), copy);
        }
        return copies;
    }

    /**
     * De recordfilters van {@code source} naar {@code target}.
     *
     * @return de nog <b>niet</b> weggeschreven kopieën op volgnummer — de sleutel waarmee een bookmark ze
     *     aanwijst
     */
    public Map<Integer, ImportRecordFilter> copyFilters(ImportDefinitionRevision target,
                                                        ImportDefinitionRevision source,
                                                        String createdBy, String createdBySubject) {
        Map<Integer, ImportRecordFilter> copies = new LinkedHashMap<>();
        for (ImportRecordFilter row
                : recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(source.getId())) {
            ImportRecordFilter copy = new ImportRecordFilter(target, row.getSequenceNumber(),
                    row.getSourceReference(), row.getOperator(), row.getCompareValue(), row.getOutcome());
            copy.setFilterStage(row.getFilterStage());
            copy.setCaseSensitive(row.isCaseSensitive());
            copy.setTrimBeforeCompare(row.isTrimBeforeCompare());
            copy.setNullBehaviour(row.getNullBehaviour());
            copy.setMissingColumnBehaviour(row.getMissingColumnBehaviour());
            copy.setCreatedBy(createdBy);
            copy.setCreatedBySubject(createdBySubject);
            copies.put(row.getSequenceNumber(), copy);
        }
        return copies;
    }

    /**
     * De kritiekheidsoverrules van {@code source} naar {@code target}. Deze rijen dragen geen
     * bookmarkwaarde en worden daarom direct opgeslagen, in leesvolgorde.
     */
    public void copyFieldCriticalities(ImportDefinitionRevision target, ImportDefinitionRevision source,
                                       String createdBy, String createdBySubject) {
        for (ImportRevisionFieldCriticality row : fieldCriticalities.findByDefinitionRevisionId(source.getId())) {
            ImportRevisionFieldCriticality copy = new ImportRevisionFieldCriticality(target.getId(),
                    row.getFieldKey(), row.getCriticality());
            copy.setCreatedBy(createdBy);
            copy.setCreatedBySubject(createdBySubject);
            fieldCriticalities.save(copy);
        }
    }

    /**
     * De meegegeven bookmarkdeclaraties plus hun usages naar {@code target}, in de volgorde van
     * {@code sources}. De declaraties en usages worden direct opgeslagen.
     * <p>
     * <b>Welke</b> declaraties meegaan, beslist de aanroeper, niet deze klasse: de materialisatiewizard
     * kopieert enkel de {@code LINK}-scope declaraties (R-MAT-03, Q5 — de {@code DEFINITION}-scope
     * declaraties zijn daar opgelost en leven voort als {@code import_definition_bookmark_value}), terwijl
     * een opvolgrevisie ze <i>alle</i> meeneemt (revision-successor-design.md &sect;2: anders verliest een
     * sjabloon-opvolger zijn declaraties). Dat is een businesskeuze per aanroeper, geen kopieerdetail.
     *
     * @param usagesByBookmark de usages per bronbookmark; moet een ingang hebben voor elke bookmark in
     *                         {@code sources}
     */
    public void copyBookmarkDeclarations(ImportDefinitionRevision target,
                                         List<ImportDefinitionBookmark> sources,
                                         Map<ImportDefinitionBookmark, List<ImportDefinitionBookmarkUsage>>
                                                 usagesByBookmark,
                                         String createdBy, String createdBySubject) {
        for (ImportDefinitionBookmark source : sources) {
            ImportDefinitionBookmark copy = new ImportDefinitionBookmark(target, source.getName(),
                    source.getLabel(), source.getDataType(), source.getValueScope(), source.getOwnerRole(),
                    source.getSortOrder());
            copy.setDescription(source.getDescription());
            copy.setRequired(source.isRequired());
            copy.setDefaultValue(source.getDefaultValue());
            copy.setAllowedValues(source.getAllowedValues());
            copy.setValidationPattern(source.getValidationPattern());
            copy.setCreatedBy(createdBy);
            copy.setCreatedBySubject(createdBySubject);
            ImportDefinitionBookmark stored = bookmarks.save(copy);
            for (ImportDefinitionBookmarkUsage usage : usagesByBookmark.get(source)) {
                usages.save(new ImportDefinitionBookmarkUsage(stored, usage.getPlaceKind(),
                        usage.getTargetHint()));
            }
        }
    }

    /**
     * De {@code DEFINITION}-scope bookmarkwaarden van {@code source} naar {@code target}
     * (revision-successor-design.md &sect;2, bouwstap S1-X-2). Alleen de opvolgrevisie roept dit aan; de
     * materialisatiewizard schrijft haar waarderijen uit de ingevulde wizardwaarden.
     * <p>
     * <b>Letterlijk mee:</b> {@code bookmark_name}, {@code data_type}, {@code value_text} en
     * {@code source_template_revision_id}. Die laatste is de enige plek waar nog af te lezen is dát een
     * vaste waarde uit een sjabloon kwam en uit welke sjabloonversie (javadoc
     * {@link ImportDefinitionBookmarkValue}); zou de kopie ze laten vallen, dan verloor een
     * opvolgrevisie stilzwijgend die herkomst.
     * <p>
     * <b>Niet mee:</b> de invultekening. {@code filled_by}/{@code filled_by_subject} worden de klonende
     * gebruiker en {@code filled_at} het moment van klonen (via
     * {@code ImportDefinitionBookmarkValue.onPersist}): deze rij is nieuw en is door déze handeling
     * vastgelegd. De oorspronkelijke invuller blijft op de bronrevisie staan, die onaangeroerd blijft.
     * <p>
     * {@code ""} wordt als {@code ""} gekopieerd en nooit tot "niet ingevuld" herleid: geen rij en een
     * lege rij zijn verschillende toestanden (R-BMK-03).
     */
    public void copyBookmarkValues(ImportDefinitionRevision target, ImportDefinitionRevision source,
                                   String filledBy, String filledBySubject) {
        for (ImportDefinitionBookmarkValue row : bookmarkValues.findByDefinitionRevisionId(source.getId())) {
            ImportDefinitionBookmarkValue copy = new ImportDefinitionBookmarkValue(target,
                    row.getBookmarkName(), row.getDataType(), row.getValueText(), filledBy, filledBySubject);
            copy.setSourceTemplateRevision(row.getSourceTemplateRevision());
            bookmarkValues.save(copy);
        }
    }
}
