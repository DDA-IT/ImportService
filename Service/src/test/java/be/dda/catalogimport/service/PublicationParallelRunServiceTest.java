package be.dda.catalogimport.service;

import static be.dda.catalogimport.service.ProdisPublicationProjectionTest.row;
import static be.dda.catalogimport.service.ProdisPublicationProjectionTest.vkp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.ProdisPublicationSourceDao;
import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourceRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleDescriptionRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleIdentity;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleLookup;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticlePriceDetailRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticlePriceRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleRow;
import be.dda.catalogimport.service.ProdisReferenceService.Availability;
import be.dda.catalogimport.service.ProdisReferenceService.CatalogArticleLookups;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterMode;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterReading;
import be.dda.catalogimport.service.PublicationArtifactStore.StoredArtifact;
import be.dda.catalogimport.service.PublicationParallelRunService.ParallelRunSummary;
import be.dda.catalogimport.service.PublicationParallelRunService.RunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * P2c (design {@code publicatie-prodis-db-direct-design.md} par. 5 en 6; {@code publicatie-prodis-db-design.md} P2):
 * {@link PublicationParallelRunService} zonder database — lezer en leesbron zijn mocks, de artefactstore is echt (op een
 * tijdelijke map). De volledige keten tegen PostgreSQL + de H2-Prodis-bron staat in {@code PublicationParallelRunTest}
 * (Web).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Elke uitkomst en de tellers per uitkomst/reden/melding, incl. {@code PRICE_ROUNDING_DIFFERENCE} (enkel mogelijk
 *       met snapshot spec 2, die nog niet gebouwd wordt: hier met een gesimuleerde v2-rij) en {@code DERIVED_PRICE_DRIFT}.</li>
 *   <li>Chunks van 200: één lookup per chunk, keyset op {@code mutation_id}.</li>
 *   <li>Niet geconfigureerd, onbereikbaar (ook halverwege: nooit een gedeeltelijk rapport), mislukt, geen snapshot: elk een
 *       expliciete status, tellers {@code null} (nooit 0).</li>
 *   <li>{@code legacy_master} één keer bij de start; PRODIS/PROFAB/onbekend met waarschuwing, niets geweigerd.</li>
 *   <li>Deterministisch: twee runs geven dezelfde items en tellers.</li>
 *   <li>Bewaren: rapport + samenvatting naast het artefact; samenvatting niet te bewaren → rapport opgeruimd.</li>
 * </ul>
 */
class PublicationParallelRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final LocalDateTime LAST_UPDATED = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private ProdisPublicationSourceDao sourceDao;
    private ProdisReferenceService reference;
    private PublicationArtifactStore store;
    private PublicationParallelRunService service;
    private final AtomicLong runIds = new AtomicLong(100);
    private final List<SourceRow> rows = new ArrayList<>();
    private final Map<CatalogArticleIdentity, CatalogArticleLookup> prodis = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        sourceDao = mock(ProdisPublicationSourceDao.class);
        reference = mock(ProdisReferenceService.class);
        store = spy(new PublicationArtifactStore(root.toString(), Clock.fixed(NOW, ZoneOffset.UTC)));
        service = new PublicationParallelRunService(sourceDao, reference, store, Clock.fixed(NOW, ZoneOffset.UTC));
        when(reference.isConfigured()).thenReturn(true);
        when(reference.legacyMaster()).thenReturn(new LegacyMasterReading(Availability.VERIFIED, LegacyMasterMode.NONE));
        when(sourceDao.findPage(anyLong(), anyLong(), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(1);
            int limit = call.getArgument(2);
            return rows.stream().filter(row -> row.mutationId() > after).limit(limit).toList();
        });
        when(reference.lookupCatalogArticles(anyList())).thenAnswer(call -> {
            List<CatalogArticleIdentity> identities = call.getArgument(0);
            List<CatalogArticleLookup> lookups = new ArrayList<>();
            for (CatalogArticleIdentity identity : identities) {
                lookups.add(prodis.getOrDefault(identity, new CatalogArticleLookup(identity, 1, List.of())));
            }
            return new CatalogArticleLookups(Availability.VERIFIED, lookups);
        });
    }

    // --- normaal scenario: elke uitkomst en de tellers -----------------------------------------------------------

    @Test
    void everyOutcomeIsPlannedCountedAndWrittenNextToTheArtifact() throws Exception {
        seedAllOutcomes();

        RunContext context = context();
        ParallelRunSummary summary = service.run(context);

        assertThat(summary.status()).isEqualTo(PublicationParallelRunService.STATUS_COMPLETED);
        assertThat(summary.statusMessage()).isNull();
        assertThat(summary.writesToProdis()).isFalse();
        assertThat(summary.legacyMaster()).isEqualTo("NONE");
        assertThat(summary.legacyMasterAvailability()).isEqualTo("VERIFIED");
        assertThat(summary.legacyMasterWarningCode()).isNull();
        assertThat(summary.itemCount()).isEqualTo(6L);
        assertThat(summary.outcomeCounts()).containsExactly(Map.entry("WOULD_UPDATE", 1L), Map.entry("UNCHANGED", 2L),
                Map.entry("CONFLICT", 1L), Map.entry("REJECTED", 1L), Map.entry("NOT_SENT", 1L));
        assertThat(summary.reasonCounts()).containsExactly(Map.entry("ARTICLE_NOT_FOUND", 1L),
                Map.entry("CREATE_NOT_SUPPORTED_IN_V1", 1L), Map.entry("VALUE_CHANGED_IN_PRODIS", 1L));
        assertThat(summary.noticeCounts()).containsEntry("DERIVED_PRICE_DRIFT", 1L)
                .containsEntry("VALUE_CHANGED_IN_PRODIS", 1L).containsEntry("ARTICLE_NOT_FOUND", 1L)
                .containsEntry("CREATE_NOT_SUPPORTED_IN_V1", 1L);
        assertThat(summary.startedAt()).isEqualTo(NOW);
        assertThat(summary.finishedAt()).isEqualTo(NOW);

        // Rapport en samenvatting staan naast het artefact; de bewaarde hash klopt met het rapportbestand.
        Path report = root.resolve(store.siblingReference(context.artifactReference(),
                PublicationParallelRunService.REPORT_FILE_NAME));
        byte[] bytes = Files.readAllBytes(report);
        assertThat(summary.reportSha256()).isEqualTo(sha256(bytes));
        assertThat(summary.reportByteSize()).isEqualTo(bytes.length);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("\r");
        assertThat(service.readSummary(context.artifactReference())).isEqualTo(summary);

        JsonNode document = JSON.readTree(bytes);
        assertThat(document.path("kind").asText()).isEqualTo("PRODIS_PARALLEL_RUN");
        assertThat(document.path("writesToProdis").asBoolean(true)).isFalse();
        assertThat(document.path("prodisAccess").asText()).isEqualTo("READ_ONLY");
        assertThat(document.path("runId").asLong()).isEqualTo(context.runId());
        JsonNode items = document.path("items");
        assertThat(items).hasSize(6);
        assertThat(items.findValuesAsText("outcome")).containsExactly("WOULD_UPDATE", "UNCHANGED", "UNCHANGED",
                "CONFLICT", "REJECTED", "NOT_SENT");
        JsonNode update = items.get(0);
        assertThat(update.path("identity").path("groupLegacyCode").asText()).isEqualTo("0601|GRP|K1");
        assertThat(update.path("basePrice").path("value").asText()).isEqualTo("120");
        assertThat(update.path("basePrice").path("expected").asText()).isEqualTo("100");
        assertThat(update.path("salesPriceLastUpdated").asBoolean()).isTrue();
        assertThat(update.path("changes").findValuesAsText("column")).contains("base_price", "price");
        assertThat(update.path("prior").path("basePrice").asText()).isEqualTo("100");
        assertThat(update.path("target").path("prices").path("1").asText()).isEqualTo("12");
        JsonNode drift = items.get(2);
        assertThat(drift.path("changes")).isEmpty();
        assertThat(drift.path("derivedDrift")).isNotEmpty();
        assertThat(drift.path("notices").findValuesAsText("code")).contains("DERIVED_PRICE_DRIFT");
        // De samenvatting in het rapport is dezelfde (zonder de rapport-hash, die er niet in kan).
        assertThat(document.path("summary").path("outcomeCounts").path("UNCHANGED").asLong()).isEqualTo(2L);
        assertThat(document.path("summary").path("reportSha256").isNull()).isTrue();
        // Geen Prodis-id's: het rapport kent enkel businesscodes.
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("\"id\"").doesNotContain("catalogArticleId");
    }

    @Test
    void anAppliedPercentageWithADifferentSourceAmountGivesPriceRoundingDifference() throws Exception {
        // Snapshot spec 2 met before_percentage bestaat nog niet (P4b); de lezer levert ze nu nooit. Hier gesimuleerd om
        // te bewijzen dat de parallelrun de melding telt en rapporteert zodra ze kan voorkomen.
        rows.add(row(r -> {
            r.mutationId = 1L;
            r.specVersion = "2";
            r.prices.add(vkp(1, "12.50", "10", "10"));
        }));
        prodis.put(identity("REF-1"), lookup("REF-1", article("100", "Nieuwe omschrijving", b -> {
        })));

        RunContext context = context();
        ParallelRunSummary summary = service.run(context);

        assertThat(summary.outcomeCounts()).containsEntry("WOULD_UPDATE", 1L);
        assertThat(summary.noticeCounts()).containsEntry("PRICE_ROUNDING_DIFFERENCE", 1L);
        JsonNode item = report(context).path("items").get(0);
        assertThat(item.path("notices").findValuesAsText("code")).contains("PRICE_ROUNDING_DIFFERENCE");
        // Altijd de berekende waarde, nooit het bronbedrag: 120 × 10% = 12, niet 12.50.
        assertThat(item.path("target").path("prices").path("1").asText()).isEqualTo("12");
    }

    // --- chunks -----------------------------------------------------------------------------------------------------

    @Test
    void theSnapshotIsReadInChunksOf200WithOneLookupPerChunk() {
        for (long id = 1; id <= 450; id++) {
            long mutationId = id;
            rows.add(row(r -> {
                r.mutationId = mutationId;
                r.reference = "REF-" + mutationId;
            }));
        }

        ParallelRunSummary summary = service.run(context());

        assertThat(summary.itemCount()).isEqualTo(450L);
        assertThat(summary.outcomeCounts()).containsEntry("REJECTED", 450L);
        verify(sourceDao).findPage(anyLong(), eq(0L), eq(200));
        verify(sourceDao).findPage(anyLong(), eq(200L), eq(200));
        verify(sourceDao).findPage(anyLong(), eq(400L), eq(200));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CatalogArticleIdentity>> batches = ArgumentCaptor.forClass(List.class);
        verify(reference, times(3)).lookupCatalogArticles(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(200, 200, 50);
        verify(reference, times(1)).legacyMaster();
    }

    // --- overgeslagen of mislukt -------------------------------------------------------------------------------------

    @Test
    void notConfiguredSkipsWithAnExplicitStatusAndReadsNothing() throws Exception {
        seedAllOutcomes();
        when(reference.isConfigured()).thenReturn(false);

        RunContext context = context();
        ParallelRunSummary summary = service.run(context);

        assertThat(summary.status()).isEqualTo(PublicationParallelRunService.STATUS_NOT_CONFIGURED);
        assertSkipped(summary);
        assertThat(summary.legacyMaster()).isNull();
        assertThat(summary.legacyMasterAvailability()).isEqualTo("NOT_CONFIGURED");
        verify(reference, never()).legacyMaster();
        verify(reference, never()).lookupCatalogArticles(anyList());
        verify(sourceDao, never()).findPage(anyLong(), anyLong(), anyInt());
        assertThat(report(context).path("items")).isEmpty();
        assertThat(report(context).path("summary").path("status").asText())
                .isEqualTo(PublicationParallelRunService.STATUS_NOT_CONFIGURED);
    }

    @Test
    void anUnreachableSourceHalfwayDiscardsEverythingAndNeverReportsNotFound() throws Exception {
        for (long id = 1; id <= 250; id++) {
            long mutationId = id;
            rows.add(row(r -> {
                r.mutationId = mutationId;
                r.reference = "REF-" + mutationId;
            }));
        }
        when(reference.lookupCatalogArticles(anyList()))
                .thenAnswer(call -> new CatalogArticleLookups(Availability.VERIFIED, ((List<CatalogArticleIdentity>)
                        call.getArgument(0)).stream().map(i -> new CatalogArticleLookup(i, 1, List.of())).toList()))
                .thenReturn(new CatalogArticleLookups(Availability.UNAVAILABLE, List.of()));

        RunContext context = context();
        ParallelRunSummary summary = service.run(context);

        assertThat(summary.status()).isEqualTo(PublicationParallelRunService.STATUS_UNAVAILABLE);
        assertThat(summary.statusMessage()).contains("could not be read").contains("SIMULATION itself is unaffected");
        assertSkipped(summary);
        assertThat(summary.legacyMaster()).isEqualTo("NONE");
        JsonNode document = report(context);
        assertThat(document.path("items")).isEmpty();
        assertThat(Files.readString(reportPath(context))).doesNotContain("ARTICLE_NOT_FOUND");
        try (var listing = Files.list(reportPath(context).getParent())) {
            assertThat(listing.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".tmp"))).isEmpty();
        }
    }

    @Test
    void anUnreadableLegacyMasterIsUnknownWithAWarningButTheRunCompletes() {
        seedAllOutcomes();
        when(reference.legacyMaster()).thenReturn(new LegacyMasterReading(Availability.UNAVAILABLE,
                LegacyMasterMode.UNKNOWN));

        ParallelRunSummary summary = service.run(context());

        assertThat(summary.status()).isEqualTo(PublicationParallelRunService.STATUS_COMPLETED);
        assertThat(summary.legacyMaster()).isEqualTo("UNKNOWN");
        assertThat(summary.legacyMasterAvailability()).isEqualTo("UNAVAILABLE");
        assertThat(summary.legacyMasterWarningCode()).isEqualTo(PublicationParallelRunService.WARNING_LEGACY_MASTER_UNKNOWN);
        assertThat(summary.itemCount()).isEqualTo(6L);
    }

    @Test
    void modeProdisOrProfabCarriesTheFixedDatasyncWarningAndRefusesNothing() {
        for (LegacyMasterMode mode : List.of(LegacyMasterMode.PRODIS, LegacyMasterMode.PROFAB)) {
            rows.clear();
            seedAllOutcomes();
            when(reference.legacyMaster()).thenReturn(new LegacyMasterReading(Availability.VERIFIED, mode));

            ParallelRunSummary summary = service.run(context());

            assertThat(summary.legacyMaster()).isEqualTo(mode.name());
            assertThat(summary.legacyMasterWarningCode())
                    .isEqualTo(PublicationParallelRunService.WARNING_LEGACY_MASTER_DATASYNC_REVERTS);
            assertThat(summary.legacyMasterWarning()).contains("datasync").contains("2026-10-22");
            assertThat(summary.outcomeCounts()).containsEntry("WOULD_UPDATE", 1L);
        }
    }

    @Test
    void aBundleWithoutSnapshotIsReportedAsSuchNotAsZeroItems() {
        RunContext context = new RunContext(runIds.incrementAndGet(), 7L, "aa", null, null, mainArtifact());

        ParallelRunSummary summary = service.run(context);

        assertThat(summary.status()).isEqualTo(PublicationParallelRunService.STATUS_NO_SNAPSHOT);
        assertSkipped(summary);
        verify(sourceDao, never()).findPage(anyLong(), anyLong(), anyInt());
    }

    @Test
    void aTechnicalFailureIsReportedAsFailedWithoutDetailsAndNeverThrows() throws Exception {
        when(sourceDao.findPage(anyLong(), anyLong(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("connection to jdbc:postgresql://secret-host refused"));

        RunContext context = context();
        ParallelRunSummary summary = service.run(context);

        assertThat(summary.status()).isEqualTo(PublicationParallelRunService.STATUS_FAILED);
        assertThat(summary.statusMessage()).contains("DataAccessResourceFailureException").doesNotContain("secret-host");
        assertSkipped(summary);
        assertThat(report(context).path("summary").path("status").asText())
                .isEqualTo(PublicationParallelRunService.STATUS_FAILED);
    }

    @Test
    void whenTheSummaryCannotBeStoredTheReportIsRemovedAndTheRunHasNoResult() {
        seedAllOutcomes();
        doThrow(new UncheckedIOException(new IOException("disk full"))).when(store)
                .writeSibling(any(), eq(PublicationParallelRunService.SUMMARY_FILE_NAME), any());
        RunContext context = context();

        ParallelRunSummary summary = service.run(context);

        assertThat(summary.status()).isEqualTo(PublicationParallelRunService.STATUS_FAILED);
        assertThat(summary.reportSha256()).isNull();
        assertThat(Files.exists(reportPath(context))).isFalse();
        assertThat(service.readSummary(context.artifactReference())).isNull();
        assertThat(service.hasReport(context.artifactReference())).isFalse();
    }

    // --- deterministisch en lezen ------------------------------------------------------------------------------------

    @Test
    void twoRunsOnTheSameBundleGiveTheSameItemsAndCounters() throws Exception {
        seedAllOutcomes();

        RunContext firstContext = context();
        ParallelRunSummary first = service.run(firstContext);
        RunContext secondContext = context();
        ParallelRunSummary second = service.run(secondContext);

        assertThat(withoutVolatile(report(secondContext))).isEqualTo(withoutVolatile(report(firstContext)));
        assertThat(report(firstContext).path("items")).hasSize(6);
        assertThat(second.outcomeCounts()).isEqualTo(first.outcomeCounts());
        assertThat(second.reasonCounts()).isEqualTo(first.reasonCounts());
        assertThat(second.noticeCounts()).isEqualTo(first.noticeCounts());
        assertThat(second.itemCount()).isEqualTo(first.itemCount());
    }

    @Test
    void aMissingSummaryIsNoResultAndACorruptOneIsUnreadableNeverAnException() throws IOException {
        String withoutParallelRun = mainArtifact();
        assertThat(service.readSummary(withoutParallelRun)).isNull();
        assertThat(service.hasReport(withoutParallelRun)).isFalse();
        assertThat(service.readSummary(null)).isNull();

        String corrupt = mainArtifact();
        Files.writeString(root.resolve(store.siblingReference(corrupt, PublicationParallelRunService.SUMMARY_FILE_NAME)),
                "{ not json");
        assertThat(service.readSummary(corrupt).status())
                .isEqualTo(PublicationParallelRunService.STATUS_SUMMARY_UNREADABLE);
    }

    // --- hulp ----------------------------------------------------------------------------------------------------

    /**
     * Zes rijen: WOULD_UPDATE (100 → 120), UNCHANGED (Prodis staat al op de target), UNCHANGED + DERIVED_PRICE_DRIFT
     * (scheve afgeleide prijzen), CONFLICT (Prodis-basisprijs gewijzigd), REJECTED (artikel bestaat niet), NOT_SENT
     * (CREATE).
     */
    private void seedAllOutcomes() {
        rows.add(row(r -> {
            r.mutationId = 1L;
            r.reference = "REF-1";
        }));
        prodis.put(identity("REF-1"), lookup("REF-1", article("100", "Nieuwe omschrijving", b -> {
        })));
        rows.add(row(r -> {
            r.mutationId = 2L;
            r.reference = "REF-2";
        }));
        prodis.put(identity("REF-2"), lookup("REF-2", article("120", "Nieuwe omschrijving", b -> {
        })));
        rows.add(row(r -> {
            r.mutationId = 3L;
            r.reference = "REF-3";
        }));
        prodis.put(identity("REF-3"), lookup("REF-3", article("120", "Nieuwe omschrijving", b -> b.scale = 1.2)));
        rows.add(row(r -> {
            r.mutationId = 4L;
            r.reference = "REF-4";
        }));
        prodis.put(identity("REF-4"), lookup("REF-4", article("77", "Nieuwe omschrijving", b -> {
        })));
        rows.add(row(r -> {
            r.mutationId = 5L;
            r.reference = "REF-5";
        }));
        rows.add(row(r -> {
            r.mutationId = 6L;
            r.reference = "REF-6";
            r.actionType = "CREATE";
            r.before = null;
        }));
    }

    private void assertSkipped(ParallelRunSummary summary) {
        assertThat(summary.itemCount()).isNull();
        assertThat(summary.outcomeCounts()).isNull();
        assertThat(summary.reasonCounts()).isNull();
        assertThat(summary.noticeCounts()).isNull();
        assertThat(summary.writesToProdis()).isFalse();
    }

    private RunContext context() {
        return new RunContext(runIds.incrementAndGet(), 7L, "c0ffee", "beef", "1", mainArtifact());
    }

    /** Een PSIMPORT-artefact van een nieuwe run, zoals {@code PublicationRunService} het eerst schrijft. */
    private String mainArtifact() {
        StoredArtifact stored = store.write(runIds.incrementAndGet(), out -> out.write("# PREVIEW\n"));
        return stored.reference();
    }

    private Path reportPath(RunContext context) {
        return root.resolve(store.siblingReference(context.artifactReference(),
                PublicationParallelRunService.REPORT_FILE_NAME));
    }

    private JsonNode report(RunContext context) throws IOException {
        try (InputStream in = Files.newInputStream(reportPath(context))) {
            return JSON.readTree(in);
        }
    }

    private static JsonNode withoutVolatile(JsonNode document) {
        ObjectNode copy = document.deepCopy();
        copy.remove(List.of("runId", "startedAt"));
        ((ObjectNode) copy.path("summary")).remove(List.of("startedAt", "finishedAt"));
        return copy;
    }

    private static CatalogArticleIdentity identity(String reference) {
        return new CatalogArticleIdentity("0601", "0601|GRP|K1", "050", reference);
    }

    private static CatalogArticleLookup lookup(String reference, CatalogArticleRow article) {
        return new CatalogArticleLookup(identity(reference), 1, List.of(article));
    }

    /** Prijscode 0, bruto-politiek 1; percentages 10..50 met prijzen basis × % × {@code scale} (1 = consistent). */
    private static final class ArticleBuilder {
        double scale = 1d;
    }

    private static CatalogArticleRow article(String base, String description, Consumer<ArticleBuilder> change) {
        ArticleBuilder builder = new ArticleBuilder();
        change.accept(builder);
        double basis = Double.parseDouble(base);
        List<CatalogArticlePriceDetailRow> details = new ArrayList<>();
        for (int code = 1; code <= 5; code++) {
            double percentage = code * 10d;
            double price = BigDecimal.valueOf(basis * percentage / 100d / builder.scale).setScale(2,
                    java.math.RoundingMode.HALF_UP).doubleValue();
            details.add(new CatalogArticlePriceDetailRow(Integer.toString(code), percentage, price, true, false));
        }
        double brut = details.get(0).price();
        CatalogArticlePriceRow price = new CatalogArticlePriceRow("0", "1", new BigDecimal(base), brut, 0d, LAST_UPDATED,
                true, false, details);
        return new CatalogArticleRow(true, false, List.of(price),
                List.of(new CatalogArticleDescriptionRow(description, true, false)));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
