package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dat deze context start bewijst al twee dingen: Liquibase migreert het volledige schema en Hibernate
 * ({@code ddl-auto: validate}) aanvaardt alle entiteiten daartegen. Daarnaast: elk in de master opgenomen
 * changelogbestand heeft minstens één toegepaste changeset in {@code databasechangelog}.
 * <p>
 * Bewust geen hard getal van changesets: dat zou bij elke nieuwe changeset breken. Het aantal bestanden volgt
 * uit de master zelf.
 */
@DaoTest
class LiquibaseSchemaValidationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyIncludedChangelogFileHasAppliedChangesets() throws IOException {
        String master = new ClassPathResource("db/changelog/db.changelog-master.yaml")
                .getContentAsString(StandardCharsets.UTF_8);
        List<String> files = master.lines().map(String::strip).filter(line -> line.startsWith("file:"))
                .map(line -> line.substring("file:".length()).strip()).toList();
        assertThat(files).isNotEmpty();

        List<String> applied = jdbc.queryForList("select distinct filename from databasechangelog", String.class);
        for (String file : files) {
            assertThat(applied).as("toegepaste changesets voor " + file)
                    .anyMatch(filename -> filename.endsWith(file));
        }
    }

    @Test
    void hibernateValidatedTheSchemaAndTheCoreTablesExist() {
        for (String table : List.of("import_batch", "import_mutation", "import_issue_group", "publication_run")) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
                    + "where table_schema = current_schema() and table_name = ?", Long.class, table))
                    .as(table).isEqualTo(1L);
        }
    }
}
