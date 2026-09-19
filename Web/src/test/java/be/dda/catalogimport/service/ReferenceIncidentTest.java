package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.ReferenceControlDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.SourceStateDao;
import be.dda.catalogimport.domain.CandidateClassification;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.ReferenceMatchResult;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Fase 3f (ontwerp fase 3, R-ID-03/R-ID-04 en R-REF-01..R-REF-09): de volledige screening van een
 * levering met <b>kritieke koppelreferenties</b>, tegen de echte service, DAO's, het bestandsarchief,
 * {@code accept-baseline} en H2.
 *
 * <h2>De regel die hier bewezen wordt</h2>
 * Een EAN, PIM-ID, CAB-ID of {@code E_MARK+ARTICLE_REFERENCE} is geen gewoon wijzigbaar importveld
 * maar een koppeling naar een intern artikel (businessanalyse par. 14.23.3). Een andere, verwijderde,
 * hergebruikte of dubbelzinnige waarde wordt daarom <b>nooit</b> een gewone {@code UPDATE}: het record
 * wordt vastgehouden, zijn inhoudelijke mutatie wordt {@code BLOCKED} en er komt een apart
 * {@code IDENTITY_REFERENCE_INCIDENT} dat op een menselijke goedkeuring wacht. Wordt die regel ooit
 * zachter gemaakt, dan koppelt een import stilzwijgend prijzen en voorraad aan het verkeerde artikel —
 * en dat is achteraf niet meer te herstellen.
 *
 * <h2>Wat hier óók bewezen wordt</h2>
 * Dat de drie nieuwe passes (E1 classificatie, E2 referentiecontrole, E5 mutatiegeneratie) elk
 * afzonderlijk hervatbaar zijn zonder iets te verdubbelen, en dat {@code accept-baseline} een
 * vastgehouden regel nooit aanvaardt.
 * <p>
 * De microbatch- en chunkgrootte staan op 2, zodat elk scenario meerdere commits doorloopt. Elke test
 * bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=2",
        "catalogimport.screening.mutation-chunk-size=2",
        "catalogimport.screening.reference-control-chunk-size=2"})
@ActiveProfiles("local")
class ReferenceIncidentTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;EAN;CAB\n";
    /** Zonder de CAB-kolom: een niet-gemapte extra kolom levert een {@code HEADER_UNKNOWN_COLUMN} op. */
    private static final String EAN_HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;EAN\n";
    private static final String PLAIN_HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String USER = "tester@example.test";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @MockitoSpyBean
    private ReferenceControlDao referenceControl;
    @MockitoSpyBean
    private MutationDao mutationDao;
    @MockitoSpyBean
    private SourceStateDao sourceState;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private SourceStateBaselineService baseline;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportFieldCatalogRepository fieldCatalog;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Geen incident ----------------------------------------------------------------------------

    @Test
    void anUnchangedReferenceIsNoIncidentAtAll() {
        Fixture fixture = fixture("REFSAME", true, true);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-1;C-1",
                "ACME;G1;R2;2,25;Schroevendraaier;E-2;C-2"));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting met referenties");

        // R-REF-06: de eerste vastlegging gebeurt bij de aanvaarding, niet bij de screening.
        assertThat(referenceStates(fixture.linkId())).hasSize(4);
        // En de referentiedeelvingerafdruk (004-14, sinds 3c aanwezig) wordt nu werkelijk gevuld;
        // zonder die waarde zou de delta van de volgende levering het referentiedeel niet kunnen zien.
        assertThat(jdbc.queryForObject("select count(*) from catalog_source_state "
                        + "where import_link_id = ? and reference_fingerprint is not null", Long.class,
                fixture.linkId())).isEqualTo(2L);

        Delivered second = deliver(fixture, "REF-2", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-1;C-1",
                "ACME;G1;R2;2,25;Schroevendraaier;E-2;C-2"));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID);
        assertThat(outcome.unchangedCount()).isEqualTo(2L);
        assertThat(outcome.identityIncidentCount()).isZero();
        assertThat(outcome.contentMutationCount()).isZero();
        assertThat(issueCodes(second.batchId())).isEmpty();
        assertThat(incidentMutations(second.batchId())).isEmpty();
        reconciles(outcome);
    }

    /**
     * R-REF-03, de andere helft van het onderscheid: een referentietype dat de revisie <b>niet</b> mapt
     * levert géén uitspraak op. De bronkolom staat gewoon in het bestand en verandert zelfs van waarde;
     * zolang er geen mapping is, is er geen rij, geen vingerafdrukdeel en dus geen incident.
     */
    @Test
    void anUnmappedReferenceTypeYieldsNoStatementEvenWhenTheColumnChanges() {
        Fixture fixture = fixture("REFUNMAP", true, false);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;1,50;Boormachine;E-1;C-1"));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting met enkel EAN gemapt");

        // Alleen de gemapte referentie is vastgelegd; over CAB doet deze definitie geen uitspraak.
        assertThat(referenceStates(fixture.linkId())).singleElement()
                .satisfies(row -> assertThat(row.get("reference_type")).isEqualTo("EAN"));

        Delivered second = deliver(fixture, "REF-2", csv(HEADER, "ACME;G1;R1;1,50;Boormachine;E-1;C-9"));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.unchangedCount()).isEqualTo(1L);
        assertThat(outcome.identityIncidentCount()).isZero();
        // De enige melding is dat er een onbekende kolom in het bestand staat (R-STR-03, een
        // waarschuwing); over de inhoud van die kolom doet de referentiecontrole geen uitspraak.
        assertThat(issueCodes(second.batchId())).containsExactly("HEADER_UNKNOWN_COLUMN");
        assertThat(jdbc.queryForObject("select count(*) from import_candidate_reference "
                + "where batch_id = ? and reference_type = 'CAB_ID'", Long.class, second.batchId()))
                .isZero();
    }

    /** Regressie: een revisie zonder referentiemappings gedraagt zich exact als vóór bouwstap 3f. */
    @Test
    void aRevisionWithoutReferenceMappingsBehavesExactlyAsBefore() {
        Fixture fixture = fixture("REFNONE", false, false);
        Delivered delivered = deliver(fixture, "REF-1", csv(PLAIN_HEADER,
                "ACME;G1;R1;1,50;Boormachine",
                "ACME;G1;R2;2,25;Schroevendraaier"));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID);
        assertThat(outcome.newCount()).isEqualTo(2L);
        assertThat(outcome.identityIncidentCount()).isZero();
        assertThat(outcome.contentMutationCount()).isEqualTo(2L);
        assertThat(contentMutations(delivered.batchId()))
                .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("PLANNED"));
        // Geen enkele referentierij, en de referentievingerafdruk blijft die van versie 1: null.
        assertThat(jdbc.queryForObject("select count(*) from import_candidate_reference "
                + "where batch_id = ?", Long.class, delivered.batchId())).isZero();
        assertThat(jdbc.queryForObject("select reference_fingerprint from import_candidate_stage "
                + "where batch_id = ? and row_number = 2", byte[].class, delivered.batchId())).isNull();

        baseline.acceptBaseline(delivered.batchId(), USER, "nulmeting zonder referenties");
        assertThat(referenceStates(fixture.linkId())).isEmpty();
    }

    // --- De vier incidentsoorten ------------------------------------------------------------------

    /**
     * R-REF-02, de kern van bouwstap 3f: een gewijzigde EAN is nooit een gewone update. Het record
     * wordt vastgehouden, de inhoudelijke mutatie wordt {@code BLOCKED} en het incident wacht op
     * goedkeuring.
     */
    @Test
    void aChangedEanIsHeldAsAnIncidentAndNeverBecomesAnOrdinaryUpdate() {
        Fixture fixture = fixture("REFCHG", true, true);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;1,50;Boormachine;E-1;C-1"));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting");

        Delivered second = deliver(fixture, "REF-2", csv(HEADER, "ACME;G1;R1;1,50;Boormachine;E-9;C-1"));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        // De levering blijft bruikbaar; het oordeel is wél blokkerend (3f-tussenstand, par. 3.6).
        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.BLOCKING);
        assertThat(outcome.identityIncidentCount()).isEqualTo(1L);
        assertThat(outcome.changedCount()).isZero();
        assertThat(outcome.newCount()).isZero();
        assertThat(outcome.unchangedCount()).isZero();
        reconciles(outcome);
        assertThat(classification(second.batchId(), 2L))
                .isEqualTo(CandidateClassification.IDENTITY_INCIDENT.name());

        // De inhoudelijke mutatie bestaat wél - anders zou onzichtbaar zijn wat er zou gebeuren -
        // maar ze is geblokkeerd en draagt haar reden.
        assertThat(contentMutations(second.batchId())).singleElement().satisfies(row -> {
            assertThat(row.get("action_type")).isEqualTo("UPDATE");
            assertThat(row.get("status")).isEqualTo(MutationStatus.BLOCKED.name());
            assertThat(row.get("status_reason"))
                    .isEqualTo(MutationDao.BLOCKED_BY_IDENTITY_REFERENCE_INCIDENT);
        });
        // En daarnaast staat het incident zelf, met oude en nieuwe waarde.
        assertThat(incidentMutations(second.batchId())).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo(MutationStatus.AWAITING_APPROVAL.name());
            assertThat(row.get("target_domain")).isEqualTo("OFFER");
            assertThat(row.get("reference_type")).isEqualTo("EAN");
            assertThat(row.get("before_reference_value")).isEqualTo("E-1");
            assertThat(row.get("after_reference_value")).isEqualTo("E-9");
            assertThat(row.get("status_reason")).isEqualTo(ReferenceMatchResult.CHANGED.name());
            assertThat((String) row.get("idempotency_key"))
                    .endsWith(ReferenceControlDao.REFERENCE_KEY_SUFFIX + "EAN");
        });
        // De melding volgt par. 15.12: logische veldnaam, nieuwe waarde, oude waarde.
        Map<String, Object> issue = singleIssue(second.batchId(),
                DeliveryScreeningService.CODE_IDENTITY_REFERENCE_INCIDENT);
        assertThat(issue.get("severity")).isEqualTo(RowIssueSeverity.CRITICAL.name());
        assertThat(issue.get("issue_domain")).isEqualTo("IDENTITY_REFERENCE");
        assertThat(issue.get("field_name")).isEqualTo("EAN-barcode");
        assertThat(issue.get("source_value")).isEqualTo("E-9");
        assertThat(issue.get("expected_value")).isEqualTo(ReferenceMatchResult.CHANGED.name());
        assertThat((String) issue.get("message")).contains("EAN-barcode").contains("E-9").contains("E-1");

        // De ongewijzigde tweede referentie van dezelfde regel is gewoon in orde.
        assertThat(matchResult(second.batchId(), 2L, "EAN")).isEqualTo(ReferenceMatchResult.CHANGED.name());
        assertThat(matchResult(second.batchId(), 2L, "CAB_ID")).isEqualTo(ReferenceMatchResult.SAME.name());
    }

    /** R-REF-03: gemapt maar leeg terwijl er een actieve waarde is, is het verwijderen van de koppeling. */
    @Test
    void aMappedButEmptyReferenceWithAnActiveValueIsRemoved() {
        Fixture fixture = fixture("REFDEL", true, false);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;1,50;Boormachine;E-1;C-1"));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting");

        Delivered second = deliver(fixture, "REF-2", csv(HEADER, "ACME;G1;R1;1,50;Boormachine;;C-1"));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.identityIncidentCount()).isEqualTo(1L);
        assertThat(matchResult(second.batchId(), 2L, "EAN"))
                .isEqualTo(ReferenceMatchResult.REMOVED.name());
        assertThat(incidentMutations(second.batchId())).singleElement().satisfies(row -> {
            assertThat(row.get("before_reference_value")).isEqualTo("E-1");
            // "Gemapt maar leeg" is een uitspraak, geen ontbrekende waarde: na blijft leeg.
            assertThat(row.get("after_reference_value")).isNull();
            assertThat(row.get("status_reason")).isEqualTo(ReferenceMatchResult.REMOVED.name());
        });
        assertThat((String) singleIssue(second.batchId(),
                DeliveryScreeningService.CODE_IDENTITY_REFERENCE_INCIDENT).get("message"))
                .contains("mapped but empty");
    }

    /** R-REF-04: een bestaande aanbieding die de actieve referentie van een andere aanbieding opeist. */
    @Test
    void aCriticalReferenceThatIsActiveForAnotherOfferIsReused() {
        Fixture fixture = fixture("REFREUSE", false, true);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-1;C-1",
                "ACME;G1;R2;2,25;Schroevendraaier;E-2;C-2"));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting met twee CAB-ID's");

        // R2 eist de CAB-ID van R1 op. R1 zelf zit niet in deze levering: anders zouden beide regels
        // dezelfde waarde dragen en zou de duplicaatcontrole binnen de levering (D1) al eerder
        // toeslaan - dat is een ander scenario.
        Delivered second = deliver(fixture, "REF-2", csv(HEADER,
                "ACME;G1;R2;2,25;Schroevendraaier;E-2;C-1"));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.identityIncidentCount()).isEqualTo(1L);
        assertThat(outcome.unchangedCount()).isZero();
        assertThat(outcome.validRecordCount()).isEqualTo(1L);
        reconciles(outcome);
        assertThat(matchResult(second.batchId(), 2L, "CAB_ID"))
                .isEqualTo(ReferenceMatchResult.REUSED.name());
        assertThat(incidentMutations(second.batchId())).singleElement().satisfies(row -> {
            assertThat(row.get("reference_type")).isEqualTo("CAB_ID");
            assertThat(row.get("before_reference_value")).isEqualTo("C-2");
            assertThat(row.get("after_reference_value")).isEqualTo("C-1");
            assertThat(row.get("status_reason")).isEqualTo(ReferenceMatchResult.REUSED.name());
        });
        assertThat((String) singleIssue(second.batchId(),
                DeliveryScreeningService.CODE_IDENTITY_REFERENCE_INCIDENT).get("message"))
                .contains("already the active critical reference of another offer");
        // De referentie blijft waar ze stond: een incident wijzigt niets.
        assertThat(referenceStates(fixture.linkId())).hasSize(2);
        assertThat(activeReferenceOf(fixture.linkId(), "R1", "CAB_ID")).isEqualTo("C-1");
        assertThat(activeReferenceOf(fixture.linkId(), "R2", "CAB_ID")).isEqualTo("C-2");
    }

    /**
     * R-ID-04/R-REF-05: de EAN wijst naar aanbieding R1 en de CAB-ID naar aanbieding R2. Welke van
     * beide het artikel van deze nieuwe aanbieding is, valt niet vast te stellen — dus wordt er niets
     * gecreëerd.
     */
    @Test
    void aNewOfferThatPointsToTwoDifferentExistingOffersIsAmbiguousAndNotCreated() {
        Fixture fixture = fixture("REFAMB", true, true);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-1;C-1",
                "ACME;G1;R2;2,25;Schroevendraaier;E-2;C-2"));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting");

        Delivered second = deliver(fixture, "REF-2", csv(HEADER, "ACME;G1;R3;3,00;Hamer;E-1;C-2"));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.identityIncidentCount()).isEqualTo(1L);
        assertThat(outcome.newCount()).isZero();
        reconciles(outcome);
        assertThat(matchResult(second.batchId(), 2L, "EAN"))
                .isEqualTo(ReferenceMatchResult.AMBIGUOUS.name());
        assertThat(matchResult(second.batchId(), 2L, "CAB_ID"))
                .isEqualTo(ReferenceMatchResult.AMBIGUOUS.name());
        // Eén incident per betrokken referentietype, allebei wachtend op goedkeuring.
        assertThat(incidentMutations(second.batchId())).hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.get("status")).isEqualTo(MutationStatus.AWAITING_APPROVAL.name());
                    assertThat(row.get("status_reason"))
                            .isEqualTo(ReferenceMatchResult.AMBIGUOUS.name());
                });
        // Geen voorgestelde koppeling: dat is uitsluitend het geval mét precies één treffer.
        assertThat(issueCodes(second.batchId()))
                .doesNotContain(DeliveryScreeningService.CODE_REFERENCE_LINK_PROPOSED);
        // De creatie is geblokkeerd en wordt bij een aanvaarding niet doorgevoerd (R-ID-04).
        assertThat(contentMutations(second.batchId())).singleElement().satisfies(row -> {
            assertThat(row.get("action_type")).isEqualTo("CREATE");
            assertThat(row.get("status")).isEqualTo(MutationStatus.BLOCKED.name());
        });
        baseline.acceptBaseline(second.batchId(), USER, "aanvaarding met een dubbelzinnige regel");
        assertThat(sourceStateReferences(fixture.linkId())).containsExactlyInAnyOrder("R1", "R2");
    }

    /** R-REF-07: dezelfde referentiewaarde bij twee aanbiedingen in één levering houdt beide regels vast. */
    @Test
    void theSameEanTwiceInOneDeliveryHoldsBothLines() {
        Fixture fixture = fixture("REFDUP", true, false);
        Delivered delivered = deliver(fixture, "REF-1", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-1;C-1",
                "ACME;G1;R2;2,25;Schroevendraaier;E-1;C-2",
                "ACME;G1;R3;3,00;Hamer;E-3;C-3"));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        // Geen "laatste wint": beide regels zijn verdacht, de derde regel gaat gewoon door.
        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.BLOCKING);
        assertThat(outcome.identityIncidentCount()).isEqualTo(2L);
        assertThat(outcome.newCount()).isEqualTo(1L);
        reconciles(outcome);
        assertThat(jdbc.queryForList("select row_number from import_row_issue where batch_id = ? "
                        + "and issue_code = ? order by row_number", Long.class, delivered.batchId(),
                DeliveryScreeningService.CODE_DUPLICATE_REFERENCE_IN_DELIVERY))
                .containsExactly(2L, 3L);
        assertThat(singleIssueSeverity(delivered.batchId(),
                DeliveryScreeningService.CODE_DUPLICATE_REFERENCE_IN_DELIVERY))
                .isEqualTo(RowIssueSeverity.CRITICAL.name());
        // Een duplicaat binnen de levering levert geen migratie-incident op: er is geen voor/na-waarde.
        assertThat(incidentMutations(delivered.batchId())).isEmpty();
        assertThat(contentMutations(delivered.batchId())).hasSize(3);
        assertThat(statusOf(delivered.batchId(), "R1")).isEqualTo(MutationStatus.BLOCKED.name());
        assertThat(statusOf(delivered.batchId(), "R2")).isEqualTo(MutationStatus.BLOCKED.name());
        assertThat(statusOf(delivered.batchId(), "R3")).isEqualTo(MutationStatus.PLANNED.name());

        baseline.acceptBaseline(delivered.batchId(), USER, "aanvaarding met een dubbele referentie");
        assertThat(sourceStateReferences(fixture.linkId())).containsExactly("R3");
        assertThat(referenceStates(fixture.linkId())).hasSize(1);
    }

    // --- Matchingstap 2: een ander aanbod voor hetzelfde artikel -----------------------------------

    /**
     * R-ID-03: geen aanbiedingsmatch maar precies één eenduidige kritieke match. Dat is géén incident
     * maar de indirecte artikelkoppeling van par. 14.23.3 — de nieuwe aanbieding wordt gewoon gemaakt
     * en de bestaande aanbiedingsidentiteit blijft onaangeroerd.
     */
    @Test
    void aNewOfferThatMatchesExactlyOneExistingOfferIsCreatedWithAProposedLink() {
        Fixture fixture = fixture("REFLINK", true, false);
        Delivered first = deliver(fixture, "REF-1", csv(EAN_HEADER, "ACME;G1;R1;1,50;Boormachine;E-1"));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting");
        Long existingState = sourceStateIdOf(fixture.linkId(), "R1");

        // Een andere leveranciersreferentie voor hetzelfde artikel, met dezelfde EAN.
        Delivered second = deliver(fixture, "REF-2", csv(EAN_HEADER, "ACME;G1;R9;1,75;Boormachine;E-1"));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        // Een informatieve vaststelling verandert het eindoordeel niet.
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID);
        assertThat(outcome.newCount()).isEqualTo(1L);
        assertThat(outcome.identityIncidentCount()).isZero();
        reconciles(outcome);
        assertThat(contentMutations(second.batchId())).singleElement().satisfies(row -> {
            assertThat(row.get("action_type")).isEqualTo("CREATE");
            assertThat(row.get("status")).isEqualTo(MutationStatus.PLANNED.name());
        });
        Map<String, Object> issue = singleIssue(second.batchId(),
                DeliveryScreeningService.CODE_REFERENCE_LINK_PROPOSED);
        assertThat(issue.get("severity")).isEqualTo(RowIssueSeverity.INFO.name());
        assertThat(issue.get("expected_value")).isEqualTo(String.valueOf(existingState));
        assertThat((String) issue.get("message"))
                .contains("another supplier offer for the same article");
        assertThat(matchResult(second.batchId(), 2L, "EAN")).isEqualTo(ReferenceMatchResult.SAME.name());

        baseline.acceptBaseline(second.batchId(), USER, "nulmeting van het tweede aanbod");
        // Beide aanbiedingen bestaan; de referentie blijft bij de eerste en wordt niet gedupliceerd.
        assertThat(sourceStateReferences(fixture.linkId())).containsExactlyInAnyOrder("R1", "R9");
        assertThat(referenceStates(fixture.linkId())).singleElement().satisfies(row -> {
            assertThat(row.get("value_normalised")).isEqualTo("E-1");
            assertThat(((Number) row.get("source_state_id")).longValue()).isEqualTo(existingState);
        });
    }

    // --- accept-baseline --------------------------------------------------------------------------

    /**
     * R-REF-06 en R-REF-09 samen: de aanvaarding legt de referenties van de aanvaarde regels vast en
     * raakt de vastgehouden regel met geen enkele query aan. Herhalen verandert niets.
     */
    @Test
    void acceptBaselineWritesTheReferenceStateForAcceptedRowsAndNeverForAHeldRow() {
        Fixture fixture = fixture("REFACC", true, false);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;1,50;Boormachine;E-1;C-1"));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting");

        Delivered second = deliver(fixture, "REF-2", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-9;C-1",
                "ACME;G1;R2;2,25;Schroevendraaier;E-2;C-2"));
        ScreeningOutcome outcome = screening.screen(second.batchId());
        assertThat(outcome.identityIncidentCount()).isEqualTo(1L);
        assertThat(outcome.newCount()).isEqualTo(1L);

        baseline.acceptBaseline(second.batchId(), USER, "aanvaarding met één vastgehouden regel");

        // De nieuwe aanbieding en haar referentie zijn aanvaard...
        assertThat(sourceStateReferences(fixture.linkId())).containsExactlyInAnyOrder("R1", "R2");
        assertThat(activeReferenceOf(fixture.linkId(), "R2", "EAN")).isEqualTo("E-2");
        // ... en de vastgehouden regel niet: R1 houdt haar oude EAN en E-9 bestaat nergens.
        assertThat(activeReferenceOf(fixture.linkId(), "R1", "EAN")).isEqualTo("E-1");
        assertThat(jdbc.queryForObject("select count(*) from catalog_reference_state "
                + "where value_normalised = 'E-9'", Long.class)).isZero();
        // De geblokkeerde mutatie en het incident blijven staan; enkel PLANNED wordt SKIPPED.
        assertThat(statusOf(second.batchId(), "R1")).isEqualTo(MutationStatus.BLOCKED.name());
        assertThat(statusOf(second.batchId(), "R2")).isEqualTo(MutationStatus.SKIPPED.name());
        assertThat(incidentMutations(second.batchId())).singleElement().satisfies(row ->
                assertThat(row.get("status")).isEqualTo(MutationStatus.AWAITING_APPROVAL.name()));

        // Idempotent: exact wat een hervatte aanvaarding doet - dezelfde chunk nog een keer.
        List<Map<String, Object>> before = referenceStates(fixture.linkId());
        int written = sourceState.insertReferencesFromStage(new SourceStateDao.AcceptanceContext(
                fixture.linkId(), second.batchId(), second.deliveryId(), fixture.libraryCode(),
                IdentityProfileKind.THREE_PART.name(), "BASELINE_ACCEPTED", USER,
                java.time.Instant.now(), java.time.Instant.now()), 0L, 999L);
        assertThat(written).isZero();
        assertThat(referenceStates(fixture.linkId())).isEqualTo(before);
    }

    /**
     * De databaseconstraint is de harde garantie, niet de controle erboven: maakt een andere batch de
     * waarde actief tussen de controle en de insert, dan faalt de chunk. Wat telt is dat er dan
     * <b>niets</b> half geschreven is en dat de batch opnieuw aanvaard kan worden.
     */
    @Test
    void aRaceOnTheActiveReferenceGivesAConflictWithoutLeavingHalfWork() {
        Fixture fixture = fixture("REFRACE", true, false);
        Delivered delivered = deliver(fixture, "REF-1", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-1;C-1"));
        screening.screen(delivered.batchId());

        Mockito.doThrow(new DuplicateKeyException("simulated race on uk_catalog_reference_state_active"))
                .when(sourceState).insertReferencesFromStage(any(), anyLong(), anyLong());

        assertThatThrownBy(() -> baseline.acceptBaseline(delivered.batchId(), USER, "gelijktijdige claim"))
                .isInstanceOf(ConflictException.class)
                .extracting(failure -> ((ConflictException) failure).getCode())
                .isEqualTo(SourceStateBaselineService.CODE_REFERENCE_ALREADY_ACTIVE);

        // Niets half: de bronstaatrij van dezelfde chunk is mee teruggedraaid.
        assertThat(sourceStateReferences(fixture.linkId())).isEmpty();
        assertThat(referenceStates(fixture.linkId())).isEmpty();
        assertThat(batches.findById(delivered.batchId()).orElseThrow().getStatus())
                .isEqualTo(ImportBatchStatus.SCREENED);

        Mockito.reset(sourceState);
        baseline.acceptBaseline(delivered.batchId(), USER, "tweede poging");

        assertThat(sourceStateReferences(fixture.linkId())).containsExactly("R1");
        assertThat(referenceStates(fixture.linkId())).hasSize(1);
    }

    // --- Passopsplitsing en hervatting -------------------------------------------------------------

    /** Breekt de verwerking ná de classificatiepass af, dan hervat ze zonder iets te verdubbelen. */
    @Test
    void aBatchThatBreaksAfterTheClassifyPassResumesWithoutDuplicates() {
        Fixture fixture = fixture("REFE1", true, false);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-1;C-1",
                "ACME;G1;R2;2,25;Schroevendraaier;E-2;C-2"));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting");

        Delivered second = deliver(fixture, "REF-2", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-8;C-1",
                "ACME;G1;R2;2,25;Schroevendraaier;E-9;C-2"));
        Mockito.doThrow(new UncheckedIOException(new IOException("simulated failure after E1")))
                .when(referenceControl).findCandidates(anyLong(), anyLong(), anyString(), anyLong(),
                        anyLong());

        assertThatThrownBy(() -> screening.screen(second.batchId()))
                .isInstanceOf(UncheckedIOException.class);

        ImportBatch interrupted = batches.findById(second.batchId()).orElseThrow();
        assertThat(interrupted.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        // E1 is klaar en vastgelegd, E2 is niet begonnen.
        assertThat(interrupted.getClassifyProgressRowNumber()).isEqualTo(3L);
        assertThat(interrupted.getReferenceProgressRowNumber()).isZero();
        assertThat(incidentMutations(second.batchId())).isEmpty();
        assertThat(contentMutations(second.batchId())).isEmpty();

        Mockito.reset(referenceControl);
        ScreeningOutcome outcome = screening.continueMutating(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.identityIncidentCount()).isEqualTo(2L);
        assertThat(incidentMutations(second.batchId())).hasSize(2);
        assertThat(idempotencyKeys(second.batchId())).doesNotHaveDuplicates();
        assertThat(issueCount(second.batchId(),
                DeliveryScreeningService.CODE_IDENTITY_REFERENCE_INCIDENT)).isEqualTo(2L);
        reconciles(outcome);
    }

    /** Breekt ze ná de referentiecontrole af, dan worden de incidenten niet een tweede keer geschreven. */
    @Test
    void aBatchThatBreaksAfterTheReferencePassResumesWithoutDuplicateIncidents() {
        Fixture fixture = fixture("REFE2", true, false);
        Delivered first = deliver(fixture, "REF-1", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-1;C-1",
                "ACME;G1;R2;2,25;Schroevendraaier;E-2;C-2"));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting");

        Delivered second = deliver(fixture, "REF-2", csv(HEADER,
                "ACME;G1;R1;1,50;Boormachine;E-8;C-1",
                "ACME;G1;R2;2,25;Schroevendraaier;E-9;C-2"));
        Mockito.doThrow(new UncheckedIOException(new IOException("simulated failure after E2")))
                .when(mutationDao).insertContentMutations(any(), any(), anyLong(), anyLong(), any());

        assertThatThrownBy(() -> screening.screen(second.batchId()))
                .isInstanceOf(UncheckedIOException.class);

        ImportBatch interrupted = batches.findById(second.batchId()).orElseThrow();
        assertThat(interrupted.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        assertThat(interrupted.getReferenceProgressRowNumber()).isEqualTo(3L);
        assertThat(interrupted.getMutationProgressRowNumber()).isZero();
        // De incidenten en hun meldingen zijn al vastgelegd; de inhoudelijke mutaties nog niet.
        assertThat(incidentMutations(second.batchId())).hasSize(2);
        assertThat(issueCount(second.batchId(),
                DeliveryScreeningService.CODE_IDENTITY_REFERENCE_INCIDENT)).isEqualTo(2L);
        assertThat(contentMutations(second.batchId())).isEmpty();

        Mockito.reset(mutationDao);
        ScreeningOutcome outcome = screening.continueMutating(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.identityIncidentCount()).isEqualTo(2L);
        // Niets verdubbeld: noch de incidenten, noch de meldingen, noch de mutaties.
        assertThat(incidentMutations(second.batchId())).hasSize(2);
        assertThat(issueCount(second.batchId(),
                DeliveryScreeningService.CODE_IDENTITY_REFERENCE_INCIDENT)).isEqualTo(2L);
        assertThat(contentMutations(second.batchId())).hasSize(2);
        assertThat(idempotencyKeys(second.batchId())).doesNotHaveDuplicates();
        reconciles(outcome);
    }

    // --- Helpers ------------------------------------------------------------------------------------

    /** {@code valid = new + changed + unchanged + duplicate_identity + identity_incident} (R-REF-09). */
    private static void reconciles(ScreeningOutcome outcome) {
        assertThat(outcome.newCount() + outcome.changedCount() + outcome.unchangedCount()
                + outcome.duplicateIdentityCount() + outcome.identityIncidentCount())
                .isEqualTo(outcome.validRecordCount());
    }

    private static byte[] csv(String header, String... rows) {
        return (header + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private List<Map<String, Object>> referenceStates(long importLinkId) {
        return jdbc.queryForList("select reference_type, value_normalised, value_raw, source_state_id, "
                + "active_marker, accepted_by from catalog_reference_state where import_link_id = ? "
                + "and active_marker = true order by reference_type, value_normalised", importLinkId);
    }

    private List<String> sourceStateReferences(long importLinkId) {
        return jdbc.queryForList("select identity_supplier_reference from catalog_source_state "
                + "where import_link_id = ? order by identity_supplier_reference", String.class,
                importLinkId);
    }

    private Long sourceStateIdOf(long importLinkId, String supplierReference) {
        return jdbc.queryForObject("select id from catalog_source_state where import_link_id = ? "
                + "and identity_supplier_reference = ?", Long.class, importLinkId, supplierReference);
    }

    private String activeReferenceOf(long importLinkId, String supplierReference, String referenceType) {
        List<String> values = jdbc.queryForList("select reference.value_normalised "
                        + "from catalog_reference_state reference "
                        + "join catalog_source_state state on state.id = reference.source_state_id "
                        + "where state.import_link_id = ? and state.identity_supplier_reference = ? "
                        + "and reference.reference_type = ? and reference.active_marker = true",
                String.class, importLinkId, supplierReference, referenceType);
        return values.isEmpty() ? null : values.get(0);
    }

    private List<Map<String, Object>> contentMutations(long batchId) {
        return jdbc.queryForList("select action_type, status, status_reason, "
                + "identity_supplier_reference, domain_mask from import_mutation "
                + "where batch_id = ? and action_type in ('CREATE', 'UPDATE') order by id", batchId);
    }

    private List<Map<String, Object>> incidentMutations(long batchId) {
        return jdbc.queryForList("select status, status_reason, target_domain, reference_type, "
                + "before_reference_value, after_reference_value, idempotency_key, source_row_number "
                + "from import_mutation where batch_id = ? and action_type = "
                + "'IDENTITY_REFERENCE_INCIDENT' order by source_row_number, reference_type", batchId);
    }

    private List<String> idempotencyKeys(long batchId) {
        return jdbc.queryForList("select idempotency_key from import_mutation where batch_id = ?",
                String.class, batchId);
    }

    private String statusOf(long batchId, String supplierReference) {
        return jdbc.queryForObject("select status from import_mutation where batch_id = ? "
                        + "and action_type in ('CREATE', 'UPDATE') and identity_supplier_reference = ?",
                String.class, batchId, supplierReference);
    }

    private List<String> issueCodes(long batchId) {
        return jdbc.queryForList("select issue_code from import_row_issue where batch_id = ? "
                + "order by id", String.class, batchId);
    }

    private long issueCount(long batchId, String issueCode) {
        Long count = jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                + "and issue_code = ?", Long.class, batchId, issueCode);
        return count == null ? 0L : count;
    }

    private Map<String, Object> singleIssue(long batchId, String issueCode) {
        return jdbc.queryForMap("select severity, issue_domain, control_level, field_name, "
                + "source_value, expected_value, message, row_number from import_row_issue "
                + "where batch_id = ? and issue_code = ?", batchId, issueCode);
    }

    private String singleIssueSeverity(long batchId, String issueCode) {
        return jdbc.queryForList("select severity from import_row_issue where batch_id = ? "
                + "and issue_code = ?", String.class, batchId, issueCode).get(0);
    }

    private String classification(long batchId, long rowNumber) {
        return jdbc.queryForObject("select classification from import_candidate_stage "
                + "where batch_id = ? and row_number = ?", String.class, batchId, rowNumber);
    }

    private String matchResult(long batchId, long rowNumber, String referenceType) {
        return jdbc.queryForObject("select match_result from import_candidate_reference "
                        + "where batch_id = ? and row_number = ? and reference_type = ?", String.class,
                batchId, rowNumber, referenceType);
    }

    private Delivered deliver(Fixture fixture, String reference, byte[] content) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, USER, null, null,
                "levering.csv", new ByteArrayInputStream(content)).delivery();
        return new Delivered(deliveries.findById(view.deliveryId()).orElseThrow().getId(),
                view.batch().batchId());
    }

    /**
     * @param withEan mapt de EAN-kolom als kritieke koppelreferentie
     * @param withCab mapt de CAB-kolom als kritieke koppelreferentie; geen van beide bouwt exact de
     *                revisie van vóór bouwstap 3f (canonicalisatieversie 1, geen enkele mapping)
     */
    private Fixture fixture(String prefix, boolean withEan, boolean withCab) {
        String unique = "RI" + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation,
                unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setStatus(RevisionStatus.ACTIVE);
        if (withEan || withCab) {
            revision.setRecordCanonicalisationVersion(2);
        }
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        if (withEan) {
            fieldMappings.saveAndFlush(referenceMapping(stored, 1, "EAN", "EAN"));
        }
        if (withCab) {
            fieldMappings.saveAndFlush(referenceMapping(stored, 2, "CAB_ID", "CAB"));
        }

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        // Elke test krijgt haar eigen bibliotheek. Dat is geen testkunstje maar de regel zelf: een
        // kritieke koppelreferentie is uniek per BIBLIOTHEEK, niet per importkoppeling
        // (par. 14.23.3). Twee koppelingen naar dezelfde bibliotheek delen die naamruimte dus echt,
        // en met één gedeelde bibliotheek zou de ene test de referentiewaarden van de andere claimen.
        String libraryCode = unique.substring(0, Math.min(unique.length(), 20));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, libraryCode));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId(), libraryCode);
    }

    /** De mapping gebruikt de geseede catalogusvelden; de test verzint geen eigen referentietypes. */
    private ImportFieldMapping referenceMapping(ImportDefinitionRevision revision, int sequenceNumber,
                                                String code, String sourceReference) {
        ImportFieldCatalogEntry target = fieldCatalog.findById(code).orElseThrow();
        ImportFieldMapping mapping = new ImportFieldMapping(revision, sequenceNumber, target,
                FieldValueKind.SOURCE_FIELD, target.getDataType(), target.getDefaultOwner(),
                target.getIdentityClass());
        mapping.setSourceReference(sourceReference);
        mapping.setReferenceType(target.getReferenceType());
        return mapping;
    }

    private record Fixture(long taskId, long linkId, long revisionId, String libraryCode) {
    }

    private record Delivered(long deliveryId, long batchId) {
    }
}
