package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportLinkBookmarkValue;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.service.support.BookmarkValueRules;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lezen en wijzigen van de {@link BookmarkValueScope#LINK}-bookmarkwaarden van één bestaande
 * {@link ImportLink} (sjabloon-materialisatie-design.md §5 en §7, beslissingslog 23/09 keuze 5,
 * bouwstap 5f).
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Slot bij een open batch.</b> Zolang de koppeling een niet-terminale batch heeft, wordt elke
 *       wijziging geweigerd met 409 {@code LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH}. Een bookmarkwaarde
 *       wijzigen terwijl er een levering onder die waarde loopt, maakt achteraf onbepaalbaar met welke
 *       invulling er gescreend is — precies wat {@code import_batch.bookmark_values_hash} moet
 *       bewijzen.</li>
 *   <li><b>Wezen.</b> Een waarderij waarvan de naam in de huidige actieve revisie niet (meer) als
 *       LINK-scope gedeclareerd staat, wordt getoond met {@code declared = false}, telt niet als
 *       ingevuld en wordt nooit toegepast. Ze blijft staan als auditmateriaal en kan ook niet
 *       gewijzigd worden: wijzigen kan alleen op een naam die de actieve revisie declareert (§7,
 *       javadoc {@link ImportLinkBookmarkValue}).</li>
 *   <li><b>Audit.</b> Een wijziging loopt altijd via {@link ImportLinkBookmarkValue#recordChange}, zodat
 *       {@code previous_value_text}, {@code updated_by} en {@code updated_at} samen geschreven worden;
 *       de twee databasechecks beletten een halve audit.</li>
 *   <li><b>{@code null} ≠ {@code ""} (R-BMK-03).</b> Geen rij betekent "niet ingevuld"; een rij met een
 *       lege waarde betekent "uitdrukkelijk leeg" en bevredigt een <i>verplichte</i> bookmark niet.
 *       Deze service laat {@code ""} toe als waarde maar telt ze nooit als ingevuld.</li>
 *   <li><b>Eén waarheid voor een {@code LINK_*}-plaats (5f-nalevering, beslissingslog 2026-09-23,
 *       A34).</b> Vult een bookmark {@link BookmarkUsagePlace#LINK_LIBRARY_CODE},
 *       {@link BookmarkUsagePlace#LINK_SEARCH_SUPPLIER} of
 *       {@link BookmarkUsagePlace#LINK_SUPPLIER_ORGANISATION}, dan werkt {@link #setValue} in dezelfde
 *       transactie ook de bijbehorende kolom op {@link ImportLink} bij — exact zoals
 *       {@code TemplateMaterialisationService} die drie plaatsen bij materialisatie schrijft. Waarde en
 *       kolom kunnen na een wijziging dus niet meer uiteenlopen: de bookmark blijft ook ná
 *       materialisatie het enige invoerveld voor die koppelingskolom.</li>
 * </ul>
 *
 * <h2>Grenzen van deze service</h2>
 * Buiten de drie {@code LINK_*}-plaatsen hierboven raakt ze geen andere kolom op {@link ImportLink} en
 * geen bevroren waarde in een revisie. {@code library_code} en de leveranciersorganisatie zijn
 * {@code NOT NULL}; een lege waarde op zo'n plaats leegt de kolom dus nooit stilzwijgend — dat blokkeert
 * met een leesbare 400 ({@code CONFIG_REQUIRED_BOOKMARK_MISSING}). {@code library_search_supplier_code}
 * is nullable en volgt de bookmarkwaarde rechtstreeks, net als bij materialisatie.
 * <p>
 * Autorisatie volgt in Fase 5; {@code updatedBy} is voorlopig een requestveld met dezelfde regels als
 * {@code acceptedBy}/{@code decidedBy} (A38).
 */
@Service
@Transactional
public class LinkBookmarkValueService {

    /** {@code import_link_bookmark_value.bookmark_name} is varchar(60). */
    private static final int MAX_NAME_LENGTH = 60;
    /** {@code import_link_bookmark_value.value_text} is varchar(500). */
    private static final int MAX_VALUE_LENGTH = 500;
    /** {@code import_link_bookmark_value.updated_by} is varchar(100). */
    private static final int MAX_USER_LENGTH = 100;

    /**
     * Eén ingevulde waarde.
     *
     * @param declared staat deze naam in de huidige actieve revisie als LINK-scope gedeclareerd?
     *                 {@code false} = wees: niet toegepast, alleen auditmateriaal
     * @param required alleen betekenisvol wanneer {@code declared}; anders altijd {@code false}
     * @param filled   is er een waarde die als ingevuld telt? {@code ""} telt niet (R-BMK-03)
     */
    public record LinkBookmarkValueRow(String bookmarkName, String label, String dataType, String valueText,
                                       String previousValueText, boolean declared, boolean required,
                                       boolean filled, Instant filledAt, String filledBy, Instant updatedAt,
                                       String updatedBy) {
    }

    /**
     * Het leesmodel van {@code GET /links/{id}/bookmark-values}.
     *
     * @param activeRevisionId     de revisie waartegen {@code declared} beoordeeld is; {@code null}
     *                             wanneer de definitie geen actieve revisie heeft — dan is er niets
     *                             gedeclareerd en is elke bestaande waarde een wees
     * @param missingRequiredNames verplichte LINK-bookmarks van de actieve revisie zonder ingevulde
     *                             waarde. Zolang deze lijst niet leeg is, weigert een nieuwe levering
     *                             met 409 {@code CONFIG_REQUIRED_BOOKMARK_MISSING} (§7)
     * @param lockedByOpenBatch    heeft de koppeling een open batch? Dan weigert elke wijziging
     */
    public record LinkBookmarkValues(long importLinkId, String importLinkCode, Long activeRevisionId,
                                     boolean lockedByOpenBatch, List<LinkBookmarkValueRow> values,
                                     List<String> missingRequiredNames) {
    }

    private final ImportLinkRepository links;
    private final ImportLinkBookmarkValueRepository values;
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkUsageRepository usages;
    private final ImportBatchRepository batches;
    private final SourceOrganisationRepository organisations;

    public LinkBookmarkValueService(ImportLinkRepository links, ImportLinkBookmarkValueRepository values,
                                    ImportDefinitionRevisionRepository revisions,
                                    ImportDefinitionBookmarkRepository bookmarks,
                                    ImportDefinitionBookmarkUsageRepository usages,
                                    ImportBatchRepository batches,
                                    SourceOrganisationRepository organisations) {
        this.links = links;
        this.values = values;
        this.revisions = revisions;
        this.bookmarks = bookmarks;
        this.usages = usages;
        this.batches = batches;
        this.organisations = organisations;
    }

    /**
     * Alle ingevulde waarden van de koppeling, wezen inbegrepen.
     *
     * @throws NotFoundException {@code LINK_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public LinkBookmarkValues list(long linkId) {
        ImportLink link = link(linkId);
        Optional<Long> activeRevisionId = activeRevision(link).map(revision -> revision.getId());
        Map<String, ImportDefinitionBookmark> declared = declaredLinkBookmarks(link);
        List<LinkBookmarkValueRow> rows = values.findByImportLinkIdOrderByBookmarkNameAsc(linkId).stream()
                .map(value -> row(value, declared.get(value.getBookmarkName())))
                .toList();
        Map<String, LinkBookmarkValueRow> byName = rows.stream()
                .collect(Collectors.toMap(LinkBookmarkValueRow::bookmarkName, Function.identity()));
        List<String> missing = declared.values().stream()
                .filter(ImportDefinitionBookmark::isRequired)
                .map(ImportDefinitionBookmark::getName)
                .filter(name -> !isFilled(byName.get(name)))
                .sorted()
                .toList();
        return new LinkBookmarkValues(link.getId(), link.getCode(), activeRevisionId.orElse(null),
                batches.existsByImportLinkIdAndOpenMarkerIsNotNull(linkId), rows, missing);
    }

    /**
     * Zet de waarde van één LINK-bookmark op deze koppeling. Bestaat er nog geen rij voor een
     * gedeclareerde naam, dan ontstaat ze hier; bestaat ze al, dan wordt ze met
     * {@link ImportLinkBookmarkValue#recordChange} bijgewerkt.
     * <p>
     * De volgorde van de controles is bewust: eerst de koppeling, dan het slot, dan de declaratie, dan
     * de waarde. Het slot gaat vóór de inhoud van het verzoek — zolang er een levering loopt, mag geen
     * enkele wijziging doorgaan, ongeacht of de rest van het verzoek klopt.
     *
     * @param valueText de nieuwe waarde; {@code ""} is een uitdrukkelijk lege waarde en wordt bewaard,
     *                  {@code null} is een ontbrekend veld en wordt geweigerd (R-BMK-03)
     * @throws NotFoundException        {@code LINK_NOT_FOUND}, {@code SOURCE_ORGANISATION_NOT_FOUND}
     *                                  (een {@code LINK_SUPPLIER_ORGANISATION}-waarde wijst naar een
     *                                  onbestaande leverancierscode)
     * @throws ConflictException        {@code LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH},
     *                                  {@code NO_ACTIVE_REVISION}
     * @throws BadRequestException      {@code BOOKMARK_UNKNOWN}, {@code BOOKMARK_SCOPE_MISMATCH},
     *                                  {@code CONFIG_BOOKMARK_VALUE_INVALID},
     *                                  {@code CONFIG_BOOKMARK_VALUE_TOO_LONG},
     *                                  {@code CONFIG_REQUIRED_BOOKMARK_MISSING} (een lege waarde zou
     *                                  {@code library_code} of de leveranciersorganisatie leegmaken,
     *                                  allebei {@code NOT NULL})
     * @throws IllegalArgumentException ontbrekende of te lange velden
     */
    public LinkBookmarkValueRow setValue(long linkId, String bookmarkName, String valueText, String updatedBy) {
        ImportLink link = link(linkId);
        String name = ActorNames.requireText(bookmarkName, "bookmarkName", MAX_NAME_LENGTH);
        String newValue = requireValue(valueText);
        String updater = ActorNames.requireActorName(updatedBy, "updatedBy", MAX_USER_LENGTH);

        // Het slot van keuze 5, vóór elke inhoudelijke beoordeling.
        if (batches.existsByImportLinkIdAndOpenMarkerIsNotNull(linkId)) {
            throw new ConflictException("LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH",
                    "Import link " + linkId + " has an open batch; a LINK bookmark value cannot change "
                            + "while a delivery is being processed under it");
        }

        ImportDefinitionBookmark declaration = declaration(link, name);
        validate(declaration, newValue);
        // 5f-nalevering: vóór er iets geschreven wordt, moet ook de propagatie naar de ImportLink-kolom
        // (indien van toepassing) al geldig zijn — geen halve wijziging bij een blokkerende plaats.
        applyToLink(link, declaration, newValue);

        Instant now = Instant.now();
        ImportLinkBookmarkValue stored = values.findByImportLinkIdAndBookmarkName(linkId, name)
                .map(existing -> {
                    // De gedenormaliseerde dataType volgt de declaratie waartegen zojuist gevalideerd
                    // is; anders zou de rij een type dragen dat nergens meer bestaat.
                    existing.setDataType(declaration.getDataType());
                    existing.recordChange(newValue, updater, now);
                    return existing;
                })
                .orElseGet(() -> new ImportLinkBookmarkValue(link, name, declaration.getDataType(), newValue,
                        updater));
        ImportLinkBookmarkValue saved = values.saveAndFlush(stored);
        links.saveAndFlush(link);
        return row(saved, declaration);
    }

    /**
     * De propagatie van A34: vult {@code declaration} één van de drie {@code LINK_*}-plaatsen, dan
     * schrijft deze methode de bijbehorende kolom op {@code link} mee, in dezelfde transactie als de
     * bookmarkwaarde zelf. Volgt dezelfde regels als {@code TemplateMaterialisationService} bij
     * materialisatie: {@code library_code} en de leveranciersorganisatie zijn verplicht en mogen nooit
     * leeg gemaakt worden; {@code library_search_supplier_code} is optioneel en volgt de waarde
     * rechtstreeks. Een bookmark zonder {@code LINK_*}-plaats laat {@code link} ongemoeid.
     */
    private void applyToLink(ImportLink link, ImportDefinitionBookmark declaration, String newValue) {
        for (ImportDefinitionBookmarkUsage usage : usages.findByBookmarkId(declaration.getId())) {
            switch (usage.getPlaceKind()) {
                case LINK_LIBRARY_CODE ->
                        link.setLibraryCode(requireLinkColumnValue(declaration, newValue, "libraryCode"));
                case LINK_SUPPLIER_ORGANISATION -> link.setSupplierOrganisation(
                        organisation(requireLinkColumnValue(declaration, newValue, "supplierOrganisationCode")));
                case LINK_SEARCH_SUPPLIER -> link.setLibrarySearchSupplierCode(newValue);
                default -> {
                    // Geen koppelingskolom voor deze plaats (bv. FIELD_MAPPING_FIXED_VALUE).
                }
            }
        }
    }

    /**
     * {@code library_code} en de leveranciersorganisatie zijn {@code NOT NULL} op {@link ImportLink}.
     * Een bookmark op die plaats mag dus nooit met een lege waarde bevredigd worden — dat blokkeert hier
     * leesbaar, net zoals {@code TemplateMaterialisationService#linkField} dat bij materialisatie doet,
     * in plaats van pas op een databasefout te stuiten.
     */
    private static String requireLinkColumnValue(ImportDefinitionBookmark declaration, String value,
                                                  String requestFieldLabel) {
        if (value.isBlank()) {
            throw new BadRequestException("CONFIG_REQUIRED_BOOKMARK_MISSING", "Bookmark '"
                    + declaration.getName() + "' fills " + requestFieldLabel + ", which the import link "
                    + "always needs; an empty value would leave that column without a value");
        }
        return value;
    }

    private SourceOrganisation organisation(String code) {
        return organisations.findByCode(code)
                .orElseThrow(() -> new NotFoundException("SOURCE_ORGANISATION_NOT_FOUND",
                        "Source organisation '" + code + "' does not exist"));
    }

    // --- Gedeeld met DeliveryIntakeService ---------------------------------------------------------

    /**
     * De verplichte LINK-bookmarks van {@code revision} die op {@code link} geen ingevulde waarde
     * hebben, op naam gesorteerd. Leeg betekent: de koppeling mag een levering starten (§7).
     * <p>
     * Een rij met een lege waarde telt hier <b>niet</b> als ingevuld (R-BMK-03/R-VAL-04), en een naam
     * die de revisie niet declareert telt niet mee — die waarde is een wees en wordt nooit toegepast.
     */
    @Transactional(readOnly = true)
    public List<String> missingRequiredValues(long linkId, long revisionId) {
        Map<String, ImportLinkBookmarkValue> filled = values.findByImportLinkIdOrderByBookmarkNameAsc(linkId)
                .stream()
                .collect(Collectors.toMap(ImportLinkBookmarkValue::getBookmarkName, Function.identity()));
        return bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(revisionId).stream()
                .filter(bookmark -> bookmark.getValueScope() == BookmarkValueScope.LINK)
                .filter(ImportDefinitionBookmark::isRequired)
                .map(ImportDefinitionBookmark::getName)
                .filter(name -> isBlank(filled.get(name)))
                .sorted()
                .toList();
    }

    // --- Hulpmiddelen -------------------------------------------------------------------------------

    private ImportLink link(long linkId) {
        return links.findById(linkId)
                .orElseThrow(() -> new NotFoundException("LINK_NOT_FOUND",
                        "Import link " + linkId + " does not exist"));
    }

    private Optional<ImportDefinitionRevision> activeRevision(ImportLink link) {
        return revisions.findByImportDefinitionIdAndStatus(link.getImportDefinition().getId(),
                RevisionStatus.ACTIVE);
    }

    /** De LINK-scope declaraties van de actieve revisie, op naam. Leeg zonder actieve revisie. */
    private Map<String, ImportDefinitionBookmark> declaredLinkBookmarks(ImportLink link) {
        return activeRevision(link)
                .map(revision -> bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(revision.getId()))
                .orElseGet(List::of).stream()
                .filter(bookmark -> bookmark.getValueScope() == BookmarkValueScope.LINK)
                .collect(Collectors.toMap(ImportDefinitionBookmark::getName, Function.identity()));
    }

    /**
     * De declaratie waartegen een wijziging beoordeeld wordt. Een naam die de actieve revisie niet
     * kent, wordt nooit stil aanvaard: dat zou een wees aanmaken die daarna toch niet toegepast wordt
     * (§4 D1, "nooit stil negeren").
     */
    private ImportDefinitionBookmark declaration(ImportLink link, String name) {
        ImportDefinitionRevision revision = activeRevision(link)
                .orElseThrow(() -> new ConflictException("NO_ACTIVE_REVISION",
                        "Import definition " + link.getImportDefinition().getId() + " has no active revision; "
                                + "there is nothing that declares which bookmarks this link has"));
        ImportDefinitionBookmark declaration = bookmarks
                .findByDefinitionRevisionIdOrderBySortOrderAsc(revision.getId()).stream()
                .filter(bookmark -> bookmark.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("BOOKMARK_UNKNOWN", "Bookmark '" + name
                        + "' is not declared in active revision " + revision.getRevisionNumber()
                        + " of import definition " + link.getImportDefinition().getId()));
        if (declaration.getValueScope() != BookmarkValueScope.LINK) {
            throw new BadRequestException("BOOKMARK_SCOPE_MISMATCH", "Bookmark '" + name + "' is declared as "
                    + declaration.getValueScope() + "; only a LINK scope bookmark is filled per import link");
        }
        return declaration;
    }

    /** D5 en D6 van het ontwerp, met dezelfde regels als de materialisatie ({@link BookmarkValueRules}). */
    private void validate(ImportDefinitionBookmark declaration, String newValue) {
        BookmarkValueRules.checkType(declaration.getDataType(), declaration.getAllowedValues(),
                        declaration.getValidationPattern(), newValue)
                .ifPresent(problem -> {
                    throw new BadRequestException(problem.code(),
                            "Bookmark '" + declaration.getName() + "': " + problem.message());
                });
        for (ImportDefinitionBookmarkUsage usage : usages.findByBookmarkId(declaration.getId())) {
            BookmarkValueRules.checkLength(usage.getPlaceKind(), newValue)
                    .ifPresent(problem -> {
                        throw new BadRequestException(problem.code(),
                                "Bookmark '" + declaration.getName() + "': " + problem.message());
                    });
        }
    }

    private static String requireValue(String valueText) {
        if (valueText == null) {
            throw new IllegalArgumentException("Missing value; use \"\" for an explicitly empty value");
        }
        if (valueText.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("value exceeds " + MAX_VALUE_LENGTH + " characters");
        }
        return valueText;
    }

    private static LinkBookmarkValueRow row(ImportLinkBookmarkValue value, ImportDefinitionBookmark declaration) {
        boolean declared = declaration != null;
        return new LinkBookmarkValueRow(value.getBookmarkName(), declared ? declaration.getLabel() : null,
                value.getDataType().name(), value.getValueText(), value.getPreviousValueText(), declared,
                declared && declaration.isRequired(), declared && !value.getValueText().isBlank(),
                value.getFilledAt(), value.getFilledBy(), value.getUpdatedAt(), value.getUpdatedBy());
    }

    private static boolean isFilled(LinkBookmarkValueRow row) {
        return row != null && row.filled();
    }

    /** Geen rij, of een rij zonder inhoud: allebei "niet ingevuld" voor een verplichte bookmark. */
    private static boolean isBlank(ImportLinkBookmarkValue value) {
        return value == null || value.getValueText() == null || value.getValueText().isBlank();
    }
}
