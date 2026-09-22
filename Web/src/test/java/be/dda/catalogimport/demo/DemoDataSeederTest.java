package be.dda.catalogimport.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Het {@code demo}-profiel maakt bij het opstarten precies één voorbeeldketen aan, en een tweede
 * start voegt daar niets aan toe.
 * <p>
 * Idempotentie is hier geen detail: met een persistente database zou een seeder die blind aanmaakt bij
 * elke herstart een tweede definitie, koppeling en taak opleveren, en dan weet niemand meer welke
 * {@code taskId} de juiste is. De seeder draait in deze test een tweede keer met exact dezelfde
 * aanroep als bij het opstarten.
 */
@SpringBootTest
@ActiveProfiles({"local", "demo"})
class DemoDataSeederTest {

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void startingTwiceLeavesExactlyOneChain() {
        assertThat(chainCounts()).containsExactly(1L, 1L, 1L, 1L, 1L);

        // Exact dezelfde aanroep als bij het opstarten van de applicatie.
        seeder.run(null);

        assertThat(chainCounts()).containsExactly(1L, 1L, 1L, 1L, 1L);
    }

    @Test
    void theSeededChainIsUsable() {
        assertThat(jdbc.queryForObject("select status from import_definition_revision r "
                + "join import_definition d on d.id = r.import_definition_id where d.code = ?",
                String.class, DemoDataSeeder.DEFINITION_CODE)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select trigger_type from catalog_import_task where name = ?",
                String.class, DemoDataSeeder.TASK_NAME)).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("select count(*) from import_field_mapping m "
                + "join import_definition_revision r on r.id = m.definition_revision_id "
                + "join import_definition d on d.id = r.import_definition_id where d.code = ?",
                Long.class, DemoDataSeeder.DEFINITION_CODE)).isEqualTo(1L);
    }

    /** De vijf rijen van de keten: organisatie, definitie, revisie, koppeling en taak. */
    private java.util.List<Long> chainCounts() {
        return java.util.List.of(
                count("select count(*) from source_organisation where code = ?",
                        DemoDataSeeder.ORGANISATION_CODE),
                count("select count(*) from import_definition where code = ?", DemoDataSeeder.DEFINITION_CODE),
                count("select count(*) from import_definition_revision r join import_definition d "
                        + "on d.id = r.import_definition_id where d.code = ?", DemoDataSeeder.DEFINITION_CODE),
                count("select count(*) from import_link where code = ?", DemoDataSeeder.LINK_CODE),
                count("select count(*) from catalog_import_task where name = ?", DemoDataSeeder.TASK_NAME));
    }

    private long count(String sql, Object argument) {
        Long count = jdbc.queryForObject(sql, Long.class, argument);
        return count == null ? 0L : count;
    }
}
