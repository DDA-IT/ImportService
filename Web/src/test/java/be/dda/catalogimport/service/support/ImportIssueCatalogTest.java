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
            // Bouwstap 3c: de recordvalidatie van de gemapte doelvelden en haar transformaties.
            FieldValueMapper.class,
            FieldTransform.class,
            MappingSettings.class,
            // Bouwstap 3d: de prijsregels stellen zes eigen codes vast (R-PRI-02..R-PRI-08).
            PriceRules.class,
            // Bouwstap 3e: de afwijkingscontrole tegen de prijshistoriek (R-PRI-10..R-PRI-12).
            PriceDeviationEvaluator.class,
            // Bouwstap 3f: de normalisatie en de controle van de kritieke koppelreferenties
            // (R-REF-01..R-REF-06). Ze stellen zelf geen codes vast, maar staan hier zodat een code
            // die er later bijkomt niet aan de scan ontsnapt.
            ReferenceNormaliser.class,
            ReferenceControlEvaluator.class,
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
            "PHASE2_NO_COMPLETENESS_CONTRACT",
            // Bouwstap 3d: de componentcode van de basisprijs in import_candidate_price. Een waarde in
            // een kolom, geen foutcode - ze belandt nooit in import_row_issue.
            "BASE_PRICE");

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
            // Bouwstap 3h-5: élke code zegt expliciet wat ze met de levering doet. Er bestaat geen
            // standaard per ernst - een nieuwe code zou dan stilzwijgend "raakt de levering niet"
            // erven, precies de fout die deze bouwstap rechtzet.
            assertThat(classification.deliveryEffect()).as("%s deliveryEffect", code).isNotNull();
            // Fase 3 laat niets stilzwijgend passeren: aanvaarden is een handeling, geen eigenschap.
            assertThat(classification.acceptable()).as("%s acceptable", code).isFalse();
        });
    }

    /**
     * De hiërarchie moet in de classificatie zelf kloppen: een probleem dat de hele levering raakt is
     * nooit een recordprobleem, en een recordprobleem verklaart nooit de hele levering onbruikbaar.
     * <p>
     * <b>Eén uitzondering, en ze is bewust</b> (aangepast in bouwstap 3f): ernst {@code CRITICAL} op
     * recordniveau. Een kritieke koppelreferentie blokkeert volgens R-REF-07 "ongeacht volume": het
     * record zelf wordt vastgehouden (impactscope {@code RECORD}), maar het eindoordeel van de
     * levering wordt {@code BLOCKING}. Wat een recordprobleem dus nooit mag zijn, is
     * {@code BLOCKING}: die ernst hoort bij een contract-, structuur- of drempelfout, die per definitie
     * de hele levering betreft.
     */
    @Test
    void severityAndControlLevelAgreeWithTheImpactScope() {
        ImportIssueCatalog.all().forEach((code, classification) -> {
            if (classification.controlLevel() == ControlLevel.RECORD) {
                assertThat(classification.defaultImpactScope()).as("%s", code).isEqualTo(ImpactScope.RECORD);
                assertThat(classification.severity()).as("%s", code)
                        .isNotEqualTo(RowIssueSeverity.BLOCKING);
            } else {
                assertThat(classification.defaultImpactScope()).as("%s", code)
                        .isIn(ImpactScope.DELIVERY, ImpactScope.DEFINITION, ImpactScope.LIBRARY);
            }
        });
    }

    /**
     * Bouwstap 3h-5 (ontwerp fase 3 par. 15.3): de toewijzing van {@link DeliveryEffect} staat hier
     * <b>vastgepind</b>. Ze bepaalt {@code validation_result} en dus of een levering geblokkeerd,
     * beoordeeld of gewoon doorgelaten wordt; ze mag niet als bijvangst van een andere wijziging
     * verschuiven.
     * <p>
     * {@code REVIEW} is een korte, gesloten lijst: de zes vaststellingen die een menselijke
     * beoordeling vragen zonder de verwerking te stoppen. {@code BLOCK} zijn de codes waarmee de
     * levering als geheel onbruikbaar is — precies de blokkeerredenen van de screening.
     * {@code NONE} is al de rest, inclusief élke recordfout: of die een review vraagt, hangt van de
     * kritiek-vlag van de kolom af (beslissingslog 20/09) en niet van de foutcode.
     */
    @Test
    void pinsTheDeliveryEffectOfEveryCode() {
        assertThat(codesWithEffect(DeliveryEffect.REVIEW)).containsExactlyInAnyOrder(
                ImportIssueCatalog.IDENTITY_REFERENCE_INCIDENT,
                ImportIssueCatalog.DUPLICATE_REFERENCE_IN_DELIVERY,
                ImportIssueCatalog.BULK_IDENTITY_INCIDENT,
                ImportIssueCatalog.BULK_PRICE_INCIDENT,
                ImportIssueCatalog.BULK_CREATION_INCIDENT,
                ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL);
        assertThat(codesWithEffect(DeliveryEffect.BLOCK)).containsExactlyInAnyOrder(
                // Configuratie van de revisie: blokkeert vóór er één byte gelezen is.
                SourceStructureConfigFactory.CODE_FORMAT_UNSUPPORTED,
                SourceStructureConfigFactory.CODE_CHARSET_UNKNOWN,
                SourceStructureConfigFactory.CODE_DELIMITER_MISSING,
                SourceStructureConfigFactory.CODE_DELIMITER_INVALID,
                SourceStructureConfigFactory.CODE_QUOTE_INVALID,
                SourceStructureConfigFactory.CODE_HEADER_LINE_INVALID,
                SourceStructureConfigFactory.CODE_FIELD_REFERENCE_KIND_INVALID,
                SourceStructureConfigFactory.CODE_HEADER_REFERENCE_INCONSISTENT,
                SourceStructureConfigFactory.CODE_IDENTITY_FIELD_MISSING,
                SourceStructureConfigFactory.CODE_PRICE_FIELD_MISSING,
                SourceStructureConfigFactory.CODE_COLUMN_COUNT_INVALID,
                SourceStructureConfigFactory.CODE_FIELD_REFERENCE_INVALID,
                SourceStructureConfigFactory.CODE_CANONICALISATION_VERSION_UNSUPPORTED,
                CandidateNormaliser.CODE_CONFIG_DISCOUNT_FIELD_MISSING,
                CandidateNormaliser.CODE_CONFIG_FIELD_NOT_RESOLVED,
                ImportMappingConfigFactory.CODE_MAPPING_TARGET_UNKNOWN,
                ImportMappingConfigFactory.CODE_MAPPING_SOURCE_UNRESOLVED,
                ImportMappingConfigFactory.CODE_MAPPING_DUPLICATE_TARGET,
                ImportMappingConfigFactory.CODE_MAPPING_TYPE_INCOMPATIBLE,
                ImportMappingConfigFactory.CODE_FIELD_MAPPING_DUPLICATES_REVISION,
                ImportMappingConfigFactory.CODE_IDENTITY_CLASS_CONFLICT,
                ImportMappingConfigFactory.CODE_OWNER_NOT_CHANGEABLE,
                ImportMappingConfigFactory.CODE_PRICE_COMPONENT_DUPLICATE,
                ImportMappingConfigFactory.CODE_TRANSFORM_INVALID,
                ImportMappingConfigFactory.CODE_FILTER_INVALID,
                ImportMappingConfigFactory.CODE_CANONICALISATION_VERSION_REQUIRED,
                ImportMappingConfigFactory.CODE_FIELD_CRITICALITY_INVALID,
                ImportMappingConfigFactory.CODE_PRICE_CONTROL_MODEL_UNSUPPORTED,
                // Het bestands-/datasetcontract.
                CsvRecordStreamer.CODE_COLUMN_INDEX_OUT_OF_RANGE,
                CsvRecordStreamer.CODE_HEADER_LINE_MISSING,
                CsvRecordStreamer.CODE_HEADER_FIELD_MISSING,
                CsvRecordStreamer.CODE_HEADER_DUPLICATE_FIELD,
                CsvRecordStreamer.CODE_HEADER_COLUMN_COUNT_MISMATCH,
                CsvRecordStreamer.CODE_HEADER_FIELD_SEMANTIC_CHANGE,
                CsvRecordStreamer.CODE_SOURCE_FILE_EMPTY,
                RecordFilterEvaluator.CODE_FILTER_COLUMN_MISSING,
                // De levering als geheel.
                ImportIssueCatalog.SOURCE_NO_DATA_RECORDS,
                ImportIssueCatalog.BYTE_SIZE_MISMATCH,
                ImportIssueCatalog.RECORD_COUNT_MISMATCH,
                ImportIssueCatalog.DUPLICATE_IDENTITY_IN_DELIVERY,
                ImportIssueCatalog.IDENTITY_HASH_COLLISION,
                ImportIssueCatalog.SCREENING_FAILED,
                ImportIssueCatalog.SCREENING_INTERRUPTED,
                ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED,
                ImportIssueCatalog.REJECTED_RECORD_THRESHOLD_EXCEEDED);
        // Al het overige raakt het oordeel over de levering niet rechtstreeks.
        assertThat(codesWithEffect(DeliveryEffect.NONE))
                .contains(ImportIssueCatalog.ROW_ISSUE_RECORDING_CAPPED,
                        ImportIssueCatalog.REFERENCE_LINK_PROPOSED,
                        PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED,
                        PriceDeviationEvaluator.CODE_PRICE_REFERENCE_NOT_AVAILABLE,
                        FieldValueMapper.CODE_VALUE_DEFAULT_APPLIED,
                        RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED,
                        ImportValueRules.CODE_PRICE_UNREADABLE,
                        CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY)
                .doesNotContain(ImportIssueCatalog.BULK_PRICE_INCIDENT,
                        ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
    }

    /**
     * De ernst is met bouwstap 3h-5 <b>niet</b> gewijzigd: enkel het effect op het oordeel is een
     * eigen as geworden. Deze test houdt dat uit elkaar — een zware ernst zegt niets over het effect
     * en omgekeerd.
     */
    @Test
    void keepsSeverityAndDeliveryEffectAsTwoIndependentAxes() {
        // Zware ernst, toch enkel een beoordeling.
        assertThat(ImportIssueCatalog.classify(ImportIssueCatalog.BULK_PRICE_INCIDENT).severity())
                .isEqualTo(RowIssueSeverity.BLOCKING);
        assertThat(ImportIssueCatalog.effectOf(ImportIssueCatalog.BULK_PRICE_INCIDENT))
                .isEqualTo(DeliveryEffect.REVIEW);
        assertThat(ImportIssueCatalog.classify(ImportIssueCatalog.IDENTITY_REFERENCE_INCIDENT)
                .severity()).isEqualTo(RowIssueSeverity.CRITICAL);
        assertThat(ImportIssueCatalog.effectOf(ImportIssueCatalog.IDENTITY_REFERENCE_INCIDENT))
                .isEqualTo(DeliveryEffect.REVIEW);
        // Dezelfde ernst, wél een blokkade.
        assertThat(ImportIssueCatalog
                .classify(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED).severity())
                .isEqualTo(RowIssueSeverity.BLOCKING);
        assertThat(ImportIssueCatalog.effectOf(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED))
                .isEqualTo(DeliveryEffect.BLOCK);
        // Een dynamische code erft het effect van haar voorvoegsel.
        assertThat(ImportIssueCatalog.effectOf(CsvRecordStreamer.CODE_HEADER_FIELD_MISSING + ":PRIJS"))
                .isEqualTo(DeliveryEffect.BLOCK);
        // En een onbekende code valt nooit stil terug op "onschuldig".
        assertThatThrownBy(() -> ImportIssueCatalog.effectOf("NOT_A_KNOWN_CODE"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static List<String> codesWithEffect(DeliveryEffect effect) {
        return ImportIssueCatalog.all().values().stream()
                .filter(classification -> classification.deliveryEffect() == effect)
                .map(IssueClassification::code)
                .toList();
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

    /**
     * Bouwstap 3e: de ernst blijft van de catalogus, met één uitdrukkelijk toegelaten uitzondering.
     * Een revisie mag een prijsafwijking op {@code ERROR} zetten (R-PRI-10, {@code
     * price_deviation_severity}); elke andere ernst — en elke andere code — wordt geweigerd in plaats
     * van overgenomen.
     */
    @Test
    void takesTheSeverityFromTheRevisionOnlyWhereTheCatalogueAllowsIt() {
        Instant now = Instant.now();
        String code = PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED;

        assertThat(ImportIssueCatalog.classify(code).severity()).isEqualTo(RowIssueSeverity.WARNING);
        assertThat(ImportIssueCatalog.issue(1L, 2L, 3L, code, "Basisprijs", "10", null, "boom",
                RowIssueSeverity.ERROR, now).severity()).isEqualTo(RowIssueSeverity.ERROR);
        // null betekent "neem de catalogus", niet "verzin iets".
        assertThat(ImportIssueCatalog.issue(1L, 2L, 3L, code, "Basisprijs", "10", null, "boom",
                null, now).severity()).isEqualTo(RowIssueSeverity.WARNING);
        // Een afwijking is nooit op zichzelf een blokkade van de hele levering.
        assertThatThrownBy(() -> ImportIssueCatalog.issue(1L, 2L, 3L, code, null, null, null, "boom",
                RowIssueSeverity.BLOCKING, now)).isInstanceOf(IllegalStateException.class);
        // En een code zonder configureerbare ernst laat helemaal niets kiezen.
        assertThatThrownBy(() -> ImportIssueCatalog.issue(1L, 2L, 3L,
                ImportValueRules.CODE_PRICE_UNREADABLE, null, null, null, "boom",
                RowIssueSeverity.WARNING, now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAnIssueRowWithoutAClassification() {
        assertThatThrownBy(() -> new IssueRow(1L, null, null, "PRICE_UNREADABLE", null, null,
                IssueDomain.PRICE, ControlLevel.RECORD, ImpactScope.RECORD, IssueHandlingStatus.DETECTED,
                null, null, "no severity", null, Instant.now()))
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
