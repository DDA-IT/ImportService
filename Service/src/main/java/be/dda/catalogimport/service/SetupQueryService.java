package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.service.SetupService.FieldCriticalityView;
import be.dda.catalogimport.service.SetupService.FilterView;
import be.dda.catalogimport.service.SetupService.MappingView;
import be.dda.catalogimport.service.TemplateBookmarkService.BookmarkView;
import be.dda.catalogimport.service.TemplateBookmarkService.UsageView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alleen-lezen inrichtingslijsten voor scherm 1a (S1-B1, beslissingslog 27/09 "Heropening scherm 1a/1b",
 * keuze A1): bronorganisatie → definitie → revisie, tot op koppelingenniveau (dat laatste blijft
 * {@link ImportLinkQueryService}). Bewust een eigen, kleine service naast het vlaggedekte
 * {@link SetupService} — die twee blijven ongemoeid. Schrijft niets.
 * <p>
 * Paginering: {@code page} 0-gebaseerd, {@code size} standaard {@value #DEFAULT_PAGE_SIZE} en begrensd
 * tot {@value #MAX_PAGE_SIZE}, zelfde contract als {@link ImportLinkQueryService}.
 */
@Service
@Transactional(readOnly = true)
public class SetupQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    public record SourceOrganisationRow(long id, String code, String name, String type, boolean active) {

        private static SourceOrganisationRow of(SourceOrganisation organisation) {
            return new SourceOrganisationRow(organisation.getId(), organisation.getCode(), organisation.getName(),
                    organisation.getOrganisationType().name(), organisation.isActive());
        }
    }

    /** {@code activeRevisionId} is {@code null} als er geen {@link RevisionStatus#ACTIVE}-revisie is. */
    public record DefinitionRow(long id, String code, String name, String usageType, long sourceOrganisationId,
                                String sourceOrganisationCode, Long activeRevisionId) {
    }

    public record RevisionRow(long id, long definitionId, int revisionNumber, String status) {

        private static RevisionRow of(ImportDefinitionRevision revision) {
            return new RevisionRow(revision.getId(), revision.getImportDefinition().getId(),
                    revision.getRevisionNumber(), revision.getStatus().name());
        }
    }

    /**
     * De ingevulde bookmarkwaarde van een {@code DEFINITION}-scope bookmark op deze revisie
     * ({@code import_definition_bookmark_value}) — zie {@link ImportDefinitionBookmarkValue}.
     * {@code sourceTemplateRevisionId} is {@code null} bij handmatig invullen (nooit gematerialiseerd).
     */
    public record BookmarkValueRow(String bookmarkName, String dataType, String valueText,
                                   Long sourceTemplateRevisionId, Instant filledAt, String filledBy,
                                   String filledBySubject) {

        private static BookmarkValueRow of(ImportDefinitionBookmarkValue value) {
            ImportDefinitionRevision sourceTemplateRevision = value.getSourceTemplateRevision();
            return new BookmarkValueRow(value.getBookmarkName(), value.getDataType().name(), value.getValueText(),
                    sourceTemplateRevision == null ? null : sourceTemplateRevision.getId(), value.getFilledAt(),
                    value.getFilledBy(), value.getFilledBySubject());
        }
    }

    /**
     * Het volledige revisiedetail voor scherm 1a (revision-successor-design.md §6 endpoint E1, bouwstap
     * S1-X-3): alle scalaire velden van {@link ImportDefinitionRevision} plus de vijf
     * configuratie-kindtabellen — precies wat een gebruiker moet zien vóór hij een opvolger wijzigt
     * (S1-X-4/S1-F4). Werkt op elke revisiestatus (DRAFT/ACTIVE/SUPERSEDED): dit is een leesendpoint
     * zonder statusbeperking, in tegenstelling tot de schrijfpaden die enkel een DRAFT bewerken.
     */
    public record RevisionDetail(long id, long definitionId, int revisionNumber, String status,
                                 Long basedOnRevisionId, String changeReason,
                                 String identityProfileKind, String identitySupplierField,
                                 String identitySupplierGroupField, String identitySupplierReferenceField,
                                 String identityDiscountCodeField,
                                 String structureFormat, String structureCharset, String structureDelimiter,
                                 String structureQuoteChar, boolean structureHasHeader,
                                 int structureHeaderLineNumber, String structureFieldReferenceKind,
                                 Integer structureExpectedColumnCount, String accessDeliverySetKind,
                                 String recordBasePriceField, String recordDescriptionField,
                                 int recordCanonicalisationVersion, String recordCurrencyField,
                                 boolean basePriceZeroAllowed, boolean basePriceNegativeAllowed,
                                 BigDecimal priceDeviationPercent, String priceDeviationSeverity,
                                 BigDecimal priceDerivationTolerance, int priceAvgShortWindow,
                                 int priceAvgLongWindow, String priceControlModel,
                                 int creationThresholdAbsolute, BigDecimal creationThresholdSharePercent,
                                 int maxCriticalRecords, Integer maxRejectedRecords,
                                 BigDecimal maxCriticalSharePercent, BigDecimal maxRejectedSharePercent,
                                 BigDecimal bulkIncidentSharePercent,
                                 int accessVersion, String accessConfigHash, int structureVersion,
                                 String structureConfigHash, int recordRulesVersion,
                                 String recordRulesConfigHash, String compositeConfigHash,
                                 Instant createdAt, String createdBy, String createdBySubject,
                                 Instant updatedAt, Instant approvedAt, String approvedBy,
                                 String approvedBySubject,
                                 List<MappingView> mappings, List<FilterView> filters,
                                 List<FieldCriticalityView> fieldCriticalities, List<BookmarkView> bookmarks,
                                 List<BookmarkValueRow> bookmarkValues) {
    }

    private final SourceOrganisationRepository organisations;
    private final ImportDefinitionRepository definitions;
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportFieldMappingRepository fieldMappings;
    private final ImportRecordFilterRepository recordFilters;
    private final ImportRevisionFieldCriticalityRepository fieldCriticalities;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkUsageRepository bookmarkUsages;
    private final ImportDefinitionBookmarkValueRepository bookmarkValues;

    public SetupQueryService(SourceOrganisationRepository organisations, ImportDefinitionRepository definitions,
                             ImportDefinitionRevisionRepository revisions,
                             ImportFieldMappingRepository fieldMappings, ImportRecordFilterRepository recordFilters,
                             ImportRevisionFieldCriticalityRepository fieldCriticalities,
                             ImportDefinitionBookmarkRepository bookmarks,
                             ImportDefinitionBookmarkUsageRepository bookmarkUsages,
                             ImportDefinitionBookmarkValueRepository bookmarkValues) {
        this.organisations = organisations;
        this.definitions = definitions;
        this.revisions = revisions;
        this.fieldMappings = fieldMappings;
        this.recordFilters = recordFilters;
        this.fieldCriticalities = fieldCriticalities;
        this.bookmarks = bookmarks;
        this.bookmarkUsages = bookmarkUsages;
        this.bookmarkValues = bookmarkValues;
    }

    /**
     * Alle bronorganisaties, oplopend op {@code code}, optioneel gefilterd op {@code active}.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<SourceOrganisationRow> listSourceOrganisations(Boolean active, Integer page, Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<SourceOrganisation> result = organisations.findOrganisationRows(active, pageRequest);
        return PageResult.of(result, SourceOrganisationRow::of);
    }

    /**
     * Alle importdefinities, oplopend op {@code code}, optioneel gefilterd op bronorganisatie en
     * gebruikstype.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<DefinitionRow> listDefinitions(Long sourceOrganisationId, DefinitionUsageType usageType,
                                                      Integer page, Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<ImportDefinition> result = definitions.findDefinitionRows(sourceOrganisationId, usageType, pageRequest);
        return PageResult.of(result, this::definitionRow);
    }

    /**
     * Alle revisies van één definitie, oplopend op {@code revisionNumber}.
     *
     * @throws NotFoundException        {@code DEFINITION_NOT_FOUND}
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<RevisionRow> listRevisions(long definitionId, Integer page, Integer size) {
        if (!definitions.existsById(definitionId)) {
            throw new NotFoundException("DEFINITION_NOT_FOUND",
                    "Import definition " + definitionId + " does not exist");
        }
        PageRequest pageRequest = pageRequest(page, size);
        Page<ImportDefinitionRevision> result = revisions.findByDefinitionId(definitionId, pageRequest);
        return PageResult.of(result, RevisionRow::of);
    }

    /**
     * Het volledige detail van één revisie: alle scalaire velden plus de vijf configuratie-kindtabellen
     * (revision-successor-design.md §6 endpoint E1). Geen statusbeperking — een DRAFT, ACTIVE of
     * SUPERSEDED revisie zijn allemaal leesbaar.
     *
     * @throws NotFoundException {@code DEFINITION_NOT_FOUND}, {@code REVISION_NOT_FOUND}
     */
    public RevisionDetail getRevisionDetail(long definitionId, long revisionId) {
        if (!definitions.existsById(definitionId)) {
            throw new NotFoundException("DEFINITION_NOT_FOUND",
                    "Import definition " + definitionId + " does not exist");
        }
        ImportDefinitionRevision revision = revisions.findById(revisionId)
                .orElseThrow(() -> new NotFoundException("REVISION_NOT_FOUND",
                        "Definition revision " + revisionId + " does not exist"));
        if (!revision.getImportDefinition().getId().equals(definitionId)) {
            throw new NotFoundException("REVISION_NOT_FOUND", "Revision " + revisionId
                    + " does not belong to definition " + definitionId);
        }
        List<MappingView> mappingViews = fieldMappings.findByRevisionIdWithTargetField(revisionId).stream()
                .map(mapping -> new MappingView(mapping.getId(), revisionId, mapping.getSequenceNumber(),
                        mapping.getTargetField().getCode(), mapping.getSourceReference(),
                        mapping.getValueKind().name(), mapping.getCriticality().name()))
                .toList();
        List<FilterView> filterViews =
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId).stream()
                        .map(filter -> new FilterView(filter.getId(), revisionId, filter.getSequenceNumber(),
                                filter.getSourceReference(), filter.getOperator().name(),
                                filter.getCompareValue(), filter.getOutcome().name()))
                        .toList();
        List<FieldCriticalityView> fieldCriticalityViews = fieldCriticalities.findByDefinitionRevisionId(revisionId)
                .stream()
                .map(row -> new FieldCriticalityView(row.getDefinitionRevisionId(), row.getFieldKey(),
                        row.getCriticality().name()))
                .toList();
        List<ImportDefinitionBookmark> declaredBookmarks =
                bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(revisionId);
        List<ImportDefinitionBookmarkUsage> declaredUsages = declaredBookmarks.stream()
                .flatMap(bookmark -> bookmarkUsages.findByBookmarkId(bookmark.getId()).stream())
                .toList();
        List<BookmarkView> bookmarkViews = declaredBookmarks.stream()
                .map(bookmark -> bookmarkView(bookmark, declaredUsages))
                .toList();
        List<BookmarkValueRow> bookmarkValueRows = bookmarkValues.findByDefinitionRevisionId(revisionId).stream()
                .map(BookmarkValueRow::of)
                .toList();
        return revisionDetail(revision, mappingViews, filterViews, fieldCriticalityViews, bookmarkViews,
                bookmarkValueRows);
    }

    private static BookmarkView bookmarkView(ImportDefinitionBookmark bookmark,
                                             List<ImportDefinitionBookmarkUsage> allUsages) {
        List<UsageView> own = allUsages.stream()
                .filter(usage -> usage.getBookmark().getId().equals(bookmark.getId()))
                .map(usage -> new UsageView(usage.getId(), usage.getPlaceKind().name(), usage.getTargetHint()))
                .toList();
        return new BookmarkView(bookmark.getId(), bookmark.getDefinitionRevision().getId(), bookmark.getName(),
                bookmark.getLabel(), bookmark.getDescription(), bookmark.getDataType().name(),
                bookmark.getValueScope().name(), bookmark.getOwnerRole(), bookmark.isRequired(),
                bookmark.getDefaultValue(), bookmark.getAllowedValues(), bookmark.getValidationPattern(),
                bookmark.getSortOrder(), own);
    }

    private static RevisionDetail revisionDetail(ImportDefinitionRevision revision, List<MappingView> mappings,
                                                 List<FilterView> filters,
                                                 List<FieldCriticalityView> fieldCriticalities,
                                                 List<BookmarkView> bookmarks,
                                                 List<BookmarkValueRow> bookmarkValues) {
        ImportDefinitionRevision basedOn = revision.getBasedOnRevision();
        return new RevisionDetail(revision.getId(), revision.getImportDefinition().getId(),
                revision.getRevisionNumber(), revision.getStatus().name(),
                basedOn == null ? null : basedOn.getId(), revision.getChangeReason(),
                revision.getIdentityProfileKind().name(), revision.getIdentitySupplierField(),
                revision.getIdentitySupplierGroupField(), revision.getIdentitySupplierReferenceField(),
                revision.getIdentityDiscountCodeField(),
                revision.getStructureFormat(), revision.getStructureCharset(), revision.getStructureDelimiter(),
                revision.getStructureQuoteChar(), revision.isStructureHasHeader(),
                revision.getStructureHeaderLineNumber(), revision.getStructureFieldReferenceKind(),
                revision.getStructureExpectedColumnCount(), revision.getAccessDeliverySetKind(),
                revision.getRecordBasePriceField(), revision.getRecordDescriptionField(),
                revision.getRecordCanonicalisationVersion(), revision.getRecordCurrencyField(),
                revision.isBasePriceZeroAllowed(), revision.isBasePriceNegativeAllowed(),
                revision.getPriceDeviationPercent(), revision.getPriceDeviationSeverity().name(),
                revision.getPriceDerivationTolerance(), revision.getPriceAvgShortWindow(),
                revision.getPriceAvgLongWindow(), revision.getPriceControlModel().name(),
                revision.getCreationThresholdAbsolute(), revision.getCreationThresholdSharePercent(),
                revision.getMaxCriticalRecords(), revision.getMaxRejectedRecords(),
                revision.getMaxCriticalSharePercent(), revision.getMaxRejectedSharePercent(),
                revision.getBulkIncidentSharePercent(),
                revision.getAccessVersion(), revision.getAccessConfigHash(), revision.getStructureVersion(),
                revision.getStructureConfigHash(), revision.getRecordRulesVersion(),
                revision.getRecordRulesConfigHash(), revision.getCompositeConfigHash(),
                revision.getCreatedAt(), revision.getCreatedBy(), revision.getCreatedBySubject(),
                revision.getUpdatedAt(), revision.getApprovedAt(), revision.getApprovedBy(),
                revision.getApprovedBySubject(),
                mappings, filters, fieldCriticalities, bookmarks, bookmarkValues);
    }

    private DefinitionRow definitionRow(ImportDefinition definition) {
        Long activeRevisionId = revisions
                .findByImportDefinitionIdAndStatus(definition.getId(), RevisionStatus.ACTIVE)
                .map(ImportDefinitionRevision::getId)
                .orElse(null);
        return new DefinitionRow(definition.getId(), definition.getCode(), definition.getDescription(),
                definition.getUsageType().name(), definition.getSourceOrganisation().getId(),
                definition.getSourceOrganisation().getCode(), activeRevisionId);
    }

    /** Sortering staat al vast in de {@code @Query} van de gebruikte repositorymethode. */
    private static PageRequest pageRequest(Integer page, Integer size) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE), Sort.unsorted());
    }
}
