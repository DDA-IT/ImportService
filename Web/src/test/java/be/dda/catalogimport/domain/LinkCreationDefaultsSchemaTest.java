package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Slice CD-B2, changeset 030 (beslissingslog 2026-10-23 "Aanmaakstandaard per koppeling: uitwerking (CD)" en "Vlag
 * 'standaarden overschrijven' op de importdefinitie"): de kolommen migreren additief, Hibernate aanvaardt de entiteiten
 * met {@code ddl-auto: validate}, de checks verbieden een halve audit, en een bestaande definitie (een rij zonder de
 * nieuwe kolom in de insert) krijgt de vlag uit. Codes zijn per test uniek omdat de database gedeeld is.
 */
@SpringBootTest
@ActiveProfiles("local")
class LinkCreationDefaultsSchemaTest {

    @Autowired
    private SourceOrganisationRepository organisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Schema ----------------------------------------------------------------------------------------------------

    @Test
    void theNewColumnsAreNullableWithTheDocumentedLengthsAndTheFlagIsNotNullDefaultFalse() {
        assertThat(length("import_link", "default_vat_code")).isEqualTo(50);
        assertThat(length("import_link", "default_unit_code")).isEqualTo(50);
        assertThat(length("import_link", "creation_defaults_origin")).isEqualTo(20);
        assertThat(length("import_link", "creation_defaults_updated_by")).isEqualTo(100);
        assertThat(length("import_link", "creation_defaults_updated_by_subject")).isEqualTo(255);
        for (String column : new String[] {"default_vat_code", "default_unit_code", "creation_defaults_origin",
                "creation_defaults_updated_at", "creation_defaults_updated_by", "creation_defaults_updated_by_subject"}) {
            assertThat(nullable("import_link", column)).as(column).isEqualTo("YES");
        }
        assertThat(dataType("import_link", "creation_defaults_updated_at")).isEqualTo("timestamp with time zone");

        assertThat(nullable("import_definition", "overwrite_creation_defaults")).isEqualTo("NO");
        assertThat(dataType("import_definition", "overwrite_creation_defaults")).isEqualTo("boolean");
        assertThat(length("import_definition", "overwrite_creation_defaults_changed_by")).isEqualTo(100);
        assertThat(length("import_definition", "overwrite_creation_defaults_changed_by_subject")).isEqualTo(255);
        assertThat(dataType("import_definition", "overwrite_creation_defaults_changed_at"))
                .isEqualTo("timestamp with time zone");
    }

    @Test
    void anExistingDefinitionRowGetsTheFlagOffAndAnExistingLinkHasNoCreationDefaults() {
        String prefix = prefix("CDOLD");
        long organisationId = organisations.saveAndFlush(new SourceOrganisation(prefix + "-ORG", prefix,
                SourceOrganisationType.SUPPLIER)).getId();
        // Zoals een rij van vóór 030: de insert noemt de nieuwe kolommen niet.
        Long definitionId = jdbc.queryForObject("insert into import_definition (source_organisation_id, code, "
                        + "description, usage_type, created_at, created_by, updated_at) "
                        + "values (?, ?, ?, 'OWN_DEFINITION', ?, 'beheerder@example.test', ?) returning id", Long.class,
                organisationId, prefix + "-DEF", prefix, OffsetDateTime.now(), OffsetDateTime.now());
        Long linkId = jdbc.queryForObject("insert into import_link (code, name, import_definition_id, "
                        + "supplier_organisation_id, library_code, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'PSARF030', ?, ?) returning id", Long.class,
                prefix + "-LINK", prefix, definitionId, organisationId, OffsetDateTime.now(), OffsetDateTime.now());

        Map<String, Object> definition = jdbc.queryForMap("select overwrite_creation_defaults, "
                + "overwrite_creation_defaults_changed_at, overwrite_creation_defaults_changed_by, "
                + "overwrite_creation_defaults_changed_by_subject from import_definition where id = ?", definitionId);
        assertThat(definition.get("overwrite_creation_defaults")).isEqualTo(false);
        assertThat(definition.get("overwrite_creation_defaults_changed_at")).isNull();
        assertThat(definition.get("overwrite_creation_defaults_changed_by")).isNull();
        assertThat(definitions.findById(definitionId).orElseThrow().isOverwriteCreationDefaults()).isFalse();

        ImportLink link = links.findById(linkId).orElseThrow();
        assertThat(link.getDefaultVatCode()).isNull();
        assertThat(link.getDefaultUnitCode()).isNull();
        assertThat(link.getCreationDefaultsOrigin()).isNull();
        assertThat(link.getCreationDefaultsUpdatedAt()).isNull();
    }

    // --- import_link: geen halve audit -----------------------------------------------------------------------------

    @Test
    void aCodeWithoutOriginTimestampOrNameIsRefused() {
        long linkId = link("CDHALF").getId();
        OffsetDateTime now = OffsetDateTime.now();

        refused(linkId, "3", null, null, null, null, null);
        refused(linkId, null, "ST", "MANUAL", null, null, null);
        refused(linkId, "3", null, "MANUAL", now, null, null);
        refused(linkId, "3", null, null, now, "beheerder@example.test", null);
        // Tijdstip en naam samen of geen van beide, ook zonder code.
        refused(linkId, null, null, "MANUAL", now, null, null);
        refused(linkId, null, null, null, null, "beheerder@example.test", null);
        // Een herkomst zonder tijdstip en een subject zonder naam.
        refused(linkId, null, null, "MANUAL", null, null, null);
        refused(linkId, null, null, null, null, null, "sub-1");

        assertThat(jdbc.queryForMap("select default_vat_code, creation_defaults_origin from import_link where id = ?",
                linkId).values()).containsOnlyNulls();
    }

    @Test
    void anInvalidOriginOrABlankCodeIsRefused() {
        long linkId = link("CDORIG").getId();
        OffsetDateTime now = OffsetDateTime.now();

        refused(linkId, "3", "ST", "BOGUS", now, "beheerder@example.test", null);
        refused(linkId, "3", "ST", "manual", now, "beheerder@example.test", null);
        refused(linkId, " ", "ST", "MANUAL", now, "beheerder@example.test", null);
        refused(linkId, "3", "", "MANUAL", now, "beheerder@example.test", null);
    }

    @Test
    void aCompleteAuditIsAcceptedAlsoForAClearedDefault() {
        long linkId = link("CDFULL").getId();
        OffsetDateTime now = OffsetDateTime.now();

        for (String origin : new String[] {"LEGACY", "MANUAL"}) {
            accepted(linkId, "3", "ST", origin, now, "beheerder@example.test", "sub-1");
        }
        accepted(linkId, "3", null, "MANUAL", now, "beheerder@example.test", null);
        // Gewist: beide codes NULL, herkomst en audit blijven als verklaring van de wissing.
        accepted(linkId, null, null, "MANUAL", now, "beheerder@example.test", "sub-1");
        accepted(linkId, null, null, null, null, null, null);
    }

    // --- import_definition: geen halve audit -----------------------------------------------------------------------

    @Test
    void theDefinitionFlagRefusesAHalfAuditButAllowsAnInheritedFlagWithoutAudit() {
        long definitionId = link("CDDEF").getImportDefinition().getId();
        String update = "update import_definition set overwrite_creation_defaults = ?, "
                + "overwrite_creation_defaults_changed_at = ?, overwrite_creation_defaults_changed_by = ?, "
                + "overwrite_creation_defaults_changed_by_subject = ? where id = ?";
        OffsetDateTime now = OffsetDateTime.now();

        assertThatThrownBy(() -> jdbc.update(update, true, now, null, null, definitionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(update, true, null, "beheerder@example.test", null, definitionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(update, false, null, null, "sub-1", definitionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update import_definition set overwrite_creation_defaults = null "
                + "where id = ?", definitionId)).isInstanceOf(DataIntegrityViolationException.class);

        // G2a: een geërfde vlag zonder eigen wijziging heeft geen audit.
        assertThatCode(() -> jdbc.update(update, true, null, null, null, definitionId)).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update(update, false, now, "beheerder@example.test", "sub-1", definitionId))
                .doesNotThrowAnyException();
    }

    // --- Entiteiten ------------------------------------------------------------------------------------------------

    @Test
    void theEntitiesWriteAndReadTheCreationDefaultsAndTheFlagWithTheirAudit() {
        ImportLink link = link("CDRT");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        link.setCreationDefaults("3", "ST", CreationDefaultsOrigin.LEGACY, "beheerder@example.test", "sub-1", now);
        links.saveAndFlush(link);
        ImportLink found = links.findById(link.getId()).orElseThrow();
        assertThat(found.getDefaultVatCode()).isEqualTo("3");
        assertThat(found.getDefaultUnitCode()).isEqualTo("ST");
        assertThat(found.getCreationDefaultsOrigin()).isEqualTo(CreationDefaultsOrigin.LEGACY);
        assertThat(found.getCreationDefaultsUpdatedBy()).isEqualTo("beheerder@example.test");
        assertThat(found.getCreationDefaultsUpdatedBySubject()).isEqualTo("sub-1");
        assertThat(found.getCreationDefaultsUpdatedAt()).isEqualTo(now);
        assertThat(jdbc.queryForObject("select creation_defaults_origin from import_link where id = ?", String.class,
                link.getId())).isEqualTo("LEGACY");

        ImportDefinition definition = definitions.findById(link.getImportDefinition().getId()).orElseThrow();
        definition.setOverwriteCreationDefaults(true, "beheerder@example.test", null, now);
        definitions.saveAndFlush(definition);
        ImportDefinition again = definitions.findById(definition.getId()).orElseThrow();
        assertThat(again.isOverwriteCreationDefaults()).isTrue();
        assertThat(again.getOverwriteCreationDefaultsChangedBy()).isEqualTo("beheerder@example.test");
        assertThat(again.getOverwriteCreationDefaultsChangedBySubject()).isNull();
        assertThat(again.getOverwriteCreationDefaultsChangedAt()).isEqualTo(now);
    }

    @Test
    void theDomainMethodsRefuseAMissingSignerOrTimestamp() {
        ImportLink link = link("CDDOM");
        Instant now = Instant.now();

        assertThatThrownBy(() -> link.setCreationDefaults("3", "ST", null, "x", null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> link.setCreationDefaults("3", "ST", CreationDefaultsOrigin.MANUAL, " ", null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> link.setCreationDefaults("3", "ST", CreationDefaultsOrigin.MANUAL, "x", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> link.getImportDefinition().setOverwriteCreationDefaults(true, null, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(link.getDefaultVatCode()).isNull();
        assertThat(link.getCreationDefaultsOrigin()).isNull();
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private void refused(long linkId, String vat, String unit, String origin, OffsetDateTime at, String by,
                         String subject) {
        assertThatThrownBy(() -> update(linkId, vat, unit, origin, at, by, subject))
                .as("vat=%s unit=%s origin=%s at=%s by=%s subject=%s", vat, unit, origin, at, by, subject)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void accepted(long linkId, String vat, String unit, String origin, OffsetDateTime at, String by,
                          String subject) {
        assertThatCode(() -> update(linkId, vat, unit, origin, at, by, subject))
                .as("vat=%s unit=%s origin=%s", vat, unit, origin).doesNotThrowAnyException();
    }

    private void update(long linkId, String vat, String unit, String origin, OffsetDateTime at, String by,
                        String subject) {
        jdbc.update("update import_link set default_vat_code = ?, default_unit_code = ?, creation_defaults_origin = ?, "
                + "creation_defaults_updated_at = ?, creation_defaults_updated_by = ?, "
                + "creation_defaults_updated_by_subject = ? where id = ?", vat, unit, origin, at, by, subject, linkId);
    }

    private ImportLink link(String name) {
        String prefix = prefix(name);
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(prefix + "-ORG", prefix + " BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, prefix + "-DEF", prefix + " catalogus", "beheerder@example.test"));
        return links.saveAndFlush(new ImportLink(prefix + "-LINK", prefix + " koppeling", definition, organisation,
                "PSARF030"));
    }

    private static String prefix(String name) {
        return name + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String columnQuery(String selected) {
        return "select " + selected + " from information_schema.columns where table_schema = current_schema() "
                + "and lower(table_name) = ? and lower(column_name) = ?";
    }

    private Integer length(String table, String column) {
        Map<String, Object> row = jdbc.queryForMap(columnQuery("character_maximum_length as len"), table, column);
        return ((Number) row.get("len")).intValue();
    }

    private String nullable(String table, String column) {
        return jdbc.queryForObject(columnQuery("is_nullable"), String.class, table, column);
    }

    private String dataType(String table, String column) {
        return jdbc.queryForObject(columnQuery("data_type"), String.class, table, column);
    }
}
