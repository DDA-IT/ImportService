package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.service.support.BookmarkDeclarations;
import be.dda.catalogimport.service.support.BookmarkDeclarations.Problem;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Declareren en lezen van de bookmarks van één sjabloonrevisie (sjabloon-materialisatie-design.md §3,
 * §4 fase C, §5; bouwstap 5b). De ingevulde <b>waarden</b> (fase D) en de materialisatie zelf horen
 * hier nog niet bij — dat is bouwstap 5c.
 * <p>
 * <b>Declareren mag alleen op een {@link RevisionStatus#DRAFT}-revisie</b> van een sjabloondefinitie
 * ({@link DefinitionUsageType#REUSABLE_TEMPLATE}): exact hetzelfde patroon en dezelfde foutcode
 * ({@code REVISION_NOT_EDITABLE}) als {@code SetupService.editableRevision} — een bevroren revisie
 * wordt nooit bijgewerkt (§14.14).
 * <p>
 * <b>Validatie loopt via {@link BookmarkDeclarations}</b> (fase C, checks C1-C4): bij het toevoegen van
 * een usage-rij wordt die nieuwe rij getoetst en bij een gevonden probleem geweigerd met een 409 uit de
 * bestaande {@code CONFIG_*}-familie, binnen dezelfde transactie (dus zonder half geschreven usage-rij).
 * {@code GET .../bookmarks} toont diezelfde
 * bevindingen daarentegen "leesbaar zonder te werpen" (§5): een bookmark zonder enige usage-rij is
 * bijvoorbeeld altijd geldig om te declareren (usages komen in een volgende aanroep), maar verschijnt
 * meteen in de {@code problems}-lijst van het leesantwoord zolang er geen usage bij is — dat is precies
 * wat een invulscherm moet kunnen tonen.
 */
@Service
@Transactional
public class TemplateBookmarkService {

    private static final int MAX_NAME_LENGTH = 60;
    private static final int MAX_LABEL_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_OWNER_ROLE_LENGTH = 40;
    private static final int MAX_DEFAULT_VALUE_LENGTH = 500;
    private static final int MAX_ALLOWED_VALUES_LENGTH = 2000;
    private static final int MAX_VALIDATION_PATTERN_LENGTH = 200;
    private static final int MAX_TARGET_HINT_LENGTH = 200;
    /** Zelfde regel als de databasecheck {@code ck_import_definition_bookmark_name} (changeset 006-1). */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final String DEFAULT_CREATED_BY = "setup-api";

    // --- Opdrachten (request) --------------------------------------------------------------------

    public record DeclareBookmarkCommand(String name, String label, String description,
                                         BookmarkDataType dataType, BookmarkValueScope valueScope,
                                         String ownerRole, Boolean required, String defaultValue,
                                         String allowedValues, String validationPattern, Integer sortOrder,
                                         String createdBy) {
    }

    public record AddUsageCommand(BookmarkUsagePlace placeKind, String targetHint) {
    }

    // --- Antwoorden (response) -------------------------------------------------------------------

    public record TemplateView(long id, String code, String name, long sourceOrganisationId,
                               String sourceOrganisationCode) {
    }

    public record UsageView(long id, String placeKind, String targetHint) {
    }

    public record BookmarkView(long id, long revisionId, String name, String label, String description,
                               String dataType, String valueScope, String ownerRole, boolean required,
                               String defaultValue, String allowedValues, String validationPattern,
                               int sortOrder, List<UsageView> usages) {
    }

    public record ProblemView(String code, String bookmarkName, String message) {

        private static ProblemView of(Problem problem) {
            return new ProblemView(problem.code(), problem.bookmarkName(), problem.message());
        }
    }

    /** De invulset voor het scherm: alle bookmarks van de revisie, met haar fase C-bevindingen. */
    public record BookmarkSetView(long definitionId, long revisionId, List<BookmarkView> bookmarks,
                                  List<ProblemView> problems) {
    }

    private final ImportDefinitionRepository definitions;
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkUsageRepository usages;
    private final ImportFieldMappingRepository fieldMappings;
    private final ImportRecordFilterRepository recordFilters;

    public TemplateBookmarkService(ImportDefinitionRepository definitions,
                                   ImportDefinitionRevisionRepository revisions,
                                   ImportDefinitionBookmarkRepository bookmarks,
                                   ImportDefinitionBookmarkUsageRepository usages,
                                   ImportFieldMappingRepository fieldMappings,
                                   ImportRecordFilterRepository recordFilters) {
        this.definitions = definitions;
        this.revisions = revisions;
        this.bookmarks = bookmarks;
        this.usages = usages;
        this.fieldMappings = fieldMappings;
        this.recordFilters = recordFilters;
    }

    // --- Sjablonen ----------------------------------------------------------------------------------

    /** De sjablonen ({@code usage_type = REUSABLE_TEMPLATE}), gepagineerd. */
    @Transactional(readOnly = true)
    public PageResult<TemplateView> listTemplates(Integer page, Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<ImportDefinition> result = definitions.findByUsageType(DefinitionUsageType.REUSABLE_TEMPLATE,
                pageRequest);
        return PageResult.of(result, TemplateBookmarkService::view);
    }

    // --- Lezen ----------------------------------------------------------------------------------------

    /**
     * De invulset van één sjabloonrevisie, met de fase C-bevindingen als {@code problems} —
     * <b>leesbaar zonder te werpen</b> (§5). Werkt op elke revisiestatus (ook {@code ACTIVE}/
     * {@code SUPERSEDED}): materialisatie mag immers ook uit een niet-{@code DRAFT} sjabloonrevisie
     * (fase A4, Q3).
     *
     * @throws NotFoundException {@code TEMPLATE_NOT_FOUND}, {@code TEMPLATE_REVISION_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public BookmarkSetView getBookmarkSet(long definitionId, long revisionId) {
        ImportDefinitionRevision revision = templateRevision(definitionId, revisionId);
        List<ImportDefinitionBookmark> declared =
                bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(revisionId);
        List<ImportDefinitionBookmarkUsage> declaredUsages = declared.stream()
                .flatMap(bookmark -> usages.findByBookmarkId(bookmark.getId()).stream())
                .toList();
        List<Problem> problems = BookmarkDeclarations.findProblems(declared, declaredUsages,
                mappedTargetFieldCodes(revisionId), filterSequenceNumbers(revisionId));
        List<BookmarkView> bookmarkViews = declared.stream()
                .map(bookmark -> view(bookmark, declaredUsages))
                .toList();
        List<ProblemView> problemViews = problems.stream().map(ProblemView::of).toList();
        return new BookmarkSetView(definitionId, revisionId, bookmarkViews, problemViews);
    }

    // --- Declareren -----------------------------------------------------------------------------------

    /**
     * Declareert één nieuwe bookmark op een {@code DRAFT}-sjabloonrevisie. Draagt nog geen usage-rij:
     * die komt via {@link #addUsage}. Een bookmark zonder usage is daarom nooit een reden om te
     * weigeren op dit moment — ze verschijnt pas als {@code CONFIG_BOOKMARK_WITHOUT_PLACE} in
     * {@link #getBookmarkSet} zolang er geen usage bijkomt.
     *
     * @throws NotFoundException        {@code TEMPLATE_NOT_FOUND}, {@code TEMPLATE_REVISION_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_EDITABLE}, {@code DEFINITION_NOT_A_TEMPLATE},
     *                                  {@code BOOKMARK_NAME_IN_USE}, {@code BOOKMARK_ORDER_IN_USE}
     * @throws IllegalArgumentException ontbrekende, te lange of ongeldige velden
     */
    public BookmarkView declareBookmark(long definitionId, long revisionId, DeclareBookmarkCommand command) {
        ImportDefinitionRevision revision = editableTemplateRevision(definitionId, revisionId);
        String name = requireText(command.name(), "name", MAX_NAME_LENGTH);
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("name '" + name + "' must match " + NAME_PATTERN.pattern()
                    + " (an uppercase technical name, e.g. BESTANDS_PREFIX)");
        }
        String label = requireText(command.label(), "label", MAX_LABEL_LENGTH);
        String description = optionalText(command.description(), "description", MAX_DESCRIPTION_LENGTH);
        BookmarkDataType dataType = require(command.dataType(), "dataType");
        BookmarkValueScope valueScope = require(command.valueScope(), "valueScope");
        String ownerRole = requireText(command.ownerRole(), "ownerRole", MAX_OWNER_ROLE_LENGTH);
        String defaultValue = optionalText(command.defaultValue(), "defaultValue", MAX_DEFAULT_VALUE_LENGTH);
        String allowedValues = optionalText(command.allowedValues(), "allowedValues", MAX_ALLOWED_VALUES_LENGTH);
        // Zelfde regel als databasecheck ck_import_definition_bookmark_enum, hier vooraf getoetst voor
        // een leesbaar antwoord in plaats van een DataIntegrityViolationException.
        if (dataType == BookmarkDataType.ENUM && (allowedValues == null || allowedValues.isBlank())) {
            throw new IllegalArgumentException("dataType ENUM requires a non-blank allowedValues");
        }
        String validationPattern = optionalText(command.validationPattern(), "validationPattern",
                MAX_VALIDATION_PATTERN_LENGTH);
        if (validationPattern != null) {
            try {
                Pattern.compile(validationPattern);
            } catch (PatternSyntaxException invalidPattern) {
                throw new IllegalArgumentException("validationPattern '" + validationPattern
                        + "' is not a valid regular expression", invalidPattern);
            }
        }
        List<ImportDefinitionBookmark> existing = bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(
                revisionId);
        if (existing.stream().anyMatch(row -> name.equals(row.getName()))) {
            throw new ConflictException("BOOKMARK_NAME_IN_USE", "Bookmark '" + name
                    + "' is already declared on revision " + revisionId);
        }
        int sortOrder = command.sortOrder() != null ? command.sortOrder()
                : existing.stream().mapToInt(ImportDefinitionBookmark::getSortOrder).max().orElse(0) + 1;
        if (existing.stream().anyMatch(row -> row.getSortOrder() == sortOrder)) {
            throw new ConflictException("BOOKMARK_ORDER_IN_USE", "Sort order " + sortOrder
                    + " is already used on revision " + revisionId);
        }
        ImportDefinitionBookmark bookmark = new ImportDefinitionBookmark(revision, name, label, dataType,
                valueScope, ownerRole, sortOrder);
        bookmark.setDescription(description);
        bookmark.setRequired(command.required() == null || command.required());
        bookmark.setDefaultValue(defaultValue);
        bookmark.setAllowedValues(allowedValues);
        bookmark.setValidationPattern(validationPattern);
        bookmark.setCreatedBy(orDefault(command.createdBy(), DEFAULT_CREATED_BY));
        ImportDefinitionBookmark stored = bookmarks.saveAndFlush(bookmark);
        return view(stored, List.of());
    }

    /**
     * Voegt een toegelaten configuratieplaats toe aan een bestaande bookmark. De nieuwe usage-rij wordt
     * met {@link BookmarkDeclarations} getoetst (checks C1/C3/C4, per usage-rij onafhankelijk van de
     * andere usage-rijen van de bookmark); een gevonden probleem weigert de aanvraag en de usage-rij
     * wordt niet weggeschreven.
     *
     * @throws NotFoundException        {@code TEMPLATE_NOT_FOUND}, {@code TEMPLATE_REVISION_NOT_FOUND},
     *                                  {@code BOOKMARK_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_EDITABLE}, {@code DEFINITION_NOT_A_TEMPLATE},
     *                                  {@code BOOKMARK_USAGE_IN_USE}, of één van de fase C-codes
     *                                  ({@code CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT},
     *                                  {@code CONFIG_BOOKMARK_PLACE_UNRESOLVED},
     *                                  {@code CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED})
     * @throws IllegalArgumentException ontbrekende of te lange velden
     */
    public UsageView addUsage(long definitionId, long revisionId, String bookmarkName, AddUsageCommand command) {
        editableTemplateRevision(definitionId, revisionId);
        ImportDefinitionBookmark bookmark = bookmarks.findByDefinitionRevisionIdAndName(revisionId, bookmarkName)
                .orElseThrow(() -> new NotFoundException("BOOKMARK_NOT_FOUND", "Bookmark '" + bookmarkName
                        + "' is not declared on revision " + revisionId));
        BookmarkUsagePlace placeKind = require(command.placeKind(), "placeKind");
        String targetHint = command.targetHint() == null ? ""
                : requireLengthOnly(command.targetHint(), "targetHint", MAX_TARGET_HINT_LENGTH);
        List<ImportDefinitionBookmarkUsage> existing = usages.findByBookmarkId(bookmark.getId());
        if (existing.stream().anyMatch(row -> row.getPlaceKind() == placeKind
                && row.getTargetHint().equals(targetHint))) {
            throw new ConflictException("BOOKMARK_USAGE_IN_USE", "Bookmark '" + bookmarkName
                    + "' already has a usage on '" + placeKind + "' with target '" + targetHint + "'");
        }
        ImportDefinitionBookmarkUsage candidate = new ImportDefinitionBookmarkUsage(bookmark, placeKind,
                targetHint);
        // Checks C1/C3/C4 zijn per usage-rij onafhankelijk van de andere usage-rijen van de bookmark
        // (enkel C2, "geen enkele usage", is bookmark-breed en is hier per constructie nooit van
        // toepassing: er is precies één kandidaat-usage). Enkel de kandidaat toetsen is dus gelijk aan
        // de volledige declaratie toetsen, maar laat een reeds bestaand, ander probleem op deze bookmark
        // het toevoegen van een nieuwe, correcte usage niet blokkeren.
        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(candidate),
                mappedTargetFieldCodes(revisionId), filterSequenceNumbers(revisionId));
        if (!problems.isEmpty()) {
            Problem problem = problems.get(0);
            throw new ConflictException(problem.code(), problem.message());
        }
        ImportDefinitionBookmarkUsage stored = usages.saveAndFlush(candidate);
        return view(stored);
    }

    // --- Validatie en vertaling ---------------------------------------------------------------------

    private java.util.Set<String> mappedTargetFieldCodes(long revisionId) {
        return fieldMappings.findByRevisionIdWithTargetField(revisionId).stream()
                .map(mapping -> mapping.getTargetField().getCode())
                .collect(Collectors.toSet());
    }

    private java.util.Set<Integer> filterSequenceNumbers(long revisionId) {
        return recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId).stream()
                .map(ImportRecordFilter::getSequenceNumber)
                .collect(Collectors.toSet());
    }

    private ImportDefinitionRevision templateRevision(long definitionId, long revisionId) {
        ImportDefinition definition = definitions.findById(definitionId)
                .orElseThrow(() -> new NotFoundException("TEMPLATE_NOT_FOUND",
                        "Import definition " + definitionId + " does not exist"));
        ImportDefinitionRevision revision = revisions.findById(revisionId)
                .orElseThrow(() -> new NotFoundException("TEMPLATE_REVISION_NOT_FOUND",
                        "Definition revision " + revisionId + " does not exist"));
        if (!revision.getImportDefinition().getId().equals(definitionId)) {
            throw new NotFoundException("TEMPLATE_REVISION_NOT_FOUND", "Revision " + revisionId
                    + " does not belong to definition " + definitionId);
        }
        if (definition.getUsageType() != DefinitionUsageType.REUSABLE_TEMPLATE) {
            throw new ConflictException("DEFINITION_NOT_A_TEMPLATE", "Definition " + definitionId
                    + " is not a REUSABLE_TEMPLATE; bookmarks only apply to a template");
        }
        return revision;
    }

    /** Een bevroren revisie wordt nooit bijgewerkt (§14.14): enkel een DRAFT is bewerkbaar. */
    private ImportDefinitionRevision editableTemplateRevision(long definitionId, long revisionId) {
        ImportDefinitionRevision revision = templateRevision(definitionId, revisionId);
        if (revision.getStatus() != RevisionStatus.DRAFT) {
            throw new ConflictException("REVISION_NOT_EDITABLE", "Revision " + revisionId + " is "
                    + revision.getStatus() + "; a frozen revision is never changed, make a new revision");
        }
        return revision;
    }

    // --- Omzetting naar antwoorden --------------------------------------------------------------------

    private static TemplateView view(ImportDefinition definition) {
        return new TemplateView(definition.getId(), definition.getCode(), definition.getDescription(),
                definition.getSourceOrganisation().getId(), definition.getSourceOrganisation().getCode());
    }

    private static BookmarkView view(ImportDefinitionBookmark bookmark,
                                     List<ImportDefinitionBookmarkUsage> allUsages) {
        List<UsageView> own = allUsages.stream()
                .filter(usage -> usage.getBookmark() == bookmark)
                .map(TemplateBookmarkService::view)
                .toList();
        return new BookmarkView(bookmark.getId(), bookmark.getDefinitionRevision().getId(), bookmark.getName(),
                bookmark.getLabel(), bookmark.getDescription(), bookmark.getDataType().name(),
                bookmark.getValueScope().name(), bookmark.getOwnerRole(), bookmark.isRequired(),
                bookmark.getDefaultValue(), bookmark.getAllowedValues(), bookmark.getValidationPattern(),
                bookmark.getSortOrder(), own);
    }

    private static UsageView view(ImportDefinitionBookmarkUsage usage) {
        return new UsageView(usage.getId(), usage.getPlaceKind().name(), usage.getTargetHint());
    }

    // --- Hulpmiddelen ----------------------------------------------------------------------------------

    private static PageRequest pageRequest(Integer page, Integer size) {
        int number = page == null ? 0 : page;
        int requested = size == null ? BundleQueryService.DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, BundleQueryService.MAX_PAGE_SIZE));
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

    /** Zoals {@link #requireText}, maar {@code ""} blijft {@code ""} in plaats van te weigeren. */
    private static String requireLengthOnly(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
