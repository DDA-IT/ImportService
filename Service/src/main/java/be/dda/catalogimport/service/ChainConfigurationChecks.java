package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.support.ConfigCheckReport;
import be.dda.catalogimport.service.support.ConfigFinding;
import be.dda.catalogimport.service.support.ConfigProblemCollector;
import be.dda.catalogimport.service.support.ImportMappingConfigFactory;
import be.dda.catalogimport.service.support.StructureFacts;
import be.dda.catalogimport.service.support.ScreeningBlockedException;
import be.dda.catalogimport.service.support.SourceStructureConfig;
import be.dda.catalogimport.service.support.SourceStructureConfigFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * De <b>ene</b> implementatie van de configuratiecontroles op een importketen die vóór een levering of een activatie
 * gelden (NT-8, beslissingslog 2026-09-30 "Nieuwe leverancier + taak (NT-spoor)", V1 = C: gereedheidscontrole zonder
 * bestand).
 * <p>
 * <b>Waarom één plek.</b> Drie aanroepers stellen dezelfde vraag "mag dit starten?":
 * <ul>
 *   <li>de upload en de servermap ({@code DeliveryIntakeService}) en de ophaalrun ({@code FetchRunService}, via
 *       {@code DeliveryIntakeService#requireIntakeConfiguration});</li>
 *   <li>het activeren van een revisie en de configuratievalidatie bij mapping/filter/kritiek
 *       ({@code SetupService});</li>
 *   <li>de gereedheidscontrole {@code GET /import-links/{id}/readiness} ({@code ImportLinkReadinessService}).</li>
 * </ul>
 * Elke controle hieronder <b>verzamelt</b> haar bevindingen als {@link Problem} in plaats van te werpen. De bestaande
 * werpers nemen de eerste bevinding en werpen exact de uitzondering die ze vroeger zelf wierpen — zelfde type (dus
 * zelfde HTTP-status), zelfde code, zelfde tekst en in dezelfde volgorde ({@link #throwFirst}). De gereedheidscontrole
 * toont ze allemaal. Zo kan de checklist nooit iets anders zeggen dan wat de upload of de activatie daarna doet.
 * <p>
 * <b>Grens.</b> De screeningfabrieken ({@link SourceStructureConfigFactory}, {@link ImportMappingConfigFactory})
 * kennen twee standen (NT-14a). {@link #configurationProblem} en {@link #activationProblems} gebruiken de stand FIRST:
 * hoogstens één configuratiebevinding, de eerste worp, voor activatie en setup. De gereedheidscontrole gebruikt de
 * stand ALL ({@link #configurationReport}, {@link #activationProblemsAll}): alle onafhankelijk te beoordelen
 * configuratiefouten, met de codes van overgeslagen afhankelijke controles. De andere controles (verplichte
 * DEFINITION- en LINK-bookmarks, taak) staan daar los van en worden altijd allemaal gemeld.
 * <p>
 * Enkel binnen een (lees)transactie aanroepen. Deze klasse schrijft nooit iets.
 */
@Component
public class ChainConfigurationChecks {

    public static final String CODE_TASK_NOT_MANUAL = "TASK_NOT_MANUAL";
    public static final String CODE_NO_ACTIVE_REVISION = "NO_ACTIVE_REVISION";
    public static final String CODE_PRICE_FIELD_MISSING = SourceStructureConfigFactory.CODE_PRICE_FIELD_MISSING;
    public static final String CODE_REQUIRED_BOOKMARK_MISSING = "CONFIG_REQUIRED_BOOKMARK_MISSING";

    /**
     * Eén bevinding.
     *
     * @param code    de stabiele code die de werper ook in zijn antwoord zet; {@code null} enkel bij een
     *                configuratiefout zonder code (komt in de fabrieken niet voor, zie {@link #configurationProblem})
     * @param message de ongewijzigde tekst van de werper
     * @param failure de uitzondering die de werper voor deze bevinding werpt (bepaalt de HTTP-status)
     */
    public record Problem(String code, String message, RuntimeException failure) {
    }

    /**
     * De configuratie van een intake.
     *
     * @param activeRevision de actieve revisie, of {@code null} zonder actieve revisie
     * @param problems       in de volgorde waarin de intake ze vroeger wierp; leeg = de intake mag starten
     */
    public record IntakeConfiguration(ImportDefinitionRevision activeRevision, List<Problem> problems) {

        /** Het gedrag van vóór NT-8: de eerste bevinding werpen, anders de actieve revisie. */
        public ImportDefinitionRevision requireActiveRevision() {
            throwFirst(problems);
            return activeRevision;
        }
    }

    private final ImportDefinitionRevisionRepository revisions;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkValueRepository bookmarkValues;
    private final LinkBookmarkValueService linkBookmarkValues;
    private final SourceStructureConfigFactory structureFactory;
    private final ImportMappingConfigFactory mappingFactory;

    public ChainConfigurationChecks(ImportDefinitionRevisionRepository revisions,
                                    ImportDefinitionBookmarkRepository bookmarks,
                                    ImportDefinitionBookmarkValueRepository bookmarkValues,
                                    LinkBookmarkValueService linkBookmarkValues,
                                    SourceStructureConfigFactory structureFactory,
                                    ImportMappingConfigFactory mappingFactory) {
        this.revisions = revisions;
        this.bookmarks = bookmarks;
        this.bookmarkValues = bookmarkValues;
        this.linkBookmarkValues = linkBookmarkValues;
        this.structureFactory = structureFactory;
        this.mappingFactory = mappingFactory;
    }

    /** Werpt de eerste bevinding (de werper van vóór NT-8); doet niets bij een lege lijst. */
    public static void throwFirst(List<Problem> problems) {
        if (!problems.isEmpty()) {
            throw problems.get(0).failure();
        }
    }

    // --- Upload en servermap: de taak zelf ------------------------------------------------------------------------

    /**
     * De taakcontroles van de manuele ontvangstwegen (upload en servermap), in de volgorde van
     * {@code DeliveryIntakeService}: {@code TASK_NOT_MANUAL}, dan {@code TASK_HAS_DELIVERY_CONFIGURATION} (A10,
     * beslissingslog 2026-09-29, LC-2). Allebei 409. De ophaalrun doet deze controles bewust niet (K-4b).
     */
    public List<Problem> manualIntakeProblems(CatalogImportTask task) {
        List<Problem> problems = new ArrayList<>();
        long taskId = task.getId();
        if (task.getTriggerType() != TaskTriggerType.MANUAL) {
            problems.add(conflict(CODE_TASK_NOT_MANUAL,
                    "Task " + taskId + " does not accept manual uploads (trigger type "
                            + task.getTriggerType() + ")"));
        }
        // A10: een taak met een Leveringsconfiguratie haalt zelf op; upload en servermap worden geweigerd, ook als
        // retry, vóór er iets gearchiveerd wordt.
        if (task.getDeliveryConfigurationVersion() != null) {
            problems.add(conflict(DeliveryIntakeService.CODE_TASK_HAS_DELIVERY_CONFIGURATION, "Task " + taskId
                    + " fetches its deliveries through a delivery configuration; upload and server directory are "
                    + "not accepted for it"));
        }
        return List.copyOf(problems);
    }

    // --- Elke intake (upload, servermap, ophaalrun): de configuratie van de koppeling ------------------------------

    /**
     * De configuratiecontroles die elke intake vóór het registreren doet: een actieve revisie, een basisprijsveld en
     * alle verplichte LINK-bookmarkwaarden. Allemaal 409. Zonder actieve revisie is er niets om verder te toetsen: dan
     * is {@code NO_ACTIVE_REVISION} de enige bevinding.
     *
     * @param owner het onderwerp in de tekst van {@code NO_ACTIVE_REVISION}, bv. {@code "Task 12"} (de intake) of
     *              {@code "Import link 5"} (de gereedheidscontrole)
     */
    public IntakeConfiguration intakeConfiguration(ImportLink link, String owner) {
        ImportDefinitionRevision revision = revisions
                .findByImportDefinitionIdAndStatus(link.getImportDefinition().getId(), RevisionStatus.ACTIVE)
                .orElse(null);
        if (revision == null) {
            return new IntakeConfiguration(null, List.of(conflict(CODE_NO_ACTIVE_REVISION,
                    owner + " has no active import definition revision")));
        }
        List<Problem> problems = new ArrayList<>();
        String priceField = revision.getRecordBasePriceField();
        if (priceField == null || priceField.isBlank()) {
            problems.add(conflict(CODE_PRICE_FIELD_MISSING,
                    "Active revision " + revision.getRevisionNumber() + " has no base price field configured"));
        }
        // Blokkeerpunt verplichte LINK-bookmarks (beslissingslog 23/09 keuze 6, vraag Q4, ontwerp §7): dezelfde vorm,
        // plaats en foutfamilie als de prijsveldcontrole hierboven. De upload wordt geweigerd vóór er iets gearchiveerd
        // of geregistreerd is, en de batch wordt niet op BLOCKED gezet - de serverstand is onvolledig, niet de levering.
        requiredLinkBookmarkProblem(link, revision, "active revision").ifPresent(problems::add);
        return new IntakeConfiguration(revision, List.copyOf(problems));
    }

    /**
     * De verplichte LINK-bookmarks van {@code revision} zonder ingevulde waarde op {@code link} (409
     * {@code CONFIG_REQUIRED_BOOKMARK_MISSING}). {@code ""} telt niet als ingevuld (R-BMK-03).
     *
     * @param revisionRole hoe de revisie in de tekst heet: {@code "active revision"} (de intake, tekst ongewijzigd)
     *                     of {@code "draft revision"} (de gereedheidscontrole op een concept)
     */
    public Optional<Problem> requiredLinkBookmarkProblem(ImportLink link, ImportDefinitionRevision revision,
                                                         String revisionRole) {
        List<String> missing = linkBookmarkValues.missingRequiredValues(link.getId(), revision.getId());
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(conflict(CODE_REQUIRED_BOOKMARK_MISSING, "Import link " + link.getId()
                + " has no value for required LINK bookmark(s) " + missing + " declared in " + revisionRole + " "
                + revision.getRevisionNumber()));
    }

    // --- Activeren van een revisie ----------------------------------------------------------------------------------

    /**
     * Wat het activeren van {@code revision} blokkeert, in de volgorde van {@code SetupService#activateRevision}: eerst
     * de configuratievalidatie ({@link #configurationProblem}, 400), dan de verplichte DEFINITION-bookmarks
     * ({@link #definitionBookmarkProblem}, 409). De statuscontrole ({@code REVISION_NOT_ACTIVATABLE}) hoort hier niet
     * bij: die gaat over de revisie, niet over haar configuratie.
     */
    public List<Problem> activationProblems(ImportDefinitionRevision revision) {
        List<Problem> problems = new ArrayList<>();
        configurationProblem(revision).ifPresent(problems::add);
        definitionBookmarkProblem(revision).ifPresent(problems::add);
        return List.copyOf(problems);
    }

    /**
     * Valideert de volledige configuratie met dezelfde fabrieken als de screening. Een
     * {@link ScreeningBlockedException} is hier geen leveringsblokkade maar een ongeldige aanvraag: een 400 met de
     * {@code CONFIG_*}-code in de boodschap én als code (NT-3). Zonder code (komt in de fabrieken niet voor) blijft het
     * de oude 400 zonder code, nooit een lege code.
     * <p>
     * Hoogstens één bevinding (stand FIRST: de eerste worp, zie de klassedocumentatie); alle bevindingen geeft
     * {@link #configurationReport}.
     */
    public Optional<Problem> configurationProblem(ImportDefinitionRevision revision) {
        try {
            SourceStructureConfig structure = structureFactory.from(revision);
            mappingFactory.from(revision, structure);
            return Optional.empty();
        } catch (ScreeningBlockedException invalid) {
            return Optional.of(configurationProblem(invalid.getCode(), invalid.getMessage(), invalid));
        }
    }

    /** Eén configuratiebevinding in het detailformaat van de activatie ({@code code + ": " + message}). */
    private static Problem configurationProblem(String code, String rawMessage, ScreeningBlockedException cause) {
        String message = code + ": " + rawMessage;
        if (code == null || code.isBlank()) {
            return new Problem(null, message, new IllegalArgumentException(message, cause));
        }
        return new Problem(code, message, new BadRequestException(code, message, cause));
    }

    /**
     * Een bevinding met de extra gegevens van de collector (NT-14-2), voor de gereedheidscontrole.
     *
     * @param problem       code, detail ({@code code + ": " + message}) en uitzondering, exact als de activatie
     * @param fieldName     het {@code fieldName} van de fout zoals in de exception; {@code null} bij structuurfouten en
     *                      bij de DEFINITION-bookmarkcontrole
     * @param revisionField de sleutel uit het woordenlijstdomein {@code revisionField}, of {@code null}
     */
    public record ReportedProblem(Problem problem, String fieldName, String revisionField) {
    }

    /**
     * Alle bevindingen van {@link #activationProblemsAll}.
     *
     * @param skippedBecause de codes van de grondoorzaken waarvan afhankelijke controles niet beoordeeld werden
     */
    public record ActivationReport(List<ReportedProblem> problems, List<String> skippedBecause) {
    }

    /**
     * ALL-stand van de configuratievalidatie (NT-14a par. 3): alle onafhankelijk te beoordelen fouten, met dezelfde
     * fabrieken en volgorde als {@link #configurationProblem}. Eerste bevinding = wat {@code configurationProblem}
     * (FIRST) zou geven, behalve dat hier de vaste valuta van de koppeling meegegeven wordt (zoals de screening).
     *
     * @param linkCurrency de vaste valuta van de koppeling, of {@code null}
     */
    public ConfigCheckReport configurationReport(ImportDefinitionRevision revision, String linkCurrency) {
        ConfigProblemCollector collector = ConfigProblemCollector.all();
        StructureFacts facts = structureFactory.collectInto(revision, linkCurrency, collector);
        mappingFactory.collectInto(revision, facts, collector);
        return collector.toReport();
    }

    /**
     * Alles wat het activeren van {@code draft} zou blokkeren, voor de gereedheidscontrole: alle configuratiefouten
     * ({@link #configurationReport}), gevolgd door de DEFINITION-bookmarkcontrole ({@link #definitionBookmarkProblem}).
     * Activatie en setup gebruiken dit niet; zij blijven {@link #activationProblems} (FIRST).
     */
    public ActivationReport activationProblemsAll(ImportDefinitionRevision draft, String linkCurrency) {
        ConfigCheckReport report = configurationReport(draft, linkCurrency);
        List<ReportedProblem> problems = new ArrayList<>();
        for (ConfigFinding finding : report.findings()) {
            problems.add(new ReportedProblem(
                    configurationProblem(finding.code(), finding.message(), null),
                    finding.fieldName(), finding.revisionField()));
        }
        definitionBookmarkProblem(draft)
                .ifPresent(problem -> problems.add(new ReportedProblem(problem, null, null)));
        return new ActivationReport(List.copyOf(problems), report.skippedBecause());
    }

    /**
     * Blokkeerpunt bij het activeren (beslissingslog 23/09 keuze 6, ontwerp §7): elke <b>verplichte</b>
     * {@link BookmarkValueScope#DEFINITION}-bookmark van deze revisie moet een
     * {@code import_definition_bookmark_value}-rij met een niet-lege waarde hebben, anders 409
     * {@code CONFIG_REQUIRED_BOOKMARK_MISSING}. Een revisie die live gaat met een open invulveld zou dat veld stil leeg
     * toepassen op elke levering die erop draait.
     * <p>
     * <b>{@code ""} bevredigt een verplichte bookmark niet</b> (R-BMK-03/R-VAL-04): afwezigheid van een rij is "niet
     * ingevuld", een lege waarde is "uitdrukkelijk leeg" — voor een verplicht veld is geen van beide een invulling.
     * <p>
     * <b>LINK-scope wordt hier niet beoordeeld</b>: op dit moment bestaat er nog geen of meer dan één koppeling. Die
     * controle gebeurt bij de start van een levering ({@link #intakeConfiguration}).
     * <p>
     * <b>Een sjabloon wordt overgeslagen.</b> Een {@link DefinitionUsageType#REUSABLE_TEMPLATE} is per definitie een
     * blauwdruk: zijn DEFINITION-bookmarks worden pas bij materialisatie ingevuld (§14.16 stap 4, R-MAT-02) en de
     * waarderijen ontstaan op de <i>afgeleide</i> revisie. Zou de controle hier ook op een sjabloon slaan, dan kon een
     * sjabloon met een verplichte DEFINITION-bookmark nooit {@code ACTIVE} worden en dus nooit gematerialiseerd worden
     * (fase A3/A4 eist een niet-{@code DRAFT} sjabloonrevisie) — het mechanisme zou zichzelf blokkeren. Zie de
     * levenscyclus in ontwerp §8: dit blokkeerpunt staat op de afgeleide revisie, niet op het sjabloon.
     */
    public Optional<Problem> definitionBookmarkProblem(ImportDefinitionRevision revision) {
        if (revision.getImportDefinition().getUsageType() == DefinitionUsageType.REUSABLE_TEMPLATE) {
            return Optional.empty();
        }
        List<String> missing = bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(revision.getId()).stream()
                .filter(bookmark -> bookmark.getValueScope() == BookmarkValueScope.DEFINITION)
                .filter(ImportDefinitionBookmark::isRequired)
                .map(ImportDefinitionBookmark::getName)
                .filter(name -> !hasDefinitionBookmarkValue(revision.getId(), name))
                .sorted()
                .toList();
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(conflict(CODE_REQUIRED_BOOKMARK_MISSING, "Revision " + revision.getId()
                + " declares required DEFINITION bookmark(s) " + missing + " without a value"));
    }

    private boolean hasDefinitionBookmarkValue(long revisionId, String bookmarkName) {
        return bookmarkValues.findByDefinitionRevisionIdAndBookmarkName(revisionId, bookmarkName)
                .map(ImportDefinitionBookmarkValue::getValueText)
                .filter(value -> !value.isBlank())
                .isPresent();
    }

    private static Problem conflict(String code, String message) {
        return new Problem(code, message, new ConflictException(code, message));
    }
}
