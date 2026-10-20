package be.dda.catalogimport.service;

import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_CREATE;
import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_NONE;
import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_REUSE;
import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_SUCCESSOR_DRAFT;
import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_UNCHANGED;
import static be.dda.catalogimport.service.LegacyConfigReport.BLOCKING;
import static be.dda.catalogimport.service.LegacyConfigReport.MIGRATED;
import static be.dda.catalogimport.service.LegacyConfigReport.WARNING;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.LegacyConfigDefinitionOriginRepository;
import be.dda.catalogimport.dao.LegacyConfigOriginRepository;
import be.dda.catalogimport.dao.LegacyConfigReceiptRepository;
import be.dda.catalogimport.dao.LegacyConfigRevisionOriginRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.LegacyConfigDefinitionOrigin;
import be.dda.catalogimport.domain.LegacyConfigOrigin;
import be.dda.catalogimport.domain.LegacyConfigReceipt;
import be.dda.catalogimport.domain.LegacyConfigReceiptStatus;
import be.dda.catalogimport.domain.LegacyConfigRevisionOrigin;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.service.LegacyConfigReport.Item;
import be.dda.catalogimport.service.LegacyConfigReport.Planned;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedCredential;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedDefinition;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedLink;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedOrganisation;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedTask;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedFilter;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedMapping;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedRevision;
import be.dda.catalogimport.service.LegacyConfigTranslator.Translation;
import be.dda.catalogimport.service.support.ConfigFinding;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PREVIEW en APPLY van één legacy-config-ontvangst, slice S2 van {@code docs/design/legacy-config-bundle-design.md}
 * (par. 2, 4, 5 en 8); contract {@code docs/design/legacy-config-bundle-v1.md}; beslissingslog 2026-10-20 (M1-M3,
 * "Legacy-config S1: idempotentie", "Legacy-config S2: bibliotheekcode, opvolgende DRAFT, Separator, aannames").
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>PREVIEW schrijft niets</b> en geeft het rapport ({@link LegacyConfigReport}) plus een
 *       {@code previewFingerprint}: SHA-256 over het canonieke rapport en de id/{@code updated_at} (en status/inhoudshash)
 *       van elke geraakte bestaande rij. PREVIEW = APPLY-resultaat.</li>
 *   <li><b>APPLY</b> (één transactie, rijslot op de ontvangst): enkel op {@code RECEIVED}
 *       ({@value #CODE_NOT_APPLICABLE} anders, dus ook bij een tweede APPLY); reden verplicht
 *       ({@value #CODE_REASON_REQUIRED}); de fingerprint moet die van het rapport van nu zijn ({@value #CODE_STALE});
 *       een geblokkeerd rapport wordt nooit toegepast ({@value #CODE_BLOCKED}). Dan: bronorganisatie find-or-create →
 *       eigen definitie (nieuw, opvolgende DRAFT of ongewijzigd) → koppeling → MANUAL-taak → herkomstrijen (020-2,
 *       020-3, 020-4) → ontvangst {@code APPLIED}. <b>APPLY activeert nooit.</b></li>
 *   <li><b>Credential (S4):</b> {@code planned.credential} toont de {@code credentialRef} van de herkomst en de
 *       {@code secretOutcome} van deze ontvangst, nooit een waarde. APPLY koppelt geen verbindingsprofiel of
 *       leveringsconfiguratie (L5: hostsleutel verplicht); het item {@code CONNECTION_NOT_MIGRATED} geeft het voorstel
 *       (host, poort, map, login, {@code credentialRef}).</li>
 *   <li><b>Nieuwe interface:</b> {@code OWN_DEFINITION} met de legacy-code (of {@code definitionCodeOverride}),
 *       revisie 1 (DRAFT, canonicalisatieversie 2), koppeling {@code LEGACY-<interface>} met de door de mens
 *       gegeven {@code libraryCode} (nooit het ruwe {@code ArtImp_BibNr}; zonder: {@code LIBRARY_CODE_NOT_RESOLVED}),
 *       taak "Legacy &lt;interface&gt;".</li>
 *   <li><b>Bekende interface:</b> koppeling en taak worden nooit gewijzigd (een afwijking is
 *       {@code LINK_DIFFERS_NOT_CHANGED}). Levert de ontvangst exact de inhoud die de laatste migratie schreef:
 *       {@code DEFINITION_UNCHANGED}, geen nieuwe revisie, ontvangst wel {@code APPLIED}. Anders: een open DRAFT
 *       blokkeert ({@code OPEN_DRAFT_EXISTS}); wijkt de laatste revisie inhoudelijk af van de laatst gemigreerde
 *       ({@code MANUAL_CHANGES_SINCE_MIGRATION}) dan ook; anders een opvolgende DRAFT via
 *       {@link RevisionSuccessorService} (letterlijke kopie) waarin APPLY de door legacy bepaalde velden, de
 *       vertaalbare mappings en de filters overschrijft. APPLY geldt als R-REV-X3-bevestiging; de preview toont
 *       {@code IDENTITY_CHANGE}. De ACTIVE revisie wordt nooit aangeraakt.</li>
 *   <li><b>Conflicten:</b> een handmatige definitie is nooit een migratiedoel ({@code DEFINITION_CODE_CONFLICT_MANUAL},
 *       op te lossen met {@code definitionCodeOverride}); een definitie van een andere interface (zelfde leverancier en
 *       inhoud of code) blokkeert tot S7 ({@code SHARED_DEFINITION_NOT_SUPPORTED}); een bestaande koppelingscode of
 *       -scope ({@code LINK_CODE_CONFLICT_MANUAL}, {@code LINK_SCOPE_CONFLICT_MANUAL}).</li>
 * </ul>
 * Schrijven gaat via de bestaande services ({@link SetupService}, {@link RevisionSuccessorService}), die hun eigen regels
 * bewaken; enkel een TAB-scheidingsteken wordt rechtstreeks op de revisie gezet, omdat {@code SetupInput.requireText}
 * een tab als leeg ziet. Gelogd wordt enkel id, herkomst, uitkomst en codes; nooit de bundel.
 */
@Service
@Transactional
public class LegacyConfigApplyService {

    public static final String CODE_NOT_APPLICABLE = "LEGACY_RECEIPT_NOT_APPLICABLE";
    public static final String CODE_STALE = "LEGACY_PREVIEW_STALE";
    public static final String CODE_BLOCKED = "LEGACY_APPLY_BLOCKED";
    public static final String CODE_REASON_REQUIRED = "LEGACY_APPLY_REASON_REQUIRED";
    public static final String CODE_REASON_TOO_LONG = "LEGACY_APPLY_REASON_TOO_LONG";

    static final String LIBRARY_CODE_NOT_RESOLVED = "LIBRARY_CODE_NOT_RESOLVED";
    static final String LIBRARY_CODE_INVALID = "LIBRARY_CODE_INVALID";
    static final String DEFINITION_CODE_CONFLICT_MANUAL = "DEFINITION_CODE_CONFLICT_MANUAL";
    static final String DEFINITION_CODE_OVERRIDE_INVALID = "DEFINITION_CODE_OVERRIDE_INVALID";
    static final String SHARED_DEFINITION_NOT_SUPPORTED = "SHARED_DEFINITION_NOT_SUPPORTED";
    static final String SOURCE_ORGANISATION_TYPE_CONFLICT = "SOURCE_ORGANISATION_TYPE_CONFLICT";
    static final String LINK_CODE_CONFLICT_MANUAL = "LINK_CODE_CONFLICT_MANUAL";
    static final String LINK_SCOPE_CONFLICT_MANUAL = "LINK_SCOPE_CONFLICT_MANUAL";
    static final String LINK_DIFFERS_NOT_CHANGED = "LINK_DIFFERS_NOT_CHANGED";
    static final String OPEN_DRAFT_EXISTS = "OPEN_DRAFT_EXISTS";
    static final String REVISION_NOT_CLONEABLE = "REVISION_NOT_CLONEABLE";
    static final String MANUAL_CHANGES_SINCE_MIGRATION = "MANUAL_CHANGES_SINCE_MIGRATION";
    static final String IDENTITY_CHANGE = "IDENTITY_CHANGE";
    static final String DEFINITION_UNCHANGED = "DEFINITION_UNCHANGED";
    static final String LEGACY_DEFINITION_CODE_CHANGED = "LEGACY_DEFINITION_CODE_CHANGED";

    static final String LINK_CODE_PREFIX = "LEGACY-";
    static final String NAME_PREFIX = "Legacy ";
    static final String ORGANISATION_NAME_PREFIX = "Leverancier ";
    /** Canonicalisatieversie van een gemigreerde revisie: mappings vereisen versie 2 (ImportMappingConfigFactory). */
    static final int CANONICALISATION_VERSION = 2;

    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_DEFINITION_CODE = 50;
    private static final int MAX_LIBRARY_CODE = 20;
    private static final Set<RevisionStatus> CLONEABLE = Set.of(RevisionStatus.ACTIVE, RevisionStatus.SUPERSEDED);
    private static final Logger LOG = LoggerFactory.getLogger(LegacyConfigApplyService.class);

    /** De door de mens gegeven keuzes; dezelfde voor PREVIEW (queryparameters) en APPLY (body). */
    public record ApplyOptions(String definitionCodeOverride, String charsetWhenUndeclared, String libraryCode) {

        static ApplyOptions none() {
            return new ApplyOptions(null, null, null);
        }

        ApplyOptions normalised() {
            return new ApplyOptions(blankToNull(definitionCodeOverride), blankToNull(charsetWhenUndeclared),
                    blankToNull(libraryCode));
        }
    }

    /** APPLY-body: {@code {previewFingerprint, reason, definitionCodeOverride?, charsetWhenUndeclared?, libraryCode?}}. */
    public record ApplyCommand(String previewFingerprint, String reason, String definitionCodeOverride,
                               String charsetWhenUndeclared, String libraryCode) {

        ApplyOptions options() {
            return new ApplyOptions(definitionCodeOverride, charsetWhenUndeclared, libraryCode).normalised();
        }
    }

    public record PreviewResult(LegacyConfigReport report, String previewFingerprint) {
    }

    /**
     * @param report                 het rapport met de id's van wat aangemaakt of hergebruikt werd
     * @param receiptStatus          {@code APPLIED}
     * @param configurationFindings  de configuratiebevindingen van de nieuwe revisie (dezelfde validatie als bij het
     *                               activeren); een DRAFT mag onvolledig zijn, activeren blokkeert dan
     */
    public record ApplyResult(LegacyConfigReport report, String receiptStatus,
                              List<ConfigurationFinding> configurationFindings) {
    }

    public record ConfigurationFinding(String code, String fieldName, String message) {
    }

    private final LegacyConfigReceiptRepository receipts;
    private final LegacyConfigOriginRepository origins;
    private final LegacyConfigDefinitionOriginRepository definitionOrigins;
    private final LegacyConfigRevisionOriginRepository revisionOrigins;
    private final SourceOrganisationRepository organisations;
    private final ImportDefinitionRepository definitions;
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportFieldMappingRepository fieldMappings;
    private final ImportRecordFilterRepository recordFilters;
    private final ImportFieldCatalogRepository fieldCatalog;
    private final ImportLinkRepository links;
    private final CatalogImportTaskRepository tasks;
    private final ExternalCredentialRepository credentials;
    private final SetupService setup;
    private final RevisionSuccessorService successors;
    private final ChainConfigurationChecks checks;
    private final Clock clock;
    private final ObjectMapper bundleReader = new ObjectMapper();
    /** Canoniek (alfabetisch) voor de fingerprint en het bewaarde rapport. */
    private final ObjectMapper canonical = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public LegacyConfigApplyService(LegacyConfigReceiptRepository receipts, LegacyConfigOriginRepository origins,
                                    LegacyConfigDefinitionOriginRepository definitionOrigins,
                                    LegacyConfigRevisionOriginRepository revisionOrigins,
                                    SourceOrganisationRepository organisations, ImportDefinitionRepository definitions,
                                    ImportDefinitionRevisionRepository revisions,
                                    ImportFieldMappingRepository fieldMappings,
                                    ImportRecordFilterRepository recordFilters,
                                    ImportFieldCatalogRepository fieldCatalog, ImportLinkRepository links,
                                    CatalogImportTaskRepository tasks, ExternalCredentialRepository credentials,
                                    SetupService setup, RevisionSuccessorService successors,
                                    ChainConfigurationChecks checks, Clock clock) {
        this.receipts = receipts;
        this.origins = origins;
        this.definitionOrigins = definitionOrigins;
        this.revisionOrigins = revisionOrigins;
        this.organisations = organisations;
        this.definitions = definitions;
        this.revisions = revisions;
        this.fieldMappings = fieldMappings;
        this.recordFilters = recordFilters;
        this.fieldCatalog = fieldCatalog;
        this.links = links;
        this.tasks = tasks;
        this.credentials = credentials;
        this.setup = setup;
        this.successors = successors;
        this.checks = checks;
        this.clock = clock;
    }

    // --- PREVIEW --------------------------------------------------------------------------------------------------

    /**
     * Het rapport van wat APPLY nu zou doen, met dezelfde keuzes als de APPLY-body; schrijft niets.
     *
     * @throws NotFoundException 404 {@code LEGACY_RECEIPT_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public PreviewResult preview(long receiptId, ApplyOptions options) {
        LegacyConfigReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> notFound(receiptId));
        Plan plan = plan(receipt, (options == null ? ApplyOptions.none() : options).normalised());
        return new PreviewResult(plan.report(), plan.fingerprint());
    }

    // --- APPLY ----------------------------------------------------------------------------------------------------

    /**
     * Past de ontvangst toe zoals de preview met {@code command.previewFingerprint()} beschreef.
     *
     * @throws NotFoundException   404 {@code LEGACY_RECEIPT_NOT_FOUND}
     * @throws BadRequestException 400 {@value #CODE_REASON_REQUIRED}, {@value #CODE_REASON_TOO_LONG}
     * @throws ConflictException   409 {@value #CODE_NOT_APPLICABLE}, {@value #CODE_STALE}, {@value #CODE_BLOCKED}
     */
    public ApplyResult apply(long receiptId, ApplyCommand command, ActorIdentity actor) {
        LegacyConfigReceipt receipt = receipts.findByIdForUpdate(receiptId).orElseThrow(() -> notFound(receiptId));
        String reason = command == null ? null : command.reason();
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(CODE_REASON_REQUIRED, "reason is required: it is the only record of why "
                    + "this legacy configuration was applied");
        }
        reason = reason.trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BadRequestException(CODE_REASON_TOO_LONG, "reason must be at most " + MAX_REASON_LENGTH
                    + " characters");
        }
        if (receipt.getStatus() != LegacyConfigReceiptStatus.RECEIVED) {
            throw new ConflictException(CODE_NOT_APPLICABLE, "Legacy-config receipt " + receiptId + " is "
                    + receipt.getStatus() + "; only a RECEIVED receipt can be applied");
        }
        Plan plan = plan(receipt, command.options());
        if (command.previewFingerprint() == null || !command.previewFingerprint().equals(plan.fingerprint())) {
            throw new ConflictException(CODE_STALE, "The preview of legacy-config receipt " + receiptId
                    + " is missing or no longer current; run the preview again (with the same choices) and apply that");
        }
        if (LegacyConfigReport.OUTCOME_BLOCKED.equals(plan.report().outcome())) {
            throw new ConflictException(CODE_BLOCKED, "Legacy-config receipt " + receiptId
                    + " cannot be applied; blocking: " + String.join(", ", plan.report().blockingCodes()));
        }
        ApplyResult result = execute(receipt, plan, reason, actor);
        LOG.info("Legacy-config receipt {} APPLIED for {}/{}/{} by {}: definition {} ({}), link {}, task {}",
                receipt.getId(), receipt.getOriginEnvironment(), receipt.getOriginDossier(),
                receipt.getInterfaceCode(), actor.username(), result.report().planned().definition().id(),
                result.report().planned().definition().revisionAction(), result.report().planned().link().id(),
                result.report().planned().task().id());
        return result;
    }

    // --- Plannen (gedeeld door PREVIEW en APPLY) -----------------------------------------------------------------

    /** Alles wat APPLY nodig heeft, plus het rapport en de fingerprint. */
    private record Plan(LegacyConfigReport report, String fingerprint, Translation translation, ApplyOptions options,
                        SourceOrganisation organisation, String organisationCode, ImportDefinition definition,
                        String definitionCode, String revisionAction, ImportDefinitionRevision basedOn,
                        ImportLink link, String linkCode, String libraryCode, Long taskId, String taskName,
                        LegacyConfigOrigin origin, LegacyConfigDefinitionOrigin definitionOrigin,
                        String plannedContentHash) {
    }

    private Plan plan(LegacyConfigReceipt receipt, ApplyOptions options) {
        JsonNode bundle = readBundle(receipt);
        String environment = receipt.getOriginEnvironment();
        String dossier = receipt.getOriginDossier();
        String interfaceCode = receipt.getInterfaceCode();
        LegacyConfigOrigin origin = origins.findByOriginEnvironmentAndOriginDossierAndInterfaceCode(environment,
                dossier, interfaceCode).orElse(null);
        // S4: de credential van de herkomst (nooit een waarde); APPLY koppelt ze nergens aan (L5: hostsleutel).
        UUID credentialRef = origin == null || origin.getCredentialId() == null ? null
                : credentials.findById(origin.getCredentialId()).map(ExternalCredential::getCredentialRef).orElse(null);
        PlannedCredential plannedCredential = credentialRef == null && receipt.getSecretOutcome() == null ? null
                : new PlannedCredential(credentialRef,
                receipt.getSecretOutcome() == null ? null : receipt.getSecretOutcome().name());
        Translation translation = LegacyConfigTranslator.translate(bundle, options.charsetWhenUndeclared(),
                credentialRef);
        List<Item> items = new ArrayList<>(translation.items());
        List<String> rows = new ArrayList<>();
        String supplierCode = translation.supplierCode();
        String definitionHash = translation.definitionContentHash();
        PlannedRevision planned = translation.revision();
        String plannedHash = planned == null ? null
                : LegacyRevisionContent.ofPlanned(planned, "CSV", this::defaultCriticality);

        rows.add("origin:" + (origin == null ? "none" : origin.getId() + ":" + origin.getUpdatedAt()));
        ImportLink link = origin == null || origin.getImportLinkId() == null ? null
                : links.findById(origin.getImportLinkId()).orElse(null);

        SourceOrganisation organisation;
        String organisationAction;
        ImportDefinition definition = null;
        String definitionAction;
        String definitionCode;
        String revisionAction = ACTION_NONE;
        ImportDefinitionRevision basedOn = null;
        String linkCode;
        String libraryCode;
        String linkAction;
        Long taskId = null;
        String taskAction;
        String taskName = NAME_PREFIX + interfaceCode;
        LegacyConfigDefinitionOrigin definitionOrigin = null;

        if (link != null) {
            // Bekende interface: koppeling, taak, organisatie en definitie bestaan en worden nooit gewijzigd.
            rows.add("link:" + link.getId() + ":" + link.getUpdatedAt());
            organisation = link.getSupplierOrganisation();
            organisationAction = ACTION_REUSE;
            definition = link.getImportDefinition();
            definitionAction = ACTION_REUSE;
            definitionCode = definition.getCode();
            linkAction = ACTION_REUSE;
            linkCode = link.getCode();
            libraryCode = link.getLibraryCode();
            rows.add("organisation:" + organisation.getId() + ":" + organisation.getUpdatedAt());
            rows.add("definition:" + definition.getId() + ":" + definition.getUpdatedAt());
            if (supplierCode != null && !supplierCode.equals(organisation.getCode())) {
                items.add(new Item("PLAN:link", BLOCKING, LINK_DIFFERS_NOT_CHANGED, "import_link.id=" + link.getId(),
                        "the interface now names supplier " + supplierCode + " but its link is for "
                                + organisation.getCode() + "; an existing link is never changed"));
            }
            if (options.libraryCode() != null && !options.libraryCode().equals(link.getLibraryCode())) {
                items.add(new Item("PLAN:link", WARNING, LINK_DIFFERS_NOT_CHANGED, "import_link.id=" + link.getId(),
                        "libraryCode " + options.libraryCode() + " differs from the existing link ("
                                + link.getLibraryCode() + "); an existing link is never changed"));
            }
            if (origin.getCatalogImportTaskId() != null) {
                CatalogImportTask task = tasks.findById(origin.getCatalogImportTaskId()).orElse(null);
                taskAction = ACTION_REUSE;
                taskId = origin.getCatalogImportTaskId();
                if (task != null) {
                    taskName = task.getName();
                    rows.add("task:" + task.getId() + ":" + task.getUpdatedAt());
                }
            } else {
                taskAction = ACTION_CREATE;
            }
            if (translation.definitionCode() != null && !translation.definitionCode().equals(definition.getCode())
                    && options.definitionCodeOverride() == null) {
                items.add(new Item("PSIMDHFD", WARNING, LEGACY_DEFINITION_CODE_CHANGED,
                        "import_definition.id=" + definition.getId(), "the interface now uses legacy definition "
                                + translation.definitionCode() + "; the existing definition " + definition.getCode()
                                + " keeps its code"));
            }
            List<LegacyConfigDefinitionOrigin> owned =
                    definitionOrigins.findByImportDefinitionIdOrderByIdAsc(definition.getId());
            if (owned.isEmpty()) {
                items.add(new Item("PLAN:definition", BLOCKING, DEFINITION_CODE_CONFLICT_MANUAL,
                        "import_definition.id=" + definition.getId(), "the definition of this link has no migration "
                                + "origin; a manual definition is never a migration target"));
            } else {
                definitionOrigin = owned.get(0);
                rows.add("definitionOrigin:" + definitionOrigin.getId() + ":" + definitionOrigin.getUpdatedAt());
            }
            sharedCheck(items, rows, environment, dossier, supplierCode, definitionHash, definition.getId());
            if (planned != null) {
                RevisionDecision decision = revisionDecision(definition, planned, plannedHash, items, rows);
                revisionAction = decision.action();
                basedOn = decision.basedOn();
            }
        } else {
            // Nieuwe interface (of een herkomst zonder koppeling, bv. enkel een credential uit S4).
            organisation = supplierCode == null ? null : organisations.findByCode(supplierCode).orElse(null);
            organisationAction = organisation == null ? ACTION_CREATE : ACTION_REUSE;
            if (organisation != null) {
                rows.add("organisation:" + organisation.getId() + ":" + organisation.getUpdatedAt());
                if (organisation.getOrganisationType() != SourceOrganisationType.SUPPLIER) {
                    items.add(new Item("INDET:Supplier", BLOCKING, SOURCE_ORGANISATION_TYPE_CONFLICT,
                            "source_organisation.id=" + organisation.getId(), "source organisation " + supplierCode
                                    + " exists as " + organisation.getOrganisationType() + ", not as SUPPLIER"));
                }
            }
            definitionAction = ACTION_CREATE;
            revisionAction = planned == null ? ACTION_NONE : ACTION_CREATE;
            definitionCode = options.definitionCodeOverride() != null ? options.definitionCodeOverride()
                    : translation.definitionCode();
            if (options.definitionCodeOverride() != null
                    && options.definitionCodeOverride().length() > MAX_DEFINITION_CODE) {
                items.add(new Item("PLAN:definition", BLOCKING, DEFINITION_CODE_OVERRIDE_INVALID, null,
                        "definitionCodeOverride is longer than " + MAX_DEFINITION_CODE + " characters"));
            } else if (options.definitionCodeOverride() != null) {
                items.add(new Item("PLAN:definition", WARNING, "DEFINITION_CODE_OVERRIDDEN",
                        "import_definition.code=" + definitionCode, "the legacy code " + translation.definitionCode()
                                + " is replaced by definitionCodeOverride"));
            }
            sharedCheck(items, rows, environment, dossier, supplierCode, definitionHash, null);
            if (organisation != null && definitionCode != null) {
                Optional<ImportDefinition> existing =
                        definitions.findBySourceOrganisationIdAndCode(organisation.getId(), definitionCode);
                if (existing.isPresent()) {
                    ImportDefinition clash = existing.get();
                    rows.add("definitionClash:" + clash.getId() + ":" + clash.getUpdatedAt());
                    if (definitionOrigins.existsByImportDefinitionId(clash.getId())) {
                        items.add(new Item("PLAN:definition", BLOCKING, SHARED_DEFINITION_NOT_SUPPORTED,
                                "import_definition.id=" + clash.getId(), "definition " + definitionCode
                                        + " of supplier " + supplierCode + " was already migrated for another "
                                        + "interface; sharing a definition follows in S7"));
                    } else {
                        items.add(new Item("PLAN:definition", BLOCKING, DEFINITION_CODE_CONFLICT_MANUAL,
                                "import_definition.id=" + clash.getId(), "a manual definition " + definitionCode
                                        + " already exists for supplier " + supplierCode + "; it is never a "
                                        + "migration target. Choose another code with definitionCodeOverride"));
                    }
                }
            }
            linkAction = ACTION_CREATE;
            linkCode = LINK_CODE_PREFIX + interfaceCode;
            libraryCode = options.libraryCode();
            if (translation.libraryNumber() != null) {
                if (libraryCode == null) {
                    items.add(new Item("INDET:ArtImp_BibNr", BLOCKING, LIBRARY_CODE_NOT_RESOLVED, null,
                            "legacy library " + translation.libraryNumber() + " cannot be resolved to a library code "
                                    + "without the Prodis database (S5); give libraryCode (supplier_catalog.scope_key)"));
                } else if (libraryCode.length() > MAX_LIBRARY_CODE) {
                    items.add(new Item("INDET:ArtImp_BibNr", BLOCKING, LIBRARY_CODE_INVALID, null,
                            "libraryCode is longer than " + MAX_LIBRARY_CODE + " characters"));
                } else {
                    items.add(new Item("INDET:ArtImp_BibNr", MIGRATED, "LIBRARY_CODE",
                            "import_link.libraryCode=" + libraryCode, "given by the person applying"));
                    items.add(new Item("INDET:ArtImp_BibNr", WARNING, LegacyConfigTranslator.PRODIS_REFERENCE_NOT_VERIFIED,
                            null, "libraryCode " + libraryCode + " for legacy library " + translation.libraryNumber()
                                    + " is not verified against the Prodis database (S5)"));
                }
            }
            Optional<ImportLink> codeClash = links.findByCode(linkCode);
            if (codeClash.isPresent()) {
                rows.add("linkClash:" + codeClash.get().getId() + ":" + codeClash.get().getUpdatedAt());
                items.add(new Item("PLAN:link", BLOCKING, LINK_CODE_CONFLICT_MANUAL,
                        "import_link.id=" + codeClash.get().getId(), "link code " + linkCode + " is already in use"));
            }
            taskAction = ACTION_CREATE;
        }

        boolean blocked = items.stream().anyMatch(item -> BLOCKING.equals(item.status()));
        Planned plannedSection = new Planned(
                new PlannedOrganisation(organisationAction, organisation == null ? null : organisation.getId(),
                        supplierCode, organisation == null
                        ? (supplierCode == null ? null : ORGANISATION_NAME_PREFIX + supplierCode)
                        : organisation.getName()),
                new PlannedDefinition(definitionAction, definition == null ? null : definition.getId(), definitionCode,
                        definition == null ? translation.definitionName() : definition.getDescription(),
                        revisionAction, basedOn == null ? null : basedOn.getId(), null),
                new PlannedLink(linkAction, link == null ? null : link.getId(), linkCode, libraryCode),
                new PlannedTask(taskAction, taskId, taskName), plannedCredential);
        LegacyConfigReport report = new LegacyConfigReport(receipt.getId(), receipt.getContentHash(),
                receipt.getStatus().name(), blocked ? LegacyConfigReport.OUTCOME_BLOCKED
                : LegacyConfigReport.OUTCOME_APPLICABLE, plannedSection, List.of(), items);
        String fingerprint = fingerprint(report, rows);
        return new Plan(report, fingerprint, translation, options, organisation, supplierCode, definition,
                definitionCode, revisionAction, basedOn, link, linkCode, libraryCode, taskId, taskName, origin,
                definitionOrigin, plannedHash);
    }

    private record RevisionDecision(String action, ImportDefinitionRevision basedOn) {
    }

    /** Bekende interface: geen nieuwe revisie, een opvolgende DRAFT, of blokkeren (V2 = C). */
    private RevisionDecision revisionDecision(ImportDefinition definition, PlannedRevision planned, String plannedHash,
                                              List<Item> items, List<String> rows) {
        List<ImportDefinitionRevision> all = revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(
                definition.getId());
        Map<Long, LegacyConfigRevisionOrigin> migrated = new LinkedHashMap<>();
        revisionOrigins.findByRevisionIdIn(all.stream().map(ImportDefinitionRevision::getId).toList())
                .forEach(row -> migrated.put(row.getRevisionId(), row));
        ImportDefinitionRevision latest = all.isEmpty() ? null : all.get(0);
        ImportDefinitionRevision lastMigrated = all.stream().filter(r -> migrated.containsKey(r.getId()))
                .findFirst().orElse(null);
        String element = "PLAN:definition";
        if (latest == null || lastMigrated == null) {
            items.add(new Item(element, BLOCKING, MANUAL_CHANGES_SINCE_MIGRATION, "import_definition.id="
                    + definition.getId(), "the definition has no migrated revision; it is never overwritten"));
            return new RevisionDecision(ACTION_NONE, null);
        }
        rows.add("latestRevision:" + latest.getId() + ":" + latest.getStatus() + ":" + latest.getUpdatedAt());
        rows.add("lastMigrated:" + lastMigrated.getId());
        String storedHash = migrated.get(lastMigrated.getId()).getMigratedContentHash();
        if (plannedHash.equals(storedHash)) {
            items.add(new Item(element, MIGRATED, DEFINITION_UNCHANGED, "revision.id=" + lastMigrated.getId(),
                    "the receipt yields exactly what migration revision " + lastMigrated.getRevisionNumber()
                            + " got; no new revision"));
            return new RevisionDecision(ACTION_UNCHANGED, null);
        }
        if (latest.getStatus() == RevisionStatus.DRAFT) {
            items.add(new Item(element, BLOCKING, OPEN_DRAFT_EXISTS, "revision.id=" + latest.getId(),
                    "revision " + latest.getRevisionNumber() + " is an open DRAFT; finish or activate it first"));
            return new RevisionDecision(ACTION_NONE, null);
        }
        if (!CLONEABLE.contains(latest.getStatus())) {
            items.add(new Item(element, BLOCKING, REVISION_NOT_CLONEABLE, "revision.id=" + latest.getId(),
                    "revision " + latest.getRevisionNumber() + " is " + latest.getStatus()
                            + "; only an ACTIVE or SUPERSEDED revision is copied into a successor"));
            return new RevisionDecision(ACTION_NONE, null);
        }
        String latestHash = LegacyRevisionContent.ofRevision(latest,
                fieldMappings.findByRevisionIdWithTargetField(latest.getId()),
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(latest.getId()));
        rows.add("latestContent:" + latestHash);
        if (!latestHash.equals(storedHash)) {
            items.add(new Item(element, BLOCKING, MANUAL_CHANGES_SINCE_MIGRATION, "revision.id=" + latest.getId(),
                    "revision " + latest.getRevisionNumber() + " differs (fields, mappings or filters) from what "
                            + "the migration wrote in revision " + lastMigrated.getRevisionNumber()
                            + "; a new legacy version would overwrite those changes"));
            return new RevisionDecision(ACTION_NONE, null);
        }
        if (identityDiffers(latest, planned)) {
            items.add(new Item(element, WARNING, IDENTITY_CHANGE, "revision.identity*", "the identity definition "
                    + "changes (profile or identity columns): every existing offer can come back as NEW. APPLY "
                    + "counts as the acknowledgement (R-REV-X3)"));
        }
        items.add(new Item(element, MIGRATED, ACTION_SUCCESSOR_DRAFT, "revision.basedOn=" + latest.getId(),
                "a successor DRAFT of revision " + latest.getRevisionNumber() + "; the ACTIVE revision is not touched"));
        return new RevisionDecision(ACTION_SUCCESSOR_DRAFT, latest);
    }

    private void sharedCheck(List<Item> items, List<String> rows, String environment, String dossier,
                             String supplierCode, String definitionHash, Long ownDefinitionId) {
        if (supplierCode == null || definitionHash == null) {
            return;
        }
        definitionOrigins.findByOriginEnvironmentAndOriginDossierAndSupplierCodeAndDefinitionContentHash(
                environment, dossier, supplierCode, definitionHash).ifPresent(shared -> {
            rows.add("sharedOrigin:" + shared.getId() + ":" + shared.getUpdatedAt());
            if (!shared.getImportDefinitionId().equals(ownDefinitionId)) {
                items.add(new Item("PLAN:definition", BLOCKING, SHARED_DEFINITION_NOT_SUPPORTED,
                        "import_definition.id=" + shared.getImportDefinitionId(), "another interface of supplier "
                                + supplierCode + " already migrated this legacy definition (same content); sharing "
                                + "a definition follows in S7"));
            }
        });
    }

    private static boolean identityDiffers(ImportDefinitionRevision latest, PlannedRevision planned) {
        return latest.getIdentityProfileKind() != planned.identityProfileKind()
                || !Objects.equals(latest.getIdentitySupplierField(), planned.supplierField())
                || !Objects.equals(latest.getIdentitySupplierGroupField(), planned.groupField())
                || !Objects.equals(latest.getIdentitySupplierReferenceField(), planned.referenceField())
                || !Objects.equals(latest.getIdentityDiscountCodeField(), planned.discountField());
    }

    // --- Uitvoeren ------------------------------------------------------------------------------------------------

    private ApplyResult execute(LegacyConfigReceipt receipt, Plan plan, String reason, ActorIdentity actor) {
        Instant now = clock.instant();
        Translation translation = plan.translation();
        PlannedRevision planned = translation.revision();

        SourceOrganisation organisation = plan.organisation();
        if (organisation == null) {
            setup.createSourceOrganisation(new SetupService.CreateSourceOrganisationCommand(plan.organisationCode(),
                    ORGANISATION_NAME_PREFIX + plan.organisationCode(), SourceOrganisationType.SUPPLIER));
            organisation = organisations.findByCode(plan.organisationCode()).orElseThrow();
        }

        long definitionId;
        if (plan.definition() == null) {
            definitionId = setup.createDefinition(new SetupService.CreateDefinitionCommand(organisation.getCode(),
                    plan.definitionCode(), translation.definitionName(), DefinitionUsageType.OWN_DEFINITION),
                    actor).id();
        } else {
            definitionId = plan.definition().getId();
        }

        Long revisionId = null;
        switch (plan.revisionAction()) {
            case ACTION_CREATE -> revisionId = setup.createRevision(definitionId, createCommand(planned, reason),
                    actor).id();
            case ACTION_SUCCESSOR_DRAFT -> {
                revisionId = successors.createSuccessor(plan.basedOn().getId(), reason, actor).id();
                setup.updateRevision(revisionId, updateCommand(planned));
                for (ImportFieldMapping mapping : fieldMappings.findByRevisionIdWithTargetField(revisionId)) {
                    if (LegacyConfigTranslator.MAPPING_TARGETS.containsValue(mapping.getTargetField().getCode())) {
                        setup.deleteMapping(revisionId, mapping.getId());
                    }
                }
                for (ImportRecordFilter filter
                        : recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId)) {
                    setup.deleteFilter(revisionId, filter.getId());
                }
            }
            default -> {
                // UNCHANGED: geen nieuwe revisie.
            }
        }

        List<ConfigurationFinding> findings = List.of();
        if (revisionId != null) {
            writeRevisionContent(revisionId, planned, actor);
            ImportDefinitionRevision revision = revisions.findById(revisionId).orElseThrow();
            String written = LegacyRevisionContent.ofRevision(revision,
                    fieldMappings.findByRevisionIdWithTargetField(revisionId),
                    recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId));
            if (!written.equals(plan.plannedContentHash())) {
                // Plan en uitvoering lopen uiteen: nooit een herkomstrij met een verkeerde inhoud (rollback).
                throw new IllegalStateException("Legacy-config receipt " + receipt.getId()
                        + ": the written revision does not match the planned content");
            }
            revisionOrigins.save(new LegacyConfigRevisionOrigin(revisionId, receipt.getId(),
                    translation.definitionContentHash(), written, now));
        }

        LegacyConfigDefinitionOrigin definitionOrigin = plan.definitionOrigin();
        if (definitionOrigin == null) {
            definitionOrigins.save(new LegacyConfigDefinitionOrigin(receipt.getOriginEnvironment(),
                    receipt.getOriginDossier(), organisation.getCode(), translation.definitionContentHash(),
                    translation.definitionCode(), definitionId, now));
        } else if (!definitionOrigin.getDefinitionContentHash().equals(translation.definitionContentHash())
                || !definitionOrigin.getLegacyDefinitionCodes().equals(translation.definitionCode())) {
            definitionOrigin.moveTo(translation.definitionContentHash(), translation.definitionCode(), now);
            definitionOrigins.save(definitionOrigin);
        }

        long linkId;
        if (plan.link() == null) {
            boolean scopeTaken = links.findByImportDefinitionId(definitionId).stream().anyMatch(existing ->
                    existing.getLibraryCode().equals(plan.libraryCode())
                            && existing.getSupplierOrganisation().getCode().equals(plan.organisationCode()));
            if (scopeTaken) {
                // Kan enkel ontstaan tussen preview en apply; het rapport van nu had dit dan getoond.
                throw new ConflictException(CODE_BLOCKED, "Legacy-config receipt " + receipt.getId()
                        + " cannot be applied; blocking: " + LINK_SCOPE_CONFLICT_MANUAL);
            }
            linkId = setup.createLink(new SetupService.CreateLinkCommand(definitionId, plan.linkCode(),
                    NAME_PREFIX + receipt.getInterfaceCode(), organisation.getCode(), plan.libraryCode(), null,
                    null)).id();
        } else {
            linkId = plan.link().getId();
        }
        long taskId = plan.taskId() != null ? plan.taskId()
                : setup.createTask(new SetupService.CreateTaskCommand(linkId, plan.taskName(), null)).id();

        LegacyConfigOrigin origin = plan.origin() != null ? plan.origin()
                : new LegacyConfigOrigin(receipt.getOriginEnvironment(), receipt.getOriginDossier(),
                receipt.getInterfaceCode(), now);
        if (origin.getImportLinkId() == null || origin.getCatalogImportTaskId() == null) {
            origin.attachChain(linkId, taskId, now);
        }
        origin.recordApplied(receipt.getId(), now);
        origins.saveAndFlush(origin);

        if (revisionId != null) {
            ImportDefinitionRevision revision = revisions.findById(revisionId).orElseThrow();
            String linkCurrency = links.findById(linkId).map(ImportLink::getDefaultCurrency).orElse(null);
            findings = checks.configurationReport(revision, linkCurrency).findings().stream()
                    .map(LegacyConfigApplyService::finding).toList();
        }

        LegacyConfigReport report = withIds(plan.report(), organisation, definitionId, revisionId, linkId, taskId);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("report", report);
        stored.put("previewFingerprint", plan.fingerprint());
        stored.put("reason", reason);
        stored.put("configurationFindings", findings);
        receipt.markApplied(now, actor.username(), actor.subject(), reason, json(stored));
        receipts.saveAndFlush(receipt);
        LegacyConfigReport applied = new LegacyConfigReport(report.receiptId(), report.contentHash(),
                LegacyConfigReceiptStatus.APPLIED.name(), report.outcome(), report.planned(), report.sharedWith(),
                report.items());
        return new ApplyResult(applied, LegacyConfigReceiptStatus.APPLIED.name(), findings);
    }

    /** Mappings en filters toevoegen via {@link SetupService} (die valideert); een TAB rechtstreeks (zie klasse). */
    private void writeRevisionContent(long revisionId, PlannedRevision planned, ActorIdentity actor) {
        if ("\t".equals(planned.delimiter())) {
            ImportDefinitionRevision revision = revisions.findById(revisionId).orElseThrow();
            revision.setStructureDelimiter("\t");
            RevisionConfigHashes.applyAll(revision);
            revisions.saveAndFlush(revision);
        }
        for (PlannedMapping mapping : planned.mappings()) {
            setup.addMapping(revisionId, new SetupService.CreateMappingCommand(mapping.targetCode(),
                    mapping.sourceReference(), null, mapping.valueKind(), null, mapping.fixedValue(), null, null,
                    null, null, null, null, mapping.transformKind(), mapping.transformConfig(), null, null), actor);
        }
        for (PlannedFilter filter : planned.filters()) {
            setup.addFilter(revisionId, new SetupService.CreateFilterCommand(filter.sequence(),
                    filter.sourceReference(), filter.operator(), filter.compareValue(), filter.outcome(),
                    filter.caseSensitive(), null, null, null, null), actor);
        }
    }

    private static SetupService.CreateRevisionCommand createCommand(PlannedRevision planned, String reason) {
        return new SetupService.CreateRevisionCommand(delimiterForSetup(planned), null, planned.charset(),
                planned.hasHeader(), planned.headerLineNumber(), LegacyConfigTranslator.FIELD_REFERENCE_KIND, null,
                planned.identityProfileKind(), planned.supplierField(), planned.groupField(),
                planned.referenceField(), planned.discountField(), planned.basePriceField(),
                planned.descriptionField(), null, CANONICALISATION_VERSION, null, null, null, null, null, null, null,
                null, null, reason, null);
    }

    /** {@code null} = ongewijzigd, {@code ""} = wissen ({@link SetupService.UpdateRevisionCommand}). */
    private static SetupService.UpdateRevisionCommand updateCommand(PlannedRevision planned) {
        String delimiter = "\t".equals(planned.delimiter()) ? null : planned.delimiter();
        return new SetupService.UpdateRevisionCommand(delimiter, null, planned.charset(), planned.hasHeader(),
                planned.headerLineNumber(), LegacyConfigTranslator.FIELD_REFERENCE_KIND, null,
                planned.identityProfileKind(), planned.supplierField(), planned.groupField(),
                planned.referenceField(), planned.discountField() == null ? "" : planned.discountField(),
                planned.basePriceField(), planned.descriptionField() == null ? "" : planned.descriptionField(), null,
                null, null, null, null, null, null, null, null, null, null, null, Boolean.TRUE, null);
    }

    /** Een TAB kan niet via {@code SetupInput.requireText} (blank); tijdelijk {@code ;}, daarna rechtstreeks gezet. */
    private static String delimiterForSetup(PlannedRevision planned) {
        return "\t".equals(planned.delimiter()) ? ";" : planned.delimiter();
    }

    private static LegacyConfigReport withIds(LegacyConfigReport report, SourceOrganisation organisation,
                                              long definitionId, Long revisionId, long linkId, long taskId) {
        Planned planned = report.planned();
        Planned withIds = new Planned(
                new PlannedOrganisation(planned.sourceOrganisation().action(), organisation.getId(),
                        organisation.getCode(), organisation.getName()),
                new PlannedDefinition(planned.definition().action(), definitionId, planned.definition().code(),
                        planned.definition().name(), planned.definition().revisionAction(),
                        planned.definition().basedOnRevision(), revisionId),
                new PlannedLink(planned.link().action(), linkId, planned.link().code(), planned.link().libraryCode()),
                new PlannedTask(planned.task().action(), taskId, planned.task().name()), planned.credential());
        return new LegacyConfigReport(report.receiptId(), report.contentHash(), report.receiptStatus(),
                report.outcome(), withIds, report.sharedWith(), report.items());
    }

    private static ConfigurationFinding finding(ConfigFinding finding) {
        return new ConfigurationFinding(finding.code(), finding.fieldName(), finding.message());
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    private Criticality defaultCriticality(String targetCode) {
        return fieldCatalog.findById(targetCode)
                .map(entry -> entry.getReferenceType() != null || entry.getPriceComponentCode() != null
                        ? Criticality.CRITICAL : Criticality.NON_CRITICAL)
                .orElse(Criticality.NON_CRITICAL);
    }

    private String fingerprint(LegacyConfigReport report, Collection<String> rows) {
        String text = json(report) + "\n" + String.join("\n", rows);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required but not available", impossible);
        }
    }

    private String json(Object value) {
        try {
            return canonical.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("The legacy-config report could not be serialised");
        }
    }

    private JsonNode readBundle(LegacyConfigReceipt receipt) {
        try {
            return bundleReader.readTree(receipt.getBundleJson());
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("bundle_json of legacy-config receipt " + receipt.getId()
                    + " is not readable");
        }
    }

    private static NotFoundException notFound(long receiptId) {
        return new NotFoundException(LegacyConfigReceiptService.CODE_RECEIPT_NOT_FOUND,
                "Legacy-config receipt " + receiptId + " not found");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
