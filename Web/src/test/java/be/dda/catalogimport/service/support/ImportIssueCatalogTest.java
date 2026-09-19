package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.RowIssueDao.IssueRow;
import be.dda.catalogimport.domain.ControlLevel;
import be.dda.catalogimport.domain.ImpactScope;
import be.dda.catalogimport.domain.IssueDomain;
import be.dda.catalogimport.domain.IssueHandlingStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.service.DeliveryScreeningService;
import be.dda.catalogimport.service.ScreeningRecoveryService;
import be.dda.catalogimport.service.support.ImportIssueCatalog.IssueClassification;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Fase 3a (ontwerp fase 3, R-ISS-06): bewijst dat élke foutcode die de screening gebruikt in
 * {@link ImportIssueCatalog} geclassificeerd is, en dat elke catalogusrij volledig is.
 * <p>
 * Dat wordt op twee manieren gecontroleerd, want één manier is te makkelijk te omzeilen:
 * <ol>
 *   <li><b>Reflectie</b> op de codeconstanten ({@code CODE_*} en de constanten van de catalogus zelf)
 *       van de klassen die problemen vaststellen;</li>
 *   <li><b>een scan van de broncode</b> van diezelfde klassen naar letterlijke
 *       {@code SCHREEUWCODE}-strings, zodat een code die rechtstreeks in een constructor getypt wordt
 *       - dus zonder constante - er evengoed uit valt.</li>
 * </ol>
 * Codes die géén issue zijn (HTTP-/conflictcodes en vaste redenen) staan in
 * {@link #NOT_ISSUE_CODES}: die belanden nooit in {@code import_row_issue} en horen dus niet in de
 * catalogus. Die lijst moet expliciet blijven — een nieuwe code erbij zetten is een bewuste daad.
 * <p>
 * Geen Spring-context nodig: de catalogus is een pure Java-bron van waarheid.
 */
class ImportIssueCatalogTest {

    /** De klassen die foutcodes vaststellen; elke code daaruit moet geclassificeerd zijn. */
    private static final List<Class<?>> CODE_HOLDERS = List.of(
            ImportIssueCatalog.class,
            SourceStructureConfigFactory.class,
            CsvRecordStreamer.class,
            CandidateNormaliser.class,
            ImportValueRules.class,
            // Bouwstap 3b: de definitievalidatie en de recordfilters stellen eigen codes vast.
            ImportMappingConfigFactory.class,
            RecordFilterEvaluator.class,
            DeliveryScreeningService.class,
            ScreeningRecoveryService.class);

    /**
     * Codes die bewust géén {@code import_row_issue} opleveren: HTTP-/conflictcodes uit de REST-laag
     * en de vaste volledigheidsreden op de marker (ontwerp fase 3 par. 4: "HTTP-/conflictcodes (geen
     * issue) blijven ongewijzigd").
     */
    private static final Set<String> NOT_ISSUE_CODES = Set.of(
            "BATCH_NOT_FOUND",
            "BATCH_NOT_SCREENABLE",
            "BATCH_NOT_RESUMABLE",
            "DELIVERY_FILE_COUNT_UNSUPPORTED",
            "DELIVERY_ALREADY_SCREENED_WITH_THIS_REVISION",
            "PHASE2_NO_COMPLETENESS_CONTRACT");

    /** Een letterlijke foutcode in de broncode: minstens twee woorden in hoofdletters met underscores. */
    private static final Pattern SHOUTED_LITERAL = Pattern.compile("\"([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\"");

    @Test
    void everyCodeConstantOfTheScreeningIsClassified() {
        List<String> missing = new ArrayList<>();
        for (Class<?> holder : CODE_HOLDERS) {
            for (String code : constantCodes(holder)) {
                if (!NOT_ISSUE_CODES.contains(code) && ImportIssueCatalog.find(code).isEmpty()) {
                    missing.add(holder.getSimpleName() + " -> " + code);
                }
            }
        }
        assertThat(missing)
                .as("every issue code constant must be classified in ImportIssueCatalog")
                .isEmpty();
    }

    @Test
    void everyShoutedCodeLiteralInTheScreeningSourcesIsClassified() {
        List<String> scanned = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Class<?> holder : CODE_HOLDERS) {
            String source = readSource(holder);
            Matcher matcher = SHOUTED_LITERAL.matcher(source);
            while (matcher.find()) {
                String code = matcher.group(1);
                scanned.add(code);
                if (!NOT_ISSUE_CODES.contains(code) && ImportIssueCatalog.find(code).isEmpty()) {
                    missing.add(holder.getSimpleName() + " -> " + code);
                }
            }
        }
        // Zonder deze ondergrens zou een mislukte scan (verkeerd pad, gewijzigde regex) stilzwijgend
        // slagen omdat ze niets vindt.
        assertThat(scanned).as("source scan found no codes at all; the scan itself is broken")
                .hasSizeGreaterThan(20);
        assertThat(missing).as("every shouted code literal must be classified or listed as a non-issue code")
                .isEmpty();
    }

    @Test
    void everyCatalogueEntryIsComplete() {
        assertThat(ImportIssueCatalog.all()).isNotEmpty();
        ImportIssueCatalog.all().forEach((code, classification) -> {
            assertThat(code).as("code").isNotBlank();
            assertThat(classification.code()).as("%s code", code).isEqualTo(code);
            assertThat(classification.severity()).as("%s severity", code).isNotNull();
            assertThat(classification.domain()).as("%s domain", code).isNotNull();
            assertThat(classification.controlLevel()).as("%s controlLevel", code).isNotNull();
            assertThat(classification.defaultImpactScope()).as("%s impactScope", code).isNotNull();
            // Fase 3 laat niets stilzwijgend passeren: aanvaarden is een handeling, geen eigenschap.
            assertThat(classification.acceptable()).as("%s acceptable", code).isFalse();
        });
    }

    /**
     * De hiërarchie moet in de classificatie zelf kloppen: een probleem dat de hele levering raakt
     * is nooit een recordprobleem, en een recordprobleem trekt nooit de hele levering onderuit.
     */
    @Test
    void severityAndControlLevelAgreeWithTheImpactScope() {
        ImportIssueCatalog.all().forEach((code, classification) -> {
            if (classification.controlLevel() == ControlLevel.RECORD) {
                assertThat(classification.defaultImpactScope()).as("%s", code).isEqualTo(ImpactScope.RECORD);
                assertThat(classification.severity().isBlockingForBatch()).as("%s", code).isFalse();
            } else {
                assertThat(classification.defaultImpactScope()).as("%s", code)
                        .isIn(ImpactScope.DELIVERY, ImpactScope.DEFINITION, ImpactScope.LIBRARY);
            }
        });
    }

    @Test
    void classifiesADynamicCodeOnItsPrefix() {
        IssueClassification classification =
                ImportIssueCatalog.classify(CsvRecordStreamer.CODE_HEADER_FIELD_MISSING + ":PRIJS");

        assertThat(classification.severity()).isEqualTo(RowIssueSeverity.BLOCKING);
        assertThat(classification.domain()).isEqualTo(IssueDomain.STRUCTURE_DATASET);
        assertThat(classification.controlLevel()).isEqualTo(ControlLevel.STRUCTURE);
        assertThat(classification.defaultImpactScope()).isEqualTo(ImpactScope.DELIVERY);
    }

    @Test
    void refusesToRecordAnIssueWhoseCodeIsNotInTheCatalogue() {
        assertThatThrownBy(() -> ImportIssueCatalog.classify("NOT_A_KNOWN_CODE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_A_KNOWN_CODE")
                .hasMessageContaining("ImportIssueCatalog");
        assertThatThrownBy(() -> ImportIssueCatalog.issue(1L, 2L, 3L, "NOT_A_KNOWN_CODE", null, null, null,
                "boom", Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThat(ImportIssueCatalog.find(null)).isEmpty();
        assertThat(ImportIssueCatalog.find("NOT_A_KNOWN_CODE")).isEmpty();
    }

    @Test
    void buildsAFullyClassifiedIssueRowWithHandlingStatusDetected() {
        Instant now = Instant.now();

        IssueRow row = ImportIssueCatalog.issue(7L, 8L, null, ImportIssueCatalog.SOURCE_NO_DATA_RECORDS,
                null, null, "1", "The delivery file contains no data records", now);

        assertThat(row.batchId()).isEqualTo(7L);
        assertThat(row.rowNumber()).isNull();
        assertThat(row.severity()).isEqualTo(RowIssueSeverity.BLOCKING);
        assertThat(row.issueDomain()).isEqualTo(IssueDomain.STRUCTURE_DATASET);
        assertThat(row.controlLevel()).isEqualTo(ControlLevel.DELIVERY);
        assertThat(row.impactScope()).isEqualTo(ImpactScope.DELIVERY);
        // Fase 3 zet enkel DETECTED (R-ISS-04); de rest van de levenscyclus komt later.
        assertThat(row.handlingStatus()).isEqualTo(IssueHandlingStatus.DETECTED);
        assertThat(row.expectedValue()).isEqualTo("1");
    }

    @Test
    void refusesAnIssueRowWithoutAClassification() {
        assertThatThrownBy(() -> new IssueRow(1L, null, null, "PRICE_UNREADABLE", null, null,
                IssueDomain.PRICE, ControlLevel.RECORD, ImpactScope.RECORD, IssueHandlingStatus.DETECTED,
                null, null, "no severity", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("severity");
    }

    private static Set<String> constantCodes(Class<?> holder) {
        Set<String> codes = new LinkedHashSet<>();
        for (Field field : holder.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())
                    || field.getType() != String.class) {
                continue;
            }
            // Constanten die geen foutcode zijn (bv. een reden of een sjabloon) vallen weg op vorm.
            try {
                field.setAccessible(true);
                String value = (String) field.get(null);
                if (value != null && SHOUTED_LITERAL.matcher("\"" + value + "\"").matches()) {
                    codes.add(value);
                }
            } catch (IllegalAccessException unreachable) {
                throw new IllegalStateException("Cannot read " + holder.getSimpleName() + "."
                        + field.getName(), unreachable);
            }
        }
        return codes;
    }

    private static String readSource(Class<?> holder) {
        // De tests draaien vanuit de Web-module; de bronbestanden staan in Service.
        Path path = Path.of("..", "Service", "src", "main", "java")
                .resolve(holder.getName().replace('.', '/') + ".java");
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Cannot find the source of " + holder.getName() + " at "
                    + path.toAbsolutePath() + "; the code scan would silently pass");
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read the source of " + holder.getName(), failure);
        }
    }
}
