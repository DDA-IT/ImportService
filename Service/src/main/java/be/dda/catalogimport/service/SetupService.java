package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import be.dda.catalogimport.domain.MissingColumnBehaviour;
import be.dda.catalogimport.domain.RevisionCriticalityField;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.support.ImportMappingConfigFactory;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import be.dda.catalogimport.service.support.ScreeningBlockedException;
import be.dda.catalogimport.service.support.SourceStructureConfig;
import be.dda.catalogimport.service.support.SourceStructureConfigFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuratie van een importketen: bronorganisatie → importdefinitie → revisie (met mappings,
 * filters en kritiek-overrules) → koppeling → taak.
 * <p>
 * <b>Waarvoor deze service bestaat.</b> Fase 1-3 bouwden het model en de verwerking, maar er is nog
 * geen beheerscherm: een keten kon tot nu toe alleen via testcode of SQL ontstaan. Deze service is de
 * ontwikkelhulp die dat met expliciete opdrachten kan doen, zodat een mens de applicatie lokaal kan
 * uitproberen.
 * <p>
 * <b>Veiligheid — lees dit vóór u ze aanzet.</b> De bijhorende REST-laag staat standaard <b>uit</b>
 * ({@code catalogimport.setup-api.enabled}, default {@code false}). Er is nog geen authenticatie
 * (Fase 5): wie de setup-API kan bereiken, kan een importdefinitie en haar drempels bepalen en dus de
 * controle op een catalogus uitschakelen. Zet ze daarom nooit aan in een omgeving met echte gegevens.
 * <p>
 * <b>Wat deze service níét doet.</b> Ze voegt geen enkele businessregel toe. Ze bewaart precies de
 * velden die het model al kent, laat de bestaande validatie het werk doen
 * ({@link SourceStructureConfigFactory}, {@link ImportMappingConfigFactory} — dezelfde validatie die
 * de screening gebruikt) en laat de databaseconstraints staan waar ze staan. Ze wijzigt niets aan het
 * gedrag van de bestaande upload- en screeningendpoints.
 * <p>
 * <b>Wanneer wordt er gevalideerd.</b> Een revisie ontstaat als {@link RevisionStatus#DRAFT} en wordt
 * dan enkel getoetst aan wat de database/entiteit al afdwingt (verplichte velden, veldlengtes). De
 * volledige configuratievalidatie gebeurt bij het toevoegen van een mapping/filter/kritiek-overrule en
 * bij {@link #activateRevision(long, String)} — het moment waarop de revisie echt bruikbaar wordt.
 * Een ongeldige configuratie levert daar {@link IllegalArgumentException} op (HTTP 400) met de
 * {@code CONFIG_*}-code van de bestaande validatie in de boodschap; de transactie rolt terug, zodat er
 * nooit een half opgeslagen definitie achterblijft.
 * <p>
 * Er wordt hier nooit een geheim, wachtwoord of bestandsinhoud bewaard of gelogd.
 */
@Service
@Transactional
public class SetupService {

    /** {@code source_organisation.code}, {@code import_definition.code}, {@code import_link.code}. */
    private static final int MAX_CODE_LENGTH = 50;
    /** {@code name}/{@code description} van organisatie, definitie, koppeling en taak. */
    private static final int MAX_NAME_LENGTH = 200;
    /** {@code import_link.library_code}. */
    private static final int MAX_LIBRARY_CODE_LENGTH = 20;
    /** {@code created_by}/{@code approved_by}. */
    private static final int MAX_USER_LENGTH = 100;
    /** {@code identity_*_field}, {@code record_*_field}, {@code source_reference}. */
    private static final int MAX_FIELD_REFERENCE_LENGTH = 200;
    /** Wie een rij aanmaakte wanneer de aanroeper niets meegeeft; nooit een echte gebruikersnaam. */
    private static final String DEFAULT_CREATED_BY = "setup-api";

    // --- Opdrachten (request) --------------------------------------------------------------------

    /** @param type {@code SUPPLIER} of {@code PURCHASING_ASSOCIATION} */
    public record CreateSourceOrganisationCommand(String code, String name, SourceOrganisationType type) {
    }

    /** @param usageType {@code null} betekent {@link DefinitionUsageType#OWN_DEFINITION} */
    public record CreateDefinitionCommand(String sourceOrganisationCode, String code, String name,
                                          DefinitionUsageType usageType) {
    }

    /**
     * De inhoud van één nieuwe revisie. Elk veld dat {@code null} blijft, houdt de bestaande default
     * van {@link ImportDefinitionRevision} — er wordt nooit stil een andere waarde gekozen.
     */
    public record CreateRevisionCommand(String delimiter, String quoteChar, String charset, Boolean hasHeader,
                                        Integer headerLineNumber, String fieldReferenceKind,
                                        Integer expectedColumnCount, IdentityProfileKind identityProfileKind,
                                        String supplierField, String supplierGroupField,
                                        String supplierReferenceField, String discountCodeField,
                                        String basePriceField, String descriptionField, String currencyField,
                                        Integer canonicalisationVersion,
                                        BigDecimal creationThresholdSharePercent,
                                        BigDecimal maxCriticalSharePercent,
                                        BigDecimal maxRejectedSharePercent,
                                        BigDecimal bulkIncidentSharePercent,
                                        BigDecimal priceDeviationPercent,
                                        RowIssueSeverity priceDeviationSeverity,
                                        Boolean basePriceZeroAllowed, Boolean basePriceNegativeAllowed,
                                        BigDecimal priceDerivationTolerance, String changeReason,
                                        String createdBy) {
    }

    /**
     * Eén veldmapping. Type, eigenaar, identiteitsklasse, prijscomponent en referentietype komen uit
     * de veldcatalogus en worden bewust <b>niet</b> door de aanroeper gezet: een mapping die haar
     * catalogusrij tegenspreekt is altijd een configuratiefout.
     */
    public record CreateMappingCommand(String targetFieldCode, String sourceReference, Integer sequenceNumber,
                                       FieldValueKind valueKind, Integer expectedPosition, String fixedValue,
                                       String defaultValue, Boolean required, Integer maxLength,
                                       Integer decimalScale, Boolean zeroAllowed, Boolean negativeAllowed,
                                       FieldTransformKind transformKind, String transformConfig,
                                       Criticality criticality, String createdBy) {
    }

    public record CreateFilterCommand(Integer sequenceNumber, String sourceReference, FilterOperator operator,
                                      String compareValue, FilterOutcome outcome, Boolean caseSensitive,
                                      Boolean trimBeforeCompare, FilterNullBehaviour nullBehaviour,
                                      MissingColumnBehaviour missingColumnBehaviour, String createdBy) {
    }

    /** @param fieldKey een sleutel uit {@link RevisionCriticalityField} */
    public record CreateFieldCriticalityCommand(String fieldKey, Criticality criticality, String createdBy) {
    }

    public record CreateLinkCommand(Long definitionId, String code, String name, String supplierCode,
                                    String libraryCode, String librarySearchSupplierCode) {
    }

    /** De taak is altijd {@link TaskTriggerType#MANUAL}: alleen daarop is een upload toegelaten. */
    public record CreateTaskCommand(Long linkId, String name, Boolean preventConcurrentRuns) {
    }

    // --- Antwoorden (response) -------------------------------------------------------------------

    public record SourceOrganisationView(long id, String code, String name, String type, boolean active) {
    }

    public record DefinitionView(long id, String code, String name, String usageType,
                                 long sourceOrganisationId, String sourceOrganisationCode) {
    }

    public record RevisionView(long id, long definitionId, int revisionNumber, String status,
                               String identityProfileKind, String delimiter, boolean hasHeader,
                               String fieldReferenceKind, int canonicalisationVersion,
                               String supplierField, String supplierGroupField, String supplierReferenceField,
                               String discountCodeField, String basePriceField, String descriptionField,
                               String currencyField, BigDecimal creationThresholdSharePercent,
                               BigDecimal maxCriticalSharePercent, BigDecimal maxRejectedSharePercent,
                               BigDecimal bulkIncidentSharePercent) {
    }

    public record MappingView(long id, long revisionId, int sequenceNumber, String targetFieldCode,
                              String sourceReference, String valueKind, String criticality) {
    }

    public record FilterView(long id, long revisionId, int sequenceNumber, String sourceReference,
                             String operator, String compareValue, String outcome) {
    }

    public record FieldCriticalityView(long revisionId, String fieldKey, String criticality) {
    }

    public record LinkView(long id, String code, String name, long definitionId, String supplierCode,
                           String libraryCode, boolean active) {
    }

    public record TaskView(long id, long linkId, String name, String triggerType, boolean active,
                           boolean preventConcurrentRuns) {
    }

    /** Het overzicht dat toont welke {@code taskId} bij welke keten hoort. */
    public record Overview(List<OrganisationOverview> sourceOrganisations) {
    }

    public record OrganisationOverview(long id, String code, String name, String type,
                                       List<DefinitionOverview> definitions) {
    }

    public record DefinitionOverview(long id, String code, String name, String usageType,
                                     RevisionView activeRevision, List<RevisionSummary> revisions,
                                     List<LinkOverview> links) {
    }

    public record RevisionSummary(long id, int revisionNumber, String status) {
    }

    public record LinkOverview(long id, String code, String name, String libraryCode, String supplierCode,
                               boolean active, List<TaskView> tasks) {
    }

    private final SourceOrganisationRepository organisations;
    private final ImportDefinitionRepository definitions;
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportFieldCatalogRepository fieldCatalog;
    private final ImportFieldMappingRepository fieldMappings;
    private final ImportRecordFilterRepository recordFilters;
    private final ImportRevisionFieldCriticalityRepository fieldCriticalities;
    private final ImportLinkRepository links;
    private final CatalogImportTaskRepository tasks;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkValueRepository bookmarkValues;
    private final SourceStructureConfigFactory structureFactory;
    private final ImportMappingConfigFactory mappingFactory;

    public SetupService(SourceOrganisationRepository organisations, ImportDefinitionRepository definitions,
                        ImportDefinitionRevisionRepository revisions, ImportFieldCatalogRepository fieldCatalog,
                        ImportFieldMappingRepository fieldMappings, ImportRecordFilterRepository recordFilters,
                        ImportRevisionFieldCriticalityRepository fieldCriticalities, ImportLinkRepository links,
                        CatalogImportTaskRepository tasks, ImportDefinitionBookmarkRepository bookmarks,
                        ImportDefinitionBookmarkValueRepository bookmarkValues,
                        SourceStructureConfigFactory structureFactory,
                        ImportMappingConfigFactory mappingFactory) {
        this.organisations = organisations;
        this.definitions = definitions;
        this.revisions = revisions;
        this.fieldCatalog = fieldCatalog;
        this.fieldMappings = fieldMappings;
        this.recordFilters = recordFilters;
        this.fieldCriticalities = fieldCriticalities;
        this.links = links;
        this.tasks = tasks;
        this.bookmarks = bookmarks;
        this.bookmarkValues = bookmarkValues;
        this.structureFactory = structureFactory;
        this.mappingFactory = mappingFactory;
    }

    // --- Bronorganisatie --------------------------------------------------------------------------

    /**
     * @throws ConflictException        {@code SOURCE_ORGANISATION_CODE_IN_USE}
     * @throws IllegalArgumentException ontbrekende of te lange velden
     */
    public SourceOrganisationView createSourceOrganisation(CreateSourceOrganisationCommand command) {
        String code = requireText(command.code(), "code", MAX_CODE_LENGTH);
        String name = requireText(command.name(), "name", MAX_NAME_LENGTH);
        SourceOrganisationType type = require(command.type(), "type");
        if (organisations.existsByCode(code)) {
            throw new ConflictException("SOURCE_ORGANISATION_CODE_IN_USE",
                    "Source organisation code '" + code + "' already exists");
        }
        SourceOrganisation stored = organisations.saveAndFlush(new SourceOrganisation(code, name, type));
        return view(stored);
    }

    // --- Importdefinitie --------------------------------------------------------------------------

    /**
     * @throws NotFoundException        {@code SOURCE_ORGANISATION_NOT_FOUND}
     * @throws ConflictException        {@code DEFINITION_CODE_IN_USE}
     * @throws IllegalArgumentException ontbrekende of te lange velden
     */
    public DefinitionView createDefinition(CreateDefinitionCommand command) {
        String code = requireText(command.code(), "code", MAX_CODE_LENGTH);
        String name = requireText(command.name(), "name", MAX_NAME_LENGTH);
        SourceOrganisation organisation = organisation(command.sourceOrganisationCode());
        if (definitions.findBySourceOrganisationIdAndCode(organisation.getId(), code).isPresent()) {
            throw new ConflictException("DEFINITION_CODE_IN_USE", "Definition code '" + code
                    + "' already exists for source organisation '" + organisation.getCode() + "'");
        }
        ImportDefinition definition = new ImportDefinition(organisation, code, name, DEFAULT_CREATED_BY);
        if (command.usageType() != null) {
            definition.setUsageType(command.usageType());
        }
        return view(definitions.saveAndFlush(definition));
    }

    // --- Revisie ----------------------------------------------------------------------------------

    /**
     * Maakt een nieuwe {@link RevisionStatus#DRAFT}-revisie met het eerstvolgende revisienummer.
     *
     * @throws NotFoundException        {@code DEFINITION_NOT_FOUND}
     * @throws IllegalArgumentException ontbrekende of ongeldige velden
     */
    public RevisionView createRevision(long definitionId, CreateRevisionCommand command) {
        ImportDefinition definition = definitions.findById(definitionId)
                .orElseThrow(() -> new NotFoundException("DEFINITION_NOT_FOUND",
                        "Import definition " + definitionId + " does not exist"));
        IdentityProfileKind identityKind = command.identityProfileKind() == null
                ? IdentityProfileKind.THREE_PART : command.identityProfileKind();
        int revisionNumber = revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(definitionId).stream()
                .mapToInt(ImportDefinitionRevision::getRevisionNumber).max().orElse(0) + 1;

        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, revisionNumber,
                identityKind, requireText(orDefault(command.createdBy(), DEFAULT_CREATED_BY), "createdBy",
                MAX_USER_LENGTH));
        revision.setIdentitySupplierField(
                requireText(command.supplierField(), "supplierField", MAX_FIELD_REFERENCE_LENGTH));
        revision.setIdentitySupplierGroupField(
                requireText(command.supplierGroupField(), "supplierGroupField", MAX_FIELD_REFERENCE_LENGTH));
        revision.setIdentitySupplierReferenceField(requireText(command.supplierReferenceField(),
                "supplierReferenceField", MAX_FIELD_REFERENCE_LENGTH));
        // De databasecheck ck_import_definition_revision_identity houdt profiel en kortingscodeveld
        // consistent: null (niet gemapt) en "" (expliciet leeg) zijn verschillende toestanden.
        String discountCodeField = optionalText(command.discountCodeField(), "discountCodeField",
                MAX_FIELD_REFERENCE_LENGTH);
        if (identityKind == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE && discountCodeField == null) {
            throw new IllegalArgumentException("identityProfileKind FOUR_PART_WITH_DISCOUNT_CODE requires "
                    + "discountCodeField");
        }
        if (identityKind == IdentityProfileKind.THREE_PART && discountCodeField != null) {
            throw new IllegalArgumentException("identityProfileKind THREE_PART must not carry a "
                    + "discountCodeField; use FOUR_PART_WITH_DISCOUNT_CODE when the discount code is mapped");
        }
        revision.setIdentityDiscountCodeField(discountCodeField);
        revision.setRecordBasePriceField(
                requireText(command.basePriceField(), "basePriceField", MAX_FIELD_REFERENCE_LENGTH));
        revision.setRecordDescriptionField(
                optionalText(command.descriptionField(), "descriptionField", MAX_FIELD_REFERENCE_LENGTH));
        revision.setRecordCurrencyField(
                optionalText(command.currencyField(), "currencyField", MAX_FIELD_REFERENCE_LENGTH));
        revision.setStructureDelimiter(requireText(command.delimiter(), "delimiter", 1));
        if (command.quoteChar() != null) {
            // "" betekent hier uitdrukkelijk: deze bron kent geen quoting.
            revision.setStructureQuoteChar(command.quoteChar().isEmpty() ? null
                    : requireText(command.quoteChar(), "quoteChar", 1));
        }
        if (command.charset() != null) {
            revision.setStructureCharset(requireText(command.charset(), "charset", 40));
        }
        if (command.hasHeader() != null) {
            revision.setStructureHasHeader(command.hasHeader());
        }
        if (command.headerLineNumber() != null) {
            revision.setStructureHeaderLineNumber(command.headerLineNumber());
        }
        if (command.fieldReferenceKind() != null) {
            revision.setStructureFieldReferenceKind(
                    requireText(command.fieldReferenceKind(), "fieldReferenceKind", 20));
        }
        revision.setStructureExpectedColumnCount(command.expectedColumnCount());
        if (command.canonicalisationVersion() != null) {
            revision.setRecordCanonicalisationVersion(command.canonicalisationVersion());
        }
        applyThresholds(revision, command);
        revision.setChangeReason(optionalText(command.changeReason(), "changeReason", 500));
        // Bouwstap 5c: dezelfde vier regels als voorheen, nu als één gedeelde berekening. De
        // materialisatiewizard moet exact dezelfde hashes op een afgeleide revisie kunnen zetten; twee
        // kopieën van deze opbouw zouden op termijn uit elkaar lopen (zie RevisionConfigHashes#applyAll).
        RevisionConfigHashes.applyAll(revision);
        return view(revisions.saveAndFlush(revision));
    }

    /**
     * Thresholds zijn altijd een percentage (beslissingslog 20/09). {@code null} laat de bestaande
     * default staan; een negatief percentage wordt geweigerd in plaats van stil op 0 gezet.
     */
    private static void applyThresholds(ImportDefinitionRevision revision, CreateRevisionCommand command) {
        if (command.creationThresholdSharePercent() != null) {
            revision.setCreationThresholdSharePercent(
                    requireNotNegative(command.creationThresholdSharePercent(), "creationThresholdSharePercent"));
        }
        if (command.maxCriticalSharePercent() != null) {
            revision.setMaxCriticalSharePercent(
                    requireNotNegative(command.maxCriticalSharePercent(), "maxCriticalSharePercent"));
        }
        if (command.maxRejectedSharePercent() != null) {
            revision.setMaxRejectedSharePercent(
                    requireNotNegative(command.maxRejectedSharePercent(), "maxRejectedSharePercent"));
        }
        if (command.bulkIncidentSharePercent() != null) {
            revision.setBulkIncidentSharePercent(
                    requireNotNegative(command.bulkIncidentSharePercent(), "bulkIncidentSharePercent"));
        }
        if (command.priceDeviationPercent() != null) {
            revision.setPriceDeviationPercent(
                    requireNotNegative(command.priceDeviationPercent(), "priceDeviationPercent"));
        }
        if (command.priceDeviationSeverity() != null) {
            revision.setPriceDeviationSeverity(command.priceDeviationSeverity());
        }
        if (command.priceDerivationTolerance() != null) {
            revision.setPriceDerivationTolerance(
                    requireNotNegative(command.priceDerivationTolerance(), "priceDerivationTolerance"));
        }
        if (command.basePriceZeroAllowed() != null) {
            revision.setBasePriceZeroAllowed(command.basePriceZeroAllowed());
        }
        if (command.basePriceNegativeAllowed() != null) {
            revision.setBasePriceNegativeAllowed(command.basePriceNegativeAllowed());
        }
    }

    /**
     * Zet de revisie op {@link RevisionStatus#ACTIVE} en de vorige actieve revisie van dezelfde
     * definitie op {@link RevisionStatus#SUPERSEDED}.
     * <p>
     * De oude revisie wordt eerst weggeschreven <b>en geflushed</b>: {@code active_marker} is
     * {@code TRUE} bij ACTIVE en {@code null} daarbuiten, en {@code uk_import_definition_revision_active}
     * zou anders binnen dezelfde transactie twee actieve revisies zien.
     * <p>
     * De volledige configuratie wordt hier nog eens gevalideerd met exact dezelfde fabrieken als de
     * screening: een revisie die pas bij de eerste levering blijkt te blokkeren, is onbruikbaar.
     *
     * @throws NotFoundException        {@code REVISION_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_ACTIVATABLE} (al ACTIVE of niet meer DRAFT),
     *                                  {@code CONFIG_REQUIRED_BOOKMARK_MISSING} (een verplichte
     *                                  DEFINITION-bookmark van deze revisie is niet ingevuld)
     * @throws IllegalArgumentException een {@code CONFIG_*}-fout in de configuratie
     */
    public RevisionView activateRevision(long revisionId, String approvedBy) {
        ImportDefinitionRevision revision = revision(revisionId);
        if (revision.getStatus() == RevisionStatus.ACTIVE) {
            throw new ConflictException("REVISION_NOT_ACTIVATABLE",
                    "Revision " + revisionId + " is already active");
        }
        if (revision.getStatus() != RevisionStatus.DRAFT) {
            throw new ConflictException("REVISION_NOT_ACTIVATABLE", "Revision " + revisionId + " is "
                    + revision.getStatus() + "; only a DRAFT revision is activated by this setup API");
        }
        validateConfiguration(revision);
        requireDefinitionBookmarkValues(revision);
        long definitionId = revision.getImportDefinition().getId();
        Optional<ImportDefinitionRevision> current =
                revisions.findByImportDefinitionIdAndStatus(definitionId, RevisionStatus.ACTIVE);
        current.ifPresent(active -> {
            active.setStatus(RevisionStatus.SUPERSEDED);
            revisions.saveAndFlush(active);
        });
        revision.setStatus(RevisionStatus.ACTIVE);
        revision.setApprovedAt(Instant.now());
        revision.setApprovedBy(requireText(orDefault(approvedBy, DEFAULT_CREATED_BY), "approvedBy",
                MAX_USER_LENGTH));
        return view(revisions.saveAndFlush(revision));
    }

    // --- Mappings, filters en kritiek-overrules ---------------------------------------------------

    /**
     * @throws NotFoundException        {@code REVISION_NOT_FOUND}, {@code FIELD_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_EDITABLE} (alleen een DRAFT is bewerkbaar)
     * @throws IllegalArgumentException een {@code CONFIG_*}-fout in de configuratie
     */
    public MappingView addMapping(long revisionId, CreateMappingCommand command) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        String targetFieldCode = requireText(command.targetFieldCode(), "targetFieldCode", 60);
        ImportFieldCatalogEntry target = fieldCatalog.findById(targetFieldCode)
                .orElseThrow(() -> new NotFoundException("FIELD_NOT_FOUND",
                        "Target field '" + targetFieldCode + "' does not exist in the field catalogue"));
        List<ImportFieldMapping> existing = fieldMappings.findByRevisionIdWithTargetField(revisionId);
        int sequenceNumber = command.sequenceNumber() != null ? command.sequenceNumber()
                : existing.stream().mapToInt(ImportFieldMapping::getSequenceNumber).max().orElse(0) + 1;
        // Zonder deze twee controles zou uk_import_field_mapping_target/-_sequence pas bij het flushen
        // toeslaan: een databasefout in plaats van een leesbaar antwoord.
        if (existing.stream().anyMatch(row -> targetFieldCode.equals(row.getTargetField().getCode()))) {
            throw new ConflictException("MAPPING_TARGET_IN_USE", "Target field '" + targetFieldCode
                    + "' is already mapped in revision " + revisionId
                    + "; a target field has exactly one source");
        }
        if (existing.stream().anyMatch(row -> row.getSequenceNumber() == sequenceNumber)) {
            throw new ConflictException("MAPPING_SEQUENCE_IN_USE", "Sequence number " + sequenceNumber
                    + " is already used in revision " + revisionId);
        }
        // Type, eigenaar, identiteitsklasse, prijscomponent en referentietype komen uit de catalogus.
        ImportFieldMapping mapping = new ImportFieldMapping(revision, sequenceNumber, target,
                orDefault(command.valueKind(), FieldValueKind.SOURCE_FIELD), target.getDataType(),
                target.getDefaultOwner(), target.getIdentityClass());
        mapping.setPriceComponentCode(target.getPriceComponentCode());
        mapping.setReferenceType(target.getReferenceType());
        mapping.setSourceReference(
                optionalText(command.sourceReference(), "sourceReference", MAX_FIELD_REFERENCE_LENGTH));
        mapping.setExpectedPosition(command.expectedPosition());
        mapping.setFixedValue(optionalText(command.fixedValue(), "fixedValue", 500));
        mapping.setDefaultValue(optionalText(command.defaultValue(), "defaultValue", 500));
        mapping.setRequired(Boolean.TRUE.equals(command.required()));
        mapping.setMaxLength(command.maxLength());
        mapping.setDecimalScale(command.decimalScale());
        mapping.setZeroAllowed(Boolean.TRUE.equals(command.zeroAllowed()));
        mapping.setNegativeAllowed(Boolean.TRUE.equals(command.negativeAllowed()));
        if (command.transformKind() != null) {
            mapping.setTransformKind(command.transformKind());
        }
        mapping.setTransformConfig(optionalText(command.transformConfig(), "transformConfig", 1000));
        mapping.setCriticality(command.criticality());
        mapping.setCreatedBy(orDefault(command.createdBy(), DEFAULT_CREATED_BY));
        ImportFieldMapping stored = fieldMappings.saveAndFlush(mapping);
        validateConfiguration(revision);
        return new MappingView(stored.getId(), revisionId, stored.getSequenceNumber(), targetFieldCode,
                stored.getSourceReference(), stored.getValueKind().name(), stored.getCriticality().name());
    }

    /**
     * @throws NotFoundException        {@code REVISION_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_EDITABLE}
     * @throws IllegalArgumentException een {@code CONFIG_*}-fout in de configuratie
     */
    public FilterView addFilter(long revisionId, CreateFilterCommand command) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        List<ImportRecordFilter> existing =
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId);
        int sequenceNumber = command.sequenceNumber() != null ? command.sequenceNumber()
                : existing.stream().mapToInt(ImportRecordFilter::getSequenceNumber).max().orElse(0) + 1;
        if (existing.stream().anyMatch(row -> row.getSequenceNumber() == sequenceNumber)) {
            throw new ConflictException("FILTER_SEQUENCE_IN_USE", "Filter sequence number " + sequenceNumber
                    + " is already used in revision " + revisionId + "; the evaluation order would be "
                    + "undefined");
        }
        ImportRecordFilter filter = new ImportRecordFilter(revision, sequenceNumber,
                requireText(command.sourceReference(), "sourceReference", MAX_FIELD_REFERENCE_LENGTH),
                require(command.operator(), "operator"),
                requireText(command.compareValue(), "compareValue", 500),
                require(command.outcome(), "outcome"));
        if (command.caseSensitive() != null) {
            filter.setCaseSensitive(command.caseSensitive());
        }
        if (command.trimBeforeCompare() != null) {
            filter.setTrimBeforeCompare(command.trimBeforeCompare());
        }
        if (command.nullBehaviour() != null) {
            filter.setNullBehaviour(command.nullBehaviour());
        }
        if (command.missingColumnBehaviour() != null) {
            filter.setMissingColumnBehaviour(command.missingColumnBehaviour());
        }
        filter.setCreatedBy(orDefault(command.createdBy(), DEFAULT_CREATED_BY));
        ImportRecordFilter stored = recordFilters.saveAndFlush(filter);
        validateConfiguration(revision);
        return new FilterView(stored.getId(), revisionId, stored.getSequenceNumber(),
                stored.getSourceReference(), stored.getOperator().name(), stored.getCompareValue(),
                stored.getOutcome().name());
    }

    /**
     * @throws NotFoundException        {@code REVISION_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_EDITABLE}
     * @throws IllegalArgumentException onbekende veldsleutel of een {@code CONFIG_*}-fout (bv. een
     *                                  identiteitsveld dat niet kritiek zou zijn)
     */
    public FieldCriticalityView addFieldCriticality(long revisionId, CreateFieldCriticalityCommand command) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        String fieldKey = requireText(command.fieldKey(), "fieldKey", 60);
        RevisionCriticalityField field = RevisionCriticalityField.byKey(fieldKey)
                .orElseThrow(() -> new IllegalArgumentException("fieldKey '" + fieldKey + "' is not a field "
                        + "of the revision itself; known keys are "
                        + List.of(RevisionCriticalityField.values())));
        Criticality criticality = require(command.criticality(), "criticality");
        // Dezelfde regels als de databasecheck en de configuratievalidatie, maar vóór het flushen: een
        // constraintfout zou hier een 500 opleveren in plaats van een leesbaar antwoord.
        if (field.isIdentity() && criticality == Criticality.NON_CRITICAL) {
            throw new IllegalArgumentException("Field '" + fieldKey + "' is part of the offer identity and "
                    + "can never be " + Criticality.NON_CRITICAL);
        }
        if (fieldCriticalities.findByDefinitionRevisionId(revisionId).stream()
                .anyMatch(existing -> fieldKey.equals(existing.getFieldKey()))) {
            throw new ConflictException("FIELD_CRITICALITY_IN_USE", "Criticality of '" + fieldKey
                    + "' is already configured for revision " + revisionId
                    + "; a field has exactly one criticality");
        }
        ImportRevisionFieldCriticality row = new ImportRevisionFieldCriticality(revisionId, fieldKey,
                criticality);
        row.setCreatedBy(orDefault(command.createdBy(), DEFAULT_CREATED_BY));
        ImportRevisionFieldCriticality stored = fieldCriticalities.saveAndFlush(row);
        validateConfiguration(revision);
        return new FieldCriticalityView(revisionId, stored.getFieldKey(), stored.getCriticality().name());
    }

    // --- Koppeling en taak -------------------------------------------------------------------------

    /**
     * @throws NotFoundException        {@code DEFINITION_NOT_FOUND}, {@code SOURCE_ORGANISATION_NOT_FOUND}
     * @throws ConflictException        {@code LINK_CODE_IN_USE}, {@code LINK_SCOPE_IN_USE}
     * @throws IllegalArgumentException ontbrekende of te lange velden
     */
    public LinkView createLink(CreateLinkCommand command) {
        Long definitionId = require(command.definitionId(), "definitionId");
        ImportDefinition definition = definitions.findById(definitionId)
                .orElseThrow(() -> new NotFoundException("DEFINITION_NOT_FOUND",
                        "Import definition " + definitionId + " does not exist"));
        String code = requireText(command.code(), "code", MAX_CODE_LENGTH);
        String name = requireText(command.name(), "name", MAX_NAME_LENGTH);
        String libraryCode = requireText(command.libraryCode(), "libraryCode", MAX_LIBRARY_CODE_LENGTH);
        SourceOrganisation supplier = organisation(command.supplierCode());
        if (links.findByCode(code).isPresent()) {
            throw new ConflictException("LINK_CODE_IN_USE", "Import link code '" + code + "' already exists");
        }
        boolean scopeTaken = links.findByImportDefinitionId(definitionId).stream()
                .anyMatch(existing -> existing.getLibraryCode().equals(libraryCode)
                        && existing.getSupplierOrganisation().getId().equals(supplier.getId()));
        if (scopeTaken) {
            throw new ConflictException("LINK_SCOPE_IN_USE", "This definition already has a link for supplier '"
                    + supplier.getCode() + "' and library '" + libraryCode + "'");
        }
        ImportLink link = new ImportLink(code, name, definition, supplier, libraryCode);
        link.setLibrarySearchSupplierCode(
                optionalText(command.librarySearchSupplierCode(), "librarySearchSupplierCode", MAX_CODE_LENGTH));
        return view(links.saveAndFlush(link));
    }

    /**
     * @throws NotFoundException        {@code LINK_NOT_FOUND}
     * @throws ConflictException        {@code TASK_NAME_IN_USE}
     * @throws IllegalArgumentException ontbrekende of te lange velden
     */
    public TaskView createTask(CreateTaskCommand command) {
        Long linkId = require(command.linkId(), "linkId");
        ImportLink link = links.findById(linkId)
                .orElseThrow(() -> new NotFoundException("LINK_NOT_FOUND",
                        "Import link " + linkId + " does not exist"));
        String name = requireText(command.name(), "name", MAX_NAME_LENGTH);
        if (tasks.findByImportLinkIdAndName(linkId, name).isPresent()) {
            throw new ConflictException("TASK_NAME_IN_USE",
                    "Task '" + name + "' already exists for import link " + linkId);
        }
        CatalogImportTask task = new CatalogImportTask(link, name, TaskTriggerType.MANUAL);
        if (command.preventConcurrentRuns() != null) {
            task.setPreventConcurrentRuns(command.preventConcurrentRuns());
        }
        return view(tasks.saveAndFlush(task));
    }

    // --- Overzicht ---------------------------------------------------------------------------------

    /** Alles wat er geconfigureerd staat, met de id's die de upload nodig heeft. */
    @Transactional(readOnly = true)
    public Overview overview() {
        List<OrganisationOverview> rows = organisations.findAll().stream()
                .sorted((left, right) -> left.getCode().compareTo(right.getCode()))
                .map(organisation -> new OrganisationOverview(organisation.getId(), organisation.getCode(),
                        organisation.getName(), organisation.getOrganisationType().name(),
                        definitionOverviews(organisation)))
                .toList();
        return new Overview(rows);
    }

    private List<DefinitionOverview> definitionOverviews(SourceOrganisation organisation) {
        return definitions.findBySourceOrganisationId(organisation.getId()).stream()
                .map(definition -> new DefinitionOverview(definition.getId(), definition.getCode(),
                        definition.getDescription(), definition.getUsageType().name(),
                        revisions.findByImportDefinitionIdAndStatus(definition.getId(), RevisionStatus.ACTIVE)
                                .map(SetupService::view).orElse(null),
                        revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(definition.getId()).stream()
                                .map(revision -> new RevisionSummary(revision.getId(),
                                        revision.getRevisionNumber(), revision.getStatus().name()))
                                .toList(),
                        linkOverviews(definition)))
                .toList();
    }

    private List<LinkOverview> linkOverviews(ImportDefinition definition) {
        return links.findByImportDefinitionId(definition.getId()).stream()
                .map(link -> new LinkOverview(link.getId(), link.getCode(), link.getName(),
                        link.getLibraryCode(), link.getSupplierOrganisation().getCode(), link.isActive(),
                        tasks.findAll().stream()
                                .filter(task -> task.getImportLink().getId().equals(link.getId()))
                                .map(SetupService::view)
                                .toList()))
                .toList();
    }

    // --- Validatie en vertaling ---------------------------------------------------------------------

    /**
     * Valideert de volledige configuratie met dezelfde fabrieken als de screening. Een
     * {@link ScreeningBlockedException} is hier geen leveringsblokkade maar een ongeldige aanvraag:
     * ze wordt vertaald naar een 400 met de {@code CONFIG_*}-code in de boodschap, en de omringende
     * transactie rolt terug.
     */
    /**
     * Blokkeerpunt bij het activeren (beslissingslog 23/09 keuze 6, ontwerp §7): elke <b>verplichte</b>
     * {@link BookmarkValueScope#DEFINITION}-bookmark van deze revisie moet een
     * {@code import_definition_bookmark_value}-rij met een niet-lege waarde hebben, anders 409
     * {@code CONFIG_REQUIRED_BOOKMARK_MISSING}. Een revisie die live gaat met een open invulveld zou
     * dat veld stil leeg toepassen op elke levering die erop draait.
     * <p>
     * <b>{@code ""} bevredigt een verplichte bookmark niet</b> (R-BMK-03/R-VAL-04): afwezigheid van een
     * rij is "niet ingevuld", een lege waarde is "uitdrukkelijk leeg" — voor een verplicht veld is geen
     * van beide een invulling.
     * <p>
     * <b>LINK-scope wordt hier niet beoordeeld</b>: op dit moment bestaat er nog geen of meer dan één
     * koppeling. Die controle gebeurt bij de start van een levering
     * ({@code DeliveryIntakeService.resolve}).
     * <p>
     * <b>Een sjabloon wordt overgeslagen.</b> Een {@link DefinitionUsageType#REUSABLE_TEMPLATE} is per
     * definitie een blauwdruk: zijn DEFINITION-bookmarks worden pas bij materialisatie ingevuld
     * (§14.16 stap 4, R-MAT-02) en de waarderijen ontstaan op de <i>afgeleide</i> revisie. Zou de
     * controle hier ook op een sjabloon slaan, dan kon een sjabloon met een verplichte
     * DEFINITION-bookmark nooit {@code ACTIVE} worden en dus nooit gematerialiseerd worden (fase A3/A4
     * eist een niet-{@code DRAFT} sjabloonrevisie) — het mechanisme zou zichzelf blokkeren. Zie de
     * levenscyclus in ontwerp §8: dit blokkeerpunt staat op de afgeleide revisie, niet op het sjabloon.
     */
    private void requireDefinitionBookmarkValues(ImportDefinitionRevision revision) {
        if (revision.getImportDefinition().getUsageType() == DefinitionUsageType.REUSABLE_TEMPLATE) {
            return;
        }
        List<String> missing = bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(revision.getId()).stream()
                .filter(bookmark -> bookmark.getValueScope() == BookmarkValueScope.DEFINITION)
                .filter(ImportDefinitionBookmark::isRequired)
                .map(ImportDefinitionBookmark::getName)
                .filter(name -> !hasDefinitionBookmarkValue(revision.getId(), name))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new ConflictException("CONFIG_REQUIRED_BOOKMARK_MISSING", "Revision " + revision.getId()
                    + " declares required DEFINITION bookmark(s) " + missing + " without a value");
        }
    }

    private boolean hasDefinitionBookmarkValue(long revisionId, String bookmarkName) {
        return bookmarkValues.findByDefinitionRevisionIdAndBookmarkName(revisionId, bookmarkName)
                .map(ImportDefinitionBookmarkValue::getValueText)
                .filter(value -> !value.isBlank())
                .isPresent();
    }

    private void validateConfiguration(ImportDefinitionRevision revision) {
        try {
            SourceStructureConfig structure = structureFactory.from(revision);
            mappingFactory.from(revision, structure);
        } catch (ScreeningBlockedException invalid) {
            throw new IllegalArgumentException(invalid.getCode() + ": " + invalid.getMessage(), invalid);
        }
    }

    private ImportDefinitionRevision revision(long revisionId) {
        return revisions.findById(revisionId)
                .orElseThrow(() -> new NotFoundException("REVISION_NOT_FOUND",
                        "Definition revision " + revisionId + " does not exist"));
    }

    /** Een bevroren revisie wordt nooit bijgewerkt (§14.14): enkel een DRAFT is bewerkbaar. */
    private ImportDefinitionRevision editableRevision(long revisionId) {
        ImportDefinitionRevision revision = revision(revisionId);
        if (revision.getStatus() != RevisionStatus.DRAFT) {
            throw new ConflictException("REVISION_NOT_EDITABLE", "Revision " + revisionId + " is "
                    + revision.getStatus() + "; a frozen revision is never changed, make a new revision");
        }
        return revision;
    }

    private SourceOrganisation organisation(String code) {
        String wanted = requireText(code, "sourceOrganisationCode", MAX_CODE_LENGTH);
        return organisations.findByCode(wanted)
                .orElseThrow(() -> new NotFoundException("SOURCE_ORGANISATION_NOT_FOUND",
                        "Source organisation '" + wanted + "' does not exist"));
    }

    // --- Omzetting naar antwoorden --------------------------------------------------------------------

    private static SourceOrganisationView view(SourceOrganisation organisation) {
        return new SourceOrganisationView(organisation.getId(), organisation.getCode(), organisation.getName(),
                organisation.getOrganisationType().name(), organisation.isActive());
    }

    private static DefinitionView view(ImportDefinition definition) {
        return new DefinitionView(definition.getId(), definition.getCode(), definition.getDescription(),
                definition.getUsageType().name(), definition.getSourceOrganisation().getId(),
                definition.getSourceOrganisation().getCode());
    }

    private static RevisionView view(ImportDefinitionRevision revision) {
        return new RevisionView(revision.getId(), revision.getImportDefinition().getId(),
                revision.getRevisionNumber(), revision.getStatus().name(),
                revision.getIdentityProfileKind().name(), revision.getStructureDelimiter(),
                revision.isStructureHasHeader(), revision.getStructureFieldReferenceKind(),
                revision.getRecordCanonicalisationVersion(), revision.getIdentitySupplierField(),
                revision.getIdentitySupplierGroupField(), revision.getIdentitySupplierReferenceField(),
                revision.getIdentityDiscountCodeField(), revision.getRecordBasePriceField(),
                revision.getRecordDescriptionField(), revision.getRecordCurrencyField(),
                revision.getCreationThresholdSharePercent(), revision.getMaxCriticalSharePercent(),
                revision.getMaxRejectedSharePercent(), revision.getBulkIncidentSharePercent());
    }

    private static LinkView view(ImportLink link) {
        return new LinkView(link.getId(), link.getCode(), link.getName(),
                link.getImportDefinition().getId(), link.getSupplierOrganisation().getCode(),
                link.getLibraryCode(), link.isActive());
    }

    private static TaskView view(CatalogImportTask task) {
        return new TaskView(task.getId(), task.getImportLink().getId(), task.getName(),
                task.getTriggerType().name(), task.isActive(), task.isPreventConcurrentRuns());
    }

    // --- Hulpmiddelen ----------------------------------------------------------------------------------

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, field, maxLength);
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private static BigDecimal requireNotNegative(BigDecimal value, String field) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
