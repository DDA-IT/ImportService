package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.IssueCaseEventRepository;
import be.dda.catalogimport.dao.IssueCaseRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bouwstap S2-B1 (docs/design/issue-case-design.md §1/§5): bewijst dat changeset 012 migreert, dat
 * Hibernate {@link IssueCase} en {@link IssueCaseEvent} met {@code ddl-auto: validate} aanvaardt, en
 * dat de databaseconstraints van het behandelgeval werkelijk afdwingen wat het ontwerp belooft.
 * Patroon {@link ScreeningSchemaTest}: codes zijn per test uniek omdat de H2-database gedeeld is;
 * {@code import_issue_group} heeft geen entiteit en wordt via {@link JdbcTemplate} benaderd.
 */
@SpringBootTest
@ActiveProfiles("local")
class IssueCaseSchemaTest {

    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private IssueCaseRepository issueCases;
    @Autowired
    private IssueCaseEventRepository issueCaseEvents;
    @Autowired
    private JdbcTemplate jdbc;

    // --- issue_case: identiteit en verplichte kolommen ------------------------------------------

    @Test
    void persistsAnIssueCaseWithTheDocumentedDefaultsAndMapping() {
        Scenario s = scenario("CASE");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        Instant now = Instant.now();

        IssueCase created = issueCases.saveAndFlush(newCase(s, "PRICE_UNREADABLE", "PRIJS|unreadable",
                batch, now));

        IssueCase found = issueCases.findById(created.getId()).orElseThrow();
        assertThat(found.getImportLink().getId()).isEqualTo(s.link().getId());
        assertThat(found.getIssueCode()).isEqualTo("PRICE_UNREADABLE");
        assertThat(found.getSignature()).isEqualTo("PRIJS|unreadable");
        assertThat(found.getStatus()).isEqualTo(IssueCaseStatus.AWAITING_REVIEW);
        assertThat(found.getObservationCount()).isEqualTo(1L);
        assertThat(found.getTotalOccurrenceCount()).isEqualTo(500L);
        assertThat(found.getReopenCount()).isZero();
        assertThat(found.getStatusChangedAt()).isNull();
        assertThat(found.getStatusReason()).isNull();
        assertThat(found.getDecisionRevision()).isNull();
        assertThat(found.getFirstSeenBatch().getId()).isEqualTo(batch.getId());
        assertThat(found.getLastSeenBatch().getId()).isEqualTo(batch.getId());
        assertThat(found.getLastSeenRevision().getId()).isEqualTo(s.revision().getId());
    }

    /** D1/R-CASE-01: {@code uk_issue_case_identity} maakt een tweede geval voor dezelfde identiteit onmogelijk. */
    @Test
    void rejectsASecondIssueCaseForTheSameIdentity() {
        Scenario s = scenario("CASEDUP");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        issueCases.saveAndFlush(newCase(s, "PRICE_UNREADABLE", "PRIJS|unreadable", batch, Instant.now()));

        assertThatThrownBy(() -> issueCases.saveAndFlush(
                newCase(s, "PRICE_UNREADABLE", "PRIJS|unreadable", batch, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Een andere signatuur, of een andere koppeling, botst niet.
        assertThatCode(() -> issueCases.saveAndFlush(
                newCase(s, "PRICE_UNREADABLE", "BEDRAG|unreadable", batch, Instant.now())))
                .doesNotThrowAnyException();
    }

    /** D3: {@code ck_issue_case_status} laat uitsluitend de vier ontworpen waarden toe, geen ACCEPTED_ of DETECTED. */
    @Test
    void allowsOnlyTheFourDocumentedStatusValues() {
        Scenario s = scenario("CASESTAT");
        Long linkId = s.link().getId();
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));

        for (IssueCaseStatus status : IssueCaseStatus.values()) {
            boolean decided = status != IssueCaseStatus.AWAITING_REVIEW;
            assertThatCode(() -> insertIssueCase(linkId, "CODE-" + status, "SIG-" + status,
                    status.name(), 1L, batch.getId(), s.revision().getId(), decided))
                    .as(status.name()).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> insertIssueCase(linkId, "CODE-BAD", "SIG-BAD", "DETECTED", 1L,
                batch.getId(), s.revision().getId(), false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertIssueCase(linkId, "CODE-BAD2", "SIG-BAD2", "ACCEPTED_FOR_BATCH",
                1L, batch.getId(), s.revision().getId(), false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** {@code ck_issue_case_decided}: elke niet-AWAITING_REVIEW-status draagt een reden en een tijdstip. */
    @Test
    void requiresAReasonAndATimestampForEveryDecidedStatus() {
        Scenario s = scenario("CASEDEC");
        Long linkId = s.link().getId();
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));

        assertThatThrownBy(() -> insertIssueCase(linkId, "CODE-NR", "SIG-NR", "REJECTED", 1L,
                batch.getId(), s.revision().getId(), false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** {@code ck_issue_case_observations}: een geval zonder waarnemingen mag enkel bestaan met een menselijke actie. */
    @Test
    void allowsZeroObservationsOnlyWhenTheCaseWasAlreadyDecided() {
        Scenario s = scenario("CASEOBS");
        Long linkId = s.link().getId();
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));

        assertThatThrownBy(() -> insertIssueCase(linkId, "CODE-OBS0", "SIG-OBS0", "AWAITING_REVIEW",
                0L, batch.getId(), s.revision().getId(), false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertIssueCase(linkId, "CODE-OBS1", "SIG-OBS1", "REJECTED", 0L,
                batch.getId(), s.revision().getId(), true))
                .doesNotThrowAnyException();
    }

    // --- issue_case_event: append-only auditregister ---------------------------------------------

    @Test
    void persistsACreatedEventWithoutAPreviousStatus() {
        Scenario s = scenario("EVTCR");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        IssueCase issueCase = issueCases.saveAndFlush(
                newCase(s, "PRICE_UNREADABLE", "PRIJS|unreadable", batch, Instant.now()));

        IssueCaseEvent event = issueCaseEvents.saveAndFlush(new IssueCaseEvent(issueCase,
                IssueCaseEventKind.CREATED, null, IssueCaseStatus.AWAITING_REVIEW,
                "Eerste vaststelling in batch " + batch.getId(), IssueCaseEventSource.SYSTEM, null, null,
                Instant.now(), batch));

        IssueCaseEvent found = issueCaseEvents.findById(event.getId()).orElseThrow();
        assertThat(found.getPreviousStatus()).isNull();
        assertThat(found.getNewStatus()).isEqualTo(IssueCaseStatus.AWAITING_REVIEW);
        assertThat(found.getSource()).isEqualTo(IssueCaseEventSource.SYSTEM);
        assertThat(found.getChangedBy()).isNull();
        assertThat(found.getObservationBatch().getId()).isEqualTo(batch.getId());
        assertThat(issueCaseEvents.findByIssueCaseIdOrderByIdAsc(issueCase.getId())).hasSize(1);
    }

    @Test
    void keepsThePreviousEventUnchangedWhenANewOneIsWritten() {
        Scenario s = scenario("EVTAPP");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        IssueCase issueCase = issueCases.saveAndFlush(
                newCase(s, "PRICE_UNREADABLE", "PRIJS|unreadable", batch, Instant.now()));
        IssueCaseEvent first = issueCaseEvents.saveAndFlush(new IssueCaseEvent(issueCase,
                IssueCaseEventKind.CREATED, null, IssueCaseStatus.AWAITING_REVIEW, "Aangemaakt",
                IssueCaseEventSource.SYSTEM, null, null, Instant.now(), batch));

        issueCaseEvents.saveAndFlush(new IssueCaseEvent(issueCase, IssueCaseEventKind.STATUS_CHANGE,
                IssueCaseStatus.AWAITING_REVIEW, IssueCaseStatus.REJECTED, "Afgewezen: onjuiste prijs",
                IssueCaseEventSource.HUMAN, "beoordelaar", null, Instant.now(), null));

        List<IssueCaseEvent> history = issueCaseEvents.findByIssueCaseIdOrderByIdAsc(issueCase.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getId()).isEqualTo(first.getId());
        assertThat(history.get(0).getReason()).isEqualTo("Aangemaakt");
        assertThat(history.get(1).getReason()).isEqualTo("Afgewezen: onjuiste prijs");
    }

    /** {@code CREATED} draagt per definitie geen vorige status ({@code ck_issue_case_event_previous}). */
    @Test
    void rejectsACreatedEventThatCarriesAPreviousStatus() {
        Scenario s = scenario("EVTPREV");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        IssueCase issueCase = issueCases.saveAndFlush(
                newCase(s, "PRICE_UNREADABLE", "PRIJS|unreadable", batch, Instant.now()));

        assertThatThrownBy(() -> issueCaseEvents.saveAndFlush(new IssueCaseEvent(issueCase,
                IssueCaseEventKind.CREATED, IssueCaseStatus.AWAITING_REVIEW, IssueCaseStatus.AWAITING_REVIEW,
                "fout", IssueCaseEventSource.SYSTEM, null, null, Instant.now(), batch)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code ck_issue_case_event_source}: {@code HUMAN} draagt altijd een naam, {@code SYSTEM} draagt
     * nooit een naam of een subject.
     */
    @Test
    void enforcesTheDocumentedSourceRulesForWhoActed() {
        Scenario s = scenario("EVTSRC");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        IssueCase issueCase = issueCases.saveAndFlush(
                newCase(s, "PRICE_UNREADABLE", "PRIJS|unreadable", batch, Instant.now()));

        assertThatThrownBy(() -> issueCaseEvents.saveAndFlush(new IssueCaseEvent(issueCase,
                IssueCaseEventKind.STATUS_CHANGE, IssueCaseStatus.AWAITING_REVIEW, IssueCaseStatus.REJECTED,
                "geen naam", IssueCaseEventSource.HUMAN, null, null, Instant.now(), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> issueCaseEvents.saveAndFlush(new IssueCaseEvent(issueCase,
                IssueCaseEventKind.STATUS_CHANGE, IssueCaseStatus.AWAITING_REVIEW, IssueCaseStatus.REJECTED,
                "systeem met naam", IssueCaseEventSource.SYSTEM, "iemand", null, Instant.now(), batch)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> issueCaseEvents.saveAndFlush(new IssueCaseEvent(issueCase,
                IssueCaseEventKind.STATUS_CHANGE, IssueCaseStatus.AWAITING_REVIEW, IssueCaseStatus.REJECTED,
                "correct", IssueCaseEventSource.HUMAN, "beoordelaar", null, Instant.now(), null)))
                .doesNotThrowAnyException();
    }

    // --- import_issue_group.issue_case_id: additieve koppelkolom (012-3) -------------------------

    @Test
    void addsTheNullableCaseLinkColumnOnImportIssueGroupWithoutBackfill() {
        Scenario s = scenario("GRPLINK");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        insertIssueGroup(batch.getId(), "PRICE_UNREADABLE", "PRIJS|unreadable", 500L, 200);

        assertThat(jdbc.queryForObject("select issue_case_id from import_issue_group "
                + "where batch_id = ? and signature = ?", Long.class, batch.getId(), "PRIJS|unreadable"))
                .isNull();

        IssueCase issueCase = issueCases.saveAndFlush(
                newCase(s, "PRICE_UNREADABLE", "PRIJS|unreadable", batch, Instant.now()));
        jdbc.update("update import_issue_group set issue_case_id = ? where batch_id = ? and signature = ?",
                issueCase.getId(), batch.getId(), "PRIJS|unreadable");

        assertThat(jdbc.queryForObject("select issue_case_id from import_issue_group "
                + "where batch_id = ? and signature = ?", Long.class, batch.getId(), "PRIJS|unreadable"))
                .isEqualTo(issueCase.getId());
    }

    @Test
    void refusesAnImportIssueGroupThatPointsToAnUnknownIssueCase() {
        Scenario s = scenario("GRPFK");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        insertIssueGroup(batch.getId(), "PRICE_UNREADABLE", "PRIJS|unreadable", 500L, 200);

        assertThatThrownBy(() -> jdbc.update("update import_issue_group set issue_case_id = ? "
                        + "where batch_id = ? and signature = ?", 999_999_999L, batch.getId(),
                "PRIJS|unreadable"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Helpers --------------------------------------------------------------------------------

    private IssueCase newCase(Scenario s, String issueCode, String signature, ImportBatch batch, Instant at) {
        return new IssueCase(s.link(), issueCode, signature, RowIssueSeverity.ERROR, IssueDomain.PRICE,
                ControlLevel.RECORD, ImpactScope.RECORD, IssueIncidentKind.PRICE, "BASE_PRICE", null,
                1L, 500L, at, at, batch, batch, s.revision(), at);
    }

    private void insertIssueCase(Long linkId, String issueCode, String signature, String status,
                                 long observationCount, Long batchId, Long revisionId, boolean decided) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("insert into issue_case (import_link_id, issue_code, signature, severity, "
                + "issue_domain, control_level, impact_scope, incident_kind, status, observation_count, "
                + "total_occurrence_count, first_seen_at, last_seen_at, first_seen_batch_id, "
                + "last_seen_batch_id, last_seen_revision_id, status_reason, status_changed_at, "
                + "created_at, updated_at) values (?, ?, ?, 'ERROR', 'PRICE', 'RECORD', 'RECORD', 'PRICE', "
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                linkId, issueCode, signature, status, observationCount, 500L, now, now, batchId, batchId,
                revisionId, decided ? "reden" : null, decided ? now : null, now, now);
    }

    private void insertIssueGroup(Long batchId, String issueCode, String signature, long occurrences,
                                  int samples) {
        jdbc.update("insert into import_issue_group (batch_id, issue_code, signature, severity, "
                        + "issue_domain, control_level, impact_scope, occurrence_count, recorded_sample_count, "
                        + "first_detected_at, last_detected_at) "
                        + "values (?, ?, ?, 'ERROR', 'PRICE', 'RECORD', 'RECORD', ?, ?, ?, ?)",
                batchId, issueCode, signature, occurrences, samples, OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private ImportDefinition definition(String prefix) {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(prefix + "-ORG", prefix + "-ORG BV", SourceOrganisationType.SUPPLIER));
        return definitions.saveAndFlush(
                new ImportDefinition(organisation, prefix + "-DEF", prefix + " catalogus", "beheerder@example.test"));
    }

    private ImportDefinitionRevision newRevision(ImportDefinition definition, int revisionNumber) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, revisionNumber,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("LEV_GROEP");
        revision.setIdentitySupplierReferenceField("LEV_REFERENTIE");
        revision.setStructureDelimiter(";");
        return revision;
    }

    /** Volledige keten tot en met levering, zodat batches aangemaakt kunnen worden (patroon ScreeningSchemaTest). */
    private Scenario scenario(String prefix) {
        ImportDefinition definition = definition(prefix);
        ImportDefinitionRevision revision = revisions.saveAndFlush(newRevision(definition, 1));
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(prefix + "-SUP", prefix + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(prefix + "-LINK", prefix + "-LINK koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, prefix + "-taak", TaskTriggerType.MANUAL));
        Delivery delivery = deliveries.saveAndFlush(new Delivery(task, "manual:" + prefix, Instant.now()));
        return new Scenario(revision, link, delivery);
    }

    private final class Scenario {
        private final ImportDefinitionRevision revision;
        private final ImportLink link;
        private final Delivery delivery;

        private Scenario(ImportDefinitionRevision revision, ImportLink link, Delivery delivery) {
            this.revision = revision;
            this.link = link;
            this.delivery = delivery;
        }

        ImportDefinitionRevision revision() {
            return revision;
        }

        ImportLink link() {
            return link;
        }

        ImportBatch newBatch(int attemptNo) {
            return new ImportBatch(delivery, link, revision, attemptNo, "tester@example.test");
        }
    }
}
