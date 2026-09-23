package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportLinkBookmarkValue;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.service.support.BookmarkDeclarations;
import be.dda.catalogimport.service.support.BookmarkValueRules;
import be.dda.catalogimport.service.support.ImportMappingConfigFactory;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import be.dda.catalogimport.service.support.ScreeningBlockedException;
import be.dda.catalogimport.service.support.SourceStructureConfig;
import be.dda.catalogimport.service.support.SourceStructureConfigFactory;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * De materialisatiewizard: uit één sjabloonrevisie plus ingevulde bookmarkwaarden ontstaat een
 * leveranciersgebonden {@link ImportDefinition} + {@link ImportDefinitionRevision} + {@link ImportLink}
 * (sjabloon-materialisatie-design.md §1 R-MAT, §3, §4, §5; bouwstap 5c).
 *
 * <h2>Businessgedrag in het kort</h2>
 * <ul>
 *   <li><b>Snapshot, geen verwijzing (R-MAT-02).</b> Elke ingevulde waarde wordt <i>letterlijk</i> in
 *       haar doelkolom van de afgeleide revisie/koppeling geschreven én als waarderij bewaard met
 *       {@code source_template_revision_id}. Na materialisatie leest de runtime nooit meer het
 *       sjabloon; een latere sjabloonwijziging verandert dus geen enkele bestaande import.</li>
 *   <li><b>Activeert nooit (R-MAT, A33).</b> De afgeleide revisie is {@link RevisionStatus#DRAFT}.
 *       Screening, test en activatie lopen daarna via de gewone route (§14.16 stap 4/6). Er ontstaat
 *       geen {@code CatalogImportTask}, geen {@code Delivery}, geen {@code ImportBatch}.</li>
 *   <li><b>LINK-declaraties worden meegekopieerd (R-MAT-03, Q5).</b> De {@code LINK}-scope
 *       bookmarkdeclaraties van het sjabloon komen op de afgeleide revisie te staan, zodat "zijn alle
 *       verplichte LINK-bookmarks ingevuld?" bij het starten van een levering beantwoord kan worden
 *       zonder het sjabloon te lezen. De {@code DEFINITION}-scope declaraties gaan <b>niet</b> mee: die
 *       zijn opgelost en leven voort als {@code import_definition_bookmark_value}.</li>
 *   <li><b>Bewust niet idempotent (A35).</b> Twee keer dezelfde materialisatie botst op de bestaande
 *       unieke sleutels en geeft 409; er ontstaat nooit een duplicaat. Er komt geen idempotentiesleutel
 *       bij: de wizard is een eenmalige, interactieve handeling (§14.18).</li>
 *   <li><b>Eén transactie, alles of niets (§3).</b> Een half gematerialiseerde definitie (regels
 *       gekopieerd, koppeling niet) zou een definitie zonder eigenaar achterlaten die er geldig
 *       uitziet.</li>
 *   <li><b>Fase F valideert opnieuw.</b> Na het schrijven draaien dezelfde fabrieken als de screening
 *       over de afgeleide revisie. Een afgeleide definitie die pas bij de eerste levering blijkt te
 *       blokkeren, is onbruikbaar.</li>
 * </ul>
 *
 * <h2>Grenzen van bouwstap 5c</h2>
 * Uitsluitend {@link MaterialisationMode#NEW_DEFINITION} (hergebruik is 5d) en uitsluitend de plaatsen
 * {@link BookmarkUsagePlace#FIELD_MAPPING_FIXED_VALUE}, {@link BookmarkUsagePlace#RECORD_FILTER_COMPARE_VALUE},
 * {@link BookmarkUsagePlace#LINK_LIBRARY_CODE} en {@link BookmarkUsagePlace#LINK_SUPPLIER_ORGANISATION}.
 * {@link BookmarkUsagePlace#REVISION_IDENTITY_FIELD} en {@link BookmarkUsagePlace#LINK_SEARCH_SUPPLIER}
 * volgen in 5e, {@link BookmarkUsagePlace#REVISION_PRICE_POLICY} blijft geweigerd (§9 punt 4, A37).
 * Elk van die plaatsen wordt <b>uitdrukkelijk geweigerd</b> met
 * {@code CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED}, nooit stil genegeerd — een stil genegeerde bookmark zou
 * een leeg of verkeerd doelveld opleveren dat op een bewuste keuze lijkt.
 * <p>
 * {@code warnings} in het antwoord is in deze bouwstap altijd leeg; de getypeerde waarschuwingen
 * ({@code OPTIONAL_BOOKMARK_NOT_FILLED}, {@code LINK_SEARCH_SUPPLIER_NOT_DERIVED}) horen bij 5e (§11).
 * <p>
 * Autorisatie volgt in Fase 5; {@code materialisedBy} is voorlopig een requestveld met dezelfde regels
 * als {@code acceptedBy}/{@code decidedBy} (A38, Q1).
 */
@Service
@Transactional
public class TemplateMaterialisationService {

    /** {@code import_definition.code}, {@code import_link.code}, {@code source_organisation.code}. */
    private static final int MAX_CODE_LENGTH = 50;
    /** {@code description}/{@code name} van definitie en koppeling. */
    private static final int MAX_NAME_LENGTH = 200;
    /** {@code import_link.library_code}. */
    private static final int MAX_LIBRARY_CODE_LENGTH = 20;
    /** {@code created_by}/{@code filled_by}. */
    private static final int MAX_USER_LENGTH = 100;
    /** {@code value_text} van beide bookmarkwaardetabellen. */
    private static final int MAX_VALUE_LENGTH = 500;
    private static final int MAX_CHANGE_REASON_LENGTH = 500;
    private static final int MAX_BOOKMARK_NAME_LENGTH = 60;

    /**
     * De plaatsen die <b>deze</b> bouwstap toepast (§11, 5c). Bewust enger dan de witte lijst van
     * {@link BookmarkDeclarations}: die laat een sjabloon toe om nu al een
     * {@link BookmarkUsagePlace#REVISION_IDENTITY_FIELD}- of
     * {@link BookmarkUsagePlace#LINK_SEARCH_SUPPLIER}-bookmark te declareren (dat blijft geldig
     * declaratiewerk), maar materialiseren kan die plaatsen nog niet invullen. Ze worden daarom hier
     * geweigerd in plaats van in {@code BookmarkDeclarations}, zodat bouwstap 5b ongewijzigd blijft.
     */
    private static final Set<BookmarkUsagePlace> MATERIALISABLE_PLACES = EnumSet.of(
            BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE,
            BookmarkUsagePlace.LINK_LIBRARY_CODE, BookmarkUsagePlace.LINK_SUPPLIER_ORGANISATION);

    // --- Contract --------------------------------------------------------------------------------

    /**
     * Nieuw materialiseren of aan een bestaande, gedeelde definitie koppelen. Bewust <b>zonder
     * default</b> (§4 B1): een stil geraden keuze bepaalt of twee leveranciers voortaan één
     * configuratie delen. Leeft uitsluitend in het REST-/servicecontract, niet in de database (§8).
     */
    public enum MaterialisationMode {
        NEW_DEFINITION,
        REUSE_DEFINITION
    }

    /**
     * Eén ingevulde bookmarkwaarde.
     *
     * @param value {@code ""} is een <b>uitdrukkelijk lege</b> waarde en wordt zo bewaard; {@code null}
     *              is een ontbrekend veld en wordt geweigerd. Die twee zijn nooit hetzelfde (R-BMK-03).
     */
    public record BookmarkValue(String name, String value) {
    }

    /**
     * Het verzoek. Er zit <b>geen bronorganisatie</b> in: de afgeleide definitie erft die van het
     * sjabloon (A31, beslissingslog 23/09 keuze 4). De concrete leverancier zit op de koppeling.
     *
     * @param templateRevisionId        {@code null} = de {@code ACTIVE} sjabloonrevisie
     * @param reuseDefinitionId         alleen bij {@link MaterialisationMode#REUSE_DEFINITION} (5d)
     * @param changeReason              optioneel; zonder opgave een vaste zin met sjabloon en revisie
     * @param supplierOrganisationCode  tenzij het sjabloon een {@code LINK_SUPPLIER_ORGANISATION}-bookmark
     *                                  declareert — dan is die bookmark het invoerveld (§4 D7)
     * @param libraryCode               tenzij het sjabloon een {@code LINK_LIBRARY_CODE}-bookmark declareert
     * @param librarySearchSupplierCode nooit automatisch afgeleid uit de leverancier van de koppeling
     *                                  (R-BMK-04); blijft {@code null} wanneer niets ze meegeeft
     */
    public record MaterialiseRequest(Long templateRevisionId, MaterialisationMode mode, Long reuseDefinitionId,
                                     String definitionCode, String definitionName, String changeReason,
                                     String linkCode, String linkName, String supplierOrganisationCode,
                                     String libraryCode, String librarySearchSupplierCode,
                                     List<BookmarkValue> bookmarkValues, String materialisedBy) {
    }

    /** Eén toegepaste waarde: wat er ingevuld is, en waar het terechtgekomen is. */
    public record AppliedValue(String name, String dataType, String value, String placeKind,
                               String targetHint) {
    }

    /** Getypeerde waarschuwing; in bouwstap 5c altijd leeg (zie klasse-javadoc). */
    public record Warning(String code, String bookmarkName, String message) {
    }

    /**
     * Het antwoord.
     *
     * @param templateRevisionNumber toont altijd welke sjabloonversie effectief gebruikt is, samen met
     *                               {@code templateRevisionStatus} — materialiseren uit een
     *                               {@code SUPERSEDED} versie mag (Q3) maar mag nooit stilzwijgend
     *                               gebeuren (§4 fase A)
     * @param definitionCreated      onderscheidt nieuw van hergebruikt, zoals {@code IntakeResult.created}
     */
    public record MaterialisationView(long templateDefinitionId, long templateRevisionId,
                                      int templateRevisionNumber, String templateRevisionStatus,
                                      long definitionId, String definitionCode, boolean definitionCreated,
                                      long definitionRevisionId, int definitionRevisionNumber,
                                      String definitionRevisionStatus, long importLinkId,
                                      String importLinkCode, List<AppliedValue> definitionValues,
                                      List<AppliedValue> linkValues, List<Warning> warnings) {
    }

    private final ImportDefinitionRepository definitions;
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportFieldMappingRepository fieldMappings;
    private final ImportRecordFilterRepository recordFilters;
    private final ImportRevisionFieldCriticalityRepository fieldCriticalities;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkUsageRepository usages;
    private final ImportDefinitionBookmarkValueRepository definitionValues;
    private final ImportLinkRepository links;
    private final ImportLinkBookmarkValueRepository linkValues;
    private final SourceOrganisationRepository organisations;
    private final SourceStructureConfigFactory structureFactory;
    private final ImportMappingConfigFactory mappingFactory;

    public TemplateMaterialisationService(ImportDefinitionRepository definitions,
                                          ImportDefinitionRevisionRepository revisions,
                                          ImportFieldMappingRepository fieldMappings,
                                          ImportRecordFilterRepository recordFilters,
                                          ImportRevisionFieldCriticalityRepository fieldCriticalities,
                                          ImportDefinitionBookmarkRepository bookmarks,
                                          ImportDefinitionBookmarkUsageRepository usages,
                                          ImportDefinitionBookmarkValueRepository definitionValues,
                                          ImportLinkRepository links,
                                          ImportLinkBookmarkValueRepository linkValues,
                                          SourceOrganisationRepository organisations,
                                          SourceStructureConfigFactory structureFactory,
                                          ImportMappingConfigFactory mappingFactory) {
        this.definitions = definitions;
        this.revisions = revisions;
        this.fieldMappings = fieldMappings;
        this.recordFilters = recordFilters;
        this.fieldCriticalities = fieldCriticalities;
        this.bookmarks = bookmarks;
        this.usages = usages;
        this.definitionValues = definitionValues;
        this.links = links;
        this.linkValues = linkValues;
        this.organisations = organisations;
        this.structureFactory = structureFactory;
        this.mappingFactory = mappingFactory;
    }

    /**
     * Materialiseert het sjabloon tot een nieuwe definitie + revisie + koppeling. De fasen A t/m E
     * lopen volledig af <b>voordat</b> er één rij geschreven wordt (§4); fase F schrijft en valideert
     * daarna binnen dezelfde transactie.
     *
     * @throws NotFoundException   {@code TEMPLATE_NOT_FOUND}, {@code TEMPLATE_REVISION_NOT_FOUND},
     *                             {@code SOURCE_ORGANISATION_NOT_FOUND}
     * @throws ConflictException   {@code DEFINITION_NOT_A_TEMPLATE},
     *                             {@code TEMPLATE_REVISION_NOT_MATERIALISABLE},
     *                             {@code NO_ACTIVE_TEMPLATE_REVISION}, de fase C-codes
     *                             ({@code CONFIG_BOOKMARK_*}), {@code DEFINITION_CODE_IN_USE},
     *                             {@code LINK_CODE_IN_USE}, {@code LINK_SCOPE_IN_USE}
     * @throws BadRequestException {@code MATERIALISATION_MODE_REQUIRED},
     *                             {@code REUSE_DEFINITION_REQUIRED}, {@code REUSE_DEFINITION_NOT_ALLOWED},
     *                             {@code BOOKMARK_UNKNOWN}, {@code CONFIG_REQUIRED_BOOKMARK_MISSING},
     *                             {@code CONFIG_BOOKMARK_VALUE_INVALID},
     *                             {@code CONFIG_BOOKMARK_VALUE_TOO_LONG},
     *                             {@code LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT}, of de
     *                             {@code CONFIG_*}-code van een fase F-blokkade
     */
    public MaterialisationView materialise(long definitionId, MaterialiseRequest request) {
        MaterialiseRequest command = request == null
                ? new MaterialiseRequest(null, null, null, null, null, null, null, null, null, null, null,
                        null, null)
                : request;

        // --- Fase A: sjabloon en sjabloonversie oplossen ------------------------------------------
        ImportDefinition template = template(definitionId);
        ImportDefinitionRevision templateRevision = templateRevision(template, command.templateRevisionId());

        // --- Fase B: vorm van het verzoek ---------------------------------------------------------
        RequestShape shape = checkRequestShape(command);

        // --- Fase C: integriteit van de sjabloondeclaratie -----------------------------------------
        Declarations declarations = checkDeclarations(templateRevision);

        // --- Fase D: de ingevulde waarden ----------------------------------------------------------
        Values values = checkValues(command, declarations, shape);

        // --- Fase E: uniciteit ----------------------------------------------------------------------
        SourceOrganisation supplier = organisation(values.supplierOrganisationCode());
        checkUniqueness(template, shape);

        // --- Fase F: schrijven, en daarna nog eens valideren ----------------------------------------
        return write(template, templateRevision, shape, declarations, values, supplier);
    }

    // --- Fase A ------------------------------------------------------------------------------------

    private ImportDefinition template(long definitionId) {
        ImportDefinition definition = definitions.findById(definitionId)
                .orElseThrow(() -> new NotFoundException("TEMPLATE_NOT_FOUND",
                        "Import definition " + definitionId + " does not exist"));
        if (definition.getUsageType() != DefinitionUsageType.REUSABLE_TEMPLATE) {
            throw new ConflictException("DEFINITION_NOT_A_TEMPLATE", "Definition " + definitionId + " is "
                    + definition.getUsageType() + ", not a REUSABLE_TEMPLATE; only a template is materialised");
        }
        return definition;
    }

    /**
     * A3/A4. Zonder opgave wordt de {@code ACTIVE} sjabloonrevisie genomen. Een {@code SUPERSEDED}
     * revisie is uitdrukkelijk materialiseerbaar (Q3, beslissingslog 23/09): §14.16 laat de gebruiker
     * "de juiste sjabloonversie kiezen". Alleen {@code DRAFT} blijft geweigerd — dat sjabloon heeft zijn
     * eigen screening/validatie nog niet doorlopen.
     */
    private ImportDefinitionRevision templateRevision(ImportDefinition template, Long templateRevisionId) {
        long definitionId = template.getId();
        ImportDefinitionRevision revision;
        if (templateRevisionId == null) {
            revision = revisions.findByImportDefinitionIdAndStatus(definitionId, RevisionStatus.ACTIVE)
                    .orElseThrow(() -> new ConflictException("NO_ACTIVE_TEMPLATE_REVISION", "Template "
                            + definitionId + " has no ACTIVE revision; name the template revision explicitly"));
        } else {
            revision = revisions.findById(templateRevisionId)
                    .orElseThrow(() -> new NotFoundException("TEMPLATE_REVISION_NOT_FOUND",
                            "Definition revision " + templateRevisionId + " does not exist"));
            if (!revision.getImportDefinition().getId().equals(definitionId)) {
                throw new NotFoundException("TEMPLATE_REVISION_NOT_FOUND", "Revision " + templateRevisionId
                        + " does not belong to definition " + definitionId);
            }
        }
        if (revision.getStatus() != RevisionStatus.ACTIVE && revision.getStatus() != RevisionStatus.SUPERSEDED) {
            throw new ConflictException("TEMPLATE_REVISION_NOT_MATERIALISABLE", "Template revision "
                    + revision.getId() + " is " + revision.getStatus()
                    + "; only an ACTIVE or SUPERSEDED template revision is materialised");
        }
        return revision;
    }

    // --- Fase B ------------------------------------------------------------------------------------

    /** De gevalideerde vorm van het verzoek; alle tekst is al getrimd en op lengte gecontroleerd. */
    private record RequestShape(String definitionCode, String definitionName, String linkCode, String linkName,
                                String changeReason, String librarySearchSupplierCode, String materialisedBy) {
    }

    private RequestShape checkRequestShape(MaterialiseRequest command) {
        if (command.mode() == null) {
            throw new BadRequestException("MATERIALISATION_MODE_REQUIRED",
                    "mode is required and has no default; state NEW_DEFINITION or REUSE_DEFINITION "
                            + "explicitly — a guessed choice decides whether two suppliers share one "
                            + "configuration");
        }
        if (command.mode() == MaterialisationMode.REUSE_DEFINITION) {
            // Bouwstap 5d bouwt hergebruik. Tot dan wordt de modus geweigerd in plaats van stil als
            // "nieuw" behandeld te worden: dat zou een tweede definitie maken waar de aanroeper er
            // juist één wilde delen.
            throw new BadRequestException("REUSE_DEFINITION_NOT_ALLOWED",
                    "mode REUSE_DEFINITION is not available yet (build step 5d); this build only "
                            + "materialises a new definition");
        }
        if (command.reuseDefinitionId() != null) {
            throw new BadRequestException("REUSE_DEFINITION_NOT_ALLOWED",
                    "reuseDefinitionId only belongs to mode REUSE_DEFINITION");
        }
        String materialisedBy = ActorNames.requireActorName(command.materialisedBy(), "materialisedBy",
                MAX_USER_LENGTH);
        return new RequestShape(
                requireText(command.definitionCode(), "definitionCode", MAX_CODE_LENGTH),
                requireText(command.definitionName(), "definitionName", MAX_NAME_LENGTH),
                requireText(command.linkCode(), "linkCode", MAX_CODE_LENGTH),
                requireText(command.linkName(), "linkName", MAX_NAME_LENGTH),
                optionalText(command.changeReason(), "changeReason", MAX_CHANGE_REASON_LENGTH),
                optionalText(command.librarySearchSupplierCode(), "librarySearchSupplierCode",
                        MAX_CODE_LENGTH),
                materialisedBy);
    }

    // --- Fase C ------------------------------------------------------------------------------------

    /** De declaratie van de sjabloonrevisie, één keer gelezen en getoetst. */
    private record Declarations(List<ImportDefinitionBookmark> bookmarks,
                                Map<ImportDefinitionBookmark, List<ImportDefinitionBookmarkUsage>> usages,
                                Map<BookmarkUsagePlace, ImportDefinitionBookmark> linkPlaceOwners) {
    }

    /**
     * Fase C, de <b>defensieve</b> controle (§4, §13). De scope-/plaatsregel hoort bij het declareren
     * afgedwongen te worden, maar de database kan ze niet uitdrukken en een sjabloon kan ook via SQL
     * ontstaan. Materialisatie vertrouwt daarom niet op een guard die er niet is: één implementatie
     * ({@link BookmarkDeclarations}), twee aanroepplaatsen.
     */
    private Declarations checkDeclarations(ImportDefinitionRevision templateRevision) {
        long revisionId = templateRevision.getId();
        List<ImportDefinitionBookmark> declared =
                bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(revisionId);
        Map<ImportDefinitionBookmark, List<ImportDefinitionBookmarkUsage>> byBookmark = new LinkedHashMap<>();
        List<ImportDefinitionBookmarkUsage> allUsages = new ArrayList<>();
        for (ImportDefinitionBookmark bookmark : declared) {
            List<ImportDefinitionBookmarkUsage> own = usages.findByBookmarkId(bookmark.getId());
            byBookmark.put(bookmark, own);
            allUsages.addAll(own);
        }
        Set<String> mappedTargetFieldCodes = fieldMappings.findByRevisionIdWithTargetField(revisionId).stream()
                .map(mapping -> mapping.getTargetField().getCode())
                .collect(Collectors.toSet());
        Set<Integer> filterSequenceNumbers =
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId).stream()
                        .map(ImportRecordFilter::getSequenceNumber)
                        .collect(Collectors.toSet());
        List<BookmarkDeclarations.Problem> problems = BookmarkDeclarations.findProblems(declared, allUsages,
                mappedTargetFieldCodes, filterSequenceNumbers);
        if (!problems.isEmpty()) {
            BookmarkDeclarations.Problem problem = problems.get(0);
            throw new ConflictException(problem.code(), problem.message());
        }

        Map<BookmarkUsagePlace, ImportDefinitionBookmark> linkPlaceOwners = new HashMap<>();
        Map<String, ImportDefinitionBookmark> revisionTargetOwners = new HashMap<>();
        for (Map.Entry<ImportDefinitionBookmark, List<ImportDefinitionBookmarkUsage>> entry
                : byBookmark.entrySet()) {
            ImportDefinitionBookmark bookmark = entry.getKey();
            for (ImportDefinitionBookmarkUsage usage : entry.getValue()) {
                BookmarkUsagePlace place = usage.getPlaceKind();
                // C4, enger dan BookmarkDeclarations: deze bouwstap past maar vier plaatsen toe.
                if (!MATERIALISABLE_PLACES.contains(place)) {
                    throw new ConflictException("CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED", "Bookmark '"
                            + bookmark.getName() + "' is declared on place '" + place
                            + "', which this build cannot materialise yet; remove the usage or wait for "
                            + "the build step that supports it");
                }
                // Twee bronnen voor dezelfde waarde is altijd fout, ook binnen de declaratie zelf: laten
                // we de laatste bookmark winnen, dan hangt de uitkomst van de leesvolgorde af.
                ImportDefinitionBookmark other = isLinkPlace(place)
                        ? linkPlaceOwners.put(place, bookmark)
                        : revisionTargetOwners.put(place.name() + '' + usage.getTargetHint(), bookmark);
                if (other != null && other != bookmark) {
                    throw new ConflictException("CONFIG_BOOKMARK_PLACE_UNRESOLVED", "Bookmarks '"
                            + other.getName() + "' and '" + bookmark.getName() + "' both fill place '"
                            + place + "' target '" + usage.getTargetHint()
                            + "'; that target would have two sources");
                }
            }
        }
        return new Declarations(declared, byBookmark, linkPlaceOwners);
    }

    // --- Fase D ------------------------------------------------------------------------------------

    /** De uitkomst van fase D: welke waarde er per bookmark geldt, en wat de koppeling krijgt. */
    private record Values(Map<String, String> effective, String supplierOrganisationCode, String libraryCode) {
    }

    private Values checkValues(MaterialiseRequest command, Declarations declarations, RequestShape shape) {
        Map<String, ImportDefinitionBookmark> byName = declarations.bookmarks().stream()
                .collect(Collectors.toMap(ImportDefinitionBookmark::getName, bookmark -> bookmark));

        // D1 - een onbekende naam wordt nooit stil genegeerd.
        Map<String, String> supplied = new LinkedHashMap<>();
        for (BookmarkValue value : command.bookmarkValues() == null ? List.<BookmarkValue>of()
                : command.bookmarkValues()) {
            String name = requireText(value == null ? null : value.name(), "bookmarkValues[].name",
                    MAX_BOOKMARK_NAME_LENGTH);
            if (!byName.containsKey(name)) {
                throw new BadRequestException("BOOKMARK_UNKNOWN", "Bookmark '" + name
                        + "' is not declared in this template revision");
            }
            if (value.value() == null) {
                throw new IllegalArgumentException("bookmarkValues['" + name
                        + "'].value is missing; use \"\" for an explicitly empty value");
            }
            if (value.value().length() > MAX_VALUE_LENGTH) {
                throw new BadRequestException(BookmarkValueRules.CODE_VALUE_TOO_LONG, "Bookmark '" + name
                        + "': value is " + value.value().length() + " characters, at most "
                        + MAX_VALUE_LENGTH + " are stored");
            }
            if (supplied.put(name, value.value()) != null) {
                // Twee waarden voor dezelfde naam: welke van de twee zou er toegepast worden?
                throw new IllegalArgumentException("bookmarkValues contains '" + name
                        + "' more than once; a bookmark has exactly one value");
            }
        }

        // D2 - in NEW_DEFINITION zijn beide scopes in scope: de wizard maakt definitie én koppeling.
        // Er is hier dus geen scopeafwijzing; DEFINITION_SCOPE_VALUE_NOT_ALLOWED_ON_REUSE hoort bij 5d.

        Map<String, String> effective = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (ImportDefinitionBookmark bookmark : declarations.bookmarks()) {
            String value = supplied.get(bookmark.getName());
            if (value == null) {
                value = bookmark.getDefaultValue();
            }
            // D3/D4 - afwezigheid is "niet ingevuld", "" is "uitdrukkelijk leeg"; voor een verplicht
            // veld is geen van beide een invulling (R-BMK-03, R-VAL-04).
            if (bookmark.isRequired() && (value == null || value.isBlank())) {
                missing.add(bookmark.getName());
                continue;
            }
            if (value == null) {
                continue;
            }
            // D5/D6 - type, patroon, keuzelijst en de lengte van de doelkolom.
            String checked = value;
            BookmarkValueRules.checkType(bookmark.getDataType(), bookmark.getAllowedValues(),
                            bookmark.getValidationPattern(), checked)
                    .ifPresent(problem -> {
                        throw new BadRequestException(problem.code(),
                                "Bookmark '" + bookmark.getName() + "': " + problem.message());
                    });
            for (ImportDefinitionBookmarkUsage usage : declarations.usages().get(bookmark)) {
                BookmarkValueRules.checkLength(usage.getPlaceKind(), checked)
                        .ifPresent(problem -> {
                            throw new BadRequestException(problem.code(),
                                    "Bookmark '" + bookmark.getName() + "': " + problem.message());
                        });
            }
            effective.put(bookmark.getName(), value);
        }
        if (!missing.isEmpty()) {
            missing.sort(String::compareTo);
            throw new BadRequestException("CONFIG_REQUIRED_BOOKMARK_MISSING",
                    "Required bookmark(s) " + missing + " have no value; an empty string is an explicitly "
                            + "empty value and does not satisfy a required bookmark");
        }

        // D7 - een LINK_*-plaats heeft precies één bron: de bookmark óf het requestveld, nooit beide.
        String supplierCode = linkField(BookmarkUsagePlace.LINK_SUPPLIER_ORGANISATION, "supplierOrganisationCode",
                command.supplierOrganisationCode(), declarations, effective, MAX_CODE_LENGTH);
        String libraryCode = linkField(BookmarkUsagePlace.LINK_LIBRARY_CODE, "libraryCode",
                command.libraryCode(), declarations, effective, MAX_LIBRARY_CODE_LENGTH);
        return new Values(effective, supplierCode, libraryCode);
    }

    /**
     * De waarde van één {@code LINK_*}-kolom: uit de bookmark wanneer het sjabloon er één declareert,
     * anders uit het requestveld (§4 D7). Beide kolommen zijn {@code not null}, dus een gedeclareerde
     * maar niet ingevulde <i>optionele</i> bookmark laat de koppeling zonder waarde achter — dat is
     * hier {@code CONFIG_REQUIRED_BOOKMARK_MISSING} in plaats van een databasefout.
     */
    private String linkField(BookmarkUsagePlace place, String requestField, String requestValue,
                             Declarations declarations, Map<String, String> effective, int maxLength) {
        ImportDefinitionBookmark owner = declarations.linkPlaceOwners().get(place);
        if (owner == null) {
            return requireText(requestValue, requestField, maxLength);
        }
        if (requestValue != null) {
            throw new BadRequestException("LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT", "Bookmark '"
                    + owner.getName() + "' already fills " + requestField + " through place '" + place
                    + "'; leave the request field out so there is only one source for that value");
        }
        String value = effective.get(owner.getName());
        if (value == null || value.isBlank()) {
            throw new BadRequestException("CONFIG_REQUIRED_BOOKMARK_MISSING", "Bookmark '" + owner.getName()
                    + "' fills " + requestField + ", which the import link always needs; give it a value");
        }
        return requireText(value, requestField, maxLength);
    }

    // --- Fase E ------------------------------------------------------------------------------------

    private void checkUniqueness(ImportDefinition template, RequestShape shape) {
        long organisationId = template.getSourceOrganisation().getId();
        if (definitions.findBySourceOrganisationIdAndCode(organisationId, shape.definitionCode()).isPresent()) {
            throw new ConflictException("DEFINITION_CODE_IN_USE", "Definition code '" + shape.definitionCode()
                    + "' already exists for source organisation '"
                    + template.getSourceOrganisation().getCode() + "'");
        }
        if (links.findByCode(shape.linkCode()).isPresent()) {
            throw new ConflictException("LINK_CODE_IN_USE",
                    "Import link code '" + shape.linkCode() + "' already exists");
        }
        // De derde unieke sleutel (definitie + leverancier + bibliotheek) kan bij een nieuwe definitie
        // niet bezet zijn; ze wordt door de database alsnog bewaakt en hieronder vertaald.
    }

    private SourceOrganisation organisation(String code) {
        return organisations.findByCode(code)
                .orElseThrow(() -> new NotFoundException("SOURCE_ORGANISATION_NOT_FOUND",
                        "Source organisation '" + code + "' does not exist"));
    }

    // --- Fase F ------------------------------------------------------------------------------------

    private MaterialisationView write(ImportDefinition template, ImportDefinitionRevision templateRevision,
                                      RequestShape shape, Declarations declarations, Values values,
                                      SourceOrganisation supplier) {
        String actor = shape.materialisedBy();
        ImportDefinition definition;
        ImportDefinitionRevision revision;
        ImportLink link;
        try {
            definition = new ImportDefinition(template.getSourceOrganisation(), shape.definitionCode(),
                    shape.definitionName(), actor);
            // usage_type blijft OWN_DEFINITION: een afgeleide definitie is live configuratie, geen
            // blauwdruk. based_on legt de herkomst vast (§14.16 "Gebaseerd op").
            definition.setBasedOnDefinition(template);
            definition = definitions.saveAndFlush(definition);

            revision = copyRevision(definition, templateRevision, shape, actor);
            revision = revisions.saveAndFlush(revision);

            // De regels worden eerst in het geheugen gekopieerd, dan ingevuld, dan pas weggeschreven:
            // zo bestaat er geen tussentoestand waarin de sjabloon-placeholderwaarde al in de database
            // staat.
            Map<String, ImportFieldMapping> mappings = copyMappings(revision, templateRevision, actor);
            Map<Integer, ImportRecordFilter> filters = copyFilters(revision, templateRevision, actor);

            link = new ImportLink(shape.linkCode(), shape.linkName(), definition, supplier,
                    values.libraryCode());
            // R-BMK-04: de bibliotheekzoekleverancier wordt nooit afgeleid uit de leverancier van de
            // koppeling of uit een detailleverancier; zonder opgave blijft ze leeg.
            link.setLibrarySearchSupplierCode(shape.librarySearchSupplierCode());
            link = links.saveAndFlush(link);

            List<AppliedValue> appliedDefinition = new ArrayList<>();
            List<AppliedValue> appliedLink = new ArrayList<>();
            applyValues(templateRevision, declarations, values, revision, link, mappings, filters, actor,
                    appliedDefinition, appliedLink);

            fieldMappings.saveAll(mappings.values());
            recordFilters.saveAll(filters.values());
            copyFieldCriticalities(revision, templateRevision, actor);
            copyLinkScopeDeclarations(revision, declarations, actor);

            // De hashes beschrijven de revisie zoals ze nu is: pas berekenen nadat elke gematerialiseerde
            // waarde in haar revisieveld staat (§2).
            RevisionConfigHashes.applyAll(revision);
            revision = revisions.saveAndFlush(revision);
            fieldMappings.flush();
            recordFilters.flush();

            validateDerivedConfiguration(revision);
            return new MaterialisationView(template.getId(), templateRevision.getId(),
                    templateRevision.getRevisionNumber(), templateRevision.getStatus().name(),
                    definition.getId(), definition.getCode(), true, revision.getId(),
                    revision.getRevisionNumber(), revision.getStatus().name(), link.getId(), link.getCode(),
                    List.copyOf(appliedDefinition), List.copyOf(appliedLink), List.of());
        } catch (DataIntegrityViolationException violation) {
            throw translate(violation);
        }
    }

    /**
     * De afgeleide revisie: revisienummer 1, {@link RevisionStatus#DRAFT}, {@code based_on_revision_id}
     * naar de sjabloonrevisie, en verder een <b>volledige kopie</b> van de configuratievelden. De drie
     * laagversienummers beginnen bewust op 1: dit is revisie 1 van een nieuwe definitie, niet de
     * voortzetting van de versiereeks van het sjabloon.
     */
    private ImportDefinitionRevision copyRevision(ImportDefinition definition,
                                                  ImportDefinitionRevision source, RequestShape shape,
                                                  String actor) {
        ImportDefinitionRevision copy = new ImportDefinitionRevision(definition, 1,
                source.getIdentityProfileKind(), actor);
        copy.setStatus(RevisionStatus.DRAFT);
        copy.setBasedOnRevision(source);
        copy.setChangeReason(shape.changeReason() != null ? shape.changeReason()
                : defaultChangeReason(source));
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
        // De vier hashkolommen zijn not null: ze moeten al vóór de eerste insert kloppen. Nadat de
        // bookmarkwaarden toegepast zijn, worden ze opnieuw berekend (zie write): een waarde die een
        // revisieveld verandert, moet ook de hash veranderen (§2).
        RevisionConfigHashes.applyAll(copy);
        return copy;
    }

    /**
     * De drie drempelkolommen die sinds bouwstap 3h-4 niet meer gelezen worden (beslissingslog 20/09:
     * elke drempel is een percentage). Ze worden tóch meegekopieerd: de afgeleide revisie hoort een
     * getrouwe kopie te zijn, en een stil afwijkende opgeslagen waarde zou later, als iemand die kolom
     * weer zou lezen, een ander gedrag geven dan het sjabloon beschreef.
     */
    @SuppressWarnings("deprecation")
    private static void copyDeprecatedThresholds(ImportDefinitionRevision copy,
                                                 ImportDefinitionRevision source) {
        copy.setCreationThresholdAbsolute(source.getCreationThresholdAbsolute());
        copy.setMaxCriticalRecords(source.getMaxCriticalRecords());
        copy.setMaxRejectedRecords(source.getMaxRejectedRecords());
    }

    private String defaultChangeReason(ImportDefinitionRevision source) {
        String reason = "Materialised from template '" + source.getImportDefinition().getCode()
                + "' revision " + source.getRevisionNumber() + " (" + source.getStatus() + ")";
        return reason.length() > MAX_CHANGE_REASON_LENGTH
                ? reason.substring(0, MAX_CHANGE_REASON_LENGTH) : reason;
    }

    /**
     * @return de kopieën op doelveldcode — de sleutel waarmee een bookmark haar mapping aanwijst. Ze zijn
     *     nog <b>niet</b> weggeschreven: eerst wordt de bookmarkwaarde erin gezet, dan pas opgeslagen.
     */
    private Map<String, ImportFieldMapping> copyMappings(ImportDefinitionRevision revision,
                                                         ImportDefinitionRevision source, String actor) {
        Map<String, ImportFieldMapping> copies = new LinkedHashMap<>();
        for (ImportFieldMapping row : fieldMappings.findByRevisionIdWithTargetField(source.getId())) {
            ImportFieldMapping copy = new ImportFieldMapping(revision, row.getSequenceNumber(),
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
            copy.setCreatedBy(actor);
            copies.put(row.getTargetField().getCode(), copy);
        }
        return copies;
    }

    /** @return de nog niet weggeschreven kopieën op volgnummer — de sleutel waarmee een bookmark ze aanwijst */
    private Map<Integer, ImportRecordFilter> copyFilters(ImportDefinitionRevision revision,
                                                         ImportDefinitionRevision source, String actor) {
        Map<Integer, ImportRecordFilter> copies = new LinkedHashMap<>();
        for (ImportRecordFilter row
                : recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(source.getId())) {
            ImportRecordFilter copy = new ImportRecordFilter(revision, row.getSequenceNumber(),
                    row.getSourceReference(), row.getOperator(), row.getCompareValue(), row.getOutcome());
            copy.setFilterStage(row.getFilterStage());
            copy.setCaseSensitive(row.isCaseSensitive());
            copy.setTrimBeforeCompare(row.isTrimBeforeCompare());
            copy.setNullBehaviour(row.getNullBehaviour());
            copy.setMissingColumnBehaviour(row.getMissingColumnBehaviour());
            copy.setCreatedBy(actor);
            copies.put(row.getSequenceNumber(), copy);
        }
        return copies;
    }

    private void copyFieldCriticalities(ImportDefinitionRevision revision, ImportDefinitionRevision source,
                                        String actor) {
        for (ImportRevisionFieldCriticality row : fieldCriticalities.findByDefinitionRevisionId(source.getId())) {
            ImportRevisionFieldCriticality copy = new ImportRevisionFieldCriticality(revision.getId(),
                    row.getFieldKey(), row.getCriticality());
            copy.setCreatedBy(actor);
            fieldCriticalities.save(copy);
        }
    }

    /**
     * R-MAT-03 (Q5): de {@code LINK}-scope declaraties gaan mee naar de afgeleide revisie, de
     * {@code DEFINITION}-scope declaraties niet. Zonder deze kopie zou de controle "zijn alle
     * verplichte LINK-bookmarks ingevuld?" bij het starten van een levering het sjabloon moeten lezen —
     * precies het runtime-leespad dat R-MAT-02 verbiedt.
     */
    private void copyLinkScopeDeclarations(ImportDefinitionRevision revision, Declarations declarations,
                                           String actor) {
        for (ImportDefinitionBookmark source : declarations.bookmarks()) {
            if (source.getValueScope() != BookmarkValueScope.LINK) {
                continue;
            }
            ImportDefinitionBookmark copy = new ImportDefinitionBookmark(revision, source.getName(),
                    source.getLabel(), source.getDataType(), source.getValueScope(), source.getOwnerRole(),
                    source.getSortOrder());
            copy.setDescription(source.getDescription());
            copy.setRequired(source.isRequired());
            copy.setDefaultValue(source.getDefaultValue());
            copy.setAllowedValues(source.getAllowedValues());
            copy.setValidationPattern(source.getValidationPattern());
            copy.setCreatedBy(actor);
            ImportDefinitionBookmark stored = bookmarks.save(copy);
            for (ImportDefinitionBookmarkUsage usage : declarations.usages().get(source)) {
                usages.save(new ImportDefinitionBookmarkUsage(stored, usage.getPlaceKind(),
                        usage.getTargetHint()));
            }
        }
    }

    /**
     * Schrijft elke ingevulde waarde op haar doelplaats én als waarderij (R-MAT-02). De
     * <b>doelkolom</b> is voor de runtime de waarheid, de waarderij is het auditspoor van wat de wizard
     * invulde (A34); beide ontstaan in deze ene transactie.
     * <p>
     * De scope bepaalt wélke waarderij: {@code DEFINITION} landt op de afgeleide revisie met
     * {@code source_template_revision_id}, {@code LINK} op de koppeling. Een {@code LINK}-scope bookmark
     * op een revisieniveau-plaats (het {@code DETAILLEVERANCIER}-geval, Q2) is toegestaan: de waarde
     * gaat dan in de revisie én in de koppelingswaarderij, en 5d weigert die definitie later voor
     * hergebruik.
     */
    private void applyValues(ImportDefinitionRevision templateRevision, Declarations declarations,
                             Values values, ImportDefinitionRevision revision, ImportLink link,
                             Map<String, ImportFieldMapping> mappings, Map<Integer, ImportRecordFilter> filters,
                             String actor, List<AppliedValue> appliedDefinition,
                             List<AppliedValue> appliedLink) {
        for (ImportDefinitionBookmark bookmark : declarations.bookmarks()) {
            String value = values.effective().get(bookmark.getName());
            if (value == null) {
                // Optioneel en niet ingevuld: de plaats behoudt de sjabloonwaarde. De getypeerde
                // waarschuwing OPTIONAL_BOOKMARK_NOT_FILLED hoort bij bouwstap 5e (§11).
                continue;
            }
            boolean definitionScope = bookmark.getValueScope() == BookmarkValueScope.DEFINITION;
            for (ImportDefinitionBookmarkUsage usage : declarations.usages().get(bookmark)) {
                applyToTarget(usage, value, mappings, filters);
                AppliedValue applied = new AppliedValue(bookmark.getName(), bookmark.getDataType().name(),
                        value, usage.getPlaceKind().name(), usage.getTargetHint());
                (definitionScope ? appliedDefinition : appliedLink).add(applied);
            }
            if (definitionScope) {
                ImportDefinitionBookmarkValue row = new ImportDefinitionBookmarkValue(revision,
                        bookmark.getName(), bookmark.getDataType(), value, actor);
                row.setSourceTemplateRevision(templateRevision);
                definitionValues.save(row);
            } else {
                linkValues.save(new ImportLinkBookmarkValue(link, bookmark.getName(),
                        bookmark.getDataType(), value, actor));
            }
        }
    }

    private void applyToTarget(ImportDefinitionBookmarkUsage usage, String value,
                               Map<String, ImportFieldMapping> mappings,
                               Map<Integer, ImportRecordFilter> filters) {
        switch (usage.getPlaceKind()) {
            case FIELD_MAPPING_FIXED_VALUE -> {
                ImportFieldMapping mapping = mappings.get(usage.getTargetHint());
                if (mapping == null) {
                    // Onbereikbaar: fase C3 heeft het doel al tegen déze revisie geresolved.
                    throw new ConflictException("CONFIG_BOOKMARK_PLACE_UNRESOLVED", "Target field '"
                            + usage.getTargetHint() + "' has no mapping in this revision");
                }
                // FieldValueKind.BOOKMARK verschijnt nooit in een afgeleide revisie: materialisatie
                // schrijft altijd FIXED_VALUE met de werkelijke waarde (§7).
                mapping.setValueKind(FieldValueKind.FIXED_VALUE);
                mapping.setFixedValue(value);
            }
            case RECORD_FILTER_COMPARE_VALUE -> {
                ImportRecordFilter filter = filters.get(sequenceNumber(usage.getTargetHint()));
                if (filter == null) {
                    throw new ConflictException("CONFIG_BOOKMARK_PLACE_UNRESOLVED", "Record filter "
                            + usage.getTargetHint() + " does not exist in this revision");
                }
                filter.setCompareValue(value);
            }
            // De koppelingskolommen zijn in fase D al opgelost tot één bron per kolom en worden bij het
            // aanmaken van de ImportLink gezet; hier valt niets meer toe te passen.
            case LINK_LIBRARY_CODE, LINK_SUPPLIER_ORGANISATION -> {
                // Zie hierboven.
            }
            default -> throw new ConflictException("CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED",
                    "Place '" + usage.getPlaceKind() + "' is not materialised by this build");
        }
    }

    /**
     * Fase F: dezelfde fabrieken als de screening en als {@code SetupService.activateRevision}, nu op de
     * <b>afgeleide</b> revisie. Een {@link ScreeningBlockedException} is hier geen leveringsblokkade
     * maar een ongeldige aanvraag: 400 met de {@code CONFIG_*}-code, en de hele transactie rolt terug.
     */
    private void validateDerivedConfiguration(ImportDefinitionRevision revision) {
        try {
            SourceStructureConfig structure = structureFactory.from(revision);
            mappingFactory.from(revision, structure);
        } catch (ScreeningBlockedException invalid) {
            throw new BadRequestException(invalid.getCode(), invalid.getCode() + ": " + invalid.getMessage()
                    + " — the materialised definition would block on its first delivery, so nothing was "
                    + "created");
        }
    }

    // --- Hulpmiddelen --------------------------------------------------------------------------------

    private static boolean isLinkPlace(BookmarkUsagePlace place) {
        return place == BookmarkUsagePlace.LINK_LIBRARY_CODE
                || place == BookmarkUsagePlace.LINK_SEARCH_SUPPLIER
                || place == BookmarkUsagePlace.LINK_SUPPLIER_ORGANISATION;
    }

    private static int sequenceNumber(String targetHint) {
        try {
            return Integer.parseInt(targetHint);
        } catch (NumberFormatException notANumber) {
            throw new ConflictException("CONFIG_BOOKMARK_PLACE_UNRESOLVED",
                    "Record filter target '" + targetHint + "' is not a sequence number");
        }
    }

    /**
     * Vertaalt de drie unieke sleutels naar hun bestaande 409-code — hetzelfde patroon als
     * {@code DeliveryIntakeService} voor {@code uk_task_run_concurrency}. Dit is het racepad: twee
     * gelijktijdige materialisaties met dezelfde code komen allebei door fase E en botsen pas hier.
     */
    private static RuntimeException translate(DataIntegrityViolationException violation) {
        Throwable cause = violation.getMostSpecificCause();
        String text = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("uk_import_definition_code")) {
            return new ConflictException("DEFINITION_CODE_IN_USE",
                    "Definition code already exists for this source organisation");
        }
        if (text.contains("uk_import_link_code")) {
            return new ConflictException("LINK_CODE_IN_USE", "Import link code already exists");
        }
        if (text.contains("uk_import_link_scope")) {
            return new ConflictException("LINK_SCOPE_IN_USE",
                    "This definition already has a link for that supplier and library");
        }
        return violation;
    }

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
}
