package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportFieldTransformStepRepository;
import be.dda.catalogimport.dao.ImportFieldValueMapRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.dao.LegacyConfigDefinitionOriginRepository;
import be.dda.catalogimport.dao.LegacyConfigRevisionOriginRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.LegacyConfigDefinitionOrigin;
import be.dda.catalogimport.domain.RevisionCriticalityField;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.service.ImportLinkReadinessService.CheckStatus;
import be.dda.catalogimport.service.ImportLinkReadinessService.CheckSubject;
import be.dda.catalogimport.service.ImportLinkReadinessService.ReadinessCheck;
import be.dda.catalogimport.service.ImportLinkReadinessService.SubjectType;
import be.dda.catalogimport.service.SetupService.FieldCriticalityView;
import be.dda.catalogimport.service.SetupService.FilterView;
import be.dda.catalogimport.service.SetupService.MappingView;
import be.dda.catalogimport.service.SetupService.TransformStepEntry;
import be.dda.catalogimport.service.SetupService.ValueMapEntry;
import be.dda.catalogimport.service.TemplateBookmarkService.BookmarkView;
import be.dda.catalogimport.service.TemplateBookmarkService.UsageView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    /**
     * EB1: één veld van de veldcatalogus, met de standaard kritiek-vlag die een nieuwe mapping erop krijgt
     * ({@link Criticality#defaultForMapping}) en of die instelbaar is (niet bij een referentieveld).
     */
    public record FieldCatalogEntryView(String code, String name, String dataType, String defaultOwner,
                                        String identityClass, String priceComponentCode, String referenceType,
                                        boolean ownerChangeable, boolean active, int sortOrder,
                                        String defaultCriticality, boolean criticalityChangeable) {
    }

    /** EB1: een revisie-eigen veld ({@link RevisionCriticalityField}) met zijn standaard kritiek-vlag. */
    public record RevisionFieldView(String fieldKey, boolean identity, String defaultCriticality,
                                    boolean criticalityChangeable) {
    }

    public record FieldCatalogView(List<FieldCatalogEntryView> fields, List<RevisionFieldView> revisionFields) {
    }

    public record SourceOrganisationRow(long id, String code, String name, String type, boolean active) {

        private static SourceOrganisationRow of(SourceOrganisation organisation) {
            return new SourceOrganisationRow(organisation.getId(), organisation.getCode(), organisation.getName(),
                    organisation.getOrganisationType().name(), organisation.isActive());
        }
    }

    /** {@code legacyOriginKind}: de gedeelde {@code OWN_DEFINITION} van een legacy-groep (S7, of een S2-rij). */
    public static final String LEGACY_ORIGIN_MIGRATED_SHARED = "MIGRATED_SHARED";
    /** {@code legacyOriginKind}: het {@code REUSABLE_TEMPLATE} van een legacy-groep ({@code template_definition_id}). */
    public static final String LEGACY_ORIGIN_MIGRATED_TEMPLATE = "MIGRATED_TEMPLATE";
    /** {@code legacyOriginKind}: de eigen kopie van een losgekoppelde interface (G2a). */
    public static final String LEGACY_ORIGIN_MIGRATED_DETACHED = "MIGRATED_DETACHED";

    /**
     * Eén importdefinitie in de lijst {@code GET /definitions}.
     * <ul>
     *   <li>{@code activeRevisionId}/{@code activeRevisionNumber}: de {@link RevisionStatus#ACTIVE}-revisie, of
     *       {@code null} als die er niet is;</li>
     *   <li>{@code draftRevisionId}/{@code draftRevisionNumber}: de {@link RevisionStatus#DRAFT}-revisie (hoogstens één,
     *       NT-13), of {@code null};</li>
     *   <li>{@code sourceOrganisationActive}: of de bronorganisatie actief is (een inactieve toont het scherm als
     *       "Inactief");</li>
     *   <li>{@code basedOnDefinitionId}: {@code import_definition.based_on_definition_id} — het sjabloon waaruit de
     *       definitie gematerialiseerd is (ook de gedeelde definitie van een legacy-groep), of de gedeelde definitie
     *       waarvan een losgekoppelde kopie stamt (G2a); {@code null} voor een definitie die nergens op gebaseerd
     *       is;</li>
     *   <li>{@code legacyOriginKind}: {@value #LEGACY_ORIGIN_MIGRATED_SHARED}, {@value #LEGACY_ORIGIN_MIGRATED_TEMPLATE}
     *       of {@value #LEGACY_ORIGIN_MIGRATED_DETACHED} volgens {@code legacy_config_definition_origin}, {@code null}
     *       voor een handmatige definitie;</li>
     *   <li>{@code legacyDefinitionCodes}: de legacy-definitiecodes van die herkomstrijen, gesorteerd en zonder dubbels;
     *       leeg voor een handmatige definitie;</li>
     *   <li>{@code overwriteCreationDefaults}: de vlag "standaarden overschrijven" (CD-B2, additief; beslissingslog
     *       2026-10-23 "Vlag 'standaarden overschrijven' op de importdefinitie").</li>
     * </ul>
     * Beslissingslog 2026-10-23 "API-uitbreiding voor de inrichtingsschermen", punt 5 (additief).
     */
    public record DefinitionRow(long id, String code, String name, String usageType, long sourceOrganisationId,
                                String sourceOrganisationCode, Long activeRevisionId, Integer activeRevisionNumber,
                                Long draftRevisionId, Integer draftRevisionNumber, boolean sourceOrganisationActive,
                                Long basedOnDefinitionId, String legacyOriginKind,
                                List<String> legacyDefinitionCodes, boolean overwriteCreationDefaults) {
    }

    /**
     * Eén revisie in de lijst {@code GET /definitions/{id}/revisions}. {@code approvedAt}/{@code approvedBy},
     * {@code changeReason} en {@code basedOnRevisionId} zijn {@code null} als ze niet ingevuld zijn (bv. een DRAFT is
     * nooit goedgekeurd). {@code fromLegacy}: de revisie heeft een eigen {@code legacy_config_revision_origin}-rij, dus
     * haar inhoud werd door een legacy-overname geschreven (een handmatige opvolger heeft die niet). Beslissingslog
     * 2026-10-23 "API-uitbreiding voor de inrichtingsschermen", punt 2 (additief).
     */
    public record RevisionRow(long id, long definitionId, int revisionNumber, String status, Instant createdAt,
                              String createdBy, Instant approvedAt, String approvedBy, String changeReason,
                              Long basedOnRevisionId, boolean fromLegacy) {

        private static RevisionRow of(ImportDefinitionRevision revision, boolean fromLegacy) {
            ImportDefinitionRevision basedOn = revision.getBasedOnRevision();
            return new RevisionRow(revision.getId(), revision.getImportDefinition().getId(),
                    revision.getRevisionNumber(), revision.getStatus().name(), revision.getCreatedAt(),
                    revision.getCreatedBy(), revision.getApprovedAt(), revision.getApprovedBy(),
                    revision.getChangeReason(), basedOn == null ? null : basedOn.getId(), fromLegacy);
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
     * <p>
     * {@code overwriteCreationDefaults} (CD-B2, additief) is de vlag van de <b>definitie</b>, niet van de revisie: elke
     * revisie van dezelfde definitie toont dezelfde, actuele waarde.
     */
    public record RevisionDetail(long id, long definitionId, int revisionNumber, String status,
                                 Long basedOnRevisionId, String changeReason,
                                 String identityProfileKind, String identitySupplierField,
                                 String identitySupplierGroupField, String identitySupplierReferenceField,
                                 String identityDiscountCodeField,
                                 String identitySupplierFixedValue, String identitySupplierGroupFixedValue,
                                 boolean identityUppercase,
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
                                 List<BookmarkValueRow> bookmarkValues, boolean overwriteCreationDefaults) {
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
    private final ImportFieldValueMapRepository valueMaps;
    private final ImportFieldTransformStepRepository transformSteps;
    private final LegacyConfigDefinitionOriginRepository definitionOrigins;
    private final LegacyConfigRevisionOriginRepository revisionOrigins;
    private final ImportFieldCatalogRepository fieldCatalog;
    private final ChainConfigurationChecks configurationChecks;

    public SetupQueryService(SourceOrganisationRepository organisations, ImportDefinitionRepository definitions,
                             ImportDefinitionRevisionRepository revisions,
                             ImportFieldMappingRepository fieldMappings, ImportRecordFilterRepository recordFilters,
                             ImportRevisionFieldCriticalityRepository fieldCriticalities,
                             ImportDefinitionBookmarkRepository bookmarks,
                             ImportDefinitionBookmarkUsageRepository bookmarkUsages,
                             ImportDefinitionBookmarkValueRepository bookmarkValues,
                             ImportFieldValueMapRepository valueMaps,
                             ImportFieldTransformStepRepository transformSteps,
                             LegacyConfigDefinitionOriginRepository definitionOrigins,
                             LegacyConfigRevisionOriginRepository revisionOrigins,
                             ImportFieldCatalogRepository fieldCatalog,
                             ChainConfigurationChecks configurationChecks) {
        this.fieldCatalog = fieldCatalog;
        this.configurationChecks = configurationChecks;
        this.definitionOrigins = definitionOrigins;
        this.revisionOrigins = revisionOrigins;
        this.valueMaps = valueMaps;
        this.transformSteps = transformSteps;
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
     * EB4: het antwoord van {@code GET /definitions/{d}/revisions/{r}/configuration-check}: wat het activeren van deze
     * revisie nu zou blokkeren, zonder te activeren en zonder bestand.
     *
     * @param activatable {@code true} precies wanneer er geen enkele {@link CheckStatus#PROBLEM} is, dus wanneer
     *                    {@code activate} zou slagen (pariteit)
     */
    public record RevisionConfigurationCheck(long revisionId, String revisionStatus, boolean activatable,
                                             List<ReadinessCheck> checks) {
    }

    /**
     * EB4 (beslissingslog 2026-10-23 "Editor-API-gaten"): de activatiecontrole van een revisie als checklist, met
     * dezelfde volgorde en codes als {@code SetupService#activateRevision}: (1) niet-DRAFT geeft enkel
     * {@code REVISION_NOT_ACTIVATABLE}; (2) alle configuratiebevindingen van
     * {@link ChainConfigurationChecks#activationProblemsAll} met valuta {@code null} (zoals de activatie, NT-14-2),
     * gevolgd door {@code INFO_CONFIG_CHECKS_SKIPPED} als er controles overgeslagen werden; (3) anders
     * {@code READY_DRAFT_ACTIVATABLE}. De eerste PROBLEM-code is de code die {@code activate} zou geven. Controles per
     * koppeling (LINK-bookmarks, valuta, taken) horen niet hier maar in de gereedheidscontrole van de koppeling.
     *
     * @throws NotFoundException {@code DEFINITION_NOT_FOUND}, {@code REVISION_NOT_FOUND} (ook een revisie van een
     *                           andere definitie)
     */
    public RevisionConfigurationCheck configurationCheck(long definitionId, long revisionId) {
        if (!definitions.existsById(definitionId)) {
            throw new NotFoundException("DEFINITION_NOT_FOUND",
                    "Import definition " + definitionId + " does not exist");
        }
        ImportDefinitionRevision revision = revisions.findById(revisionId)
                .orElseThrow(() -> new NotFoundException("REVISION_NOT_FOUND",
                        "Definition revision " + revisionId + " does not exist"));
        if (!revision.getImportDefinition().getId().equals(definitionId)) {
            throw new NotFoundException("REVISION_NOT_FOUND", "Definition revision " + revisionId
                    + " does not belong to import definition " + definitionId);
        }
        CheckSubject subject = new CheckSubject(SubjectType.REVISION, revisionId);
        List<ReadinessCheck> checks = new ArrayList<>();
        if (revision.getStatus() != RevisionStatus.DRAFT) {
            String detail = revision.getStatus() == RevisionStatus.ACTIVE
                    ? "Revision " + revisionId + " is already active"
                    : "Revision " + revisionId + " is " + revision.getStatus()
                            + "; only a DRAFT revision is activated by this setup API";
            checks.add(new ReadinessCheck("REVISION_NOT_ACTIVATABLE", CheckStatus.PROBLEM, subject, detail));
            return new RevisionConfigurationCheck(revisionId, revision.getStatus().name(), false, List.copyOf(checks));
        }
        ChainConfigurationChecks.ActivationReport activation = configurationChecks.activationProblemsAll(revision, null);
        for (ChainConfigurationChecks.ReportedProblem reported : activation.problems()) {
            ChainConfigurationChecks.Problem problem = reported.problem();
            String code = problem.code() == null || problem.code().isBlank()
                    ? ImportLinkReadinessService.CONFIG_INVALID : problem.code();
            checks.add(new ReadinessCheck(code, CheckStatus.PROBLEM, subject, problem.message(),
                    reported.fieldName(), reported.revisionField()));
        }
        if (activation.problems().isEmpty()) {
            checks.add(new ReadinessCheck(ImportLinkReadinessService.READY_DRAFT_ACTIVATABLE, CheckStatus.OK, subject,
                    null));
        }
        if (!activation.skippedBecause().isEmpty()) {
            checks.add(new ReadinessCheck(ImportLinkReadinessService.INFO_CONFIG_CHECKS_SKIPPED, CheckStatus.INFO,
                    subject, "Checks depending on " + activation.skippedBecause() + " were not evaluated", null, null,
                    activation.skippedBecause()));
        }
        return new RevisionConfigurationCheck(revisionId, revision.getStatus().name(),
                activation.problems().isEmpty(), List.copyOf(checks));
    }

    /**
     * EB1: de volledige veldcatalogus (ook inactieve velden, met {@code active=false}), oplopend op
     * {@code sortOrder} en dan {@code code}, plus de zeven revisie-eigen velden. De standaardkritiek komt uit
     * dezelfde functie als {@code ImportFieldMapping.getCriticality()} (par. 15.1); een revisieveld van de
     * identiteit is niet instelbaar.
     */
    public FieldCatalogView fieldCatalog() {
        List<FieldCatalogEntryView> fields = fieldCatalog.findAll(Sort.by("sortOrder", "code")).stream()
                .map(SetupQueryService::toView).toList();
        List<RevisionFieldView> revisionFields = java.util.Arrays.stream(RevisionCriticalityField.values())
                .map(field -> new RevisionFieldView(field.name(), field.isIdentity(),
                        field.defaultCriticality().name(), !field.isIdentity()))
                .toList();
        return new FieldCatalogView(fields, revisionFields);
    }

    private static FieldCatalogEntryView toView(ImportFieldCatalogEntry entry) {
        return new FieldCatalogEntryView(entry.getCode(), entry.getName(), entry.getDataType().name(),
                entry.getDefaultOwner().name(), entry.getIdentityClass().name(), entry.getPriceComponentCode(),
                entry.getReferenceType(), entry.isOwnerChangeable(), entry.isActive(), entry.getSortOrder(),
                Criticality.defaultForMapping(entry.getReferenceType(), entry.getPriceComponentCode()).name(),
                Criticality.changeableForMapping(entry.getReferenceType()));
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
        DefinitionPageLookups lookups = definitionPageLookups(
                result.getContent().stream().map(ImportDefinition::getId).toList());
        return PageResult.of(result, definition -> definitionRow(definition, lookups));
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
        // Eén extra query per pagina: welke revisies een eigen legacy-herkomstrij (020-4) hebben.
        List<Long> pageIds = result.getContent().stream().map(ImportDefinitionRevision::getId).toList();
        Set<Long> fromLegacy = new HashSet<>();
        if (!pageIds.isEmpty()) {
            revisionOrigins.findByRevisionIdIn(pageIds).forEach(origin -> fromLegacy.add(origin.getRevisionId()));
        }
        return PageResult.of(result, revision -> RevisionRow.of(revision, fromLegacy.contains(revision.getId())));
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
        // S8a: de vertaalrijen in één query voor de hele revisie, gekoppeld op mapping.
        Map<Long, List<ValueMapEntry>> valueMapsByMapping = new LinkedHashMap<>();
        valueMaps.findByRevisionId(revisionId).forEach(row -> valueMapsByMapping
                .computeIfAbsent(row.getMapping().getId(), id -> new ArrayList<>())
                .add(new ValueMapEntry(row.getSourceValue(), row.getTargetValue())));
        // S8c: idem voor de ketenstappen (op stapnummer, de query sorteert).
        Map<Long, List<TransformStepEntry>> stepsByMapping = new LinkedHashMap<>();
        transformSteps.findByRevisionId(revisionId).forEach(row -> stepsByMapping
                .computeIfAbsent(row.getMapping().getId(), id -> new ArrayList<>())
                .add(new TransformStepEntry(row.getTransformKind(), row.getTransformConfig())));
        List<MappingView> mappingViews = fieldMappings.findByRevisionIdWithTargetField(revisionId).stream()
                .map(mapping -> new MappingView(mapping.getId(), revisionId, mapping.getSequenceNumber(),
                        mapping.getTargetField().getCode(), mapping.getSourceReference(),
                        mapping.getValueKind().name(), mapping.getCriticality().name(),
                        valueMapsByMapping.get(mapping.getId()), stepsByMapping.get(mapping.getId()),
                        mapping.getExpectedPosition(), mapping.getFixedValue(), mapping.getDefaultValue(),
                        mapping.isRequired(), mapping.getMaxLength(), mapping.getDecimalScale(),
                        mapping.isZeroAllowed(), mapping.isNegativeAllowed(), mapping.getTransformKind().name(),
                        mapping.getTransformConfig()))
                .toList();
        List<FilterView> filterViews =
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId).stream()
                        .map(filter -> new FilterView(filter.getId(), revisionId, filter.getSequenceNumber(),
                                filter.getSourceReference(), filter.getOperator().name(),
                                filter.getCompareValue(), filter.getOutcome().name(), filter.getMatchGroup(),
                                filter.isCaseSensitive(), filter.isTrimBeforeCompare(),
                                filter.getNullBehaviour().name(), filter.getMissingColumnBehaviour().name()))
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
                revision.getIdentitySupplierFixedValue(), revision.getIdentitySupplierGroupFixedValue(),
                revision.isIdentityUppercase(),
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
                mappings, filters, fieldCriticalities, bookmarks, bookmarkValues,
                revision.getImportDefinition().isOverwriteCreationDefaults());
    }

    /**
     * Wat {@link DefinitionRow} per definitie van één pagina nodig heeft, met een vast aantal bulkqueries per pagina in
     * plaats van één (of meer) per rij.
     */
    private record DefinitionPageLookups(Map<Long, ImportDefinitionRevision> activeByDefinition,
                                         Map<Long, ImportDefinitionRevision> draftByDefinition,
                                         Map<Long, List<LegacyConfigDefinitionOrigin>> originsByDefinition,
                                         Map<Long, List<LegacyConfigDefinitionOrigin>> originsByTemplate) {
    }

    private DefinitionPageLookups definitionPageLookups(List<Long> definitionIds) {
        Map<Long, ImportDefinitionRevision> active = new HashMap<>();
        Map<Long, ImportDefinitionRevision> draft = new HashMap<>();
        Map<Long, List<LegacyConfigDefinitionOrigin>> byDefinition = new HashMap<>();
        Map<Long, List<LegacyConfigDefinitionOrigin>> byTemplate = new HashMap<>();
        if (definitionIds.isEmpty()) {
            return new DefinitionPageLookups(active, draft, byDefinition, byTemplate);
        }
        // Hoogstens één ACTIVE en één DRAFT per definitie (beide ook in de database afgedwongen).
        for (ImportDefinitionRevision revision : revisions.findByImportDefinitionIdInAndStatusIn(definitionIds,
                List.of(RevisionStatus.ACTIVE, RevisionStatus.DRAFT))) {
            Long definitionId = revision.getImportDefinition().getId();
            if (revision.getStatus() == RevisionStatus.ACTIVE) {
                active.put(definitionId, revision);
            } else {
                draft.put(definitionId, revision);
            }
        }
        definitionOrigins.findByImportDefinitionIdIn(definitionIds).forEach(origin -> byDefinition
                .computeIfAbsent(origin.getImportDefinitionId(), id -> new ArrayList<>()).add(origin));
        definitionOrigins.findByTemplateDefinitionIdIn(definitionIds).forEach(origin -> byTemplate
                .computeIfAbsent(origin.getTemplateDefinitionId(), id -> new ArrayList<>()).add(origin));
        return new DefinitionPageLookups(active, draft, byDefinition, byTemplate);
    }

    private static DefinitionRow definitionRow(ImportDefinition definition, DefinitionPageLookups lookups) {
        Long id = definition.getId();
        ImportDefinitionRevision active = lookups.activeByDefinition().get(id);
        ImportDefinitionRevision draft = lookups.draftByDefinition().get(id);
        List<LegacyConfigDefinitionOrigin> own = lookups.originsByDefinition().getOrDefault(id, List.of());
        List<LegacyConfigDefinitionOrigin> asTemplate = lookups.originsByTemplate().getOrDefault(id, List.of());
        ImportDefinition basedOn = definition.getBasedOnDefinition();
        SourceOrganisation organisation = definition.getSourceOrganisation();
        return new DefinitionRow(id, definition.getCode(), definition.getDescription(),
                definition.getUsageType().name(), organisation.getId(), organisation.getCode(),
                active == null ? null : active.getId(), active == null ? null : active.getRevisionNumber(),
                draft == null ? null : draft.getId(), draft == null ? null : draft.getRevisionNumber(),
                organisation.isActive(), basedOn == null ? null : basedOn.getId(),
                legacyOriginKind(own, asTemplate), legacyDefinitionCodes(own, asTemplate),
                definition.isOverwriteCreationDefaults());
    }

    /**
     * De soort legacy-herkomst volgens {@code legacy_config_definition_origin} (zoals {@code LegacyConfigApplyService}
     * ze vastlegt): een eigen losgekoppelde-kopierij (G2a) → {@value #LEGACY_ORIGIN_MIGRATED_DETACHED}; een eigen
     * groepsrij ({@code import_definition_id}, S2/S7) → {@value #LEGACY_ORIGIN_MIGRATED_SHARED}; sjabloon van een groep
     * ({@code template_definition_id}, S7) → {@value #LEGACY_ORIGIN_MIGRATED_TEMPLATE}; geen rij → {@code null}.
     */
    private static String legacyOriginKind(List<LegacyConfigDefinitionOrigin> own,
                                           List<LegacyConfigDefinitionOrigin> asTemplate) {
        if (own.stream().anyMatch(LegacyConfigDefinitionOrigin::isDetached)) {
            return LEGACY_ORIGIN_MIGRATED_DETACHED;
        }
        if (!own.isEmpty()) {
            return LEGACY_ORIGIN_MIGRATED_SHARED;
        }
        return asTemplate.isEmpty() ? null : LEGACY_ORIGIN_MIGRATED_TEMPLATE;
    }

    /** De komma-gescheiden {@code legacy_definition_codes} van alle betrokken herkomstrijen, gesorteerd, uniek. */
    private static List<String> legacyDefinitionCodes(List<LegacyConfigDefinitionOrigin> own,
                                                      List<LegacyConfigDefinitionOrigin> asTemplate) {
        Set<String> codes = new TreeSet<>();
        for (List<LegacyConfigDefinitionOrigin> rows : List.of(own, asTemplate)) {
            for (LegacyConfigDefinitionOrigin row : rows) {
                for (String code : row.getLegacyDefinitionCodes().split(",")) {
                    if (!code.isBlank()) {
                        codes.add(code.strip());
                    }
                }
            }
        }
        return List.copyOf(codes);
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
