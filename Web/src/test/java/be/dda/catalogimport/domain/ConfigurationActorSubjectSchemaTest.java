package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bouwstap 5A-6, changeset {@code 007-4-configuration-subject} (docs/design/fase5-auth-design.md par. 4
 * en G1; docs/decisions.md 2026-09-25 "Fase 5-AUTH: ontwerp bindend"): bewijst dat de tien
 * subjectkolommen op de acht configuratietabellen migreren, dat Hibernate de uitgebreide entiteiten met
 * {@code ddl-auto: validate} aanvaardt (het booten van deze context ís dat bewijs) en dat de zes
 * koppelchecks werkelijk afdwingen wat ze beloven.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> {@code NULL} betekent overal exact hetzelfde — "geen geverifieerde identiteit".
 *       <b>Implementatie:</b> elke kolom die uit een geverifieerde requestactor gevuld wordt, heeft een
 *       subjectkolom; geen backfill, geen default.</li>
 *   <li><b>Regel:</b> een handtekening zonder ondertekenaar bestaat niet. <b>Implementatie:</b> bij een
 *       nullable {@code *_by} een koppelcheck {@code (x_by_subject is null or x_by is not null)}.</li>
 *   <li><b>Data:</b> {@code varchar(255)} (bovengrens van {@code sub}, OIDC Core par. 2), nullable, geen
 *       index — audit-only, nooit een zoeksleutel.</li>
 * </ul>
 * Codes krijgen een per-run uniek achtervoegsel: het {@code local}-profiel draait tegen een blijvende
 * PostgreSQL (zelfde patroon als {@link ImportTemplateBookmarkSchemaTest}).
 */
@SpringBootTest
@ActiveProfiles("local")
class ConfigurationActorSubjectSchemaTest {

    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
    private static final String USER = "beheerder@example.test";
    private static final String SUBJECT = "keycloak-sub-5a6";

    /** De tien kolommen van changeset 007-4 (ontwerp par. 1.2 en par. 4, gevolg G1). */
    private static final List<String> SUBJECT_COLUMNS = List.of(
            "import_definition.created_by_subject",
            "import_definition_revision.created_by_subject",
            "import_definition_revision.approved_by_subject",
            "import_field_mapping.created_by_subject",
            "import_record_filter.created_by_subject",
            "import_revision_field_criticality.created_by_subject",
            "import_definition_bookmark.created_by_subject",
            "import_definition_bookmark_value.filled_by_subject",
            "import_link_bookmark_value.filled_by_subject",
            "import_link_bookmark_value.updated_by_subject");

    /** De zes koppelchecks: precies de nullable {@code *_by}-kolommen, niet meer en niet minder. */
    private static final List<String> LINKING_CHECKS = List.of(
            "ck_import_definition_revision_approved_subject",
            "ck_import_field_mapping_created_subject",
            "ck_import_record_filter_created_subject",
            "ck_import_revision_field_criticality_subject",
            "ck_import_definition_bookmark_created_subject",
            "ck_import_link_bookmark_value_updated_subject");

    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Vorm van de kolommen ----------------------------------------------------------------------

    @Test
    void everySubjectColumnIsANullableVarchar255WithoutADefault() {
        for (String qualified : SUBJECT_COLUMNS) {
            String table = qualified.substring(0, qualified.indexOf('.'));
            String column = qualified.substring(qualified.indexOf('.') + 1);

            Map<String, Object> row = jdbc.queryForMap(
                    "select data_type, character_maximum_length, is_nullable, column_default "
                            + "from information_schema.columns "
                            + "where table_schema = current_schema() and table_name = ? and column_name = ?",
                    table, column);

            assertThat((String) row.get("data_type")).as(qualified)
                    .isEqualToIgnoringCase("character varying");
            assertThat(((Number) row.get("character_maximum_length")).intValue()).as(qualified).isEqualTo(255);
            assertThat((String) row.get("is_nullable")).as(qualified).isEqualToIgnoringCase("YES");
            // Geen default: een subject wordt nooit stil ingevuld.
            assertThat(row.get("column_default")).as(qualified).isNull();
        }
    }

    /** Tel ze expliciet: het ontwerp belooft 18 kolommen op 12 tabellen, waarvan 10 op 8 hier. */
    @Test
    void changeset0074AddsTenSubjectColumnsOnEightConfigurationTables() {
        assertThat(SUBJECT_COLUMNS).hasSize(10);
        assertThat(SUBJECT_COLUMNS.stream().map(name -> name.substring(0, name.indexOf('.'))).distinct())
                .hasSize(8);

        // Samen met 007-1..007-3 (bundel, bundellidmaatschap, beslissing, batch): 18 op 12; plus
        // publication_run.requested_by_subject (009-1, 5P-6): 19 op 13; plus
        // issue_case.status_changed_by_subject en issue_case_event.changed_by_subject (012-issue-case, S2-B1): 21 op 15;
        // plus external_credential.{secret_updated,created,revoked}_by_subject en
        // external_credential_event.changed_by_subject (013-external-credential, K-2a): 25 op 17;
        // plus created_by_subject op connection_profile, connection_profile_version, delivery_configuration en
        // delivery_configuration_version, en changed_by_subject op acquisition_config_event
        // (014-delivery-configuration, LC-1): 30 op 22.
        Long total = jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where table_schema = current_schema() and column_name like '%\\_by\\_subject'", Long.class);
        Long tables = jdbc.queryForObject("select count(distinct table_name) from information_schema.columns "
                + "where table_schema = current_schema() and column_name like '%\\_by\\_subject'", Long.class);
        assertThat(total).isEqualTo(30L);
        assertThat(tables).isEqualTo(22L);
    }

    @Test
    void everyNullableActorColumnHasItsLinkingCheck() {
        for (String constraint : LINKING_CHECKS) {
            Long found = jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                            + "where constraint_schema = current_schema() and constraint_type = 'CHECK' "
                            + "and constraint_name = ?", Long.class, constraint);
            assertThat(found).as(constraint).isEqualTo(1L);
        }
    }

    /** Een subjectkolom mag nooit een zoeksleutel worden: audit-only, dus geen index (ontwerp par. 4). */
    @Test
    void noSubjectColumnIsIndexed() {
        List<String> indexed = jdbc.queryForList("select indexname from pg_indexes "
                + "where schemaname = current_schema() and indexdef like '%\\_by\\_subject%'", String.class);

        assertThat(indexed).isEmpty();
    }

    // --- Gedrag van de koppelchecks -----------------------------------------------------------------

    @Test
    void refusesAnApprovedSubjectOnARevisionThatWasNeverApproved() {
        long revisionId = revision("APPR");

        assertThatThrownBy(() -> jdbc.update("update import_definition_revision "
                        + "set approved_by_subject = ? where id = ?", SUBJECT, revisionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Met de naam erbij mag het wel: naam en subject horen bij dezelfde handtekening.
        assertThatCode(() -> jdbc.update("update import_definition_revision "
                        + "set approved_by = ?, approved_at = ?, approved_by_subject = ? where id = ?",
                USER, OffsetDateTime.now(), SUBJECT, revisionId))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select approved_by_subject from import_definition_revision where id = ?",
                String.class, revisionId)).isEqualTo(SUBJECT);
    }

    /** {@code created_by} is NOT NULL op definitie en revisie: daar staat het subject er los naast. */
    @Test
    void storesTheCreatedSubjectOnADefinitionAndRevisionAndLeavesItNullWhenAbsent() {
        long revisionId = revision("CRE");
        long definitionId = jdbc.queryForObject("select import_definition_id from import_definition_revision "
                + "where id = ?", Long.class, revisionId);

        jdbc.update("update import_definition set created_by_subject = ? where id = ?", SUBJECT, definitionId);
        jdbc.update("update import_definition_revision set created_by_subject = ? where id = ?", SUBJECT,
                revisionId);

        assertThat(jdbc.queryForObject("select created_by_subject from import_definition where id = ?",
                String.class, definitionId)).isEqualTo(SUBJECT);
        assertThat(jdbc.queryForObject("select created_by_subject from import_definition_revision where id = ?",
                String.class, revisionId)).isEqualTo(SUBJECT);

        // Een tweede, niet-geverifieerde revisie houdt NULL: nooit afgeleid uit de naam.
        long unverified = revision("CRE2");
        assertThat(jdbc.queryForObject("select created_by_subject from import_definition_revision where id = ?",
                String.class, unverified)).isNull();
    }

    @Test
    void refusesACreatedSubjectWithoutACreatedByOnAFilter() {
        long revisionId = revision("FILT");

        assertThatThrownBy(() -> insertFilter(revisionId, 1, null, SUBJECT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertFilter(revisionId, 2, USER, SUBJECT)).doesNotThrowAnyException();
        // Zonder login: naam zonder subject blijft geldig (DemoDataSeeder, Service-test).
        assertThatCode(() -> insertFilter(revisionId, 3, USER, null)).doesNotThrowAnyException();
    }

    @Test
    void refusesACreatedSubjectWithoutACreatedByOnAFieldCriticality() {
        long revisionId = revision("CRIT");

        assertThatThrownBy(() -> insertCriticality(revisionId, "DESCRIPTION", null, SUBJECT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertCriticality(revisionId, "DESCRIPTION", USER, SUBJECT))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesACreatedSubjectWithoutACreatedByOnABookmarkDeclaration() {
        long revisionId = revision("BMK");

        assertThatThrownBy(() -> insertBookmark(revisionId, "CULTUUR", 1, null, SUBJECT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertBookmark(revisionId, "CULTUUR", 2, USER, SUBJECT))
                .doesNotThrowAnyException();
    }

    /**
     * {@code filled_by} is NOT NULL, {@code updated_by} niet: het subject van de eerste invuller staat
     * er los naast, dat van de wijziging hangt aan de naam van de wijziging.
     */
    @Test
    void keepsTheFilledSubjectAndRefusesAnUpdatedSubjectWithoutAnUpdatedBy() {
        long linkId = link("LBV");
        jdbc.update("insert into import_link_bookmark_value (import_link_id, bookmark_name, data_type, "
                        + "value_text, filled_at, filled_by, filled_by_subject) values (?, ?, ?, ?, ?, ?, ?)",
                linkId, "CULTUUR", "TEXT", "NL", OffsetDateTime.now(), USER, SUBJECT);

        assertThat(jdbc.queryForObject("select filled_by_subject from import_link_bookmark_value "
                + "where import_link_id = ?", String.class, linkId)).isEqualTo(SUBJECT);

        assertThatThrownBy(() -> jdbc.update("update import_link_bookmark_value "
                        + "set updated_by_subject = ? where import_link_id = ?", "sub-zonder-naam", linkId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Met naam en tijdstip erbij (ck_import_link_bookmark_value_updated blijft ook gelden) mag het.
        assertThatCode(() -> jdbc.update("update import_link_bookmark_value set value_text = ?, "
                        + "previous_value_text = ?, updated_at = ?, updated_by = ?, updated_by_subject = ? "
                        + "where import_link_id = ?",
                "FR", "NL", OffsetDateTime.now(), "tweede@example.test", "sub-tweede", linkId))
                .doesNotThrowAnyException();
        Map<String, Object> row = jdbc.queryForMap("select filled_by_subject, updated_by_subject "
                + "from import_link_bookmark_value where import_link_id = ?", linkId);
        assertThat(row).containsEntry("filled_by_subject", SUBJECT)
                .containsEntry("updated_by_subject", "sub-tweede");
    }

    @Test
    void storesTheFilledSubjectOnADefinitionScopeBookmarkValue() {
        long revisionId = revision("DBV");
        jdbc.update("insert into import_definition_bookmark_value (definition_revision_id, bookmark_name, "
                        + "data_type, value_text, filled_at, filled_by, filled_by_subject) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                revisionId, "CULTUUR", "TEXT", "NL", OffsetDateTime.now(), USER, SUBJECT);
        jdbc.update("insert into import_definition_bookmark_value (definition_revision_id, bookmark_name, "
                        + "data_type, value_text, filled_at, filled_by) values (?, ?, ?, ?, ?, ?)",
                revisionId, "ZONDER_LOGIN", "TEXT", "FR", OffsetDateTime.now(), USER);

        assertThat(jdbc.queryForObject("select filled_by_subject from import_definition_bookmark_value "
                        + "where definition_revision_id = ? and bookmark_name = ?", String.class, revisionId,
                "CULTUUR")).isEqualTo(SUBJECT);
        assertThat(jdbc.queryForObject("select filled_by_subject from import_definition_bookmark_value "
                        + "where definition_revision_id = ? and bookmark_name = ?", String.class, revisionId,
                "ZONDER_LOGIN")).isNull();
    }

    /** Een te lang subject past niet: de Web-laag weigert het al met 403 {@code ACTOR_IDENTITY_INVALID}. */
    @Test
    void refusesASubjectLongerThanTheColumn() {
        long revisionId = revision("LONG");

        assertThatThrownBy(() -> jdbc.update("update import_definition_revision "
                        + "set created_by_subject = ? where id = ?", "x".repeat(256), revisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Helpers -------------------------------------------------------------------------------------

    private static String code(String base) {
        return base + "-" + RUN;
    }

    private void insertFilter(long revisionId, int sequenceNumber, String createdBy, String subject) {
        jdbc.update("insert into import_record_filter (definition_revision_id, sequence_number, "
                        + "source_reference, operator, compare_value, outcome, created_at, created_by, "
                        + "created_by_subject) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                revisionId, sequenceNumber, "groep", "EQUALS", "MEET", "EXCLUDE", OffsetDateTime.now(),
                createdBy, subject);
    }

    private void insertCriticality(long revisionId, String fieldKey, String createdBy, String subject) {
        jdbc.update("insert into import_revision_field_criticality (definition_revision_id, field_key, "
                        + "criticality, created_at, created_by, created_by_subject) values (?, ?, ?, ?, ?, ?)",
                revisionId, fieldKey, "CRITICAL", OffsetDateTime.now(), createdBy, subject);
    }

    private void insertBookmark(long revisionId, String name, int sortOrder, String createdBy, String subject) {
        jdbc.update("insert into import_definition_bookmark (definition_revision_id, name, label, data_type, "
                        + "value_scope, owner_role, required, sort_order, created_at, created_by, "
                        + "created_by_subject) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                revisionId, name, name + " label", "TEXT", "LINK", "catalogImport.manage", true, sortOrder,
                OffsetDateTime.now(), createdBy, subject);
    }

    /** Een gewone definitie met één {@code DRAFT}-revisie; geeft het revisie-id terug. */
    private long revision(String prefix) {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(new SourceOrganisation(
                code("AS" + prefix), prefix + " leverancier", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation,
                code("AS" + prefix + "-DEF"), prefix + " catalogus", USER));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
                IdentityProfileKind.THREE_PART, USER);
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("LEV_GROEP");
        revision.setIdentitySupplierReferenceField("LEV_REFERENTIE");
        revision.setStructureDelimiter(";");
        return revisions.saveAndFlush(revision).getId();
    }

    /** Een importkoppeling op een verse definitie; geeft het koppeling-id terug. */
    private long link(String prefix) {
        long revisionId = revision(prefix);
        Long definitionId = jdbc.queryForObject("select import_definition_id from import_definition_revision "
                + "where id = ?", Long.class, revisionId);
        ImportDefinition definition = definitions.findById(definitionId).orElseThrow();
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(new SourceOrganisation(
                code("AS" + prefix + "-SUP"), prefix + " leverancier BV", SourceOrganisationType.SUPPLIER));
        return links.saveAndFlush(new ImportLink(code("AS" + prefix + "-LNK"), prefix + " koppeling",
                definition, supplier, "PSARF050")).getId();
    }
}
