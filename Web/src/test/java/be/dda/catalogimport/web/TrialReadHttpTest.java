package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * NT-9: proefinlezing {@code POST /revisions/{id}/trial-reads} (bindend contract {@code docs/design/proefinlezing-design.md};
 * beslissingslog 2026-09-30 "NT-9a" en NT-spoor V1 = A).
 *
 * <ul>
 *   <li><b>Regel (pariteit, contract par. 4):</b> per scenario geeft de proef (met {@code linkId}) hetzelfde oordeel als
 *       een echte upload van hetzelfde bestand op een verse koppeling zonder bronstaat: tellers, {@code blockedCode}
 *       (WOULD_BLOCK ⇔ BLOCKED, NO_BLOCKER_FOUND ⇔ SCREENED), de gegroepeerde issuegroepen en de CRITICAL/WARNING-aantallen.
 *       <b>Implementatie:</b> gedeelde {@code RecordScreeningCore}, {@code loadConfiguration}, bulk- en voorrangsregel.</li>
 *   <li><b>Regel (idempotentie, par. 6):</b> tweemaal hetzelfde bestand ⇒ byte-gelijk antwoord. <b>Data:</b> de tellingen
 *       van {@code delivery}, {@code delivery_file}, {@code import_batch}, {@code import_row_issue},
 *       {@code import_issue_group}, {@code import_mutation}, {@code task_run}, {@code catalog_source_state} en de
 *       archiefmap zijn vóór en na gelijk.</li>
 *   <li><b>Regel (par. 3 en 5):</b> MANAGE, foutcodes in de volgorde van het contract, DRAFT werkt, één INFO-logregel
 *       zonder bestandsnaam of inhoud.</li>
 * </ul>
 * Elke test bouwt een eigen keten (via de setup-API) met unieke codes: de database is gedeeld.
 * <p>
 * <b>Bewust verschil met de screening</b> (gemeld in het rapport van NT-9): op een levering die op een drempel of op
 * "geen datarecords" strandt, schrijft de screening {@code duplicate_identity_count} niet (blijft {@code null}); de
 * proef heeft de duplicaatcontrole dan wél gedaan en toont de gemeten {@code 0}.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
class TrialReadHttpTest {

    private static final String API = "/api/catalog-import";
    private static final String SETUP = API + "/setup";
    private static final String USER = "an.janssens@example.test";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "leverancier;groep;referentie;prijs;omschrijving";
    private static final String[] SIDE_EFFECT_TABLES = {"delivery", "delivery_file", "import_batch", "import_row_issue",
            "import_issue_group", "import_mutation", "task_run", "catalog_source_state", "import_candidate_stage"};

    @TempDir
    static Path archiveRoot;
    @TempDir
    static Path localSourceRoot;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> localSourceRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Pariteit per scenario (contract par. 4) -------------------------------------------------------------------

    @Test
    void aValidFileHasNoBlockerAndShowsTheRowsExactlyAsTheScreeningStagesThem() throws Exception {
        Chain chain = chain("VAL", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = csv(row("R1", "10,00", "Hamer"), row("R2", "2,5", "Zaag"), row("R3", "3.75", "Boor"));

        String trial = trialOk(chain, csv, true);
        long batchId = upload(chain, csv);

        assertParity(trial, batchId);
        assertThat(string(trial, "$.verdict.result")).isEqualTo("NO_BLOCKER_FOUND");
        assertThat(string(trial, "$.verdict.stage")).isNull();
        assertThat(number(trial, "$.counters.rawRecordCount")).isEqualTo(3L);
        assertThat(number(trial, "$.counters.physicalLineCount")).isEqualTo(4L);
        assertThat(number(trial, "$.counters.columnCount")).isEqualTo(5L);
        assertThat(number(trial, "$.counters.linesWithReplacementCharacter")).isZero();
        assertThat(string(trial, "$.revisionStatus")).isEqualTo("ACTIVE");
        assertThat(number(trial, "$.linkId")).isEqualTo(chain.linkId());
        assertThat(string(trial, "$.currencyDefault.origin")).isEqualTo("SYSTEM_DEFAULT");
        assertThat(string(trial, "$.currencyDefault.value")).isEqualTo("EUR");
        assertThat(number(trial, "$.file.byteSize")).isEqualTo((long) csv.length);
        assertThat(string(trial, "$.file.sha256")).isEqualTo(sha256(csv));
        assertThat(string(trial, "$.file.fileName")).isEqualTo("proef.csv");

        // Voorbeeldrijen: prijs zoals gelezen én zoals geïnterpreteerd, identiteit gelijk aan wat de screening stagede.
        assertThat(this.<List<Object>>read(trial, "$.sampleRows")).hasSize(3);
        assertThat(string(trial, "$.sampleRows[0].status")).isEqualTo("VALID");
        assertThat(number(trial, "$.sampleRows[0].lineNumber")).isEqualTo(2L);
        assertThat(this.<List<String>>read(trial, "$.sampleRows[0].rawValues"))
                .containsExactly("ACME", "G1", "R1", "10,00", "Hamer");
        assertThat(string(trial, "$.sampleRows[0].filter.kind")).isEqualTo("IN_SCOPE");
        assertThat(string(trial, "$.sampleRows[0].interpreted.supplier")).isEqualTo("ACME");
        assertThat(string(trial, "$.sampleRows[0].interpreted.discountState")).isEqualTo("NOT_USED");
        assertThat(string(trial, "$.sampleRows[2].interpreted.basePriceRaw")).isEqualTo("3.75");
        assertThat(string(trial, "$.sampleRows[0].interpreted.basePrice")).isEqualTo("10.000000");
        for (int index = 0; index < 3; index++) {
            long line = index + 2L;
            assertThat(string(trial, "$.sampleRows[" + index + "].interpreted.basePrice"))
                    .isEqualTo(jdbc.queryForObject("select base_price from import_candidate_stage where batch_id = ? "
                            + "and row_number = ?", BigDecimal.class, batchId, line).toPlainString());
            assertThat(string(trial, "$.sampleRows[" + index + "].interpreted.identityHash"))
                    .isEqualTo(HexFormat.of().formatHex(jdbc.queryForObject("select identity_hash from "
                            + "import_candidate_stage where batch_id = ? and row_number = ?", byte[].class, batchId, line)));
        }
        assertThat(string(trial, "$.sampleRows[0].interpreted.currency")).isEqualTo("EUR");
        assertThat(string(trial, "$.sampleRows[0].interpreted.currencyOrigin")).isEqualTo("SYSTEM_DEFAULT");
        assertThat((Boolean) read(trial, "$.sampleRowsTruncated")).isFalse();

        // Header: alles gevonden, niets extra.
        assertThat(this.<List<String>>read(trial, "$.header.foundColumns"))
                .containsExactly("leverancier", "groep", "referentie", "prijs", "omschrijving");
        assertThat(this.<List<Object>>read(trial, "$.header.missingRequired")).isEmpty();
        assertThat(this.<List<Object>>read(trial, "$.header.extraColumns")).isEmpty();
        assertThat(number(trial, "$.header.expectedColumns[3].foundAtPosition")).isEqualTo(4L);
        assertThat(string(trial, "$.header.expectedColumns[3].role")).isEqualTo("PRICE");

        // Drempels: kritiek is een ondergrens, identiteitsincidenten niet geëvalueerd.
        assertThat(string(trial, "$.thresholds.critical.outcome")).isEqualTo("NOT_APPLICABLE");
        assertThat((Boolean) read(trial, "$.thresholds.critical.countIsLowerBound")).isTrue();
        assertThat((Boolean) read(trial, "$.thresholds.critical.identityIncidentsEvaluated")).isFalse();
        assertThat(string(trial, "$.thresholds.critical.thresholdPercent")).isEqualTo("25");
        assertThat(string(trial, "$.thresholds.rejected.thresholdPercent")).isNull();
        assertThat(string(trial, "$.thresholds.bulkIncidentSharePercent")).isEqualTo("1");
        assertThat(this.<List<String>>read(trial, "$.notEvaluated[*].check")).contains("CREATION_POLICY",
                "IDENTITY_HASH_COLLISION_AGAINST_SOURCE_STATE", "PRICE_DEVIATION", "MANIFEST_COUNTS",
                "IDENTITY_HASH_COLLISION_IN_FILE", "IDENTITY_INCIDENTS_IN_CRITICAL_THRESHOLD",
                "DUPLICATE_REFERENCE_IN_DELIVERY").doesNotContain("REFERENCE_CONTROL");
        assertThat(this.<List<String>>read(trial, "$.notEvaluated[*].status")).containsOnly("INFO");
        assertThat(this.<List<Object>>read(trial, "$.configProblems")).isEmpty();
    }

    @Test
    void anUnreadablePriceRejectsOnlyThatLineAndCountsAsACriticalLineWithinTheThreshold() throws Exception {
        Chain chain = chain("PRC", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "abc", "b"), row("R3", "3,00", "c"), row("R4", "4,00", "d"),
                row("R5", "5,00", "e"));

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.verdict.result")).isEqualTo("NO_BLOCKER_FOUND");
        assertThat(number(trial, "$.counters.rejectedRecordCount")).isEqualTo(1L);
        assertThat(number(trial, "$.counters.criticalLineCount")).isEqualTo(1L);
        assertThat(string(trial, "$.thresholds.critical.outcome")).isEqualTo("WITHIN");
        assertThat(string(trial, "$.thresholds.critical.sharePercent")).isEqualTo("20");
        assertThat(string(trial, "$.sampleRows[1].status")).isEqualTo("REJECTED");
        assertThat(string(trial, "$.sampleRows[1].interpreted")).isNull();
        assertThat(string(trial, "$.sampleRows[1].issues[0].code")).isEqualTo("PRICE_UNREADABLE");
        assertThat(string(trial, "$.sampleRows[1].issues[0].sourceValue")).isEqualTo("abc");
        assertThat(string(trial, "$.issueGroups[0].code")).isEqualTo("PRICE_UNREADABLE");
        assertThat(string(trial, "$.issueGroups[0].fieldName")).isEqualTo("prijs");
        assertThat((Boolean) read(trial, "$.issueGroups[0].grouped")).isFalse();
        assertThat(number(trial, "$.issueGroups[0].examples[0].lineNumber")).isEqualTo(3L);
        assertThat(number(trial, "$.counters.issueOccurrencesBySeverity.ERROR")).isEqualTo(1L);
    }

    @Test
    void anEmptyIdentityComponentRejectsTheLine() throws Exception {
        Chain chain = chain("IDC", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = csv(row("R1", "1,00", "a"), "ACME;;R2;2,00;b", row("R3", "3,00", "c"), row("R4", "4,00", "d"),
                row("R5", "5,00", "e"));

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(number(trial, "$.counters.rejectedRecordCount")).isEqualTo(1L);
        assertThat(string(trial, "$.sampleRows[1].issues[0].code")).isEqualTo("IDENTITY_COMPONENT_EMPTY");
    }

    @Test
    void aLineWithTheWrongColumnCountIsAnUnreadableSampleAndOnlyThatLineIsRejected() throws Exception {
        Chain chain = chain("COL", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = csv(row("R1", "1,00", "a"), "ACME;G1;R2", row("R3", "3,00", "c"), row("R4", "4,00", "d"),
                row("R5", "5,00", "e"));

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.sampleRows[1].status")).isEqualTo("UNREADABLE");
        assertThat(string(trial, "$.sampleRows[1].sourceValue")).isEqualTo("ACME;G1;R2");
        assertThat(string(trial, "$.sampleRows[1].rawValues")).isNull();
        assertThat(string(trial, "$.sampleRows[1].filter")).isNull();
        assertThat(string(trial, "$.sampleRows[1].issues[0].code")).isEqualTo("ROW_COLUMN_COUNT_MISMATCH");
        assertThat(number(trial, "$.counters.criticalLineCount")).isEqualTo(1L);
    }

    @Test
    void aMissingHeaderFieldBlocksWithTheFirstOneButTheHeaderShowsAllMissingColumns() throws Exception {
        Chain chain = chain("HDR", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = ("leverancier;groep;referentie;extra\nACME;G1;R1;x\n").getBytes(StandardCharsets.UTF_8);

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.verdict.result")).isEqualTo("WOULD_BLOCK");
        assertThat(string(trial, "$.verdict.blockedCode")).isEqualTo("HEADER_FIELD_MISSING:prijs");
        assertThat(string(trial, "$.verdict.stage")).isEqualTo("READING");
        assertThat(string(trial, "$.verdict.blockedReason")).contains("prijs");
        assertThat(this.<List<String>>read(trial, "$.header.missingRequired")).containsExactly("prijs", "omschrijving");
        assertThat(string(trial, "$.header.extraColumns[0].name")).isEqualTo("extra");
        assertThat(number(trial, "$.header.extraColumns[0].position")).isEqualTo(4L);
        assertThat(number(trial, "$.header.foundColumnCount")).isEqualTo(4L);
        // Leesblokkade: alle tellers null, nooit 0.
        for (String counter : new String[] {"rawRecordCount", "validRecordCount", "rejectedRecordCount",
                "filteredOutCount", "errorBeforeFilterCount", "criticalLineCount", "duplicateIdentityCount",
                "scopeRecordCount", "physicalLineCount", "columnCount", "linesWithReplacementCharacter"}) {
            assertThat((Object) read(trial, "$.counters." + counter)).as(counter).isNull();
        }
        assertThat(this.<List<Object>>read(trial, "$.sampleRows")).isEmpty();
        assertThat(string(trial, "$.thresholds.critical.outcome")).isEqualTo("UNDETERMINED");
        assertThat(this.<List<Object>>read(trial, "$.configProblems")).isEmpty();
        // Het bestand is toch volledig gehasht.
        assertThat(string(trial, "$.file.sha256")).isEqualTo(sha256(csv));
    }

    @Test
    void aRepeatedIdentityBlocksAndCountsEveryInvolvedLineLikeTheScreening() throws Exception {
        Chain chain = chain("DUP", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "2,00", "b"), row("R1", "3,00", "c"));

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.verdict.blockedCode")).isEqualTo("DUPLICATE_IDENTITY_IN_DELIVERY");
        assertThat(string(trial, "$.verdict.stage")).isEqualTo("IDENTITY");
        assertThat(number(trial, "$.counters.duplicateIdentityCount")).isEqualTo(2L);
        List<Map<String, Object>> groups = read(trial, "$.issueGroups[?(@.code == 'DUPLICATE_IDENTITY_IN_DELIVERY')]");
        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(((Number) group.get("occurrenceCount")).longValue()).isEqualTo(2L);
            assertThat(group.get("severity")).isEqualTo("BLOCKING");
        });
        assertThat(this.<List<String>>read(trial,
                "$.issueGroups[?(@.code == 'DUPLICATE_IDENTITY_IN_DELIVERY')].examples[0].message").get(0))
                .endsWith("first occurrence on line 2");
        assertThat(this.<List<Number>>read(trial,
                "$.issueGroups[?(@.code == 'DUPLICATE_IDENTITY_IN_DELIVERY')].examples[0].lineNumber").get(0).longValue())
                .isEqualTo(4L);
    }

    @Test
    void tenOrMoreIdenticalErrorsAboveOnePercentAreABulkGroupWithTheRealCount() throws Exception {
        Chain chain = chain("BLK", revisionJson("25", "null"), null, true, revisionId -> { });
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            rows.add(row("R" + i, "1,00", i % 10 == 0 ? "x".repeat(1001) : "Artikel " + i));
        }
        byte[] csv = csv(rows.toArray(String[]::new));

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.verdict.result")).isEqualTo("NO_BLOCKER_FOUND");
        List<Map<String, Object>> groups = read(trial, "$.issueGroups[?(@.code == 'VALUE_TOO_LONG')]");
        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(((Number) group.get("occurrenceCount")).longValue()).isEqualTo(10L);
            assertThat(group.get("grouped")).isEqualTo(true);
            assertThat(group.get("bulkIncident")).isEqualTo(true);
            assertThat(group.get("sharePercent")).isEqualTo("10");
            assertThat(group.get("examplesTruncated")).isEqualTo(true);
            assertThat((List<?>) group.get("examples")).hasSize(5);
        });
        assertThat(number(trial, "$.counters.criticalLineCount")).isZero();
        assertThat(this.<List<Object>>read(trial, "$.sampleRows")).hasSize(20);
        assertThat((Boolean) read(trial, "$.sampleRowsTruncated")).isTrue();
        // Een cel wordt in de voorbeeldrij op 200 tekens gekapt.
        assertThat(string(trial, "$.sampleRows[9].rawValues[4]")).hasSize(200);
    }

    @Test
    void filtersDecideTheScopeAndTheCountersReconcileExactlyOnTheThreshold() throws Exception {
        Chain chain = chain("FLT", revisionJson("25", "null"), null, true, revisionId ->
                setupJson(post(SETUP + "/revisions/{id}/filters", revisionId), "{\"sourceReference\":\"groep\","
                        + "\"operator\":\"EQUALS\",\"compareValue\":\"MEET\",\"outcome\":\"EXCLUDE\"}")
                        .andExpect(status().isCreated()));
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "2,00", "b"), "ACME;MEET;R3;3,00;c", row("R4", "4,00", "d"),
                "ACME;MEET;R5;5,00;e", "ACME;G1;R6");

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(number(trial, "$.counters.rawRecordCount")).isEqualTo(6L);
        assertThat(number(trial, "$.counters.validRecordCount")).isEqualTo(3L);
        assertThat(number(trial, "$.counters.filteredOutCount")).isEqualTo(2L);
        assertThat(number(trial, "$.counters.errorBeforeFilterCount")).isEqualTo(1L);
        assertThat(number(trial, "$.counters.rejectedRecordCount")).isZero();
        assertThat(number(trial, "$.counters.scopeRecordCount")).isEqualTo(4L);
        // 1 kritieke lijn op 4 in scope = exact 25%: niet overschreden.
        assertThat(string(trial, "$.thresholds.critical.outcome")).isEqualTo("WITHIN");
        assertThat(string(trial, "$.verdict.result")).isEqualTo("NO_BLOCKER_FOUND");
        assertThat(string(trial, "$.sampleRows[2].status")).isEqualTo("FILTERED_OUT");
        assertThat(string(trial, "$.sampleRows[2].filter.kind")).isEqualTo("FILTERED_OUT");
        assertThat(number(trial, "$.sampleRows[2].filter.decidingSequenceNumber")).isEqualTo(1L);
        assertThat(string(trial, "$.header.expectedColumns[?(@.role == 'FILTER')].reference")).contains("groep");
    }

    @Test
    void aCriticalShareAboveTheThresholdBlocksWithTheCriticalCode() throws Exception {
        Chain chain = chain("CRT", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "abc", "b"), row("R3", "xyz", "c"), row("R4", "4,00", "d"),
                row("R5", "5,00", "e"));

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.verdict.blockedCode")).isEqualTo("CRITICAL_RECORD_THRESHOLD_EXCEEDED");
        assertThat(string(trial, "$.verdict.stage")).isEqualTo("THRESHOLD");
        assertThat(string(trial, "$.thresholds.critical.outcome")).isEqualTo("EXCEEDED");
        assertThat(string(trial, "$.thresholds.critical.sharePercent")).isEqualTo("40");
    }

    @Test
    void aRejectedShareAboveItsConfiguredThresholdBlocksWithTheRejectedCode() throws Exception {
        Chain chain = chain("REJ", revisionJson("100", "10"), null, true, revisionId -> { });
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "2,00", "x".repeat(1001)), row("R3", "3,00", "c"),
                row("R4", "4,00", "d"), row("R5", "5,00", "e"));

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.verdict.blockedCode")).isEqualTo("REJECTED_RECORD_THRESHOLD_EXCEEDED");
        assertThat(string(trial, "$.thresholds.critical.outcome")).isEqualTo("NOT_APPLICABLE");
        assertThat(string(trial, "$.thresholds.rejected.outcome")).isEqualTo("EXCEEDED");
        assertThat(string(trial, "$.thresholds.rejected.thresholdPercent")).isEqualTo("10");
    }

    @Test
    void anEmptyFileIsACompletedTrialThatWouldBlock() throws Exception {
        Chain chain = chain("EMP", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = new byte[0];

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.verdict.blockedCode")).isEqualTo("SOURCE_FILE_EMPTY");
        assertThat((Object) read(trial, "$.counters.rawRecordCount")).isNull();
        assertThat(number(trial, "$.file.byteSize")).isZero();
        assertThat((Object) read(trial, "$.header.foundColumnCount")).isNull();
        assertThat(this.<List<Object>>read(trial, "$.header.missingRequired")).isEmpty();
    }

    @Test
    void aHeaderWithoutDataRecordsBlocksWithRawZero() throws Exception {
        Chain chain = chain("HON", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = (HEADER + "\n").getBytes(StandardCharsets.UTF_8);

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.verdict.blockedCode")).isEqualTo("SOURCE_NO_DATA_RECORDS");
        assertThat(string(trial, "$.verdict.stage")).isEqualTo("FILE_LEVEL");
        assertThat(number(trial, "$.counters.rawRecordCount")).isZero();
        assertThat(number(trial, "$.counters.validRecordCount")).isZero();
    }

    @Test
    void aBookmarkMappingIsAConfigurationProblemAndEverythingElseStaysEmpty() throws Exception {
        Chain chain = chain("BMK", revisionJson("25", "null"), null, true, revisionId ->
                setupJson(post(SETUP + "/revisions/{id}/mappings", revisionId),
                        "{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\"}").andExpect(status().isCreated()));
        // Nog geen invulmechanisme voor sjabloonwaarden: de screening blokkeert zo'n mapping vóór het lezen.
        jdbc.update("update import_field_mapping set value_kind = 'BOOKMARK', bookmark_name = 'LAND' "
                + "where definition_revision_id = ?", chain.revisionId());
        byte[] csv = ((HEADER + ";ean\n") + row("R1", "1,00", "a") + ";5400000000001\n").getBytes(StandardCharsets.UTF_8);

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.verdict.blockedCode")).isEqualTo("CONFIG_MAPPING_SOURCE_UNRESOLVED");
        assertThat(string(trial, "$.verdict.stage")).isEqualTo("CONFIGURATION");
        assertThat(this.<List<Object>>read(trial, "$.configProblems")).isNotEmpty();
        assertThat(string(trial, "$.configProblems[0].code")).isEqualTo("CONFIG_MAPPING_SOURCE_UNRESOLVED");
        assertThat((Object) read(trial, "$.counters.rawRecordCount")).isNull();
        assertThat((Object) read(trial, "$.counters.issueOccurrencesBySeverity")).isNull();
        assertThat((Object) read(trial, "$.header")).isNull();
        assertThat((Object) read(trial, "$.thresholds")).isNull();
        assertThat(this.<List<Object>>read(trial, "$.sampleRows")).isEmpty();
        assertThat(this.<List<Object>>read(trial, "$.issueGroups")).isEmpty();
        assertThat(this.<List<Object>>read(trial, "$.notEvaluated")).isEmpty();
        assertThat(number(trial, "$.file.byteSize")).isEqualTo((long) csv.length);
    }

    /**
     * NT-14-3: bij meerdere onafhankelijke configuratiefouten toont de proef ze allemaal; de eerste is het verdict en
     * is de code waarmee een echte upload van hetzelfde bestand op dezelfde keten blokkeert.
     */
    @Test
    void severalIndependentConfigErrorsAreAllListedAndTheFirstIsTheVerdictAndTheRealUploadBlockedCode(
            CapturedOutput output) throws Exception {
        Chain chain = chain("ALC", revisionJson("25", "null"), null, true, revisionId -> { });
        jdbc.update("update import_definition_revision set identity_supplier_field = ' ', "
                + "identity_supplier_group_field = ' ', structure_header_line_number = 0 where id = ?",
                chain.revisionId());
        byte[] csv = csv(row("R1", "1,00", "a"));

        String trial = trialOk(chain, csv, true);
        long batchId = upload(chain, csv);

        assertThat(this.<List<Object>>read(trial, "$.configProblems").size()).isGreaterThanOrEqualTo(3);
        assertThat(this.<List<String>>read(trial, "$.configProblems[*].code")).containsExactlyInAnyOrder(
                "CONFIG_IDENTITY_FIELD_MISSING", "CONFIG_IDENTITY_FIELD_MISSING", "CONFIG_HEADER_LINE_INVALID");
        assertThat(string(trial, "$.verdict.stage")).isEqualTo("CONFIGURATION");
        assertThat(string(trial, "$.configProblems[0].code")).isEqualTo(string(trial, "$.verdict.blockedCode"));
        assertThat(string(trial, "$.configProblems[0].message")).isEqualTo(string(trial, "$.verdict.blockedReason"));
        assertThat(jdbc.queryForObject("select blocked_code from import_batch where id = ?", String.class, batchId))
                .isEqualTo(string(trial, "$.verdict.blockedCode"));
        assertThat(this.<List<Object>>read(trial, "$.configChecksSkippedBecause")).isNotNull();
        assertThat(output.getOut()).doesNotContain("differs from verdict");
        // Idempotent: byte-gelijk.
        assertThat(trialOk(chain, csv, true)).isEqualTo(trial);
    }

    @Test
    void aDependentConfigCheckIsListedInTheSkippedChecks(CapturedOutput output) throws Exception {
        Chain chain = chain("SKP", revisionJson("25", "null"), null, true, revisionId -> { });
        jdbc.update("update import_definition_revision set structure_delimiter = '', structure_quote_char = '''', "
                + "structure_header_line_number = 0 where id = ?", chain.revisionId());
        byte[] csv = csv(row("R1", "1,00", "a"));

        String trial = trialOk(chain, csv, true);
        long batchId = upload(chain, csv);

        assertThat(this.<List<String>>read(trial, "$.configProblems[*].code"))
                .contains("CONFIG_DELIMITER_MISSING", "CONFIG_HEADER_LINE_INVALID");
        assertThat(this.<List<String>>read(trial, "$.configChecksSkippedBecause")).isNotEmpty()
                .contains("CONFIG_DELIMITER_MISSING");
        assertThat(string(trial, "$.configProblems[0].code")).isEqualTo(string(trial, "$.verdict.blockedCode"));
        assertThat(jdbc.queryForObject("select blocked_code from import_batch where id = ?", String.class, batchId))
                .isEqualTo(string(trial, "$.verdict.blockedCode"));
        assertThat(output.getOut()).doesNotContain("differs from verdict");
    }

    @Test
    void theFixedCurrencyOfTheLinkIsUsedOnlyWhenTheLinkIsGiven() throws Exception {
        Chain chain = chain("CUR", revisionJson("25", "null"), "USD", true, revisionId -> { });
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "2,00", "b"));

        String withLink = trialOk(chain, csv, true);
        String withoutLink = trialOk(chain, csv, false);
        long batchId = upload(chain, csv);
        assertParity(withLink, batchId);

        assertThat(string(withLink, "$.currencyDefault.value")).isEqualTo("USD");
        assertThat(string(withLink, "$.currencyDefault.origin")).isEqualTo("LINK_DEFAULT");
        assertThat(string(withLink, "$.sampleRows[0].interpreted.currency")).isEqualTo("USD");
        assertThat(string(withLink, "$.sampleRows[0].interpreted.currencyOrigin")).isEqualTo("LINK_DEFAULT");
        assertThat(jdbc.queryForList("select base_price_currency || '/' || base_price_currency_origin "
                + "from import_candidate_stage where batch_id = ? order by row_number", String.class, batchId))
                .containsOnly("USD/LINK_DEFAULT");

        assertThat((Object) read(withoutLink, "$.linkId")).isNull();
        assertThat(string(withoutLink, "$.currencyDefault.value")).isEqualTo("EUR");
        assertThat(string(withoutLink, "$.currencyDefault.origin")).isEqualTo("SYSTEM_DEFAULT");
        assertThat(string(withoutLink, "$.sampleRows[0].interpreted.currencyOrigin")).isEqualTo("SYSTEM_DEFAULT");
        // De munt zit niet in de identiteit.
        assertThat(string(withoutLink, "$.sampleRows[0].interpreted.identityHash"))
                .isEqualTo(string(withLink, "$.sampleRows[0].interpreted.identityHash"));
    }

    @Test
    void aByteOrderMarkIsAWarningAndUndecodableBytesAreCountedAsACharsetHint() throws Exception {
        Chain chain = chain("BOM", revisionJson("25", "null"), null, true, revisionId -> { });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        bytes.write((HEADER + "\n" + row("R1", "1,00", "Caf")).getBytes(StandardCharsets.UTF_8));
        bytes.write(0xE9); // Latijn-1 "é" in een UTF-8-bestand: stil vervangen door U+FFFD, geen fout
        bytes.write(("\n" + row("R2", "2,00", "Thee") + "\n").getBytes(StandardCharsets.UTF_8));
        byte[] csv = bytes.toByteArray();

        String trial = trialOk(chain, csv, true);
        assertParity(trial, upload(chain, csv));

        assertThat(string(trial, "$.verdict.result")).isEqualTo("NO_BLOCKER_FOUND");
        assertThat(number(trial, "$.counters.issueOccurrencesBySeverity.WARNING")).isEqualTo(1L);
        assertThat(string(trial, "$.issueGroups[0].code")).isEqualTo("SOURCE_BOM_REMOVED");
        assertThat(number(trial, "$.counters.linesWithReplacementCharacter")).isEqualTo(1L);
        assertThat(string(trial, "$.sampleRows[0].interpreted.description")).isEqualTo("Caf�");
        assertThat(string(trial, "$.header.foundColumns[0]")).isEqualTo("leverancier");
    }

    // --- Idempotentie en geen neveneffecten (par. 6) ------------------------------------------------------------

    @Test
    void twoTrialsOfTheSameFileAreByteEqualAndNothingIsStoredOrArchived() throws Exception {
        Chain chain = chain("IDM", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "abc", "b"), row("R1", "3,00", "c"));
        List<Long> tablesBefore = sideEffectCounts();
        long archivedBefore = archivedFileCount();

        String first = trialOk(chain, csv, true);
        String second = trialOk(chain, csv, true);

        assertThat(second).isEqualTo(first);
        assertThat(sideEffectCounts()).isEqualTo(tablesBefore);
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
        assertThat(jdbc.queryForObject("select status from import_definition_revision where id = ?", String.class,
                chain.revisionId())).isEqualTo("ACTIVE");
    }

    // --- Revisiestatus, rechten en foutcodes (par. 1, 3 en 5) -----------------------------------------------------

    @Test
    void aDraftRevisionCanBeTriedWithoutALink() throws Exception {
        Chain chain = chain("DRF", revisionJson("25", "null"), null, false, revisionId -> { });
        byte[] csv = csv(row("R1", "1,00", "a"));

        String trial = trialOk(chain, csv, false);

        assertThat(string(trial, "$.revisionStatus")).isEqualTo("DRAFT");
        assertThat(number(trial, "$.revisionNumber")).isEqualTo(1L);
        assertThat(string(trial, "$.verdict.result")).isEqualTo("NO_BLOCKER_FOUND");
        assertThat(number(trial, "$.counters.validRecordCount")).isEqualTo(1L);
    }

    @Test
    void readOnlyIsForbiddenAndNothingIsRead() throws Exception {
        Chain chain = chain("RO", revisionJson("25", "null"), null, true, revisionId -> { });
        List<Long> before = sideEffectCounts();

        this.mockMvc.perform(trialRequest(chain.revisionId(), csv(row("R1", "1,00", "a")), chain.linkId())
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        assertThat(sideEffectCounts()).isEqualTo(before);
    }

    @Test
    void theErrorCodesComeInTheOrderOfTheContract() throws Exception {
        Chain chain = chain("ERR", revisionJson("25", "null"), null, true, revisionId -> { });
        Chain other = chain("ERO", revisionJson("25", "null"), null, true, revisionId -> { });
        long unknown = 999_999_999L;
        byte[] csv = csv(row("R1", "1,00", "a"));

        // Zonder bestand: FILE_REQUIRED, ook vóór een onbekende revisie.
        this.mockMvc.perform(multipart(API + "/revisions/{id}/trial-reads", unknown)
                        .param("linkId", String.valueOf(unknown)).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_REQUIRED"));
        // Onbekende revisie vóór een onbekende koppeling.
        this.mockMvc.perform(trialRequest(unknown, csv, unknown).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        this.mockMvc.perform(trialRequest(chain.revisionId(), csv, unknown).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
        this.mockMvc.perform(trialRequest(chain.revisionId(), csv, other.linkId()).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_NOT_OF_REVISION_DEFINITION"));
        // Geen multipart: 415 van Spring.
        this.mockMvc.perform(post(API + "/revisions/{id}/trial-reads", chain.revisionId())
                        .with(as(USER, Permission.MANAGE)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void exactlyOneInfoLineIsLoggedWithoutFileNameOrContent(CapturedOutput output) throws Exception {
        Chain chain = chain("LOG", revisionJson("25", "null"), null, true, revisionId -> { });
        byte[] csv = csv(row("GEHEIMREF", "abc", "Geheime omschrijving"));
        int before = count(output.getOut(), "Trial read:");

        this.mockMvc.perform(multipart(API + "/revisions/{id}/trial-reads", chain.revisionId())
                        .file(new MockMultipartFile("file", "geheim-bestand.csv", "text/csv", csv))
                        .param("linkId", String.valueOf(chain.linkId()))
                        .with(as(USER, Permission.MANAGE)))
                .andExpect(status().isOk());

        String out = output.getOut();
        assertThat(count(out, "Trial read:") - before).isEqualTo(1);
        String line = out.lines().filter(text -> text.contains("Trial read:")).reduce((a, b) -> b).orElseThrow();
        assertThat(line).contains("revisionId=" + chain.revisionId(), "linkId=" + chain.linkId(),
                "byteSize=" + csv.length, "raw=1", "verdict=");
        assertThat(out).doesNotContain("geheim-bestand.csv", "GEHEIMREF", "Geheime omschrijving");
    }

    // --- Pariteit -------------------------------------------------------------------------------------------------

    /**
     * De proef tegenover de echte screening van hetzelfde bestand: oordeel, tellers, gegroepeerde issuegroepen en
     * CRITICAL/WARNING, plus de reconciliatie {@code raw = valid + rejected + errorBeforeFilter + filteredOut}.
     */
    private void assertParity(String trial, long batchId) {
        Map<String, Object> batch = jdbc.queryForMap("select status, blocked_code, raw_record_count, "
                + "valid_record_count, rejected_record_count, filtered_out_count, error_before_filter_count, "
                + "critical_line_count, duplicate_identity_count, critical_issue_count, warning_count "
                + "from import_batch where id = ?", batchId);
        if ("WOULD_BLOCK".equals(string(trial, "$.verdict.result"))) {
            String code = string(trial, "$.verdict.blockedCode");
            assertThat(batch.get("status")).isEqualTo("BLOCKED");
            assertThat(batch.get("blocked_code")).isEqualTo(code.length() <= 60 ? code : code.substring(0, 60));
        } else {
            assertThat(batch.get("status")).isEqualTo("SCREENED");
            assertThat(string(trial, "$.verdict.blockedCode")).isNull();
        }
        String[][] counters = {{"rawRecordCount", "raw_record_count"}, {"validRecordCount", "valid_record_count"},
                {"rejectedRecordCount", "rejected_record_count"}, {"filteredOutCount", "filtered_out_count"},
                {"errorBeforeFilterCount", "error_before_filter_count"}, {"criticalLineCount", "critical_line_count"}};
        for (String[] counter : counters) {
            assertThat(number(trial, "$.counters." + counter[0])).as(counter[0])
                    .isEqualTo(asLong(batch.get(counter[1])));
        }
        Long screeningDuplicates = asLong(batch.get("duplicate_identity_count"));
        Long trialDuplicates = number(trial, "$.counters.duplicateIdentityCount");
        if (screeningDuplicates != null) {
            assertThat(trialDuplicates).as("duplicateIdentityCount").isEqualTo(screeningDuplicates);
        } else {
            // Zie de klassejavadoc: de screening schrijft de kolom niet op dit pad; de proef toont null of de meting 0.
            assertThat(trialDuplicates == null || trialDuplicates == 0L).as("duplicateIdentityCount").isTrue();
        }
        if (read(trial, "$.counters.issueOccurrencesBySeverity") != null) {
            assertThat(number(trial, "$.counters.issueOccurrencesBySeverity.CRITICAL")).as("CRITICAL")
                    .isEqualTo(asLong(batch.get("critical_issue_count")));
            assertThat(number(trial, "$.counters.issueOccurrencesBySeverity.WARNING")).as("WARNING")
                    .isEqualTo(asLong(batch.get("warning_count")));
        }
        List<String> screeningGroups = jdbc.query("select issue_code, signature, occurrence_count, is_bulk_incident "
                        + "from import_issue_group where batch_id = ? and occurrence_count >= 10",
                (resultSet, index) -> resultSet.getString(1) + "|" + resultSet.getString(2) + "|"
                        + resultSet.getLong(3) + "|" + resultSet.getBoolean(4), batchId);
        List<Map<String, Object>> trialGroups = read(trial, "$.issueGroups[?(@.grouped == true)]");
        assertThat(trialGroups.stream().map(group -> group.get("code") + "|FIELD="
                + (group.get("fieldName") == null ? "-" : group.get("fieldName")) + "|"
                + ((Number) group.get("occurrenceCount")).longValue() + "|" + group.get("bulkIncident")).toList())
                .as("grouped issue groups").containsExactlyInAnyOrderElementsOf(screeningGroups);
        Long raw = number(trial, "$.counters.rawRecordCount");
        if (raw != null) {
            assertThat(raw).as("reconciliation").isEqualTo(number(trial, "$.counters.validRecordCount")
                    + number(trial, "$.counters.rejectedRecordCount")
                    + number(trial, "$.counters.errorBeforeFilterCount")
                    + number(trial, "$.counters.filteredOutCount"));
        }
    }

    // --- Keten via de setup-API ---------------------------------------------------------------------------------

    private record Chain(String code, long definitionId, long revisionId, long linkId, long taskId) {
    }

    @FunctionalInterface
    private interface RevisionSetup {
        void apply(long revisionId) throws Exception;
    }

    private Chain chain(String prefix, String revisionJson, String linkCurrency, boolean activate,
                        RevisionSetup configure) throws Exception {
        String unique = unique(prefix);
        setupJson(post(SETUP + "/source-organisations"),
                "{\"code\":\"" + unique + "\",\"name\":\"" + unique + " BV\",\"type\":\"SUPPLIER\"}")
                .andExpect(status().isCreated());
        long definitionId = id(setupJson(post(SETUP + "/definitions"), "{\"sourceOrganisationCode\":\"" + unique
                + "\",\"code\":\"" + unique + "-DEF\",\"name\":\"" + unique
                + " catalogus\",\"usageType\":\"OWN_DEFINITION\"}").andExpect(status().isCreated()));
        long revisionId = id(setupJson(post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson)
                .andExpect(status().isCreated()));
        configure.apply(revisionId);
        if (activate) {
            setupJson(post(SETUP + "/revisions/{id}/activate", revisionId), "{\"approvedBy\":\"" + USER + "\"}")
                    .andExpect(status().isOk());
        }
        long linkId = id(setupJson(post(SETUP + "/links"), "{\"definitionId\":" + definitionId + ",\"code\":\""
                + unique + "-LINK\",\"name\":\"" + unique + " koppeling\",\"supplierCode\":\"" + unique
                + "\",\"libraryCode\":\"" + library(unique) + "\""
                + (linkCurrency == null ? "" : ",\"defaultCurrency\":\"" + linkCurrency + "\"") + "}")
                .andExpect(status().isCreated()));
        long taskId = id(setupJson(post(SETUP + "/tasks"), "{\"linkId\":" + linkId + ",\"name\":\"" + unique
                + " levering\"}").andExpect(status().isCreated()));
        return new Chain(unique, definitionId, revisionId, linkId, taskId);
    }

    private ResultActions setupJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                    String body) throws Exception {
        return this.mockMvc.perform(request.with(as(USER)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Puntkomma, header, driedelige identiteit, geen muntveld (zodat de vaste valuta van de koppeling kan gelden). */
    private static String revisionJson(String maxCriticalSharePercent, String maxRejectedSharePercent) {
        return """
                {"delimiter":";","quoteChar":"\\"","charset":"UTF-8","hasHeader":true,
                 "headerLineNumber":1,"fieldReferenceKind":"HEADER_NAME",
                 "identityProfileKind":"THREE_PART","supplierField":"leverancier",
                 "supplierGroupField":"groep","supplierReferenceField":"referentie",
                 "discountCodeField":null,"basePriceField":"prijs","descriptionField":"omschrijving",
                 "currencyField":null,"canonicalisationVersion":2,"creationThresholdSharePercent":10,
                 "maxCriticalSharePercent":%s,"maxRejectedSharePercent":%s}"""
                .formatted(maxCriticalSharePercent, maxRejectedSharePercent);
    }

    // --- Verzoeken ------------------------------------------------------------------------------------------------

    private String trialOk(Chain chain, byte[] csv, boolean withLink) throws Exception {
        return this.mockMvc.perform(trialRequest(chain.revisionId(), csv, withLink ? chain.linkId() : null)
                        .with(as(USER, Permission.MANAGE)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static MockMultipartHttpServletRequestBuilder trialRequest(long revisionId, byte[] csv, Long linkId) {
        MockMultipartHttpServletRequestBuilder request = multipart(API + "/revisions/{id}/trial-reads", revisionId)
                .file(new MockMultipartFile("file", "proef.csv", "text/csv", csv));
        if (linkId != null) {
            request.param("linkId", String.valueOf(linkId));
        }
        return request;
    }

    private long upload(Chain chain, byte[] csv) throws Exception {
        String body = this.mockMvc.perform(multipart(API + "/tasks/{id}/deliveries", chain.taskId())
                        .file(new MockMultipartFile("file", "proef.csv", "text/csv", csv))
                        .param("deliveryReference", unique("REF"))
                        .with(as(USER)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.batchId")).longValue();
    }

    // --- Hulpjes --------------------------------------------------------------------------------------------------

    private static byte[] csv(String... rows) {
        return (HEADER + "\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String row(String reference, String price, String description) {
        return "ACME;G1;" + reference + ";" + price + ";" + description;
    }

    private List<Long> sideEffectCounts() {
        List<Long> counts = new ArrayList<>();
        for (String table : SIDE_EFFECT_TABLES) {
            counts.add(jdbc.queryForObject("select count(*) from " + table, Long.class));
        }
        return counts;
    }

    private static long archivedFileCount() throws IOException {
        try (Stream<Path> files = Files.walk(archiveRoot)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }

    @SuppressWarnings("unchecked")
    private <T> T read(String json, String path) {
        return (T) JsonPath.read(json, path);
    }

    private String string(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? null : value.toString();
    }

    private Long number(String json, String path) {
        return asLong(JsonPath.read(json, path));
    }

    private static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static String unique(String prefix) {
        return "TR" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + prefix;
    }

    /** {@code import_link.library_code} is varchar(20). */
    private static String library(String unique) {
        return unique.length() <= 20 ? unique : unique.substring(0, 20);
    }
}
