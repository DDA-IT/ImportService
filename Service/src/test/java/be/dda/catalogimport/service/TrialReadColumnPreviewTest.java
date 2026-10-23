package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.service.DeliveryScreeningService.LoadedConfiguration;
import be.dda.catalogimport.service.TrialReadService.SampleStatus;
import be.dda.catalogimport.service.TrialReadService.Stage;
import be.dda.catalogimport.service.TrialReadService.TrialReadResult;
import be.dda.catalogimport.service.TrialReadService.VerdictResult;
import be.dda.catalogimport.service.support.ColumnPreview;
import be.dda.catalogimport.service.support.ColumnPreview.Column;
import be.dda.catalogimport.service.support.ColumnPreview.Row;
import be.dda.catalogimport.service.support.ColumnPreview.Status;
import be.dda.catalogimport.service.support.ImportMappingConfig;
import be.dda.catalogimport.service.support.ImportMappingConfigFactory;
import be.dda.catalogimport.service.support.ScreeningBlockedException;
import be.dda.catalogimport.service.support.SourceStructureConfig;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import be.dda.catalogimport.service.support.SourceStructureConfigFactory;
import be.dda.catalogimport.service.testsupport.NoOpTransactionManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * EB5/G3: het kolomvoorbeeld {@code columnPreview} van de proefinlezing (beslissingslog 2026-10-23 "Editor-API-gaten",
 * {@code docs/design/proefinlezing-design.md} par. 2 en 3).
 * <ul>
 *   <li><b>Regel:</b> de bewerker ziet de kolommen en de eerste datalijnen van het bestand, ook wanneer de levering op
 *       de configuratie of de header zou blokkeren. <b>Implementatie:</b> een byte-tap tussen hash en streamer; het
 *       leeglezen loopt erdoor; splitsen met de leesindeling van {@code SourceStructureConfigFactory.layoutOf}.</li>
 *   <li><b>Regel:</b> niets wordt gecorrigeerd of geraden: een afgebroken regel valt weg, een te lange of niet te parsen
 *       regel is {@code unreadable}, een cel wordt enkel gekapt.</li>
 *   <li><b>Regel:</b> het voorbeeld verandert niets aan het oordeel: {@code sampleRows}, tellers, hash en grootte zijn
 *       dezelfde, ongeacht de voorbeeldgrenzen.</li>
 * </ul>
 * Unit-test op {@link TrialReadService} met de echte configuratiefabrieken (geen mappings of filters); de revisie komt
 * uit een gemockte repository.
 */
class TrialReadColumnPreviewTest {

    private static final String HEADER = "leverancier;groep;referentie;prijs;omschrijving";
    private static final SourceStructureConfigFactory STRUCTURE = new SourceStructureConfigFactory();
    private static final ImportMappingConfigFactory MAPPING = new ImportMappingConfigFactory(null, null);
    private static final long REVISION_ID = 42L;

    private ImportDefinitionRevision revision;
    private ImportDefinitionRevisionRepository revisions;
    private DeliveryScreeningService screening;

    @BeforeEach
    void setUp() {
        revision = new ImportDefinitionRevision(null, 1, IdentityProfileKind.THREE_PART, "beheerder@example.test");
        ReflectionTestUtils.setField(revision, "id", REVISION_ID);
        revision.setStructureDelimiter(";");
        revision.setIdentitySupplierField("leverancier");
        revision.setIdentitySupplierGroupField("groep");
        revision.setIdentitySupplierReferenceField("referentie");
        revision.setRecordBasePriceField("prijs");
        revision.setRecordDescriptionField("omschrijving");
        revision.setRecordCanonicalisationVersion(2);

        revisions = mock(ImportDefinitionRevisionRepository.class);
        when(revisions.findById(REVISION_ID)).thenReturn(Optional.of(revision));
        screening = mock(DeliveryScreeningService.class);
        // Dezelfde twee fabrieken als DeliveryScreeningService.loadConfiguration, zonder databank.
        when(screening.loadConfiguration(any(), any())).thenAnswer(invocation -> load(invocation.getArgument(0)));
    }

    // --- Normaal pad ------------------------------------------------------------------------------------------------

    @Test
    void aValidFileShowsTheHeaderColumnsAndTheFirstDataLinesNextToAnUnchangedVerdict() {
        byte[] csv = csv(row("R1", "10,00", "Hamer"), row("R2", "2,5", "Zaag"), row("R3", "3.75", "Boor"));

        TrialReadResult result = trial(service(), csv);
        ColumnPreview preview = result.columnPreview();

        assertThat(result.verdict().result()).isEqualTo(VerdictResult.NO_BLOCKER_FOUND);
        assertThat(preview.status()).isEqualTo(Status.AVAILABLE);
        assertThat(preview.layout().charset()).isEqualTo("UTF-8");
        assertThat(preview.layout().delimiter()).isEqualTo(";");
        assertThat(preview.layout().quoteChar()).isEqualTo("\"");
        assertThat(preview.layout().hasHeader()).isTrue();
        assertThat(preview.layout().headerLineNumber()).isEqualTo(1);
        assertThat(preview.layout().referenceKind()).isEqualTo(FieldReferenceKind.HEADER_NAME);
        assertThat(preview.headerLineNumber()).isEqualTo(1);
        assertThat(preview.columns()).containsExactly(new Column(1, "leverancier"), new Column(2, "groep"),
                new Column(3, "referentie"), new Column(4, "prijs"), new Column(5, "omschrijving"));
        assertThat(preview.rows()).containsExactly(
                new Row(2, List.of("ACME", "G1", "R1", "10,00", "Hamer"), false),
                new Row(3, List.of("ACME", "G1", "R2", "2,5", "Zaag"), false),
                new Row(4, List.of("ACME", "G1", "R3", "3.75", "Boor"), false));
        assertThat(preview.columnsTruncated()).isFalse();
        assertThat(preview.rowsTruncated()).isFalse();
        // Het bestaande contract blijft: voorbeeldrijen zoals geïnterpreteerd.
        assertThat(result.sampleRows()).hasSize(3);
        assertThat(result.sampleRows().get(0).status()).isEqualTo(SampleStatus.VALID);
    }

    @Test
    void aHeaderNameIsTrimmedButADataCellIsShownAsRead() {
        byte[] csv = (" leverancier ;groep;referentie;prijs;omschrijving\nACME; G1 ;R1;1,00;a\n")
                .getBytes(StandardCharsets.UTF_8);

        ColumnPreview preview = trial(service(), csv).columnPreview();

        assertThat(preview.columns().get(0)).isEqualTo(new Column(1, "leverancier"));
        assertThat(preview.rows().get(0).cells()).containsExactly("ACME", " G1 ", "R1", "1,00", "a");
    }

    @Test
    void aQuotedCellIsSplitLikeTheStreamer() {
        byte[] csv = csv("ACME;G1;R1;1,00;\"Hamer; groot\"");

        ColumnPreview preview = trial(service(), csv).columnPreview();

        assertThat(preview.rows().get(0).cells()).containsExactly("ACME", "G1", "R1", "1,00", "Hamer; groot");
    }

    // --- Ook bij een configuratiefout of een headerblokkade ---------------------------------------------------------

    @Test
    void aConfigurationFailureStillShowsTheColumnsOfTheFile() {
        revision.setRecordBasePriceField(null);
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "2,00", "b"));

        TrialReadResult result = trial(service(), csv);

        assertThat(result.verdict().blockedCode()).isEqualTo(SourceStructureConfigFactory.CODE_PRICE_FIELD_MISSING);
        assertThat(result.verdict().stage()).isEqualTo(Stage.CONFIGURATION);
        assertThat(result.sampleRows()).isEmpty();
        assertThat(result.header()).isNull();
        assertThat(result.file().byteSize()).isEqualTo(csv.length);
        assertThat(result.file().sha256()).isEqualTo(sha256(csv));
        ColumnPreview preview = result.columnPreview();
        assertThat(preview.status()).isEqualTo(Status.AVAILABLE);
        assertThat(preview.columns()).extracting(Column::header)
                .containsExactly("leverancier", "groep", "referentie", "prijs", "omschrijving");
        assertThat(preview.rows()).extracting(Row::lineNumber).containsExactly(2L, 3L);
    }

    @Test
    void aMissingHeaderFieldBlocksTheReadingButTheColumnsAndRowsAreShown() {
        byte[] csv = ("leverancier;groep;referentie;extra\nACME;G1;R1;x\nACME;G1;R2;y\n")
                .getBytes(StandardCharsets.UTF_8);

        TrialReadResult result = trial(service(), csv);

        assertThat(result.verdict().blockedCode()).isEqualTo("HEADER_FIELD_MISSING:prijs");
        assertThat(result.verdict().stage()).isEqualTo(Stage.READING);
        assertThat(result.sampleRows()).isEmpty();
        ColumnPreview preview = result.columnPreview();
        assertThat(preview.status()).isEqualTo(Status.AVAILABLE);
        assertThat(preview.columns()).extracting(Column::header)
                .containsExactly("leverancier", "groep", "referentie", "extra");
        assertThat(preview.rows()).containsExactly(new Row(2, List.of("ACME", "G1", "R1", "x"), false),
                new Row(3, List.of("ACME", "G1", "R2", "y"), false));
        assertThat(result.file().byteSize()).isEqualTo(csv.length);
    }

    @Test
    void anInvalidCharsetIsAnInvalidLayoutWithoutColumns() {
        revision.setStructureCharset("NOPE-9");
        byte[] csv = csv(row("R1", "1,00", "a"));

        TrialReadResult result = trial(service(), csv);

        assertThat(result.verdict().blockedCode()).isEqualTo(SourceStructureConfigFactory.CODE_CHARSET_UNKNOWN);
        ColumnPreview preview = result.columnPreview();
        assertThat(preview.status()).isEqualTo(Status.LAYOUT_INVALID);
        assertThat(preview.layout().charset()).isNull();
        assertThat(preview.layout().delimiter()).isEqualTo(";");
        assertThat(preview.headerLineNumber()).isNull();
        assertThat(preview.columns()).isEmpty();
        assertThat(preview.rows()).isEmpty();
        assertThat(preview.rowsTruncated()).isFalse();
        assertThat(result.file().byteSize()).isEqualTo(csv.length);
    }

    @Test
    void anInvalidDelimiterIsAnInvalidLayoutWithoutColumns() {
        revision.setStructureDelimiter(";;");

        ColumnPreview preview = trial(service(), csv(row("R1", "1,00", "a"))).columnPreview();

        assertThat(preview.status()).isEqualTo(Status.LAYOUT_INVALID);
        assertThat(preview.layout().delimiter()).isNull();
        assertThat(preview.layout().charset()).isEqualTo("UTF-8");
        assertThat(preview.columns()).isEmpty();
    }

    // --- Verwijzingswijze en header ---------------------------------------------------------------------------------

    @Test
    void columnIndexWithAHeaderShowsTheHeaderNamesAndTheReferenceKind() {
        columnIndex();
        byte[] csv = csv(row("R1", "1,00", "a"));

        TrialReadResult result = trial(service(), csv);
        ColumnPreview preview = result.columnPreview();

        assertThat(result.verdict().result()).isEqualTo(VerdictResult.NO_BLOCKER_FOUND);
        assertThat(preview.layout().referenceKind()).isEqualTo(FieldReferenceKind.COLUMN_INDEX);
        assertThat(preview.headerLineNumber()).isEqualTo(1);
        assertThat(preview.columns()).extracting(Column::position).containsExactly(1, 2, 3, 4, 5);
        assertThat(preview.columns()).extracting(Column::header)
                .containsExactly("leverancier", "groep", "referentie", "prijs", "omschrijving");
        assertThat(preview.rows()).singleElement().extracting(Row::lineNumber).isEqualTo(2L);
    }

    @Test
    void withoutAHeaderTheColumnsArePositionsUpToTheWidestShownLine() {
        columnIndex();
        revision.setStructureHasHeader(false);
        byte[] csv = (row("R1", "1,00", "a") + "\n" + "ACME;G1;R2;2,00;b;extra\n").getBytes(StandardCharsets.UTF_8);

        ColumnPreview preview = trial(service(), csv).columnPreview();

        assertThat(preview.status()).isEqualTo(Status.AVAILABLE);
        assertThat(preview.layout().hasHeader()).isFalse();
        assertThat(preview.headerLineNumber()).isNull();
        assertThat(preview.columns()).containsExactly(new Column(1, null), new Column(2, null), new Column(3, null),
                new Column(4, null), new Column(5, null), new Column(6, null));
        assertThat(preview.rows()).extracting(Row::lineNumber).containsExactly(1L, 2L);
        assertThat(preview.rows().get(1).cells()).hasSize(6).endsWith("extra");
    }

    @Test
    void prefixLinesAreSkippedAndLineNumbersStayPhysical() {
        revision.setStructureHeaderLineNumber(3);
        byte[] csv = ("Prijslijst ACME\nGeldig vanaf 1/1\n" + HEADER + "\n" + row("R1", "1,00", "a") + "\n\n"
                + row("R2", "2,00", "b") + "\n").getBytes(StandardCharsets.UTF_8);

        TrialReadResult result = trial(service(), csv);
        ColumnPreview preview = result.columnPreview();

        assertThat(result.counters().prefixLineCount()).isEqualTo(2L);
        assertThat(preview.headerLineNumber()).isEqualTo(3);
        assertThat(preview.layout().headerLineNumber()).isEqualTo(3);
        assertThat(preview.columns()).extracting(Column::header).startsWith("leverancier");
        // De lege regel 5 wordt overgeslagen (zoals de streamer), de nummers blijven fysiek.
        assertThat(preview.rows()).extracting(Row::lineNumber).containsExactly(4L, 6L);
    }

    @Test
    void aHeaderLineAfterTheEndOfTheFileIsBeyondThePreview() {
        revision.setStructureHeaderLineNumber(5);
        byte[] csv = ("regel 1\nregel 2\n").getBytes(StandardCharsets.UTF_8);

        TrialReadResult result = trial(service(), csv);
        ColumnPreview preview = result.columnPreview();

        assertThat(result.verdict().blockedCode()).isEqualTo("HEADER_LINE_MISSING");
        assertThat(preview.status()).isEqualTo(Status.HEADER_BEYOND_PREVIEW);
        assertThat(preview.headerLineNumber()).isNull();
        assertThat(preview.columns()).isEmpty();
        assertThat(preview.rows()).isEmpty();
        assertThat(preview.rowsTruncated()).isFalse();
    }

    @Test
    void aHeaderLineBeyondTheBytePreviewIsBeyondThePreviewAndTheRestIsTruncated() {
        revision.setStructureHeaderLineNumber(3);
        byte[] csv = ("een lange inleidende regel\nnog een lange inleidende regel\n" + HEADER + "\n"
                + row("R1", "1,00", "a") + "\n").getBytes(StandardCharsets.UTF_8);

        TrialReadResult result = trial(service(10, 500, 40), csv);
        ColumnPreview preview = result.columnPreview();

        assertThat(preview.status()).isEqualTo(Status.HEADER_BEYOND_PREVIEW);
        assertThat(preview.rowsTruncated()).isTrue();
        // Het oordeel kijkt naar het hele bestand.
        assertThat(result.verdict().result()).isEqualTo(VerdictResult.NO_BLOCKER_FOUND);
    }

    @Test
    void aByteOrderMarkIsRemovedFromTheFirstColumn() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(0xEF);
        bytes.write(0xBB);
        bytes.write(0xBF);
        bytes.writeBytes(csv(row("R1", "1,00", "a")));

        ColumnPreview preview = trial(service(), bytes.toByteArray()).columnPreview();

        assertThat(preview.columns().get(0)).isEqualTo(new Column(1, "leverancier"));
    }

    @Test
    void aByteOrderMarkIsAlsoRemovedFromTheFirstDataLineWithoutHeader() {
        columnIndex();
        revision.setStructureHasHeader(false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(0xEF);
        bytes.write(0xBB);
        bytes.write(0xBF);
        bytes.writeBytes((row("R1", "1,00", "a") + "\n").getBytes(StandardCharsets.UTF_8));

        ColumnPreview preview = trial(service(), bytes.toByteArray()).columnPreview();

        assertThat(preview.rows().get(0).cells().get(0)).isEqualTo("ACME");
    }

    @Test
    void theRevisionCharsetDecodesTheCells() {
        revision.setStructureCharset("ISO-8859-1");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes((HEADER + "\n" + row("R1", "1,00", "Caf")).getBytes(StandardCharsets.ISO_8859_1));
        bytes.write(0xE9);
        bytes.write('\n');

        ColumnPreview preview = trial(service(), bytes.toByteArray()).columnPreview();

        assertThat(preview.layout().charset()).isEqualTo("ISO-8859-1");
        assertThat(preview.rows().get(0).cells().get(4)).isEqualTo("Café");
    }

    @Test
    void crlfAndLoneCarriageReturnsEndALineLikeTheStreamer() {
        byte[] csv = (HEADER + "\r\n" + row("R1", "1,00", "a") + "\r" + row("R2", "2,00", "b") + "\r\n")
                .getBytes(StandardCharsets.UTF_8);

        TrialReadResult result = trial(service(), csv);
        ColumnPreview preview = result.columnPreview();

        assertThat(preview.rows()).extracting(Row::lineNumber).containsExactly(2L, 3L);
        assertThat(preview.rows().get(1).cells()).endsWith("b");
        assertThat(result.counters().physicalLineCount()).isEqualTo(3L);
    }

    // --- Lege en onleesbare invoer ----------------------------------------------------------------------------------

    @Test
    void anEmptyFileIsFileEmpty() {
        TrialReadResult result = trial(service(), new byte[0]);
        ColumnPreview preview = result.columnPreview();

        assertThat(result.verdict().blockedCode()).isEqualTo("SOURCE_FILE_EMPTY");
        assertThat(preview.status()).isEqualTo(Status.FILE_EMPTY);
        assertThat(preview.layout().charset()).isEqualTo("UTF-8");
        assertThat(preview.columns()).isEmpty();
        assertThat(preview.rows()).isEmpty();
        assertThat(preview.rowsTruncated()).isFalse();
    }

    @Test
    void aHeaderWithoutDataLinesShowsColumnsAndNoRows() {
        ColumnPreview preview = trial(service(), (HEADER + "\n").getBytes(StandardCharsets.UTF_8)).columnPreview();

        assertThat(preview.status()).isEqualTo(Status.AVAILABLE);
        assertThat(preview.columns()).hasSize(5);
        assertThat(preview.rows()).isEmpty();
        assertThat(preview.rowsTruncated()).isFalse();
    }

    @Test
    void aLineLongerThanTheMaximumLineLengthIsUnreadableWithoutCells() {
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "2,00", "x".repeat(200)), row("R3", "3,00", "c"));

        TrialReadResult result = trial(service(20, 5, 100, 10, 500, 1024 * 1024), csv);
        ColumnPreview preview = result.columnPreview();

        assertThat(preview.rows()).extracting(Row::unreadable).containsExactly(false, true, false);
        assertThat(preview.rows().get(1).cells()).isNull();
        assertThat(preview.rows().get(1).lineNumber()).isEqualTo(3L);
        // De streamer verwerpt dezelfde regel als ROW_TOO_LONG.
        assertThat(result.sampleRows().get(1).status()).isEqualTo(SampleStatus.UNREADABLE);
    }

    @Test
    void anUnclosedQuoteIsUnreadableAndNeverHalfSplit() {
        byte[] csv = csv(row("R1", "1,00", "a"), "ACME;G1;R2;2,00;\"open");

        ColumnPreview preview = trial(service(), csv).columnPreview();

        assertThat(preview.rows().get(1)).isEqualTo(new Row(3, null, true));
    }

    @Test
    void aLineWithAnotherColumnCountIsShownAsItIs() {
        byte[] csv = csv(row("R1", "1,00", "a"), "ACME;G1;R2");

        ColumnPreview preview = trial(service(), csv).columnPreview();

        assertThat(preview.rows().get(1)).isEqualTo(new Row(3, List.of("ACME", "G1", "R2"), false));
    }

    // --- Grenzen ----------------------------------------------------------------------------------------------------

    @Test
    void onlyTheConfiguredNumberOfRowsIsShownAndTheSampleRowsAreUnchanged() {
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "2,00", "b"), row("R3", "3,00", "c"),
                row("R4", "4,00", "d"));

        TrialReadResult result = trial(service(2, 500, 1024 * 1024), csv);
        ColumnPreview preview = result.columnPreview();

        assertThat(preview.rows()).extracting(Row::lineNumber).containsExactly(2L, 3L);
        assertThat(preview.rowsTruncated()).isTrue();
        assertThat(result.sampleRows()).hasSize(4);
    }

    @Test
    void exactlyTheConfiguredNumberOfRowsIsNotTruncated() {
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "2,00", "b"));

        ColumnPreview preview = trial(service(2, 500, 1024 * 1024), csv).columnPreview();

        assertThat(preview.rows()).hasSize(2);
        assertThat(preview.rowsTruncated()).isFalse();
    }

    @Test
    void theDefaultIsTenRows() {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rows.add(row("R" + i, "1,00", "a"));
        }

        ColumnPreview preview = trial(service(0, 0, 0), csv(rows.toArray(String[]::new))).columnPreview();

        assertThat(preview.rows()).hasSize(TrialReadService.DEFAULT_PREVIEW_ROWS);
        assertThat(preview.rowsTruncated()).isTrue();
    }

    @Test
    void onlyTheConfiguredNumberOfColumnsIsShownInTheHeaderAndInEveryRow() {
        byte[] csv = csv(row("R1", "1,00", "a"));

        ColumnPreview preview = trial(service(10, 3, 1024 * 1024), csv).columnPreview();

        assertThat(preview.columns()).extracting(Column::header).containsExactly("leverancier", "groep", "referentie");
        assertThat(preview.rows().get(0).cells()).containsExactly("ACME", "G1", "R1");
        assertThat(preview.columnsTruncated()).isTrue();
    }

    @Test
    void aWiderDataLineThanTheHeaderAlsoTruncatesTheColumns() {
        byte[] csv = ("a;b\n1;2;3;4\n").getBytes(StandardCharsets.UTF_8);

        ColumnPreview preview = trial(service(10, 3, 1024 * 1024), csv).columnPreview();

        assertThat(preview.columns()).hasSize(2);
        assertThat(preview.rows().get(0).cells()).containsExactly("1", "2", "3");
        assertThat(preview.columnsTruncated()).isTrue();
    }

    @Test
    void aCellAndAHeaderNameAreCappedAtTwoHundredCharacters() {
        String longName = "k".repeat(250);
        byte[] csv = (HEADER + ";" + longName + "\n" + row("R1", "1,00", "x".repeat(300)) + ";y\n")
                .getBytes(StandardCharsets.UTF_8);

        ColumnPreview preview = trial(service(), csv).columnPreview();

        assertThat(preview.rows().get(0).cells().get(4)).hasSize(ColumnPreview.CELL_MAX_LENGTH)
                .isEqualTo("x".repeat(200));
        assertThat(preview.columns().get(5).header()).hasSize(ColumnPreview.CELL_MAX_LENGTH);
        assertThat(ColumnPreview.CELL_MAX_LENGTH).isEqualTo(TrialReadService.RAW_VALUE_MAX_LENGTH);
    }

    @Test
    void anIncompleteLastLineInTheByteBufferIsDroppedAndTheRowsAreTruncated() {
        String line2 = row("R1", "1,00", "a");
        String line3 = row("R2", "2,00", "b");
        byte[] csv = csv(line2, line3, row("R3", "3,00", "c"));
        // Tot midden in regel 3: header + regel 2 volledig, regel 3 half.
        int limit = (HEADER + "\n" + line2 + "\n").length() + 5;

        TrialReadResult cut = trial(service(10, 500, limit), csv);
        TrialReadResult whole = trial(service(), csv);

        assertThat(cut.columnPreview().rows()).containsExactly(
                new Row(2, List.of("ACME", "G1", "R1", "1,00", "a"), false));
        assertThat(cut.columnPreview().rowsTruncated()).isTrue();
        // De tap verandert niets aan het lezen: zelfde grootte, hash, tellers en voorbeeldrijen als zonder grens.
        assertThat(cut.file()).isEqualTo(whole.file());
        assertThat(cut.file().sha256()).isEqualTo(sha256(csv));
        assertThat(cut.counters()).isEqualTo(whole.counters());
        assertThat(cut.sampleRows()).isEqualTo(whole.sampleRows());
        assertThat(cut.verdict()).isEqualTo(whole.verdict());
    }

    @Test
    void aFileExactlyAsLargeAsTheByteLimitIsCompleteAndKeepsALastLineWithoutLineBreak() {
        byte[] csv = (HEADER + "\n" + row("R1", "1,00", "a") + "\n" + row("R2", "2,00", "b"))
                .getBytes(StandardCharsets.UTF_8);

        ColumnPreview preview = trial(service(10, 500, csv.length), csv).columnPreview();

        assertThat(preview.rows()).extracting(Row::lineNumber).containsExactly(2L, 3L);
        assertThat(preview.rowsTruncated()).isFalse();
    }

    @Test
    void oneByteMoreThanTheLimitDropsTheLastLine() {
        byte[] csv = (HEADER + "\n" + row("R1", "1,00", "a") + "\n" + row("R2", "2,00", "b"))
                .getBytes(StandardCharsets.UTF_8);

        ColumnPreview preview = trial(service(10, 500, csv.length - 1), csv).columnPreview();

        assertThat(preview.rows()).extracting(Row::lineNumber).containsExactly(2L);
        assertThat(preview.rowsTruncated()).isTrue();
    }

    @Test
    void theByteLimitAlsoAppliesAfterAConfigurationFailure() {
        revision.setRecordBasePriceField(null);
        String line2 = row("R1", "1,00", "a");
        byte[] csv = csv(line2, row("R2", "2,00", "b"));

        TrialReadResult result = trial(service(10, 500, (HEADER + "\n" + line2 + "\n").length() + 3), csv);

        assertThat(result.verdict().stage()).isEqualTo(Stage.CONFIGURATION);
        assertThat(result.columnPreview().rows()).extracting(Row::lineNumber).containsExactly(2L);
        assertThat(result.columnPreview().rowsTruncated()).isTrue();
        assertThat(result.file().byteSize()).isEqualTo(csv.length);
    }

    @Test
    void aMultiByteCharacterCutByTheLimitStaysInTheDroppedLine() {
        String line2 = row("R1", "1,00", "a");
        byte[] csv = csv(line2, row("R2", "2,00", "ééé"));
        int limit = (HEADER + "\n" + line2 + "\n" + "ACME;G1;R2;2,00;").getBytes(StandardCharsets.UTF_8).length + 1;

        ColumnPreview preview = trial(service(10, 500, limit), csv).columnPreview();

        assertThat(preview.rows()).extracting(Row::lineNumber).containsExactly(2L);
        assertThat(preview.rows().get(0).cells()).noneMatch(cell -> cell.indexOf('�') >= 0);
    }

    // --- Pure functie en properties ---------------------------------------------------------------------------------

    @Test
    void twoTrialsOfTheSameFileGiveAnEqualPreview() {
        byte[] csv = csv(row("R1", "1,00", "a"), row("R2", "abc", "b"));
        TrialReadService service = service();

        TrialReadResult first = trial(service, csv);
        TrialReadResult second = trial(service, csv);

        assertThat(second.columnPreview()).isEqualTo(first.columnPreview());
        assertThat(second).isEqualTo(first);
    }

    @Test
    void morePreviewRowsThanFiftyDoesNotStart() {
        assertThatThrownBy(() -> service(TrialReadService.MAX_PREVIEW_ROWS + 1, 500, 1024 * 1024))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("preview-rows");
        assertThat(service(TrialReadService.MAX_PREVIEW_ROWS, 500, 1024 * 1024)).isNotNull();
    }

    @Test
    void moreThanEightMebibytesDoesNotStart() {
        assertThatThrownBy(() -> service(10, 500, TrialReadService.MAX_PREVIEW_MAX_BYTES + 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("preview-max-bytes");
        assertThat(service(10, 500, TrialReadService.MAX_PREVIEW_MAX_BYTES)).isNotNull();
    }

    @Test
    void negativeLimitsFallBackToTheDefaults() {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            rows.add(row("R" + i, "1,00", "a"));
        }

        ColumnPreview preview = trial(service(-1, -1, -1), csv(rows.toArray(String[]::new))).columnPreview();

        assertThat(preview.rows()).hasSize(10);
        assertThat(preview.columns()).hasSize(5);
        assertThat(preview.columnsTruncated()).isFalse();
    }

    // --- Hulpjes ----------------------------------------------------------------------------------------------------

    private TrialReadService service() {
        return service(TrialReadService.DEFAULT_PREVIEW_ROWS, TrialReadService.DEFAULT_PREVIEW_MAX_COLUMNS,
                TrialReadService.DEFAULT_PREVIEW_MAX_BYTES);
    }

    private TrialReadService service(int previewRows, int previewMaxColumns, int previewMaxBytes) {
        return service(20, 5, 100_000, previewRows, previewMaxColumns, previewMaxBytes);
    }

    private TrialReadService service(int sampleRows, int maxIssueExamples, int maxLineLength, int previewRows,
                                     int previewMaxColumns, int previewMaxBytes) {
        return new TrialReadService(revisions, mock(ImportLinkRepository.class), screening,
                mock(ChainConfigurationChecks.class), STRUCTURE, new NoOpTransactionManager(), sampleRows,
                maxIssueExamples, 1_000, maxLineLength, previewRows, previewMaxColumns, previewMaxBytes);
    }

    private static TrialReadResult trial(TrialReadService service, byte[] content) {
        return service.trialRead(REVISION_ID, null, "proef.csv", new ByteArrayInputStream(content));
    }

    private static LoadedConfiguration load(ImportDefinitionRevision revision) {
        try {
            SourceStructureConfig config = STRUCTURE.from(revision, null);
            ImportMappingConfig mapping = MAPPING.from(revision, config, List.of(), List.of());
            return new LoadedConfiguration(config, mapping, null);
        } catch (ScreeningBlockedException blocked) {
            return new LoadedConfiguration(null, null, blocked);
        }
    }

    private void columnIndex() {
        revision.setStructureFieldReferenceKind("COLUMN_INDEX");
        revision.setIdentitySupplierField("1");
        revision.setIdentitySupplierGroupField("2");
        revision.setIdentitySupplierReferenceField("3");
        revision.setRecordBasePriceField("4");
        revision.setRecordDescriptionField("5");
    }

    private static byte[] csv(String... rows) {
        return (HEADER + "\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String row(String reference, String price, String description) {
        return "ACME;G1;" + reference + ";" + price + ";" + description;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
