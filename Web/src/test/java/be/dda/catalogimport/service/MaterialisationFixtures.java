package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ApplicationContext;

/**
 * Bouwt de achtergrond voor de materialisatietests van bouwstap 5c: een bronorganisatie
 * (aankoopvereniging), een leverancier, en een sjabloondefinitie met één {@code ACTIVE} revisie die een
 * veldmapping en een recordfilter draagt — precies de twee revisieniveau-plaatsen die 5c materialiseert.
 *
 * <h2>Waarom de achtergrond hier en niet via de setup-API ontstaat</h2>
 * De tests moeten ook toestanden kunnen maken die de setup-API bewust <i>niet</i> toelaat (een
 * sjabloonrevisie op {@code SUPERSEDED}, een declaratie die fase C moet afkeuren). De rijen worden
 * daarom rechtstreeks via de repositories geschreven, met dezelfde inhoud die de setup-API zou
 * opleveren. De hashes worden met {@link RevisionConfigHashes#applyAll} gezet, zodat de
 * <i>sjabloon</i>revisie dezelfde canonieke hashes draagt als elke andere revisie — anders zou de
 * vergelijking met de afgeleide revisie niets bewijzen.
 *
 * <h2>Unieke codes per run</h2>
 * Het {@code local}-profiel draait tegen een blijvende PostgreSQL, niet tegen een verse H2 per run.
 * Elke code krijgt daarom een run-uniek voorvoegsel (zelfde patroon als
 * {@code TemplateBookmarkDeclarationTest} en {@code ImportTemplateBookmarkSchemaTest}); zonder dat
 * botst de tweede run op de rijen die de eerste achterliet.
 */
final class MaterialisationFixtures {

    /** Het doelveld waarop een {@code FIELD_MAPPING_FIXED_VALUE}-bookmark landt. */
    static final String MAPPED_FIELD = "E_SUPPLIER";
    /** Het volgnummer van het recordfilter waarop een {@code RECORD_FILTER_COMPARE_VALUE}-bookmark landt. */
    static final int FILTER_SEQUENCE = 1;
    /** De waarde die in het sjabloon staat zolang een bookmark ze niet vervangen heeft. */
    static final String TEMPLATE_PLACEHOLDER = "SJABLOON";
    static final String USER = "beheerder@example.test";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String RUN =
            Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();

    private final SourceOrganisationRepository organisations;
    private final ImportDefinitionRepository definitions;
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportFieldCatalogRepository fieldCatalog;
    private final ImportFieldMappingRepository fieldMappings;
    private final ImportRecordFilterRepository recordFilters;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkUsageRepository usages;

    private MaterialisationFixtures(ApplicationContext context) {
        this.organisations = context.getBean(SourceOrganisationRepository.class);
        this.definitions = context.getBean(ImportDefinitionRepository.class);
        this.revisions = context.getBean(ImportDefinitionRevisionRepository.class);
        this.fieldCatalog = context.getBean(ImportFieldCatalogRepository.class);
        this.fieldMappings = context.getBean(ImportFieldMappingRepository.class);
        this.recordFilters = context.getBean(ImportRecordFilterRepository.class);
        this.bookmarks = context.getBean(ImportDefinitionBookmarkRepository.class);
        this.usages = context.getBean(ImportDefinitionBookmarkUsageRepository.class);
    }

    static MaterialisationFixtures of(ApplicationContext context) {
        return new MaterialisationFixtures(context);
    }

    /**
     * Eén complete achtergrond.
     *
     * @param unique     het voorvoegsel waarmee elke code van deze test begint
     * @param definition het sjabloon ({@code REUSABLE_TEMPLATE})
     * @param revision   de sjabloonrevisie
     * @param source     de bronorganisatie van het sjabloon (de aankoopvereniging die levert)
     * @param supplier   een aparte leverancier voor de koppeling
     */
    record Template(String unique, ImportDefinition definition, ImportDefinitionRevision revision,
                    SourceOrganisation source, SourceOrganisation supplier) {

        String definitionCode() {
            return unique + "-DEF";
        }

        String linkCode() {
            return unique + "-LINK";
        }

        /** Een tweede koppelingscode op hetzelfde sjabloon, voor hergebruik (bouwstap 5d). */
        String linkCode(String suffix) {
            return unique + "-LINK" + suffix;
        }
    }

    /** Een sjabloon met een {@code ACTIVE} revisie. */
    Template template(String prefix) {
        return template(prefix, DefinitionUsageType.REUSABLE_TEMPLATE, RevisionStatus.ACTIVE);
    }

    Template template(String prefix, DefinitionUsageType usageType, RevisionStatus status) {
        String unique = "MAT" + RUN + SEQUENCE.incrementAndGet() + prefix;
        SourceOrganisation source = organisations.saveAndFlush(new SourceOrganisation(unique + "-SRC",
                unique + " aankoopvereniging", SourceOrganisationType.PURCHASING_ASSOCIATION));
        SourceOrganisation supplier = organisations.saveAndFlush(new SourceOrganisation(unique + "-SUP",
                unique + " leverancier", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = new ImportDefinition(source, unique + "-TPL", unique + " sjabloon",
                USER);
        definition.setUsageType(usageType);
        definition = definitions.saveAndFlush(definition);
        ImportDefinitionRevision revision = revisions.saveAndFlush(revision(definition, status));
        addMapping(revision);
        addFilter(revision, FilterOperator.EQUALS);
        return new Template(unique, definition, revision, source, supplier);
    }

    /** Een tweede revisie op hetzelfde sjabloon, zodat de eerste {@code SUPERSEDED} kan worden. */
    ImportDefinitionRevision supersede(Template template) {
        ImportDefinitionRevision old = revisions.findById(template.revision().getId()).orElseThrow();
        old.setStatus(RevisionStatus.SUPERSEDED);
        revisions.saveAndFlush(old);
        return old;
    }

    /**
     * Een tweede, {@code ACTIVE} sjabloonrevisie (nummer 2) met dezelfde structuur; de eerste wordt
     * {@code SUPERSEDED}. Nodig voor de sjabloonversieregel van bouwstap 5d (§6 punt 3): een definitie
     * die op revisie 1 bevroren is, mag niet stil hergebruikt worden wanneer het verzoek revisie 2
     * noemt.
     * <p>
     * De eerste revisie wordt <b>eerst</b> op {@code SUPERSEDED} gezet: er mag hoogstens één
     * {@code ACTIVE} revisie per definitie bestaan, en dat wordt ook op databaseniveau afgedwongen.
     * Bookmarkdeclaraties komen hier niet mee — de test declareert die zelf op de nieuwe revisie.
     */
    ImportDefinitionRevision nextRevision(Template template) {
        supersede(template);
        ImportDefinitionRevision next = revisions.saveAndFlush(
                revision(template.definition(), RevisionStatus.ACTIVE, 2));
        addMapping(next);
        addFilter(next, FilterOperator.EQUALS);
        return next;
    }

    private ImportDefinitionRevision revision(ImportDefinition definition, RevisionStatus status) {
        return revision(definition, status, 1);
    }

    private ImportDefinitionRevision revision(ImportDefinition definition, RevisionStatus status,
                                              int revisionNumber) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, revisionNumber,
                IdentityProfileKind.THREE_PART, USER);
        revision.setStatus(status);
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setRecordBasePriceField("PRIJS");
        revision.setStructureDelimiter(";");
        // Elke gemapte kolom moet door de vingerafdruk gedekt zijn; versie 2 is daarvoor de enige
        // toegelaten waarde (ImportMappingConfigFactory, par. 3.5).
        revision.setRecordCanonicalisationVersion(2);
        RevisionConfigHashes.applyAll(revision);
        return revision;
    }

    /**
     * Een extra leverancier bij hetzelfde sjabloon: de <b>tweede</b> leverancier die dezelfde gedeelde
     * definitie hergebruikt (bouwstap 5d). Twee leveranciers op één definitie is precies het geval dat
     * {@code REUSE_DEFINITION} bedient.
     */
    SourceOrganisation extraSupplier(Template template, String suffix) {
        return organisations.saveAndFlush(new SourceOrganisation(template.unique() + "-SUP" + suffix,
                template.unique() + " leverancier " + suffix, SourceOrganisationType.SUPPLIER));
    }

    /** Een vaste-waardemapping met de sjabloonwaarde erin: het doel van een FIELD_MAPPING_FIXED_VALUE. */
    private void addMapping(ImportDefinitionRevision revision) {
        ImportFieldCatalogEntry target = fieldCatalog.findById(MAPPED_FIELD).orElseThrow();
        ImportFieldMapping mapping = new ImportFieldMapping(revision, 1, target, FieldValueKind.FIXED_VALUE,
                target.getDataType(), target.getDefaultOwner(), target.getIdentityClass());
        mapping.setFixedValue(TEMPLATE_PLACEHOLDER);
        mapping.setCriticality(Criticality.NON_CRITICAL);
        mapping.setCreatedBy(USER);
        fieldMappings.saveAndFlush(mapping);
    }

    /** Een recordfilter met de sjabloonwaarde erin: het doel van een RECORD_FILTER_COMPARE_VALUE. */
    void addFilter(ImportDefinitionRevision revision, FilterOperator operator) {
        ImportRecordFilter filter = new ImportRecordFilter(revision, FILTER_SEQUENCE, "CULTUUR", operator,
                TEMPLATE_PLACEHOLDER, FilterOutcome.INCLUDE);
        filter.setCreatedBy(USER);
        recordFilters.saveAndFlush(filter);
    }

    /** Vervangt het recordfilter door één met een andere operator (voor de fase F-blokkade). */
    void replaceFilter(ImportDefinitionRevision revision, FilterOperator operator) {
        recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revision.getId())
                .forEach(recordFilters::delete);
        recordFilters.flush();
        addFilter(revision, operator);
    }

    ImportDefinitionBookmark declare(ImportDefinitionRevision revision, String name, BookmarkValueScope scope,
                                     boolean required, BookmarkDataType dataType, int sortOrder) {
        return declare(revision, name, scope, required, dataType, sortOrder, null, null);
    }

    /**
     * Keuzelijst en patroon horen bij de <b>insert</b> en niet bij een latere update: de databasecheck
     * {@code ck_import_definition_bookmark_enum} weigert een {@code ENUM}-declaratie zonder
     * {@code allowed_values} al bij het wegschrijven.
     */
    ImportDefinitionBookmark declare(ImportDefinitionRevision revision, String name, BookmarkValueScope scope,
                                     boolean required, BookmarkDataType dataType, int sortOrder,
                                     String allowedValues, String validationPattern) {
        ImportDefinitionBookmark bookmark = new ImportDefinitionBookmark(revision, name,
                name.toLowerCase(java.util.Locale.ROOT), dataType, scope, "catalogImport.manage", sortOrder);
        bookmark.setRequired(required);
        bookmark.setAllowedValues(allowedValues);
        bookmark.setValidationPattern(validationPattern);
        bookmark.setCreatedBy(USER);
        return bookmarks.saveAndFlush(bookmark);
    }

    /** Zet een {@code default_value} op een reeds gedeclareerde bookmark (fase D3). */
    ImportDefinitionBookmark withDefault(ImportDefinitionBookmark bookmark, String defaultValue) {
        bookmark.setDefaultValue(defaultValue);
        return bookmarks.saveAndFlush(bookmark);
    }

    ImportDefinitionBookmarkUsage usage(ImportDefinitionBookmark bookmark, BookmarkUsagePlace place,
                                        String targetHint) {
        return usages.saveAndFlush(new ImportDefinitionBookmarkUsage(bookmark, place, targetHint));
    }

    /** Een bookmark mét haar enige plaats, de meest voorkomende vorm in deze tests. */
    ImportDefinitionBookmark declareOn(ImportDefinitionRevision revision, String name,
                                       BookmarkValueScope scope, boolean required, BookmarkDataType dataType,
                                       int sortOrder, BookmarkUsagePlace place, String targetHint) {
        return declareOn(revision, name, scope, required, dataType, sortOrder, place, targetHint, null, null);
    }

    ImportDefinitionBookmark declareOn(ImportDefinitionRevision revision, String name,
                                       BookmarkValueScope scope, boolean required, BookmarkDataType dataType,
                                       int sortOrder, BookmarkUsagePlace place, String targetHint,
                                       String allowedValues, String validationPattern) {
        ImportDefinitionBookmark bookmark = declare(revision, name, scope, required, dataType, sortOrder,
                allowedValues, validationPattern);
        usage(bookmark, place, targetHint);
        return bookmark;
    }
}
