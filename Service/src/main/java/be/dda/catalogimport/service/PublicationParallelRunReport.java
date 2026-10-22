package be.dda.catalogimport.service;

import be.dda.catalogimport.service.ProdisPublicationPlanner.ArticleState;
import be.dda.catalogimport.service.ProdisPublicationPlanner.Change;
import be.dda.catalogimport.service.ProdisPublicationPlanner.Plan;
import be.dda.catalogimport.service.ProdisPublicationPlanner.PlanOutcome;
import be.dda.catalogimport.service.ProdisPublicationProjection.AmountField;
import be.dda.catalogimport.service.ProdisPublicationProjection.Item;
import be.dda.catalogimport.service.ProdisPublicationProjection.Notice;
import be.dda.catalogimport.service.PublicationParallelRunService.ParallelRunSummary;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Het JSON-rapport van de publicatie-parallelrun (design {@code publicatie-prodis-db-direct-design.md} par. 5 en 6, slice
 * P2c; {@code publicatie-prodis-db-design.md} P2 "artefact/tellers"). Schrijft paginagewijs naar een stroom (de bundel komt
 * nooit volledig in het geheugen) en telt intussen per uitkomst, per redencode en per meldingscode.
 *
 * <h2>Vorm (parallelRunSpecVersion 1)</h2>
 * <pre>
 * { "kind": "PRODIS_PARALLEL_RUN", "parallelRunSpecVersion": 1, "writesToProdis": false, "prodisAccess": "READ_ONLY",
 *   "runId", "bundleId", "bundleContentHash", "snapshotHash", "snapshotSpecVersion", "startedAt",
 *   "items": [ { mutationId, requestHash, action, identity, currency, basePrice, salesPercentages, description,
 *                outcome, reasonCode, salesPriceLastUpdated, changes, derivedDrift, notices, prior, target } ],
 *   "summary": { status, statusMessage, legacyMaster…, itemCount, outcomeCounts, reasonCounts, noticeCounts, … } }
 * </pre>
 * <ul>
 *   <li><b>Deterministisch</b>: items in de volgorde van de lezer ({@code mutation_id} oplopend), vaste veldvolgorde,
 *       kaarten gesorteerd, regeleinde altijd {@code \n} (ook op Windows). Enkel {@code runId}, {@code startedAt} en
 *       {@code finishedAt} verschillen tussen twee runs op dezelfde bundel.</li>
 *   <li><b>Bedragen als tekst</b> ({@code stripTrailingZeros().toPlainString()}, zoals de planner): nooit een
 *       JSON-getal dat een lezer als {@code double} kan herinterpreteren.</li>
 *   <li><b>Geen Prodis-id's</b>: enkel businesscodes en waarden.</li>
 * </ul>
 */
public final class PublicationParallelRunReport {

    /** Versie van de vorm van dit rapport en van het samenvattingsbestand. */
    public static final int SPEC_VERSION = 1;
    public static final String KIND = "PRODIS_PARALLEL_RUN";
    /** De parallelrun gebruikt enkel de alleen-lezen leesbron ({@code catalogimport_ro}). */
    public static final String PRODIS_ACCESS = "READ_ONLY";

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .build();

    /** De kop van het rapport; tijdstempels en {@code runId} vallen buiten elke vergelijking. */
    public record Header(long runId, long bundleId, String bundleContentHash, String snapshotHash,
                         String snapshotSpecVersion, java.time.Instant startedAt) {
    }

    /** De tellers van een (voltooid) rapport. */
    public record Counts(long itemCount, Map<String, Long> outcomeCounts, Map<String, Long> reasonCounts,
                         Map<String, Long> noticeCounts) {
        public Counts {
            outcomeCounts = Collections.unmodifiableMap(new LinkedHashMap<>(outcomeCounts));
            reasonCounts = Collections.unmodifiableMap(new TreeMap<>(reasonCounts));
            noticeCounts = Collections.unmodifiableMap(new TreeMap<>(noticeCounts));
        }
    }

    private final JsonGenerator json;
    private final Map<PlanOutcome, Long> outcomes = new EnumMap<>(PlanOutcome.class);
    private final Map<String, Long> reasons = new TreeMap<>();
    private final Map<String, Long> notices = new TreeMap<>();
    private long items;
    private boolean itemsOpen;

    public PublicationParallelRunReport(Writer out) throws IOException {
        this.json = generator(out);
        for (PlanOutcome outcome : PlanOutcome.values()) {
            outcomes.put(outcome, 0L);
        }
    }

    /** Schrijft de kop en opent de itemlijst. */
    public void begin(Header header) throws IOException {
        json.writeStartObject();
        json.writeStringField("kind", KIND);
        json.writeNumberField("parallelRunSpecVersion", SPEC_VERSION);
        json.writeBooleanField("writesToProdis", false);
        json.writeStringField("prodisAccess", PRODIS_ACCESS);
        json.writeNumberField("runId", header.runId());
        json.writeNumberField("bundleId", header.bundleId());
        json.writeStringField("bundleContentHash", header.bundleContentHash());
        json.writeStringField("snapshotHash", header.snapshotHash());
        json.writeStringField("snapshotSpecVersion", header.snapshotSpecVersion());
        json.writeStringField("startedAt", header.startedAt() == null ? null : header.startedAt().toString());
        json.writeArrayFieldStart("items");
        itemsOpen = true;
    }

    /** Eén geplande snapshotrij: schrijft het item en telt het mee. */
    public void item(Item item, Plan plan) throws IOException {
        if (!itemsOpen) {
            throw new IllegalStateException("begin() first");
        }
        if (item.mutationId() != plan.mutationId()) {
            throw new IllegalArgumentException("the plan belongs to mutation " + plan.mutationId() + ", not "
                    + item.mutationId());
        }
        count(plan);
        json.writeStartObject();
        json.writeNumberField("mutationId", item.mutationId());
        json.writeStringField("requestHash", plan.requestHash());
        json.writeStringField("action", item.action() == null ? null : item.action().name());
        json.writeObjectFieldStart("identity");
        json.writeStringField("supplierNumber", item.identity().supplierNumber());
        json.writeStringField("groupLegacyCode", item.identity().groupLegacyCode());
        json.writeStringField("supplierReference", item.identity().supplierReference());
        json.writeStringField("scopeKey", item.identity().scopeKey());
        json.writeEndObject();
        json.writeStringField("currency", item.currency());
        writeAmountField("basePrice", item.basePrice(), item.expected().basePrice());
        json.writeObjectFieldStart("salesPercentages");
        for (Map.Entry<Integer, AmountField> entry : item.salesPercentages().entrySet()) {
            writeAmountField(Integer.toString(entry.getKey()), entry.getValue(),
                    item.expected().percentages().get(entry.getKey()));
        }
        json.writeEndObject();
        if (item.description() == null) {
            json.writeNullField("description");
        } else {
            json.writeObjectFieldStart("description");
            json.writeStringField("state", item.description().state().name());
            json.writeEndObject();
        }
        json.writeStringField("outcome", plan.outcome().name());
        json.writeStringField("reasonCode", plan.reasonCode());
        json.writeBooleanField("salesPriceLastUpdated", plan.salesPriceLastUpdated());
        writeChanges("changes", plan.changes());
        writeChanges("derivedDrift", plan.derivedDrift());
        json.writeArrayFieldStart("notices");
        for (Notice notice : plan.notices()) {
            json.writeStartObject();
            json.writeStringField("code", notice.code());
            json.writeStringField("severity", notice.severity().name());
            json.writeStringField("field", notice.field());
            json.writeStringField("message", notice.message());
            json.writeEndObject();
        }
        json.writeEndArray();
        writeState("prior", plan.prior());
        writeState("target", plan.target());
        json.writeEndObject();
    }

    /** De tellers tot nu toe. */
    public Counts counts() {
        Map<String, Long> outcomeCounts = new LinkedHashMap<>();
        outcomes.forEach((outcome, count) -> outcomeCounts.put(outcome.name(), count));
        return new Counts(items, outcomeCounts, reasons, notices);
    }

    /** Sluit de itemlijst, schrijft de samenvatting en sluit het document (de stroom zelf blijft open). */
    public void end(ParallelRunSummary summary) throws IOException {
        if (!itemsOpen) {
            throw new IllegalStateException("begin() first");
        }
        json.writeEndArray();
        itemsOpen = false;
        json.writeFieldName("summary");
        writeSummary(json, summary);
        json.writeEndObject();
        json.writeRaw('\n');
        json.flush();
    }

    /**
     * Het losse samenvattingsbestand: dezelfde samenvatting als in het rapport, aangevuld met de SHA-256 en grootte van
     * het rapport (in het rapport zelf kan dat niet). Klein: de runview leest enkel dit bestand.
     */
    public static void writeSummaryDocument(Writer out, ParallelRunSummary summary) throws IOException {
        JsonGenerator json = generator(out);
        json.writeStartObject();
        json.writeStringField("kind", KIND + "_SUMMARY");
        json.writeFieldName("summary");
        writeSummary(json, summary);
        json.writeEndObject();
        json.writeRaw('\n');
        json.flush();
    }

    // --- hulp ----------------------------------------------------------------------------------------------------

    private void count(Plan plan) {
        items++;
        outcomes.merge(plan.outcome(), 1L, Long::sum);
        if (plan.reasonCode() != null) {
            reasons.merge(plan.reasonCode(), 1L, Long::sum);
        }
        for (Notice notice : plan.notices()) {
            notices.merge(notice.code(), 1L, Long::sum);
        }
    }

    private static void writeSummary(JsonGenerator json, ParallelRunSummary summary) throws IOException {
        json.writeStartObject();
        json.writeNumberField("parallelRunSpecVersion", summary.parallelRunSpecVersion());
        json.writeStringField("status", summary.status());
        json.writeStringField("statusMessage", summary.statusMessage());
        json.writeBooleanField("writesToProdis", summary.writesToProdis());
        json.writeStringField("legacyMaster", summary.legacyMaster());
        json.writeStringField("legacyMasterAvailability", summary.legacyMasterAvailability());
        json.writeStringField("legacyMasterWarningCode", summary.legacyMasterWarningCode());
        json.writeStringField("legacyMasterWarning", summary.legacyMasterWarning());
        if (summary.itemCount() == null) {
            json.writeNullField("itemCount");
        } else {
            json.writeNumberField("itemCount", summary.itemCount());
        }
        writeCounts(json, "outcomeCounts", summary.outcomeCounts());
        writeCounts(json, "reasonCounts", summary.reasonCounts());
        writeCounts(json, "noticeCounts", summary.noticeCounts());
        json.writeStringField("startedAt", summary.startedAt() == null ? null : summary.startedAt().toString());
        json.writeStringField("finishedAt", summary.finishedAt() == null ? null : summary.finishedAt().toString());
        json.writeStringField("reportSha256", summary.reportSha256());
        if (summary.reportByteSize() == null) {
            json.writeNullField("reportByteSize");
        } else {
            json.writeNumberField("reportByteSize", summary.reportByteSize());
        }
        json.writeEndObject();
    }

    private static void writeCounts(JsonGenerator json, String name, Map<String, Long> counts) throws IOException {
        if (counts == null) {
            json.writeNullField(name);
            return;
        }
        json.writeObjectFieldStart(name);
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            json.writeNumberField(entry.getKey(), entry.getValue());
        }
        json.writeEndObject();
    }

    private void writeAmountField(String name, AmountField field, BigDecimal expected) throws IOException {
        json.writeObjectFieldStart(name);
        json.writeStringField("state", field.state().name());
        json.writeStringField("value", plain(field.value()));
        json.writeStringField("expected", plain(expected));
        json.writeEndObject();
    }

    private void writeChanges(String name, List<Change> changes) throws IOException {
        json.writeArrayFieldStart(name);
        for (Change change : changes) {
            json.writeStartObject();
            json.writeStringField("table", change.column().table());
            json.writeStringField("column", change.column().column());
            if (change.detailCode() == null) {
                json.writeNullField("detailCode");
            } else {
                json.writeNumberField("detailCode", change.detailCode());
            }
            json.writeStringField("prior", change.prior());
            json.writeStringField("target", change.target());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private void writeState(String name, ArticleState state) throws IOException {
        if (state == null) {
            json.writeNullField(name);
            return;
        }
        json.writeObjectFieldStart(name);
        json.writeStringField("priceCode", state.priceCode());
        json.writeStringField("brutPricePolitics", state.brutPricePolitics());
        json.writeStringField("basePrice", plain(state.basePrice()));
        json.writeStringField("brutPrice", plain(state.brutPrice()));
        json.writeStringField("brutPricePercentage", plain(state.brutPricePercentage()));
        writeDoubles("percentages", state.percentages());
        writeDoubles("prices", state.prices());
        json.writeStringField("description", state.description());
        json.writeStringField("salesPriceLastUpdated",
                state.salesPriceLastUpdated() == null ? null : state.salesPriceLastUpdated().toString());
        json.writeEndObject();
    }

    private void writeDoubles(String name, Map<Integer, Double> values) throws IOException {
        json.writeObjectFieldStart(name);
        for (Map.Entry<Integer, Double> entry : values.entrySet()) {
            json.writeStringField(Integer.toString(entry.getKey()), plain(entry.getValue()));
        }
        json.writeEndObject();
    }

    private static JsonGenerator generator(Writer out) throws IOException {
        JsonGenerator json = FACTORY.createGenerator(out);
        // Vaste inspringing en altijd "\n": het rapport is byte-gelijk op elk platform (DefaultIndenter gebruikt anders
        // System.lineSeparator()).
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        json.setPrettyPrinter(new DefaultPrettyPrinter().withObjectIndenter(indenter).withArrayIndenter(indenter));
        return json;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String plain(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
