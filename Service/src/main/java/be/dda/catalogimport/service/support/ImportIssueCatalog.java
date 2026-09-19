package be.dda.catalogimport.service.support;

import be.dda.catalogimport.dao.RowIssueDao.IssueRow;
import be.dda.catalogimport.domain.ControlLevel;
import be.dda.catalogimport.domain.ImpactScope;
import be.dda.catalogimport.domain.IssueDomain;
import be.dda.catalogimport.domain.IssueHandlingStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * De foutcodecatalogus: per stabiele foutcode de ernst, het domein, het controleniveau, de
 * standaard impactscope en of de fout aanvaardbaar is (ontwerp fase 3, R-ISS-06 en par. 4).
 * <p>
 * <b>Java is hier de bron van waarheid, bewust geen databasetabel.</b> De classificatie hoort bij de
 * code die de fout vaststelt: ze hoort mee met een release te wijzigen, ze mag niet per omgeving
 * verschillen en ze mag niet door een beheerder aangepast worden terwijl er nog leveringen op
 * beoordeeld zijn. Wat wél per levering historisch vastligt, is de classificatie zoals ze op het
 * moment van vaststellen gold: die wordt gedenormaliseerd op de issuerij bewaard (R-ISS-02).
 * <p>
 * <b>Elke gebruikte code staat hier.</b> {@link #classify(String)} gooit op een onbekende code: dat is
 * een programmeerfout, geen datafout. {@code ImportIssueCatalogTest} scant de codeconstanten van de
 * screening en faalt zodra er een code bijkomt die hier ontbreekt.
 * <p>
 * <b>Dynamische codes.</b> {@code HEADER_FIELD_MISSING:<veld>} draagt de veldnaam in de code zelf.
 * Zulke codes worden op hun voorvoegsel (vóór de dubbele punt) geclassificeerd.
 * <p>
 * Codes die géén issue zijn — HTTP-/conflictcodes zoals {@code BATCH_NOT_FOUND} of
 * {@code DELIVERY_ALREADY_SCREENED_WITH_THIS_REVISION} — staan hier bewust niet in: die belanden
 * nooit in {@code import_row_issue}.
 */
public final class ImportIssueCatalog {

    // --- Codes zonder eigen thuis in een van de parserklassen ---------------------------------
    // De codes die bij het lezen of normaliseren ontstaan, staan als constante bij de klasse die ze
    // gooit (CsvRecordStreamer, CandidateNormaliser, ImportValueRules, SourceStructureConfigFactory).
    // De codes hieronder horen bij de screeningorchestratie; DeliveryScreeningService en
    // ScreeningRecoveryService verwijzen naar deze constanten, zodat de waarde maar op één plek staat.

    /** Het verwachte byte-aantal uit het manifest klopt niet met het ontvangen bestand. */
    public static final String BYTE_SIZE_MISMATCH = "BYTE_SIZE_MISMATCH";
    /** Het verwachte recordaantal uit het manifest klopt niet met het gelezen bestand. */
    public static final String RECORD_COUNT_MISMATCH = "RECORD_COUNT_MISMATCH";
    /** Het bestand bevat een header maar geen enkele datalijn. */
    public static final String SOURCE_NO_DATA_RECORDS = "SOURCE_NO_DATA_RECORDS";
    /** Dezelfde aanbiedingsidentiteit komt meermaals voor in één levering; nooit "laatste wint". */
    public static final String DUPLICATE_IDENTITY_IN_DELIVERY = "DUPLICATE_IDENTITY_IN_DELIVERY";
    /** Zelfde identiteitshash, andere sleutelcomponenten: de identiteit is niet betrouwbaar. */
    public static final String IDENTITY_HASH_COLLISION = "IDENTITY_HASH_COLLISION";
    /** Technische fout tijdens de screening; batch en run eindigen op FAILED. */
    public static final String SCREENING_FAILED = "SCREENING_FAILED";
    /** De applicatie stopte terwijl deze batch gescreend werd. */
    public static final String SCREENING_INTERRUPTED = "SCREENING_INTERRUPTED";
    /**
     * Er zijn meer voorvallen van een foutcode dan er voorbeeldrijen bewaard worden
     * ({@code catalogimport.screening.max-sample-rows-per-code}). Informatief: de levering blokkeert
     * hier niet door (ontwerp fase 3, afwijking C) en de volledige aantallen per code staan in de
     * melding.
     */
    public static final String ROW_ISSUE_RECORDING_CAPPED = "ROW_ISSUE_RECORDING_CAPPED";

    /** Scheidingsteken tussen het voorvoegsel en het variabele deel van een dynamische code. */
    public static final char DYNAMIC_CODE_SEPARATOR = ':';

    /**
     * De classificatie van één foutcode.
     *
     * @param defaultImpactScope de reikwijdte die geldt tenzij de vaststellende regel het beter weet
     * @param acceptable         of deze fout aanvaard mág worden zonder de data te corrigeren; in
     *                           fase 3 staat dat overal op {@code false}: er wordt nooit iets
     *                           stilzwijgend doorgelaten
     */
    public record IssueClassification(String code, RowIssueSeverity severity, IssueDomain domain,
                                      ControlLevel controlLevel, ImpactScope defaultImpactScope,
                                      boolean acceptable) {
    }

    private static final Map<String, IssueClassification> BY_CODE = buildCatalogue();

    private ImportIssueCatalog() {
    }

    /**
     * @return de classificatie van deze code
     * @throws IllegalStateException wanneer de code niet in de catalogus staat; dat is een
     *                               programmeerfout (R-ISS-06), geen bronprobleem, en mag daarom
     *                               niet met een verzonnen classificatie verborgen worden
     */
    public static IssueClassification classify(String issueCode) {
        return find(issueCode).orElseThrow(() -> new IllegalStateException("Issue code '" + issueCode
                + "' is not present in ImportIssueCatalog; every issue code must be classified there "
                + "(severity, domain, control level, impact scope) before it can be recorded"));
    }

    /** Zoekt de classificatie, ook voor een dynamische code als {@code HEADER_FIELD_MISSING:PRIJS}. */
    public static Optional<IssueClassification> find(String issueCode) {
        if (issueCode == null) {
            return Optional.empty();
        }
        IssueClassification exact = BY_CODE.get(issueCode);
        if (exact != null) {
            return Optional.of(exact);
        }
        int separator = issueCode.indexOf(DYNAMIC_CODE_SEPARATOR);
        if (separator <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(issueCode.substring(0, separator)));
    }

    /** De volledige catalogus, op code; onveranderlijk. */
    public static Map<String, IssueClassification> all() {
        return BY_CODE;
    }

    /**
     * De gemeenschappelijke schrijfroute: bouwt een {@link IssueRow} waarvan ernst, domein, niveau
     * en impactscope gegarandeerd uit deze catalogus komen. Elke nieuwe issue loopt hierlangs, zodat
     * er geen enkel pad bestaat dat een zelfbedachte ernst wegschrijft.
     *
     * @param rowNumber     {@code null} bij een probleem op leverings- of structuurniveau
     * @param expectedValue wat er verwacht werd; wordt getoond, nooit toegepast
     * @throws IllegalStateException bij een code die niet in de catalogus staat
     */
    public static IssueRow issue(long batchId, Long deliveryFileId, Long rowNumber, String issueCode,
                                 String fieldName, String sourceValue, String expectedValue,
                                 String message, Instant createdAt) {
        IssueClassification classification = classify(issueCode);
        return new IssueRow(batchId, deliveryFileId, rowNumber, issueCode, fieldName,
                classification.severity(), classification.domain(), classification.controlLevel(),
                classification.defaultImpactScope(), IssueHandlingStatus.DETECTED, sourceValue,
                expectedValue, message, createdAt);
    }

    private static Map<String, IssueClassification> buildCatalogue() {
        Map<String, IssueClassification> catalogue = new LinkedHashMap<>();

        // --- Niveau 2: configuratie van de revisie (blokkeert vóór er één byte gelezen is) -------
        for (String code : new String[] {
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
                CandidateNormaliser.CODE_CONFIG_FIELD_NOT_RESOLVED}) {
            put(catalogue, code, RowIssueSeverity.BLOCKING, IssueDomain.AUTHORISATION_CONFIG,
                    ControlLevel.STRUCTURE, ImpactScope.DELIVERY);
        }

        // --- Niveau 2: het bestands-/datasetcontract --------------------------------------------
        for (String code : new String[] {
                CsvRecordStreamer.CODE_COLUMN_INDEX_OUT_OF_RANGE,
                CsvRecordStreamer.CODE_HEADER_LINE_MISSING,
                CsvRecordStreamer.CODE_HEADER_FIELD_MISSING,
                CsvRecordStreamer.CODE_HEADER_DUPLICATE_FIELD,
                CsvRecordStreamer.CODE_HEADER_COLUMN_COUNT_MISMATCH}) {
            put(catalogue, code, RowIssueSeverity.BLOCKING, IssueDomain.STRUCTURE_DATASET,
                    ControlLevel.STRUCTURE, ImpactScope.DELIVERY);
        }
        put(catalogue, CsvRecordStreamer.CODE_SOURCE_BOM_REMOVED, RowIssueSeverity.WARNING,
                IssueDomain.STRUCTURE_DATASET, ControlLevel.STRUCTURE, ImpactScope.DELIVERY);

        // --- Niveau 1: de levering als geheel ---------------------------------------------------
        put(catalogue, CsvRecordStreamer.CODE_SOURCE_FILE_EMPTY, RowIssueSeverity.BLOCKING,
                IssueDomain.STRUCTURE_DATASET, ControlLevel.DELIVERY, ImpactScope.DELIVERY);
        put(catalogue, SOURCE_NO_DATA_RECORDS, RowIssueSeverity.BLOCKING,
                IssueDomain.STRUCTURE_DATASET, ControlLevel.DELIVERY, ImpactScope.DELIVERY);
        put(catalogue, BYTE_SIZE_MISMATCH, RowIssueSeverity.BLOCKING, IssueDomain.DELIVERY_SOURCE,
                ControlLevel.DELIVERY, ImpactScope.DELIVERY);
        put(catalogue, RECORD_COUNT_MISMATCH, RowIssueSeverity.BLOCKING, IssueDomain.DELIVERY_SOURCE,
                ControlLevel.DELIVERY, ImpactScope.DELIVERY);
        put(catalogue, DUPLICATE_IDENTITY_IN_DELIVERY, RowIssueSeverity.BLOCKING,
                IssueDomain.IDENTITY_REFERENCE, ControlLevel.DELIVERY, ImpactScope.DELIVERY);
        put(catalogue, IDENTITY_HASH_COLLISION, RowIssueSeverity.BLOCKING,
                IssueDomain.IDENTITY_REFERENCE, ControlLevel.DELIVERY, ImpactScope.DELIVERY);
        put(catalogue, SCREENING_FAILED, RowIssueSeverity.BLOCKING, IssueDomain.PUBLICATION_TECHNICAL,
                ControlLevel.DELIVERY, ImpactScope.DELIVERY);
        put(catalogue, SCREENING_INTERRUPTED, RowIssueSeverity.BLOCKING,
                IssueDomain.PUBLICATION_TECHNICAL, ControlLevel.DELIVERY, ImpactScope.DELIVERY);
        put(catalogue, ROW_ISSUE_RECORDING_CAPPED, RowIssueSeverity.INFO,
                IssueDomain.PUBLICATION_TECHNICAL, ControlLevel.DELIVERY, ImpactScope.DELIVERY);

        // --- Niveau 3: één bronregel ------------------------------------------------------------
        for (String code : new String[] {
                CsvRecordStreamer.CODE_ROW_COLUMN_COUNT_MISMATCH,
                CsvRecordStreamer.CODE_ROW_TOO_LONG,
                ImportValueRules.CODE_CSV_UNCLOSED_QUOTE}) {
            put(catalogue, code, RowIssueSeverity.ERROR, IssueDomain.STRUCTURE_DATASET,
                    ControlLevel.RECORD, ImpactScope.RECORD);
        }
        for (String code : new String[] {
                ImportValueRules.CODE_VALUE_MISSING,
                ImportValueRules.CODE_CANONICAL_CONTROL_CHARACTER,
                CandidateNormaliser.CODE_VALUE_TOO_LONG}) {
            put(catalogue, code, RowIssueSeverity.ERROR, IssueDomain.MAPPING_VALIDATION,
                    ControlLevel.RECORD, ImpactScope.RECORD);
        }
        put(catalogue, CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY, RowIssueSeverity.ERROR,
                IssueDomain.IDENTITY_REFERENCE, ControlLevel.RECORD, ImpactScope.RECORD);
        for (String code : new String[] {
                ImportValueRules.CODE_PRICE_MISSING,
                ImportValueRules.CODE_PRICE_UNREADABLE,
                ImportValueRules.CODE_PRICE_SCALE_EXCEEDED,
                CandidateNormaliser.CODE_PRICE_OUT_OF_RANGE}) {
            put(catalogue, code, RowIssueSeverity.ERROR, IssueDomain.PRICE, ControlLevel.RECORD,
                    ImpactScope.RECORD);
        }

        return Collections.unmodifiableMap(catalogue);
    }

    private static void put(Map<String, IssueClassification> catalogue, String code,
                            RowIssueSeverity severity, IssueDomain domain, ControlLevel controlLevel,
                            ImpactScope defaultImpactScope) {
        // Fase 3 kent geen enkele aanvaardbare fout: aanvaarden is een expliciete handeling met
        // een behandelstatus, geen eigenschap van de foutcode.
        IssueClassification classification =
                new IssueClassification(code, severity, domain, controlLevel, defaultImpactScope, false);
        if (catalogue.putIfAbsent(code, classification) != null) {
            throw new IllegalStateException("Issue code '" + code + "' is declared twice in ImportIssueCatalog");
        }
    }
}
