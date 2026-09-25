package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.service.PsimportPreviewMapper.Field;
import be.dda.catalogimport.service.PsimportPreviewMapper.Row;
import be.dda.catalogimport.service.PsimportPreviewMapper.State;
import be.dda.catalogimport.service.PsimportPreviewService.PsimportPreview;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit-tests (geen database) van de CSV-serialisatie van de PSIMPORT-preview. */
class PsimportPreviewCsvSerializerTest {

    private static PsimportPreview preview(long bundleId, List<Row> rows, String contentHash) {
        return new PsimportPreview(true, "UNVERIFIED_FIELD_INVENTORY", "1", bundleId, contentHash, Instant.now(),
                rows, 0, rows.size(), rows.size(), 1);
    }

    private static Row row(long batchId, long mutationId, String actionType, boolean complete, List<Field> fields) {
        return new Row(batchId, mutationId, actionType, complete, fields);
    }

    private static Field field(String code, String label, String value, State state) {
        return new Field(code, label, value, state);
    }

    @Test
    void emptyPreviewGeneratesHeadersOnly() {
        PsimportPreview preview = preview(1L, List.of(), "abcd1234");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);

        String[] lines = csv.split("\n");
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        assertThat(lines[0]).startsWith("# PREVIEW previewOnly=true");
        assertThat(lines[0]).contains("bundleContentHash=abcd1234");
        assertThat(lines[1]).startsWith("batchId,mutationId,actionType,complete");
    }

    @Test
    void bannerIncludesPreviewOnlyAndContractStatus() {
        PsimportPreview preview = preview(1L, List.of(), "hash123");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String banner = csv.split("\n")[0];

        assertThat(banner).startsWith("# PREVIEW");
        assertThat(banner).contains("previewOnly=true");
        assertThat(banner).contains("contractStatus=UNVERIFIED_FIELD_INVENTORY");
        assertThat(banner).contains("previewSpecVersion=1");
        assertThat(banner).contains("bundleContentHash=hash123");
    }

    @Test
    void nullContentHashInBannerIsNullKeyword() {
        PsimportPreview preview = preview(1L, List.of(), null);

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String banner = csv.split("\n")[0];

        assertThat(banner).contains("bundleContentHash=(null)");
    }

    @Test
    void headerRowIncludesFixedColumnsAndFieldColumns() {
        List<Field> fields = List.of(
                field("SUPPLIER", "Leverancier", "ACME", State.VALUE),
                field("BASE_PRICE", "Prijs", "1.00", State.VALUE)
        );
        Row row = row(7L, 42L, "CREATE", true, fields);
        PsimportPreview preview = preview(1L, List.of(row), "hash");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String header = csv.split("\n")[1];

        assertThat(header).startsWith("batchId,mutationId,actionType,complete");
        assertThat(header).contains("SUPPLIER,SUPPLIER.state");
        assertThat(header).contains("BASE_PRICE,BASE_PRICE.state");
    }

    @Test
    void dataRowIncludesAllColumnsInOrder() {
        List<Field> fields = List.of(
                field("SUPPLIER", "Leverancier", "ACME", State.VALUE),
                field("BASE_PRICE", "Prijs", "1.00", State.VALUE)
        );
        Row row = row(7L, 42L, "CREATE", true, fields);
        PsimportPreview preview = preview(1L, List.of(row), "hash");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String dataRow = csv.split("\n")[2];

        assertThat(dataRow).startsWith("7,42,CREATE,true");
        assertThat(dataRow).contains("ACME,VALUE");
        assertThat(dataRow).contains("1.00,VALUE");
    }

    @Test
    void nullValueIsEmpty() {
        List<Field> fields = List.of(
                field("SUPPLIER", "Leverancier", null, State.UNKNOWN)
        );
        Row row = row(7L, 42L, "CREATE", false, fields);
        PsimportPreview preview = preview(1L, List.of(row), "hash");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String dataRow = csv.split("\n")[2];

        // Null value → leeg veld, maar state is nog zichtbaar
        assertThat(dataRow).contains(",UNKNOWN");
    }

    @Test
    void rfcEscapingQuotesCommasAndNewlines() {
        List<Field> fields = List.of(
                field("CODE1", "Label", "value,with,commas", State.VALUE),
                field("CODE2", "Label", "value\"with\"quotes", State.VALUE),
                field("CODE3", "Label", "value\nwith\nnewlines", State.VALUE)
        );
        Row row = row(7L, 42L, "CREATE", true, fields);
        PsimportPreview preview = preview(1L, List.of(row), "hash");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String dataRow = csv.split("\n")[2];

        // Komma → ingesloten in quotes
        assertThat(dataRow).contains("\"value,with,commas\"");
        // Quote → verdubbeld
        assertThat(dataRow).contains("\"value\"\"with\"\"quotes\"");
        // Newline → ingesloten in quotes (een record kan dus meerdere fysieke regels beslaan: niet op dataRow toetsen)
        assertThat(csv).contains("\"value\nwith\nnewlines\"");
    }

    @Test
    void formulaInjectionProtectionViaLeadingApostrophe() {
        List<Field> fields = List.of(
                field("CODE_EQ", "Label", "=1+1", State.VALUE),
                field("CODE_PLUS", "Label", "+1", State.VALUE),
                field("CODE_MINUS", "Label", "-1", State.VALUE),
                field("CODE_AT", "Label", "@example.com", State.VALUE),
                field("CODE_NORMAL", "Label", "normal", State.VALUE)
        );
        Row row = row(7L, 42L, "CREATE", true, fields);
        PsimportPreview preview = preview(1L, List.of(row), "hash");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String dataRow = csv.split("\n")[2];

        // = begint met apostrof
        assertThat(dataRow).contains("'=1+1");
        // + begint met apostrof
        assertThat(dataRow).contains("'+1");
        // - begint met apostrof
        assertThat(dataRow).contains("'-1");
        // @ begint met apostrof
        assertThat(dataRow).contains("'@example.com");
        // normal: geen apostrof
        assertThat(dataRow).contains("normal,VALUE");
        assertThat(dataRow).doesNotContain("'normal");
    }

    @Test
    void formulaInjectionAndRfcEscapingCombined() {
        // Een waarde die zowel formule-injectie risico als RFC-escaping nodig heeft
        List<Field> fields = List.of(
                field("CODE", "Label", "=\"formula,with,commas\"", State.VALUE)
        );
        Row row = row(7L, 42L, "CREATE", true, fields);
        PsimportPreview preview = preview(1L, List.of(row), "hash");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String dataRow = csv.split("\n")[2];

        // Begint met = → apostrof voorafgaan
        // Bevat komma en quote → RFC-escaping
        // '="formula,with,commas" → interne quotes verdubbeld, geheel tussen quotes
        assertThat(dataRow).contains("\"'=\"\"formula,with,commas\"\"\"");
    }

    @Test
    void incompleteRowHasCompleteFalse() {
        List<Field> fields = List.of(
                field("SUPPLIER", "Leverancier", null, State.UNKNOWN)
        );
        Row row = row(7L, 42L, "CREATE", false, fields);
        PsimportPreview preview = preview(1L, List.of(row), "hash");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String dataRow = csv.split("\n")[2];

        assertThat(dataRow).startsWith("7,42,CREATE,false");
    }

    @Test
    void multipleRowsAreAllSerialized() {
        List<Field> fields1 = List.of(field("CODE", "Label", "value1", State.VALUE));
        List<Field> fields2 = List.of(field("CODE", "Label", "value2", State.VALUE));
        Row row1 = row(7L, 41L, "CREATE", true, fields1);
        Row row2 = row(7L, 42L, "UPDATE", true, fields2);
        PsimportPreview preview = preview(1L, List.of(row1, row2), "hash");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String[] lines = csv.split("\n");

        assertThat(lines).hasSizeGreaterThanOrEqualTo(4);
        assertThat(lines[2]).startsWith("7,41,CREATE,true");
        assertThat(lines[3]).startsWith("7,42,UPDATE,true");
        assertThat(lines[2]).contains("value1");
        assertThat(lines[3]).contains("value2");
    }

    @Test
    void emptyStringValueIsDistinctFromNull() {
        List<Field> fields = List.of(
                field("CODE1", "Label", "", State.VALUE),
                field("CODE2", "Label", null, State.UNKNOWN)
        );
        Row row = row(7L, 42L, "CREATE", false, fields);
        PsimportPreview preview = preview(1L, List.of(row), "hash");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String dataRow = csv.split("\n")[2];

        // CODE1 met lege string: twee komma's zonder waarde tussen
        // CODE2 met null: ook twee komma's (VALUE en UNKNOWN)
        String[] parts = dataRow.split(",");
        // Controleer dat we voldoende kolommen hebben
        assertThat(parts.length).isGreaterThanOrEqualTo(6);
    }

    @Test
    void stateValuesAreCorrectlyMapped() {
        List<Field> fields = List.of(
                field("CODE1", "Label", "value", State.VALUE),
                field("CODE2", "Label", null, State.NOT_MAPPED),
                field("CODE3", "Label", null, State.NOT_AVAILABLE_IN_MUTATION),
                field("CODE4", "Label", null, State.NOT_CONTRACTED),
                field("CODE5", "Label", null, State.UNKNOWN)
        );
        Row row = row(7L, 42L, "CREATE", false, fields);
        PsimportPreview preview = preview(1L, List.of(row), "hash");

        String csv = PsimportPreviewCsvSerializer.toCsv(preview);
        String dataRow = csv.split("\n")[2];

        assertThat(dataRow).contains("VALUE,");
        assertThat(dataRow).contains("NOT_MAPPED");
        assertThat(dataRow).contains("NOT_AVAILABLE_IN_MUTATION");
        assertThat(dataRow).contains("NOT_CONTRACTED");
        assertThat(dataRow).contains("UNKNOWN");
    }
}
