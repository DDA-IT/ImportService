package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ProdisReferenceJdbc;
import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;

/**
 * Keuzelijsten uit de alleen-lezen Prodis-database en de grens met de hoofddatabase (legacy-config S5;
 * {@code docs/design/legacy-config-bundle-design.md} par. 6 en 10). Over HTTP, met de H2-testbron.
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Drie keuzelijsten, enkel met MANAGE (403 vóór alles), met businesscodes en zonder Prodis-id's.</li>
 *   <li>Onbekende leverancier 404; onbruikbare bron 503 {@code PRODIS_DB_UNAVAILABLE}, nooit een lege lijst.</li>
 *   <li>De tweede bron laat de hoofd-DataSource, JPA, Liquibase en de {@code JdbcTemplate} ongemoeid, en de
 *       readiness-probe blijft UP als Prodis weg is.</li>
 * </ul>
 */
class ProdisReferenceHttpTest extends LegacyConfigProdisTestBase {

    @Autowired
    ApplicationContext context;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void theReferenceListsNeedManageBeforeAnythingElse() throws Exception {
        for (String path : List.of("/suppliers?q=x", "/suppliers/NOPE/groups", "/suppliers/NOPE/catalogs")) {
            mockMvc.perform(get(PRODIS_REFERENCE + path).with(as(USER, Permission.READ)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    @Test
    void suppliersGroupsAndLibrariesAreListedAsBusinessCodes() throws Exception {
        String prefix = "H" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        long first = PRODIS.insertSupplier(prefix + "1", "Http Leverancier Een");
        PRODIS.insertSupplier(prefix + "2", "Http Leverancier Twee");
        PRODIS.insertGroup(first, prefix + "1|10|A", "10", "A");
        PRODIS.insertCatalog(first, "106", "106", "OUD", "SUPERSEDED");
        PRODIS.insertCatalog(first, "106", "106", "HUIDIG", "ACTIVE");
        PRODIS.insertCatalog(first, "7", "7", "ZEVEN", "IMPORTED");

        String suppliers = list("/suppliers?q=" + prefix);
        assertThat((List<String>) JsonPath.read(suppliers, "$[*].number")).containsExactly(prefix + "1", prefix + "2");
        assertThat((List<String>) JsonPath.read(suppliers, "$[*].name"))
                .containsExactly("Http Leverancier Een", "Http Leverancier Twee");
        assertThat(suppliers).doesNotContain("\"id\"");

        String groups = list("/suppliers/" + prefix + "1/groups");
        assertThat((List<String>) JsonPath.read(groups, "$[*].legacyCode")).containsExactly(prefix + "1|10|A");
        assertThat((List<String>) JsonPath.read(groups, "$[*].discountCode")).containsExactly("A");

        String catalogs = list("/suppliers/" + prefix + "1/catalogs");
        assertThat((List<String>) JsonPath.read(catalogs, "$[*].scopeKey")).containsExactly("106", "7");
        assertThat((List<String>) JsonPath.read(catalogs, "$[*].catalogName")).containsExactly("HUIDIG", "ZEVEN");
        assertThat(catalogs).doesNotContain("\"id\"");

        assertThat(list("/suppliers/" + prefix + "2/catalogs")).isEqualTo("[]");
        for (String path : List.of("/suppliers/" + prefix + "9/groups", "/suppliers/" + prefix + "9/catalogs")) {
            mockMvc.perform(get(PRODIS_REFERENCE + path).with(as(USER, Permission.MANAGE)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRODIS_SUPPLIER_NOT_FOUND"));
        }
        mockMvc.perform(get(PRODIS_REFERENCE + "/suppliers").param("q", "x".repeat(101))
                        .with(as(USER, Permission.MANAGE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODIS_REFERENCE_QUERY_INVALID"));
    }

    @Test
    void anUnusableSourceIs503AndTheApplicationStaysReady() throws Exception {
        String number = "U" + UUID.randomUUID().toString().substring(0, 6);
        PRODIS.insertSupplier(number, "Onbereikbaar");
        PRODIS.breakSource();
        try {
            for (String path : List.of("/suppliers?q=" + number, "/suppliers/" + number + "/groups",
                    "/suppliers/" + number + "/catalogs")) {
                mockMvc.perform(get(PRODIS_REFERENCE + path).with(as(USER, Permission.MANAGE)))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("PRODIS_DB_UNAVAILABLE"))
                        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("jdbc:"))));
            }
            mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        } finally {
            PRODIS.restore();
        }
        assertThat(list("/suppliers?q=" + number)).contains("Onbereikbaar");
    }

    /**
     * Ontwerp par. 10: een tweede bean van type DataSource/JdbcTemplate zou de autoconfiguratie van de hoofddatabase
     * laten terugtreden. Hier: precies één DataSource (de hoofddatabase), en JPA, Liquibase en de JdbcTemplate's
     * gebruiken die; de Prodis-bron bestaat enkel als {@link ProdisReferenceJdbc}.
     */
    @Test
    void theMainDataSourceJpaAndLiquibaseAreUntouched() {
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        DataSource main = context.getBean(DataSource.class);
        assertThat(main).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) main).getJdbcUrl()).startsWith("jdbc:postgresql:").contains("catalog_import");
        assertThat(((HikariDataSource) main).isReadOnly()).isFalse();

        assertThat(context.getBeansOfType(JdbcTemplate.class)).hasSize(1);
        assertThat(context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(main);
        assertThat(context.getBeansOfType(NamedParameterJdbcTemplate.class)).hasSize(1);
        assertThat(context.getBean(NamedParameterJdbcTemplate.class).getJdbcTemplate().getDataSource())
                .isSameAs(main);
        assertThat(((EntityManagerFactoryInfo) entityManagerFactory).getDataSource()).isSameAs(main);
        assertThat(context.getBean(SpringLiquibase.class).getDataSource()).isSameAs(main);

        assertThat(context.getBeansOfType(ProdisReferenceJdbc.class)).hasSize(1);
        assertThat(context.getBean(ProdisReferenceJdbc.class).isConfigured()).isTrue();
    }

    private String list(String path) throws Exception {
        return mockMvc.perform(get(PRODIS_REFERENCE + path).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
