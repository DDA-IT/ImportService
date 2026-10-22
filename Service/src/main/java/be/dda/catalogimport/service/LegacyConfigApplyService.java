package be.dda.catalogimport.service;

import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_CREATE;
import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_NONE;
import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_REPOINT;
import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_REUSE;
import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_SUCCESSOR_DRAFT;
import static be.dda.catalogimport.service.LegacyConfigReport.ACTION_UNCHANGED;
import static be.dda.catalogimport.service.LegacyConfigReport.BLOCKING;
import static be.dda.catalogimport.service.LegacyConfigReport.MIGRATED;
import static be.dda.catalogimport.service.LegacyConfigReport.WARNING;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
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
import be.dda.catalogimport.dao.LegacyConfigDefinitionOriginRepository;
import be.dda.catalogimport.dao.LegacyConfigDetachmentRepository;
import be.dda.catalogimport.dao.LegacyConfigOriginRepository;
import be.dda.catalogimport.dao.LegacyConfigReceiptRepository;
import be.dda.catalogimport.dao.LegacyConfigRevisionOriginRepository;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierCatalogRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierGroupRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierRow;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.LegacyConfigDefinitionOrigin;
import be.dda.catalogimport.domain.LegacyConfigDetachment;
import be.dda.catalogimport.domain.LegacyConfigOrigin;
import be.dda.catalogimport.domain.LegacyConfigReceipt;
import be.dda.catalogimport.domain.LegacyConfigReceiptStatus;
import be.dda.catalogimport.domain.LegacyConfigRevisionOrigin;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.service.LegacyConfigReport.Item;
import be.dda.catalogimport.service.LegacyConfigReport.LibraryCandidate;
import be.dda.catalogimport.service.LegacyConfigReport.Planned;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedCredential;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedDefinition;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedDetach;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedLink;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedOrganisation;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedTask;
import be.dda.catalogimport.service.LegacyConfigReport.PlannedTemplate;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedFilter;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedMapping;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedRevision;
import be.dda.catalogimport.service.LegacyConfigTranslator.Translation;
import be.dda.catalogimport.service.ProdisReferenceService.Availability;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyLookup;
import be.dda.catalogimport.service.support.ConfigFinding;
import be.dda.catalogimport.service.support.RevisionCopier;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PREVIEW en APPLY van één legacy-config-ontvangst, slice S2 van {@code docs/design/legacy-config-bundle-design.md}
 * (par. 2, 4, 5 en 8); contract {@code docs/design/legacy-config-bundle-v1.md}; beslissingslog 2026-10-20 (M1-M3,
 * "Legacy-config S1: idempotentie", "Legacy-config S2: bibliotheekcode, opvolgende DRAFT, Separator, aannames").
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>PREVIEW schrijft niets</b> en geeft het rapport ({@link LegacyConfigReport}) plus een
 *       {@code previewFingerprint}: SHA-256 over het canonieke rapport en de id/{@code updated_at} (en status/inhoudshash)
 *       van elke geraakte bestaande rij. PREVIEW = APPLY-resultaat.</li>
 *   <li><b>APPLY</b> (één transactie, rijslot op de ontvangst): enkel op {@code RECEIVED}
 *       ({@value #CODE_NOT_APPLICABLE} anders, dus ook bij een tweede APPLY); reden verplicht
 *       ({@value #CODE_REASON_REQUIRED}); de fingerprint moet die van het rapport van nu zijn ({@value #CODE_STALE});
 *       een geblokkeerd rapport wordt nooit toegepast ({@value #CODE_BLOCKED}). Dan: bronorganisatie find-or-create →
 *       eigen definitie (nieuw, opvolgende DRAFT of ongewijzigd) → koppeling → MANUAL-taak → herkomstrijen (020-2,
 *       020-3, 020-4) → ontvangst {@code APPLIED}. <b>APPLY activeert nooit</b> (enige uitzondering: de kopie van de
 *       ACTIVE revisie bij loskoppelen, G2a).</li>
 *   <li><b>Credential (S4):</b> {@code planned.credential} toont de {@code credentialRef} van de herkomst en de
 *       {@code secretOutcome} van deze ontvangst, nooit een waarde. APPLY koppelt geen verbindingsprofiel of
 *       leveringsconfiguratie (L5: hostsleutel verplicht); het item {@code CONNECTION_NOT_MIGRATED} geeft het voorstel
 *       (host, poort, map, login, {@code credentialRef}).</li>
 *   <li><b>Nieuwe interface:</b> {@code OWN_DEFINITION} met de legacy-code (of {@code definitionCodeOverride}),
 *       revisie 1 (DRAFT, canonicalisatieversie 2) - sinds S7 de gedeelde definitie van een sjabloon, of enkel een
 *       koppeling op een bestaande gedeelde definitie (zie "Gedeelde definities" hieronder) -, koppeling {@code LEGACY-<interface>} met de door de mens
 *       gegeven {@code libraryCode} (nooit het ruwe {@code ArtImp_BibNr}; zonder: {@code LIBRARY_CODE_NOT_RESOLVED}),
 *       taak "Legacy &lt;interface&gt;".</li>
 *   <li><b>Bekende interface:</b> koppeling en taak worden nooit gewijzigd (een afwijking is
 *       {@code LINK_DIFFERS_NOT_CHANGED}; enige uitzondering: loskoppelen, G2a hieronder). Levert de ontvangst exact de inhoud die de laatste migratie schreef:
 *       {@code DEFINITION_UNCHANGED}, geen nieuwe revisie, ontvangst wel {@code APPLIED}. Anders: een open DRAFT
 *       blokkeert ({@code OPEN_DRAFT_EXISTS}); wijkt de laatste revisie inhoudelijk af van de laatst gemigreerde
 *       ({@code MANUAL_CHANGES_SINCE_MIGRATION}) dan ook; anders een opvolgende DRAFT via
 *       {@link RevisionSuccessorService} (letterlijke kopie) waarin APPLY de door legacy bepaalde velden, de
 *       vertaalbare mappings en de filters overschrijft. APPLY geldt als R-REV-X3-bevestiging; de preview toont
 *       {@code IDENTITY_CHANGE}. De ACTIVE revisie wordt nooit aangeraakt.</li>
 *   <li><b>Conflicten:</b> een handmatige definitie is nooit een migratiedoel ({@code DEFINITION_CODE_CONFLICT_MANUAL},
 *       op te lossen met {@code definitionCodeOverride}; idem {@value #TEMPLATE_CODE_CONFLICT_MANUAL} voor de
 *       sjablooncode); een bestaande koppelingscode of -scope ({@code LINK_CODE_CONFLICT_MANUAL},
 *       {@code LINK_SCOPE_CONFLICT_MANUAL}, die laatste al in de preview).</li>
 * </ul>
 * <h2>Gedeelde definities (slice S7, beslissingslog 2026-10-21 "Legacy-config S7: gedeelde definities", A2)</h2>
 * <ul>
 *   <li><b>Groep</b> = (omgeving, dossier, INDET {@code Supplier}, {@code definitionContentHash}) = één rij in
 *       {@code legacy_config_definition_origin} (020-3); ook herkend op dezelfde legacy-code onder dezelfde leverancier.
 *       Nooit over leveranciers heen.</li>
 *   <li><b>Eerste interface van een groep</b> (ook als ze alleen blijft): een {@code REUSABLE_TEMPLATE} (DRAFT,
 *       code {@code <legacy-code>-SJ}, de mens activeert) met de gemigreerde inhoud, en een gedeelde
 *       {@code OWN_DEFINITION} (legacy-code) {@code based_on} dat sjabloon, met revisie 1 als kopie van de
 *       sjabloonrevisie via {@link RevisionCopier} ({@code based_on_revision} = de sjabloonrevisie; de
 *       materialisatiewizard weigert een DRAFT-sjabloon). 020-3 draagt beide id's, 020-4 de revisie van de gedeelde
 *       definitie. Het sjabloon krijgt nooit een koppeling (changeset 006-5).</li>
 *   <li><b>Volgende interface</b> van de groep: enkel koppeling + taak op de gedeelde definitie, op voorwaarde dat haar
 *       geplande inhoud (ook de interface-instellingen: structuur, identiteit) gelijk is aan de laatst gemigreerde
 *       revisie; anders {@value #SHARED_DEFINITION_DIVERGED}. Dezelfde {@code libraryCode} als een ander lid →
 *       {@code LINK_SCOPE_CONFLICT_MANUAL} ({@code uk_import_link_scope}).</li>
 *   <li><b>Latere wijziging:</b> de gedeelde definitie volgt de S2-regels (opvolgende DRAFT, {@code OPEN_DRAFT_EXISTS},
 *       {@code MANUAL_CHANGES_SINCE_MIGRATION}), maar enkel als élk ander lid in zijn laatste ontvangst dezelfde nieuwe
 *       {@code definitionContentHash} heeft; anders {@value #SHARED_DEFINITION_DIVERGED}. Het sjabloon blijft ongemoeid
 *       ({@value #TEMPLATE_NOT_UPDATED}). Loskoppelen: zie hieronder (G2a).</li>
 *   <li>{@code sharedWith}: de andere leden plus de interfaces waarvan de laatste ontvangst (niet {@code DISCARDED})
 *       dezelfde leverancier en {@code definitionContentHash} heeft, zonder losgekoppelde interfaces; staat in het
 *       rapport en dus in de fingerprint.</li>
 * </ul>
 * <h2>Loskoppelen (slice G2a, {@code docs/design/g2-design.md} par. G2a; beslissingslog 2026-10-22 "G2a")</h2>
 * <ul>
 *   <li><b>Trigger:</b> de optie {@code detachFromShared} (PREVIEW-queryparameter en APPLY-body, samen met optioneel
 *       {@code detachedDefinitionCode}) op een interface met {@value #SHARED_DEFINITION_DIVERGED}; dat item wordt
 *       {@value #DETACH_FROM_SHARED} (MIGRATED). Zonder afwijking: {@value #DETACH_NOT_NEEDED} (WARNING, genegeerd).</li>
 *   <li><b>Bekende interface (V1 = A):</b> de bestaande koppeling wordt verlegd ({@code planned.link.action}
 *       {@value LegacyConfigReport#ACTION_REPOINT}) naar een eigen {@code OWN_DEFINITION} {@code LEGACY-<interface>}
 *       ({@code based_on} de gedeelde definitie). Koppelings- en taakcode, leveringsconfiguratie, credential,
 *       bronstaat, prijshistoriek en behandelgevallen blijven: ze hangen aan de koppeling; oude batches houden hun
 *       bevroren revisie. Gedeeltelijke herroeping van "een bestaande koppeling wordt nooit gewijzigd", enkel voor
 *       deze actie. Enkel zonder open batch ({@value #DETACH_OPEN_BATCH}) of lopende run
 *       ({@value #DETACH_RUN_IN_PROGRESS}), onder rijslot op de taken en de koppeling.</li>
 *   <li><b>Revisies (V2 = A):</b> revisie 1 = ongewijzigde kopie van de ACTIVE revisie van de gedeelde definitie,
 *       geactiveerd via {@link SetupService#activateRevision} met de actor als goedkeurder (uitzondering op "APPLY
 *       activeert nooit"); revisie 2 = opvolgende DRAFT met de nieuwe legacy-inhoud. Zonder ACTIVE revisie, of voor een
 *       nieuwe interface: revisie 1 = DRAFT met de nieuwe inhoud.</li>
 *   <li><b>Herkomst:</b> 020-2 ongewijzigd (behalve de laatst toegepaste ontvangst), de 020-3-rij van de groep
 *       ongewijzigd, een nieuwe 020-3-rij voor de kopie ({@code detached_interface_code}), 020-4 voor revisie 1 geërfd en
 *       voor de nieuwe inhoud nieuw, plus een auditrij in {@code legacy_config_detachment}.</li>
 *   <li><b>Daarna:</b> de kopie volgt enkel haar interface (exact S2, item {@value #DETACHED_DEFINITION},
 *       {@code sharedWith = []}); de groep ziet de losgekoppelde interface niet meer en wordt door de kopie nooit
 *       herkend (enkel groepsrijen).</li>
 * </ul>
 * <h2>Prodis-controle (slice S5, design par. 5 en 6)</h2>
 * Via {@link ProdisReferenceService} (alleen-lezen Prodis-database), in PREVIEW en APPLY gelijk:
 * <ul>
 *   <li><b>Leverancier:</b> INDET {@code Supplier} moet precies één {@code supplier.number} zijn
 *       ({@value #PRODIS_SUPPLIER_NOT_FOUND} / {@value #PRODIS_SUPPLIER_AMBIGUOUS} blokkeren). Een <b>nieuwe</b>
 *       bronorganisatie krijgt de Prodis-naam; een bestaande wordt nooit hernoemd ({@value #SOURCE_ORGANISATION_NAME_KEPT}).</li>
 *   <li><b>Bibliotheek (nieuwe koppeling):</b> zonder {@code libraryCode} de {@code scope_key} van de enige bibliotheek
 *       van die leverancier met {@code legacy_number} = {@code ArtImp_BibNr}; geen of meer dan één →
 *       {@value #LIBRARY_CODE_NOT_RESOLVED} met de kandidaten in {@code planned.link.libraryCandidates}. Een gegeven
 *       {@code libraryCode} moet een {@code scope_key} van die leverancier zijn ({@value #LIBRARY_CODE_NOT_IN_PRODIS}).</li>
 *   <li>De gebruikte Prodis-gegevens tellen mee in de {@code previewFingerprint}: wijzigen ze tussen PREVIEW en APPLY,
 *       dan is de preview verouderd ({@value #CODE_STALE}).</li>
 *   <li><b>Niet geconfigureerd:</b> gedrag S2 ({@code PRODIS_REFERENCE_NOT_VERIFIED}, {@code libraryCode} verplicht).
 *       <b>Onbereikbaar:</b> idem, en de fingerprint begint met
 *       {@value LegacyConfigReport#FINGERPRINT_UNVERIFIED_PREFIX}; APPLY mag dan met de {@code libraryCode} uit de body
 *       (design par. 6), zolang Prodis onbereikbaar blijft - is Prodis intussen terug, dan is die preview verouderd.</li>
 *   <li><b>Vaste leverancier/groep (S6, PSIMPDEF 9/10 met een vaste "Waarde"):</b> wordt {@code identity_*_fixed_value};
 *       de leverancier moet precies één {@code supplier.number} zijn, de groep een {@code supplier_group.group_name}
 *       van de leverancier van de identiteit ({@value #PRODIS_SUPPLIER_GROUP_NOT_FOUND}), gecontroleerd in hoofdletters
 *       ({@link #verifyFixedValues}). Elke gemigreerde revisie krijgt {@code identity_uppercase=true}; beide tellen
 *       mee in de inhoudshash ({@link LegacyRevisionContent}) en zo ook in de fingerprint. Niet geconfigureerd of
 *       onbereikbaar: zoals de leverancier hierboven ({@code PRODIS_REFERENCE_NOT_VERIFIED}, "unverified").</li>
 * </ul>
 * Schrijven gaat via de bestaande services ({@link SetupService}, {@link RevisionSuccessorService}), die hun eigen regels
 * bewaken; enkel een TAB-scheidingsteken wordt rechtstreeks op de revisie gezet, omdat {@code SetupInput.requireText}
 * een tab als leeg ziet. Gelogd wordt enkel id, herkomst, uitkomst en codes; nooit de bundel.
 */
@Service
@Transactional
public class LegacyConfigApplyService {

    public static final String CODE_NOT_APPLICABLE = "LEGACY_RECEIPT_NOT_APPLICABLE";
    public static final String CODE_STALE = "LEGACY_PREVIEW_STALE";
    public static final String CODE_BLOCKED = "LEGACY_APPLY_BLOCKED";
    public static final String CODE_REASON_REQUIRED = "LEGACY_APPLY_REASON_REQUIRED";
    public static final String CODE_REASON_TOO_LONG = "LEGACY_APPLY_REASON_TOO_LONG";
    public static final String CODE_DISCARD_REASON_REQUIRED = "LEGACY_DISCARD_REASON_REQUIRED";
    public static final String CODE_DISCARD_REASON_TOO_LONG = "LEGACY_DISCARD_REASON_TOO_LONG";

    static final String LIBRARY_CODE_NOT_RESOLVED = "LIBRARY_CODE_NOT_RESOLVED";
    static final String LIBRARY_CODE_INVALID = "LIBRARY_CODE_INVALID";
    static final String DEFINITION_CODE_CONFLICT_MANUAL = "DEFINITION_CODE_CONFLICT_MANUAL";
    static final String DEFINITION_CODE_OVERRIDE_INVALID = "DEFINITION_CODE_OVERRIDE_INVALID";
    // S7: gedeelde definities.
    static final String SHARED_DEFINITION = "SHARED_DEFINITION";
    static final String SHARED_DEFINITION_DIVERGED = "SHARED_DEFINITION_DIVERGED";
    static final String TEMPLATE_NOT_UPDATED = "TEMPLATE_NOT_UPDATED";
    static final String TEMPLATE_CODE_CONFLICT_MANUAL = "TEMPLATE_CODE_CONFLICT_MANUAL";
    static final String DEFINITION_CODE_OVERRIDE_NOT_USED = "DEFINITION_CODE_OVERRIDE_NOT_USED";
    /** Code van het sjabloon: {@code <code van de gedeelde definitie>-SJ} (beslissingslog 2026-10-21 S7). */
    static final String TEMPLATE_CODE_SUFFIX = "-SJ";
    static final String SOURCE_ORGANISATION_TYPE_CONFLICT = "SOURCE_ORGANISATION_TYPE_CONFLICT";
    static final String LINK_CODE_CONFLICT_MANUAL = "LINK_CODE_CONFLICT_MANUAL";
    static final String LINK_SCOPE_CONFLICT_MANUAL = "LINK_SCOPE_CONFLICT_MANUAL";
    static final String LINK_DIFFERS_NOT_CHANGED = "LINK_DIFFERS_NOT_CHANGED";
    static final String OPEN_DRAFT_EXISTS = "OPEN_DRAFT_EXISTS";
    static final String REVISION_NOT_CLONEABLE = "REVISION_NOT_CLONEABLE";
    static final String MANUAL_CHANGES_SINCE_MIGRATION = "MANUAL_CHANGES_SINCE_MIGRATION";
    static final String IDENTITY_CHANGE = "IDENTITY_CHANGE";
    static final String DEFINITION_UNCHANGED = "DEFINITION_UNCHANGED";
    static final String LEGACY_DEFINITION_CODE_CHANGED = "LEGACY_DEFINITION_CODE_CHANGED";
    // S5: controle tegen de alleen-lezen Prodis-database.
    static final String PRODIS_SUPPLIER_NOT_FOUND = ProdisReferenceService.CODE_SUPPLIER_NOT_FOUND;
    static final String PRODIS_SUPPLIER_AMBIGUOUS = "PRODIS_SUPPLIER_AMBIGUOUS";
    static final String PRODIS_SUPPLIER = "PRODIS_SUPPLIER";
    // S6: vaste groep (PSIMPDEF 10) tegen Prodis.
    static final String PRODIS_SUPPLIER_GROUP = "PRODIS_SUPPLIER_GROUP";
    static final String PRODIS_SUPPLIER_GROUP_NOT_FOUND = "PRODIS_SUPPLIER_GROUP_NOT_FOUND";
    static final String PRODIS_SUPPLIER_NAME_EMPTY = "PRODIS_SUPPLIER_NAME_EMPTY";
    static final String PRODIS_SUPPLIER_NAME_TOO_LONG = "PRODIS_SUPPLIER_NAME_TOO_LONG";
    static final String SOURCE_ORGANISATION_NAME_KEPT = "SOURCE_ORGANISATION_NAME_KEPT";
    static final String LIBRARY_CODE_NOT_IN_PRODIS = "LIBRARY_CODE_NOT_IN_PRODIS";
    static final String LIBRARY_CODE_NOT_LEGACY_MATCH = "LIBRARY_CODE_NOT_LEGACY_MATCH";
    // G2a: loskoppelen van een gedeelde definitie (docs/design/g2-design.md par. G2a).
    static final String DETACH_FROM_SHARED = "DETACH_FROM_SHARED";
    static final String DETACH_NOT_NEEDED = "DETACH_NOT_NEEDED";
    static final String DETACH_OPEN_BATCH = "DETACH_OPEN_BATCH";
    static final String DETACH_RUN_IN_PROGRESS = "DETACH_RUN_IN_PROGRESS";
    static final String DETACHED_DEFINITION_CODE_CONFLICT = "DETACHED_DEFINITION_CODE_CONFLICT";
    static final String DETACHED_DEFINITION_CODE_INVALID = "DETACHED_DEFINITION_CODE_INVALID";
    static final String DETACHED_DEFINITION = "DETACHED_DEFINITION";
    /** Achter de beschrijving van de kopie: "&lt;gedeelde beschrijving&gt; (losgekoppeld voor &lt;interface&gt;)". */
    static final String DETACHED_NAME_SUFFIX = " (losgekoppeld voor %s)";

    static final String LINK_CODE_PREFIX = "LEGACY-";
    static final String NAME_PREFIX = "Legacy ";
    static final String ORGANISATION_NAME_PREFIX = "Leverancier ";
    /** Canonicalisatieversie van een gemigreerde revisie: mappings vereisen versie 2 (ImportMappingConfigFactory). */
    static final int CANONICALISATION_VERSION = 2;

    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_DEFINITION_CODE = 50;
    /** S7: ruimte voor {@value #TEMPLATE_CODE_SUFFIX} binnen {@code import_definition.code} (50). */
    private static final int MAX_SHARED_DEFINITION_CODE = MAX_DEFINITION_CODE - TEMPLATE_CODE_SUFFIX.length();
    private static final int MAX_LIBRARY_CODE = 20;
    /** {@code source_organisation.name} ({@code SetupService}). */
    private static final int MAX_ORGANISATION_NAME = 200;
    /** {@code import_definition.description} ({@code SetupService}). */
    private static final int MAX_DEFINITION_NAME = 200;
    private static final Set<RevisionStatus> CLONEABLE = Set.of(RevisionStatus.ACTIVE, RevisionStatus.SUPERSEDED);
    private static final Logger LOG = LoggerFactory.getLogger(LegacyConfigApplyService.class);

    /**
     * De door de mens gegeven keuzes; dezelfde voor PREVIEW (queryparameters) en APPLY (body).
     *
     * @param detachFromShared       (G2a) loskoppelen van de gedeelde definitie als de interface ervan afwijkt
     *                               ({@value #SHARED_DEFINITION_DIVERGED}); anders genegeerd ({@value #DETACH_NOT_NEEDED})
     * @param detachedDefinitionCode (G2a) de code van de eigen kopie in plaats van {@code LEGACY-<interface>}; enkel
     *                               samen met {@code detachFromShared}
     */
    public record ApplyOptions(String definitionCodeOverride, String charsetWhenUndeclared, String libraryCode,
                               boolean detachFromShared, String detachedDefinitionCode) {

        /** Zonder loskoppelen (vorm van vóór G2a). */
        public ApplyOptions(String definitionCodeOverride, String charsetWhenUndeclared, String libraryCode) {
            this(definitionCodeOverride, charsetWhenUndeclared, libraryCode, false, null);
        }

        static ApplyOptions none() {
            return new ApplyOptions(null, null, null);
        }

        ApplyOptions normalised() {
            return new ApplyOptions(blankToNull(definitionCodeOverride), blankToNull(charsetWhenUndeclared),
                    blankToNull(libraryCode), detachFromShared, blankToNull(detachedDefinitionCode));
        }
    }

    /**
     * APPLY-body: {@code {previewFingerprint, reason, definitionCodeOverride?, charsetWhenUndeclared?, libraryCode?,
     * detachFromShared?, detachedDefinitionCode?}}; de laatste twee additief (G2a).
     */
    public record ApplyCommand(String previewFingerprint, String reason, String definitionCodeOverride,
                               String charsetWhenUndeclared, String libraryCode, Boolean detachFromShared,
                               String detachedDefinitionCode) {

        /** Zonder loskoppelen (vorm van vóór G2a). */
        public ApplyCommand(String previewFingerprint, String reason, String definitionCodeOverride,
                            String charsetWhenUndeclared, String libraryCode) {
            this(previewFingerprint, reason, definitionCodeOverride, charsetWhenUndeclared, libraryCode, null, null);
        }

        ApplyOptions options() {
            return new ApplyOptions(definitionCodeOverride, charsetWhenUndeclared, libraryCode,
                    Boolean.TRUE.equals(detachFromShared), detachedDefinitionCode).normalised();
        }
    }

    /** DISCARD-body: {@code {reason}}. */
    public record DiscardCommand(String reason) {
    }

    /** @param receiptStatus {@code DISCARDED} */
    public record DiscardResult(long receiptId, String receiptStatus) {
    }

    public record PreviewResult(LegacyConfigReport report, String previewFingerprint) {
    }

    /**
     * @param report                 het rapport met de id's van wat aangemaakt of hergebruikt werd
     * @param receiptStatus          {@code APPLIED}
     * @param configurationFindings  de configuratiebevindingen van de nieuwe revisie (dezelfde validatie als bij het
     *                               activeren); een DRAFT mag onvolledig zijn, activeren blokkeert dan
     */
    public record ApplyResult(LegacyConfigReport report, String receiptStatus,
                              List<ConfigurationFinding> configurationFindings) {
    }

    public record ConfigurationFinding(String code, String fieldName, String message) {
    }

    private final LegacyConfigReceiptRepository receipts;
    private final LegacyConfigOriginRepository origins;
    private final LegacyConfigDefinitionOriginRepository definitionOrigins;
    private final LegacyConfigRevisionOriginRepository revisionOrigins;
    private final SourceOrganisationRepository organisations;
    private final ImportDefinitionRepository definitions;
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportFieldMappingRepository fieldMappings;
    /** S8a: de vertaalrijen van de mappings, voor de inhoudshash ({@link LegacyRevisionContent}). */
    private final ImportFieldValueMapRepository valueMaps;
    private final ImportFieldTransformStepRepository transformSteps;
    private final ImportRecordFilterRepository recordFilters;
    private final ImportFieldCatalogRepository fieldCatalog;
    private final ImportLinkRepository links;
    private final CatalogImportTaskRepository tasks;
    private final ExternalCredentialRepository credentials;
    private final SetupService setup;
    private final RevisionSuccessorService successors;
    private final RevisionCopier revisionCopier;
    private final ChainConfigurationChecks checks;
    private final ProdisReferenceService prodis;
    private final Clock clock;
    // G2a: guards (open batch, lopende run), de volledige kopie van een revisie en de audit van het loskoppelen.
    private final ImportBatchRepository batches;
    private final TaskRunRepository taskRuns;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkUsageRepository bookmarkUsages;
    private final LegacyConfigDetachmentRepository detachments;
    private final ObjectMapper bundleReader = new ObjectMapper();
    /** Canoniek (alfabetisch) voor de fingerprint en het bewaarde rapport. */
    private final ObjectMapper canonical = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public LegacyConfigApplyService(LegacyConfigReceiptRepository receipts, LegacyConfigOriginRepository origins,
                                    LegacyConfigDefinitionOriginRepository definitionOrigins,
                                    LegacyConfigRevisionOriginRepository revisionOrigins,
                                    SourceOrganisationRepository organisations, ImportDefinitionRepository definitions,
                                    ImportDefinitionRevisionRepository revisions,
                                    ImportFieldMappingRepository fieldMappings,
                                    ImportRecordFilterRepository recordFilters,
                                    ImportFieldCatalogRepository fieldCatalog, ImportLinkRepository links,
                                    CatalogImportTaskRepository tasks, ExternalCredentialRepository credentials,
                                    SetupService setup, RevisionSuccessorService successors,
                                    RevisionCopier revisionCopier, ChainConfigurationChecks checks,
                                    ProdisReferenceService prodis, Clock clock,
                                    ImportFieldValueMapRepository valueMaps,
                                    ImportFieldTransformStepRepository transformSteps,
                                    ImportBatchRepository batches, TaskRunRepository taskRuns,
                                    ImportDefinitionBookmarkRepository bookmarks,
                                    ImportDefinitionBookmarkUsageRepository bookmarkUsages,
                                    LegacyConfigDetachmentRepository detachments) {
        this.receipts = receipts;
        this.batches = batches;
        this.taskRuns = taskRuns;
        this.bookmarks = bookmarks;
        this.bookmarkUsages = bookmarkUsages;
        this.detachments = detachments;
        this.valueMaps = valueMaps;
        this.transformSteps = transformSteps;
        this.origins = origins;
        this.definitionOrigins = definitionOrigins;
        this.revisionOrigins = revisionOrigins;
        this.organisations = organisations;
        this.definitions = definitions;
        this.revisions = revisions;
        this.fieldMappings = fieldMappings;
        this.recordFilters = recordFilters;
        this.fieldCatalog = fieldCatalog;
        this.links = links;
        this.tasks = tasks;
        this.credentials = credentials;
        this.setup = setup;
        this.successors = successors;
        this.revisionCopier = revisionCopier;
        this.checks = checks;
        this.prodis = prodis;
        this.clock = clock;
    }

    // --- PREVIEW --------------------------------------------------------------------------------------------------

    /**
     * Het rapport van wat APPLY nu zou doen, met dezelfde keuzes als de APPLY-body; schrijft niets.
     *
     * @throws NotFoundException 404 {@code LEGACY_RECEIPT_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public PreviewResult preview(long receiptId, ApplyOptions options) {
        LegacyConfigReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> notFound(receiptId));
        Plan plan = plan(receipt, (options == null ? ApplyOptions.none() : options).normalised());
        return new PreviewResult(plan.report(), plan.fingerprint());
    }

    // --- APPLY ----------------------------------------------------------------------------------------------------

    /**
     * Past de ontvangst toe zoals de preview met {@code command.previewFingerprint()} beschreef.
     *
     * @throws NotFoundException   404 {@code LEGACY_RECEIPT_NOT_FOUND}
     * @throws BadRequestException 400 {@value #CODE_REASON_REQUIRED}, {@value #CODE_REASON_TOO_LONG}
     * @throws ConflictException   409 {@value #CODE_NOT_APPLICABLE}, {@value #CODE_STALE}, {@value #CODE_BLOCKED}
     */
    public ApplyResult apply(long receiptId, ApplyCommand command, ActorIdentity actor) {
        LegacyConfigReceipt receipt = receipts.findByIdForUpdate(receiptId).orElseThrow(() -> notFound(receiptId));
        String reason = command == null ? null : command.reason();
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(CODE_REASON_REQUIRED, "reason is required: it is the only record of why "
                    + "this legacy configuration was applied");
        }
        reason = reason.trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BadRequestException(CODE_REASON_TOO_LONG, "reason must be at most " + MAX_REASON_LENGTH
                    + " characters");
        }
        if (receipt.getStatus() != LegacyConfigReceiptStatus.RECEIVED) {
            throw new ConflictException(CODE_NOT_APPLICABLE, "Legacy-config receipt " + receiptId + " is "
                    + receipt.getStatus() + "; only a RECEIVED receipt can be applied");
        }
        ApplyOptions options = command.options();
        if (options.detachFromShared()) {
            // G2a: het verleggen van de koppeling mag niet samenvallen met een intake of ophaalrun op die koppeling.
            // Eerst de sloten, dan pas het plan: de guards (open batch, lopende run) worden zo onder slot opnieuw
            // geëvalueerd, en een verandering sinds de preview geeft een andere fingerprint (409 STALE).
            lockLinkForDetach(receipt);
        }
        Plan plan = plan(receipt, options);
        if (command.previewFingerprint() == null || !command.previewFingerprint().equals(plan.fingerprint())) {
            throw new ConflictException(CODE_STALE, "The preview of legacy-config receipt " + receiptId
                    + " is missing or no longer current; run the preview again (with the same choices) and apply that");
        }
        if (LegacyConfigReport.OUTCOME_BLOCKED.equals(plan.report().outcome())) {
            throw new ConflictException(CODE_BLOCKED, "Legacy-config receipt " + receiptId
                    + " cannot be applied; blocking: " + String.join(", ", plan.report().blockingCodes()));
        }
        ApplyResult result = execute(receipt, plan, reason, actor);
        LOG.info("Legacy-config receipt {} APPLIED for {}/{}/{} by {}: definition {} ({}), link {}, task {}",
                receipt.getId(), receipt.getOriginEnvironment(), receipt.getOriginDossier(),
                receipt.getInterfaceCode(), actor.username(), result.report().planned().definition().id(),
                result.report().planned().definition().revisionAction(), result.report().planned().link().id(),
                result.report().planned().task().id());
        return result;
    }

    /**
     * G2a: rijsloten voor het verleggen van een bestaande koppeling, in de slotvolgorde van de intake: eerst alle taken
     * van de koppeling (op id, het slot van elke intake en ophaalrun, {@code CatalogImportTaskRepository#findByIdForUpdate}),
     * dan de koppeling zelf. Een interface zonder koppeling (nieuw) heeft niets te vergrendelen.
     */
    private void lockLinkForDetach(LegacyConfigReceipt receipt) {
        Long linkId = origins.findByOriginEnvironmentAndOriginDossierAndInterfaceCode(receipt.getOriginEnvironment(),
                receipt.getOriginDossier(), receipt.getInterfaceCode()).map(LegacyConfigOrigin::getImportLinkId)
                .orElse(null);
        if (linkId == null) {
            return;
        }
        tasks.findByImportLinkIdForUpdate(linkId);
        links.findByIdForUpdate(linkId);
    }

    // --- DISCARD (slice G1) ---------------------------------------------------------------------------------------

    /**
     * Verwerpt een open ontvangst (rijslot zoals APPLY): enkel {@code RECEIVED}, reden verplicht. Schrijft niets anders
     * dan de ontvangst zelf; de ontvangst blijft de laatste van haar herkomst, dus dezelfde inhoud opnieuw pushen geeft
     * {@code UNCHANGED} met status {@code DISCARDED}.
     *
     * @throws NotFoundException   404 {@code LEGACY_RECEIPT_NOT_FOUND}
     * @throws BadRequestException 400 {@value #CODE_DISCARD_REASON_REQUIRED}, {@value #CODE_DISCARD_REASON_TOO_LONG}
     * @throws ConflictException   409 {@value #CODE_NOT_APPLICABLE}
     */
    public DiscardResult discard(long receiptId, DiscardCommand command, ActorIdentity actor) {
        LegacyConfigReceipt receipt = receipts.findByIdForUpdate(receiptId).orElseThrow(() -> notFound(receiptId));
        String reason = command == null ? null : command.reason();
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(CODE_DISCARD_REASON_REQUIRED, "reason is required: it is the only record of "
                    + "why this legacy configuration was discarded");
        }
        reason = reason.trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BadRequestException(CODE_DISCARD_REASON_TOO_LONG, "reason must be at most " + MAX_REASON_LENGTH
                    + " characters");
        }
        if (receipt.getStatus() != LegacyConfigReceiptStatus.RECEIVED) {
            throw new ConflictException(CODE_NOT_APPLICABLE, "Legacy-config receipt " + receiptId + " is "
                    + receipt.getStatus() + "; only a RECEIVED receipt can be discarded");
        }
        receipt.markDiscarded(clock.instant(), actor.username(), actor.subject(), reason);
        receipts.saveAndFlush(receipt);
        LOG.info("Legacy-config receipt {} DISCARDED for {}/{}/{} by {}", receipt.getId(),
                receipt.getOriginEnvironment(), receipt.getOriginDossier(), receipt.getInterfaceCode(),
                actor.username());
        return new DiscardResult(receipt.getId(), LegacyConfigReceiptStatus.DISCARDED.name());
    }

    // --- Plannen (gedeeld door PREVIEW en APPLY) -----------------------------------------------------------------

    /** Alles wat APPLY nodig heeft, plus het rapport en de fingerprint. */
    private record Plan(LegacyConfigReport report, String fingerprint, Translation translation, ApplyOptions options,
                        SourceOrganisation organisation, String organisationCode, String organisationName,
                        ImportDefinition definition,
                        String definitionCode, String revisionAction, ImportDefinitionRevision basedOn,
                        ImportLink link, String linkCode, String libraryCode, Long taskId, String taskName,
                        LegacyConfigOrigin origin, LegacyConfigDefinitionOrigin definitionOrigin,
                        String plannedContentHash, String templateCode, String legacyDefinitionCodes,
                        DetachDecision detach) {
    }

    /**
     * G2a: wat het loskoppelen doet. {@code from} is de gedeelde definitie (van de groep); {@code copied} haar ACTIVE
     * revisie die als revisie 1 van de kopie meegaat (en {@code inherited} de 020-4-rij van haar laatst gemigreerde
     * revisie, die revisie 1 erft), of {@code null}: dan is revisie 1 een DRAFT met de nieuwe inhoud.
     */
    private record DetachDecision(ImportDefinition from, String toCode, String toName,
                                  ImportDefinitionRevision copied, LegacyConfigRevisionOrigin inherited) {

        boolean carriesActive() {
            return copied != null;
        }
    }

    private Plan plan(LegacyConfigReceipt receipt, ApplyOptions options) {
        JsonNode bundle = readBundle(receipt);
        String environment = receipt.getOriginEnvironment();
        String dossier = receipt.getOriginDossier();
        String interfaceCode = receipt.getInterfaceCode();
        LegacyConfigOrigin origin = origins.findByOriginEnvironmentAndOriginDossierAndInterfaceCode(environment,
                dossier, interfaceCode).orElse(null);
        // S4: de credential van de herkomst (nooit een waarde); APPLY koppelt ze nergens aan (L5: hostsleutel).
        UUID credentialRef = origin == null || origin.getCredentialId() == null ? null
                : credentials.findById(origin.getCredentialId()).map(ExternalCredential::getCredentialRef).orElse(null);
        PlannedCredential plannedCredential = credentialRef == null && receipt.getSecretOutcome() == null ? null
                : new PlannedCredential(credentialRef,
                receipt.getSecretOutcome() == null ? null : receipt.getSecretOutcome().name());
        Translation translation = LegacyConfigTranslator.translate(bundle, options.charsetWhenUndeclared(),
                credentialRef);
        List<Item> items = new ArrayList<>(translation.items());
        List<String> rows = new ArrayList<>();
        String supplierCode = translation.supplierCode();
        String definitionHash = translation.definitionContentHash();
        PlannedRevision planned = translation.revision();
        String plannedHash = planned == null ? null
                : LegacyRevisionContent.ofPlanned(planned, "CSV", this::defaultCriticality);
        // S5: leverancier en bibliotheken uit de alleen-lezen Prodis-database (of: uit / onbereikbaar).
        LegacyLookup lookup = prodis.lookup(supplierCode);
        SupplierRow prodisSupplier = verifySupplier(items, rows, lookup, supplierCode);
        // S6: vaste leverancier/groep (PSIMPDEF 9/10) tegen dezelfde Prodis-bron; samen met de leverancier bepalen ze
        // of de preview geverifieerd is.
        Availability availability = verifyFixedValues(items, rows, translation, supplierCode, lookup.availability());

        rows.add("origin:" + (origin == null ? "none" : origin.getId() + ":" + origin.getUpdatedAt()));
        ImportLink link = origin == null || origin.getImportLinkId() == null ? null
                : links.findById(origin.getImportLinkId()).orElse(null);

        SourceOrganisation organisation;
        String organisationAction;
        ImportDefinition definition = null;
        String definitionAction;
        String definitionCode;
        String revisionAction = ACTION_NONE;
        ImportDefinitionRevision basedOn = null;
        String linkCode;
        String libraryCode;
        List<LibraryCandidate> libraryCandidates = List.of();
        String linkAction;
        Long taskId = null;
        String taskAction;
        String taskName = NAME_PREFIX + interfaceCode;
        LegacyConfigDefinitionOrigin definitionOrigin = null;
        // S7: de andere interfaces met een koppeling op de (gedeelde) definitie, en het sjabloon van de groep.
        List<String> members = List.of();
        String templateAction = ACTION_NONE;
        String templateCode = null;
        Long templateId = null;
        // G2a: de interface wijkt af van haar groep (SHARED_DEFINITION_DIVERGED), of haar definitie is al haar eigen
        // losgekoppelde kopie.
        boolean divergesFromGroup = false;
        boolean ownCopy = false;

        if (link != null) {
            // Bekende interface: koppeling, taak, organisatie en definitie bestaan en worden nooit gewijzigd.
            rows.add("link:" + link.getId() + ":" + link.getUpdatedAt());
            organisation = link.getSupplierOrganisation();
            organisationAction = ACTION_REUSE;
            definition = link.getImportDefinition();
            definitionAction = ACTION_REUSE;
            definitionCode = definition.getCode();
            linkAction = ACTION_REUSE;
            linkCode = link.getCode();
            libraryCode = link.getLibraryCode();
            rows.add("organisation:" + organisation.getId() + ":" + organisation.getUpdatedAt());
            rows.add("definition:" + definition.getId() + ":" + definition.getUpdatedAt());
            if (supplierCode != null && !supplierCode.equals(organisation.getCode())) {
                items.add(new Item("PLAN:link", BLOCKING, LINK_DIFFERS_NOT_CHANGED, "import_link.id=" + link.getId(),
                        "the interface now names supplier " + supplierCode + " but its link is for "
                                + organisation.getCode() + "; an existing link is never changed"));
            }
            if (options.libraryCode() != null && !options.libraryCode().equals(link.getLibraryCode())) {
                items.add(new Item("PLAN:link", WARNING, LINK_DIFFERS_NOT_CHANGED, "import_link.id=" + link.getId(),
                        "libraryCode " + options.libraryCode() + " differs from the existing link ("
                                + link.getLibraryCode() + "); an existing link is never changed"));
            }
            if (origin.getCatalogImportTaskId() != null) {
                CatalogImportTask task = tasks.findById(origin.getCatalogImportTaskId()).orElse(null);
                taskAction = ACTION_REUSE;
                taskId = origin.getCatalogImportTaskId();
                if (task != null) {
                    taskName = task.getName();
                    rows.add("task:" + task.getId() + ":" + task.getUpdatedAt());
                }
            } else {
                taskAction = ACTION_CREATE;
            }
            if (translation.definitionCode() != null && !translation.definitionCode().equals(definition.getCode())
                    && options.definitionCodeOverride() == null) {
                items.add(new Item("PSIMDHFD", WARNING, LEGACY_DEFINITION_CODE_CHANGED,
                        "import_definition.id=" + definition.getId(), "the interface now uses legacy definition "
                                + translation.definitionCode() + "; the existing definition " + definition.getCode()
                                + " keeps its code"));
            }
            List<LegacyConfigDefinitionOrigin> owned =
                    definitionOrigins.findByImportDefinitionIdOrderByIdAsc(definition.getId());
            members = memberInterfaces(definition.getId(), interfaceCode, rows);
            if (owned.isEmpty()) {
                items.add(new Item("PLAN:definition", BLOCKING, DEFINITION_CODE_CONFLICT_MANUAL,
                        "import_definition.id=" + definition.getId(), "the definition of this link has no migration "
                                + "origin; a manual definition is never a migration target"));
            } else {
                definitionOrigin = owned.get(0);
                rows.add("definitionOrigin:" + definitionOrigin.getId() + ":" + definitionOrigin.getUpdatedAt());
                templateId = definitionOrigin.getTemplateDefinitionId();
                if (definitionOrigin.isDetached()) {
                    // G2a: de eigen kopie volgt enkel deze interface (exact S2); ze deelt niets en trekt geen leden aan.
                    ownCopy = true;
                    ImportDefinition from = definition.getBasedOnDefinition();
                    items.add(new Item("PLAN:definition", MIGRATED, DETACHED_DEFINITION,
                            "import_definition.id=" + definition.getId(), "definition " + definition.getCode()
                                    + " is the own copy of this interface, detached from shared definition "
                                    + (from == null ? "(unknown)" : from.getCode())
                                    + "; it follows only this interface and is not shared"));
                }
            }
            // Een eigen kopie kan nooit "verhuizen" naar een andere groep: haar 020-3-rij botst met geen groepssleutel
            // (026-1); opnieuw aansluiten is niet in G2 (design G2a punt 9).
            boolean otherGroup = !ownCopy && otherGroupCheck(items, rows, environment, dossier, supplierCode,
                    definitionHash, definitionOrigin);
            if (planned != null && !otherGroup && definitionOrigin != null) {
                LastMigrated lastMigrated = lastMigrated(definition);
                boolean diverged = lastMigrated != null && !plannedHash.equals(lastMigrated.hash())
                        && !members.isEmpty()
                        && membersDiverge(items, rows, receipt, definitionOrigin, definition, members);
                if (!diverged) {
                    RevisionDecision decision = revisionDecision(definition, planned, plannedHash, items, rows);
                    revisionAction = decision.action();
                    basedOn = decision.basedOn();
                }
                divergesFromGroup = diverged;
            }
            divergesFromGroup = divergesFromGroup || otherGroup;
            if (ACTION_SUCCESSOR_DRAFT.equals(revisionAction) && templateId != null) {
                items.add(new Item("PLAN:template", LegacyConfigReport.NOT_MIGRATED, TEMPLATE_NOT_UPDATED,
                        "import_definition.id=" + templateId, "the template of this shared definition keeps its "
                                + "content; only the shared definition gets a successor DRAFT"));
            }
        } else {
            // Nieuwe interface (of een herkomst zonder koppeling, bv. enkel een credential uit S4).
            organisation = supplierCode == null ? null : organisations.findByCode(supplierCode).orElse(null);
            organisationAction = organisation == null ? ACTION_CREATE : ACTION_REUSE;
            if (organisation != null) {
                rows.add("organisation:" + organisation.getId() + ":" + organisation.getUpdatedAt());
                if (organisation.getOrganisationType() != SourceOrganisationType.SUPPLIER) {
                    items.add(new Item("INDET:Supplier", BLOCKING, SOURCE_ORGANISATION_TYPE_CONFLICT,
                            "source_organisation.id=" + organisation.getId(), "source organisation " + supplierCode
                                    + " exists as " + organisation.getOrganisationType() + ", not as SUPPLIER"));
                }
            }
            GroupMatch group = findGroup(environment, dossier, supplierCode, definitionHash,
                    translation.definitionCode(), rows);
            if (group != null) {
                // S7: de groep bestaat al - enkel koppeling en taak op haar gedeelde definitie.
                definitionOrigin = group.origin();
                definition = definitions.findById(definitionOrigin.getImportDefinitionId()).orElseThrow();
                rows.add("definition:" + definition.getId() + ":" + definition.getUpdatedAt());
                definitionAction = ACTION_REUSE;
                definitionCode = definition.getCode();
                templateId = definitionOrigin.getTemplateDefinitionId();
                members = memberInterfaces(definition.getId(), interfaceCode, rows);
                if (options.definitionCodeOverride() != null
                        && !options.definitionCodeOverride().equals(definition.getCode())) {
                    items.add(new Item("PLAN:definition", WARNING, DEFINITION_CODE_OVERRIDE_NOT_USED,
                            "import_definition.id=" + definition.getId(), "the interface joins the shared definition "
                                    + definition.getCode() + "; definitionCodeOverride is not used"));
                }
                if (!group.byHash()) {
                    divergesFromGroup = true;
                    items.add(new Item("PLAN:definition", BLOCKING, SHARED_DEFINITION_DIVERGED,
                            "import_definition.id=" + definition.getId(), "legacy definition "
                                    + translation.definitionCode() + " of supplier " + supplierCode
                                    + " is already migrated as shared definition " + definition.getCode()
                                    + " with other content" + describeMembers(members) + DETACH_HINT_NEW));
                } else if (planned != null) {
                    LastMigrated lastMigrated = lastMigrated(definition);
                    if (lastMigrated == null) {
                        items.add(new Item("PLAN:definition", BLOCKING, MANUAL_CHANGES_SINCE_MIGRATION,
                                "import_definition.id=" + definition.getId(), "the shared definition has no "
                                        + "migrated revision; it is never overwritten"));
                    } else if (plannedHash.equals(lastMigrated.hash())) {
                        rows.add("lastMigrated:" + lastMigrated.revision().getId());
                        revisionAction = ACTION_UNCHANGED;
                        items.add(new Item("PLAN:definition", MIGRATED, SHARED_DEFINITION,
                                "import_definition.id=" + definition.getId(), "the interface joins shared definition "
                                        + definition.getCode() + describeMembers(members)
                                        + "; only a link and a task are created"));
                    } else {
                        rows.add("lastMigrated:" + lastMigrated.revision().getId());
                        divergesFromGroup = true;
                        items.add(new Item("PLAN:definition", BLOCKING, SHARED_DEFINITION_DIVERGED,
                                "import_definition.id=" + definition.getId(), "the legacy definition is the same, "
                                        + "but this interface yields other content (interface settings such as "
                                        + "structure or identity) than shared definition " + definition.getCode()
                                        + describeMembers(members) + DETACH_HINT_NEW));
                    }
                }
            } else {
                definitionAction = ACTION_CREATE;
                revisionAction = planned == null ? ACTION_NONE : ACTION_CREATE;
                definitionCode = options.definitionCodeOverride() != null ? options.definitionCodeOverride()
                        : translation.definitionCode();
                if (options.definitionCodeOverride() != null
                        && options.definitionCodeOverride().length() > MAX_SHARED_DEFINITION_CODE) {
                    items.add(new Item("PLAN:definition", BLOCKING, DEFINITION_CODE_OVERRIDE_INVALID, null,
                            "definitionCodeOverride is longer than " + MAX_SHARED_DEFINITION_CODE
                                    + " characters (the template code adds " + TEMPLATE_CODE_SUFFIX + ")"));
                } else if (options.definitionCodeOverride() != null) {
                    items.add(new Item("PLAN:definition", WARNING, "DEFINITION_CODE_OVERRIDDEN",
                            "import_definition.code=" + definitionCode, "the legacy code "
                                    + translation.definitionCode() + " is replaced by definitionCodeOverride"));
                }
                templateAction = ACTION_CREATE;
                templateCode = definitionCode == null ? null : definitionCode + TEMPLATE_CODE_SUFFIX;
                if (organisation != null && definitionCode != null) {
                    Optional<ImportDefinition> existing =
                            definitions.findBySourceOrganisationIdAndCode(organisation.getId(), definitionCode);
                    if (existing.isPresent()) {
                        ImportDefinition clash = existing.get();
                        rows.add("definitionClash:" + clash.getId() + ":" + clash.getUpdatedAt());
                        items.add(new Item("PLAN:definition", BLOCKING, DEFINITION_CODE_CONFLICT_MANUAL,
                                "import_definition.id=" + clash.getId(), "a definition " + definitionCode
                                        + " already exists for supplier " + supplierCode + " ("
                                        + (definitionOrigins.existsByImportDefinitionId(clash.getId())
                                        ? "migrated for another legacy definition" : "manual")
                                        + "); it is never a migration target. Choose another code with "
                                        + "definitionCodeOverride"));
                    }
                    Optional<ImportDefinition> templateClash =
                            definitions.findBySourceOrganisationIdAndCode(organisation.getId(), templateCode);
                    if (templateClash.isPresent()) {
                        ImportDefinition clash = templateClash.get();
                        rows.add("templateClash:" + clash.getId() + ":" + clash.getUpdatedAt());
                        items.add(new Item("PLAN:template", BLOCKING, TEMPLATE_CODE_CONFLICT_MANUAL,
                                "import_definition.id=" + clash.getId(), "a definition " + templateCode
                                        + " already exists for supplier " + supplierCode + "; the template of the "
                                        + "shared definition needs that code. Choose another code with "
                                        + "definitionCodeOverride"));
                    }
                }
            }
            linkAction = ACTION_CREATE;
            linkCode = LINK_CODE_PREFIX + interfaceCode;
            LibraryDecision library = resolveLibrary(items, lookup, prodisSupplier, translation.libraryNumber(),
                    options.libraryCode());
            libraryCode = library.libraryCode();
            libraryCandidates = library.candidates();
            Optional<ImportLink> codeClash = links.findByCode(linkCode);
            if (codeClash.isPresent()) {
                rows.add("linkClash:" + codeClash.get().getId() + ":" + codeClash.get().getUpdatedAt());
                items.add(new Item("PLAN:link", BLOCKING, LINK_CODE_CONFLICT_MANUAL,
                        "import_link.id=" + codeClash.get().getId(), "link code " + linkCode + " is already in use"));
            }
            taskAction = ACTION_CREATE;
        }

        // G2a: loskoppelen tot een eigen kopie, enkel als de interface afwijkt van haar groep.
        DetachDecision detach = null;
        if (options.detachFromShared()) {
            if (divergesFromGroup && planned != null && definitionOrigin != null && definitionOrigin.isGroup()) {
                detach = planDetach(items, rows, interfaceCode, definition, link, options, planned, plannedHash);
                definitionAction = ACTION_CREATE;
                definitionCode = detach.toCode();
                revisionAction = detach.carriesActive() ? ACTION_SUCCESSOR_DRAFT : ACTION_CREATE;
                basedOn = null;
                templateAction = ACTION_NONE;
                templateId = null;
                members = List.of();
                if (link != null) {
                    linkAction = ACTION_REPOINT;
                }
            } else {
                items.add(new Item("PLAN:detach", WARNING, DETACH_NOT_NEEDED, null, ownCopy
                        ? "detachFromShared is ignored: this interface already has its own (detached) definition"
                        : "detachFromShared is ignored: this interface does not diverge from a shared definition"));
            }
        }
        if (link == null && detach == null && definition != null && organisation != null && libraryCode != null) {
            // S7: uk_import_link_scope (definitie, leverancier, bibliotheek) - al in de preview, niet pas bij APPLY.
            // Bij loskoppelen komt de nieuwe koppeling op de (nog lege) kopie: geen botsing mogelijk.
            for (ImportLink other : links.findByImportDefinitionId(definition.getId())) {
                if (other.getLibraryCode().equals(libraryCode)
                        && other.getSupplierOrganisation().getId().equals(organisation.getId())) {
                    rows.add("linkScope:" + other.getId() + ":" + other.getUpdatedAt());
                    items.add(new Item("PLAN:link", BLOCKING, LINK_SCOPE_CONFLICT_MANUAL,
                            "import_link.id=" + other.getId(), "link " + other.getCode() + " of the shared "
                                    + "definition " + definition.getCode() + " already uses library "
                                    + libraryCode + " for supplier " + supplierCode
                                    + "; two interfaces of one shared definition need different libraries"));
                }
            }
        }

        PlannedTemplate plannedTemplate;
        if (ACTION_CREATE.equals(templateAction)) {
            plannedTemplate = new PlannedTemplate(ACTION_CREATE, null, templateCode, null);
        } else if (templateId != null) {
            ImportDefinition template = definitions.findById(templateId).orElseThrow();
            rows.add("template:" + template.getId() + ":" + template.getUpdatedAt());
            plannedTemplate = new PlannedTemplate(ACTION_REUSE, template.getId(), template.getCode(), null);
        } else {
            plannedTemplate = new PlannedTemplate(ACTION_NONE, null, null, null);
        }
        // G2a: een eigen kopie (bestaand of door dit loskoppelen) deelt niets: sharedWith is leeg.
        List<String> sharedWith = detach != null || ownCopy ? List.of()
                : sharedWith(members, environment, dossier, supplierCode, definitionHash, interfaceCode);
        // 020-3 legacy_definition_codes: een interface alleen op haar definitie vervangt de code (S2); een groep
        // verzamelt de legacy-codes van haar leden; een losgekoppelde kopie draagt de code van haar interface.
        String legacyCodes = detach != null || definitionOrigin == null || (link != null && members.isEmpty())
                ? translation.definitionCode()
                : mergeCodes(definitionOrigin.getLegacyDefinitionCodes(), translation.definitionCode());

        String organisationName = organisationName(items, organisation, prodisSupplier, supplierCode);
        boolean blocked = items.stream().anyMatch(item -> BLOCKING.equals(item.status()));
        PlannedDefinition plannedDefinition = detach != null
                ? new PlannedDefinition(definitionAction, null, definitionCode, detach.toName(), revisionAction, null,
                null)
                : new PlannedDefinition(definitionAction, definition == null ? null : definition.getId(),
                definitionCode, definition == null ? translation.definitionName() : definition.getDescription(),
                revisionAction, basedOn == null ? null : basedOn.getId(), null);
        PlannedDetach plannedDetach = detach == null ? null : new PlannedDetach(detach.from().getId(),
                detach.from().getCode(), detach.toCode(), detach.carriesActive() ? detach.copied().getId() : null,
                detach.carriesActive());
        Planned plannedSection = new Planned(
                new PlannedOrganisation(organisationAction, organisation == null ? null : organisation.getId(),
                        supplierCode, organisationName),
                plannedDefinition,
                new PlannedLink(linkAction, link == null ? null : link.getId(), linkCode, libraryCode,
                        libraryCandidates),
                new PlannedTask(taskAction, taskId, taskName), plannedCredential, plannedTemplate, plannedDetach);
        LegacyConfigReport report = new LegacyConfigReport(receipt.getId(), receipt.getContentHash(),
                receipt.getStatus().name(), blocked ? LegacyConfigReport.OUTCOME_BLOCKED
                : LegacyConfigReport.OUTCOME_APPLICABLE, plannedSection, sharedWith, items,
                availability.name());
        String fingerprint = fingerprint(report, rows);
        if (availability == Availability.UNAVAILABLE) {
            // Design par. 6 ("unverified"): nooit gelijk aan een geverifieerde fingerprint, maar wel nog gebonden aan
            // het rapport en de geraakte rijen (anders zou een APPLY tijdens een Prodis-storing niet meer op
            // veroudering controleren).
            fingerprint = LegacyConfigReport.FINGERPRINT_UNVERIFIED_PREFIX + fingerprint;
        }
        return new Plan(report, fingerprint, translation, options, organisation, supplierCode, organisationName,
                definition,
                definitionCode, revisionAction, basedOn, link, linkCode, libraryCode, taskId, taskName, origin,
                definitionOrigin, plannedHash, templateCode, legacyCodes, detach);
    }

    // --- Loskoppelen (G2a) ----------------------------------------------------------------------------------------

    /** Toegevoegd aan de tekst van {@value #SHARED_DEFINITION_DIVERGED} voor een bekende interface. */
    private static final String DETACH_HINT_KNOWN = "; apply with detachFromShared=true to move this interface to "
            + "its own copy of the definition (its link, task and catalogue history stay)";
    /** Idem voor een nieuwe interface. */
    private static final String DETACH_HINT_NEW = "; apply with detachFromShared=true to give this interface its own "
            + "definition instead of joining the shared one";

    /**
     * G2a ({@code docs/design/g2-design.md} par. G2a; beslissingslog 2026-10-22 "G2a", V1 = A en V2 = A): plant de
     * eigen kopie van de gedeelde definitie {@code shared} voor een interface die afwijkt van haar groep. Vervangt
     * {@value #SHARED_DEFINITION_DIVERGED} door {@value #DETACH_FROM_SHARED} (MIGRATED) en voegt de blokkerende
     * controles toe:
     * <ul>
     *   <li>code: {@code LEGACY-<interface>} of {@code detachedDefinitionCode}; te lang →
     *       {@value #DETACHED_DEFINITION_CODE_INVALID}, al in gebruik bij de leverancier →
     *       {@value #DETACHED_DEFINITION_CODE_CONFLICT};</li>
     *   <li>bestaande koppeling (verleggen): geen open batch ({@value #DETACH_OPEN_BATCH}) en geen lopende run op een
     *       van haar taken ({@value #DETACH_RUN_IN_PROGRESS});</li>
     *   <li>revisies (bestaande koppeling): heeft de gedeelde definitie een ACTIVE revisie, dan wordt die revisie 1 van
     *       de kopie (geactiveerd) en de nieuwe inhoud de opvolgende DRAFT; daarvoor gelden de S2-regels op de gedeelde
     *       definitie: geen migratierevisie of handmatige wijziging sinds de migratie
     *       ({@value #MANUAL_CHANGES_SINCE_MIGRATION}), geen open DRAFT ({@value #OPEN_DRAFT_EXISTS}). Zonder ACTIVE
     *       revisie: revisie 1 = DRAFT met de nieuwe inhoud (een open DRAFT van de gedeelde definitie blijft bij de
     *       groep).</li>
     *   <li>nieuwe interface (design punt 8, zonder verleggen): revisie 1 = DRAFT met de nieuwe inhoud, niets
     *       geactiveerd - er loopt nog geen inname die stil zou vallen, dus de uitzondering van V2 op "APPLY activeert
     *       nooit" is hier niet nodig (gemelde interpretatie).</li>
     * </ul>
     */
    private DetachDecision planDetach(List<Item> items, List<String> rows, String interfaceCode,
                                      ImportDefinition shared, ImportLink link, ApplyOptions options,
                                      PlannedRevision planned, String plannedHash) {
        String element = "PLAN:detach";
        rows.add("detachFrom:" + shared.getId() + ":" + shared.getUpdatedAt());
        String toCode = options.detachedDefinitionCode() != null ? options.detachedDefinitionCode()
                : LINK_CODE_PREFIX + interfaceCode;
        String toName = detachedName(shared.getDescription(), interfaceCode);
        List<Item> found = new ArrayList<>();
        if (toCode.length() > MAX_DEFINITION_CODE) {
            found.add(new Item(element, BLOCKING, DETACHED_DEFINITION_CODE_INVALID, null, "detachedDefinitionCode is "
                    + "longer than " + MAX_DEFINITION_CODE + " characters"));
        } else {
            Optional<ImportDefinition> clash = definitions.findBySourceOrganisationIdAndCode(
                    shared.getSourceOrganisation().getId(), toCode);
            if (clash.isPresent()) {
                rows.add("detachClash:" + clash.get().getId() + ":" + clash.get().getUpdatedAt());
                found.add(new Item(element, BLOCKING, DETACHED_DEFINITION_CODE_CONFLICT,
                        "import_definition.id=" + clash.get().getId(), "a definition " + toCode + " already exists "
                                + "for supplier " + shared.getSourceOrganisation().getCode()
                                + "; choose another code with detachedDefinitionCode"));
            }
        }
        ImportDefinitionRevision copied = null;
        LegacyConfigRevisionOrigin inherited = null;
        if (link != null) {
            boolean openBatch = batches.existsByImportLinkIdAndOpenMarkerIsNotNull(link.getId());
            rows.add("detachOpenBatch:" + openBatch);
            if (openBatch) {
                found.add(new Item(element, BLOCKING, DETACH_OPEN_BATCH, "import_link.id=" + link.getId(),
                        "link " + link.getCode() + " has an open batch (still screening or waiting); finish it "
                                + "before detaching, every batch keeps the revision it was screened with"));
            }
            List<Long> running = new ArrayList<>();
            for (CatalogImportTask task : tasks.findByImportLinkIdOrderByIdAsc(link.getId())) {
                taskRuns.findByTaskIdAndConcurrencyTokenIsNotNull(task.getId())
                        .ifPresent(run -> running.add(run.getId()));
            }
            rows.add("detachRuns:" + running);
            if (!running.isEmpty()) {
                found.add(new Item(element, BLOCKING, DETACH_RUN_IN_PROGRESS, "task_run.id=" + running.get(0),
                        "a task of link " + link.getCode() + " has a run in progress; wait until it ends before "
                                + "detaching"));
            }
            copied = revisions.findByImportDefinitionIdAndStatus(shared.getId(), RevisionStatus.ACTIVE)
                    .orElse(null);
            if (copied != null) {
                inherited = detachRevisionChecks(found, rows, shared, copied, planned);
            } else {
                rows.add("detachActive:none");
            }
        }
        String message;
        if (link == null) {
            message = "interface " + interfaceCode + " gets its own definition " + toCode + " (based on shared "
                    + "definition " + shared.getCode() + ") instead of joining it; revision 1 is a DRAFT with its "
                    + "legacy content";
        } else if (copied != null) {
            message = "link " + link.getCode() + " moves from shared definition " + shared.getCode()
                    + " to its own copy " + toCode + "; its code, task, delivery configuration, credential and "
                    + "catalogue history stay. Revision 1 is an unchanged copy of ACTIVE revision "
                    + copied.getRevisionNumber() + " and is activated by this APPLY (approver: the person applying); "
                    + "the new legacy content becomes DRAFT revision 2";
        } else {
            message = "link " + link.getCode() + " moves from shared definition " + shared.getCode()
                    + " to its own copy " + toCode + "; its code, task, delivery configuration, credential and "
                    + "catalogue history stay. The shared definition has no ACTIVE revision: revision 1 is a DRAFT "
                    + "with the new legacy content (an open DRAFT of the shared definition stays with the group)";
        }
        Item detachItem = new Item(element, MIGRATED, DETACH_FROM_SHARED, "import_definition.code=" + toCode, message);
        int at = -1;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (SHARED_DEFINITION_DIVERGED.equals(items.get(i).code())) {
                items.remove(i);
                at = i;
            }
        }
        if (at < 0) {
            items.add(detachItem);
        } else {
            items.add(at, detachItem);
        }
        items.addAll(found);
        return new DetachDecision(shared, toCode, toName, copied, inherited);
    }

    /**
     * De S2-regels op de gedeelde definitie wanneer haar ACTIVE revisie {@code active} als revisie 1 van de kopie
     * meegaat (design G2a punt 5): de nieuwe inhoud in revisie 2 overschrijft wat legacy bepaalt, dus mag de ACTIVE
     * revisie niet handmatig afwijken van wat de migratie schreef, en een open DRAFT van de gedeelde definitie
     * blokkeert zoals bij een opvolgende DRAFT.
     *
     * @return de 020-4-rij van de laatst gemigreerde revisie (die revisie 1 erft), of {@code null} als een blokkerend
     *     item toegevoegd werd
     */
    private LegacyConfigRevisionOrigin detachRevisionChecks(List<Item> found, List<String> rows, ImportDefinition shared,
                                                           ImportDefinitionRevision active, PlannedRevision planned) {
        String element = "PLAN:detach";
        rows.add("detachActive:" + active.getId() + ":" + active.getUpdatedAt());
        ImportDefinitionRevision latest = revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(shared.getId())
                .get(0);
        rows.add("detachLatest:" + latest.getId() + ":" + latest.getStatus() + ":" + latest.getUpdatedAt());
        LastMigrated lastMigrated = lastMigrated(shared);
        if (identityDiffers(active, planned)) {
            found.add(new Item(element, WARNING, IDENTITY_CHANGE, "revision.identity*", "the identity definition "
                    + "of the own copy changes in DRAFT revision 2 (profile, identity columns, fixed values or upper "
                    + "case): every existing offer can come back as NEW once it is activated. APPLY counts as the "
                    + "acknowledgement (R-REV-X3)"));
        }
        if (lastMigrated == null) {
            found.add(new Item(element, BLOCKING, MANUAL_CHANGES_SINCE_MIGRATION, "import_definition.id="
                    + shared.getId(), "shared definition " + shared.getCode() + " has no migrated revision; its "
                    + "ACTIVE revision is never copied and overwritten"));
            return null;
        }
        if (latest.getStatus() == RevisionStatus.DRAFT) {
            found.add(new Item(element, BLOCKING, OPEN_DRAFT_EXISTS, "revision.id=" + latest.getId(),
                    "revision " + latest.getRevisionNumber() + " of shared definition " + shared.getCode()
                            + " is an open DRAFT; finish or activate it first"));
            return null;
        }
        rows.add("detachLastMigrated:" + lastMigrated.revision().getId());
        String activeHash = LegacyRevisionContent.ofRevision(active,
                fieldMappings.findByRevisionIdWithTargetField(active.getId()),
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(active.getId()),
                valueMaps.findByRevisionId(active.getId()), transformSteps.findByRevisionId(active.getId()));
        rows.add("detachActiveContent:" + activeHash);
        if (!activeHash.equals(lastMigrated.hash())) {
            found.add(new Item(element, BLOCKING, MANUAL_CHANGES_SINCE_MIGRATION, "revision.id=" + active.getId(),
                    "ACTIVE revision " + active.getRevisionNumber() + " of shared definition " + shared.getCode()
                            + " differs (fields, mappings or filters) from what the migration wrote in revision "
                            + lastMigrated.revision().getRevisionNumber() + "; the new legacy content would overwrite "
                            + "those changes in the own copy"));
            return null;
        }
        return revisionOrigins.findByRevisionId(lastMigrated.revision().getId()).orElseThrow();
    }

    /** "&lt;gedeelde beschrijving&gt; (losgekoppeld voor &lt;interface&gt;)", ingekort tot 200 tekens met "...". */
    static String detachedName(String sharedDescription, String interfaceCode) {
        String suffix = String.format(Locale.ROOT, DETACHED_NAME_SUFFIX, interfaceCode);
        String base = sharedDescription == null ? "" : sharedDescription.strip();
        if (base.length() + suffix.length() > MAX_DEFINITION_NAME) {
            base = base.substring(0, MAX_DEFINITION_NAME - suffix.length() - 3).stripTrailing() + "...";
        }
        return base + suffix;
    }

    // --- Prodis-controle (S5) -------------------------------------------------------------------------------------

    /**
     * Vervangt het S2-item {@code PRODIS_REFERENCE_NOT_VERIFIED} van {@code INDET:Supplier} (uit de vertaler) door de
     * uitkomst van de Prodis-controle, op dezelfde plaats in het rapport, en neemt de gebruikte Prodis-gegevens op in de
     * fingerprintrijen. Niet geconfigureerd: het S2-item blijft ongewijzigd.
     *
     * @return de Prodis-leverancier als ze precies één keer bestaat, anders {@code null}
     */
    private static SupplierRow verifySupplier(List<Item> items, List<String> rows, LegacyLookup lookup,
                                              String supplierCode) {
        if (supplierCode == null || lookup.availability() == Availability.NOT_CONFIGURED) {
            return null;
        }
        String element = "INDET:" + LegacyConfigTranslator.LABEL_SUPPLIER;
        String notVerified = LegacyConfigTranslator.PRODIS_REFERENCE_NOT_VERIFIED;
        if (lookup.availability() == Availability.UNAVAILABLE) {
            replaceItem(items, element, notVerified, new Item(element, WARNING, notVerified, null, "supplier "
                    + supplierCode + " is not verified: the Prodis database is not available right now; the name of a "
                    + "new source organisation is a placeholder"));
            return null;
        }
        for (SupplierRow supplier : lookup.suppliers()) {
            rows.add("prodis:supplier:" + supplier.number() + "|" + supplier.name());
        }
        for (SupplierCatalogRow catalog : lookup.catalogs()) {
            rows.add("prodis:catalog:" + catalog.scopeKey() + "|" + catalog.legacyNumber() + "|"
                    + catalog.catalogName() + "|" + catalog.status());
        }
        if (lookup.suppliers().isEmpty()) {
            replaceItem(items, element, notVerified, new Item(element, BLOCKING, PRODIS_SUPPLIER_NOT_FOUND, null,
                    "supplier " + supplierCode + " does not exist in Prodis (supplier.number); the source organisation "
                            + "and the link cannot be determined"));
            return null;
        }
        if (lookup.suppliers().size() > 1) {
            replaceItem(items, element, notVerified, new Item(element, BLOCKING, PRODIS_SUPPLIER_AMBIGUOUS, null,
                    "supplier number " + supplierCode + " occurs more than once in Prodis (supplier.number is not "
                            + "unique there); it cannot be verified"));
            return null;
        }
        SupplierRow supplier = lookup.suppliers().get(0);
        replaceItem(items, element, notVerified, new Item(element, MIGRATED, PRODIS_SUPPLIER,
                "prodis.supplier.number=" + supplier.number(), "verified in Prodis: " + supplier.name()));
        return supplier;
    }

    /**
     * S6 (design par. 5 rijen 9/10): een vaste leverancier/groep moet in Prodis bestaan. Gecontroleerd wordt de waarde
     * zoals de screening ze gebruikt, dus in hoofdletters (een gemigreerde revisie heeft altijd
     * {@code identity_uppercase=true}). De groep hoort bij de leverancier van de identiteit: de vaste leverancier,
     * anders INDET {@code Supplier}. Vervangt het item {@code PRODIS_REFERENCE_NOT_VERIFIED} van de vertaler op zijn
     * plaats (zoals {@link #verifySupplier}) en neemt de gelezen Prodis-rijen op in de fingerprint.
     * <ul>
     *   <li>niet geconfigureerd: de vertaleritems blijven ({@code PRODIS_REFERENCE_NOT_VERIFIED});</li>
     *   <li>onbereikbaar: idem, met de reden; de preview is "unverified" (design par. 6, zoals de leverancier in S5);</li>
     *   <li>geverifieerd: leverancier 0 of 2 keer → {@value #PRODIS_SUPPLIER_NOT_FOUND} /
     *       {@value #PRODIS_SUPPLIER_AMBIGUOUS}, groep niet bij die leverancier → {@value #PRODIS_SUPPLIER_GROUP_NOT_FOUND}
     *       (blokkerend).</li>
     * </ul>
     *
     * @return de samengevoegde beschikbaarheid: {@code UNAVAILABLE} zodra één van beide lezingen faalde
     */
    private Availability verifyFixedValues(List<Item> items, List<String> rows, Translation translation,
                                           String supplierCode, Availability supplierAvailability) {
        String supplierFixed = translation.supplierFixedValue();
        String groupFixed = translation.groupFixedValue();
        if (supplierFixed == null && groupFixed == null) {
            return supplierAvailability;
        }
        String supplier = supplierFixed == null ? null : supplierFixed.toUpperCase(Locale.ROOT);
        String group = groupFixed == null ? null : groupFixed.toUpperCase(Locale.ROOT);
        String groupSupplier = group == null ? null : supplier != null ? supplier : supplierCode;
        ProdisReferenceService.FixedIdentityLookup lookup = prodis.lookupFixedIdentity(supplier, groupSupplier);
        Availability fixedAvailability = lookup.availability();
        if (fixedAvailability == Availability.NOT_CONFIGURED) {
            return supplierAvailability;
        }
        String notVerified = LegacyConfigTranslator.PRODIS_REFERENCE_NOT_VERIFIED;
        String supplierElement = "PSIMPDEF#" + LegacyConfigTranslator.LINE_SUPPLIER;
        String groupElement = "PSIMPDEF#" + LegacyConfigTranslator.LINE_GROUP;
        if (fixedAvailability == Availability.UNAVAILABLE) {
            if (supplier != null) {
                replaceItem(items, supplierElement, notVerified, new Item(supplierElement, WARNING, notVerified, null,
                        "fixed supplier " + supplier + " is not verified: the Prodis database is not available right "
                                + "now"));
            }
            if (group != null) {
                replaceItem(items, groupElement, notVerified, new Item(groupElement, WARNING, notVerified, null,
                        "fixed supplier group " + group + " is not verified: the Prodis database is not available "
                                + "right now"));
            }
            return Availability.UNAVAILABLE;
        }
        if (supplier != null) {
            for (SupplierRow row : lookup.suppliers()) {
                rows.add("prodisFixedSupplier:" + row.number() + "|" + row.name());
            }
            Item outcome;
            if (lookup.suppliers().isEmpty()) {
                outcome = new Item(supplierElement, BLOCKING, PRODIS_SUPPLIER_NOT_FOUND, null, "fixed supplier "
                        + supplier + " does not exist in Prodis (supplier.number)");
            } else if (lookup.suppliers().size() > 1) {
                outcome = new Item(supplierElement, BLOCKING, PRODIS_SUPPLIER_AMBIGUOUS, null, "fixed supplier "
                        + supplier + " occurs more than once in Prodis (supplier.number is not unique)");
            } else {
                outcome = new Item(supplierElement, MIGRATED, PRODIS_SUPPLIER, "prodis.supplier.number=" + supplier,
                        "verified in Prodis: " + lookup.suppliers().get(0).name());
            }
            replaceItem(items, supplierElement, notVerified, outcome);
        }
        if (group != null) {
            for (SupplierGroupRow row : lookup.groups()) {
                rows.add("prodisGroup:" + row.legacyCode() + "|" + row.groupName() + "|" + row.discountCode());
            }
            Item outcome = lookup.hasGroup(group)
                    ? new Item(groupElement, MIGRATED, PRODIS_SUPPLIER_GROUP, "prodis.supplier_group.group_name="
                    + group, "verified in Prodis: a group of supplier " + groupSupplier)
                    : new Item(groupElement, BLOCKING, PRODIS_SUPPLIER_GROUP_NOT_FOUND, null, "fixed supplier group "
                    + group + " is not a group (supplier_group.group_name) of supplier " + groupSupplier
                    + " in Prodis");
            replaceItem(items, groupElement, notVerified, outcome);
        }
        return supplierAvailability == Availability.UNAVAILABLE ? Availability.UNAVAILABLE : Availability.VERIFIED;
    }

    /**
     * De naam van de bronorganisatie: een bestaande organisatie houdt haar naam (nooit hernoemd); een nieuwe krijgt de
     * Prodis-naam, of zonder Prodis-controle de plaatshouder "Leverancier &lt;nr&gt;" (S2).
     */
    private static String organisationName(List<Item> items, SourceOrganisation organisation, SupplierRow prodisSupplier,
                                           String supplierCode) {
        String element = "INDET:" + LegacyConfigTranslator.LABEL_SUPPLIER;
        if (organisation != null) {
            if (prodisSupplier != null && prodisSupplier.name() != null
                    && organisation.getCode().equals(supplierCode)
                    && !prodisSupplier.name().strip().equals(organisation.getName())) {
                items.add(new Item(element, LegacyConfigReport.NOT_MIGRATED, SOURCE_ORGANISATION_NAME_KEPT,
                        "source_organisation.id=" + organisation.getId(), "the existing source organisation keeps its "
                                + "name '" + organisation.getName() + "'; Prodis names it '"
                                + prodisSupplier.name().strip() + "'"));
            }
            return organisation.getName();
        }
        if (supplierCode == null) {
            return null;
        }
        String placeholder = ORGANISATION_NAME_PREFIX + supplierCode;
        if (prodisSupplier == null) {
            return placeholder;
        }
        String name = prodisSupplier.name() == null ? "" : prodisSupplier.name().strip();
        if (name.isEmpty()) {
            items.add(new Item(element, WARNING, PRODIS_SUPPLIER_NAME_EMPTY, "source_organisation.name=" + placeholder,
                    "supplier " + supplierCode + " has no name in Prodis; the new source organisation gets a placeholder"));
            return placeholder;
        }
        if (name.length() > MAX_ORGANISATION_NAME) {
            items.add(new Item(element, BLOCKING, PRODIS_SUPPLIER_NAME_TOO_LONG, null, "the Prodis name of supplier "
                    + supplierCode + " is longer than " + MAX_ORGANISATION_NAME + " characters; it is never truncated"));
            return placeholder;
        }
        return name;
    }

    private record LibraryDecision(String libraryCode, List<LibraryCandidate> candidates) {
    }

    /**
     * De bibliotheekcode van een nieuwe koppeling (A3: {@code supplier_catalog.scope_key} via {@code legacy_number} =
     * {@code ArtImp_BibNr}; nooit het ruwe {@code ArtImp_BibNr}).
     */
    private static LibraryDecision resolveLibrary(List<Item> items, LegacyLookup lookup, SupplierRow prodisSupplier,
                                                  String libraryNumber, String given) {
        String element = "INDET:" + LegacyConfigTranslator.LABEL_LIBRARY;
        if (libraryNumber == null) {
            return new LibraryDecision(given, List.of()); // LIBRARY_NOT_DECLARED blokkeert al (vertaler)
        }
        if (given != null && given.length() > MAX_LIBRARY_CODE) {
            items.add(new Item(element, BLOCKING, LIBRARY_CODE_INVALID, null,
                    "libraryCode is longer than " + MAX_LIBRARY_CODE + " characters"));
            return new LibraryDecision(given, List.of());
        }
        if (lookup.availability() != Availability.VERIFIED) {
            String why = lookup.availability() == Availability.NOT_CONFIGURED
                    ? "the Prodis database is not configured" : "the Prodis database is not available right now";
            if (given == null) {
                items.add(new Item(element, BLOCKING, LIBRARY_CODE_NOT_RESOLVED, null, "legacy library " + libraryNumber
                        + " cannot be resolved to a library code: " + why + "; give libraryCode "
                        + "(supplier_catalog.scope_key)"));
            } else {
                items.add(new Item(element, MIGRATED, "LIBRARY_CODE", "import_link.libraryCode=" + given,
                        "given by the person applying"));
                items.add(new Item(element, WARNING, LegacyConfigTranslator.PRODIS_REFERENCE_NOT_VERIFIED, null,
                        "libraryCode " + given + " for legacy library " + libraryNumber + " is not verified: " + why));
            }
            return new LibraryDecision(given, List.of());
        }
        if (prodisSupplier == null) {
            // De leverancier is niet (eenduidig) in Prodis: PRODIS_SUPPLIER_* of SUPPLIER_NOT_DECLARED blokkeert al.
            items.add(given == null
                    ? new Item(element, BLOCKING, LIBRARY_CODE_NOT_RESOLVED, null, "legacy library " + libraryNumber
                    + " cannot be resolved: the supplier is not (uniquely) known in Prodis")
                    : new Item(element, BLOCKING, LIBRARY_CODE_NOT_IN_PRODIS, null, "libraryCode " + given
                    + " cannot be verified: the supplier is not (uniquely) known in Prodis"));
            return new LibraryDecision(given, List.of());
        }
        String number = libraryNumber.strip();
        Set<String> matchingScopes = new LinkedHashSet<>();
        for (SupplierCatalogRow row : lookup.catalogs()) {
            if (row.legacyNumber() != null && row.legacyNumber().strip().equals(number)) {
                matchingScopes.add(row.scopeKey());
            }
        }
        List<LibraryCandidate> all = new ArrayList<>();
        List<LibraryCandidate> matches = new ArrayList<>();
        for (SupplierCatalogRow library : ProdisReferenceService.libraries(lookup.catalogs())) {
            LibraryCandidate candidate = new LibraryCandidate(library.scopeKey(), library.catalogName(),
                    library.legacyNumber(), library.status());
            all.add(candidate);
            if (matchingScopes.contains(library.scopeKey())) {
                matches.add(candidate);
            }
        }
        String supplier = prodisSupplier.number();
        if (given == null) {
            if (matches.size() == 1) {
                LibraryCandidate match = matches.get(0);
                if (match.scopeKey().length() > MAX_LIBRARY_CODE) {
                    items.add(new Item(element, BLOCKING, LIBRARY_CODE_INVALID, null, "Prodis library "
                            + match.scopeKey() + " is longer than " + MAX_LIBRARY_CODE + " characters"));
                    return new LibraryDecision(null, matches);
                }
                items.add(new Item(element, MIGRATED, "LIBRARY_CODE", "import_link.libraryCode=" + match.scopeKey(),
                        "resolved from Prodis: library " + match.scopeKey() + " (" + match.catalogName()
                                + ") of supplier " + supplier + " has legacy_number " + number));
                return new LibraryDecision(match.scopeKey(), List.of());
            }
            List<LibraryCandidate> candidates = matches.isEmpty() ? all : matches;
            items.add(new Item(element, BLOCKING, LIBRARY_CODE_NOT_RESOLVED, null, (matches.isEmpty()
                    ? "no Prodis library of supplier " + supplier + " has legacy_number " + number
                    : matches.size() + " Prodis libraries of supplier " + supplier + " have legacy_number " + number)
                    + "; give libraryCode, one of: " + describe(candidates)));
            return new LibraryDecision(null, candidates);
        }
        if (all.stream().noneMatch(candidate -> candidate.scopeKey().equals(given))) {
            List<LibraryCandidate> candidates = matches.isEmpty() ? all : matches;
            items.add(new Item(element, BLOCKING, LIBRARY_CODE_NOT_IN_PRODIS, null, "libraryCode " + given
                    + " is not a library (supplier_catalog.scope_key) of supplier " + supplier + " in Prodis; one of: "
                    + describe(candidates)));
            return new LibraryDecision(given, candidates);
        }
        items.add(new Item(element, MIGRATED, "LIBRARY_CODE", "import_link.libraryCode=" + given,
                "given by the person applying; a library of supplier " + supplier + " in Prodis"));
        if (!matchingScopes.contains(given)) {
            items.add(new Item(element, WARNING, LIBRARY_CODE_NOT_LEGACY_MATCH, "import_link.libraryCode=" + given,
                    "Prodis library " + given + " does not have legacy_number " + number + " (ArtImp_BibNr)"));
        }
        return new LibraryDecision(given, List.of());
    }

    /** Hoogstens 20 kandidaten in de boodschap; de volledige lijst staat in {@code planned.link.libraryCandidates}. */
    private static String describe(List<LibraryCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "(the supplier has no library in Prodis)";
        }
        StringBuilder text = new StringBuilder();
        int shown = Math.min(candidates.size(), 20);
        for (int i = 0; i < shown; i++) {
            LibraryCandidate candidate = candidates.get(i);
            text.append(i == 0 ? "" : ", ").append(candidate.scopeKey()).append(" (").append(candidate.catalogName())
                    .append(')');
        }
        if (candidates.size() > shown) {
            text.append(", ... (").append(candidates.size() - shown).append(" more)");
        }
        return text.toString();
    }

    /** Vervangt het eerste item met dit element en deze code; bestaat het niet, dan wordt het achteraan toegevoegd. */
    private static void replaceItem(List<Item> items, String element, String code, Item replacement) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).element().equals(element) && items.get(i).code().equals(code)) {
                items.set(i, replacement);
                return;
            }
        }
        items.add(replacement);
    }

    private record RevisionDecision(String action, ImportDefinitionRevision basedOn) {
    }

    /** Bekende interface: geen nieuwe revisie, een opvolgende DRAFT, of blokkeren (V2 = C). */
    private RevisionDecision revisionDecision(ImportDefinition definition, PlannedRevision planned, String plannedHash,
                                              List<Item> items, List<String> rows) {
        List<ImportDefinitionRevision> all = revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(
                definition.getId());
        Map<Long, LegacyConfigRevisionOrigin> migrated = new LinkedHashMap<>();
        revisionOrigins.findByRevisionIdIn(all.stream().map(ImportDefinitionRevision::getId).toList())
                .forEach(row -> migrated.put(row.getRevisionId(), row));
        ImportDefinitionRevision latest = all.isEmpty() ? null : all.get(0);
        ImportDefinitionRevision lastMigrated = all.stream().filter(r -> migrated.containsKey(r.getId()))
                .findFirst().orElse(null);
        String element = "PLAN:definition";
        if (latest == null || lastMigrated == null) {
            items.add(new Item(element, BLOCKING, MANUAL_CHANGES_SINCE_MIGRATION, "import_definition.id="
                    + definition.getId(), "the definition has no migrated revision; it is never overwritten"));
            return new RevisionDecision(ACTION_NONE, null);
        }
        rows.add("latestRevision:" + latest.getId() + ":" + latest.getStatus() + ":" + latest.getUpdatedAt());
        rows.add("lastMigrated:" + lastMigrated.getId());
        String storedHash = migrated.get(lastMigrated.getId()).getMigratedContentHash();
        if (plannedHash.equals(storedHash)) {
            items.add(new Item(element, MIGRATED, DEFINITION_UNCHANGED, "revision.id=" + lastMigrated.getId(),
                    "the receipt yields exactly what migration revision " + lastMigrated.getRevisionNumber()
                            + " got; no new revision"));
            return new RevisionDecision(ACTION_UNCHANGED, null);
        }
        if (latest.getStatus() == RevisionStatus.DRAFT) {
            items.add(new Item(element, BLOCKING, OPEN_DRAFT_EXISTS, "revision.id=" + latest.getId(),
                    "revision " + latest.getRevisionNumber() + " is an open DRAFT; finish or activate it first"));
            return new RevisionDecision(ACTION_NONE, null);
        }
        if (!CLONEABLE.contains(latest.getStatus())) {
            items.add(new Item(element, BLOCKING, REVISION_NOT_CLONEABLE, "revision.id=" + latest.getId(),
                    "revision " + latest.getRevisionNumber() + " is " + latest.getStatus()
                            + "; only an ACTIVE or SUPERSEDED revision is copied into a successor"));
            return new RevisionDecision(ACTION_NONE, null);
        }
        String latestHash = LegacyRevisionContent.ofRevision(latest,
                fieldMappings.findByRevisionIdWithTargetField(latest.getId()),
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(latest.getId()),
                valueMaps.findByRevisionId(latest.getId()), transformSteps.findByRevisionId(latest.getId()));
        rows.add("latestContent:" + latestHash);
        if (!latestHash.equals(storedHash)) {
            items.add(new Item(element, BLOCKING, MANUAL_CHANGES_SINCE_MIGRATION, "revision.id=" + latest.getId(),
                    "revision " + latest.getRevisionNumber() + " differs (fields, mappings or filters) from what "
                            + "the migration wrote in revision " + lastMigrated.getRevisionNumber()
                            + "; a new legacy version would overwrite those changes"));
            return new RevisionDecision(ACTION_NONE, null);
        }
        if (identityDiffers(latest, planned)) {
            items.add(new Item(element, WARNING, IDENTITY_CHANGE, "revision.identity*", "the identity definition "
                    + "changes (profile, identity columns, fixed values or upper case): every existing offer can come "
                    + "back as NEW. APPLY "
                    + "counts as the acknowledgement (R-REV-X3)"));
        }
        items.add(new Item(element, MIGRATED, ACTION_SUCCESSOR_DRAFT, "revision.basedOn=" + latest.getId(),
                "a successor DRAFT of revision " + latest.getRevisionNumber() + "; the ACTIVE revision is not touched"));
        return new RevisionDecision(ACTION_SUCCESSOR_DRAFT, latest);
    }

    // --- Gedeelde definities (S7) ---------------------------------------------------------------------------------

    /** Een bestaande groep (020-3-rij) en of ze op de inhoudshash of enkel op de legacy-code gevonden werd. */
    private record GroupMatch(LegacyConfigDefinitionOrigin origin, boolean byHash) {
    }

    /**
     * De groep van een nieuwe interface: eerst op (omgeving, dossier, leverancier, {@code definitionContentHash}), anders
     * op dezelfde legacy-code onder dezelfde leverancier (beslissingslog 2026-10-21 S7). Nooit over leveranciers heen.
     */
    private GroupMatch findGroup(String environment, String dossier, String supplierCode, String definitionHash,
                                 String legacyCode, List<String> rows) {
        if (supplierCode == null) {
            return null;
        }
        if (definitionHash != null) {
            Optional<LegacyConfigDefinitionOrigin> byHash = definitionOrigins
                    .findByOriginEnvironmentAndOriginDossierAndSupplierCodeAndDefinitionContentHash(environment,
                            dossier, supplierCode, definitionHash);
            if (byHash.isPresent()) {
                rows.add("group:" + byHash.get().getId() + ":" + byHash.get().getUpdatedAt());
                return new GroupMatch(byHash.get(), true);
            }
        }
        if (legacyCode == null) {
            return null;
        }
        for (LegacyConfigDefinitionOrigin candidate : definitionOrigins
                .findByOriginEnvironmentAndOriginDossierAndSupplierCodeOrderByIdAsc(environment, dossier, supplierCode)) {
            if (codes(candidate.getLegacyDefinitionCodes()).contains(legacyCode)) {
                rows.add("groupByCode:" + candidate.getId() + ":" + candidate.getUpdatedAt());
                return new GroupMatch(candidate, false);
            }
        }
        return null;
    }

    /**
     * De andere interfaces (codes, gesorteerd) waarvan de koppeling op deze definitie staat. Handmatige koppelingen
     * (zonder herkomst) zijn geen interface en tellen niet mee.
     */
    private List<String> memberInterfaces(long definitionId, String interfaceCode, List<String> rows) {
        List<Long> linkIds = links.findByImportDefinitionId(definitionId).stream().map(ImportLink::getId).toList();
        if (linkIds.isEmpty()) {
            return List.of();
        }
        List<String> members = new ArrayList<>();
        for (LegacyConfigOrigin member : origins.findByImportLinkIdInOrderByInterfaceCodeAsc(linkIds)) {
            if (!member.getInterfaceCode().equals(interfaceCode)) {
                members.add(member.getInterfaceCode());
                rows.add("member:" + member.getId() + ":" + member.getInterfaceCode() + ":" + member.getUpdatedAt());
            }
        }
        return List.copyOf(members);
    }

    /**
     * Een bekende interface waarvan de legacy-inhoud nu gelijk is aan die van een <b>andere</b> groep van dezelfde
     * leverancier: ze kan niet verhuizen (een koppeling wordt nooit gewijzigd; loskoppelen is een latere slice) en de
     * 020-3-hash kan niet ter plaatse naar een bezette sleutel.
     *
     * @return {@code true} als dat zo is (dan staat er een blokkerend item)
     */
    private boolean otherGroupCheck(List<Item> items, List<String> rows, String environment, String dossier,
                                    String supplierCode, String definitionHash,
                                    LegacyConfigDefinitionOrigin definitionOrigin) {
        if (supplierCode == null || definitionHash == null || definitionOrigin == null
                || definitionHash.equals(definitionOrigin.getDefinitionContentHash())) {
            return false;
        }
        Optional<LegacyConfigDefinitionOrigin> other = definitionOrigins
                .findByOriginEnvironmentAndOriginDossierAndSupplierCodeAndDefinitionContentHash(environment, dossier,
                        supplierCode, definitionHash);
        if (other.isEmpty() || other.get().getId().equals(definitionOrigin.getId())) {
            return false;
        }
        rows.add("otherGroup:" + other.get().getId() + ":" + other.get().getUpdatedAt());
        items.add(new Item("PLAN:definition", BLOCKING, SHARED_DEFINITION_DIVERGED,
                "import_definition.id=" + other.get().getImportDefinitionId(), "the legacy definition of this "
                        + "interface now equals the one of another shared definition of supplier " + supplierCode
                        + "; an interface never joins another shared definition" + DETACH_HINT_KNOWN));
        return true;
    }

    /**
     * Bekende interface van een gedeelde definitie met nieuwe inhoud: enkel als élk ander lid in zijn laatste ontvangst
     * dezelfde nieuwe {@code definitionContentHash} heeft, volgt de gedeelde definitie (S2-regels); anders
     * {@value #SHARED_DEFINITION_DIVERGED}. Een gelijke {@code definitionContentHash} met andere geplande inhoud wil
     * zeggen dat enkel de interface-instellingen van deze interface wijzigden: ook dat is een afwijking van de groep.
     *
     * @return {@code true} als de groep uiteenloopt (dan staat er een blokkerend item)
     */
    private boolean membersDiverge(List<Item> items, List<String> rows, LegacyConfigReceipt receipt,
                                   LegacyConfigDefinitionOrigin definitionOrigin, ImportDefinition definition,
                                   List<String> members) {
        String newHash = receipt.getDefinitionContentHash();
        String element = "PLAN:definition";
        String target = "import_definition.id=" + definition.getId();
        if (newHash == null || newHash.equals(definitionOrigin.getDefinitionContentHash())) {
            items.add(new Item(element, BLOCKING, SHARED_DEFINITION_DIVERGED, target, "the legacy definition is "
                    + "unchanged, but the interface settings (structure or identity) of " + receipt.getInterfaceCode()
                    + " now differ from shared definition " + definition.getCode() + describeMembers(members)
                    + DETACH_HINT_KNOWN));
            return true;
        }
        List<String> behind = new ArrayList<>();
        for (String member : members) {
            Optional<LegacyConfigReceipt> latest = receipts
                    .findFirstByOriginEnvironmentAndOriginDossierAndInterfaceCodeOrderByIdDesc(
                            receipt.getOriginEnvironment(), receipt.getOriginDossier(), member);
            rows.add("memberReceipt:" + member + ":" + latest.map(LegacyConfigReceipt::getId).orElse(null));
            if (latest.isEmpty() || !newHash.equals(latest.get().getDefinitionContentHash())) {
                behind.add(member);
            }
        }
        if (behind.isEmpty()) {
            return false;
        }
        items.add(new Item(element, BLOCKING, SHARED_DEFINITION_DIVERGED, target, "only part of the interfaces "
                + "of shared definition " + definition.getCode() + " deliver the new legacy content: "
                + String.join(", ", behind) + " still deliver(s) the previous content" + DETACH_HINT_KNOWN));
        return true;
    }

    /** De laatst gemigreerde revisie van een definitie en haar {@code migrated_content_hash} (020-4). */
    private record LastMigrated(ImportDefinitionRevision revision, String hash) {
    }

    private LastMigrated lastMigrated(ImportDefinition definition) {
        List<ImportDefinitionRevision> all = revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(
                definition.getId());
        Map<Long, LegacyConfigRevisionOrigin> migrated = new LinkedHashMap<>();
        revisionOrigins.findByRevisionIdIn(all.stream().map(ImportDefinitionRevision::getId).toList())
                .forEach(row -> migrated.put(row.getRevisionId(), row));
        return all.stream().filter(r -> migrated.containsKey(r.getId())).findFirst()
                .map(r -> new LastMigrated(r, migrated.get(r.getId()).getMigratedContentHash())).orElse(null);
    }

    /**
     * {@code sharedWith}: de andere leden plus de interfaces waarvan de laatste ontvangst (niet {@code DISCARDED}) dezelfde
     * leverancier en {@code definitionContentHash} heeft; gesorteerd, zonder deze interface en (G2a) zonder interfaces
     * die losgekoppeld zijn (een 020-3-rij met hun {@code detached_interface_code}).
     */
    private List<String> sharedWith(List<String> members, String environment, String dossier, String supplierCode,
                                    String definitionHash, String interfaceCode) {
        Set<String> shared = new TreeSet<>(members);
        if (supplierCode != null && definitionHash != null) {
            for (LegacyConfigReceipt other
                    : receipts.findLatestWithDefinitionContentHash(environment, dossier, definitionHash)) {
                if (other.getInterfaceCode().equals(interfaceCode)
                        || other.getStatus() == LegacyConfigReceiptStatus.DISCARDED) {
                    continue;
                }
                // G2a: een losgekoppelde interface heeft haar eigen definitie en hoort niet meer bij de groep.
                if (definitionOrigins.findByOriginEnvironmentAndOriginDossierAndDetachedInterfaceCode(environment,
                        dossier, other.getInterfaceCode()).isPresent()) {
                    continue;
                }
                if (supplierCode.equals(LegacyConfigTranslator.supplierOf(readBundle(other)))) {
                    shared.add(other.getInterfaceCode());
                }
            }
        }
        return List.copyOf(shared);
    }

    private static Set<String> codes(String legacyDefinitionCodes) {
        Set<String> codes = new TreeSet<>();
        for (String code : legacyDefinitionCodes.split(",")) {
            if (!code.isBlank()) {
                codes.add(code.strip());
            }
        }
        return codes;
    }

    /** De legacy-codes van een groep, gesorteerd en zonder dubbels, gescheiden door een komma (020-3). */
    private static String mergeCodes(String existing, String added) {
        Set<String> codes = codes(existing);
        if (added != null && !added.isBlank()) {
            codes.add(added.strip());
        }
        return String.join(",", codes);
    }

    private static String describeMembers(List<String> members) {
        return members.isEmpty() ? "" : " (used by " + String.join(", ", members) + ")";
    }

    private static boolean identityDiffers(ImportDefinitionRevision latest, PlannedRevision planned) {
        return latest.getIdentityProfileKind() != planned.identityProfileKind()
                || !Objects.equals(latest.getIdentitySupplierField(), planned.supplierField())
                || !Objects.equals(latest.getIdentitySupplierGroupField(), planned.groupField())
                || !Objects.equals(latest.getIdentitySupplierReferenceField(), planned.referenceField())
                || !Objects.equals(latest.getIdentityDiscountCodeField(), planned.discountField())
                || !Objects.equals(latest.getIdentitySupplierFixedValue(), planned.supplierFixedValue())
                || !Objects.equals(latest.getIdentitySupplierGroupFixedValue(), planned.groupFixedValue())
                || latest.isIdentityUppercase() != planned.identityUppercase();
    }

    // --- Uitvoeren ------------------------------------------------------------------------------------------------

    private ApplyResult execute(LegacyConfigReceipt receipt, Plan plan, String reason, ActorIdentity actor) {
        Instant now = clock.instant();
        Translation translation = plan.translation();
        PlannedRevision planned = translation.revision();

        SourceOrganisation organisation = plan.organisation();
        if (organisation == null) {
            setup.createSourceOrganisation(new SetupService.CreateSourceOrganisationCommand(plan.organisationCode(),
                    plan.organisationName(), SourceOrganisationType.SUPPLIER));
            organisation = organisations.findByCode(plan.organisationCode()).orElseThrow();
        }

        long definitionId;
        Long templateId = null;
        Long templateRevisionId = null;
        Long revisionId = null;
        DetachDecision detach = plan.detach();
        if (detach != null) {
            // G2a: de eigen kopie (revisie 1 en eventueel de opvolgende DRAFT met de nieuwe inhoud).
            DetachedCopy copy = createDetachedCopy(receipt, plan, reason, actor, now);
            definitionId = copy.definitionId();
            revisionId = copy.revisionId();
        } else if (plan.definition() == null) {
            // S7 (A2): altijd eerst het sjabloon met de gemigreerde inhoud, dan de gedeelde definitie als kopie ervan.
            templateId = setup.createDefinition(new SetupService.CreateDefinitionCommand(organisation.getCode(),
                    plan.templateCode(), translation.definitionName(), DefinitionUsageType.REUSABLE_TEMPLATE),
                    actor).id();
            // S6: de vaste waarden zijn in plan() al tegen Prodis gecontroleerd (of bewust "unverified", design par. 6);
            // SetupService controleert ze daarom niet opnieuw.
            templateRevisionId = setup.createRevision(templateId, createCommand(planned, reason), actor, false).id();
            writeRevisionContent(templateRevisionId, planned, actor);
            verifyWritten(receipt, templateRevisionId, plan.plannedContentHash());
            definitionId = setup.createDefinition(new SetupService.CreateDefinitionCommand(organisation.getCode(),
                    plan.definitionCode(), translation.definitionName(), DefinitionUsageType.OWN_DEFINITION),
                    actor).id();
            revisionId = createSharedRevision(definitionId, templateId, templateRevisionId, reason, actor);
        } else {
            definitionId = plan.definition().getId();
        }

        if (detach == null && ACTION_SUCCESSOR_DRAFT.equals(plan.revisionAction())) {
            revisionId = successors.createSuccessor(plan.basedOn().getId(), reason, actor).id();
            overwriteWithLegacyContent(revisionId, planned, actor);
        }
        // CREATE: hierboven (sjabloon + gedeelde definitie, of de eigen kopie); UNCHANGED: geen nieuwe revisie.

        List<ConfigurationFinding> findings = List.of();
        if (revisionId != null) {
            // 020-4 enkel op de revisie van de gedeelde definitie (het sjabloon is geen migratiedoel).
            String written = verifyWritten(receipt, revisionId, plan.plannedContentHash());
            revisionOrigins.save(new LegacyConfigRevisionOrigin(revisionId, receipt.getId(),
                    translation.definitionContentHash(), written, now));
        }

        LegacyConfigDefinitionOrigin definitionOrigin = plan.definitionOrigin();
        if (detach != null) {
            // G2a: een nieuwe 020-3-rij voor de kopie; de groepsrij blijft ongewijzigd (hash en legacy-codes).
            definitionOrigins.save(LegacyConfigDefinitionOrigin.detached(receipt.getOriginEnvironment(),
                    receipt.getOriginDossier(), organisation.getCode(), translation.definitionContentHash(),
                    plan.legacyDefinitionCodes(), definitionId, receipt.getInterfaceCode(), now));
        } else if (definitionOrigin == null) {
            definitionOrigins.save(new LegacyConfigDefinitionOrigin(receipt.getOriginEnvironment(),
                    receipt.getOriginDossier(), organisation.getCode(), translation.definitionContentHash(),
                    plan.legacyDefinitionCodes(), templateId, definitionId, now));
        } else if (!definitionOrigin.getDefinitionContentHash().equals(translation.definitionContentHash())
                || !definitionOrigin.getLegacyDefinitionCodes().equals(plan.legacyDefinitionCodes())) {
            definitionOrigin.moveTo(translation.definitionContentHash(), plan.legacyDefinitionCodes(), now);
            definitionOrigins.save(definitionOrigin);
        }

        long linkId;
        if (plan.link() == null) {
            boolean scopeTaken = links.findByImportDefinitionId(definitionId).stream().anyMatch(existing ->
                    existing.getLibraryCode().equals(plan.libraryCode())
                            && existing.getSupplierOrganisation().getCode().equals(plan.organisationCode()));
            if (scopeTaken) {
                // Kan enkel ontstaan tussen preview en apply; het rapport van nu had dit dan getoond.
                throw new ConflictException(CODE_BLOCKED, "Legacy-config receipt " + receipt.getId()
                        + " cannot be applied; blocking: " + LINK_SCOPE_CONFLICT_MANUAL);
            }
            linkId = setup.createLink(new SetupService.CreateLinkCommand(definitionId, plan.linkCode(),
                    NAME_PREFIX + receipt.getInterfaceCode(), organisation.getCode(), plan.libraryCode(), null,
                    null)).id();
        } else {
            linkId = plan.link().getId();
            if (detach != null) {
                // G2a (V1 = A): de bestaande koppeling verlegd naar de eigen kopie (rijslot sinds lockLinkForDetach).
                // Code, naam, leverancier, bibliotheek, taken, leveringsconfiguratie en historiek blijven; guard 006-5
                // houdt (de kopie is een OWN_DEFINITION) en uk_import_link_scope kan niet botsen (de kopie is nieuw).
                ImportLink link = plan.link();
                link.setImportDefinition(definitions.findById(definitionId).orElseThrow());
                links.saveAndFlush(link);
            }
        }
        long taskId = plan.taskId() != null ? plan.taskId()
                : setup.createTask(new SetupService.CreateTaskCommand(linkId, plan.taskName(), null)).id();

        LegacyConfigOrigin origin = plan.origin() != null ? plan.origin()
                : new LegacyConfigOrigin(receipt.getOriginEnvironment(), receipt.getOriginDossier(),
                receipt.getInterfaceCode(), now);
        if (origin.getImportLinkId() == null || origin.getCatalogImportTaskId() == null) {
            origin.attachChain(linkId, taskId, now);
        }
        origin.recordApplied(receipt.getId(), now);
        origins.saveAndFlush(origin);
        if (detach != null) {
            // G2a: de append-only audit (026-2); import_link bewaart zelf geen definitiehistoriek.
            detachments.saveAndFlush(LegacyConfigDetachment.detach(origin.getId(), linkId, detach.from().getId(),
                    definitionId, receipt.getId(), reason, now, actor.username(), actor.subject()));
        }

        if (revisionId != null) {
            ImportDefinitionRevision revision = revisions.findById(revisionId).orElseThrow();
            String linkCurrency = links.findById(linkId).map(ImportLink::getDefaultCurrency).orElse(null);
            findings = checks.configurationReport(revision, linkCurrency).findings().stream()
                    .map(LegacyConfigApplyService::finding).toList();
        }

        LegacyConfigReport report = withIds(plan.report(), organisation, definitionId, revisionId, linkId, taskId,
                templateId, templateRevisionId);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("report", report);
        stored.put("previewFingerprint", plan.fingerprint());
        stored.put("reason", reason);
        stored.put("configurationFindings", findings);
        receipt.markApplied(now, actor.username(), actor.subject(), reason, json(stored));
        receipts.saveAndFlush(receipt);
        LegacyConfigReport applied = new LegacyConfigReport(report.receiptId(), report.contentHash(),
                LegacyConfigReceiptStatus.APPLIED.name(), report.outcome(), report.planned(), report.sharedWith(),
                report.items(), report.prodisReference());
        return new ApplyResult(applied, LegacyConfigReceiptStatus.APPLIED.name(), findings);
    }

    /**
     * S7: revisie 1 van de gedeelde definitie als letterlijke kopie van de sjabloonrevisie via {@link RevisionCopier}
     * (zoals de materialisatie, die een DRAFT-sjabloon weigert): {@code based_on_definition_id} = het sjabloon,
     * {@code based_on_revision_id} = de sjabloonrevisie, zodat de materialisatiewizard de gedeelde definitie herkent
     * ({@code REUSE_DEFINITION}) zodra de mens het sjabloon activeert.
     */
    private long createSharedRevision(long definitionId, long templateId, long templateRevisionId, String reason,
                                      ActorIdentity actor) {
        ImportDefinition shared = definitions.findById(definitionId).orElseThrow();
        shared.setBasedOnDefinition(definitions.findById(templateId).orElseThrow());
        definitions.saveAndFlush(shared);
        ImportDefinitionRevision source = revisions.findById(templateRevisionId).orElseThrow();
        ImportDefinitionRevision copy = revisions.saveAndFlush(revisionCopier.copyRevision(source, shared, 1, source,
                reason, actor.username(), actor.subject()));
        // S8a/S8c: de vertaalrijen en ketenstappen gaan mee (de gedeelde definitie draagt dezelfde als het sjabloon).
        revisionCopier.copyMappingChildren(fieldMappings.saveAll(revisionCopier.copyMappings(copy, source,
                actor.username(), actor.subject()).values()), source);
        recordFilters.saveAll(revisionCopier.copyFilters(copy, source, actor.username(), actor.subject()).values());
        revisionCopier.copyFieldCriticalities(copy, source, actor.username(), actor.subject());
        fieldMappings.flush();
        recordFilters.flush();
        RevisionConfigHashes.applyAll(copy);
        revisions.saveAndFlush(copy);
        return copy.getId();
    }

    /** Wat het loskoppelen aanmaakte: de kopie en de revisie met de nieuwe legacy-inhoud (die 020-4 krijgt). */
    private record DetachedCopy(long definitionId, long revisionId) {
    }

    /**
     * G2a: de eigen kopie van de gedeelde definitie ({@code docs/design/g2-design.md} par. G2a punten 3 en 4).
     * <ol>
     *   <li>{@code OWN_DEFINITION} met de geplande code en beschrijving via {@link SetupService#createDefinition} (zelfde
     *       leverancier), {@code based_on_definition_id} = de gedeelde definitie (niet het sjabloon);</li>
     *   <li>met een ACTIVE revisie (V2 = A): revisie 1 = letterlijke kopie via {@link RevisionCopier} met alle
     *       kindtabellen zoals een opvolgende revisie (mappings met vertaalrijen en ketenstappen, filters,
     *       kritiekheden, bookmarkdeclaraties met usages en bookmarkwaarden), gecontroleerd tegen de inhoud die de
     *       laatste migratie schreef, dan geactiveerd via {@link SetupService#activateRevision} (volledige validatie,
     *       de actor als goedkeurder) en met de geërfde 020-4-rij; revisie 2 = opvolgende DRAFT via
     *       {@link RevisionSuccessorService} met de nieuwe legacy-inhoud (S2-regels);</li>
     *   <li>zonder ACTIVE revisie (of voor een nieuwe interface): revisie 1 = DRAFT met de nieuwe legacy-inhoud.</li>
     * </ol>
     * Faalt de activatie (400 {@code CONFIG_*}, 409), dan rolt de hele APPLY terug: er is niets aangemaakt of verlegd.
     */
    private DetachedCopy createDetachedCopy(LegacyConfigReceipt receipt, Plan plan, String reason,
                                            ActorIdentity actor, Instant now) {
        DetachDecision detach = plan.detach();
        PlannedRevision planned = plan.translation().revision();
        ImportDefinition shared = definitions.findById(detach.from().getId()).orElseThrow();
        long copyId = setup.createDefinition(new SetupService.CreateDefinitionCommand(
                shared.getSourceOrganisation().getCode(), detach.toCode(), detach.toName(),
                DefinitionUsageType.OWN_DEFINITION), actor).id();
        ImportDefinition copy = definitions.findById(copyId).orElseThrow();
        copy.setBasedOnDefinition(shared);
        definitions.saveAndFlush(copy);
        if (!detach.carriesActive()) {
            long first = setup.createRevision(copyId, createCommand(planned, reason), actor, false).id();
            writeRevisionContent(first, planned, actor);
            return new DetachedCopy(copyId, first);
        }
        ImportDefinitionRevision source = revisions.findById(detach.copied().getId()).orElseThrow();
        if (source.getStatus() != RevisionStatus.ACTIVE) {
            // Kan niet: het plan las haar onder hetzelfde slot als ACTIVE; nooit een niet-actieve revisie activeren.
            throw new IllegalStateException("Legacy-config receipt " + receipt.getId() + ": revision " + source.getId()
                    + " is no longer ACTIVE");
        }
        String by = actor.username();
        String bySubject = actor.subject();
        ImportDefinitionRevision first = revisions.saveAndFlush(revisionCopier.copyRevision(source, copy, 1, source,
                reason, by, bySubject));
        revisionCopier.copyMappingChildren(fieldMappings.saveAll(revisionCopier.copyMappings(first, source, by,
                bySubject).values()), source);
        recordFilters.saveAll(revisionCopier.copyFilters(first, source, by, bySubject).values());
        revisionCopier.copyFieldCriticalities(first, source, by, bySubject);
        copyAllBookmarkDeclarations(first, source, by, bySubject);
        revisionCopier.copyBookmarkValues(first, source, by, bySubject);
        fieldMappings.flush();
        recordFilters.flush();
        RevisionConfigHashes.applyAll(first);
        revisions.saveAndFlush(first);
        // Revisie 1 is een ongewijzigde kopie: haar inhoud is wat de laatste migratie schreef (plan: geen
        // MANUAL_CHANGES_SINCE_MIGRATION), dus erft ze die 020-4-rij.
        LegacyConfigRevisionOrigin inherited = detach.inherited();
        verifyWritten(receipt, first.getId(), inherited.getMigratedContentHash());
        setup.activateRevision(first.getId(), actor);
        revisionOrigins.save(new LegacyConfigRevisionOrigin(first.getId(), inherited.getReceiptId(),
                inherited.getDefinitionContentHash(), inherited.getMigratedContentHash(), now));
        long second = successors.createSuccessor(first.getId(), reason, actor).id();
        overwriteWithLegacyContent(second, planned, actor);
        return new DetachedCopy(copyId, second);
    }

    /**
     * De bookmarkdeclaraties van {@code source} met hun usages, alle scopes - zoals een opvolgende revisie
     * ({@code RevisionSuccessorService}): zo blijft elke bestaande {@code import_link_bookmark_value} van de verlegde
     * koppeling gedeclareerd.
     */
    private void copyAllBookmarkDeclarations(ImportDefinitionRevision target, ImportDefinitionRevision source,
                                             String createdBy, String createdBySubject) {
        List<ImportDefinitionBookmark> declarations =
                bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(source.getId());
        Map<ImportDefinitionBookmark, List<ImportDefinitionBookmarkUsage>> usagesByBookmark = new LinkedHashMap<>();
        for (ImportDefinitionBookmark declaration : declarations) {
            usagesByBookmark.put(declaration, bookmarkUsages.findByBookmarkId(declaration.getId()));
        }
        revisionCopier.copyBookmarkDeclarations(target, declarations, usagesByBookmark, createdBy, createdBySubject);
    }

    /**
     * Een opvolgende DRAFT (letterlijke kopie) krijgt wat legacy bepaalt (S2, V2 = C): de door legacy bepaalde
     * revisievelden, de vertaalbare mappings en alle filters worden vervangen; drempels, kritiek-overrules en
     * bookmarks blijven uit de kopie.
     */
    private void overwriteWithLegacyContent(long revisionId, PlannedRevision planned, ActorIdentity actor) {
        setup.updateRevision(revisionId, updateCommand(planned), false);
        for (ImportFieldMapping mapping : fieldMappings.findByRevisionIdWithTargetField(revisionId)) {
            if (LegacyConfigTranslator.MAPPING_TARGETS.containsValue(mapping.getTargetField().getCode())) {
                setup.deleteMapping(revisionId, mapping.getId());
            }
        }
        for (ImportRecordFilter filter : recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId)) {
            setup.deleteFilter(revisionId, filter.getId());
        }
        writeRevisionContent(revisionId, planned, actor);
    }

    /**
     * Plan en uitvoering mogen nooit uiteenlopen: anders nooit een herkomstrij met een verkeerde inhoud (rollback).
     *
     * @return de inhoudshash van de geschreven revisie ({@link LegacyRevisionContent})
     */
    private String verifyWritten(LegacyConfigReceipt receipt, long revisionId, String plannedContentHash) {
        ImportDefinitionRevision revision = revisions.findById(revisionId).orElseThrow();
        String written = LegacyRevisionContent.ofRevision(revision,
                fieldMappings.findByRevisionIdWithTargetField(revisionId),
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId),
                valueMaps.findByRevisionId(revisionId), transformSteps.findByRevisionId(revisionId));
        if (!written.equals(plannedContentHash)) {
            throw new IllegalStateException("Legacy-config receipt " + receipt.getId()
                    + ": the written revision " + revisionId + " does not match the planned content");
        }
        return written;
    }

    /** Mappings en filters toevoegen via {@link SetupService} (die valideert); een TAB rechtstreeks (zie klasse). */
    private void writeRevisionContent(long revisionId, PlannedRevision planned, ActorIdentity actor) {
        if ("\t".equals(planned.delimiter())) {
            ImportDefinitionRevision revision = revisions.findById(revisionId).orElseThrow();
            revision.setStructureDelimiter("\t");
            RevisionConfigHashes.applyAll(revision);
            revisions.saveAndFlush(revision);
        }
        for (PlannedMapping mapping : planned.mappings()) {
            // S8a: de vertaalrijen van een CALC 7-mapping in dezelfde aanroep (de validatie ziet de volledige mapping).
            List<SetupService.ValueMapEntry> valueMap = mapping.valueMap().stream()
                    .map(entry -> new SetupService.ValueMapEntry(entry.source(), entry.target())).toList();
            // S8c: de ketenstappen eveneens in dezelfde aanroep, in volgorde (SetupService nummert vanaf 1).
            List<SetupService.TransformStepEntry> steps = mapping.steps().stream()
                    .sorted(java.util.Comparator.comparingInt(LegacyConfigTranslator.PlannedStep::number))
                    .map(step -> new SetupService.TransformStepEntry(step.kind(), step.config())).toList();
            setup.addMapping(revisionId, new SetupService.CreateMappingCommand(mapping.targetCode(),
                    mapping.sourceReference(), null, mapping.valueKind(), null, mapping.fixedValue(), null, null,
                    null, null, null, null, mapping.transformKind(), mapping.transformConfig(), null, null,
                    valueMap, steps), actor);
        }
        for (PlannedFilter filter : planned.filters()) {
            setup.addFilter(revisionId, new SetupService.CreateFilterCommand(filter.sequence(),
                    filter.sourceReference(), filter.operator(), filter.compareValue(), filter.outcome(),
                    filter.caseSensitive(), null, filter.nullBehaviour(), null, null, filter.matchGroup()), actor);
        }
    }

    private static SetupService.CreateRevisionCommand createCommand(PlannedRevision planned, String reason) {
        return new SetupService.CreateRevisionCommand(delimiterForSetup(planned), null, planned.charset(),
                planned.hasHeader(), planned.headerLineNumber(), LegacyConfigTranslator.FIELD_REFERENCE_KIND, null,
                planned.identityProfileKind(), planned.supplierField(), planned.groupField(),
                planned.referenceField(), planned.discountField(), planned.basePriceField(),
                planned.descriptionField(), null, CANONICALISATION_VERSION, null, null, null, null, null, null, null,
                null, null, reason, null, planned.supplierFixedValue(), planned.groupFixedValue(),
                planned.identityUppercase());
    }

    /** {@code null} = ongewijzigd, {@code ""} = wissen ({@link SetupService.UpdateRevisionCommand}). */
    private static SetupService.UpdateRevisionCommand updateCommand(PlannedRevision planned) {
        String delimiter = "\t".equals(planned.delimiter()) ? null : planned.delimiter();
        return new SetupService.UpdateRevisionCommand(delimiter, null, planned.charset(), planned.hasHeader(),
                planned.headerLineNumber(), LegacyConfigTranslator.FIELD_REFERENCE_KIND, null,
                planned.identityProfileKind(), emptyIfNull(planned.supplierField()), emptyIfNull(planned.groupField()),
                planned.referenceField(), planned.discountField() == null ? "" : planned.discountField(),
                planned.basePriceField(), planned.descriptionField() == null ? "" : planned.descriptionField(), null,
                null, null, null, null, null, null, null, null, null, null, null, Boolean.TRUE, null,
                emptyIfNull(planned.supplierFixedValue()), emptyIfNull(planned.groupFixedValue()),
                planned.identityUppercase());
    }

    /** S6: precies één van kolom of vaste waarde; de andere bron wordt uitdrukkelijk gewist ({@code ""}). */
    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    /** Een TAB kan niet via {@code SetupInput.requireText} (blank); tijdelijk {@code ;}, daarna rechtstreeks gezet. */
    private static String delimiterForSetup(PlannedRevision planned) {
        return "\t".equals(planned.delimiter()) ? ";" : planned.delimiter();
    }

    private static LegacyConfigReport withIds(LegacyConfigReport report, SourceOrganisation organisation,
                                              long definitionId, Long revisionId, long linkId, long taskId,
                                              Long templateId, Long templateRevisionId) {
        Planned planned = report.planned();
        Planned withIds = new Planned(
                new PlannedOrganisation(planned.sourceOrganisation().action(), organisation.getId(),
                        organisation.getCode(), organisation.getName()),
                new PlannedDefinition(planned.definition().action(), definitionId, planned.definition().code(),
                        planned.definition().name(), planned.definition().revisionAction(),
                        planned.definition().basedOnRevision(), revisionId),
                new PlannedLink(planned.link().action(), linkId, planned.link().code(), planned.link().libraryCode(),
                        planned.link().libraryCandidates()),
                new PlannedTask(planned.task().action(), taskId, planned.task().name()), planned.credential(),
                new PlannedTemplate(planned.template().action(),
                        templateId != null ? templateId : planned.template().id(), planned.template().code(),
                        templateRevisionId), planned.detach());
        return new LegacyConfigReport(report.receiptId(), report.contentHash(), report.receiptStatus(),
                report.outcome(), withIds, report.sharedWith(), report.items(), report.prodisReference());
    }

    private static ConfigurationFinding finding(ConfigFinding finding) {
        return new ConfigurationFinding(finding.code(), finding.fieldName(), finding.message());
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    private Criticality defaultCriticality(String targetCode) {
        return fieldCatalog.findById(targetCode)
                .map(entry -> entry.getReferenceType() != null || entry.getPriceComponentCode() != null
                        ? Criticality.CRITICAL : Criticality.NON_CRITICAL)
                .orElse(Criticality.NON_CRITICAL);
    }

    private String fingerprint(LegacyConfigReport report, Collection<String> rows) {
        String text = json(report) + "\n" + String.join("\n", rows);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required but not available", impossible);
        }
    }

    private String json(Object value) {
        try {
            return canonical.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("The legacy-config report could not be serialised");
        }
    }

    private JsonNode readBundle(LegacyConfigReceipt receipt) {
        try {
            return bundleReader.readTree(receipt.getBundleJson());
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("bundle_json of legacy-config receipt " + receipt.getId()
                    + " is not readable");
        }
    }

    private static NotFoundException notFound(long receiptId) {
        return new NotFoundException(LegacyConfigReceiptService.CODE_RECEIPT_NOT_FOUND,
                "Legacy-config receipt " + receiptId + " not found");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
