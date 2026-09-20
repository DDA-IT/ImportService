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
import java.util.Set;

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
    /**
     * Een kritieke koppelreferentie (EAN, PIM-ID, CAB-ID, {@code E_MARK+ARTICLE_REFERENCE}) is
     * gewijzigd, verwijderd, hergebruikt of dubbelzinnig geworden (R-REF-02..R-REF-05). Het soort
     * staat in {@code expected_value} en in de melding.
     */
    public static final String IDENTITY_REFERENCE_INCIDENT = "IDENTITY_REFERENCE_INCIDENT";
    /**
     * Dezelfde genormaliseerde referentiewaarde staat in deze ene levering bij twee of meer
     * verschillende aanbiedingsidentiteiten (R-REF-07). Nooit "laatste wint": élke betrokken regel
     * wordt vastgehouden.
     */
    public static final String DUPLICATE_REFERENCE_IN_DELIVERY = "DUPLICATE_REFERENCE_IN_DELIVERY";
    /**
     * Matchingstap 2 (R-ID-03): deze nieuwe aanbieding hoort via een kritieke referentie bij hetzelfde
     * artikel als een bestaande aanbieding. Informatief — de aanbieding wordt gewoon aangemaakt en de
     * bestaande aanbiedingsidentiteit wordt nooit vervangen.
     */
    public static final String REFERENCE_LINK_PROPOSED = "REFERENCE_LINK_PROPOSED";
    /**
     * Zoveel gelijksoortige prijsafwijkingen (zelfde component, zelfde richting) binnen één levering
     * dat het geen reeks losse vaststellingen meer is maar één gebeurtenis (R-PRI-14). Eén melding
     * per groep; de individuele meldingen blijven bestaan, met hun voorbeeldcap.
     */
    public static final String BULK_PRICE_INCIDENT = "BULK_PRICE_INCIDENT";
    /**
     * Zoveel gelijksoortige incidenten op kritieke koppelreferenties (zelfde type, zelfde soort
     * incident) dat het om één gebeurtenis gaat — typisch een bulktransformatie bij de leverancier
     * (R-REF-07). Kritiek, want een onbetrouwbare koppeling blokkeert ongeacht volume; élk
     * individueel incident blijft daarnaast onverkort bestaan (individuele audit).
     */
    public static final String BULK_IDENTITY_INCIDENT = "BULK_IDENTITY_INCIDENT";

    /** Scheidingsteken tussen het voorvoegsel en het variabele deel van een dynamische code. */
    public static final char DYNAMIC_CODE_SEPARATOR = ':';

    /**
     * De classificatie van één foutcode.
     *
     * @param defaultImpactScope     de reikwijdte die geldt tenzij de vaststellende regel het beter
     *                               weet
     * @param acceptable             of deze fout aanvaard mág worden zonder de data te corrigeren; in
     *                               fase 3 staat dat overal op {@code false}: er wordt nooit iets
     *                               stilzwijgend doorgelaten
     * @param configurableSeverities de ernstwaarden die een <b>importdefinitie</b> voor deze code mag
     *                               kiezen in plaats van {@link #severity()}. Normaal leeg: de ernst
     *                               hoort bij de code en niet bij de configuratie. Eén uitzondering is
     *                               in de businessanalyse uitdrukkelijk voorzien — de prijsafwijking
     *                               is standaard een waarschuwing en mag per revisie op {@code ERROR}
     *                               gezet worden (R-PRI-10). De verzameling houdt die uitzondering
     *                               eng: een andere ernst wordt geweigerd in plaats van
     *                               overgenomen.
     */
    public record IssueClassification(String code, RowIssueSeverity severity, IssueDomain domain,
                                      ControlLevel controlLevel, ImpactScope defaultImpactScope,
                                      boolean acceptable, Set<RowIssueSeverity> configurableSeverities) {

        public IssueClassification {
            configurableSeverities = configurableSeverities == null
                    ? Set.of() : Set.copyOf(configurableSeverities);
        }

        /** Mag een importdefinitie deze ernst voor deze code kiezen? */
        public boolean allowsSeverity(RowIssueSeverity candidate) {
            return candidate == severity || configurableSeverities.contains(candidate);
        }
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
        return issue(batchId, deliveryFileId, rowNumber, issueCode, fieldName, sourceValue,
                expectedValue, message, null, createdAt);
    }

    /**
     * Dezelfde schrijfroute, maar met de ernst die de <b>importdefinitie</b> voor deze code gekozen
     * heeft. Alleen een ernst die de catalogus uitdrukkelijk toelaat
     * ({@link IssueClassification#configurableSeverities()}) wordt overgenomen; alles anders is een
     * programmeerfout en wordt geweigerd. Zo blijft de catalogus de bron van waarheid, ook wanneer
     * één regel per revisie zwaarder gewogen mag worden (R-PRI-10: {@code price_deviation_severity}).
     *
     * @param configuredSeverity de ernst uit de revisie, of {@code null} om die van de catalogus te
     *                           gebruiken
     * @throws IllegalStateException bij een onbekende code of een niet-toegelaten ernst
     */
    public static IssueRow issue(long batchId, Long deliveryFileId, Long rowNumber, String issueCode,
                                 String fieldName, String sourceValue, String expectedValue,
                                 String message, RowIssueSeverity configuredSeverity,
                                 Instant createdAt) {
        // Een probleem dat aan een bronregel hangt, kan zich op elke volgende regel herhalen en
        // krijgt daarom een groepeerbare signatuur (bouwstap 3g). Een probleem zonder regelnummer
        // raakt de levering als geheel en komt per definitie hoogstens één keer voor: dat hoort bij
        // geen enkele groep en krijgt bewust geen signatuur.
        return issue(batchId, deliveryFileId, rowNumber, issueCode, fieldName, sourceValue,
                expectedValue, message,
                rowNumber == null ? null : IssueSignature.generic(fieldName), configuredSeverity,
                createdAt);
    }

    /**
     * Dezelfde schrijfroute, met een <b>expliciete</b> foutsignatuur. Nodig voor de vaststellingen
     * waarvan de signatuur niet uit de issuerij af te leiden is: een prijsafwijking groepeert op
     * component én richting (R-PRI-14) en een referentie-incident op type én soort (R-REF-07) —
     * gegevens die niet allemaal als kolom op de rij staan. Zo hoeft pass E4 die regels niet nog
     * eens in SQL na te rekenen.
     *
     * @param signature de signatuur, of {@code null} voor een probleem dat bij geen groep hoort
     */
    public static IssueRow issue(long batchId, Long deliveryFileId, Long rowNumber, String issueCode,
                                 String fieldName, String sourceValue, String expectedValue,
                                 String message, IssueSignature.Signature signature,
                                 RowIssueSeverity configuredSeverity, Instant createdAt) {
        IssueClassification classification = classify(issueCode);
        RowIssueSeverity severity = classification.severity();
        if (configuredSeverity != null) {
            if (!classification.allowsSeverity(configuredSeverity)) {
                throw new IllegalStateException("Issue code '" + issueCode + "' is classified as "
                        + severity + " and may not be recorded as " + configuredSeverity
                        + "; only " + classification.configurableSeverities()
                        + " can be configured per import definition");
            }
            severity = configuredSeverity;
        }
        return new IssueRow(batchId, deliveryFileId, rowNumber, issueCode, fieldName,
                severity, classification.domain(), classification.controlLevel(),
                classification.defaultImpactScope(), IssueHandlingStatus.DETECTED, sourceValue,
                expectedValue, message, signature == null ? null : signature.value(), createdAt);
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
                CandidateNormaliser.CODE_CONFIG_FIELD_NOT_RESOLVED,
                // Bouwstap 3b: de veldmapping en de recordfilters van de revisie (R-STR-04..R-STR-06,
                // R-REF-08, R-FLT-01). Ook deze blokkeren vóór er één byte gelezen is.
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
                // Bouwstap 3h-1: de kritiek-vlag van een mapping of revisie-eigen veld schendt de regels
                // (par. 15.1). Nog zonder DeliveryEffect: dat komt in bouwstap 3h-5.
                ImportMappingConfigFactory.CODE_FIELD_CRITICALITY_INVALID,
                // Bouwstap 3e: een prijscontrolemodel dat deze build niet kent (BOXPLOT). Stil
                // terugvallen op de afwijkingscontrole zou een beheerder laten denken dat er een
                // boxplot-analyse draait.
                ImportMappingConfigFactory.CODE_PRICE_CONTROL_MODEL_UNSUPPORTED}) {
            put(catalogue, code, RowIssueSeverity.BLOCKING, IssueDomain.AUTHORISATION_CONFIG,
                    ControlLevel.STRUCTURE, ImpactScope.DELIVERY);
        }

        // --- Niveau 2: het bestands-/datasetcontract --------------------------------------------
        for (String code : new String[] {
                CsvRecordStreamer.CODE_COLUMN_INDEX_OUT_OF_RANGE,
                CsvRecordStreamer.CODE_HEADER_LINE_MISSING,
                CsvRecordStreamer.CODE_HEADER_FIELD_MISSING,
                CsvRecordStreamer.CODE_HEADER_DUPLICATE_FIELD,
                CsvRecordStreamer.CODE_HEADER_COLUMN_COUNT_MISMATCH,
                // Bouwstap 3b: een andere headernaam op de verwachte positie van een identiteits-,
                // prijs- of referentieveld is geen verschuiving maar een betekeniswijziging: doorgaan
                // zou de verkeerde kolom als sleutel of als prijs inlezen (R-STR-02).
                CsvRecordStreamer.CODE_HEADER_FIELD_SEMANTIC_CHANGE,
                // Bouwstap 3b: zonder de kolom waarop de importscope gedefinieerd is, valt niet vast te
                // stellen welke records tot deze import horen (R-FLT-03).
                RecordFilterEvaluator.CODE_FILTER_COLUMN_MISSING}) {
            put(catalogue, code, RowIssueSeverity.BLOCKING, IssueDomain.STRUCTURE_DATASET,
                    ControlLevel.STRUCTURE, ImpactScope.DELIVERY);
        }
        put(catalogue, CsvRecordStreamer.CODE_SOURCE_BOM_REMOVED, RowIssueSeverity.WARNING,
                IssueDomain.STRUCTURE_DATASET, ControlLevel.STRUCTURE, ImpactScope.DELIVERY);
        // Een verschoven of extra kolom is een waarschuwing: de kolom wordt op naam teruggevonden, de
        // levering gaat door, maar de beheerder moet weten dat de bron van vorm veranderd is
        // (R-STR-02/R-STR-03).
        for (String code : new String[] {
                CsvRecordStreamer.CODE_HEADER_FIELD_SHIFTED,
                CsvRecordStreamer.CODE_HEADER_UNKNOWN_COLUMN}) {
            put(catalogue, code, RowIssueSeverity.WARNING, IssueDomain.STRUCTURE_DATASET,
                    ControlLevel.STRUCTURE, ImpactScope.DELIVERY);
        }

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
                CandidateNormaliser.CODE_VALUE_TOO_LONG,
                // Bouwstap 3c: de recordvalidatie van de gemapte doelvelden (R-REC-01..R-REC-08). Elk
                // van deze fouten verwerpt uitsluitend de betrokken bronregel; de waarde wordt nooit
                // stil 0, leeg, afgekapt of geraden.
                FieldValueMapper.CODE_VALUE_TYPE_MISMATCH,
                FieldValueMapper.CODE_DATE_UNREADABLE,
                FieldValueMapper.CODE_DATE_AMBIGUOUS,
                FieldTransform.CODE_TRANSFORM_FAILED,
                FieldTransform.CODE_TRANSFORM_DIVIDE_BY_ZERO,
                FieldTransform.CODE_MAPPING_VALUE_UNKNOWN,
                // Bouwstap 3b: een regel die door een REJECT-filterrij verworpen wordt. Bewust ERROR en
                // geen stille uitsluiting: de beheerder heeft verklaard dat zo'n record niet hoort te
                // bestaan, dus het moet zichtbaar zijn en in rejected_record_count tellen.
                RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED}) {
            put(catalogue, code, RowIssueSeverity.ERROR, IssueDomain.MAPPING_VALIDATION,
                    ControlLevel.RECORD, ImpactScope.RECORD);
        }
        put(catalogue, CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY, RowIssueSeverity.ERROR,
                IssueDomain.IDENTITY_REFERENCE, ControlLevel.RECORD, ImpactScope.RECORD);
        // Bouwstap 3c, R-REC-03: een toegepaste standaardwaarde verwerpt niets en blokkeert niets,
        // maar ze is wél een afwijking van wat de leverancier stuurde. INFO houdt haar buiten
        // rejected_record_count en buiten validation_result, en zichtbaar in de probleemlijst.
        put(catalogue, FieldValueMapper.CODE_VALUE_DEFAULT_APPLIED, RowIssueSeverity.INFO,
                IssueDomain.MAPPING_VALIDATION, ControlLevel.RECORD, ImpactScope.RECORD);
        for (String code : new String[] {
                ImportValueRules.CODE_PRICE_MISSING,
                ImportValueRules.CODE_PRICE_UNREADABLE,
                ImportValueRules.CODE_PRICE_SCALE_EXCEEDED,
                CandidateNormaliser.CODE_PRICE_OUT_OF_RANGE,
                // Bouwstap 3d: de prijsregels van één bronregel (R-PRI-02..R-PRI-08). Allemaal ERROR op
                // recordniveau: ze verwerpen uitsluitend de betrokken regel, en nooit wordt een bedrag,
                // een verhouding of een munt stilzwijgend aangepast om de regel toch door te laten.
                PriceRules.CODE_PRICE_ZERO_NOT_ALLOWED,
                PriceRules.CODE_PRICE_NEGATIVE_NOT_ALLOWED,
                PriceRules.CODE_PRICE_CURRENCY_MISMATCH,
                PriceRules.CODE_PRICE_PERCENTAGE_NOT_COMPUTABLE,
                PriceRules.CODE_PRICE_DERIVATION_MISMATCH,
                PriceRules.CODE_PRICE_PERCENTAGE_OUT_OF_RANGE}) {
            put(catalogue, code, RowIssueSeverity.ERROR, IssueDomain.PRICE, ControlLevel.RECORD,
                    ImpactScope.RECORD);
        }

        // Bouwstap 3e, R-PRI-10: een prijs die meer dan de ingestelde grens afwijkt van haar
        // referenties is standaard een WAARSCHUWING - het record blijft geldig en de prijs wordt
        // nergens aangepast (R-PRI-12). Een revisie mag de melding verzwaren naar ERROR
        // (price_deviation_severity); ook dán wordt het record niet verworpen, het weegt enkel
        // zwaarder in de beoordeling. Zwaardere ernsten zijn niet configureerbaar: een afwijking is
        // een signaal, nooit op zichzelf een blokkade van de hele levering.
        put(catalogue, PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED, RowIssueSeverity.WARNING,
                IssueDomain.PRICE, ControlLevel.RECORD, ImpactScope.RECORD,
                Set.of(RowIssueSeverity.ERROR));
        // R-PRI-11: één samenvattende melding per levering over de referenties die ontbraken, nooit
        // één per record. Informatief: er is niets mis met de levering, er is enkel (nog) niets om
        // mee te vergelijken.
        put(catalogue, PriceDeviationEvaluator.CODE_PRICE_REFERENCE_NOT_AVAILABLE, RowIssueSeverity.INFO,
                IssueDomain.PRICE, ControlLevel.DELIVERY, ImpactScope.DELIVERY);

        // Bouwstap 3f, R-REF-02..R-REF-07: kritieke koppelreferenties. Ernst CRITICAL op RECORD-niveau
        // is bewust de enige combinatie waarin een recordprobleem het eindoordeel van de hele levering
        // op BLOCKING zet: een onbetrouwbare identiteitskoppeling blokkeert "ongeacht volume"
        // (R-REF-07) en mag nooit meeliften op een drempel. Het record zelf wordt vastgehouden
        // (R-REF-09), niet verworpen: het blijft geldig gelezen en telt in identity_incident_count.
        for (String code : new String[] {
                IDENTITY_REFERENCE_INCIDENT,
                DUPLICATE_REFERENCE_IN_DELIVERY}) {
            put(catalogue, code, RowIssueSeverity.CRITICAL, IssueDomain.IDENTITY_REFERENCE,
                    ControlLevel.RECORD, ImpactScope.RECORD);
        }
        // R-ID-03: een nieuwe aanbieding voor hetzelfde artikel is geen fout maar een vaststelling.
        // De aanbieding wordt volgens het gewone creatiebeleid gemaakt; de bestaande
        // aanbiedingsidentiteit blijft onaangeroerd.
        put(catalogue, REFERENCE_LINK_PROPOSED, RowIssueSeverity.INFO, IssueDomain.IDENTITY_REFERENCE,
                ControlLevel.RECORD, ImpactScope.RECORD);

        // Bouwstap 3g, R-THR-04: één samenvattende melding per bulkgroep, op leveringsniveau. Bewust
        // NAAST de individuele meldingen en nooit in de plaats ervan - de individuele audit blijft
        // (R-REF-07). De ernst volgt die van het onderliggende verschijnsel: een reeks
        // prijsafwijkingen is een blokkerende vaststelling over de levering (R-PRI-14), een reeks
        // identiteitsincidenten is kritiek en blokkeert ongeacht volume.
        put(catalogue, BULK_PRICE_INCIDENT, RowIssueSeverity.BLOCKING, IssueDomain.PRICE,
                ControlLevel.DELIVERY, ImpactScope.DELIVERY);
        put(catalogue, BULK_IDENTITY_INCIDENT, RowIssueSeverity.CRITICAL,
                IssueDomain.IDENTITY_REFERENCE, ControlLevel.DELIVERY, ImpactScope.DELIVERY);

        return Collections.unmodifiableMap(catalogue);
    }

    private static void put(Map<String, IssueClassification> catalogue, String code,
                            RowIssueSeverity severity, IssueDomain domain, ControlLevel controlLevel,
                            ImpactScope defaultImpactScope) {
        put(catalogue, code, severity, domain, controlLevel, defaultImpactScope, Set.of());
    }

    private static void put(Map<String, IssueClassification> catalogue, String code,
                            RowIssueSeverity severity, IssueDomain domain, ControlLevel controlLevel,
                            ImpactScope defaultImpactScope,
                            Set<RowIssueSeverity> configurableSeverities) {
        // Fase 3 kent geen enkele aanvaardbare fout: aanvaarden is een expliciete handeling met
        // een behandelstatus, geen eigenschap van de foutcode.
        IssueClassification classification = new IssueClassification(code, severity, domain,
                controlLevel, defaultImpactScope, false, configurableSeverities);
        if (catalogue.putIfAbsent(code, classification) != null) {
            throw new IllegalStateException("Issue code '" + code + "' is declared twice in ImportIssueCatalog");
        }
    }
}
