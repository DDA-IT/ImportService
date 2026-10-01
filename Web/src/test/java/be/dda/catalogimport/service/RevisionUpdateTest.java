package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.SetupService.RevisionView;
import be.dda.catalogimport.service.SetupService.UpdateRevisionCommand;
import be.dda.catalogimport.testsupport.TestActors;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap S1-X-4: het <b>wijzigen</b> van een DRAFT-opvolgrevisie
 * ({@code docs/design/revision-successor-design.md} §5 met R-REV-X2/R-REV-X3, §6 endpoints E3 en E4,
 * §10 O3 "beide beschermingen invoeren").
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>Drempels en prijsbeleid wijzigen laat de vier configuratiehashes ongemoeid</b> — geen fout,
 *       maar de ontdekking van ontwerp §9: de hashes dekken enkel de scalaire identiteits-, structuur- en
 *       recordvelden. Een test die het tegendeel zou aannemen, zou de hash verkeerd uitleggen.</li>
 *   <li><b>Een structuurveld wijzigen herberekent de hashes wél</b>, en enkel de laag die veranderde.</li>
 *   <li><b>R-REV-X3:</b> een {@code identity*Field}-wijziging zonder {@code acknowledgeIdentityChange}
 *       geeft 409 {@code IDENTITY_CHANGE_NOT_ACKNOWLEDGED} en bewaart <b>niets</b> — ook niet de
 *       drempelwijziging die in hetzelfde verzoek meekwam. Mét bevestiging slaagt ze en verandert de
 *       recordregel-hash.</li>
 *   <li><b>R-REV-X2:</b> {@code recordCanonicalisationVersion} wijzigen mag zolang er nog geen aanvaarde
 *       bronstaat is; bestaat die wel voor een koppeling van deze definitie, dan 409
 *       {@code REVISION_CANONICALISATION_CHANGE_BLOCKED} — <b>ook</b> met
 *       {@code acknowledgeIdentityChange=true}, want deze blokkade kent geen bevestiging (§5 geeft haar,
 *       anders dan R-REV-X3, geen bevestigingsveld). Een wijziging die de versie niet raakt, blijft
 *       gewoon toegestaan naast bestaande bronstaat.</li>
 *   <li><b>Alleen een DRAFT is bewerkbaar:</b> PATCH en DELETE op {@code ACTIVE}/{@code SUPERSEDED} geven
 *       409 {@code REVISION_NOT_EDITABLE} (de bestaande code van {@code editableRevision}).</li>
 *   <li><b>E4:</b> een geërfde mapping/filter verwijderen werkt; steunt er een bookmarkdeclaratie op, dan
 *       409 met haar eigen bestaande {@code CONFIG_BOOKMARK_*}-code en blijft de rij staan; een onbekende
 *       of vreemde rij is 404 {@code MAPPING_NOT_FOUND}/{@code FILTER_NOT_FOUND}.</li>
 * </ul>
 * De rechtenbewijzen staan bewust elders: 403 zonder {@code MANAGE} in
 * {@code PermissionWriteEndpointsHttpTest}, bereikbaar zonder {@code catalogimport.setup-api.enabled} (sinds
 * NT-3) in {@code SetupApiDisabledTest}, de rechtentabel in {@code PermissionCoverageTest}.
 *
 * <h2>Waarom dezelfde annotaties als {@code RevisionSuccessorTest}</h2>
 * Letterlijk gelijk, zodat Spring deze klasse dezelfde applicatiecontext en dezelfde uitdrukkelijk kleine
 * verbindingspool laat delen; de lokale PostgreSQL heeft weinig vrije verbindingen. Ze gebruikt ook
 * dezelfde {@code MaterialisationFixtures}.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RevisionUpdateTest {

    private static final String USER = MaterialisationFixtures.USER;
    private static final String SUBJECT = "test-sub-" + MaterialisationFixtures.USER;
    private static final String SETUP = "/api/catalog-import/setup";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SetupService setup;
    @Autowired
    private RevisionSuccessorService successors;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportRecordFilterRepository recordFilters;
    @Autowired
    private ImportDefinitionBookmarkRepository bookmarks;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private ImportBatchRepository batches;
    /** De bronstaat heeft bewust geen entiteit: een proefrij komt er dus met JDBC in. */
    @Autowired
    private JdbcTemplate jdbc;

    private MaterialisationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = MaterialisationFixtures.of(context);
    }

    // --- E3: drempels en prijsbeleid ------------------------------------------------------------------

    /**
     * De ontdekking van ontwerp §9, hier vastgepind: drempels en prijsbeleid zitten in <b>geen enkele</b>
     * hash. Wie de vier hashes later als "zijn deze twee configuraties gelijk?" zou gebruiken, krijgt op
     * dit punt een verkeerd antwoord — dat moet zichtbaar blijven, niet stilzwijgend "opgelost" worden.
     */
    @Test
    void changingThresholdsAndPricePolicyLeavesAllFourHashesUntouchedAndAnswersWithTheNewValues() {
        Draft draft = draft("THRESH");
        ImportDefinitionRevision before = revisions.findById(draft.id()).orElseThrow();
        String accessHash = before.getAccessConfigHash();
        String structureHash = before.getStructureConfigHash();
        String recordHash = before.getRecordRulesConfigHash();
        String compositeHash = before.getCompositeConfigHash();

        RevisionView view = setup.updateRevision(draft.id(), update()
                .maxCriticalSharePercent(new BigDecimal("7.5"))
                .creationThresholdSharePercent(new BigDecimal("3"))
                .priceDeviationPercent(new BigDecimal("25"))
                .priceDerivationTolerance(new BigDecimal("0.05"))
                .basePriceZeroAllowed(true)
                .build());

        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.maxCriticalSharePercent()).isEqualByComparingTo("7.5");
        assertThat(view.creationThresholdSharePercent()).isEqualByComparingTo("3");

        ImportDefinitionRevision after = revisions.findById(draft.id()).orElseThrow();
        assertThat(after.getMaxCriticalSharePercent()).isEqualByComparingTo("7.5");
        assertThat(after.getPriceDeviationPercent()).isEqualByComparingTo("25");
        assertThat(after.getPriceDerivationTolerance()).isEqualByComparingTo("0.05");
        assertThat(after.isBasePriceZeroAllowed()).isTrue();
        assertThat(after.getAccessConfigHash()).isEqualTo(accessHash);
        assertThat(after.getStructureConfigHash()).isEqualTo(structureHash);
        assertThat(after.getRecordRulesConfigHash()).isEqualTo(recordHash);
        assertThat(after.getCompositeConfigHash()).isEqualTo(compositeHash);
        // Niets anders meegestuurd = niets anders gewijzigd.
        assertThat(after.getStructureDelimiter()).isEqualTo(";");
        assertThat(after.getIdentitySupplierField()).isEqualTo("LEVERANCIER");
    }

    /** Een negatief percentage wordt geweigerd in plaats van stil op 0 gezet, net als bij het aanmaken. */
    @Test
    void refusesANegativeThresholdAndSavesNothing() {
        Draft draft = draft("NEGTHR");

        assertThatThrownBy(() -> setup.updateRevision(draft.id(), update()
                .maxCriticalSharePercent(new BigDecimal("-1"))
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCriticalSharePercent");

        assertThat(revisions.findById(draft.id()).orElseThrow().getMaxCriticalSharePercent())
                .isEqualByComparingTo("1");
    }

    // --- E3: structuurvelden --------------------------------------------------------------------------

    /** Eén structuurveld: alleen de structuurlaag (en dus de samengestelde hash) verandert. */
    @Test
    void changingAStructureFieldRecomputesOnlyTheStructureAndCompositeHash() {
        Draft draft = draft("STRUCT");
        ImportDefinitionRevision before = revisions.findById(draft.id()).orElseThrow();
        String accessHash = before.getAccessConfigHash();
        String structureHash = before.getStructureConfigHash();
        String recordHash = before.getRecordRulesConfigHash();
        String compositeHash = before.getCompositeConfigHash();

        RevisionView view = setup.updateRevision(draft.id(), update().delimiter("|").charset("ISO-8859-1")
                .expectedColumnCount(12).build());

        assertThat(view.delimiter()).isEqualTo("|");
        ImportDefinitionRevision after = revisions.findById(draft.id()).orElseThrow();
        assertThat(after.getStructureCharset()).isEqualTo("ISO-8859-1");
        assertThat(after.getStructureExpectedColumnCount()).isEqualTo(12);
        assertThat(after.getStructureConfigHash()).isNotEqualTo(structureHash);
        assertThat(after.getCompositeConfigHash()).isNotEqualTo(compositeHash);
        assertThat(after.getAccessConfigHash()).isEqualTo(accessHash);
        assertThat(after.getRecordRulesConfigHash()).isEqualTo(recordHash);
    }

    /**
     * {@code null} betekent "ongewijzigd", {@code ""} betekent voor een optioneel veld "uitdrukkelijk
     * leeg". Een verplicht veld wordt nooit stil leeggemaakt: een lege tekst is daar een 400.
     */
    @Test
    void treatsNullAsUnchangedAndAnEmptyTextAsExplicitlyEmptyOnOptionalFieldsOnly() {
        Draft draft = draft("EMPTY");
        setup.updateRevision(draft.id(), update().descriptionField("OMSCHRIJVING").build());
        assertThat(revisions.findById(draft.id()).orElseThrow().getRecordDescriptionField())
                .isEqualTo("OMSCHRIJVING");

        // Een leeg verzoek wijzigt niets.
        setup.updateRevision(draft.id(), update().build());
        assertThat(revisions.findById(draft.id()).orElseThrow().getRecordDescriptionField())
                .isEqualTo("OMSCHRIJVING");
        // Ook een volledig afwezige body is toegestaan en wijzigt niets.
        setup.updateRevision(draft.id(), null);
        assertThat(revisions.findById(draft.id()).orElseThrow().getRecordDescriptionField())
                .isEqualTo("OMSCHRIJVING");

        // "" op een optioneel veld: uitdrukkelijk leeg.
        setup.updateRevision(draft.id(), update().descriptionField("").build());
        assertThat(revisions.findById(draft.id()).orElseThrow().getRecordDescriptionField()).isNull();

        // "" op een verplicht veld: geweigerd, en de bestaande waarde blijft staan.
        assertThatThrownBy(() -> setup.updateRevision(draft.id(), update().delimiter("").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delimiter");
        assertThat(revisions.findById(draft.id()).orElseThrow().getStructureDelimiter()).isEqualTo(";");
    }

    // --- R-REV-X3: de identiteit --------------------------------------------------------------------

    @Test
    void refusesAnIdentityFieldChangeWithoutAcknowledgementAndSavesNothingFromThatRequest() {
        Draft draft = draft("IDNOACK");

        assertThatThrownBy(() -> setup.updateRevision(draft.id(), update()
                .supplierReferenceField("ANDERE_REFERENTIE")
                .maxCriticalSharePercent(new BigDecimal("9"))
                .build()))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("IDENTITY_CHANGE_NOT_ACKNOWLEDGED");

        // Niets uit dat verzoek is bewaard: ook de drempel die er onschuldig bij stond niet.
        ImportDefinitionRevision after = revisions.findById(draft.id()).orElseThrow();
        assertThat(after.getIdentitySupplierReferenceField()).isEqualTo("REFERENTIE");
        assertThat(after.getMaxCriticalSharePercent()).isEqualByComparingTo("1");
    }

    /** Ook het identiteitsprofiel zelf valt onder R-REV-X3, niet enkel de vier veldnamen. */
    @Test
    void refusesAnIdentityProfileChangeWithoutAcknowledgement() {
        Draft draft = draft("PROFNOACK");

        assertThatThrownBy(() -> setup.updateRevision(draft.id(), update()
                .identityProfileKind(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE)
                .discountCodeField("KORTINGSCODE")
                .build()))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("IDENTITY_CHANGE_NOT_ACKNOWLEDGED");

        ImportDefinitionRevision after = revisions.findById(draft.id()).orElseThrow();
        assertThat(after.getIdentityProfileKind()).isEqualTo(IdentityProfileKind.THREE_PART);
        assertThat(after.getIdentityDiscountCodeField()).isNull();
    }

    @Test
    void acceptsAnIdentityFieldChangeWithAcknowledgementAndChangesTheRecordRulesHash() {
        Draft draft = draft("IDACK");
        ImportDefinitionRevision before = revisions.findById(draft.id()).orElseThrow();
        String recordHash = before.getRecordRulesConfigHash();
        String structureHash = before.getStructureConfigHash();

        RevisionView view = setup.updateRevision(draft.id(), update()
                .supplierReferenceField("ANDERE_REFERENTIE")
                .acknowledgeIdentityChange(true)
                .build());

        assertThat(view.supplierReferenceField()).isEqualTo("ANDERE_REFERENTIE");
        ImportDefinitionRevision after = revisions.findById(draft.id()).orElseThrow();
        assertThat(after.getIdentitySupplierReferenceField()).isEqualTo("ANDERE_REFERENTIE");
        assertThat(after.getRecordRulesConfigHash()).isNotEqualTo(recordHash);
        assertThat(after.getStructureConfigHash()).isEqualTo(structureHash);
        assertThat(after.getStatus()).isEqualTo(RevisionStatus.DRAFT);
    }

    /** Hetzelfde veld op exact dezelfde waarde zetten is geen wijziging en vraagt geen bevestiging. */
    @Test
    void doesNotAskForAcknowledgementWhenTheIdentityFieldKeepsItsValue() {
        Draft draft = draft("IDSAME");

        RevisionView view = setup.updateRevision(draft.id(), update()
                .supplierField("LEVERANCIER")
                .supplierGroupField("GROEP")
                .supplierReferenceField("REFERENTIE")
                .identityProfileKind(IdentityProfileKind.THREE_PART)
                .maxCriticalSharePercent(new BigDecimal("4"))
                .build());

        assertThat(view.maxCriticalSharePercent()).isEqualByComparingTo("4");
    }

    /** Een profiel dat niet bij het kortingscodeveld past is een 400, nooit een databasefout. */
    @Test
    void refusesAnIdentityProfileThatContradictsTheDiscountCodeField() {
        Draft draft = draft("IDCONTRA");

        assertThatThrownBy(() -> setup.updateRevision(draft.id(), update()
                .identityProfileKind(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE)
                .acknowledgeIdentityChange(true)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FOUR_PART_WITH_DISCOUNT_CODE requires discountCodeField");

        assertThat(revisions.findById(draft.id()).orElseThrow().getIdentityProfileKind())
                .isEqualTo(IdentityProfileKind.THREE_PART);
    }

    // --- R-REV-X2: de canonicalisatieversie ----------------------------------------------------------

    /** Zonder aanvaarde bronstaat is er niets te beschermen: de versie mag omhoog. */
    @Test
    void allowsRaisingTheCanonicalisationVersionWithoutAcceptedSourceState() {
        Draft draft = draft("CANONOK");
        // De achtergrond staat op 2; eerst naar 1, zodat de stap daarna aantoonbaar een verhoging is.
        setup.updateRevision(draft.id(), update().canonicalisationVersion(1).build());
        String recordHash = revisions.findById(draft.id()).orElseThrow().getRecordRulesConfigHash();

        RevisionView view = setup.updateRevision(draft.id(), update().canonicalisationVersion(2).build());

        assertThat(view.canonicalisationVersion()).isEqualTo(2);
        ImportDefinitionRevision after = revisions.findById(draft.id()).orElseThrow();
        assertThat(after.getRecordCanonicalisationVersion()).isEqualTo(2);
        assertThat(after.getRecordRulesConfigHash()).isNotEqualTo(recordHash);
    }

    /**
     * R-REV-X2 is onvoorwaardelijk: de bestaande bronstaat van een koppeling van deze definitie blokkeert
     * de versiewijziging, en {@code acknowledgeIdentityChange=true} verandert daar niets aan. Het ontwerp
     * §5 geeft deze regel — anders dan R-REV-X3 — geen bevestigingsveld, en de reden is niet formeel maar
     * inhoudelijk: er bestaat geen migratie voor.
     */
    @Test
    void blocksACanonicalisationVersionChangeWhenAcceptedSourceStateExistsEvenWhenAcknowledged() {
        Draft draft = draft("CANONBLOK");
        withAcceptedSourceState(draft);

        assertThatThrownBy(() -> setup.updateRevision(draft.id(), update()
                .canonicalisationVersion(3)
                .acknowledgeIdentityChange(true)
                .build()))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_CANONICALISATION_CHANGE_BLOCKED");

        assertThat(revisions.findById(draft.id()).orElseThrow().getRecordCanonicalisationVersion())
                .isEqualTo(2);

        // Ook een verlaging is geblokkeerd: het versienummer staat vooraan in identity_hash, dus 2 -> 1
        // maakt elke bestaande aanbieding even onvindbaar als 2 -> 3.
        assertThatThrownBy(() -> setup.updateRevision(draft.id(), update()
                .canonicalisationVersion(1)
                .build()))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_CANONICALISATION_CHANGE_BLOCKED");

        // De blokkade is eng: alles wat de versie niet raakt, blijft naast bestaande bronstaat toegestaan.
        RevisionView view = setup.updateRevision(draft.id(), update()
                .maxCriticalSharePercent(new BigDecimal("6"))
                .canonicalisationVersion(2)
                .build());
        assertThat(view.maxCriticalSharePercent()).isEqualByComparingTo("6");
        assertThat(view.canonicalisationVersion()).isEqualTo(2);
    }

    /**
     * Wanneer één verzoek beide blokkades raakt, leest de aanroeper eerst de blokkade die hij nooit kan
     * opheffen — anders zou hij het verzoek met een bevestiging herhalen en dan alsnog stuiten.
     */
    @Test
    void reportsTheUnconditionalCanonicalisationBlockBeforeTheIdentityAcknowledgement() {
        Draft draft = draft("BOTH");
        withAcceptedSourceState(draft);

        assertThatThrownBy(() -> setup.updateRevision(draft.id(), update()
                .canonicalisationVersion(3)
                .supplierReferenceField("ANDERE_REFERENTIE")
                .build()))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_CANONICALISATION_CHANGE_BLOCKED");

        ImportDefinitionRevision after = revisions.findById(draft.id()).orElseThrow();
        assertThat(after.getRecordCanonicalisationVersion()).isEqualTo(2);
        assertThat(after.getIdentitySupplierReferenceField()).isEqualTo("REFERENTIE");
    }

    // --- Alleen een DRAFT is bewerkbaar -------------------------------------------------------------

    @Test
    void refusesToPatchAnActiveOrSupersededRevision() {
        MaterialisationFixtures.Template active = ownDefinition("PATCHACT", RevisionStatus.ACTIVE);

        assertThatThrownBy(() -> setup.updateRevision(active.revision().getId(),
                update().maxCriticalSharePercent(new BigDecimal("2")).build()))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_NOT_EDITABLE");
        assertThat(revisions.findById(active.revision().getId()).orElseThrow()
                .getMaxCriticalSharePercent()).isEqualByComparingTo("1");

        MaterialisationFixtures.Template superseded = ownDefinition("PATCHSUP", RevisionStatus.ACTIVE);
        fixtures.supersede(superseded);
        assertThatThrownBy(() -> setup.updateRevision(superseded.revision().getId(),
                update().maxCriticalSharePercent(new BigDecimal("2")).build()))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_NOT_EDITABLE");

        assertThatThrownBy(() -> setup.updateRevision(-1L, update().build()))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("REVISION_NOT_FOUND");
    }

    // --- E4: een geërfde kindrij verwijderen --------------------------------------------------------

    @Test
    void deletesAnInheritedMappingAndFilterFromADraft() {
        Draft draft = draft("DEL");
        long mappingId = mappingId(draft.id());
        long filterId = filterId(draft.id());

        setup.deleteMapping(draft.id(), mappingId);
        assertThat(fieldMappings.findByRevisionIdWithTargetField(draft.id())).isEmpty();
        assertThat(fieldMappings.findById(mappingId)).isEmpty();

        setup.deleteFilter(draft.id(), filterId);
        assertThat(recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(draft.id())).isEmpty();
        assertThat(recordFilters.findById(filterId)).isEmpty();

        // De bronrevisie houdt haar eigen kindrijen: verwijderen werkt per revisie, niet per definitie.
        assertThat(fieldMappings.findByRevisionIdWithTargetField(draft.sourceRevisionId())).hasSize(1);
        assertThat(recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(
                draft.sourceRevisionId())).hasSize(1);
    }

    /**
     * De hercontrole van §6 E4: een {@code FIELD_MAPPING_FIXED_VALUE}-declaratie die naar deze mapping
     * wijst, zou na de verwijdering een invulveld achterlaten dat nergens landt. De bestaande
     * {@code CONFIG_BOOKMARK_PLACE_UNRESOLVED} volstaat; er is geen nieuwe foutcode nodig.
     */
    @Test
    void refusesToDeleteAMappingABookmarkDeclarationDependsOnAndKeepsTheRow() {
        Draft draft = draft("DELBMKMAP");
        ImportDefinitionRevision revision = revisions.findById(draft.id()).orElseThrow();
        fixtures.declareOn(revision, "VASTE_LEVERANCIER", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE,
                MaterialisationFixtures.MAPPED_FIELD);
        long mappingId = mappingId(draft.id());

        assertThatThrownBy(() -> setup.deleteMapping(draft.id(), mappingId))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_PLACE_UNRESOLVED");

        // Teruggedraaid: de mapping staat er nog en de declaratie ook.
        assertThat(fieldMappings.findById(mappingId)).isPresent();
        assertThat(bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(draft.id())).hasSize(1);
    }

    @Test
    void refusesToDeleteAFilterABookmarkDeclarationDependsOnAndKeepsTheRow() {
        Draft draft = draft("DELBMKFLT");
        ImportDefinitionRevision revision = revisions.findById(draft.id()).orElseThrow();
        fixtures.declareOn(revision, "CULTUUR", BookmarkValueScope.DEFINITION, true, BookmarkDataType.TEXT,
                1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE,
                String.valueOf(MaterialisationFixtures.FILTER_SEQUENCE));
        long filterId = filterId(draft.id());

        assertThatThrownBy(() -> setup.deleteFilter(draft.id(), filterId))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_PLACE_UNRESOLVED");

        assertThat(recordFilters.findById(filterId)).isPresent();
    }

    @Test
    void refusesToDeleteOnANonDraftRevisionAndRefusesAnUnknownOrForeignRow() {
        Draft draft = draft("DELBAD");
        long draftMappingId = mappingId(draft.id());
        long sourceMappingId = mappingId(draft.sourceRevisionId());
        long sourceFilterId = filterId(draft.sourceRevisionId());

        // Niet-DRAFT: de bronrevisie staat op ACTIVE.
        assertThatThrownBy(() -> setup.deleteMapping(draft.sourceRevisionId(), sourceMappingId))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_NOT_EDITABLE");
        assertThatThrownBy(() -> setup.deleteFilter(draft.sourceRevisionId(), sourceFilterId))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_NOT_EDITABLE");
        assertThat(fieldMappings.findById(sourceMappingId)).isPresent();

        // Onbekende rij.
        assertThatThrownBy(() -> setup.deleteMapping(draft.id(), 999_999_999L))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("MAPPING_NOT_FOUND");
        assertThatThrownBy(() -> setup.deleteFilter(draft.id(), 999_999_999L))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("FILTER_NOT_FOUND");

        // Een bestaande rij van een ándere revisie is hier geen geldig doel: 404, en ze blijft staan.
        assertThatThrownBy(() -> setup.deleteMapping(draft.id(), sourceMappingId))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("MAPPING_NOT_FOUND");
        assertThatThrownBy(() -> setup.deleteFilter(draft.id(), sourceFilterId))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("FILTER_NOT_FOUND");
        assertThat(fieldMappings.findById(sourceMappingId)).isPresent();
        assertThat(recordFilters.findById(sourceFilterId)).isPresent();
        assertThat(fieldMappings.findById(draftMappingId)).isPresent();

        // Onbekende revisie blijft 404 REVISION_NOT_FOUND, vóór de rij beoordeeld wordt.
        assertThatThrownBy(() -> setup.deleteMapping(-1L, draftMappingId))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("REVISION_NOT_FOUND");
    }

    /** Tweemaal hetzelfde verwijderen levert nooit een tweede verwijdering op, maar een duidelijke 404. */
    @Test
    void aRepeatedDeleteIsA404AndNotASilentSuccess() {
        Draft draft = draft("DELTWICE");
        long mappingId = mappingId(draft.id());

        setup.deleteMapping(draft.id(), mappingId);
        assertThatThrownBy(() -> setup.deleteMapping(draft.id(), mappingId))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("MAPPING_NOT_FOUND");
    }

    // --- HTTP (endpoints E3 en E4) ------------------------------------------------------------------

    @Test
    void returnsTheRevisionViewOn200A204OnDeleteAndTheStableCodesOverHttp() throws Exception {
        Draft draft = draft("HTTPUPD");

        mockMvc.perform(patch(SETUP + "/revisions/{id}", draft.id())
                        .with(TestActors.as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyField\":\"MUNT\",\"createdBy\":\"" + USER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.revisionNumber").value(2))
                .andExpect(jsonPath("$.currencyField").value("MUNT"));
        assertThat(revisions.findById(draft.id()).orElseThrow().getRecordCurrencyField()).isEqualTo("MUNT");

        // R-REV-X3 over HTTP: 409 met een stabiele code, en niets gewijzigd.
        mockMvc.perform(patch(SETUP + "/revisions/{id}", draft.id())
                        .with(TestActors.as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierField\":\"ANDERS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDENTITY_CHANGE_NOT_ACKNOWLEDGED"));
        assertThat(revisions.findById(draft.id()).orElseThrow().getIdentitySupplierField())
                .isEqualTo("LEVERANCIER");

        // Mét bevestiging: 200, en de opgeslagen naam blijft die van het token (er is geen updated_by).
        mockMvc.perform(patch(SETUP + "/revisions/{id}", draft.id())
                        .with(TestActors.as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierField\":\"ANDERS\",\"acknowledgeIdentityChange\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierField").value("ANDERS"));
        ImportDefinitionRevision changed = revisions.findById(draft.id()).orElseThrow();
        assertThat(changed.getCreatedBy()).isEqualTo(USER);
        assertThat(changed.getCreatedBySubject()).isEqualTo(SUBJECT);

        // Een createdBy die iemand anders aanwijst: 400 ACTOR_FIELD_MISMATCH, niets gewijzigd (5A-6).
        mockMvc.perform(patch(SETUP + "/revisions/{id}", draft.id())
                        .with(TestActors.as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charset\":\"ISO-8859-1\",\"createdBy\":\"iemand.anders@example.test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        assertThat(revisions.findById(draft.id()).orElseThrow().getStructureCharset()).isEqualTo("UTF-8");

        // E4: 204 zonder inhoud, en de rij is weg.
        long mappingId = mappingId(draft.id());
        mockMvc.perform(delete(SETUP + "/revisions/{id}/mappings/{mappingId}", draft.id(), mappingId)
                        .with(TestActors.as(USER)))
                .andExpect(status().isNoContent());
        assertThat(fieldMappings.findById(mappingId)).isEmpty();

        long filterId = filterId(draft.id());
        mockMvc.perform(delete(SETUP + "/revisions/{id}/filters/{filterId}", draft.id(), filterId)
                        .with(TestActors.as(USER)))
                .andExpect(status().isNoContent());
        assertThat(recordFilters.findById(filterId)).isEmpty();

        // Onbekende rijen: 404 met een stabiele code.
        mockMvc.perform(delete(SETUP + "/revisions/{id}/mappings/{mappingId}", draft.id(), 999_999_999L)
                        .with(TestActors.as(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MAPPING_NOT_FOUND"));
        mockMvc.perform(delete(SETUP + "/revisions/{id}/filters/{filterId}", draft.id(), 999_999_999L)
                        .with(TestActors.as(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILTER_NOT_FOUND"));

        // PATCH op een niet-DRAFT over HTTP: 409 REVISION_NOT_EDITABLE.
        mockMvc.perform(patch(SETUP + "/revisions/{id}", draft.sourceRevisionId())
                        .with(TestActors.as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxCriticalSharePercent\":50}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));
    }

    // --- Helpers ------------------------------------------------------------------------------------

    /** Een DRAFT-opvolger plus de bronrevisie waaruit ze komt. */
    private record Draft(long id, long sourceRevisionId, MaterialisationFixtures.Template template) {
    }

    private static ActorIdentity actor() {
        return new ActorIdentity(USER, SUBJECT);
    }

    /**
     * De gewone werkwijze van S1-X: een ACTIVE revisie met één mapping en één filter, waarvan een
     * DRAFT-opvolger gemaakt wordt. Elke test wijzigt dus precies wat scherm 1a straks wijzigt.
     */
    private Draft draft(String prefix) {
        MaterialisationFixtures.Template source = ownDefinition(prefix, RevisionStatus.ACTIVE);
        RevisionView successor = successors.createSuccessor(source.revision().getId(),
                "Configuratie bijstellen (" + prefix + ")", actor());
        return new Draft(successor.id(), source.revision().getId(), source);
    }

    private MaterialisationFixtures.Template ownDefinition(String prefix, RevisionStatus status) {
        return fixtures.template(prefix, DefinitionUsageType.OWN_DEFINITION, status);
    }

    private long mappingId(long revisionId) {
        return fieldMappings.findByRevisionIdWithTargetField(revisionId).get(0).getId();
    }

    private long filterId(long revisionId) {
        return recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId).stream()
                .map(ImportRecordFilter::getId).findFirst().orElseThrow();
    }

    /**
     * Eén aanvaarde bronstaatrij op een koppeling van dezelfde definitie: precies de toestand die R-REV-X2
     * beschrijft ("er bestaat al aanvaarde bronstaat"). De keten koppeling → taak → levering → batch is
     * nodig omdat {@code catalog_source_state} naar de levering en de batch verwijst; de tabel heeft bewust
     * geen entiteit, dus de rij zelf komt er met JDBC in (zelfde patroon als {@code ScreeningSchemaTest}).
     */
    private void withAcceptedSourceState(Draft draft) {
        MaterialisationFixtures.Template template = draft.template();
        ImportDefinitionRevision source = revisions.findById(draft.sourceRevisionId()).orElseThrow();
        ImportLink link = links.saveAndFlush(new ImportLink(template.linkCode(),
                template.unique() + " koppeling", template.definition(), template.supplier(), "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, template.unique() + "-taak", TaskTriggerType.MANUAL));
        Delivery delivery = deliveries.saveAndFlush(
                new Delivery(task, "manual:" + template.unique(), Instant.now()));
        ImportBatch batch = batches.saveAndFlush(
                new ImportBatch(delivery, link, source, 1, USER));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("insert into catalog_source_state (import_link_id, identity_hash, identity_supplier, "
                        + "identity_supplier_group, identity_supplier_reference, identity_discount_state, "
                        + "identity_profile_kind, article_fingerprint, price_fingerprint, "
                        + "combined_fingerprint, base_price, state_origin, last_change_delivery_id, "
                        + "last_change_batch_id, created_at, updated_at) "
                        + "values (?, ?, 'LEV', 'GRP', 'REF', 'NOT_USED', 'THREE_PART', ?, ?, ?, ?, "
                        + "'BASELINE_ACCEPTED', ?, ?, ?, ?)",
                link.getId(), sha256("identity-" + template.unique()), sha256("article"), sha256("price"),
                sha256("combined"), new BigDecimal("1.500000"), delivery.getId(), batch.getId(), now, now);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required but not available", impossible);
        }
    }

    private static Update update() {
        return new Update();
    }

    /**
     * Een leesbare opbouw van {@link UpdateRevisionCommand}: het record heeft 28 velden waarvan een test
     * er telkens één of twee zet. Positioneel aanroepen zou hier het risico zijn dat twee verwisselde
     * {@code BigDecimal}-drempels een test onopgemerkt iets anders laten bewijzen.
     */
    private static final class Update {
        private String delimiter;
        private String charset;
        private Integer expectedColumnCount;
        private IdentityProfileKind identityProfileKind;
        private String supplierField;
        private String supplierGroupField;
        private String supplierReferenceField;
        private String discountCodeField;
        private String descriptionField;
        private Integer canonicalisationVersion;
        private BigDecimal creationThresholdSharePercent;
        private BigDecimal maxCriticalSharePercent;
        private BigDecimal priceDeviationPercent;
        private BigDecimal priceDerivationTolerance;
        private Boolean basePriceZeroAllowed;
        private Boolean acknowledgeIdentityChange;

        Update delimiter(String value) {
            this.delimiter = value;
            return this;
        }

        Update charset(String value) {
            this.charset = value;
            return this;
        }

        Update expectedColumnCount(Integer value) {
            this.expectedColumnCount = value;
            return this;
        }

        Update identityProfileKind(IdentityProfileKind value) {
            this.identityProfileKind = value;
            return this;
        }

        Update supplierField(String value) {
            this.supplierField = value;
            return this;
        }

        Update supplierGroupField(String value) {
            this.supplierGroupField = value;
            return this;
        }

        Update supplierReferenceField(String value) {
            this.supplierReferenceField = value;
            return this;
        }

        Update discountCodeField(String value) {
            this.discountCodeField = value;
            return this;
        }

        Update descriptionField(String value) {
            this.descriptionField = value;
            return this;
        }

        Update canonicalisationVersion(Integer value) {
            this.canonicalisationVersion = value;
            return this;
        }

        Update creationThresholdSharePercent(BigDecimal value) {
            this.creationThresholdSharePercent = value;
            return this;
        }

        Update maxCriticalSharePercent(BigDecimal value) {
            this.maxCriticalSharePercent = value;
            return this;
        }

        Update priceDeviationPercent(BigDecimal value) {
            this.priceDeviationPercent = value;
            return this;
        }

        Update priceDerivationTolerance(BigDecimal value) {
            this.priceDerivationTolerance = value;
            return this;
        }

        Update basePriceZeroAllowed(Boolean value) {
            this.basePriceZeroAllowed = value;
            return this;
        }

        Update acknowledgeIdentityChange(Boolean value) {
            this.acknowledgeIdentityChange = value;
            return this;
        }

        UpdateRevisionCommand build() {
            return new UpdateRevisionCommand(delimiter, null, charset, null, null, null,
                    expectedColumnCount, identityProfileKind, supplierField, supplierGroupField,
                    supplierReferenceField, discountCodeField, null, descriptionField, null,
                    canonicalisationVersion, creationThresholdSharePercent, maxCriticalSharePercent, null,
                    null, priceDeviationPercent, null, basePriceZeroAllowed, null,
                    priceDerivationTolerance, null, acknowledgeIdentityChange, null);
        }
    }
}
