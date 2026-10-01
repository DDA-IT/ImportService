package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.service.ChainConfigurationChecks.ActivationReport;
import be.dda.catalogimport.service.ChainConfigurationChecks.IntakeConfiguration;
import be.dda.catalogimport.service.ChainConfigurationChecks.Problem;
import be.dda.catalogimport.service.ChainConfigurationChecks.ReportedProblem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gereedheidscontrole van één importkoppeling en haar keten, zonder bestand (NT-8; beslissingslog 2026-09-30
 * "Nieuwe leverancier + taak (NT-spoor)", V1 = C). Antwoord van {@code GET /import-links/{id}/readiness}.
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Alles in één keer.</b> Elke bevinding wordt gemeld, niet enkel de eerste, zodat de gebruiker één checklist
 *       ziet in plaats van fout na fout te ontdekken.</li>
 *   <li><b>Zelfde code als de upload/activatie.</b> Een {@link CheckStatus#PROBLEM} draagt exact de code die de upload
 *       (409) of het activeren van het concept (400/409) zou geven — de controles komen uit dezelfde implementatie
 *       ({@link ChainConfigurationChecks}). {@code ready} is {@code true} precies wanneer er geen enkele
 *       {@code PROBLEM} is.</li>
 *   <li><b>Zonder actieve revisie</b> wordt elk concept (DRAFT) getoetst met de activatievalidatie, zonder iets te
 *       schrijven: {@link #READY_DRAFT_ACTIVATABLE} als het activeren zou slagen, anders de {@code CONFIG_*}-codes.</li>
 *   <li><b>Informatief</b> ({@link CheckStatus#INFO}, telt niet mee voor {@code ready}): de doelbibliotheek wordt niet
 *       tegen Prodis gecontroleerd (aanname A5), een niet-actieve koppeling (de upload controleert die vlag niet), en
 *       "geen concept om te activeren".</li>
 * </ul>
 *
 * <h2>Technisch</h2>
 * Leestransactie; er wordt niets geschreven, ook niet bij het valideren van een concept (de screeningfabrieken lezen
 * enkel). Geen N+1 over taken: één query voor de taken van de koppeling.
 */
@Service
@Transactional(readOnly = true)
public class ImportLinkReadinessService {

    // --- Nieuwe stabiele codes voor OK- en INFO-regels (een PROBLEM draagt de code van de upload/activatie) --------

    /** OK: de koppeling staat op actief. */
    public static final String READY_LINK_ACTIVE = "READY_LINK_ACTIVE";
    /** OK: de definitie heeft een actieve revisie. */
    public static final String READY_ACTIVE_REVISION = "READY_ACTIVE_REVISION";
    /** OK: de actieve revisie heeft een basisprijsveld (tegenhanger van {@code CONFIG_PRICE_FIELD_MISSING}). */
    public static final String READY_PRICE_FIELD = "READY_PRICE_FIELD";
    /** OK: alle verplichte LINK-bookmarks van de getoetste revisie zijn ingevuld. */
    public static final String READY_LINK_BOOKMARKS = "READY_LINK_BOOKMARKS";
    /** OK: het concept zou de activatievalidatie doorstaan (enkel zonder actieve revisie). */
    public static final String READY_DRAFT_ACTIVATABLE = "READY_DRAFT_ACTIVATABLE";
    /** OK: deze taak aanvaardt een upload (MANUAL, zonder Leveringsconfiguratie). */
    public static final String READY_TASK_ACCEPTS_UPLOAD = "READY_TASK_ACCEPTS_UPLOAD";
    /** PROBLEM: de koppeling heeft geen enkele taak; er is dus niets om een levering op te ontvangen. */
    public static final String LINK_HAS_NO_TASK = "LINK_HAS_NO_TASK";
    /** INFO: de koppeling staat op niet-actief (de upload controleert dit niet; enkel ter informatie). */
    public static final String INFO_LINK_INACTIVE = "INFO_LINK_INACTIVE";
    /** INFO: geen actieve revisie en ook geen concept om te activeren. */
    public static final String INFO_NO_DRAFT_REVISION = "INFO_NO_DRAFT_REVISION";
    /** INFO: de doelbibliotheek wordt niet tegen Prodis gecontroleerd (aanname A5). */
    public static final String INFO_LIBRARY_NOT_VERIFIED = "INFO_LIBRARY_NOT_VERIFIED";
    /**
     * INFO: bij een concept werden configuratiecontroles niet beoordeeld omdat de controle waarop ze steunen faalde;
     * na herstel kunnen er nog fouten bijkomen (NT-14a). Telt niet mee voor {@code ready}.
     */
    public static final String INFO_CONFIG_CHECKS_SKIPPED = "INFO_CONFIG_CHECKS_SKIPPED";
    /** Terugval voor een configuratiefout zonder code (komt in de screeningfabrieken niet voor). */
    public static final String CONFIG_INVALID = "CONFIG_INVALID";

    public enum CheckStatus { OK, PROBLEM, INFO }

    public enum SubjectType { LINK, DEFINITION, REVISION, TASK }

    /** Waar een controle over gaat. */
    public record CheckSubject(SubjectType type, long id) {
    }

    /**
     * Eén regel van de checklist.
     *
     * @param detail technische toelichting (Engels, zoals de foutmelding van de upload/activatie); {@code null} bij
     *               een OK-regel
     * @param fieldName     additief (NT-14-2): het {@code fieldName} van de configuratiefout; {@code null} elders
     * @param revisionField additief (NT-14-2): sleutel uit het woordenlijstdomein {@code revisionField}; {@code null}
     *                      elders
     * @param skippedBecause additief (NT-14-5): de codes van overgeslagen afhankelijke controles; leeg lijstje voor
     *                       alle rijen behalve {@code INFO_CONFIG_CHECKS_SKIPPED}
     */
    public record ReadinessCheck(String code, CheckStatus status, CheckSubject subject, String detail,
                                 String fieldName, String revisionField, List<String> skippedBecause) {

        public ReadinessCheck(String code, CheckStatus status, CheckSubject subject, String detail) {
            this(code, status, subject, detail, null, null, List.of());
        }

        public ReadinessCheck(String code, CheckStatus status, CheckSubject subject, String detail,
                             String fieldName, String revisionField) {
            this(code, status, subject, detail, fieldName, revisionField, List.of());
        }
    }

    /** Het antwoord: {@code ready} = geen enkele {@link CheckStatus#PROBLEM}. */
    public record LinkReadiness(long linkId, boolean ready, List<ReadinessCheck> checks) {
    }

    private final ImportLinkRepository links;
    private final ImportDefinitionRevisionRepository revisions;
    private final CatalogImportTaskRepository tasks;
    private final ChainConfigurationChecks configurationChecks;

    public ImportLinkReadinessService(ImportLinkRepository links, ImportDefinitionRevisionRepository revisions,
                                      CatalogImportTaskRepository tasks,
                                      ChainConfigurationChecks configurationChecks) {
        this.links = links;
        this.revisions = revisions;
        this.tasks = tasks;
        this.configurationChecks = configurationChecks;
    }

    /**
     * @throws NotFoundException {@code LINK_NOT_FOUND}
     */
    public LinkReadiness readiness(long linkId) {
        ImportLink link = links.findById(linkId)
                .orElseThrow(() -> new NotFoundException("LINK_NOT_FOUND",
                        "Import link " + linkId + " does not exist"));
        CheckSubject linkSubject = new CheckSubject(SubjectType.LINK, link.getId());
        List<ReadinessCheck> checks = new ArrayList<>();

        checks.add(link.isActive() ? ok(READY_LINK_ACTIVE, linkSubject)
                : info(INFO_LINK_INACTIVE, linkSubject, "Import link " + link.getId() + " is not active; "
                + "an upload does not check this flag"));
        revisionChecks(link, linkSubject, checks);
        taskChecks(link, linkSubject, checks);
        checks.add(info(INFO_LIBRARY_NOT_VERIFIED, linkSubject, "Library code '" + link.getLibraryCode()
                + "' is not checked against Prodis"));

        boolean ready = checks.stream().noneMatch(check -> check.status() == CheckStatus.PROBLEM);
        return new LinkReadiness(link.getId(), ready, List.copyOf(checks));
    }

    private void revisionChecks(ImportLink link, CheckSubject linkSubject, List<ReadinessCheck> checks) {
        long definitionId = link.getImportDefinition().getId();
        IntakeConfiguration intake = configurationChecks.intakeConfiguration(link, "Import link " + link.getId());
        ImportDefinitionRevision active = intake.activeRevision();
        if (active != null) {
            CheckSubject revisionSubject = new CheckSubject(SubjectType.REVISION, active.getId());
            checks.add(ok(READY_ACTIVE_REVISION, revisionSubject));
            boolean priceMissing = false;
            boolean bookmarksMissing = false;
            for (Problem problem : intake.problems()) {
                if (ChainConfigurationChecks.CODE_PRICE_FIELD_MISSING.equals(problem.code())) {
                    priceMissing = true;
                    checks.add(problem(problem, revisionSubject));
                } else {
                    // CONFIG_REQUIRED_BOOKMARK_MISSING: gaat over de waarden van de koppeling.
                    bookmarksMissing = true;
                    checks.add(problem(problem, linkSubject));
                }
            }
            if (!priceMissing) {
                checks.add(ok(READY_PRICE_FIELD, revisionSubject));
            }
            if (!bookmarksMissing) {
                checks.add(ok(READY_LINK_BOOKMARKS, linkSubject));
            }
            return;
        }

        // Geen actieve revisie: NO_ACTIVE_REVISION, zoals de upload.
        CheckSubject definitionSubject = new CheckSubject(SubjectType.DEFINITION, definitionId);
        for (Problem problem : intake.problems()) {
            checks.add(problem(problem, definitionSubject));
        }
        List<ImportDefinitionRevision> drafts = revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(definitionId)
                .stream()
                .filter(revision -> revision.getStatus() == RevisionStatus.DRAFT)
                .sorted(Comparator.comparingInt(ImportDefinitionRevision::getRevisionNumber))
                .toList();
        if (drafts.isEmpty()) {
            checks.add(info(INFO_NO_DRAFT_REVISION, definitionSubject, "Import definition " + definitionId
                    + " has no active and no draft revision"));
            return;
        }
        for (ImportDefinitionRevision draft : drafts) {
            CheckSubject draftSubject = new CheckSubject(SubjectType.REVISION, draft.getId());
            // NT-14a par. 3: alle onafhankelijke configuratiefouten (stand ALL), met de vaste valuta van de koppeling
            // zoals de screening. De eerste rij is gelijk aan de eerste worp van de activatie.
            ActivationReport activation = configurationChecks.activationProblemsAll(draft, link.getDefaultCurrency());
            if (activation.problems().isEmpty()) {
                checks.add(ok(READY_DRAFT_ACTIVATABLE, draftSubject));
            }
            for (ReportedProblem reported : activation.problems()) {
                checks.add(problem(reported.problem(), draftSubject, reported.fieldName(), reported.revisionField()));
            }
            if (!activation.skippedBecause().isEmpty()) {
                checks.add(info(INFO_CONFIG_CHECKS_SKIPPED, draftSubject, "Checks depending on "
                        + activation.skippedBecause() + " were not evaluated", activation.skippedBecause()));
            }
            // Wat de upload ná het activeren van dit concept zou melden: de LINK-bookmarks die het concept declareert.
            Optional<Problem> linkBookmarks =
                    configurationChecks.requiredLinkBookmarkProblem(link, draft, "draft revision");
            if (linkBookmarks.isPresent()) {
                checks.add(problem(linkBookmarks.get(), linkSubject));
            } else {
                checks.add(ok(READY_LINK_BOOKMARKS, linkSubject));
            }
        }
    }

    private void taskChecks(ImportLink link, CheckSubject linkSubject, List<ReadinessCheck> checks) {
        List<CatalogImportTask> linkTasks = tasks.findByImportLinkIdOrderByIdAsc(link.getId());
        if (linkTasks.isEmpty()) {
            checks.add(new ReadinessCheck(LINK_HAS_NO_TASK, CheckStatus.PROBLEM, linkSubject,
                    "Import link " + link.getId() + " has no task to receive a delivery on"));
            return;
        }
        for (CatalogImportTask task : linkTasks) {
            CheckSubject taskSubject = new CheckSubject(SubjectType.TASK, task.getId());
            List<Problem> problems = configurationChecks.manualIntakeProblems(task);
            if (problems.isEmpty()) {
                checks.add(ok(READY_TASK_ACCEPTS_UPLOAD, taskSubject));
            }
            for (Problem problem : problems) {
                checks.add(problem(problem, taskSubject));
            }
        }
    }

    private static ReadinessCheck ok(String code, CheckSubject subject) {
        return new ReadinessCheck(code, CheckStatus.OK, subject, null);
    }

    private static ReadinessCheck info(String code, CheckSubject subject, String detail) {
        return new ReadinessCheck(code, CheckStatus.INFO, subject, detail);
    }

    private static ReadinessCheck info(String code, CheckSubject subject, String detail, List<String> skippedBecause) {
        return new ReadinessCheck(code, CheckStatus.INFO, subject, detail, null, null, skippedBecause);
    }

    private static ReadinessCheck problem(Problem problem, CheckSubject subject) {
        return problem(problem, subject, null, null);
    }

    private static ReadinessCheck problem(Problem problem, CheckSubject subject, String fieldName,
                                          String revisionField) {
        String code = problem.code() == null || problem.code().isBlank() ? CONFIG_INVALID : problem.code();
        return new ReadinessCheck(code, CheckStatus.PROBLEM, subject, problem.message(), fieldName, revisionField);
    }
}
