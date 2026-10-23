package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportFieldTransformStepRepository;
import be.dda.catalogimport.dao.ImportFieldValueMapRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.SourceStateDao;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.CreationDefaultsOrigin;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportFieldTransformStep;
import be.dda.catalogimport.domain.ImportFieldValueMap;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import be.dda.catalogimport.domain.MissingColumnBehaviour;
import be.dda.catalogimport.domain.RevisionCriticalityField;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.support.BookmarkDeclarations;
import be.dda.catalogimport.service.support.ImportMappingConfigFactory;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import be.dda.catalogimport.service.support.SourceStructureConfigFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuratie van een importketen: bronorganisatie → importdefinitie → revisie (met mappings,
 * filters en kritiek-overrules) → koppeling → taak.
 * <p>
 * <b>Waarvoor deze service bestaat.</b> Een keten met expliciete opdrachten inrichten: sinds NT-3
 * (beslissingslog 2026-09-30 "Nieuwe leverancier + taak (NT-spoor)", V2 = a) is dat de manier waarop een
 * gebruiker zelf een nieuwe leverancier en taak aanmaakt in de UI.
 * <p>
 * <b>Veiligheid.</b> Wie deze schrijfmethodes kan aanroepen, kan een importdefinitie en haar drempels
 * bepalen en dus de controle op een catalogus beïnvloeden. De bijhorende REST-laag
 * ({@code CatalogImportSetupController}) vraagt daarom een login en het recht {@code catalogImport.manage}
 * per actie (403 {@code PERMISSION_DENIED} zonder recht); ze staat sinds NT-3 <b>niet</b> meer achter
 * {@code catalogimport.setup-api.enabled}. Enkel {@link #overview()} ({@code GET /setup/overview}) is nog
 * achter die vlag bereikbaar.
 * <p>
 * <b>Gelijktijdig aanmaken (NT-3; bronorganisatie, definitie, koppeling, taak).</b> De controle "bestaat deze
 * code/naam al?" gebeurt vóór het
 * wegschrijven en geeft de gewone 409 {@code *_IN_USE}. Twee gelijktijdige verzoeken kunnen die controle
 * allebei passeren; de tweede botst dan op de unieke databasesleutel. Die botsing wordt op constraintnaam
 * vertaald naar <b>dezelfde</b> 409-code ({@link #translateCreateConflict}) — nooit een 500, en elke andere
 * integriteitsfout gaat ongewijzigd door.
 * <p>
 * <b>Foutcodes op een 400 (NT-3, additief).</b> Een ongeldige aanvraag werpt een {@link BadRequestException}
 * (een {@link IllegalArgumentException}) met een stabiele code naast de ongewijzigde tekst: de
 * {@code CONFIG_*}-code van de configuratievalidatie, of voor een veldfout {@code <VELD>_REQUIRED},
 * {@code <VELD>_TOO_LONG} of {@code <VELD>_INVALID}, waarbij {@code <VELD>} de veldnaam uit het verzoek in
 * hoofdletters met underscores is (bv. {@code delimiter} → {@code DELIMITER_REQUIRED}) — dezelfde vorm als
 * het bestaande {@code CHANGE_REASON_REQUIRED}.
 * <p>
 * <b>Wie tekent (5A-6).</b> Elke schrijfmethode hieronder heeft naast haar bestaande {@code String}-vorm
 * een overload met {@link ActorIdentity}. De Web-laag gebruikt uitsluitend die overload en bewaart naam
 * én OIDC-subject; de oude {@code String}-vorm bewaart enkel de naam en laat het subject {@code null}
 * ("geen geverifieerde identiteit") — alleen voor tests en {@code DemoDataSeeder}.
 * <p>
 * <b>Wat deze service níét doet.</b> Ze voegt geen enkele businessregel toe. Ze bewaart precies de
 * velden die het model al kent, laat de bestaande validatie het werk doen
 * ({@link SourceStructureConfigFactory}, {@link ImportMappingConfigFactory} — dezelfde validatie die
 * de screening gebruikt) en laat de databaseconstraints staan waar ze staan. Ze wijzigt niets aan het
 * gedrag van de bestaande upload- en screeningendpoints.
 * <p>
 * <b>Wanneer wordt er gevalideerd.</b> Een revisie ontstaat als {@link RevisionStatus#DRAFT} en wordt
 * dan enkel getoetst aan wat de database/entiteit al afdwingt (verplichte velden, veldlengtes). De
 * volledige configuratievalidatie gebeurt bij het toevoegen van een mapping/filter/kritiek-overrule en
 * bij {@link #activateRevision(long, String)} — het moment waarop de revisie echt bruikbaar wordt.
 * Een ongeldige configuratie levert daar een {@link BadRequestException} op (HTTP 400) met de
 * {@code CONFIG_*}-code van de bestaande validatie in de boodschap én als code; de transactie rolt terug, zodat er
 * nooit een half opgeslagen definitie achterblijft.
 * <p>
 * <b>Wijzigen en verwijderen binnen een DRAFT</b> ({@link #updateRevision}, {@link #deleteMapping},
 * {@link #deleteFilter} — bouwstap S1-X-4) valideren de configuratie <b>niet</b> opnieuw: een werkversie
 * mag tussentijds onvolledig zijn, en bij het activeren gebeurt de volledige validatie alsnog. Wat daar wél
 * bewaakt wordt, zijn de twee onomkeerbare wijzigingen uit
 * {@code docs/design/revision-successor-design.md} §5 (R-REV-X2/R-REV-X3) en de integriteit van de
 * bookmarkdeclaratie na een verwijdering.
 * <p>
 * Er wordt hier nooit een geheim, wachtwoord of bestandsinhoud bewaard of gelogd.
 */
@Service
@Transactional
public class SetupService {

    /** {@code source_organisation.code}, {@code import_definition.code}, {@code import_link.code}. */
    private static final int MAX_CODE_LENGTH = 50;
    /** Zelfde vorm als PriceRules.ISO_4217 (private daar): exact drie hoofdletters. */
    private static final Pattern LINK_CURRENCY_SHAPE = Pattern.compile("[A-Z]{3}");
    /** {@code name}/{@code description} van organisatie, definitie, koppeling en taak. */
    private static final int MAX_NAME_LENGTH = 200;
    /** {@code import_link.library_code}. */
    private static final int MAX_LIBRARY_CODE_LENGTH = 20;
    /** {@code created_by}/{@code approved_by}. */
    private static final int MAX_USER_LENGTH = 100;
    /** {@code identity_*_field}, {@code record_*_field}, {@code source_reference}. */
    static final int MAX_FIELD_REFERENCE_LENGTH = 200;
    /** {@code identity_supplier_fixed_value}, {@code identity_supplier_group_fixed_value} (legacy-config S6). */
    static final int MAX_IDENTITY_FIXED_VALUE_LENGTH = 50;
    /** {@code import_definition_revision.change_reason}. */
    static final int MAX_CHANGE_REASON_LENGTH = 500;
    /** {@code import_link.default_vat_code}, {@code import_link.default_unit_code} (changeset 030). */
    static final int MAX_CREATION_DEFAULT_CODE_LENGTH = 50;
    /** 400 (CD-B2): de btw-code bestaat niet onder de actieve Prodis-rijen ({@code vat_code.code}). */
    public static final String CODE_PRODIS_VAT_CODE_NOT_FOUND = "PRODIS_VAT_CODE_NOT_FOUND";
    /** 400 (CD-B2): de eenheid bestaat niet onder de actieve Prodis-rijen ({@code unit_of_measure.code}). */
    public static final String CODE_PRODIS_UNIT_NOT_FOUND = "PRODIS_UNIT_NOT_FOUND";
    /** Waarschuwing (CD-B2): de code komt meer dan eens voor onder de actieve Prodis-rijen; de waarde is bewaard. */
    public static final String WARNING_PRODIS_REFERENCE_AMBIGUOUS = "PRODIS_REFERENCE_AMBIGUOUS";
    /** Wie een rij aanmaakte wanneer de aanroeper niets meegeeft; nooit een echte gebruikersnaam. */
    private static final String DEFAULT_CREATED_BY = "setup-api";

    // --- Opdrachten (request) --------------------------------------------------------------------

    /** @param type {@code SUPPLIER} of {@code PURCHASING_ASSOCIATION} */
    public record CreateSourceOrganisationCommand(String code, String name, SourceOrganisationType type) {
    }

    /** @param usageType {@code null} betekent {@link DefinitionUsageType#OWN_DEFINITION} */
    public record CreateDefinitionCommand(String sourceOrganisationCode, String code, String name,
                                          DefinitionUsageType usageType) {
    }

    /**
     * De negen drempel- en prijsbeleidsvelden die zowel het aanmaken ({@link CreateRevisionCommand}) als
     * het wijzigen ({@link UpdateRevisionCommand}) van een revisie kent, zodat
     * {@link RevisionFieldRules#applyThresholds(ImportDefinitionRevision, RevisionThresholds)} één implementatie blijft.
     * <p>
     * Deze velden zijn financieel bepalend (blokkeerdrempels, prijsafwijking, prijsreconstructie). Twee
     * kopieën van dezelfde toepassingsregels zouden op termijn uit elkaar lopen en dan zou dezelfde
     * ingevoerde waarde op het ene pad wél en op het andere níet een negatief percentage weigeren.
     */
    interface RevisionThresholds {

        BigDecimal creationThresholdSharePercent();

        BigDecimal maxCriticalSharePercent();

        BigDecimal maxRejectedSharePercent();

        BigDecimal bulkIncidentSharePercent();

        BigDecimal priceDeviationPercent();

        RowIssueSeverity priceDeviationSeverity();

        Boolean basePriceZeroAllowed();

        Boolean basePriceNegativeAllowed();

        BigDecimal priceDerivationTolerance();
    }

    /**
     * De inhoud van één nieuwe revisie. Elk veld dat {@code null} blijft, houdt de bestaande default
     * van {@link ImportDefinitionRevision} — er wordt nooit stil een andere waarde gekozen.
     * <p>
     * <b>Legacy-config S6 (additief; gemelde betekeniswijziging van {@code supplierField}, beslissingslog M3).</b>
     * {@code supplierFixedValue}/{@code supplierGroupFixedValue} leggen de leverancier/groep vast voor elke regel
     * (businesscode uit Prodis, hoogstens 50 tekens); dan is {@code supplierField}/{@code supplierGroupField}
     * <b>optioneel</b> en moet het leeg blijven: precies één van kolom of vaste waarde (400
     * {@code SUPPLIER_FIXED_VALUE_INVALID} / {@code SUPPLIER_GROUP_FIXED_VALUE_INVALID} bij beide, 400
     * {@code SUPPLIER_FIELD_REQUIRED} / {@code SUPPLIER_GROUP_FIELD_REQUIRED} bij geen van beide, zoals vroeger). Is de
     * Prodis-leesbron geconfigureerd, dan moet de vaste waarde daar bestaan (400 {@code *_NOT_IN_PRODIS}, 503
     * {@code PRODIS_DB_UNAVAILABLE}); zonder Prodis-bron wordt ze aanvaard met een waarschuwing in
     * {@link RevisionView#warnings()}. {@code identityUppercase} ({@code null} = {@code false}) zet de identiteitsdelen
     * in hoofdletters vóór de canonicalisatie.
     */
    public record CreateRevisionCommand(String delimiter, String quoteChar, String charset, Boolean hasHeader,
                                        Integer headerLineNumber, String fieldReferenceKind,
                                        Integer expectedColumnCount, IdentityProfileKind identityProfileKind,
                                        String supplierField, String supplierGroupField,
                                        String supplierReferenceField, String discountCodeField,
                                        String basePriceField, String descriptionField, String currencyField,
                                        Integer canonicalisationVersion,
                                        BigDecimal creationThresholdSharePercent,
                                        BigDecimal maxCriticalSharePercent,
                                        BigDecimal maxRejectedSharePercent,
                                        BigDecimal bulkIncidentSharePercent,
                                        BigDecimal priceDeviationPercent,
                                        RowIssueSeverity priceDeviationSeverity,
                                        Boolean basePriceZeroAllowed, Boolean basePriceNegativeAllowed,
                                        BigDecimal priceDerivationTolerance, String changeReason,
                                        String createdBy, String supplierFixedValue,
                                        String supplierGroupFixedValue,
                                        Boolean identityUppercase) implements RevisionThresholds {
    }

    /**
     * De gevraagde wijziging aan de scalaire velden van een {@link RevisionStatus#DRAFT}-revisie (endpoint
     * E3, {@code docs/design/revision-successor-design.md} §6; bouwstap S1-X-4). <b>Exact dezelfde
     * veldnamen</b> als {@link CreateRevisionCommand}, plus {@link #acknowledgeIdentityChange()}.
     *
     * <h2>{@code null} betekent altijd "ongewijzigd"</h2>
     * Een afwezig of {@code null} veld laat de bestaande waarde van de revisie staan — deze opdracht kan
     * dus nooit stilzwijgend een veld leegmaken dat de aanroeper niet noemde. Dat is dezelfde conventie
     * als bij {@link CreateRevisionCommand}, waar {@code null} de bestaande default laat staan.
     * <p>
     * <b>Gevolg voor de optionele velden.</b> Voor {@code discountCodeField}, {@code descriptionField},
     * {@code currencyField} en {@code quoteChar} betekent een uitdrukkelijk lege tekst ({@code ""}) wél
     * "wissen" — hetzelfde onderscheid dat {@link CreateRevisionCommand#quoteChar()} al maakt tussen
     * {@code null} ("niets gezegd") en {@code ""} ("deze bron kent geen quoting"). Voor de verplichte
     * velden ({@code delimiter}, de drie leveranciersvelden, {@code basePriceField}) is een lege tekst
     * een 400: ze worden nooit stil leeggemaakt. {@code expectedColumnCount} en
     * {@code maxRejectedSharePercent} zijn langs dit pad niet terug op {@code null} te zetten; dat is een
     * bewuste beperking van "{@code null} = ongewijzigd" en geen stille wijziging.
     *
     * @param acknowledgeIdentityChange R-REV-X3 (§5): een wijziging aan {@code identityProfileKind} of aan
     *                                  een van de vier {@code identity*Field}-velden verandert de canonieke
     *                                  identiteitstekst en laat élke bestaande aanbieding als {@code NEW}
     *                                  terugkomen. Zonder een uitdrukkelijke {@code true} wordt zo'n
     *                                  wijziging geweigerd met 409 {@code IDENTITY_CHANGE_NOT_ACKNOWLEDGED}
     *                                  (patroon {@code MATERIALISATION_MODE_REQUIRED}: geen default, want
     *                                  een geraden keuze bepaalt hier de identiteit van de catalogus).
     *                                  Afwezig of {@code null} = {@code false}.
     * @param createdBy                 optioneel en sinds 5A-6 enkel nog een controle tegen de aangemelde
     *                                  gebruiker; een wijziging aan een DRAFT heeft geen eigen
     *                                  {@code *_by}-kolom, dus er wordt hier geen naam bewaard
     * @param supplierFixedValue        legacy-config S6: {@code null} = ongewijzigd, {@code ""} = wissen, anders de
     *                                  vaste leverancier. Omdat precies één van kolom of vaste waarde mag, wist wie
     *                                  overstapt de andere uitdrukkelijk ({@code "supplierField": ""} betekent sinds S6
     *                                  "wissen" in plaats van een 400 — die volgt nog altijd als er daarna geen van
     *                                  beide overblijft). Nooit een stille wissel.
     * @param supplierGroupFixedValue   idem voor de leveranciersgroep en {@code supplierGroupField}
     * @param identityUppercase         {@code null} = ongewijzigd. Een wijziging aan een vaste waarde of aan deze
     *                                  schakelaar is een identiteitswijziging (R-REV-X3)
     */
    public record UpdateRevisionCommand(String delimiter, String quoteChar, String charset, Boolean hasHeader,
                                        Integer headerLineNumber, String fieldReferenceKind,
                                        Integer expectedColumnCount, IdentityProfileKind identityProfileKind,
                                        String supplierField, String supplierGroupField,
                                        String supplierReferenceField, String discountCodeField,
                                        String basePriceField, String descriptionField, String currencyField,
                                        Integer canonicalisationVersion,
                                        BigDecimal creationThresholdSharePercent,
                                        BigDecimal maxCriticalSharePercent,
                                        BigDecimal maxRejectedSharePercent,
                                        BigDecimal bulkIncidentSharePercent,
                                        BigDecimal priceDeviationPercent,
                                        RowIssueSeverity priceDeviationSeverity,
                                        Boolean basePriceZeroAllowed, Boolean basePriceNegativeAllowed,
                                        BigDecimal priceDerivationTolerance, String changeReason,
                                        Boolean acknowledgeIdentityChange,
                                        String createdBy, String supplierFixedValue,
                                        String supplierGroupFixedValue,
                                        Boolean identityUppercase) implements RevisionThresholds {

        /** Een leeg verzoek: elke wijziging afwezig, dus alles ongewijzigd. */
        static UpdateRevisionCommand empty() {
            return new UpdateRevisionCommand(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }
    }

    /**
     * Eén veldmapping. Type, eigenaar, identiteitsklasse, prijscomponent en referentietype komen uit
     * de veldcatalogus en worden bewust <b>niet</b> door de aanroeper gezet: een mapping die haar
     * catalogusrij tegenspreekt is altijd een configuratiefout.
     */
    public record CreateMappingCommand(String targetFieldCode, String sourceReference, Integer sequenceNumber,
                                       FieldValueKind valueKind, Integer expectedPosition, String fixedValue,
                                       String defaultValue, Boolean required, Integer maxLength,
                                       Integer decimalScale, Boolean zeroAllowed, Boolean negativeAllowed,
                                       FieldTransformKind transformKind, String transformConfig,
                                       Criticality criticality, String createdBy,
                                       List<ValueMapEntry> valueMap, List<TransformStepEntry> steps) {
    }

    /**
     * Eén stap van de transformatieketen van een {@code CHAIN}-mapping (legacy-config S8c, additief API-veld
     * {@code steps[{kind, config}]}; tabel {@code import_field_transform_step}), in volgorde: de eerste stap krijgt
     * nummer 1. Hoogstens {@value ImportFieldTransformStep#MAX_STEPS} stappen; {@code kind} is verplicht en nooit
     * {@code CHAIN}; {@code config} hoogstens {@value ImportFieldTransformStep#MAX_CONFIG_LENGTH} tekens. Of de keten
     * past (de gesloten volgorde {@code [SUBSTRING] → [AFFIX|PREFIX|SUFFIX] → [één waardebewerking]}, de instellingen
     * per stap, {@code steps} enkel bij {@code CHAIN}) beslist de configuratievalidatie: 400 {@code CONFIG_TRANSFORM_INVALID}.
     */
    public record TransformStepEntry(FieldTransformKind kind, String config) {
    }

    /**
     * Eén regel van de vertaaltabel van een {@code MAP}-mapping (legacy-config S8a, additief API-veld
     * {@code valueMap[{source, target}]}; tabel {@code import_field_value_map}). De bronwaarde is verplicht, beide
     * hoogstens {@value ImportFieldValueMap#MAX_VALUE_LENGTH} tekens en getrimd zoals {@code values=}; een lege
     * doelwaarde ({@code ""}) vertaalt naar leeg. Een MAP gebruikt óf {@code values=} in {@code transformConfig}, óf
     * {@code valueMap}: beide, geen van beide, of {@code valueMap} bij een andere soort geeft 400
     * {@code CONFIG_TRANSFORM_INVALID} (dezelfde configuratievalidatie als de screening).
     */
    public record ValueMapEntry(String source, String target) {
    }

    /**
     * @param matchGroup legacy-config S8f (additief API-veld, kolom {@code import_record_filter.match_group}): filterrijen
     *                   met dezelfde groep vormen een EN-regel; {@code null} (of afwezig in JSON) = een losse rij zoals
     *                   vroeger. Een groep is een positief getal (anders 400 {@code MATCH_GROUP_INVALID}) en een
     *                   gegroepeerde rij is {@code EXCLUDE} met {@code nullBehaviour=COMPARE_AS_EMPTY} (anders 400
     *                   {@code CONFIG_FILTER_INVALID}). Enkel in een groep mag een {@code EQUALS}-rij een lege
     *                   {@code compareValue} hebben ({@code ""} = "de kolom is leeg", legacy PSIMPEXCL); elders blijft
     *                   een lege vergelijkwaarde 400 {@code COMPARE_VALUE_REQUIRED}.
     */
    public record CreateFilterCommand(Integer sequenceNumber, String sourceReference, FilterOperator operator,
                                      String compareValue, FilterOutcome outcome, Boolean caseSensitive,
                                      Boolean trimBeforeCompare, FilterNullBehaviour nullBehaviour,
                                      MissingColumnBehaviour missingColumnBehaviour, String createdBy,
                                      Integer matchGroup) {

        /** De vorm van voor S8f: een losse rij. */
        public CreateFilterCommand(Integer sequenceNumber, String sourceReference, FilterOperator operator,
                                   String compareValue, FilterOutcome outcome, Boolean caseSensitive,
                                   Boolean trimBeforeCompare, FilterNullBehaviour nullBehaviour,
                                   MissingColumnBehaviour missingColumnBehaviour, String createdBy) {
            this(sequenceNumber, sourceReference, operator, compareValue, outcome, caseSensitive, trimBeforeCompare,
                    nullBehaviour, missingColumnBehaviour, createdBy, null);
        }
    }

    /** @param fieldKey een sleutel uit {@link RevisionCriticalityField} */
    public record CreateFieldCriticalityCommand(String fieldKey, Criticality criticality, String createdBy) {
    }

    /** Body van {@code PUT .../field-criticality/{fieldKey}} (EB2); de veldsleutel staat in het pad. */
    public record SetFieldCriticalityCommand(Criticality criticality, String createdBy) {
    }

    public record CreateLinkCommand(Long definitionId, String code, String name, String supplierCode,
                                    String libraryCode, String librarySearchSupplierCode,
                                    String defaultCurrency) {
    }

    /** De taak is altijd {@link TaskTriggerType#MANUAL}: alleen daarop is een upload toegelaten. */
    public record CreateTaskCommand(Long linkId, String name, Boolean preventConcurrentRuns) {
    }

    // --- Antwoorden (response) -------------------------------------------------------------------

    public record SourceOrganisationView(long id, String code, String name, String type, boolean active) {
    }

    public record DefinitionView(long id, String code, String name, String usageType,
                                 long sourceOrganisationId, String sourceOrganisationCode) {
    }

    public record RevisionView(long id, long definitionId, int revisionNumber, String status,
                               String identityProfileKind, String delimiter, boolean hasHeader,
                               String fieldReferenceKind, int canonicalisationVersion,
                               String supplierField, String supplierGroupField, String supplierReferenceField,
                               String discountCodeField, String basePriceField, String descriptionField,
                               String currencyField, BigDecimal creationThresholdSharePercent,
                               BigDecimal maxCriticalSharePercent, BigDecimal maxRejectedSharePercent,
                               BigDecimal bulkIncidentSharePercent, String supplierFixedValue,
                               String supplierGroupFixedValue, boolean identityUppercase,
                               List<SetupWarning> warnings) {

        public RevisionView {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** Dezelfde weergave met de waarschuwingen van dit verzoek. */
        RevisionView withWarnings(List<SetupWarning> requestWarnings) {
            return new RevisionView(id, definitionId, revisionNumber, status, identityProfileKind, delimiter,
                    hasHeader, fieldReferenceKind, canonicalisationVersion, supplierField, supplierGroupField,
                    supplierReferenceField, discountCodeField, basePriceField, descriptionField, currencyField,
                    creationThresholdSharePercent, maxCriticalSharePercent, maxRejectedSharePercent,
                    bulkIncidentSharePercent, supplierFixedValue, supplierGroupFixedValue, identityUppercase,
                    requestWarnings);
        }
    }

    /**
     * Een waarschuwing bij het aanmaken of wijzigen van een revisie (legacy-config S6, additief): het verzoek is
     * uitgevoerd, maar iets is niet nagekeken. Vandaag enkel {@code PRODIS_REFERENCE_NOT_VERIFIED}: een vaste
     * leverancier/groep is aanvaard zonder Prodis-controle omdat de Prodis-leesbron niet geconfigureerd is. Wordt niet
     * bewaard; enkel in het antwoord van dat verzoek. Sinds CD-B2 ook bij de aanmaakstandaard van een koppeling
     * ({@code PRODIS_REFERENCE_NOT_VERIFIED} of {@value #WARNING_PRODIS_REFERENCE_AMBIGUOUS}).
     *
     * @param field het verzoekveld ({@code supplierFixedValue}, {@code supplierGroupFixedValue}, {@code vatCode} of
     *              {@code unitCode})
     */
    public record SetupWarning(String code, String field, String message) {
    }

    /**
     * @param valueMap de vertaalrijen (S8a, additief) in volgorde; leeg als de mapping er geen heeft
     * @param steps    de ketenstappen (S8c, additief) in volgorde; leeg als de mapping er geen heeft
     * @param expectedPosition vanaf hier de overige aanmaakvelden (G2c-1, additief), zodat een mapping vervangen
     *                         kan worden zonder te gissen wat er stond
     */
    public record MappingView(long id, long revisionId, int sequenceNumber, String targetFieldCode,
                              String sourceReference, String valueKind, String criticality,
                              List<ValueMapEntry> valueMap, List<TransformStepEntry> steps,
                              Integer expectedPosition, String fixedValue, String defaultValue, boolean required,
                              Integer maxLength, Integer decimalScale, boolean zeroAllowed, boolean negativeAllowed,
                              String transformKind, String transformConfig) {

        public MappingView {
            valueMap = valueMap == null ? List.of() : List.copyOf(valueMap);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /**
     * @param matchGroup    de EN-groep (S8f, additief), of {@code null} voor een losse rij
     * @param caseSensitive vanaf hier de overige aanmaakvelden (G2c-1, additief)
     */
    public record FilterView(long id, long revisionId, int sequenceNumber, String sourceReference,
                             String operator, String compareValue, String outcome, Integer matchGroup,
                             boolean caseSensitive, boolean trimBeforeCompare, String nullBehaviour,
                             String missingColumnBehaviour) {
    }

    public record FieldCriticalityView(long revisionId, String fieldKey, String criticality) {
    }

    public record LinkView(long id, String code, String name, long definitionId, String supplierCode,
                           String libraryCode, boolean active, String defaultCurrency) {
    }

    public record TaskView(long id, long linkId, String name, String triggerType, boolean active,
                           boolean preventConcurrentRuns) {
    }

    /**
     * Body van {@code PUT /setup/links/{id}/creation-defaults} (CD-B2): vervangt <b>beide</b> codes; blanco of
     * {@code null} wist die code.
     */
    public record SetCreationDefaultsCommand(String vatCode, String unitCode) {
    }

    /**
     * De aanmaakstandaard van een koppeling na het verzoek, met de waarschuwingen van dat verzoek (niet bewaard).
     *
     * @param creationDefaultsOrigin {@code LEGACY}, {@code MANUAL} of {@code null} (nooit gezet)
     */
    public record LinkCreationDefaultsView(long linkId, String defaultVatCode, String defaultUnitCode,
                                           String creationDefaultsOrigin, Instant creationDefaultsUpdatedAt,
                                           String creationDefaultsUpdatedBy, List<SetupWarning> warnings) {

        public LinkCreationDefaultsView {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** Body van {@code PUT /setup/definitions/{id}/overwrite-creation-defaults} (CD-B2); {@code enabled} is verplicht. */
    public record SetOverwriteCreationDefaultsCommand(Boolean enabled) {
    }

    /** De vlag "standaarden overschrijven" van een definitie met de audit van de laatste wijziging. */
    public record DefinitionOverwriteFlagView(long definitionId, boolean overwriteCreationDefaults,
                                              Instant overwriteCreationDefaultsChangedAt,
                                              String overwriteCreationDefaultsChangedBy) {
    }

    /** Het overzicht dat toont welke {@code taskId} bij welke keten hoort. */
    public record Overview(List<OrganisationOverview> sourceOrganisations) {
    }

    public record OrganisationOverview(long id, String code, String name, String type,
                                       List<DefinitionOverview> definitions) {
    }

    public record DefinitionOverview(long id, String code, String name, String usageType,
                                     RevisionView activeRevision, List<RevisionSummary> revisions,
                                     List<LinkOverview> links) {
    }

    public record RevisionSummary(long id, int revisionNumber, String status) {
    }

    public record LinkOverview(long id, String code, String name, String libraryCode, String supplierCode,
                               boolean active, List<TaskView> tasks) {
    }

    private final SourceOrganisationRepository organisations;
    private final ImportDefinitionRepository definitions;
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportFieldCatalogRepository fieldCatalog;
    private final ImportFieldMappingRepository fieldMappings;
    private final ImportFieldValueMapRepository valueMaps;
    private final ImportFieldTransformStepRepository transformSteps;
    private final ImportRecordFilterRepository recordFilters;
    private final ImportRevisionFieldCriticalityRepository fieldCriticalities;
    private final ImportLinkRepository links;
    private final CatalogImportTaskRepository tasks;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkUsageRepository bookmarkUsages;
    private final SourceStateDao sourceStates;
    private final ChainConfigurationChecks checks;
    private final ProdisReferenceService prodis;

    /**
     * Sinds NT-8 lopen de configuratievalidatie (screeningfabrieken) en de DEFINITION-bookmarkcontrole via
     * {@link ChainConfigurationChecks}; de fabrieken en de waarderepository zijn daarom geen parameter meer
     * (enkel Spring bouwt deze service).
     */
    public SetupService(SourceOrganisationRepository organisations, ImportDefinitionRepository definitions,
                        ImportDefinitionRevisionRepository revisions, ImportFieldCatalogRepository fieldCatalog,
                        ImportFieldMappingRepository fieldMappings, ImportRecordFilterRepository recordFilters,
                        ImportRevisionFieldCriticalityRepository fieldCriticalities, ImportLinkRepository links,
                        CatalogImportTaskRepository tasks, ImportDefinitionBookmarkRepository bookmarks,
                        ImportDefinitionBookmarkUsageRepository bookmarkUsages, SourceStateDao sourceStates,
                        ChainConfigurationChecks checks, ProdisReferenceService prodis,
                        ImportFieldValueMapRepository valueMaps, ImportFieldTransformStepRepository transformSteps) {
        this.organisations = organisations;
        this.valueMaps = valueMaps;
        this.transformSteps = transformSteps;
        this.definitions = definitions;
        this.revisions = revisions;
        this.fieldCatalog = fieldCatalog;
        this.fieldMappings = fieldMappings;
        this.recordFilters = recordFilters;
        this.fieldCriticalities = fieldCriticalities;
        this.links = links;
        this.tasks = tasks;
        this.bookmarks = bookmarks;
        this.bookmarkUsages = bookmarkUsages;
        this.sourceStates = sourceStates;
        this.checks = checks;
        this.prodis = prodis;
    }

    // --- Bronorganisatie --------------------------------------------------------------------------

    /**
     * @throws ConflictException        {@code SOURCE_ORGANISATION_CODE_IN_USE}
     * @throws IllegalArgumentException ontbrekende of te lange velden
     */
    public SourceOrganisationView createSourceOrganisation(CreateSourceOrganisationCommand command) {
        String code = SetupInput.requireText(command.code(), "code", MAX_CODE_LENGTH);
        String name = SetupInput.requireText(command.name(), "name", MAX_NAME_LENGTH);
        SourceOrganisationType type = SetupInput.require(command.type(), "type");
        if (organisations.existsByCode(code)) {
            throw sourceOrganisationCodeInUse(code);
        }
        try {
            SourceOrganisation stored = organisations.saveAndFlush(new SourceOrganisation(code, name, type));
            return view(stored);
        } catch (DataIntegrityViolationException violation) {
            // NT-3: een gelijktijdig verzoek met dezelfde code passeerde de controle hierboven ook.
            throw translateCreateConflict(violation, "uk_source_organisation_code",
                    sourceOrganisationCodeInUse(code));
        }
    }

    private static ConflictException sourceOrganisationCodeInUse(String code) {
        return new ConflictException("SOURCE_ORGANISATION_CODE_IN_USE",
                "Source organisation code '" + code + "' already exists");
    }

    // --- Importdefinitie --------------------------------------------------------------------------

    /**
     * @throws NotFoundException        {@code SOURCE_ORGANISATION_NOT_FOUND}
     * @throws ConflictException        {@code DEFINITION_CODE_IN_USE}
     * @throws IllegalArgumentException ontbrekende of te lange velden
     */
    public DefinitionView createDefinition(CreateDefinitionCommand command) {
        return createDefinition(command, ActorIdentity.unverified(DEFAULT_CREATED_BY));
    }

    /**
     * Zoals hierboven, met de geverifieerde identiteit van wie aanmaakt (Fase 5-AUTH, 5A-6): de naam
     * komt in {@code import_definition.created_by} — waar zonder login {@code setup-api}
     * staat — en het subject in {@code created_by_subject}. De Web-laag gebruikt uitsluitend deze
     * overload; het verzoek draagt hier geen actorveld, dus er valt niets te vergelijken.
     */
    public DefinitionView createDefinition(CreateDefinitionCommand command, ActorIdentity actor) {
        String code = SetupInput.requireText(command.code(), "code", MAX_CODE_LENGTH);
        String name = SetupInput.requireText(command.name(), "name", MAX_NAME_LENGTH);
        SourceOrganisation organisation = organisation(command.sourceOrganisationCode());
        if (definitions.findBySourceOrganisationIdAndCode(organisation.getId(), code).isPresent()) {
            throw definitionCodeInUse(code, organisation);
        }
        ImportDefinition definition = new ImportDefinition(organisation, code, name,
                SetupInput.requireText(SetupInput.orDefault(actor.username(), DEFAULT_CREATED_BY), "createdBy", MAX_USER_LENGTH));
        definition.setCreatedBySubject(actor.subject());
        if (command.usageType() != null) {
            definition.setUsageType(command.usageType());
        }
        try {
            return view(definitions.saveAndFlush(definition));
        } catch (DataIntegrityViolationException violation) {
            // NT-3: een gelijktijdig verzoek met dezelfde code passeerde de controle hierboven ook.
            throw translateCreateConflict(violation, "uk_import_definition_code",
                    definitionCodeInUse(code, organisation));
        }
    }

    private static ConflictException definitionCodeInUse(String code, SourceOrganisation organisation) {
        return new ConflictException("DEFINITION_CODE_IN_USE", "Definition code '" + code
                + "' already exists for source organisation '" + organisation.getCode() + "'");
    }

    // --- Revisie ----------------------------------------------------------------------------------

    /**
     * Maakt een nieuwe {@link RevisionStatus#DRAFT}-revisie met het eerstvolgende revisienummer.
     *
     * @throws NotFoundException        {@code DEFINITION_NOT_FOUND}
     * @throws IllegalArgumentException ontbrekende of ongeldige velden
     */
    public RevisionView createRevision(long definitionId, CreateRevisionCommand command) {
        return createRevision(definitionId, command, ActorIdentity.unverified(command.createdBy()));
    }

    /**
     * Zoals hierboven, met de geverifieerde identiteit van wie aanmaakt (Fase 5-AUTH, 5A-6): de naam
     * komt in {@code import_definition_revision.created_by}, het subject in {@code created_by_subject}.
     * De naam uit {@code command.createdBy()} wordt hier <b>niet</b> gebruikt — de Web-laag heeft dat
     * veld al met de aangemelde gebruiker vergeleken (400 {@code ACTOR_FIELD_MISMATCH}) en bewaart
     * altijd de token-spelling.
     */
    public RevisionView createRevision(long definitionId, CreateRevisionCommand command,
                                       ActorIdentity actor) {
        return createRevision(definitionId, command, actor, true);
    }

    /**
     * @param verifyInProdis legacy-config S6: {@code false} enkel voor de legacy-migratie
     *                       ({@code LegacyConfigApplyService}), die de vaste waarden al in haar PREVIEW tegen Prodis
     *                       controleerde (en ze bij een onbereikbare Prodis bewust toelaat, design par. 6)
     */
    RevisionView createRevision(long definitionId, CreateRevisionCommand command, ActorIdentity actor,
                                boolean verifyInProdis) {
        ImportDefinition definition = definitions.findById(definitionId)
                .orElseThrow(() -> new NotFoundException("DEFINITION_NOT_FOUND",
                        "Import definition " + definitionId + " does not exist"));
        IdentityProfileKind identityKind = command.identityProfileKind() == null
                ? IdentityProfileKind.THREE_PART : command.identityProfileKind();
        List<ImportDefinitionRevision> existing =
                revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(definitionId);
        // NT-13: hoogstens één DRAFT per definitie, ook hier (niet enkel in het opvolgerpad). Zelfde 409-code en
        // -tekst als RevisionSuccessorService; de racevariant wordt onderaan op de unieke sleutel vertaald.
        existing.stream().filter(open -> open.getStatus() == RevisionStatus.DRAFT).findFirst()
                .ifPresent(open -> {
                    throw draftAlreadyExists(definitionId, open);
                });
        int revisionNumber = existing.stream()
                .mapToInt(ImportDefinitionRevision::getRevisionNumber).max().orElse(0) + 1;

        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, revisionNumber,
                identityKind, SetupInput.requireText(SetupInput.orDefault(actor.username(), DEFAULT_CREATED_BY), "createdBy",
                MAX_USER_LENGTH));
        revision.setCreatedBySubject(actor.subject());
        // Legacy-config S6 (M3): leverancier en groep uit precies één van kolom of vaste waarde. Zonder vaste waarde
        // exact het gedrag van vóór S6 (SUPPLIER_FIELD_REQUIRED).
        String supplierFixed = SetupInput.optionalText(command.supplierFixedValue(), "supplierFixedValue",
                MAX_IDENTITY_FIXED_VALUE_LENGTH);
        String groupFixed = SetupInput.optionalText(command.supplierGroupFixedValue(), "supplierGroupFixedValue",
                MAX_IDENTITY_FIXED_VALUE_LENGTH);
        revision.setIdentitySupplierField(supplierFixed == null
                ? SetupInput.requireText(command.supplierField(), "supplierField", MAX_FIELD_REFERENCE_LENGTH)
                : SetupInput.optionalText(command.supplierField(), "supplierField", MAX_FIELD_REFERENCE_LENGTH));
        revision.setIdentitySupplierFixedValue(supplierFixed);
        revision.setIdentitySupplierGroupField(groupFixed == null
                ? SetupInput.requireText(command.supplierGroupField(), "supplierGroupField", MAX_FIELD_REFERENCE_LENGTH)
                : SetupInput.optionalText(command.supplierGroupField(), "supplierGroupField",
                        MAX_FIELD_REFERENCE_LENGTH));
        revision.setIdentitySupplierGroupFixedValue(groupFixed);
        RevisionFieldRules.requireOneIdentitySource(revision);
        revision.setIdentityUppercase(Boolean.TRUE.equals(command.identityUppercase()));
        revision.setIdentitySupplierReferenceField(SetupInput.requireText(command.supplierReferenceField(),
                "supplierReferenceField", MAX_FIELD_REFERENCE_LENGTH));
        // De databasecheck ck_import_definition_revision_identity houdt profiel en kortingscodeveld
        // consistent: null (niet gemapt) en "" (expliciet leeg) zijn verschillende toestanden.
        String discountCodeField = SetupInput.optionalText(command.discountCodeField(), "discountCodeField",
                MAX_FIELD_REFERENCE_LENGTH);
        RevisionFieldRules.requireIdentityConsistency(identityKind, discountCodeField);
        revision.setIdentityDiscountCodeField(discountCodeField);
        revision.setRecordBasePriceField(
                SetupInput.requireText(command.basePriceField(), "basePriceField", MAX_FIELD_REFERENCE_LENGTH));
        revision.setRecordDescriptionField(
                SetupInput.optionalText(command.descriptionField(), "descriptionField", MAX_FIELD_REFERENCE_LENGTH));
        revision.setRecordCurrencyField(
                SetupInput.optionalText(command.currencyField(), "currencyField", MAX_FIELD_REFERENCE_LENGTH));
        revision.setStructureDelimiter(SetupInput.requireText(command.delimiter(), "delimiter", 1));
        if (command.quoteChar() != null) {
            // "" betekent hier uitdrukkelijk: deze bron kent geen quoting.
            revision.setStructureQuoteChar(command.quoteChar().isEmpty() ? null
                    : SetupInput.requireText(command.quoteChar(), "quoteChar", 1));
        }
        if (command.charset() != null) {
            revision.setStructureCharset(SetupInput.requireText(command.charset(), "charset", 40));
        }
        if (command.hasHeader() != null) {
            revision.setStructureHasHeader(command.hasHeader());
        }
        if (command.headerLineNumber() != null) {
            revision.setStructureHeaderLineNumber(command.headerLineNumber());
        }
        if (command.fieldReferenceKind() != null) {
            revision.setStructureFieldReferenceKind(
                    SetupInput.requireText(command.fieldReferenceKind(), "fieldReferenceKind", 20));
        }
        revision.setStructureExpectedColumnCount(command.expectedColumnCount());
        if (command.canonicalisationVersion() != null) {
            revision.setRecordCanonicalisationVersion(command.canonicalisationVersion());
        }
        RevisionFieldRules.applyThresholds(revision, command);
        revision.setChangeReason(SetupInput.optionalText(command.changeReason(), "changeReason", MAX_CHANGE_REASON_LENGTH));
        // Bouwstap 5c: dezelfde vier regels als voorheen, nu als één gedeelde berekening. De
        // materialisatiewizard moet exact dezelfde hashes op een afgeleide revisie kunnen zetten; twee
        // kopieën van deze opbouw zouden op termijn uit elkaar lopen (zie RevisionConfigHashes#applyAll).
        RevisionConfigHashes.applyAll(revision);
        List<SetupWarning> warnings = verifyInProdis
                ? verifyFixedIdentityInProdis(revision, revision.getIdentitySupplierFixedValue() != null,
                revision.getIdentitySupplierGroupFixedValue() != null)
                : List.of();
        try {
            return view(revisions.saveAndFlush(revision)).withWarnings(warnings);
        } catch (DataIntegrityViolationException violation) {
            throw translateDraftConflict(violation, definitionId);
        }
    }

    /**
     * NT-13: 409 {@code REVISION_DRAFT_ALREADY_EXISTS} — er staat al een DRAFT open op deze definitie. Gedeeld
     * door {@link #createRevision} en {@code RevisionSuccessorService}, zodat code en tekst nooit uit elkaar lopen.
     */
    static ConflictException draftAlreadyExists(long definitionId, ImportDefinitionRevision openDraft) {
        return new ConflictException("REVISION_DRAFT_ALREADY_EXISTS", "Import definition " + definitionId
                + " already has a DRAFT revision (revision " + openDraft.getRevisionNumber()
                + ", id " + openDraft.getId() + "); finish or activate that one first — two open "
                + "drafts would mean two people are preparing the next configuration side by side");
    }

    /**
     * NT-13: de racevariant — een gelijktijdig verzoek zette tussen controle en insert zelf een DRAFT. De botsing op
     * {@code uk_import_definition_revision_draft} wordt dezelfde 409 (de transactie is afgebroken, het nummer van de
     * andere DRAFT is hier niet meer op te vragen). Elke andere integriteitsfout gaat ongewijzigd door.
     * <p>
     * Ook {@code uk_import_definition_revision_number} telt mee: twee gelijktijdige verzoeken berekenen hetzelfde
     * volgnummer, en PostgreSQL meldt dan die (oudere) sleutel vóór de draft-sleutel. Revisies worden alleen via
     * deze twee paden (aanmaken en opvolger) ingevoegd en beide maken een DRAFT: een nummerbotsing betekent dus
     * altijd dat er gelijktijdig een andere DRAFT bijkwam.
     */
    static RuntimeException translateDraftConflict(DataIntegrityViolationException violation, long definitionId) {
        if (violates(violation, "uk_import_definition_revision_draft")
                || violates(violation, "uk_import_definition_revision_number")) {
            return new ConflictException("REVISION_DRAFT_ALREADY_EXISTS", "Import definition " + definitionId
                    + " already has a DRAFT revision (created at the same moment by another request); finish "
                    + "or activate that one first — two open drafts would mean two people are preparing the "
                    + "next configuration side by side");
        }
        return violation;
    }

    // --- Een DRAFT-revisie wijzigen (endpoint E3, bouwstap S1-X-4) ---------------------------------

    /**
     * Wijzigt de <b>scalaire</b> velden van een {@link RevisionStatus#DRAFT}-revisie (endpoint E3,
     * {@code docs/design/revision-successor-design.md} §5, §6). Een bevroren revisie wordt nooit
     * bijgewerkt (§14.14): daarvoor bestaat de opvolgrevisie
     * ({@link RevisionSuccessorService#createSuccessor}).
     *
     * <h2>Wat hier bewaakt wordt en waarom</h2>
     * De kloonstap zelf is byte-identiek (R-REV-X1); de <b>betekenis</b> van een importdefinitie verandert
     * pas door een wijziging in de DRAFT. Twee wijzigingen zijn daarbij niet zichtbaar gevaarlijk maar wel
     * onomkeerbaar, en die krijgen elk hun eigen blokkade (§5, O3 = beide invoeren):
     * <ul>
     *   <li><b>R-REV-X2 — {@code recordCanonicalisationVersion}.</b> Het versienummer zit vooraan in
     *       {@code identity_hash}. Bestaat er voor enige koppeling van deze definitie al aanvaarde
     *       bronstaat, dan zou elke bestaande aanbieding na deze wijziging als {@code NEW} terugkomen
     *       (massa-CREATE) en is de wijziging niet migreerbaar: 409
     *       {@code REVISION_CANONICALISATION_CHANGE_BLOCKED}. <b>Onvoorwaardelijk</b> — er is geen
     *       bevestigingsveld dat deze blokkade opheft, in tegenstelling tot R-REV-X3 hieronder. Wie deze
     *       versie wil verhogen, doet dat op een koppeling die nog niets aanvaard heeft.</li>
     *   <li><b>R-REV-X3 — identiteitsprofiel en de vier {@code identity*Field}-velden.</b> Zelfde gevolg
     *       (andere canonieke identiteitstekst ⇒ massa-{@code NEW}), maar hier is de wijziging wél legitiem
     *       zolang ze bewust is: zonder {@code acknowledgeIdentityChange = true} 409
     *       {@code IDENTITY_CHANGE_NOT_ACKNOWLEDGED}.</li>
     * </ul>
     * Beide controles gebeuren <b>na</b> het toepassen van de wijzigingen (de vergelijking is er een tussen
     * oud en nieuw) maar <b>vóór</b> het wegschrijven; ze werpen, en de omringende transactie rolt terug.
     * Er blijft dus nooit een half gewijzigde revisie achter: ook de drempelwijziging die in hetzelfde
     * verzoek meekwam, is dan niet opgeslagen.
     *
     * <h2>Wat hier bewust niet gebeurt</h2>
     * <ul>
     *   <li><b>Geen volledige configuratievalidatie.</b> Een DRAFT is een werkversie; een tussenstap mag
     *       tijdelijk onvolledig zijn. De {@code CONFIG_*}-validatie met de screeningfabrieken blijft staan
     *       waar ze hoort: bij {@link #activateRevision(long, ActorIdentity)}, het moment waarop de revisie
     *       echt bruikbaar wordt. Zou ze hier al gelden, dan was een mapping niet meer te vervangen — de
     *       tussentoestand tussen verwijderen en opnieuw toevoegen zou permanent blokkeren.</li>
     *   <li><b>Geen statuswijziging en geen goedkeuring.</b> De revisie blijft DRAFT; activeren blijft een
     *       aparte, ondertekende handeling.</li>
     *   <li><b>Geen eigen {@code *_by}-audit.</b> Er is geen {@code updated_by}-kolom (§7: geen migratie in
     *       S1-X); {@code updated_at} wordt door de entiteit bijgewerkt. Wie de DRAFT wijzigde, is dus niet
     *       apart vastgelegd — wie hem aanmaakte ({@code created_by}) en wie hem activeert
     *       ({@code approved_by}) wel.</li>
     * </ul>
     *
     * @param command {@code null} of een volledig leeg verzoek is toegestaan en wijzigt niets; elk
     *                {@code null}-veld betekent "ongewijzigd" (zie {@link UpdateRevisionCommand})
     * @return dezelfde revisieweergave als {@code createRevision}, {@code successor} en {@code activate}
     * @throws NotFoundException        {@code REVISION_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_EDITABLE} (alleen een DRAFT is bewerkbaar),
     *                                  {@code REVISION_CANONICALISATION_CHANGE_BLOCKED} (R-REV-X2),
     *                                  {@code IDENTITY_CHANGE_NOT_ACKNOWLEDGED} (R-REV-X3)
     * @throws IllegalArgumentException een lege verplichte waarde, een te lange waarde, een negatief
     *                                  percentage of een identiteitsprofiel dat niet bij het
     *                                  kortingscodeveld past
     */
    public RevisionView updateRevision(long revisionId, UpdateRevisionCommand command) {
        return updateRevision(revisionId, command, true);
    }

    /** @param verifyInProdis zie {@link #createRevision(long, CreateRevisionCommand, ActorIdentity, boolean)} */
    RevisionView updateRevision(long revisionId, UpdateRevisionCommand command, boolean verifyInProdis) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        UpdateRevisionCommand request = command == null ? UpdateRevisionCommand.empty() : command;
        RevisionFieldRules.IdentityBefore before = RevisionFieldRules.IdentityBefore.of(revision);

        RevisionFieldRules.applyScalars(revision, request);

        // R-REV-X2 gaat voor: die blokkade is met geen enkele bevestiging te omzeilen, dus ze is het
        // eerste dat de aanroeper hoort te lezen wanneer hij beide wijzigingen in één verzoek stuurt.
        requireCanonicalisationChangeAllowed(revision, before);
        RevisionFieldRules.requireIdentityChangeAcknowledged(revision, before, request);

        // Altijd ná het zetten van alle velden: de hash beschrijft de revisie zoals ze nu is. Drempels en
        // prijsbeleid zitten bewust niet in enige hash (zie de ontdekking in ontwerp §9), dus een verzoek
        // dat enkel drempels wijzigt, laat de vier hashes terecht ongewijzigd.
        RevisionConfigHashes.applyAll(revision);
        // Legacy-config S6: enkel een vaste waarde die (of waarvan de hoofdletterschakelaar) in dit verzoek wijzigt,
        // wordt opnieuw in Prodis nagekeken; een ongewijzigde DRAFT hangt niet van Prodis af.
        List<SetupWarning> warnings = verifyInProdis
                ? verifyFixedIdentityInProdis(revision, before.supplierFixedValueChangedIn(revision),
                before.groupFixedValueChangedIn(revision))
                : List.of();
        return view(revisions.saveAndFlush(revision)).withWarnings(warnings);
    }

    /**
     * Legacy-config S6 ({@code docs/design/legacy-config-bundle-design.md} par. 5 rijen 9/10, par. 6): een vaste
     * leverancier/groep moet in de Prodis-database bestaan als die bron geconfigureerd is. Gecontroleerd wordt de
     * waarde zoals de screening ze gebruikt: in hoofdletters als {@code identity_uppercase} aan staat.
     * <ul>
     *   <li>leverancier: precies één {@code supplier.number}; geen → 400 {@code SUPPLIER_FIXED_VALUE_NOT_IN_PRODIS}, twee
     *       → 400 {@code SUPPLIER_FIXED_VALUE_AMBIGUOUS_IN_PRODIS} (nooit stil de eerste nemen, S5);</li>
     *   <li>groep: een {@code supplier_group.group_name} van de leverancier van deze identiteit (de vaste leverancier,
     *       anders de bronorganisatie van de definitie) → anders 400 {@code SUPPLIER_GROUP_FIXED_VALUE_NOT_IN_PRODIS};</li>
     *   <li>Prodis niet geconfigureerd: aanvaard, met een waarschuwing {@code PRODIS_REFERENCE_NOT_VERIFIED} per waarde;
     *       onbereikbaar: 503 {@code PRODIS_DB_UNAVAILABLE} (zoals de keuzelijsten van S5), er wordt niets bewaard.</li>
     * </ul>
     * Bewaard wordt enkel de businesscode, nooit een Prodis-id.
     */
    private List<SetupWarning> verifyFixedIdentityInProdis(ImportDefinitionRevision revision, boolean supplierChanged,
                                                           boolean groupChanged) {
        String supplierFixed = revision.getIdentitySupplierFixedValue();
        String groupFixed = revision.getIdentitySupplierGroupFixedValue();
        boolean checkSupplier = supplierChanged && supplierFixed != null;
        boolean checkGroup = groupChanged && groupFixed != null;
        if (!checkSupplier && !checkGroup) {
            return List.of();
        }
        boolean uppercase = revision.isIdentityUppercase();
        String supplier = checkSupplier ? effective(supplierFixed, uppercase) : null;
        String group = checkGroup ? effective(groupFixed, uppercase) : null;
        String groupSupplier = !checkGroup ? null : supplierFixed != null ? effective(supplierFixed, uppercase)
                : revision.getImportDefinition().getSourceOrganisation().getCode();
        ProdisReferenceService.FixedIdentityLookup lookup = prodis.verifyFixedIdentity(supplier, groupSupplier);
        if (lookup.availability() == ProdisReferenceService.Availability.NOT_CONFIGURED) {
            List<SetupWarning> warnings = new ArrayList<>(2);
            if (checkSupplier) {
                warnings.add(notVerified("supplierFixedValue", "supplier " + supplier));
            }
            if (checkGroup) {
                warnings.add(notVerified("supplierGroupFixedValue", "supplier group " + group));
            }
            return warnings;
        }
        if (checkSupplier && lookup.suppliers().isEmpty()) {
            throw new BadRequestException("SUPPLIER_FIXED_VALUE_NOT_IN_PRODIS", "supplierFixedValue " + supplier
                    + " does not exist in Prodis (supplier.number)");
        }
        if (checkSupplier && lookup.suppliers().size() > 1) {
            throw new BadRequestException("SUPPLIER_FIXED_VALUE_AMBIGUOUS_IN_PRODIS", "supplierFixedValue "
                    + supplier + " occurs more than once in Prodis (supplier.number is not unique); it is never "
                    + "resolved to one of them silently");
        }
        if (checkGroup && !lookup.hasGroup(group)) {
            throw new BadRequestException("SUPPLIER_GROUP_FIXED_VALUE_NOT_IN_PRODIS", "supplierGroupFixedValue "
                    + group + " is not a supplier group (supplier_group.group_name) of supplier " + groupSupplier
                    + " in Prodis");
        }
        return List.of();
    }

    private static String effective(String fixedValue, boolean uppercase) {
        return uppercase ? fixedValue.toUpperCase(Locale.ROOT) : fixedValue;
    }

    private static SetupWarning notVerified(String field, String what) {
        return new SetupWarning(LegacyConfigTranslator.PRODIS_REFERENCE_NOT_VERIFIED, field, what
                + " is not verified: the Prodis database is not configured");
    }

    /**
     * R-REV-X2 (§5). De bronstaat wordt alleen bevraagd wanneer de versie werkelijk wijzigt: dat is één
     * extra query op een wijziging die zelden voorkomt, en nul query's op elke andere wijziging.
     * <p>
     * <b>Elke wijziging, niet enkel een verhoging.</b> Het ontwerp beschrijft het geval "verhoogd" — dat is
     * het geval dat in de praktijk voorkomt — maar een <i>verlaging</i> heeft exact hetzelfde gevolg: het
     * versienummer staat vooraan in {@code identity_hash}, dus ook 2 → 1 laat elke bestaande aanbieding als
     * {@code NEW} terugkomen. De blokkade sluit daarom beide richtingen af; een uitzondering voor
     * verlagen zou een even onomkeerbare wijziging stil doorlaten.
     */
    private void requireCanonicalisationChangeAllowed(ImportDefinitionRevision revision,
                                                      RevisionFieldRules.IdentityBefore before) {
        int after = revision.getRecordCanonicalisationVersion();
        if (after == before.canonicalisationVersion()) {
            return;
        }
        long definitionId = revision.getImportDefinition().getId();
        if (!sourceStates.existsForImportDefinition(definitionId)) {
            return;
        }
        throw new ConflictException("REVISION_CANONICALISATION_CHANGE_BLOCKED", "Revision "
                + revision.getId() + " cannot change recordCanonicalisationVersion from "
                + before.canonicalisationVersion() + " to " + after + ": import definition " + definitionId
                + " already has accepted source state. The version is part of the offer identity hash, so "
                + "every existing offer would come back as NEW (a mass creation) and there is no migration "
                + "for that. Nothing was saved");
    }

    /**
     * Zet de revisie op {@link RevisionStatus#ACTIVE} en de vorige actieve revisie van dezelfde
     * definitie op {@link RevisionStatus#SUPERSEDED}.
     * <p>
     * De oude revisie wordt eerst weggeschreven <b>en geflushed</b>: {@code active_marker} is
     * {@code TRUE} bij ACTIVE en {@code null} daarbuiten, en {@code uk_import_definition_revision_active}
     * zou anders binnen dezelfde transactie twee actieve revisies zien.
     * <p>
     * De volledige configuratie wordt hier nog eens gevalideerd met exact dezelfde fabrieken als de
     * screening: een revisie die pas bij de eerste levering blijkt te blokkeren, is onbruikbaar.
     * <p>
     * <b>Gelijktijdige activatie (S1-X-2, 3a).</b> Twee transacties die elk een DRAFT van dezelfde
     * definitie activeren, botsen op {@code uk_import_definition_revision_active}. Dat gaf tot nu toe een
     * 500; sinds deze bouwstap wordt die botsing vertaald naar 409
     * {@code REVISION_ACTIVATION_CONFLICT} — zelfde patroon als
     * {@code TemplateMaterialisationService.translate} voor haar drie unieke sleutels. De hele transactie
     * rolt terug: er is dan niets geactiveerd en niets op {@code SUPERSEDED} gezet.
     * <p>
     * <b>Laagversienummers (S1-X-2, 3b).</b> Activeren is het moment waarop
     * {@code access_version}/{@code structure_version}/{@code record_rules_version} betekenis krijgen —
     * zie {@link #applyLayerVersions}.
     *
     * @throws NotFoundException        {@code REVISION_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_ACTIVATABLE} (al ACTIVE of niet meer DRAFT),
     *                                  {@code CONFIG_REQUIRED_BOOKMARK_MISSING} (een verplichte
     *                                  DEFINITION-bookmark van deze revisie is niet ingevuld),
     *                                  {@code REVISION_ACTIVATION_CONFLICT} (gelijktijdige activatie)
     * @throws IllegalArgumentException een {@code CONFIG_*}-fout in de configuratie
     */
    public RevisionView activateRevision(long revisionId, String approvedBy) {
        return activateRevision(revisionId, ActorIdentity.unverified(approvedBy));
    }

    /**
     * Zoals hierboven, met de geverifieerde identiteit van wie activeert (Fase 5-AUTH, 5A-6): de naam
     * komt in {@code import_definition_revision.approved_by}, het subject in
     * {@code approved_by_subject}. De Web-laag gebruikt uitsluitend deze overload.
     */
    public RevisionView activateRevision(long revisionId, ActorIdentity actor) {
        ImportDefinitionRevision revision = revision(revisionId);
        if (revision.getStatus() == RevisionStatus.ACTIVE) {
            throw new ConflictException("REVISION_NOT_ACTIVATABLE",
                    "Revision " + revisionId + " is already active");
        }
        if (revision.getStatus() != RevisionStatus.DRAFT) {
            throw new ConflictException("REVISION_NOT_ACTIVATABLE", "Revision " + revisionId + " is "
                    + revision.getStatus() + "; only a DRAFT revision is activated by this setup API");
        }
        // NT-8: configuratievalidatie (400) en verplichte DEFINITION-bookmarks (409) uit de gedeelde implementatie,
        // in dezelfde volgorde als vroeger; de eerste bevinding wordt geworpen.
        ChainConfigurationChecks.throwFirst(checks.activationProblems(revision));
        long definitionId = revision.getImportDefinition().getId();
        String approver = SetupInput.requireText(SetupInput.orDefault(actor.username(), DEFAULT_CREATED_BY), "approvedBy",
                MAX_USER_LENGTH);
        try {
            Optional<ImportDefinitionRevision> current =
                    revisions.findByImportDefinitionIdAndStatus(definitionId, RevisionStatus.ACTIVE);
            current.ifPresent(active -> {
                active.setStatus(RevisionStatus.SUPERSEDED);
                revisions.saveAndFlush(active);
            });
            revision.setStatus(RevisionStatus.ACTIVE);
            revision.setApprovedAt(Instant.now());
            revision.setApprovedBy(approver);
            // Naam en subject altijd samen: ck_import_definition_revision_approved_subject weigert een
            // subject zonder naam.
            revision.setApprovedBySubject(actor.subject());
            applyLayerVersions(revision);
            return view(revisions.saveAndFlush(revision));
        } catch (DataIntegrityViolationException violation) {
            throw translateActivation(violation);
        }
    }

    /**
     * 3b van {@code docs/design/revision-successor-design.md} §3: bij activatie krijgen de drie
     * laagversienummers voor het eerst betekenis. Per laag geldt {@code versie = bron.versie + 1} zodra
     * de laaghash van de bronrevisie verschilt, en {@code versie = bron.versie} wanneer die laag
     * inhoudelijk onveranderd bleef. Zo leest een mens aan het versienummer af of er in die laag
     * werkelijk iets veranderd is, in plaats van aan drie kolommen die altijd op 1 stonden.
     * <p>
     * <b>Alleen binnen dezelfde definitie.</b> Draagt de revisie geen herkomstrevisie, of hoort die
     * herkomstrevisie bij een <i>andere</i> definitie, dan blijft alles staan. Dat tweede geval is de
     * gematerialiseerde revisie 1: haar {@code based_on_revision_id} wijst naar een sjabloonrevisie van
     * een andere definitie, en de versiereeks van dat sjabloon is niet de hare
     * (zie {@code TemplateMaterialisationService.copyRevision}: "revisie 1 van een nieuwe definitie, niet
     * de voortzetting van de versiereeks van het sjabloon").
     * <p>
     * Additief en risicoloos: vandaag leest geen enkele productiecode deze drie kolommen, en de
     * bestaande paden ({@link #createRevision} zet geen herkomstrevisie, materialisatie verwijst naar een
     * andere definitie) blijven onaangeroerd op 1.
     */
    private static void applyLayerVersions(ImportDefinitionRevision revision) {
        ImportDefinitionRevision source = revision.getBasedOnRevision();
        if (source == null || !source.getImportDefinition().getId()
                .equals(revision.getImportDefinition().getId())) {
            return;
        }
        revision.setAccessVersion(nextLayerVersion(source.getAccessVersion(),
                source.getAccessConfigHash(), revision.getAccessConfigHash()));
        revision.setStructureVersion(nextLayerVersion(source.getStructureVersion(),
                source.getStructureConfigHash(), revision.getStructureConfigHash()));
        revision.setRecordRulesVersion(nextLayerVersion(source.getRecordRulesVersion(),
                source.getRecordRulesConfigHash(), revision.getRecordRulesConfigHash()));
    }

    /** Gelijke laaghash = dezelfde laagversie; elke afwijking is precies één stap. */
    private static int nextLayerVersion(int sourceVersion, String sourceHash, String hash) {
        return Objects.equals(sourceHash, hash) ? sourceVersion : sourceVersion + 1;
    }

    /**
     * 3a van {@code docs/design/revision-successor-design.md} §3: de unieke sleutel op de actieve revisie
     * naar haar 409-code, hetzelfde patroon als {@code TemplateMaterialisationService.translate}. Elke
     * andere integriteitsfout blijft ongewijzigd doorgaan — een fout stil als "race" bestempelen zou de
     * echte oorzaak verbergen.
     */
    private static RuntimeException translateActivation(DataIntegrityViolationException violation) {
        String text = String.valueOf(violation.getMostSpecificCause().getMessage())
                .toLowerCase(Locale.ROOT);
        if (text.contains("uk_import_definition_revision_active")) {
            return new ConflictException("REVISION_ACTIVATION_CONFLICT", "Another revision of this import "
                    + "definition was activated at the same moment; nothing was activated and nothing was "
                    + "superseded. Read the revision list again before retrying");
        }
        return violation;
    }

    /**
     * NT-3: de racevariant van de controle "bestaat deze sleutel al?" bij het aanmaken. Botst het
     * wegschrijven op precies de verwachte unieke sleutel {@code constraint}, dan is dat hetzelfde feit als
     * de controle vooraf al meldt en wordt het {@code conflict} — met dezelfde 409-code, nooit een 500.
     * Zelfde patroon als {@link #translateActivation} en {@code TemplateMaterialisationService.translate}:
     * er wordt uitsluitend op constraintnaam gematcht, en elke andere integriteitsfout (een andere sleutel,
     * een foreign key, een check) gaat ongewijzigd door — een fout stil als "bestaat al" bestempelen zou de
     * echte oorzaak verbergen. De omringende transactie rolt in beide gevallen terug.
     */
    private static RuntimeException translateCreateConflict(DataIntegrityViolationException violation,
                                                            String constraint, ConflictException conflict) {
        return violates(violation, constraint) ? conflict : violation;
    }

    /** {@code true} wanneer de meest specifieke oorzaak de unieke sleutel {@code constraint} noemt. */
    private static boolean violates(DataIntegrityViolationException violation, String constraint) {
        String text = String.valueOf(violation.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        return text.contains(constraint);
    }

    // --- Mappings, filters en kritiek-overrules ---------------------------------------------------

    /**
     * @throws NotFoundException        {@code REVISION_NOT_FOUND}, {@code FIELD_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_EDITABLE} (alleen een DRAFT is bewerkbaar)
     * @throws IllegalArgumentException een {@code CONFIG_*}-fout in de configuratie
     */
    public MappingView addMapping(long revisionId, CreateMappingCommand command) {
        return addMapping(revisionId, command, ActorIdentity.unverified(command.createdBy()));
    }

    /**
     * Zoals hierboven, met de geverifieerde identiteit van wie de mapping toevoegt (Fase 5-AUTH,
     * 5A-6): {@code import_field_mapping.created_by} plus {@code created_by_subject}. De naam uit
     * {@code command.createdBy()} wordt hier niet gebruikt; de Web-laag heeft ze al vergeleken.
     */
    public MappingView addMapping(long revisionId, CreateMappingCommand command, ActorIdentity actor) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        String targetFieldCode = SetupInput.requireText(command.targetFieldCode(), "targetFieldCode", 60);
        ImportFieldCatalogEntry target = fieldCatalog.findById(targetFieldCode)
                .orElseThrow(() -> new NotFoundException("FIELD_NOT_FOUND",
                        "Target field '" + targetFieldCode + "' does not exist in the field catalogue"));
        List<ImportFieldMapping> existing = fieldMappings.findByRevisionIdWithTargetField(revisionId);
        int sequenceNumber = command.sequenceNumber() != null ? command.sequenceNumber()
                : existing.stream().mapToInt(ImportFieldMapping::getSequenceNumber).max().orElse(0) + 1;
        // Zonder deze twee controles zou uk_import_field_mapping_target/-_sequence pas bij het flushen
        // toeslaan: een databasefout in plaats van een leesbaar antwoord.
        if (existing.stream().anyMatch(row -> targetFieldCode.equals(row.getTargetField().getCode()))) {
            throw new ConflictException("MAPPING_TARGET_IN_USE", "Target field '" + targetFieldCode
                    + "' is already mapped in revision " + revisionId
                    + "; a target field has exactly one source");
        }
        if (existing.stream().anyMatch(row -> row.getSequenceNumber() == sequenceNumber)) {
            throw new ConflictException("MAPPING_SEQUENCE_IN_USE", "Sequence number " + sequenceNumber
                    + " is already used in revision " + revisionId);
        }
        requireCriticalityAllowed(target, command.criticality());
        // Type, eigenaar, identiteitsklasse, prijscomponent en referentietype komen uit de catalogus.
        ImportFieldMapping mapping = new ImportFieldMapping(revision, sequenceNumber, target,
                SetupInput.orDefault(command.valueKind(), FieldValueKind.SOURCE_FIELD), target.getDataType(),
                target.getDefaultOwner(), target.getIdentityClass());
        mapping.setPriceComponentCode(target.getPriceComponentCode());
        mapping.setReferenceType(target.getReferenceType());
        mapping.setSourceReference(
                SetupInput.optionalText(command.sourceReference(), "sourceReference", MAX_FIELD_REFERENCE_LENGTH));
        mapping.setExpectedPosition(command.expectedPosition());
        mapping.setFixedValue(SetupInput.optionalText(command.fixedValue(), "fixedValue", 500));
        mapping.setDefaultValue(SetupInput.optionalText(command.defaultValue(), "defaultValue", 500));
        mapping.setRequired(Boolean.TRUE.equals(command.required()));
        mapping.setMaxLength(command.maxLength());
        mapping.setDecimalScale(command.decimalScale());
        mapping.setZeroAllowed(Boolean.TRUE.equals(command.zeroAllowed()));
        mapping.setNegativeAllowed(Boolean.TRUE.equals(command.negativeAllowed()));
        if (command.transformKind() != null) {
            mapping.setTransformKind(command.transformKind());
        }
        mapping.setTransformConfig(SetupInput.optionalText(command.transformConfig(), "transformConfig", 1000));
        mapping.setCriticality(command.criticality());
        mapping.setCreatedBy(SetupInput.orDefault(actor.username(), DEFAULT_CREATED_BY));
        mapping.setCreatedBySubject(actor.subject());
        List<ValueMapEntry> valueMap = valueMapInput(command.valueMap());
        List<TransformStepEntry> steps = stepInput(command.steps());
        ImportFieldMapping stored = fieldMappings.saveAndFlush(mapping);
        // S8a: de vertaalrijen vóór de configuratievalidatie, zodat die de mapping volledig ziet (één transactie).
        List<ImportFieldValueMap> rows = new ArrayList<>(valueMap.size());
        for (int i = 0; i < valueMap.size(); i++) {
            ImportFieldValueMap row = new ImportFieldValueMap(stored, i + 1, valueMap.get(i).source(),
                    valueMap.get(i).target());
            row.setCreatedBy(stored.getCreatedBy());
            row.setCreatedBySubject(stored.getCreatedBySubject());
            rows.add(row);
        }
        valueMaps.saveAll(rows);
        valueMaps.flush();
        // S8c: idem voor de ketenstappen, genummerd in de volgorde van het verzoek.
        List<ImportFieldTransformStep> stepRows = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            ImportFieldTransformStep row = new ImportFieldTransformStep(stored, i + 1, steps.get(i).kind(),
                    steps.get(i).config());
            row.setCreatedBy(stored.getCreatedBy());
            row.setCreatedBySubject(stored.getCreatedBySubject());
            stepRows.add(row);
        }
        transformSteps.saveAll(stepRows);
        transformSteps.flush();
        validateConfiguration(revision);
        return new MappingView(stored.getId(), revisionId, stored.getSequenceNumber(), targetFieldCode,
                stored.getSourceReference(), stored.getValueKind().name(), stored.getCriticality().name(),
                valueMap, steps, stored.getExpectedPosition(), stored.getFixedValue(), stored.getDefaultValue(),
                stored.isRequired(), stored.getMaxLength(), stored.getDecimalScale(), stored.isZeroAllowed(),
                stored.isNegativeAllowed(), stored.getTransformKind().name(), stored.getTransformConfig());
    }

    /**
     * EB1: een referentieveld is altijd kritiek (par. 15.1). Een uitdrukkelijke {@code NON_CRITICAL} wordt vóór het
     * flushen geweigerd (400 {@code CRITICALITY_INVALID}) in plaats van op {@code
     * ck_import_field_mapping_reference_critical} te stranden (500); {@code null} betekent de standaard.
     */
    private static void requireCriticalityAllowed(ImportFieldCatalogEntry target, Criticality criticality) {
        if (criticality == Criticality.NON_CRITICAL && !Criticality.changeableForMapping(target.getReferenceType())) {
            throw new BadRequestException(SetupInput.fieldCode("criticality", "INVALID"), "Target field '"
                    + target.getCode() + "' is a reference field (" + target.getReferenceType() + ") and is always "
                    + Criticality.CRITICAL + "; it can never be " + Criticality.NON_CRITICAL);
        }
    }

    /**
     * S8c: de ketenstappen uit het verzoek. Weigert vóór het schrijven wat de databasechecks van 024-1 anders als 500
     * zouden geven: meer dan {@value ImportFieldTransformStep#MAX_STEPS} stappen, een lege stap, een stap zonder soort,
     * een keten als stap en een te lange configuratie. De configuratie wordt getrimd zoals {@code transformConfig}
     * ({@link SetupInput#optionalText}); leeg wordt {@code null}. Of de keten past, beslist de configuratievalidatie.
     */
    private static List<TransformStepEntry> stepInput(List<TransformStepEntry> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        if (steps.size() > ImportFieldTransformStep.MAX_STEPS) {
            throw new BadRequestException(SetupInput.fieldCode("steps", "TOO_MANY"), "steps holds at most "
                    + ImportFieldTransformStep.MAX_STEPS + " steps (substring, prefix/suffix, one value operation) but "
                    + "has " + steps.size());
        }
        List<TransformStepEntry> entries = new ArrayList<>(steps.size());
        for (TransformStepEntry step : steps) {
            if (step == null) {
                throw new BadRequestException(SetupInput.fieldCode("steps", "INVALID"),
                        "steps must not contain an empty entry");
            }
            FieldTransformKind kind = SetupInput.require(step.kind(), "stepKind");
            if (kind == FieldTransformKind.CHAIN) {
                throw new BadRequestException(SetupInput.fieldCode("stepKind", "INVALID"),
                        "a step of a chain is never a CHAIN itself");
            }
            entries.add(new TransformStepEntry(kind, SetupInput.optionalText(step.config(), "stepConfig",
                    ImportFieldTransformStep.MAX_CONFIG_LENGTH)));
        }
        return List.copyOf(entries);
    }

    /**
     * S8a: de vertaalrijen uit het verzoek, getrimd zoals {@code values=} ({@code MappingSettings.table}). Weigert
     * vóór het schrijven wat de databasechecks van 023-1 anders als 500 zouden geven: een lege of te lange bronwaarde,
     * een ontbrekende of te lange doelwaarde, een dubbele bronwaarde. Of de rijen bij de transformatie passen (soort
     * MAP, niet samen met {@code values=}, hoofdletterconflicten) beslist de configuratievalidatie.
     */
    private static List<ValueMapEntry> valueMapInput(List<ValueMapEntry> valueMap) {
        if (valueMap == null || valueMap.isEmpty()) {
            return List.of();
        }
        List<ValueMapEntry> entries = new ArrayList<>(valueMap.size());
        Set<String> sources = new HashSet<>();
        for (ValueMapEntry entry : valueMap) {
            if (entry == null) {
                throw new BadRequestException(SetupInput.fieldCode("valueMap", "INVALID"),
                        "valueMap must not contain an empty entry");
            }
            String source = SetupInput.requireText(entry.source(), "valueMapSource",
                    ImportFieldValueMap.MAX_VALUE_LENGTH);
            if (entry.target() == null) {
                throw new BadRequestException(SetupInput.fieldCode("valueMapTarget", "REQUIRED"),
                        "valueMapTarget must not be null; use an empty text to translate '" + source
                                + "' to empty");
            }
            String target = entry.target().trim();
            if (target.length() > ImportFieldValueMap.MAX_VALUE_LENGTH) {
                throw new BadRequestException(SetupInput.fieldCode("valueMapTarget", "TOO_LONG"),
                        "valueMapTarget must be at most " + ImportFieldValueMap.MAX_VALUE_LENGTH + " characters");
            }
            if (!sources.add(source)) {
                throw new BadRequestException(SetupInput.fieldCode("valueMapSource", "DUPLICATE"),
                        "valueMap translates source value '" + source + "' more than once");
            }
            entries.add(new ValueMapEntry(source, target));
        }
        return List.copyOf(entries);
    }

    /**
     * @throws NotFoundException        {@code REVISION_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_EDITABLE}
     * @throws IllegalArgumentException een {@code CONFIG_*}-fout in de configuratie
     */
    public FilterView addFilter(long revisionId, CreateFilterCommand command) {
        return addFilter(revisionId, command, ActorIdentity.unverified(command.createdBy()));
    }

    /**
     * Zoals hierboven, met de geverifieerde identiteit van wie de filter toevoegt (Fase 5-AUTH, 5A-6):
     * {@code import_record_filter.created_by} plus {@code created_by_subject}.
     */
    public FilterView addFilter(long revisionId, CreateFilterCommand command, ActorIdentity actor) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        List<ImportRecordFilter> existing =
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId);
        int sequenceNumber = command.sequenceNumber() != null ? command.sequenceNumber()
                : existing.stream().mapToInt(ImportRecordFilter::getSequenceNumber).max().orElse(0) + 1;
        if (existing.stream().anyMatch(row -> row.getSequenceNumber() == sequenceNumber)) {
            throw new ConflictException("FILTER_SEQUENCE_IN_USE", "Filter sequence number " + sequenceNumber
                    + " is already used in revision " + revisionId + "; the evaluation order would be "
                    + "undefined");
        }
        Integer matchGroup = command.matchGroup();
        if (matchGroup != null && matchGroup <= 0) {
            throw new BadRequestException(SetupInput.fieldCode("matchGroup", "INVALID"),
                    "matchGroup must be a positive number");
        }
        String sourceReference =
                SetupInput.requireText(command.sourceReference(), "sourceReference", MAX_FIELD_REFERENCE_LENGTH);
        FilterOperator operator = SetupInput.require(command.operator(), "operator");
        // S8f: in een EN-groep betekent EQUALS '' "de kolom is leeg" (legacy PSIMPEXCL: '' is letterlijk leeg).
        boolean emptyAllowed = matchGroup != null && operator == FilterOperator.EQUALS
                && command.compareValue() != null && command.compareValue().isBlank();
        String compareValue = emptyAllowed ? ""
                : SetupInput.requireText(command.compareValue(), "compareValue", 500);
        FilterOutcome outcome = SetupInput.require(command.outcome(), "outcome");
        ImportRecordFilter filter = new ImportRecordFilter(revision, sequenceNumber, sourceReference, operator,
                compareValue, outcome);
        if (command.caseSensitive() != null) {
            filter.setCaseSensitive(command.caseSensitive());
        }
        if (command.trimBeforeCompare() != null) {
            filter.setTrimBeforeCompare(command.trimBeforeCompare());
        }
        if (command.nullBehaviour() != null) {
            filter.setNullBehaviour(command.nullBehaviour());
        }
        if (command.missingColumnBehaviour() != null) {
            filter.setMissingColumnBehaviour(command.missingColumnBehaviour());
        }
        if (matchGroup != null && (filter.getOutcome() != FilterOutcome.EXCLUDE
                || filter.getNullBehaviour() != FilterNullBehaviour.COMPARE_AS_EMPTY)) {
            // Dezelfde regel als de configuratievalidatie (M4g) en changeset 025-1, voor het schrijven: anders zou de
            // databasecheck de fout geven in plaats van een leesbare melding.
            throw new BadRequestException(ImportMappingConfigFactory.CODE_FILTER_INVALID, "Record filter "
                    + sequenceNumber + " belongs to match group " + matchGroup + " but is " + filter.getOutcome()
                    + " with null behaviour " + filter.getNullBehaviour() + "; every rule of a match group is "
                    + "EXCLUDE with nullBehaviour COMPARE_AS_EMPTY");
        }
        filter.setMatchGroup(matchGroup);
        filter.setCreatedBy(SetupInput.orDefault(actor.username(), DEFAULT_CREATED_BY));
        filter.setCreatedBySubject(actor.subject());
        ImportRecordFilter stored = recordFilters.saveAndFlush(filter);
        validateConfiguration(revision);
        return new FilterView(stored.getId(), revisionId, stored.getSequenceNumber(),
                stored.getSourceReference(), stored.getOperator().name(), stored.getCompareValue(),
                stored.getOutcome().name(), stored.getMatchGroup(), stored.isCaseSensitive(),
                stored.isTrimBeforeCompare(), stored.getNullBehaviour().name(),
                stored.getMissingColumnBehaviour().name());
    }

    /**
     * @throws NotFoundException        {@code REVISION_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_EDITABLE}
     * @throws IllegalArgumentException onbekende veldsleutel of een {@code CONFIG_*}-fout (bv. een
     *                                  identiteitsveld dat niet kritiek zou zijn)
     */
    public FieldCriticalityView addFieldCriticality(long revisionId, CreateFieldCriticalityCommand command) {
        return addFieldCriticality(revisionId, command, ActorIdentity.unverified(command.createdBy()));
    }

    /**
     * Zoals hierboven, met de geverifieerde identiteit van wie de kritiek-overrule vastlegt (Fase
     * 5-AUTH, 5A-6): {@code import_revision_field_criticality.created_by} plus
     * {@code created_by_subject}.
     */
    public FieldCriticalityView addFieldCriticality(long revisionId, CreateFieldCriticalityCommand command,
                                                    ActorIdentity actor) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        String fieldKey = SetupInput.requireText(command.fieldKey(), "fieldKey", 60);
        RevisionCriticalityField field = RevisionCriticalityField.byKey(fieldKey)
                .orElseThrow(() -> new BadRequestException(SetupInput.fieldCode("fieldKey", "INVALID"), "fieldKey '"
                        + fieldKey + "' is not a field of the revision itself; known keys are "
                        + List.of(RevisionCriticalityField.values())));
        Criticality criticality = SetupInput.require(command.criticality(), "criticality");
        // Dezelfde regels als de databasecheck en de configuratievalidatie, maar vóór het flushen: een
        // constraintfout zou hier een 500 opleveren in plaats van een leesbaar antwoord.
        if (field.isIdentity() && criticality == Criticality.NON_CRITICAL) {
            throw new BadRequestException(SetupInput.fieldCode("criticality", "INVALID"), "Field '" + fieldKey
                    + "' is part of the offer identity and can never be " + Criticality.NON_CRITICAL);
        }
        if (fieldCriticalities.findByDefinitionRevisionId(revisionId).stream()
                .anyMatch(existing -> fieldKey.equals(existing.getFieldKey()))) {
            throw new ConflictException("FIELD_CRITICALITY_IN_USE", "Criticality of '" + fieldKey
                    + "' is already configured for revision " + revisionId
                    + "; a field has exactly one criticality");
        }
        ImportRevisionFieldCriticality row = new ImportRevisionFieldCriticality(revisionId, fieldKey,
                criticality);
        row.setCreatedBy(SetupInput.orDefault(actor.username(), DEFAULT_CREATED_BY));
        row.setCreatedBySubject(actor.subject());
        ImportRevisionFieldCriticality stored = fieldCriticalities.saveAndFlush(row);
        validateConfiguration(revision);
        return new FieldCriticalityView(revisionId, stored.getFieldKey(), stored.getCriticality().name());
    }

    /**
     * Zet of wijzigt de kritiek-overrule van één revisie-eigen veld van een {@link RevisionStatus#DRAFT}-revisie
     * (EB2, beslissingslog 2026-10-23 "Editor-API-gaten", G5). Zonder rij wordt ze aangemaakt; dezelfde waarde nog
     * eens is een no-op (de audit blijft ongewijzigd). Bij een andere waarde wordt de rij <b>vervangen</b> (verwijderen,
     * flushen, opnieuw toevoegen): {@code created_at/by/subject} betekenen "wie zette deze waarde, wanneer"; er is
     * geen historiek van vorige waarden (precedent {@link #replaceMapping}).
     *
     * @throws NotFoundException   {@code REVISION_NOT_FOUND}
     * @throws ConflictException   {@code REVISION_NOT_EDITABLE}; {@code REVISION_EDIT_CONFLICT} wanneer dezelfde rij
     *                             gelijktijdig door een ander gewijzigd of verwijderd werd (herlaad en probeer opnieuw)
     * @throws BadRequestException {@code FIELD_KEY_INVALID}, {@code CRITICALITY_REQUIRED}, {@code CRITICALITY_INVALID}
     *                             (identiteitsveld naar NON_CRITICAL) of een {@code CONFIG_*}-fout; dan is er niets bewaard
     */
    public FieldCriticalityView setFieldCriticality(long revisionId, String fieldKey,
                                                    SetFieldCriticalityCommand command, ActorIdentity actor) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        RevisionCriticalityField field = knownCriticalityField(fieldKey);
        Criticality criticality = SetupInput.require(command == null ? null : command.criticality(), "criticality");
        if (field.isIdentity() && criticality == Criticality.NON_CRITICAL) {
            throw new BadRequestException(SetupInput.fieldCode("criticality", "INVALID"), "Field '" + fieldKey
                    + "' is part of the offer identity and can never be " + Criticality.NON_CRITICAL);
        }
        ImportRevisionFieldCriticality.Key key = new ImportRevisionFieldCriticality.Key(revisionId, fieldKey);
        try {
            ImportRevisionFieldCriticality existing = fieldCriticalities.findById(key).orElse(null);
            if (existing != null && existing.getCriticality() == criticality) {
                return new FieldCriticalityView(revisionId, fieldKey, criticality.name());
            }
            if (existing != null) {
                fieldCriticalities.delete(existing);
                fieldCriticalities.flush();
            }
            ImportRevisionFieldCriticality row = new ImportRevisionFieldCriticality(revisionId, fieldKey,
                    criticality);
            row.setCreatedBy(SetupInput.orDefault(actor.username(), DEFAULT_CREATED_BY));
            row.setCreatedBySubject(actor.subject());
            fieldCriticalities.saveAndFlush(row);
        } catch (OptimisticLockingFailureException stale) {
            throw criticalityEditConflict(fieldKey);
        } catch (DataIntegrityViolationException violation) {
            throw violates(violation, "pk_import_revision_field_criticality")
                    ? criticalityEditConflict(fieldKey) : violation;
        }
        validateConfiguration(revision);
        return new FieldCriticalityView(revisionId, fieldKey, criticality.name());
    }

    /**
     * Wist de kritiek-overrule van één veld van een {@link RevisionStatus#DRAFT}-revisie: het veld valt terug op de
     * standaard van de soort (EB2).
     *
     * @throws NotFoundException {@code REVISION_NOT_FOUND}, {@code FIELD_CRITICALITY_NOT_FOUND} (geen rij voor dit veld)
     * @throws ConflictException {@code REVISION_NOT_EDITABLE}; {@code REVISION_EDIT_CONFLICT} wanneer de rij
     *                           gelijktijdig door een ander gewijzigd of verwijderd werd
     */
    public void deleteFieldCriticality(long revisionId, String fieldKey) {
        editableRevision(revisionId);
        knownCriticalityField(fieldKey);
        ImportRevisionFieldCriticality existing = fieldCriticalities
                .findById(new ImportRevisionFieldCriticality.Key(revisionId, fieldKey))
                .orElseThrow(() -> new NotFoundException("FIELD_CRITICALITY_NOT_FOUND", "Revision " + revisionId
                        + " has no criticality override for '" + fieldKey + "'; the default applies"));
        try {
            fieldCriticalities.delete(existing);
            fieldCriticalities.flush();
        } catch (OptimisticLockingFailureException stale) {
            throw criticalityEditConflict(fieldKey);
        }
    }

    private static RevisionCriticalityField knownCriticalityField(String fieldKey) {
        return RevisionCriticalityField.byKey(fieldKey)
                .orElseThrow(() -> new BadRequestException(SetupInput.fieldCode("fieldKey", "INVALID"), "fieldKey '"
                        + fieldKey + "' is not a field of the revision itself; known keys are "
                        + List.of(RevisionCriticalityField.values())));
    }

    private static ConflictException criticalityEditConflict(String fieldKey) {
        return new ConflictException("REVISION_EDIT_CONFLICT", "The criticality of '" + fieldKey
                + "' was changed by someone else at the same time; reload and try again");
    }

    // --- Een geërfde kindrij verwijderen (endpoint E4, bouwstap S1-X-4) ----------------------------

    /**
     * Verwijdert één veldmapping van een {@link RevisionStatus#DRAFT}-revisie (endpoint E4,
     * {@code docs/design/revision-successor-design.md} §6).
     * <p>
     * Een opvolgrevisie erft alle mappings van haar bron (§2). Zonder dit pad kon een geërfde mapping
     * alleen nog toegevoegd, nooit verwijderd worden — wie een kolom niet meer wil overnemen, moest de
     * volledige revisie opnieuw opbouwen.
     * <p>
     * <b>Ná het verwijderen wordt de bookmarkdeclaratie van deze revisie hercontroleerd</b>
     * ({@link #requireBookmarkDeclarationsStillResolve}): een {@code FIELD_MAPPING_FIXED_VALUE}-usage die
     * naar deze mapping wees, zou anders een invulveld achterlaten dat nergens meer landt.
     *
     * @throws NotFoundException {@code REVISION_NOT_FOUND}, {@code MAPPING_NOT_FOUND} (onbekend of niet van
     *                           deze revisie — een id van een andere revisie is hier geen geldig doel)
     * @throws ConflictException {@code REVISION_NOT_EDITABLE}, of een {@code CONFIG_BOOKMARK_*}-code uit de
     *                           hercontrole; in dat geval is er niets verwijderd
     */
    public void deleteMapping(long revisionId, long mappingId) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        ImportFieldMapping mapping = fieldMappings.findById(mappingId)
                .filter(row -> row.getDefinitionRevision().getId().equals(revision.getId()))
                .orElseThrow(() -> new NotFoundException("MAPPING_NOT_FOUND", "Field mapping " + mappingId
                        + " does not exist in revision " + revisionId));
        // S8a: eerst de vertaalrijen (geen cascade in het schema, changeset 023-1), als entiteit verwijderd.
        valueMaps.deleteAll(valueMaps.findByMappingIdOrderBySequenceNumberAsc(mapping.getId()));
        // S8c: idem voor de ketenstappen (geen cascade in 024-1).
        transformSteps.deleteAll(transformSteps.findByMappingIdOrderByStepNumberAsc(mapping.getId()));
        fieldMappings.delete(mapping);
        fieldMappings.flush();
        requireBookmarkDeclarationsStillResolve(revisionId);
    }

    /**
     * Vervangt één veldmapping van een {@link RevisionStatus#DRAFT}-revisie door de mapping uit {@code command}
     * (G2c-1): verwijderen (met vertaalrijen en ketenstappen als entiteit) en opnieuw toevoegen in dezelfde
     * transactie. De nieuwe mapping behoudt het volgnummer van de oude; een {@code sequenceNumber} in het verzoek
     * wordt genegeerd. De nieuwe mapping krijgt een nieuw id. De bookmarkhercontrole van {@link #deleteMapping}
     * gebeurt na het toevoegen, zodat een usage die op dezelfde doelveldcode steunt geldig blijft.
     *
     * @throws NotFoundException        {@code REVISION_NOT_FOUND}, {@code MAPPING_NOT_FOUND}, {@code FIELD_NOT_FOUND}
     * @throws ConflictException        {@code REVISION_NOT_EDITABLE} of een {@code CONFIG_BOOKMARK_*}-code; er is
     *                                  dan niets gewijzigd
     * @throws IllegalArgumentException een {@code CONFIG_*}-fout; er is niets gewijzigd
     */
    public MappingView replaceMapping(long revisionId, long mappingId, CreateMappingCommand command,
                                      ActorIdentity actor) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        ImportFieldMapping mapping = fieldMappings.findById(mappingId)
                .filter(row -> row.getDefinitionRevision().getId().equals(revision.getId()))
                .orElseThrow(() -> new NotFoundException("MAPPING_NOT_FOUND", "Field mapping " + mappingId
                        + " does not exist in revision " + revisionId));
        // Vóór het verwijderen: een ongeldige kritiek laat de bestaande mapping ongemoeid.
        fieldCatalog.findById(command.targetFieldCode() == null ? "" : command.targetFieldCode().trim())
                .ifPresent(target -> requireCriticalityAllowed(target, command.criticality()));
        int sequenceNumber = mapping.getSequenceNumber();
        valueMaps.deleteAll(valueMaps.findByMappingIdOrderBySequenceNumberAsc(mapping.getId()));
        transformSteps.deleteAll(transformSteps.findByMappingIdOrderByStepNumberAsc(mapping.getId()));
        fieldMappings.delete(mapping);
        fieldMappings.flush();
        CreateMappingCommand replacement = new CreateMappingCommand(command.targetFieldCode(),
                command.sourceReference(), sequenceNumber, command.valueKind(), command.expectedPosition(),
                command.fixedValue(), command.defaultValue(), command.required(), command.maxLength(),
                command.decimalScale(), command.zeroAllowed(), command.negativeAllowed(), command.transformKind(),
                command.transformConfig(), command.criticality(), command.createdBy(), command.valueMap(),
                command.steps());
        MappingView view = addMapping(revisionId, replacement, actor);
        requireBookmarkDeclarationsStillResolve(revisionId);
        return view;
    }

    /**
     * Verwijdert één recordfilter van een {@link RevisionStatus#DRAFT}-revisie (endpoint E4); zelfde
     * regels en dezelfde hercontrole als {@link #deleteMapping}, hier voor een
     * {@code RECORD_FILTER_COMPARE_VALUE}-usage die naar het volgnummer van dit filter wees.
     *
     * @throws NotFoundException {@code REVISION_NOT_FOUND}, {@code FILTER_NOT_FOUND}
     * @throws ConflictException {@code REVISION_NOT_EDITABLE}, of een {@code CONFIG_BOOKMARK_*}-code
     */
    public void deleteFilter(long revisionId, long filterId) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        ImportRecordFilter filter = recordFilters.findById(filterId)
                .filter(row -> row.getDefinitionRevision().getId().equals(revision.getId()))
                .orElseThrow(() -> new NotFoundException("FILTER_NOT_FOUND", "Record filter " + filterId
                        + " does not exist in revision " + revisionId));
        recordFilters.delete(filter);
        recordFilters.flush();
        requireBookmarkDeclarationsStillResolve(revisionId);
    }

    /**
     * Vervangt één recordfilter van een {@link RevisionStatus#DRAFT}-revisie door het filter uit {@code command}
     * (EB3, beslissingslog 2026-10-23 "Editor-API-gaten", G2): verwijderen, flushen en opnieuw toevoegen in dezelfde
     * transactie. Het nieuwe filter krijgt een nieuw id maar behoudt het volgnummer van het oude (een
     * {@code sequenceNumber} in het verzoek wordt genegeerd); de bookmarkhercontrole gebeurt na het toevoegen, zodat
     * een {@code RECORD_FILTER_COMPARE_VALUE}-usage op dit volgnummer geldig blijft. Een gelijktijdige wijziging van
     * dezelfde rij geeft {@code REVISION_EDIT_CONFLICT} (zoals EB2).
     *
     * @throws NotFoundException   {@code REVISION_NOT_FOUND}, {@code FILTER_NOT_FOUND} (ook een filter van een andere
     *                             revisie)
     * @throws ConflictException   {@code REVISION_NOT_EDITABLE}, {@code CONFIG_BOOKMARK_*}, {@code REVISION_EDIT_CONFLICT};
     *                             er is dan niets gewijzigd
     * @throws BadRequestException veldcodes, {@code MATCH_GROUP_INVALID}, {@code CONFIG_FILTER_INVALID}; niets gewijzigd
     */
    public FilterView replaceFilter(long revisionId, long filterId, CreateFilterCommand command,
                                    ActorIdentity actor) {
        ImportDefinitionRevision revision = editableRevision(revisionId);
        ImportRecordFilter filter = recordFilters.findById(filterId)
                .filter(row -> row.getDefinitionRevision().getId().equals(revision.getId()))
                .orElseThrow(() -> new NotFoundException("FILTER_NOT_FOUND", "Record filter " + filterId
                        + " does not exist in revision " + revisionId));
        int sequenceNumber = filter.getSequenceNumber();
        CreateFilterCommand replacement = new CreateFilterCommand(sequenceNumber, command.sourceReference(),
                command.operator(), command.compareValue(), command.outcome(), command.caseSensitive(),
                command.trimBeforeCompare(), command.nullBehaviour(), command.missingColumnBehaviour(),
                command.createdBy(), command.matchGroup());
        try {
            recordFilters.delete(filter);
            recordFilters.flush();
            FilterView view = addFilter(revisionId, replacement, actor);
            requireBookmarkDeclarationsStillResolve(revisionId);
            return view;
        } catch (OptimisticLockingFailureException stale) {
            throw filterEditConflict(sequenceNumber);
        } catch (DataIntegrityViolationException violation) {
            throw violates(violation, "uk_import_record_filter_sequence")
                    ? filterEditConflict(sequenceNumber) : violation;
        } catch (ConflictException inUse) {
            // Het volgnummer was vrij na onze delete: is het nu bezet, dan zette een gelijktijdige PUT het opnieuw.
            throw "FILTER_SEQUENCE_IN_USE".equals(inUse.getCode()) ? filterEditConflict(sequenceNumber) : inUse;
        }
    }

    private static ConflictException filterEditConflict(int sequenceNumber) {
        return new ConflictException("REVISION_EDIT_CONFLICT", "Record filter " + sequenceNumber
                + " was changed by someone else at the same time; reload and try again");
    }

    /**
     * Toetst de bookmarkdeclaratie van deze revisie opnieuw met dezelfde
     * {@link BookmarkDeclarations#findProblems} als {@code TemplateBookmarkService} (bij declareren) en
     * {@code TemplateMaterialisationService} (defensief bij materialiseren) — één implementatie, nu drie
     * aanroepplaatsen.
     * <p>
     * <b>Waarom na een verwijdering.</b> Een usage verwijst naar een doel in déze revisie: een doelveldcode
     * (mapping) of een filtervolgnummer. Verdwijnt dat doel, dan is de declaratie stil onbruikbaar
     * geworden: het invulveld blijft in het scherm staan en de ingevulde waarde landt nergens. Het eerste
     * gevonden probleem wordt een 409 met zijn eigen bestaande {@code CONFIG_BOOKMARK_*}-code en de
     * verwijdering rolt terug — er is bewust geen nieuwe foutcode voor, want het probleem is exact hetzelfde
     * als bij het declareren.
     * <p>
     * <b>Alle bevindingen tellen mee, ook een die er al stond.</b> {@code findProblems} is een toets op de
     * hele declaratie, niet op het verschil. Een revisie die al met een onbruikbare declaratie rondliep,
     * blokkeert dus ook op een verwijdering die daar niets mee te maken heeft. Dat is dezelfde defensieve
     * keuze als bij materialisatie: de declaratie moet eerst kloppen.
     * <p>
     * De bookmarks worden <b>eerst</b> geladen en daarna hun usages: {@code findProblems} groepeert op
     * objectidentiteit, en binnen één persistentiecontext is {@code usage.getBookmark()} dan dezelfde
     * instantie als de rij in {@code declared}.
     */
    private void requireBookmarkDeclarationsStillResolve(long revisionId) {
        List<ImportDefinitionBookmark> declared =
                bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(revisionId);
        if (declared.isEmpty()) {
            return;
        }
        List<ImportDefinitionBookmarkUsage> allUsages = new ArrayList<>();
        for (ImportDefinitionBookmark bookmark : declared) {
            allUsages.addAll(bookmarkUsages.findByBookmarkId(bookmark.getId()));
        }
        Set<String> mappedTargetFieldCodes = fieldMappings.findByRevisionIdWithTargetField(revisionId).stream()
                .map(mapping -> mapping.getTargetField().getCode())
                .collect(Collectors.toSet());
        Set<Integer> filterSequenceNumbers =
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId).stream()
                        .map(ImportRecordFilter::getSequenceNumber)
                        .collect(Collectors.toSet());
        List<BookmarkDeclarations.Problem> problems = BookmarkDeclarations.findProblems(declared, allUsages,
                mappedTargetFieldCodes, filterSequenceNumbers);
        if (!problems.isEmpty()) {
            BookmarkDeclarations.Problem problem = problems.get(0);
            throw new ConflictException(problem.code(), problem.message()
                    + "; nothing was deleted from revision " + revisionId);
        }
    }

    // --- Koppeling en taak -------------------------------------------------------------------------

    /**
     * @throws NotFoundException        {@code DEFINITION_NOT_FOUND}, {@code SOURCE_ORGANISATION_NOT_FOUND}
     * @throws ConflictException        {@code LINK_CODE_IN_USE}, {@code LINK_SCOPE_IN_USE}
     * @throws IllegalArgumentException ontbrekende of te lange velden
     */
    public LinkView createLink(CreateLinkCommand command) {
        Long definitionId = SetupInput.require(command.definitionId(), "definitionId");
        ImportDefinition definition = definitions.findById(definitionId)
                .orElseThrow(() -> new NotFoundException("DEFINITION_NOT_FOUND",
                        "Import definition " + definitionId + " does not exist"));
        String code = SetupInput.requireText(command.code(), "code", MAX_CODE_LENGTH);
        String name = SetupInput.requireText(command.name(), "name", MAX_NAME_LENGTH);
        String libraryCode = SetupInput.requireText(command.libraryCode(), "libraryCode", MAX_LIBRARY_CODE_LENGTH);
        SourceOrganisation supplier = organisation(command.supplierCode());
        if (links.findByCode(code).isPresent()) {
            throw linkCodeInUse(code);
        }
        boolean scopeTaken = links.findByImportDefinitionId(definitionId).stream()
                .anyMatch(existing -> existing.getLibraryCode().equals(libraryCode)
                        && existing.getSupplierOrganisation().getId().equals(supplier.getId()));
        if (scopeTaken) {
            throw linkScopeInUse(supplier, libraryCode);
        }
        String defaultCurrency = command.defaultCurrency();
        if (defaultCurrency != null && defaultCurrency.isBlank()) {
            defaultCurrency = null;
        } else if (defaultCurrency != null && !LINK_CURRENCY_SHAPE.matcher(defaultCurrency).matches()) {
            // Dezelfde vormregel als PriceRules: exact drie hoofdletters, nooit upper-casen of trimmen.
            throw new BadRequestException("LINK_CURRENCY_INVALID", "defaultCurrency '" + defaultCurrency
                    + "' is not an ISO 4217 currency code (exactly three capital letters)");
        }
        ImportLink link = new ImportLink(code, name, definition, supplier, libraryCode);
        link.setDefaultCurrency(defaultCurrency);
        link.setLibrarySearchSupplierCode(
                SetupInput.optionalText(command.librarySearchSupplierCode(), "librarySearchSupplierCode", MAX_CODE_LENGTH));
        try {
            return view(links.saveAndFlush(link));
        } catch (DataIntegrityViolationException violation) {
            // NT-3: een gelijktijdig verzoek passeerde de controles hierboven ook. Twee sleutels, elk met
            // hun eigen bestaande code; een botsing op de code gaat voor, zoals in de controle vooraf.
            if (violates(violation, "uk_import_link_code")) {
                throw linkCodeInUse(code);
            }
            throw translateCreateConflict(violation, "uk_import_link_scope", linkScopeInUse(supplier, libraryCode));
        }
    }

    // --- Aanmaakstandaard en vlag "standaarden overschrijven" (CD-B2) -------------------------------------------------

    /**
     * Zet of wist de aanmaakstandaard (btw-code en eenheid) van een koppeling (CD-B2; beslissingslog 2026-10-23
     * "Aanmaakstandaard per koppeling: uitwerking (CD)"). Het verzoek vervangt <b>beide</b> codes; blanco wordt
     * {@code null} (wissen mag); waarden worden getrimd en daarna exact (hoofdlettergevoelig) in Prodis vergeleken.
     * Controle in Prodis volgens het S6-patroon ({@link ProdisReferenceService#verifyCreationDefaults}):
     * <ul>
     *   <li>niet gevonden onder de actieve rijen → 400 {@value #CODE_PRODIS_VAT_CODE_NOT_FOUND} /
     *       {@value #CODE_PRODIS_UNIT_NOT_FOUND}, er wordt niets bewaard;</li>
     *   <li>meer dan één actieve rij met die code → bewaard, met waarschuwing {@value #WARNING_PRODIS_REFERENCE_AMBIGUOUS}
     *       (de code is een businesscode; welke rij P8 later gebruikt, beslist P8 - nooit stil hier);</li>
     *   <li>Prodis niet geconfigureerd → bewaard, met waarschuwing {@code PRODIS_REFERENCE_NOT_VERIFIED} per code;</li>
     *   <li>Prodis onbereikbaar → 503 {@code PRODIS_DB_UNAVAILABLE}, er wordt niets bewaard.</li>
     * </ul>
     * Elk geslaagd verzoek zet de herkomst op {@link CreationDefaultsOrigin#MANUAL} en de audit (naam, subject, tijdstip)
     * op deze wijziging, ook als de waarden gelijk blijven (een expliciete menselijke handeling); {@code import_link
     * .updated_at} schuift mee. De Prodis-controle gebeurt enkel voor een gevraagde (niet-lege) code.
     *
     * @throws NotFoundException   {@code LINK_NOT_FOUND}
     * @throws BadRequestException {@code VAT_CODE_TOO_LONG}, {@code UNIT_CODE_TOO_LONG},
     *                             {@value #CODE_PRODIS_VAT_CODE_NOT_FOUND}, {@value #CODE_PRODIS_UNIT_NOT_FOUND}
     * @throws ProdisReferenceUnavailableException 503
     */
    public LinkCreationDefaultsView setLinkCreationDefaults(long linkId, SetCreationDefaultsCommand command,
                                                            ActorIdentity actor) {
        ImportLink link = links.findById(linkId)
                .orElseThrow(() -> new NotFoundException("LINK_NOT_FOUND", "Import link " + linkId + " does not exist"));
        SetCreationDefaultsCommand request = command == null ? new SetCreationDefaultsCommand(null, null) : command;
        String vatCode = SetupInput.optionalText(request.vatCode(), "vatCode", MAX_CREATION_DEFAULT_CODE_LENGTH);
        String unitCode = SetupInput.optionalText(request.unitCode(), "unitCode", MAX_CREATION_DEFAULT_CODE_LENGTH);
        String updatedBy = SetupInput.requireText(actor.username(), "updatedBy", MAX_USER_LENGTH);

        ProdisReferenceService.CreationDefaultsCheck check = prodis.verifyCreationDefaults(vatCode, unitCode);
        List<SetupWarning> warnings = new ArrayList<>(2);
        creationDefaultOutcome(check.vat(), "vatCode", "VAT code " + vatCode, CODE_PRODIS_VAT_CODE_NOT_FOUND,
                "vat_code.code", warnings);
        creationDefaultOutcome(check.unit(), "unitCode", "unit " + unitCode, CODE_PRODIS_UNIT_NOT_FOUND,
                "unit_of_measure.code", warnings);

        link.setCreationDefaults(vatCode, unitCode, CreationDefaultsOrigin.MANUAL, updatedBy, actor.subject(),
                Instant.now());
        ImportLink saved = links.saveAndFlush(link);
        return new LinkCreationDefaultsView(saved.getId(), saved.getDefaultVatCode(), saved.getDefaultUnitCode(),
                saved.getCreationDefaultsOrigin() == null ? null : saved.getCreationDefaultsOrigin().name(),
                saved.getCreationDefaultsUpdatedAt(), saved.getCreationDefaultsUpdatedBy(), warnings);
    }

    /** Vertaalt de Prodis-uitkomst van één code: 400 bij niet gevonden, anders hoogstens een waarschuwing. */
    private static void creationDefaultOutcome(ProdisReferenceService.CodeMatch match, String field, String what,
                                               String notFoundCode, String prodisColumn, List<SetupWarning> warnings) {
        switch (match) {
            case NOT_FOUND -> throw new BadRequestException(notFoundCode, field + ": " + what
                    + " is not an active " + prodisColumn + " in Prodis; nothing was saved");
            case AMBIGUOUS -> warnings.add(new SetupWarning(WARNING_PRODIS_REFERENCE_AMBIGUOUS, field, what
                    + " occurs more than once among the active rows in Prodis (" + prodisColumn + " is not unique)"));
            case NOT_CONFIGURED -> warnings.add(notVerified(field, what));
            case FOUND, NOT_REQUESTED -> {
                // Niets te melden.
            }
        }
    }

    /**
     * Zet de vlag "standaarden overschrijven" van een importdefinitie (CD-B2; beslissingslog 2026-10-23 "Vlag
     * 'standaarden overschrijven' op de importdefinitie") en legt de audit van deze wijziging vast. Bij een gedeelde
     * definitie geldt de vlag voor alle koppelingen eronder. Raakt geen revisie, hash of readiness.
     *
     * @throws NotFoundException   {@code DEFINITION_NOT_FOUND}
     * @throws BadRequestException {@code ENABLED_REQUIRED}
     */
    public DefinitionOverwriteFlagView setOverwriteCreationDefaults(long definitionId,
                                                                    SetOverwriteCreationDefaultsCommand command,
                                                                    ActorIdentity actor) {
        ImportDefinition definition = definitions.findById(definitionId)
                .orElseThrow(() -> new NotFoundException("DEFINITION_NOT_FOUND",
                        "Import definition " + definitionId + " does not exist"));
        boolean enabled = SetupInput.require(command == null ? null : command.enabled(), "enabled");
        String changedBy = SetupInput.requireText(actor.username(), "changedBy", MAX_USER_LENGTH);
        definition.setOverwriteCreationDefaults(enabled, changedBy, actor.subject(), Instant.now());
        ImportDefinition saved = definitions.saveAndFlush(definition);
        return new DefinitionOverwriteFlagView(saved.getId(), saved.isOverwriteCreationDefaults(),
                saved.getOverwriteCreationDefaultsChangedAt(), saved.getOverwriteCreationDefaultsChangedBy());
    }

    private static ConflictException linkCodeInUse(String code) {
        return new ConflictException("LINK_CODE_IN_USE", "Import link code '" + code + "' already exists");
    }

    private static ConflictException linkScopeInUse(SourceOrganisation supplier, String libraryCode) {
        return new ConflictException("LINK_SCOPE_IN_USE", "This definition already has a link for supplier '"
                + supplier.getCode() + "' and library '" + libraryCode + "'");
    }

    /**
     * @throws NotFoundException        {@code LINK_NOT_FOUND}
     * @throws ConflictException        {@code TASK_NAME_IN_USE}
     * @throws IllegalArgumentException ontbrekende of te lange velden
     */
    public TaskView createTask(CreateTaskCommand command) {
        Long linkId = SetupInput.require(command.linkId(), "linkId");
        ImportLink link = links.findById(linkId)
                .orElseThrow(() -> new NotFoundException("LINK_NOT_FOUND",
                        "Import link " + linkId + " does not exist"));
        String name = SetupInput.requireText(command.name(), "name", MAX_NAME_LENGTH);
        if (tasks.findByImportLinkIdAndName(linkId, name).isPresent()) {
            throw taskNameInUse(name, linkId);
        }
        CatalogImportTask task = new CatalogImportTask(link, name, TaskTriggerType.MANUAL);
        if (command.preventConcurrentRuns() != null) {
            task.setPreventConcurrentRuns(command.preventConcurrentRuns());
        }
        try {
            return view(tasks.saveAndFlush(task));
        } catch (DataIntegrityViolationException violation) {
            // NT-3: een gelijktijdig verzoek met dezelfde naam passeerde de controle hierboven ook.
            throw translateCreateConflict(violation, "uk_catalog_import_task_name", taskNameInUse(name, linkId));
        }
    }

    private static ConflictException taskNameInUse(String name, long linkId) {
        return new ConflictException("TASK_NAME_IN_USE",
                "Task '" + name + "' already exists for import link " + linkId);
    }

    // --- Overzicht ---------------------------------------------------------------------------------

    /** Alles wat er geconfigureerd staat, met de id's die de upload nodig heeft. */
    @Transactional(readOnly = true)
    public Overview overview() {
        List<OrganisationOverview> rows = organisations.findAll().stream()
                .sorted((left, right) -> left.getCode().compareTo(right.getCode()))
                .map(organisation -> new OrganisationOverview(organisation.getId(), organisation.getCode(),
                        organisation.getName(), organisation.getOrganisationType().name(),
                        definitionOverviews(organisation)))
                .toList();
        return new Overview(rows);
    }

    private List<DefinitionOverview> definitionOverviews(SourceOrganisation organisation) {
        return definitions.findBySourceOrganisationId(organisation.getId()).stream()
                .map(definition -> new DefinitionOverview(definition.getId(), definition.getCode(),
                        definition.getDescription(), definition.getUsageType().name(),
                        revisions.findByImportDefinitionIdAndStatus(definition.getId(), RevisionStatus.ACTIVE)
                                .map(SetupService::view).orElse(null),
                        revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(definition.getId()).stream()
                                .map(revision -> new RevisionSummary(revision.getId(),
                                        revision.getRevisionNumber(), revision.getStatus().name()))
                                .toList(),
                        linkOverviews(definition)))
                .toList();
    }

    private List<LinkOverview> linkOverviews(ImportDefinition definition) {
        return links.findByImportDefinitionId(definition.getId()).stream()
                .map(link -> new LinkOverview(link.getId(), link.getCode(), link.getName(),
                        link.getLibraryCode(), link.getSupplierOrganisation().getCode(), link.isActive(),
                        tasks.findAll().stream()
                                .filter(task -> task.getImportLink().getId().equals(link.getId()))
                                .map(SetupService::view)
                                .toList()))
                .toList();
    }

    // --- Validatie en vertaling ---------------------------------------------------------------------

    /**
     * Valideert de volledige configuratie met dezelfde fabrieken als de screening. Een
     * {@code ScreeningBlockedException} is hier geen leveringsblokkade maar een ongeldige aanvraag:
     * ze wordt vertaald naar een 400 met de {@code CONFIG_*}-code in de boodschap (en sinds NT-3 als
     * code), en de omringende transactie rolt terug.
     * <p>
     * Sinds NT-8 staat de validatie zelf in {@link ChainConfigurationChecks#configurationProblem} (gedeeld
     * met de gereedheidscontrole); het blokkeerpunt van de verplichte DEFINITION-bookmarks bij het activeren
     * in {@link ChainConfigurationChecks#definitionBookmarkProblem}. Code, status en tekst zijn ongewijzigd.
     */
    private void validateConfiguration(ImportDefinitionRevision revision) {
        checks.configurationProblem(revision).ifPresent(problem -> {
            throw problem.failure();
        });
    }

    private ImportDefinitionRevision revision(long revisionId) {
        return revisions.findById(revisionId)
                .orElseThrow(() -> new NotFoundException("REVISION_NOT_FOUND",
                        "Definition revision " + revisionId + " does not exist"));
    }

    /** Een bevroren revisie wordt nooit bijgewerkt (§14.14): enkel een DRAFT is bewerkbaar. */
    private ImportDefinitionRevision editableRevision(long revisionId) {
        ImportDefinitionRevision revision = revision(revisionId);
        if (revision.getStatus() != RevisionStatus.DRAFT) {
            throw new ConflictException("REVISION_NOT_EDITABLE", "Revision " + revisionId + " is "
                    + revision.getStatus() + "; a frozen revision is never changed, make a new revision");
        }
        return revision;
    }

    private SourceOrganisation organisation(String code) {
        String wanted = SetupInput.requireText(code, "sourceOrganisationCode", MAX_CODE_LENGTH);
        return organisations.findByCode(wanted)
                .orElseThrow(() -> new NotFoundException("SOURCE_ORGANISATION_NOT_FOUND",
                        "Source organisation '" + wanted + "' does not exist"));
    }

    // --- Omzetting naar antwoorden --------------------------------------------------------------------

    private static SourceOrganisationView view(SourceOrganisation organisation) {
        return new SourceOrganisationView(organisation.getId(), organisation.getCode(), organisation.getName(),
                organisation.getOrganisationType().name(), organisation.isActive());
    }

    private static DefinitionView view(ImportDefinition definition) {
        return new DefinitionView(definition.getId(), definition.getCode(), definition.getDescription(),
                definition.getUsageType().name(), definition.getSourceOrganisation().getId(),
                definition.getSourceOrganisation().getCode());
    }

    /**
     * Package-private sinds bouwstap S1-X-2: {@link RevisionSuccessorService} antwoordt met exact
     * dezelfde {@link RevisionView} als {@code createRevision} en {@code activate} op dezelfde
     * controller (revision-successor-design.md §6, E2: "201 + revisieweergave"). Eén opbouw, zodat de
     * drie endpoints niet uiteen kunnen lopen. Naam noch signatuur veranderde.
     */
    static RevisionView view(ImportDefinitionRevision revision) {
        return new RevisionView(revision.getId(), revision.getImportDefinition().getId(),
                revision.getRevisionNumber(), revision.getStatus().name(),
                revision.getIdentityProfileKind().name(), revision.getStructureDelimiter(),
                revision.isStructureHasHeader(), revision.getStructureFieldReferenceKind(),
                revision.getRecordCanonicalisationVersion(), revision.getIdentitySupplierField(),
                revision.getIdentitySupplierGroupField(), revision.getIdentitySupplierReferenceField(),
                revision.getIdentityDiscountCodeField(), revision.getRecordBasePriceField(),
                revision.getRecordDescriptionField(), revision.getRecordCurrencyField(),
                revision.getCreationThresholdSharePercent(), revision.getMaxCriticalSharePercent(),
                revision.getMaxRejectedSharePercent(), revision.getBulkIncidentSharePercent(),
                revision.getIdentitySupplierFixedValue(), revision.getIdentitySupplierGroupFixedValue(),
                revision.isIdentityUppercase(), List.of());
    }

    private static LinkView view(ImportLink link) {
        return new LinkView(link.getId(), link.getCode(), link.getName(),
                link.getImportDefinition().getId(), link.getSupplierOrganisation().getCode(),
                link.getLibraryCode(), link.isActive(), link.getDefaultCurrency());
    }

    private static TaskView view(CatalogImportTask task) {
        return new TaskView(task.getId(), task.getImportLink().getId(), task.getName(),
                task.getTriggerType().name(), task.isActive(), task.isPreventConcurrentRuns());
    }
}
