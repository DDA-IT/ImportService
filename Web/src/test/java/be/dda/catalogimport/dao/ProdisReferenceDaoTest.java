package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.config.ProdisReadOnlyDatabaseConfiguration;
import be.dda.catalogimport.config.ProdisReferenceDatabaseProperties;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierCatalogRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierGroupRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierRow;
import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.NotFoundException;
import be.dda.catalogimport.service.ProdisReferenceNotConfiguredException;
import be.dda.catalogimport.service.ProdisReferenceService;
import be.dda.catalogimport.service.ProdisReferenceService.Availability;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyLookup;
import be.dda.catalogimport.service.ProdisReferenceUnavailableException;
import be.dda.catalogimport.testsupport.ProdisReferenceTestDatabase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * {@link ProdisReferenceDao} en {@link ProdisReferenceService} tegen de H2-testbron met de drie Prodis-tabellen
 * (legacy-config S5, {@code docs/design/legacy-config-bundle-design.md} par. 6). Zonder Spring-context; de pool komt uit
 * {@link ProdisReadOnlyDatabaseConfiguration} (dezelfde code als in productie).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Leverancier op exact nummer; een dubbel nummer (geen unieke constraint in Prodis) geeft 2 rijen, nooit stil één.</li>
 *   <li>Zoeken: nummerprefix of naam (hoofdletterongevoelig), LIKE-tekens letterlijk, hoogstens 50.</li>
 *   <li>Groepen en bibliotheken enkel van die leverancier; een bibliotheek is een {@code scope_key} (ACTIVE-rij voorkeur).</li>
 *   <li>Niet geconfigureerd: 409 voor keuzelijsten, {@code NOT_CONFIGURED} voor PREVIEW; een fout: 503 / {@code UNAVAILABLE}.</li>
 * </ul>
 */
class ProdisReferenceDaoTest {

    private static final ProdisReferenceTestDatabase PRODIS =
            ProdisReferenceTestDatabase.named("prodis-dao-" + UUID.randomUUID());
    private static ProdisReferenceJdbc jdbc;
    private static ProdisReferenceDao dao;
    private static ProdisReferenceService service;

    private static long acme;

    @BeforeAll
    static void setUp() {
        ProdisReferenceDatabaseProperties properties = new ProdisReferenceDatabaseProperties();
        properties.setUrl(PRODIS.url());
        properties.setUsername(ProdisReferenceTestDatabase.USERNAME);
        properties.setPassword(ProdisReferenceTestDatabase.PASSWORD);
        jdbc = new ProdisReadOnlyDatabaseConfiguration().prodisReferenceJdbc(properties);
        dao = new ProdisReferenceDao(provider(jdbc));
        service = new ProdisReferenceService(dao);

        acme = PRODIS.insertSupplier("D0001", "Acme Tools");
        long other = PRODIS.insertSupplier("D0002", "Other_Name 100%");
        PRODIS.insertSupplier("D0003", "acme distribution");
        PRODIS.insertSupplier("DDUP", "First");
        PRODIS.insertSupplier("DDUP", "Second");
        PRODIS.insertGroup(acme, "D0001|20|A", "20", "A");
        PRODIS.insertGroup(acme, "D0001|10|", "10", "");
        PRODIS.insertGroup(other, "D0002|10|", "10", "");
        PRODIS.insertCatalog(acme, "106", "106", "ACME OLD", "SUPERSEDED");
        PRODIS.insertCatalog(acme, "106", "106", "ACME", "ACTIVE");
        PRODIS.insertCatalog(acme, "107", "106", "ACME PROMO", "IMPORTED");
        PRODIS.insertCatalog(acme, "2", "2", "ACME TWO", "ACTIVE");
        PRODIS.insertCatalog(other, "106", "106", "OTHER", "ACTIVE");
    }

    @AfterAll
    static void tearDown() throws Exception {
        jdbc.close();
    }

    @Test
    void aSupplierIsFoundByItsExactNumberAndADuplicateNumberIsNeverReducedToOne() {
        assertThat(dao.isConfigured()).isTrue();
        assertThat(dao.findSupplier("D0001")).containsExactly(new SupplierRow("D0001", "Acme Tools"));
        assertThat(dao.findSupplier("D000")).isEmpty();
        assertThat(dao.findSupplier("d0001")).isEmpty();
        assertThat(dao.findSupplier("DDUP")).extracting(SupplierRow::name).containsExactly("First", "Second");
    }

    @Test
    void searchMatchesANumberPrefixOrANameCaseInsensitivelyAndTreatsLikeCharactersLiterally() {
        assertThat(dao.searchSuppliers("D000")).extracting(SupplierRow::number)
                .containsExactly("D0001", "D0002", "D0003");
        assertThat(dao.searchSuppliers("ACME")).extracting(SupplierRow::number).containsExactly("D0001", "D0003");
        assertThat(dao.searchSuppliers("_")).extracting(SupplierRow::number).containsExactly("D0002");
        assertThat(dao.searchSuppliers("100%")).extracting(SupplierRow::number).containsExactly("D0002");
        assertThat(dao.searchSuppliers("%")).extracting(SupplierRow::number).containsExactly("D0002");
        assertThat(dao.searchSuppliers("no such supplier")).isEmpty();
        // Leeg = de eerste 50 op nummer (welke precies hangt af van de andere tests in deze database).
        assertThat(dao.searchSuppliers(" ")).isNotEmpty().hasSizeLessThanOrEqualTo(ProdisReferenceDao.SEARCH_LIMIT);
        assertThat(ProdisReferenceDao.escapeLike("a\\b%c_d")).isEqualTo("a\\\\b\\%c\\_d");
    }

    @Test
    void searchIsCappedAtFifty() {
        for (int i = 0; i < ProdisReferenceDao.SEARCH_LIMIT + 5; i++) {
            PRODIS.insertSupplier(String.format("CAP%03d", i), "Capped " + i);
        }
        assertThat(dao.searchSuppliers("CAP")).hasSize(ProdisReferenceDao.SEARCH_LIMIT)
                .extracting(SupplierRow::number).startsWith("CAP000", "CAP001");
    }

    @Test
    void groupsAndCatalogsBelongOnlyToThatSupplier() {
        assertThat(dao.findSupplierGroups("D0001")).containsExactly(
                new SupplierGroupRow("D0001|10|", "10", ""), new SupplierGroupRow("D0001|20|A", "20", "A"));
        assertThat(dao.findSupplierGroups("NOPE")).isEmpty();

        assertThat(dao.findSupplierCatalogs("D0001", null)).extracting(SupplierCatalogRow::scopeKey)
                .containsExactly("106", "106", "107", "2");
        // Binnen een scope_key de nieuwste rij eerst.
        assertThat(dao.findSupplierCatalogs("D0001", null).get(0).status()).isEqualTo("ACTIVE");
        assertThat(dao.findSupplierCatalogs("D0001", "106")).extracting(SupplierCatalogRow::scopeKey)
                .containsExactly("106", "106", "107");
        assertThat(dao.findSupplierCatalogs("D0001", "999")).isEmpty();
    }

    @Test
    void aLibraryIsOneScopeKeyPreferringItsActiveRow() {
        List<SupplierCatalogRow> libraries = ProdisReferenceService.libraries(List.of(
                new SupplierCatalogRow("106", "106", "NEWEST", "IMPORTED"),
                new SupplierCatalogRow("106", "106", "CURRENT", "ACTIVE"),
                new SupplierCatalogRow("106", "106", "OLD", "SUPERSEDED"),
                new SupplierCatalogRow("7", "7", "ONLY", "FAILED")));
        assertThat(libraries).containsExactly(new SupplierCatalogRow("106", "106", "CURRENT", "ACTIVE"),
                new SupplierCatalogRow("7", "7", "ONLY", "FAILED"));

        assertThat(service.supplierCatalogs("D0001")).extracting(SupplierCatalogRow::catalogName)
                .containsExactly("ACME", "ACME PROMO", "ACME TWO");
        assertThat(service.supplierGroups("D0001")).hasSize(2);
        assertThatThrownBy(() -> service.supplierCatalogs("NOPE")).isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo(ProdisReferenceService.CODE_SUPPLIER_NOT_FOUND);
        assertThatThrownBy(() -> service.supplierGroups(" ")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theLegacyLookupReturnsTheSupplierAndAllItsCatalogRows() {
        LegacyLookup lookup = service.lookup("D0001");
        assertThat(lookup.availability()).isEqualTo(Availability.VERIFIED);
        assertThat(lookup.suppliers()).containsExactly(new SupplierRow("D0001", "Acme Tools"));
        assertThat(lookup.catalogs()).hasSize(4);

        assertThat(service.lookup("NOPE")).isEqualTo(new LegacyLookup(Availability.VERIFIED, List.of(), List.of()));
        // Dubbel nummer: beide leveranciers, geen bibliotheken (die zouden niet eenduidig zijn).
        assertThat(service.lookup("DDUP").suppliers()).hasSize(2);
        assertThat(service.lookup("DDUP").catalogs()).isEmpty();
        assertThat(service.lookup(null).availability()).isEqualTo(Availability.VERIFIED);
    }

    @Test
    void withoutConfigurationTheFunctionIsOff() throws Exception {
        for (ProdisReferenceDao off : List.of(new ProdisReferenceDao(provider(null)),
                new ProdisReferenceDao(provider(ProdisReferenceJdbc.disabled())))) {
            ProdisReferenceService offService = new ProdisReferenceService(off);
            assertThat(off.isConfigured()).isFalse();
            assertThatThrownBy(() -> off.findSupplier("D0001")).isInstanceOf(IllegalStateException.class);
            assertThat(offService.lookup("D0001").availability()).isEqualTo(Availability.NOT_CONFIGURED);
            assertThatThrownBy(() -> offService.searchSuppliers("x"))
                    .isInstanceOf(ProdisReferenceNotConfiguredException.class)
                    .extracting("code").isEqualTo("PRODIS_DB_NOT_CONFIGURED");
            assertThatThrownBy(() -> offService.supplierGroups("D0001"))
                    .isInstanceOf(ProdisReferenceNotConfiguredException.class);
            assertThatThrownBy(() -> offService.supplierCatalogs("D0001"))
                    .isInstanceOf(ProdisReferenceNotConfiguredException.class);
        }
        ProdisReferenceJdbc.disabled().close();
    }

    @Test
    void aFailingSourceIsUnavailableNeverEmpty() {
        PRODIS.breakSource();
        try {
            assertThat(service.lookup("D0001").availability()).isEqualTo(Availability.UNAVAILABLE);
            assertThatThrownBy(() -> service.searchSuppliers("D"))
                    .isInstanceOf(ProdisReferenceUnavailableException.class)
                    .hasMessageNotContaining("jdbc:").hasMessageNotContaining(ProdisReferenceTestDatabase.PASSWORD);
            assertThatThrownBy(() -> service.supplierCatalogs("D0001"))
                    .isInstanceOf(ProdisReferenceUnavailableException.class);
            assertThatThrownBy(() -> dao.findSupplier("D0001"))
                    .hasMessageStartingWith("Prodis reference query failed (SQLState ");
        } finally {
            PRODIS.restore();
        }
        assertThat(service.lookup("D0001").availability()).isEqualTo(Availability.VERIFIED);
    }

    @Test
    void aTooLongSearchTextIs400() {
        assertThatThrownBy(() -> service.searchSuppliers("x".repeat(101)))
                .isInstanceOf(BadRequestException.class)
                .extracting("code").isEqualTo(ProdisReferenceService.CODE_QUERY_INVALID);
        assertThat(service.searchSuppliers("x".repeat(100))).isEmpty();
    }

    private static ObjectProvider<ProdisReferenceJdbc> provider(ProdisReferenceJdbc bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory(bean == null ? Map.of()
                : Map.of("prodisReferenceJdbc", bean));
        return factory.getBeanProvider(ProdisReferenceJdbc.class);
    }
}
