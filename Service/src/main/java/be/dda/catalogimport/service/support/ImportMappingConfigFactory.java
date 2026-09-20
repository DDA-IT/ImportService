package be.dda.catalogimport.service.support;

import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.FieldDataType;
import be.dda.catalogimport.domain.FieldOwner;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterStage;
import be.dda.catalogimport.domain.IdentityClass;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import be.dda.catalogimport.domain.PriceControlModel;
import be.dda.catalogimport.domain.RevisionCriticalityField;
import be.dda.catalogimport.domain.RevisionOwnedField;
import be.dda.catalogimport.service.support.ImportMappingConfig.FieldMapping;
import be.dda.catalogimport.service.support.ImportMappingConfig.RecordFilter;
import be.dda.catalogimport.service.support.ImportMappingConfig.ValueFormat;
import be.dda.catalogimport.service.support.ImportValueRules.DecimalFormat;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Leest en valideert de veldmapping en de recordfilters van een bevroren revisie tot een
 * {@link ImportMappingConfig} (ontwerp fase 3, par. 3.1 stap B', R-STR-04..R-STR-06, R-REF-08,
 * R-FLT-01).
 * <p>
 * <b>Businessgedrag.</b> Een fout in de definitie is geen regelfout maar een configuratiefout: de
 * volledige levering wordt geblokkeerd met een {@code CONFIG_*}-code, <b>vóór</b> er één byte van het
 * bronbestand gelezen is. De beheerder krijgt zo één duidelijke melding in plaats van een miljoen
 * identieke rijfouten, en er wordt nooit een deel van de catalogus verwerkt op basis van een
 * definitie die zichzelf tegenspreekt.
 * <p>
 * <b>Wat hier hard is.</b>
 * <ul>
 *   <li>Een mapping wijst naar een bestaand doelveld uit de catalogus; onbekend ⇒
 *       {@code CONFIG_MAPPING_TARGET_UNKNOWN}.</li>
 *   <li>Een doelveld komt hoogstens één keer voor per revisie ⇒
 *       {@code CONFIG_MAPPING_DUPLICATE_TARGET}.</li>
 *   <li>De bronverwijzing moet oplosbaar zijn; bij {@code COLUMN_INDEX} meteen, bij
 *       {@code HEADER_NAME} pas bij de headercontrole ⇒ {@code CONFIG_MAPPING_SOURCE_UNRESOLVED}.</li>
 *   <li>Type en lengte zijn verenigbaar met de catalogus ⇒
 *       {@code CONFIG_MAPPING_TYPE_INCOMPATIBLE}.</li>
 *   <li>Hoogstens één mapping per prijscomponent ⇒ {@code CONFIG_PRICE_COMPONENT_DUPLICATE}. Twee
 *       kolommen die dezelfde prijscomponent vullen is altijd fout, ook als ze toevallig hetzelfde
 *       bedrag bevatten.</li>
 *   <li>Een niet-wisselbare eigenaar blijft de eigenaar uit de catalogus ⇒
 *       {@code CONFIG_OWNER_NOT_CHANGEABLE} (R-REF-08).</li>
 *   <li>De identiteitsklasse spreekt de catalogus niet tegen en er is minstens één sterke
 *       identiteitsregel ⇒ {@code CONFIG_IDENTITY_CLASS_CONFLICT} (R-STR-05).</li>
 *   <li>Een veld dat de revisiekolommen al bepalen wordt niet nóg eens gemapt ⇒
 *       {@code CONFIG_FIELD_MAPPING_DUPLICATES_REVISION} (R-STR-06).</li>
 *   <li>Een onbekende of nog niet ondersteunde transformatiesoort ⇒
 *       {@code CONFIG_TRANSFORM_INVALID}; een onbruikbare filterrij ⇒
 *       {@code CONFIG_FILTER_INVALID}.</li>
 *   <li>Elke mapping vereist canonicalisatieversie 2 ⇒
 *       {@code CONFIG_CANONICALISATION_VERSION_REQUIRED} (par. 3.5). Prijscomponenten werken sinds
 *       bouwstap 3d onder versie 2; referentiemappings blokkeren nog tot bouwstap 3f, in plaats van te
 *       draaien met een vingerafdruk die de gemapte velden niet dekt.</li>
 *   <li>Een prijscomponent is DECIMAL met hoogstens {@value PriceRules#AMOUNT_SCALE} decimalen en kent
 *       hoogstens één uitdrukkelijk geconfigureerde bovengrens ({@link #SETTING_MAX_PERCENTAGE},
 *       R-PRI-08).</li>
 *   <li>De kritiek-vlag schendt de regels (een referentiemapping die niet kritiek is, een
 *       identiteitsveld dat niet kritiek is, een onbekende veldsleutel of een dubbele overrule) ⇒
 *       {@code CONFIG_FIELD_CRITICALITY_INVALID} (bouwstap 3h-1, par. 15.1).</li>
 * </ul>
 * Deze klasse leest de revisie en haar mappings uitsluitend via getters en geeft een momentopname
 * terug; ze wordt daarom binnen de openende transactie aangeroepen en het resultaat wordt daarbuiten
 * gebruikt. Alles wordt exact één keer per batch gelezen — nooit een query per bronregel.
 */
@Component
public class ImportMappingConfigFactory {

    public static final String CODE_MAPPING_TARGET_UNKNOWN = "CONFIG_MAPPING_TARGET_UNKNOWN";
    public static final String CODE_MAPPING_SOURCE_UNRESOLVED = "CONFIG_MAPPING_SOURCE_UNRESOLVED";
    public static final String CODE_MAPPING_DUPLICATE_TARGET = "CONFIG_MAPPING_DUPLICATE_TARGET";
    public static final String CODE_MAPPING_TYPE_INCOMPATIBLE = "CONFIG_MAPPING_TYPE_INCOMPATIBLE";
    public static final String CODE_FIELD_MAPPING_DUPLICATES_REVISION =
            "CONFIG_FIELD_MAPPING_DUPLICATES_REVISION";
    public static final String CODE_IDENTITY_CLASS_CONFLICT = "CONFIG_IDENTITY_CLASS_CONFLICT";
    public static final String CODE_OWNER_NOT_CHANGEABLE = "CONFIG_OWNER_NOT_CHANGEABLE";
    public static final String CODE_PRICE_COMPONENT_DUPLICATE = "CONFIG_PRICE_COMPONENT_DUPLICATE";
    public static final String CODE_TRANSFORM_INVALID = "CONFIG_TRANSFORM_INVALID";
    public static final String CODE_FILTER_INVALID = "CONFIG_FILTER_INVALID";
    public static final String CODE_CANONICALISATION_VERSION_REQUIRED =
            "CONFIG_CANONICALISATION_VERSION_REQUIRED";
    /**
     * De kritiek-vlag van een mapping of van een revisie-eigen veld schendt de regels (bouwstap 3h-1,
     * par. 15.1): een referentiemapping die niet kritiek is, een identiteitsveld dat niet kritiek is,
     * een onbekende veldsleutel of een dubbele overrule. De databasechecks weren zulke rijen al; deze
     * validatie is de tweede verdediging voor een configuratie die niet via de database binnenkwam.
     */
    public static final String CODE_FIELD_CRITICALITY_INVALID = "CONFIG_FIELD_CRITICALITY_INVALID";
    /**
     * Het prijscontrolemodel van de revisie wordt door deze build niet uitgevoerd (bouwstap 3e).
     * {@code BOXPLOT} is in het schema gedeclareerd maar bewust niet geïmplementeerd (ontwerp fase 3,
     * par. 0): stil terugvallen op de afwijkingscontrole zou de beheerder laten denken dat er een
     * boxplot-analyse op zijn prijzen draait.
     */
    public static final String CODE_PRICE_CONTROL_MODEL_UNSUPPORTED =
            "CONFIG_PRICE_CONTROL_MODEL_UNSUPPORTED";

    /**
     * De canonicalisatieversie die de prijscomponenten en de kritieke referenties in de
     * vingerafdrukken opneemt (par. 3.5). Ze wordt pas in bouwstap 3d/3f ondersteund; tot dan is dit
     * enkel de versie die een revisie met zulke mappings móét declareren.
     */
    public static final int CANONICALISATION_VERSION_WITH_COMPONENTS = 2;

    /** Instelling in {@code transform_config}: het decimaalteken van dit veld (R-REC-04). */
    public static final String SETTING_DECIMAL_SEPARATOR = "decimalSeparator";
    /** Instelling in {@code transform_config}: het duizendtalteken; enkel dán wordt het verwijderd. */
    public static final String SETTING_GROUPING_SEPARATOR = "groupingSeparator";
    /** Instelling in {@code transform_config}: het verklaarde bronformaat van een datum (R-REC-05). */
    public static final String SETTING_DATE_FORMAT = "dateFormat";
    /** Instelling in {@code transform_config}: de tijdzone van een brontijdstempel (R-REC-05). */
    public static final String SETTING_ZONE = "zone";
    /**
     * Instelling in {@code transform_config}: de semantische bovengrens van een prijscomponent, in
     * procent van de basisprijs (R-PRI-08). Zonder deze instelling wordt er géén bovengrens
     * gecontroleerd — er bestaat geen kunstmatig plafond op een prijsverhouding.
     */
    public static final String SETTING_MAX_PERCENTAGE = "maxPercentage";

    private final ImportFieldMappingRepository mappings;
    private final ImportRecordFilterRepository filters;
    private final ImportRevisionFieldCriticalityRepository fieldCriticalities;

    @Autowired
    public ImportMappingConfigFactory(ImportFieldMappingRepository mappings,
                                      ImportRecordFilterRepository filters,
                                      ImportRevisionFieldCriticalityRepository fieldCriticalities) {
        this.mappings = mappings;
        this.filters = filters;
        this.fieldCriticalities = fieldCriticalities;
    }

    /**
     * De fabriek zonder toegang tot de kritiek-overrules van de revisie-eigen velden: voor aanroepers
     * die enkel een reeds geladen configuratie valideren
     * ({@link #from(ImportDefinitionRevision, SourceStructureConfig, List, List)}).
     */
    public ImportMappingConfigFactory(ImportFieldMappingRepository mappings,
                                      ImportRecordFilterRepository filters) {
        this(mappings, filters, null);
    }

    /**
     * @param structure de al gevalideerde bronconfiguratie; bepaalt of een bronverwijzing een
     *                  headernaam of een kolomindex is
     * @throws ScreeningBlockedException bij ontbrekende, tegenstrijdige of nog niet ondersteunde
     *                                   configuratie
     */
    public ImportMappingConfig from(ImportDefinitionRevision revision, SourceStructureConfig structure) {
        Long revisionId = revision.getId();
        // Stap B': mappings, filters en de kritiek-overrules van de revisie-eigen velden worden
        // exact één keer per batch gelezen, vóór er één byte van het bronbestand gelezen is.
        return from(revision, structure, mappings.findByRevisionIdWithTargetField(revisionId),
                filters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId),
                fieldCriticalities.findByDefinitionRevisionId(revisionId));
    }

    /**
     * Valideert een reeds geladen configuratie. Dezelfde regels als
     * {@link #from(ImportDefinitionRevision, SourceStructureConfig)}, maar zonder de database aan te
     * raken: zo is elke regel afzonderlijk te bewijzen, en kan een latere configuratie-editor een
     * ontwerp valideren vóórdat het bewaard wordt.
     *
     * @throws ScreeningBlockedException bij ontbrekende, tegenstrijdige of nog niet ondersteunde
     *                                   configuratie
     */
    public ImportMappingConfig from(ImportDefinitionRevision revision, SourceStructureConfig structure,
                                    List<ImportFieldMapping> mappingRows,
                                    List<ImportRecordFilter> filterRows) {
        return from(revision, structure, mappingRows, filterRows, List.of());
    }

    /**
     * Dezelfde validatie, inclusief de kritiek-overrules van de revisie-eigen velden (bouwstap 3h-1).
     * Zonder overrule-rij geldt per revisie-eigen veld de standaard van de soort.
     *
     * @param criticalityRows de rijen uit {@code import_revision_field_criticality} van deze revisie
     * @throws ScreeningBlockedException bij ontbrekende, tegenstrijdige of nog niet ondersteunde
     *                                   configuratie, en bij een kritiek-vlag die de regels schendt
     *                                   ({@link #CODE_FIELD_CRITICALITY_INVALID})
     */
    public ImportMappingConfig from(ImportDefinitionRevision revision, SourceStructureConfig structure,
                                    List<ImportFieldMapping> mappingRows,
                                    List<ImportRecordFilter> filterRows,
                                    List<ImportRevisionFieldCriticality> criticalityRows) {
        List<ImportFieldMapping> active = mappingRows.stream()
                .filter(ImportFieldMapping::isActive)
                .toList();
        verifyPriceControlModel(revision);
        Map<RevisionCriticalityField, Criticality> overrules = readCriticalityOverrules(criticalityRows);
        List<FieldMapping> fields = readFields(revision, structure, active);
        List<RecordFilter> recordFilters = readFilters(structure, filterRows);
        verifyIdentityClasses(revision, fields);
        verifyCanonicalisationVersion(revision, fields);
        Map<String, Criticality> criticalities = ImportMappingConfig.criticalityMap(
                revisionOwnCriticality(structure, overrules), fields);
        return new ImportMappingConfig(revision.getRecordCanonicalisationVersion(), fields, recordFilters,
                criticalities);
    }

    // --- Kritiek-vlag (bouwstap 3h-1) -----------------------------------------------------------

    /**
     * Valideert de overrule-rijen van de revisie-eigen velden en zet ze om naar een map per veld.
     * <p>
     * Wat hier hard is: een onbekende {@code field_key}, een ontbrekende waarde, een dubbele rij voor
     * hetzelfde veld en een identiteitsveld dat {@code NON_CRITICAL} zou zijn. De database weert dit al
     * (pk en checks van changeset 004-15); dit is de tweede verdediging, want een configuratie die
     * halverwege een miljoenenimport stil een identiteitsveld als niet-kritiek behandelt, is precies wat
     * de review moet voorkomen.
     */
    private static Map<RevisionCriticalityField, Criticality> readCriticalityOverrules(
            List<ImportRevisionFieldCriticality> rows) {
        Map<RevisionCriticalityField, Criticality> overrules = new LinkedHashMap<>();
        for (ImportRevisionFieldCriticality row : rows) {
            String key = row.getFieldKey();
            RevisionCriticalityField field = RevisionCriticalityField.byKey(key).orElse(null);
            if (field == null) {
                throw blocked(CODE_FIELD_CRITICALITY_INVALID, key, String.valueOf(row.getCriticality()),
                        "Criticality is configured for '" + key + "', which is not a field of the revision "
                                + "itself; the known fields are " + Arrays.toString(
                                RevisionCriticalityField.values()));
            }
            if (row.getCriticality() == null) {
                throw blocked(CODE_FIELD_CRITICALITY_INVALID, key, null,
                        "Criticality of '" + key + "' has no value; use " + Criticality.CRITICAL + " or "
                                + Criticality.NON_CRITICAL);
            }
            if (field.isIdentity() && row.getCriticality() == Criticality.NON_CRITICAL) {
                throw blocked(CODE_FIELD_CRITICALITY_INVALID, key, String.valueOf(row.getCriticality()),
                        "Field '" + key + "' is part of the offer identity and can never be "
                                + Criticality.NON_CRITICAL + "; without an identity there is no offer to "
                                + "review");
            }
            if (overrules.put(field, row.getCriticality()) != null) {
                throw blocked(CODE_FIELD_CRITICALITY_INVALID, key, String.valueOf(row.getCriticality()),
                        "Criticality of '" + key + "' is configured more than once for this revision; a "
                                + "field has exactly one criticality");
            }
        }
        return overrules;
    }

    /**
     * De kritiek-vlag van elk revisie-eigen veld, per bronreferentie: dezelfde namen als
     * {@code ImportValueException.getField()} voor die velden (de headernaam of kolomindex uit de
     * bronstructuur). De kortingscode telt enkel mee bij een vierdelige identiteit — bij een
     * driedelige wordt de kolom niet gelezen, dus kan er ook geen fout op vallen. Zonder overrule-rij
     * geldt de standaard van de soort ({@link RevisionCriticalityField#defaultCriticality()}).
     */
    private static Map<String, Criticality> revisionOwnCriticality(
            SourceStructureConfig structure, Map<RevisionCriticalityField, Criticality> overrules) {
        Map<String, Criticality> own = new LinkedHashMap<>();
        putOwn(own, structure.supplierField(), RevisionCriticalityField.SUPPLIER, overrules);
        putOwn(own, structure.supplierGroupField(), RevisionCriticalityField.SUPPLIER_GROUP, overrules);
        putOwn(own, structure.supplierReferenceField(), RevisionCriticalityField.SUPPLIER_REFERENCE,
                overrules);
        if (structure.identityProfileKind() == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE) {
            putOwn(own, structure.discountCodeField(), RevisionCriticalityField.DISCOUNT_CODE, overrules);
        }
        putOwn(own, structure.basePriceField(), RevisionCriticalityField.BASE_PRICE, overrules);
        putOwn(own, structure.currencyField(), RevisionCriticalityField.CURRENCY, overrules);
        putOwn(own, structure.descriptionField(), RevisionCriticalityField.DESCRIPTION, overrules);
        return own;
    }

    /** Twee revisie-eigen velden op dezelfde bronkolom: de strengste vlag wint. */
    private static void putOwn(Map<String, Criticality> own, String sourceReference,
                               RevisionCriticalityField field,
                               Map<RevisionCriticalityField, Criticality> overrules) {
        if (sourceReference == null) {
            return;
        }
        own.merge(sourceReference, overrules.getOrDefault(field, field.defaultCriticality()),
                Criticality::strictest);
    }

    /**
     * R-REF-08 / par. 15.1: een kritieke koppelreferentie kan nooit {@code NON_CRITICAL} zijn. De
     * databasecheck {@code ck_import_field_mapping_reference_critical} weert het ook, maar een
     * configuratie die niet uit de database komt (een toekomstige editor) mag hier niet doorheen.
     */
    private static void verifyMappingCriticality(ImportFieldMapping row, String code) {
        if (row.getReferenceType() != null && row.getCriticality() != Criticality.CRITICAL) {
            throw blocked(CODE_FIELD_CRITICALITY_INVALID, code, String.valueOf(row.getCriticality()),
                    "Mapping for '" + code + "' carries reference type '" + row.getReferenceType()
                            + "' and can never be " + Criticality.NON_CRITICAL + "; an unreliable "
                            + "reference is exactly what the review must catch");
        }
    }

    // --- Mappings ------------------------------------------------------------------------------

    private static List<FieldMapping> readFields(ImportDefinitionRevision revision,
                                                 SourceStructureConfig structure,
                                                 List<ImportFieldMapping> rows) {
        List<FieldMapping> fields = new ArrayList<>(rows.size());
        Set<String> targets = new LinkedHashSet<>();
        Set<Integer> sequences = new HashSet<>();
        Set<String> priceComponents = new HashSet<>();
        for (ImportFieldMapping row : rows) {
            ImportFieldCatalogEntry target = row.getTargetField();
            String code = target == null ? null : target.getCode();
            verifyDoesNotDuplicateRevision(revision, code);
            if (target == null || !target.isActive()) {
                throw blocked(CODE_MAPPING_TARGET_UNKNOWN, code, null,
                        "Mapping " + row.getSequenceNumber() + " targets field '" + code
                                + "', which does not exist or is no longer active in the field catalogue");
            }
            if (!targets.add(code)) {
                throw blocked(CODE_MAPPING_DUPLICATE_TARGET, code, null,
                        "Target field '" + code + "' is mapped more than once in this revision; a target "
                                + "field has exactly one source");
            }
            if (!sequences.add(row.getSequenceNumber())) {
                throw blocked(CODE_MAPPING_DUPLICATE_TARGET, code,
                        String.valueOf(row.getSequenceNumber()),
                        "Sequence number " + row.getSequenceNumber() + " occurs more than once in the "
                                + "mapping of this revision");
            }
            verifyTypes(row, target, code);
            verifyOwner(row, target, code);
            verifyIdentityClass(row, target, code);
            verifyMappingCriticality(row, code);
            // Stap B': de transformatie en de notatie worden hier exact één keer geparsed, vóór er één
            // byte gelezen is. Een ongeldige configuratie blokkeert dus de levering in plaats van een
            // miljoen identieke rijfouten op te leveren (R-REC-07, R-REC-05, R-REC-04).
            MappingSettings settings = MappingSettings.parse(code, row.getTransformConfig());
            ValueFormat valueFormat = readValueFormat(row, code, settings);
            FieldTransform transform = readTransform(row, code, settings, valueFormat);
            BigDecimal maxPercentage = readMaxPercentage(row, code, settings);
            settings.verifyFullyUsed();
            verifySource(structure, row, code, transform);
            if (row.getPriceComponentCode() != null && !priceComponents.add(row.getPriceComponentCode())) {
                throw blocked(CODE_PRICE_COMPONENT_DUPLICATE, code, row.getPriceComponentCode(),
                        "Price component '" + row.getPriceComponentCode() + "' is mapped more than once in "
                                + "this revision; two sources for one price component are never reconciled "
                                + "silently");
            }
            fields.add(toFieldMapping(row, target, transform, valueFormat, maxPercentage));
        }
        return fields;
    }

    /**
     * R-PRI-08: de enige toegelaten bovengrens op een prijsverhouding is een <b>uitdrukkelijk
     * geconfigureerde</b> semantische grens per component ({@code maxPercentage=} in
     * {@code transform_config}). Zonder die instelling wordt er niet op de verhouding gecontroleerd —
     * een aankoopprijs van 900% van de basisprijs kan legitiem zijn en mag niet door een verzonnen
     * plafond geweigerd worden.
     * <p>
     * De instelling op een veld dat géén prijscomponent is, is een configuratiefout: ze zou nooit
     * gebruikt worden en de beheerder zou denken dat er gecontroleerd wordt.
     */
    private static BigDecimal readMaxPercentage(ImportFieldMapping row, String code,
                                                MappingSettings settings) {
        String value = settings.get(SETTING_MAX_PERCENTAGE);
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (row.getPriceComponentCode() == null) {
            throw blocked(CODE_TRANSFORM_INVALID, code, value,
                    "Mapping for '" + code + "' declares " + SETTING_MAX_PERCENTAGE
                            + " but carries no price component; the limit would never be checked");
        }
        BigDecimal max;
        try {
            max = new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException notANumber) {
            throw blocked(CODE_TRANSFORM_INVALID, code, value, "Setting " + SETTING_MAX_PERCENTAGE
                    + " of '" + code + "' must be a decimal percentage but is '" + value + "'");
        }
        if (max.signum() <= 0) {
            throw blocked(CODE_TRANSFORM_INVALID, code, value, "Setting " + SETTING_MAX_PERCENTAGE
                    + " of '" + code + "' must be higher than 0; a maximum of 0 would reject every "
                    + "positive price");
        }
        return max;
    }

    /**
     * De verklaarde notatie van één veld (R-REC-04/R-REC-05), uit {@code transform_config}:
     * {@code decimalSeparator}, {@code groupingSeparator}, {@code dateFormat} en {@code zone}.
     * <p>
     * <b>Waarom hier en niet in een nieuwe kolom.</b> {@code import_field_mapping.transform_config}
     * bestaat al en draagt per definitie de parameters van één veld; een extra kolom zou een
     * schemawijziging vragen zonder iets toe te voegen. De sleutels van de notatie en die van de
     * transformatie kunnen elkaar niet overlappen, en een onbekende sleutel is een configuratiefout.
     */
    private static ValueFormat readValueFormat(ImportFieldMapping row, String code,
                                               MappingSettings settings) {
        DecimalFormat decimal = ValueFormat.DEFAULT.decimal();
        if (row.getDecimalScale() != null || settings.get(SETTING_DECIMAL_SEPARATOR) != null
                || settings.get(SETTING_GROUPING_SEPARATOR) != null) {
            int scale = row.getDecimalScale() == null ? ImportValueRules.MAX_DECIMAL_SCALE
                    : row.getDecimalScale();
            Character decimalSeparator = settings.optionalCharacter(SETTING_DECIMAL_SEPARATOR);
            Character groupingSeparator = settings.optionalCharacter(SETTING_GROUPING_SEPARATOR);
            if (decimalSeparator != null && decimalSeparator.equals(groupingSeparator)) {
                throw blocked(CODE_TRANSFORM_INVALID, code, String.valueOf(decimalSeparator),
                        "Mapping for '" + code + "' declares the same decimal and grouping separator; the "
                                + "notation of a number would then be undefined");
            }
            decimal = new DecimalFormat(scale, decimalSeparator, groupingSeparator);
        }

        String pattern = settings.get(SETTING_DATE_FORMAT);
        ZoneId zone = zone(settings.get(SETTING_ZONE), code);
        if (pattern == null || pattern.isEmpty()) {
            if (zone != null && !isDateType(row)) {
                throw blocked(CODE_TRANSFORM_INVALID, code, null,
                        "Mapping for '" + code + "' declares a time zone but is not a date or timestamp");
            }
            return new ValueFormat(decimal, null, null, zone);
        }
        if (!isDateType(row)) {
            throw blocked(CODE_TRANSFORM_INVALID, code, pattern,
                    "Mapping for '" + code + "' declares a date format but its type is " + row.getDataType());
        }
        return new ValueFormat(decimal, dateFormatter(pattern, code), pattern, zone);
    }

    private static boolean isDateType(ImportFieldMapping row) {
        return row.getDataType() == FieldDataType.DATE || row.getDataType() == FieldDataType.DATETIME;
    }

    /**
     * Bouwt de datumparser één keer per batch. De parser staat bewust op {@code STRICT}: 31 februari
     * bestaat niet en moet een fout opleveren, niet stilzwijgend 28 februari worden. Daarvoor is het
     * proleptische jaarsymbool {@code u} nodig; een patroon met {@code y} (de gangbare schrijfwijze)
     * wordt daarom op de jaarposities omgezet naar {@code u}. Dat wijzigt de betekenis niet — het
     * verschil betreft uitsluitend de tijdrekening vóór onze jaartelling — en spaart de beheerder een
     * val die anders elke datum onleesbaar zou maken.
     */
    private static DateTimeFormatter dateFormatter(String pattern, String code) {
        try {
            return DateTimeFormatter.ofPattern(prolepticYear(pattern))
                    .withResolverStyle(ResolverStyle.STRICT);
        } catch (IllegalArgumentException invalid) {
            throw blocked(CODE_TRANSFORM_INVALID, code, pattern,
                    "Mapping for '" + code + "' declares the date format '" + pattern
                            + "', which is not a valid pattern: " + invalid.getMessage());
        }
    }

    /** Vervangt {@code y} door {@code u} buiten aanhalingstekens; letterlijke tekst blijft ongemoeid. */
    private static String prolepticYear(String pattern) {
        StringBuilder proleptic = new StringBuilder(pattern.length());
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (current == '\'') {
                quoted = !quoted;
            }
            proleptic.append(!quoted && current == 'y' ? 'u' : current);
        }
        return proleptic.toString();
    }

    private static ZoneId zone(String zone, String code) {
        if (zone == null || zone.isEmpty()) {
            return null;
        }
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException unknown) {
            throw blocked(CODE_TRANSFORM_INVALID, code, zone,
                    "Mapping for '" + code + "' declares the unknown time zone '" + zone + "'");
        }
    }

    /**
     * R-REC-07: de transformatie komt uit de gesloten lijst van {@link FieldTransform}. Een onbekende
     * of onvolledige configuratie blokkeert; ze wordt nooit als "geen transformatie" behandeld.
     */
    private static FieldTransform readTransform(ImportFieldMapping row, String code,
                                                MappingSettings settings, ValueFormat valueFormat) {
        if (row.getTransformKind() == null) {
            throw blocked(CODE_TRANSFORM_INVALID, code, null,
                    "Mapping for '" + code + "' has no transform kind; use NONE to state that there is no "
                            + "transformation");
        }
        return FieldTransform.of(row.getTransformKind(), settings, code, valueFormat.decimal());
    }

    /**
     * R-PRI-10: deze build voert uitsluitend prijscontrolemodel 1 uit (de afwijkingscontrole tegen
     * de vorige waarde en de gemiddelden van de laatste 50 en 200 goedgekeurde dagwaarden).
     * {@code BOXPLOT} bestaat in het schema zodat het model later zonder migratie kan aansluiten,
     * maar een revisie die het declareert wordt <b>geweigerd</b> vóór er één byte gelezen is. De
     * levering stilzwijgend met een ander model beoordelen zou betekenen dat de beheerder denkt dat
     * er een spreidingsanalyse draait terwijl er een percentagegrens getoetst wordt.
     */
    private static void verifyPriceControlModel(ImportDefinitionRevision revision) {
        PriceControlModel model = revision.getPriceControlModel();
        if (model == PriceControlModel.DEVIATION) {
            return;
        }
        throw blocked(CODE_PRICE_CONTROL_MODEL_UNSUPPORTED, null, String.valueOf(model),
                "This revision declares price control model " + model + ", which this build does not "
                        + "perform; only " + PriceControlModel.DEVIATION + " (the deviation check "
                        + "against the previous value and the two moving averages) is implemented");
    }

    /** R-STR-06: de revisiekolommen blijven autoritair; een tweede bron voor dezelfde waarde is fout. */
    private static void verifyDoesNotDuplicateRevision(ImportDefinitionRevision revision, String code) {
        RevisionOwnedField owned = RevisionOwnedField.byCode(code).orElse(null);
        if (owned == null || !isDeterminedByRevision(revision, owned)) {
            return;
        }
        throw blocked(CODE_FIELD_MAPPING_DUPLICATES_REVISION, code, owned.revisionColumn(),
                "Target field '" + code + "' is already determined by revision column '"
                        + owned.revisionColumn() + "'; remove either the mapping or the revision column "
                        + "instead of keeping two sources for the same value");
    }

    private static boolean isDeterminedByRevision(ImportDefinitionRevision revision,
                                                  RevisionOwnedField owned) {
        return switch (owned) {
            case SUPPLIER -> revision.getIdentitySupplierField() != null;
            case SUPPLIER_GROUP -> revision.getIdentitySupplierGroupField() != null;
            case SUPPLIER_REFERENCE -> revision.getIdentitySupplierReferenceField() != null;
            case DISCOUNT_CODE -> revision.getIdentityDiscountCodeField() != null;
            case BASE_PRICE -> revision.getRecordBasePriceField() != null;
            case DESCRIPTION -> revision.getRecordDescriptionField() != null;
        };
    }

    private static void verifySource(SourceStructureConfig structure, ImportFieldMapping row, String code,
                                     FieldTransform transform) {
        FieldValueKind kind = row.getValueKind();
        if (kind == FieldValueKind.FIXED_VALUE) {
            if (row.getFixedValue() == null) {
                throw blocked(CODE_MAPPING_SOURCE_UNRESOLVED, code, null,
                        "Mapping for '" + code + "' declares a fixed value but does not carry one");
            }
            return;
        }
        if (kind == FieldValueKind.BOOKMARK) {
            // Sjablonen en bookmarks bestaan al in het schema (beslissingslog 18/09) maar er is nog geen
            // invulmechanisme; stil negeren zou een leeg doelveld opleveren dat op een bewuste blanco lijkt.
            throw blocked(CODE_MAPPING_SOURCE_UNRESOLVED, code, row.getBookmarkName(),
                    "Mapping for '" + code + "' reads the template value '" + row.getBookmarkName()
                            + "', which this build cannot fill in yet");
        }
        if (kind == FieldValueKind.DERIVED) {
            // Een afgeleid veld heeft geen bronkolom: enkel een transformatie die haar waarde zélf
            // opbouwt kan het vullen (R-REC-07). Elke andere transformatie zou een leeg veld opleveren.
            if (!(transform instanceof FieldTransform.Fixed) && !(transform instanceof FieldTransform.Concat)) {
                throw blocked(CODE_TRANSFORM_INVALID, code, String.valueOf(row.getTransformKind()),
                        "Mapping for '" + code + "' is derived but its transformation " + row.getTransformKind()
                                + " needs a source value; only a fixed value or a concatenation can build a "
                                + "derived field on its own");
            }
            return;
        }
        String reference = row.getSourceReference();
        if (reference == null || reference.isBlank()) {
            throw blocked(CODE_MAPPING_SOURCE_UNRESOLVED, code, reference,
                    "Mapping for '" + code + "' has no source column");
        }
        if (structure.fieldReferenceKind() == FieldReferenceKind.COLUMN_INDEX) {
            requireColumnIndex(CODE_MAPPING_SOURCE_UNRESOLVED, code, reference,
                    structure.expectedColumnCount());
        }
        // Bij HEADER_NAME wordt de kolom pas bij de headercontrole beoordeeld (R-STR-04): het
        // bronbestand is hier nog niet geopend.
        Integer expected = row.getExpectedPosition();
        if (expected != null && expected < 1) {
            throw blocked(CODE_MAPPING_SOURCE_UNRESOLVED, code, String.valueOf(expected),
                    "Expected position of '" + code + "' must be a 1-based column position");
        }
    }

    private static void verifyTypes(ImportFieldMapping row, ImportFieldCatalogEntry target, String code) {
        if (row.getDataType() != target.getDataType()) {
            throw blocked(CODE_MAPPING_TYPE_INCOMPATIBLE, code, String.valueOf(row.getDataType()),
                    "Mapping for '" + code + "' declares type " + row.getDataType()
                            + " but the field catalogue declares " + target.getDataType()
                            + "; a leading zero or a decimal separator would silently change meaning");
        }
        if (row.getMaxLength() != null && row.getMaxLength() < 1) {
            throw blocked(CODE_MAPPING_TYPE_INCOMPATIBLE, code, String.valueOf(row.getMaxLength()),
                    "Maximum length of '" + code + "' must be 1 or higher");
        }
        if (row.getDecimalScale() != null && (row.getDecimalScale() < 0 || row.getDecimalScale() > 12)) {
            throw blocked(CODE_MAPPING_TYPE_INCOMPATIBLE, code, String.valueOf(row.getDecimalScale()),
                    "Decimal scale of '" + code + "' must be between 0 and 12");
        }
        String componentCode = row.getPriceComponentCode();
        if (componentCode != null && !componentCode.equals(target.getPriceComponentCode())) {
            throw blocked(CODE_MAPPING_TYPE_INCOMPATIBLE, code, componentCode,
                    "Mapping for '" + code + "' declares price component '" + componentCode
                            + "' but the field catalogue declares '" + target.getPriceComponentCode() + "'");
        }
        if (componentCode != null) {
            if (row.getDataType() != FieldDataType.DECIMAL) {
                throw blocked(CODE_MAPPING_TYPE_INCOMPATIBLE, code, String.valueOf(row.getDataType()),
                        "Mapping for '" + code + "' carries price component '" + componentCode
                                + "' but is declared as " + row.getDataType()
                                + "; an amount is always DECIMAL");
            }
            // import_candidate_price.source_amount is numeric(24,6): een bronbedrag met meer decimalen
            // zou bij het wegschrijven stil afgerond worden. Dat wordt hier geweigerd, niet afgerond.
            if (row.getDecimalScale() != null && row.getDecimalScale() > PriceRules.AMOUNT_SCALE) {
                throw blocked(CODE_MAPPING_TYPE_INCOMPATIBLE, code, String.valueOf(row.getDecimalScale()),
                        "Mapping for '" + code + "' declares " + row.getDecimalScale() + " decimals for "
                                + "price component '" + componentCode + "', but an amount is stored with "
                                + "at most " + PriceRules.AMOUNT_SCALE
                                + "; the remaining decimals would be rounded away silently");
            }
        }
        String reference = row.getReferenceType();
        if (reference != null && !reference.equals(target.getReferenceType())) {
            throw blocked(CODE_MAPPING_TYPE_INCOMPATIBLE, code, reference,
                    "Mapping for '" + code + "' declares reference type '" + reference
                            + "' but the field catalogue declares '" + target.getReferenceType() + "'");
        }
    }

    /** R-REF-08: eigenaarschap van een kritieke referentie (en van een Prodis-veld) is niet wisselbaar. */
    private static void verifyOwner(ImportFieldMapping row, ImportFieldCatalogEntry target, String code) {
        if (!target.isOwnerChangeable() && row.getFieldOwner() != target.getDefaultOwner()) {
            throw blocked(CODE_OWNER_NOT_CHANGEABLE, code, String.valueOf(row.getFieldOwner()),
                    "Target field '" + code + "' is owned by " + target.getDefaultOwner()
                            + " and that ownership cannot be changed by an import definition");
        }
        if (row.getFieldOwner() == FieldOwner.CRITICAL_REFERENCE && row.getReferenceType() == null) {
            throw blocked(CODE_MAPPING_TYPE_INCOMPATIBLE, code, null,
                    "Target field '" + code + "' is owned by the reference control but carries no "
                            + "reference type");
        }
    }

    /**
     * R-STR-05: geen enkel veld is tegelijk sterk identificerend en ondersteunend/zwak, en er is
     * minstens één sterke identiteitsregel. De bestaande revisiekolommen (leverancier,
     * leveranciersgroep, leveranciersreferentie) zijn zelf de sterke identiteit en zijn verplicht;
     * daarmee is de tweede voorwaarde vandaag altijd voldaan. De controle blijft staan omdat een
     * latere fase de identiteit volledig uit mappings kan opbouwen.
     */
    private static void verifyIdentityClasses(ImportDefinitionRevision revision, List<FieldMapping> fields) {
        boolean strongFromRevision = revision.getIdentitySupplierField() != null
                && revision.getIdentitySupplierGroupField() != null
                && revision.getIdentitySupplierReferenceField() != null;
        boolean strongFromMapping = fields.stream()
                .anyMatch(field -> field.identityClass() == IdentityClass.STRONG);
        if (!strongFromRevision && !strongFromMapping) {
            throw blocked(CODE_IDENTITY_CLASS_CONFLICT, null, null,
                    "This revision declares no strong identity rule; an offer identity can never be derived "
                            + "from supporting or weak fields alone");
        }
    }

    private static void verifyIdentityClass(ImportFieldMapping row, ImportFieldCatalogEntry target,
                                            String code) {
        IdentityClass declared = row.getIdentityClass();
        IdentityClass catalogue = target.getIdentityClass();
        if (declared == catalogue || declared == IdentityClass.NONE) {
            return;
        }
        throw blocked(CODE_IDENTITY_CLASS_CONFLICT, code, String.valueOf(declared),
                "Target field '" + code + "' is classified as " + catalogue + " in the field catalogue but "
                        + "this revision declares " + declared + "; a field is never strongly identifying and "
                        + "supporting or weak at the same time. Only NONE (not used for identity) is allowed "
                        + "as an alternative.");
    }

    /**
     * Par. 3.5: de vingerafdruk moet <b>elk</b> gemapt veld dekken. Een revisie die velden mapt maar
     * canonicalisatieversie 1 declareert, zou wijzigingen aan die velden niet in de delta zien: de
     * artikelvingerafdruk van versie 1 dekt enkel de omschrijving. Zo'n revisie blokkeert daarom
     * ({@code CONFIG_CANONICALISATION_VERSION_REQUIRED}) in plaats van te draaien met een
     * vingerafdruk die haar eigen mappings negeert.
     * <p>
     * <b>Prijscomponenten</b> zijn sinds bouwstap 3d volledig verwerkbaar onder versie 2: hun
     * percentages zitten in de prijsvingerafdruk. Onder versie 1 blijven ze geblokkeerd met dezelfde
     * code — de v1-prijshash dekt enkel de basisprijs en de munt, zodat een gewijzigde verhouding
     * onzichtbaar zou blijven.
     * <p>
     * <b>Kritieke referenties</b> zijn sinds bouwstap 3f volledig verwerkbaar onder versie 2: hun
     * genormaliseerde waarden zitten in de referentievingerafdruk en lopen door de referentiecontrole
     * (R-REF-02..R-REF-06). Onder versie 1 blijven ze geblokkeerd met dezelfde code — versie 1 kent
     * geen referentiedeel, dus een gewijzigde EAN zou onzichtbaar blijven in de delta en dus stil als
     * gewone update doorgaan. Dat is precies wat par. 14.23.3 verbiedt.
     * <p>
     * <b>Ook de munt.</b> Een revisie die {@code record_currency_field} declareert, wijzigt de
     * prijsvingerafdruk (de munt zit erin). Onder versie 1 zou dat de hash van elke bestaande bronstaat
     * doen verschuiven; zo'n revisie moet dus versie 2 declareren.
     */
    private static void verifyCanonicalisationVersion(ImportDefinitionRevision revision,
                                                      List<FieldMapping> fields) {
        int version = revision.getRecordCanonicalisationVersion();
        boolean hasReferences = fields.stream().anyMatch(field -> field.referenceType() != null);
        if (hasReferences && version != CANONICALISATION_VERSION_WITH_COMPONENTS) {
            throw blocked(CODE_CANONICALISATION_VERSION_REQUIRED, null, String.valueOf(version),
                    "This revision maps critical references. Those belong to canonicalisation version "
                            + CANONICALISATION_VERSION_WITH_COMPONENTS + "; under version " + version
                            + " a changed EAN, PIM or CAB would stay out of the fingerprint and would "
                            + "silently pass as an ordinary update");
        }
        if (revision.getRecordCurrencyField() != null && !revision.getRecordCurrencyField().isBlank()
                && version != CANONICALISATION_VERSION_WITH_COMPONENTS) {
            throw blocked(CODE_CANONICALISATION_VERSION_REQUIRED, null, String.valueOf(version),
                    "This revision reads a currency from the source, which is part of the price "
                            + "fingerprint of canonicalisation version " + CANONICALISATION_VERSION_WITH_COMPONENTS
                            + "; under version " + version + " the currency would change the fingerprint of "
                            + "every existing source state");
        }
        if (fields.isEmpty()) {
            return;
        }
        if (version != CANONICALISATION_VERSION_WITH_COMPONENTS) {
            throw blocked(CODE_CANONICALISATION_VERSION_REQUIRED, null, String.valueOf(version),
                    "This revision maps " + fields.size() + " target field(s), which must be covered by "
                            + "canonicalisation version " + CANONICALISATION_VERSION_WITH_COMPONENTS
                            + "; version " + version + " would leave "
                            + "changes to those fields out of the fingerprint");
        }
    }

    private static FieldMapping toFieldMapping(ImportFieldMapping row, ImportFieldCatalogEntry target,
                                               FieldTransform transform, ValueFormat valueFormat,
                                               BigDecimal maxPercentage) {
        return new FieldMapping(row.getSequenceNumber(), target.getCode(), target.getName(),
                row.getValueKind(), trimToNull(row.getSourceReference()), row.getExpectedPosition(),
                row.getFixedValue(), row.getBookmarkName(), row.getDefaultValue(), row.getDataType(),
                row.isRequired(), row.getMaxLength(), row.getDecimalScale(), row.isZeroAllowed(),
                row.isNegativeAllowed(), row.getTransformKind(), row.getTransformConfig(), transform,
                valueFormat, row.getFieldOwner(), row.getIdentityClass(), row.getPriceComponentCode(),
                row.getReferenceType(), maxPercentage, row.getCriticality());
    }

    // --- Recordfilters --------------------------------------------------------------------------

    private static List<RecordFilter> readFilters(SourceStructureConfig structure,
                                                  List<ImportRecordFilter> rows) {
        List<RecordFilter> filters = new ArrayList<>(rows.size());
        Set<Integer> sequences = new HashSet<>();
        for (ImportRecordFilter row : rows) {
            int sequence = row.getSequenceNumber();
            if (!sequences.add(sequence)) {
                throw blocked(CODE_FILTER_INVALID, null, String.valueOf(sequence),
                        "Record filter sequence number " + sequence + " occurs more than once in this "
                                + "revision; the evaluation order would be undefined");
            }
            if (row.getFilterStage() != FilterStage.SOURCE_FIELD) {
                throw blocked(CODE_FILTER_INVALID, row.getSourceReference(),
                        String.valueOf(row.getFilterStage()),
                        "Record filter " + sequence + " filters on stage " + row.getFilterStage()
                                + ", which this build does not support yet; ignoring it would import records "
                                + "that are deliberately out of scope");
            }
            String reference = trimToNull(row.getSourceReference());
            if (reference == null) {
                throw blocked(CODE_FILTER_INVALID, null, null,
                        "Record filter " + sequence + " has no source column");
            }
            if (structure.fieldReferenceKind() == FieldReferenceKind.COLUMN_INDEX) {
                requireColumnIndex(CODE_FILTER_INVALID, reference, reference,
                        structure.expectedColumnCount());
            }
            if (row.getOperator() == null || row.getOutcome() == null || row.getNullBehaviour() == null
                    || row.getMissingColumnBehaviour() == null) {
                throw blocked(CODE_FILTER_INVALID, reference, null,
                        "Record filter " + sequence + " is incomplete; operator, outcome, null behaviour and "
                                + "missing column behaviour are all required");
            }
            String compareValue = row.getCompareValue();
            if (compareValue == null || (compareValue.isBlank() && requiresNonEmptyCompareValue(
                    row.getOperator()))) {
                throw blocked(CODE_FILTER_INVALID, reference, compareValue,
                        "Record filter " + sequence + " compares with " + row.getOperator()
                                + " but has no value to compare with; that rule would match every record");
            }
            filters.add(new RecordFilter(sequence, reference, row.getOperator(), compareValue,
                    row.getOutcome(), row.isCaseSensitive(), row.isTrimBeforeCompare(),
                    row.getNullBehaviour(), row.getMissingColumnBehaviour()));
        }
        return filters;
    }

    /** Een lege waarde is betekenisloos bij deze operatoren: ze zouden altijd (of nooit) matchen. */
    private static boolean requiresNonEmptyCompareValue(FilterOperator operator) {
        return operator == FilterOperator.BEGINS_WITH || operator == FilterOperator.ENDS_WITH
                || operator == FilterOperator.CONTAINS || operator == FilterOperator.NOT_CONTAINS;
    }

    // --- Hulpmiddelen ---------------------------------------------------------------------------

    private static void requireColumnIndex(String code, String fieldName, String reference,
                                           Integer expectedColumnCount) {
        int index;
        try {
            index = Integer.parseInt(reference.trim());
        } catch (NumberFormatException notAnIndex) {
            throw blocked(code, fieldName, reference,
                    "Source reference '" + reference + "' must be a 1-based column index because this "
                            + "revision references fields by column index");
        }
        if (index < 1 || (expectedColumnCount != null && index > expectedColumnCount)) {
            throw blocked(code, fieldName, reference,
                    "Source reference '" + reference + "' is not a column of this source (expected column "
                            + "count " + expectedColumnCount + ")");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ScreeningBlockedException blocked(String code, String fieldName, String sourceValue,
                                                     String reason) {
        return new ScreeningBlockedException(code, fieldName, sourceValue, null, reason);
    }
}
