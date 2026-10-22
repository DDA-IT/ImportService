package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ProdisPublicationSourceDao;
import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourceRow;
import be.dda.catalogimport.dao.ProdisReferenceDao;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleIdentity;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleLookup;
import be.dda.catalogimport.service.ProdisPublicationPlanner.Plan;
import be.dda.catalogimport.service.ProdisPublicationProjection.Item;
import be.dda.catalogimport.service.ProdisReferenceService.Availability;
import be.dda.catalogimport.service.ProdisReferenceService.CatalogArticleLookups;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterMode;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterReading;
import be.dda.catalogimport.service.PublicationArtifactStore.StoredArtifact;
import be.dda.catalogimport.service.PublicationParallelRunReport.Counts;
import be.dda.catalogimport.service.PublicationParallelRunReport.Header;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * De publicatie-parallelrun op een {@code SIMULATION}-run (design {@code publicatie-prodis-db-direct-design.md} par. 5
 * en 6, slice P2c; {@code publicatie-prodis-db-design.md} P2; beslissingen 2026-10-22). Een dry-run van de latere
 * publicatie naar de Prodis-database: per snapshotrij wat er zou gebeuren, zonder iets te schrijven.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>Leesbron niet geconfigureerd → {@value #STATUS_NOT_CONFIGURED} (er wordt niets gelezen).</li>
 *   <li>De modus van Prodis ({@code system_settings.legacy_master}) <b>één keer bij de start</b> lezen. Onleesbaar of
 *       onbereikbaar → {@code UNKNOWN} + waarschuwing, geen weigering (design par. 5); bij PRODIS/PROFAB de vaste
 *       waarschuwing dat de datasync de wijzigingen terugzet (P-V1).</li>
 *   <li>Bundel zonder bundelsnapshot → {@value #STATUS_NO_SNAPSHOT}: er is niets om te vergelijken (nooit "0 items").</li>
 *   <li>Per chunk van {@value #CHUNK_SIZE} snapshotrijen ({@link ProdisPublicationSourceDao}, keyset op
 *       {@code mutation_id}): projectie → één batch-lookup op de <b>alleen-lezen</b> leesbron voor alle volledige
 *       identiteiten → planner → item in het rapport. De planner beslist {@code WOULD_UPDATE|UNCHANGED|CONFLICT|
 *       REJECTED|NOT_SENT}.</li>
 *   <li>Een lookup die niet {@code VERIFIED} is, breekt de hele parallelrun af → {@value #STATUS_UNAVAILABLE}: het
 *       half geschreven rapport wordt weggegooid en vervangen door een statusrapport zonder items. Nooit stil "alles
 *       ARTICLE_NOT_FOUND" (de planner wordt zonder lookup nooit aangeroepen).</li>
 *   <li>Een andere technische fout → {@value #STATUS_FAILED}, idem zonder items.</li>
 * </ol>
 *
 * <h2>Wat er bewaard wordt (geen schemawijziging)</h2>
 * Twee extra artefacten in de map van het PSIMPORT-artefact van de run ({@link PublicationArtifactStore#writeSibling}):
 * het volledige rapport {@value #REPORT_FILE_NAME} (download) en een klein samenvattingsbestand
 * {@value #SUMMARY_FILE_NAME} met status, modus, tellers en de SHA-256/grootte van het rapport. De runview leest enkel de
 * samenvatting (afgeleide data, geen kolom). Rapport eerst, samenvatting daarna: kan de samenvatting niet geschreven
 * worden, dan wordt het rapport opgeruimd en bestaat er voor de run geen parallelrunresultaat.
 *
 * <h2>Grenzen</h2>
 * <ul>
 *   <li>Er wordt <b>niets</b> naar Prodis geschreven: enkel {@link ProdisReferenceService} (rol {@code catalogimport_ro},
 *       pool {@code readOnly}). In CatalogImport wordt enkel gelezen (snapshot) en op het bestandssysteem geschreven.</li>
 *   <li>Deze service gooit nooit bij het uitvoeren: de {@code SIMULATION}-run slaagt zoals voorheen, wat er met de
 *       parallelrun ook gebeurt.</li>
 *   <li>Deterministisch: dezelfde bundel tegen dezelfde Prodis-toestand geeft hetzelfde rapport, op {@code runId} en
 *       tijdstempels na.</li>
 * </ul>
 */
@Service
public class PublicationParallelRunService {

    /** Voltooid: elk snapshotitem is gepland en geteld. */
    public static final String STATUS_COMPLETED = "PARALLEL_RUN_COMPLETED";
    /** {@code catalogimport.prodis-db.*} niet gezet: overgeslagen. */
    public static final String STATUS_NOT_CONFIGURED = "PARALLEL_RUN_NOT_CONFIGURED";
    /** De leesbron gaf een fout (onbereikbaar, rechten, tabel): overgeslagen, geen enkel item gerapporteerd. */
    public static final String STATUS_UNAVAILABLE = "PARALLEL_RUN_UNAVAILABLE";
    /** De bundel draagt geen bundelsnapshot (bevroren vóór 5P-2, of zonder publiceerbare mutaties): niets te vergelijken. */
    public static final String STATUS_NO_SNAPSHOT = "PARALLEL_RUN_NO_SNAPSHOT";
    /** Een technische fout (geen leesbronfout): overgeslagen, details in het serverlogboek. */
    public static final String STATUS_FAILED = "PARALLEL_RUN_FAILED";
    /** Het bewaarde samenvattingsbestand kon niet gelezen worden (enkel bij het tonen). */
    public static final String STATUS_SUMMARY_UNREADABLE = "PARALLEL_RUN_SUMMARY_UNREADABLE";

    /** Prodis staat in modus PRODIS of PROFAB: de volgende datasync zet de wijzigingen terug (P-V1, bewust aanvaard). */
    public static final String WARNING_LEGACY_MASTER_DATASYNC_REVERTS = "LEGACY_MASTER_DATASYNC_REVERTS";
    /** De modus van Prodis kon niet gelezen worden: onbekend, niets geweigerd (design par. 5). */
    public static final String WARNING_LEGACY_MASTER_UNKNOWN = "LEGACY_MASTER_UNKNOWN";

    /** De vaste waarschuwing uit design par. 5 bij modus PRODIS (en PROFAB, aanname design par. 7). */
    static final String MESSAGE_DATASYNC_REVERTS = "De volgende datasync zet deze wijzigingen terug naar de "
            + "legacy-waarden (bewust aanvaard, beslissing 2026-10-22)";
    static final String MESSAGE_LEGACY_MASTER_UNKNOWN = "De modus van Prodis (legacy_master) kon niet gelezen worden: "
            + "onbekend. Er wordt niets geweigerd (design par. 5)";

    /** Het volledige rapport (download). */
    public static final String REPORT_FILE_NAME = "prodis-parallel-run.json";
    /** De samenvatting (runview). */
    public static final String SUMMARY_FILE_NAME = "prodis-parallel-run-summary.json";

    /** Snapshotrijen per chunk = identiteiten per batch van de leesbron (design: batches van 200). */
    static final int CHUNK_SIZE = ProdisReferenceDao.CATALOG_BATCH_SIZE;

    private static final Logger LOG = LoggerFactory.getLogger(PublicationParallelRunService.class);

    /**
     * De samenvatting van één parallelrun, zoals de runview ze toont (afgeleid uit {@value #SUMMARY_FILE_NAME}). Bevat
     * bewust geen bestandspad.
     *
     * @param legacyMaster             de modus bij de start ({@code NONE|PRODIS|PROFAB|UNKNOWN}); {@code null} als de
     *                                 leesbron niet geconfigureerd is
     * @param legacyMasterAvailability {@code VERIFIED|UNAVAILABLE|NOT_CONFIGURED}
     * @param itemCount                {@code null} tenzij {@value #STATUS_COMPLETED}: "niet geteld" is nooit 0
     * @param outcomeCounts            per uitkomst (alle vijf, ook 0); {@code null} tenzij voltooid
     * @param reasonCounts             per blokkerende redencode; {@code null} tenzij voltooid
     * @param noticeCounts             per meldingscode (waarschuwingen én blokkerend); {@code null} tenzij voltooid
     * @param reportSha256             SHA-256 van {@value #REPORT_FILE_NAME}; {@code null} als er niets bewaard is
     */
    public record ParallelRunSummary(int parallelRunSpecVersion, String status, String statusMessage,
                                     boolean writesToProdis, String legacyMaster, String legacyMasterAvailability,
                                     String legacyMasterWarningCode, String legacyMasterWarning, Long itemCount,
                                     Map<String, Long> outcomeCounts, Map<String, Long> reasonCounts,
                                     Map<String, Long> noticeCounts, Instant startedAt, Instant finishedAt,
                                     String reportSha256, Long reportByteSize) {
        public ParallelRunSummary {
            outcomeCounts = outcomeCounts == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(outcomeCounts));
            reasonCounts = reasonCounts == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(reasonCounts));
            noticeCounts = noticeCounts == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(noticeCounts));
        }

        ParallelRunSummary withReport(StoredArtifact report) {
            return new ParallelRunSummary(parallelRunSpecVersion, status, statusMessage, writesToProdis, legacyMaster,
                    legacyMasterAvailability, legacyMasterWarningCode, legacyMasterWarning, itemCount, outcomeCounts,
                    reasonCounts, noticeCounts, startedAt, finishedAt, report.sha256Hex(), report.byteSize());
        }

        ParallelRunSummary failed(String message) {
            return new ParallelRunSummary(parallelRunSpecVersion, STATUS_FAILED, message, writesToProdis, legacyMaster,
                    legacyMasterAvailability, legacyMasterWarningCode, legacyMasterWarning, null, null, null, null,
                    startedAt, finishedAt, null, null);
        }
    }

    /**
     * Wat de parallelrun over de run weet.
     *
     * @param artifactReference de referentie van het PSIMPORT-artefact van de run: de extra artefacten komen in dezelfde map
     */
    public record RunContext(long runId, long bundleId, String bundleContentHash, String snapshotHash,
                             String snapshotSpecVersion, String artifactReference) {
    }

    /** De leesbron gaf geen {@code VERIFIED}: de hele parallelrun wordt afgebroken. */
    private static final class SourceNotVerified extends RuntimeException {
        private final Availability availability;

        private SourceNotVerified(Availability availability) {
            super("Prodis read source " + availability, null, false, false);
            this.availability = availability;
        }
    }

    private final ProdisPublicationSourceDao sourceDao;
    private final ProdisReferenceService reference;
    private final PublicationArtifactStore artifacts;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public PublicationParallelRunService(ProdisPublicationSourceDao sourceDao, ProdisReferenceService reference,
                                         PublicationArtifactStore artifacts, Clock clock) {
        this.sourceDao = sourceDao;
        this.reference = reference;
        this.artifacts = artifacts;
        this.clock = clock;
    }

    // --- uitvoeren -----------------------------------------------------------------------------------------------

    /**
     * Voert de parallelrun uit en bewaart rapport + samenvatting naast het PSIMPORT-artefact. Gooit nooit.
     *
     * @return de samenvatting; met status {@value #STATUS_FAILED} en zonder rapport-hash als er niets bewaard kon worden
     */
    public ParallelRunSummary run(RunContext context) {
        Instant startedAt = clock.instant();
        ParallelRunSummary summary;
        StoredArtifact report;
        try {
            Result result = attempt(context, startedAt);
            summary = result.summary();
            report = result.report();
        } catch (RuntimeException failure) {
            LOG.warn("Parallel run for publication run {} (bundle {}) failed", context.runId(), context.bundleId(),
                    failure);
            ParallelRunSummary failed = statusSummary(STATUS_FAILED, "Technical failure ("
                    + failure.getClass().getSimpleName() + "); the parallel run was skipped, the SIMULATION itself is "
                    + "unaffected. See the server log", null, startedAt);
            try {
                summary = failed;
                report = writeStatusReport(context, failed);
            } catch (RuntimeException alsoFailed) {
                LOG.error("Parallel run for publication run {}: the failure report could not be stored either",
                        context.runId(), alsoFailed);
                return failed;
            }
        }
        ParallelRunSummary stored = summary.withReport(report);
        try {
            artifacts.writeSibling(context.artifactReference(), SUMMARY_FILE_NAME,
                    out -> PublicationParallelRunReport.writeSummaryDocument(out, stored));
        } catch (RuntimeException failure) {
            LOG.error("Parallel run for publication run {}: the summary could not be stored; the report is removed",
                    context.runId(), failure);
            artifacts.deleteQuietly(report.reference());
            return summary.failed("The parallel run result could not be stored; nothing was kept for this run");
        }
        LOG.info("Parallel run for publication run {} (bundle {}): {} (legacy_master {}), {} item(s), outcomes {}. "
                        + "Nothing was written to Prodis.", context.runId(), context.bundleId(), stored.status(),
                stored.legacyMaster(), stored.itemCount(), stored.outcomeCounts());
        return stored;
    }

    private record Result(ParallelRunSummary summary, StoredArtifact report) {
    }

    private Result attempt(RunContext context, Instant startedAt) {
        if (!reference.isConfigured()) {
            return statusOnly(context, statusSummary(STATUS_NOT_CONFIGURED, "The Prodis read source "
                    + "(catalogimport.prodis-db.*) is not configured; the parallel run was skipped. The SIMULATION "
                    + "itself is unaffected", new LegacyMasterReading(Availability.NOT_CONFIGURED,
                    LegacyMasterMode.UNKNOWN), startedAt));
        }
        LegacyMasterReading legacy = reference.legacyMaster();
        if (legacy.availability() == Availability.NOT_CONFIGURED) {
            return statusOnly(context, statusSummary(STATUS_NOT_CONFIGURED, "The Prodis read source is not "
                    + "configured; the parallel run was skipped", legacy, startedAt));
        }
        if (context.snapshotHash() == null) {
            return statusOnly(context, statusSummary(STATUS_NO_SNAPSHOT, "The bundle carries no bundle snapshot "
                    + "(frozen before snapshots existed, or nothing to publish); nothing was compared with Prodis",
                    legacy, startedAt));
        }
        ParallelRunSummary[] completed = new ParallelRunSummary[1];
        try {
            StoredArtifact report = artifacts.writeSibling(context.artifactReference(), REPORT_FILE_NAME,
                    out -> completed[0] = writeFullReport(context, legacy, startedAt, out));
            return new Result(completed[0], report);
        } catch (SourceNotVerified notVerified) {
            // Het half geschreven rapport is door de store al opgeruimd; nooit een gedeeltelijk resultaat.
            LOG.warn("Parallel run for publication run {}: the Prodis read source is {}; skipped", context.runId(),
                    notVerified.availability);
            return notVerified.availability == Availability.NOT_CONFIGURED
                    ? statusOnly(context, statusSummary(STATUS_NOT_CONFIGURED, "The Prodis read source is not "
                            + "configured; the parallel run was skipped", legacy, startedAt))
                    : statusOnly(context, statusSummary(STATUS_UNAVAILABLE, "The Prodis read source could not be "
                            + "read (unreachable, missing rights or tables); the parallel run was skipped and no item "
                            + "was compared. The SIMULATION itself is unaffected", legacy, startedAt));
        }
    }

    /** Het volledige rapport: kop, alle items per chunk, samenvatting. */
    private ParallelRunSummary writeFullReport(RunContext context, LegacyMasterReading legacy, Instant startedAt,
                                               Writer out) throws IOException {
        PublicationParallelRunReport report = new PublicationParallelRunReport(out);
        report.begin(header(context, startedAt));
        long after = 0L;
        while (true) {
            List<SourceRow> page = sourceDao.findPage(context.bundleId(), after, CHUNK_SIZE);
            if (page.isEmpty()) {
                break;
            }
            List<Item> items = new ArrayList<>(page.size());
            for (SourceRow row : page) {
                items.add(ProdisPublicationProjection.project(row));
            }
            Map<CatalogArticleIdentity, CatalogArticleLookup> lookups = lookup(items);
            for (Item item : items) {
                CatalogArticleLookup lookup = item.identity().complete()
                        ? lookups.get(item.identity().toLookupIdentity()) : null;
                Plan plan = ProdisPublicationPlanner.plan(item, lookup, legacy.mode());
                report.item(item, plan);
            }
            after = page.get(page.size() - 1).mutationId();
            if (page.size() < CHUNK_SIZE) {
                break;
            }
        }
        Counts counts = report.counts();
        ParallelRunSummary summary = summary(STATUS_COMPLETED, null, legacy, counts, startedAt, clock.instant());
        report.end(summary);
        return summary;
    }

    /** Eén batch op de leesbron voor alle volledige identiteiten van de chunk; niet {@code VERIFIED} → afbreken. */
    private Map<CatalogArticleIdentity, CatalogArticleLookup> lookup(List<Item> items) {
        Set<CatalogArticleIdentity> identities = new LinkedHashSet<>();
        for (Item item : items) {
            if (item.identity().complete()) {
                identities.add(item.identity().toLookupIdentity());
            }
        }
        if (identities.isEmpty()) {
            return Map.of();
        }
        CatalogArticleLookups lookups = reference.lookupCatalogArticles(new ArrayList<>(identities));
        if (lookups.availability() != Availability.VERIFIED) {
            throw new SourceNotVerified(lookups.availability());
        }
        Map<CatalogArticleIdentity, CatalogArticleLookup> byIdentity = new HashMap<>();
        for (CatalogArticleLookup lookup : lookups.lookups()) {
            byIdentity.put(lookup.identity(), lookup);
        }
        return byIdentity;
    }

    private Result statusOnly(RunContext context, ParallelRunSummary summary) {
        return new Result(summary, writeStatusReport(context, summary));
    }

    /** Een rapport zonder items (overgeslagen of mislukt): de download toont dan dezelfde status. */
    private StoredArtifact writeStatusReport(RunContext context, ParallelRunSummary summary) {
        return artifacts.writeSibling(context.artifactReference(), REPORT_FILE_NAME, out -> {
            PublicationParallelRunReport report = new PublicationParallelRunReport(out);
            report.begin(header(context, summary.startedAt()));
            report.end(summary);
        });
    }

    private ParallelRunSummary statusSummary(String status, String message, LegacyMasterReading legacy,
                                             Instant startedAt) {
        return summary(status, message, legacy, null, startedAt, clock.instant());
    }

    private static ParallelRunSummary summary(String status, String message, LegacyMasterReading legacy, Counts counts,
                                              Instant startedAt, Instant finishedAt) {
        String mode = null;
        String availability = null;
        String warningCode = null;
        String warning = null;
        if (legacy != null) {
            availability = legacy.availability().name();
            if (legacy.availability() != Availability.NOT_CONFIGURED) {
                mode = legacy.mode().name();
                if (legacy.mode() == LegacyMasterMode.PRODIS || legacy.mode() == LegacyMasterMode.PROFAB) {
                    warningCode = WARNING_LEGACY_MASTER_DATASYNC_REVERTS;
                    warning = MESSAGE_DATASYNC_REVERTS;
                } else if (legacy.mode() == LegacyMasterMode.UNKNOWN) {
                    warningCode = WARNING_LEGACY_MASTER_UNKNOWN;
                    warning = MESSAGE_LEGACY_MASTER_UNKNOWN;
                }
            }
        }
        return new ParallelRunSummary(PublicationParallelRunReport.SPEC_VERSION, status, message, false, mode,
                availability, warningCode, warning, counts == null ? null : counts.itemCount(),
                counts == null ? null : counts.outcomeCounts(), counts == null ? null : counts.reasonCounts(),
                counts == null ? null : counts.noticeCounts(), startedAt, finishedAt, null, null);
    }

    private static Header header(RunContext context, Instant startedAt) {
        return new Header(context.runId(), context.bundleId(), context.bundleContentHash(), context.snapshotHash(),
                context.snapshotSpecVersion(), startedAt);
    }

    // --- lezen ---------------------------------------------------------------------------------------------------

    /**
     * De bewaarde samenvatting van de parallelrun van een run, of {@code null} als die run er geen heeft (een run van
     * vóór P2c, of een run waarvan het resultaat niet bewaard kon worden). Een onleesbaar bestand geeft
     * {@value #STATUS_SUMMARY_UNREADABLE}, nooit een uitzondering.
     *
     * @param artifactReference de referentie van het PSIMPORT-artefact van de run
     */
    public ParallelRunSummary readSummary(String artifactReference) {
        String summaryReference;
        try {
            summaryReference = artifacts.siblingReference(artifactReference, SUMMARY_FILE_NAME);
            if (!artifacts.exists(summaryReference)) {
                return null;
            }
        } catch (RuntimeException invalid) {
            return null;
        }
        try (InputStream in = artifacts.open(summaryReference)) {
            return parseSummary(json.readTree(in).path("summary"));
        } catch (IOException | RuntimeException unreadable) {
            LOG.warn("The parallel run summary {} could not be read: {}", SUMMARY_FILE_NAME,
                    unreadable.getClass().getSimpleName());
            return new ParallelRunSummary(PublicationParallelRunReport.SPEC_VERSION, STATUS_SUMMARY_UNREADABLE,
                    "The stored parallel run summary could not be read", false, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
    }

    /** {@code true} als de run een parallelrunrapport heeft. */
    public boolean hasReport(String artifactReference) {
        try {
            return artifacts.exists(artifacts.siblingReference(artifactReference, REPORT_FILE_NAME));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Opent het rapport; de aanroeper sluit de stroom en controleert eerst {@link #hasReport}. */
    public InputStream openReport(String artifactReference) {
        return artifacts.open(artifacts.siblingReference(artifactReference, REPORT_FILE_NAME));
    }

    private static ParallelRunSummary parseSummary(JsonNode node) {
        if (!node.isObject() || !node.path("status").isTextual()) {
            throw new IllegalStateException("not a parallel run summary");
        }
        return new ParallelRunSummary(node.path("parallelRunSpecVersion").asInt(), node.path("status").asText(),
                text(node, "statusMessage"), node.path("writesToProdis").asBoolean(), text(node, "legacyMaster"),
                text(node, "legacyMasterAvailability"), text(node, "legacyMasterWarningCode"),
                text(node, "legacyMasterWarning"), number(node, "itemCount"), counts(node.path("outcomeCounts")),
                counts(node.path("reasonCounts")), counts(node.path("noticeCounts")), instant(node, "startedAt"),
                instant(node, "finishedAt"), text(node, "reportSha256"), number(node, "reportByteSize"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? value.asLong() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }

    private static Map<String, Long> counts(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            counts.put(entry.getKey(), entry.getValue().asLong());
        }
        return counts;
    }
}
