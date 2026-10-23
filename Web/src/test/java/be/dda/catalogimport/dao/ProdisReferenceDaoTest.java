package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.config.ProdisReadOnlyDatabaseConfiguration;
import be.dda.catalogimport.config.ProdisReferenceDatabaseProperties;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleDescriptionRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleIdentity;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleLookup;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticlePriceDetailRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticlePriceRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierCatalogRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierGroupRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.UnitOfMeasureRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.VatCodeRow;
import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.NotFoundException;
import be.dda.catalogimport.service.ProdisReferenceNotConfiguredException;
import be.dda.catalogimport.service.ProdisReferenceService;
import be.dda.catalogimport.service.ProdisReferenceService.Availability;
import be.dda.catalogimport.service.ProdisReferenceService.CodeMatch;
import be.dda.catalogimport.service.ProdisReferenceService.CreationDefaultsCheck;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyLookup;
import be.dda.catalogimport.service.ProdisReferenceService.CatalogArticleLookups;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterMode;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterReading;
import be.dda.catalogimport.service.ProdisReferenceUnavailableException;
import be.dda.catalogimport.testsupport.ProdisReferenceTestDatabase;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
        seedCatalog();
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
    void aLegacyNumberMatchesWhenAllDigitsIgnoringLeadingZerosAndOtherwiseExactly() {
        long leg = PRODIS.insertSupplier("LEG01", "Library Test");
        PRODIS.insertCatalog(leg, "181", "181", "DELICOLOR", "ACTIVE");
        PRODIS.insertCatalog(leg, "1810", "1810", "TEN EIGHTY", "ACTIVE");
        PRODIS.insertCatalog(leg, "0", "0", "ZERO", "ACTIVE");
        PRODIS.insertCatalog(leg, "0181", "0181", "PADDED", "ACTIVE");
        PRODIS.insertCatalog(leg, "AB12", "AB12", "TEXT", "ACTIVE");

        assertThat(scopesOf("LEG01", "181")).containsExactlyInAnyOrder("181", "0181");
        assertThat(scopesOf("LEG01", "00181")).containsExactlyInAnyOrder("181", "0181");
        assertThat(scopesOf("LEG01", "0181")).containsExactlyInAnyOrder("181", "0181");
        assertThat(scopesOf("LEG01", "0")).containsExactly("0");
        assertThat(scopesOf("LEG01", "000")).containsExactly("0");
        assertThat(scopesOf("LEG01", "AB12")).containsExactly("AB12");
        assertThat(scopesOf("LEG01", "ab12")).isEmpty();
        assertThat(scopesOf("LEG01", "0AB12")).isEmpty();
        assertThat(scopesOf("LEG01", "1810")).containsExactly("1810");
        assertThat(scopesOf("LEG01", "18100")).isEmpty();
        assertThat(scopesOf("LEG01", "")).isEmpty();
        assertThat(ProdisReferenceDao.normalizeLegacyNumber("00181")).isEqualTo("181");
        assertThat(ProdisReferenceDao.normalizeLegacyNumber("000")).isEqualTo("0");
        assertThat(ProdisReferenceDao.normalizeLegacyNumber("AB12")).isEqualTo("AB12");
        assertThat(ProdisReferenceDao.normalizeLegacyNumber(null)).isNull();
    }

    private List<String> scopesOf(String supplierNumber, String legacyNumber) {
        return dao.findSupplierCatalogs(supplierNumber, legacyNumber).stream()
                .map(SupplierCatalogRow::scopeKey).toList();
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
    void vatCodesAndUnitsAreActiveOnlyOnePerTrimmedCodeWithTheLowestIdLabelAndADuplicateIsCounted() {
        PRODIS.clearCreationDefaults();
        PRODIS.insertVatCode("3", "BTW21%", 21.0, true, false);
        PRODIS.insertVatCode(" 3 ", "BTW21% dubbel", 22.0, true, false);
        PRODIS.insertVatCode("3", "BTW21% verwijderd", 23.0, true, true);
        PRODIS.insertVatCode("3", "BTW21% inactief", 24.0, false, false);
        PRODIS.insertVatCode("1", "BTW6%", 6.0, true, false);
        PRODIS.insertVatCode("0", "Vrijgesteld", null, true, false);
        PRODIS.insertVatCode(null, "Zonder code", 1.0, true, false);
        PRODIS.insertVatCode("  ", "Lege code", 1.0, true, false);
        PRODIS.insertVatCode("9", "Enkel inactief", 9.0, false, false);
        PRODIS.insertUnitOfMeasure("ST", "Stuk", "st", true, false);
        PRODIS.insertUnitOfMeasure("ST", "Stuk 2", "st2", true, false);
        PRODIS.insertUnitOfMeasure("DS", "Doos", "ds", true, false);
        PRODIS.insertUnitOfMeasure("DS", "Doos oud", "dso", false, false);
        PRODIS.insertUnitOfMeasure("KG", "Kilo", "kg", true, true);
        PRODIS.insertUnitOfMeasure(null, "Zonder", "x", true, false);

        assertThat(dao.findVatCodes()).containsExactly(
                new VatCodeRow("0", "Vrijgesteld", null, 1),
                new VatCodeRow("1", "BTW6%", 6.0, 1),
                new VatCodeRow("3", "BTW21%", 21.0, 2));
        assertThat(dao.findUnitsOfMeasure()).containsExactly(
                new UnitOfMeasureRow("DS", "Doos", "ds", 1),
                new UnitOfMeasureRow("ST", "Stuk", "st", 2));
        assertThat(service.vatCodes()).hasSize(3);
        assertThat(service.unitsOfMeasure()).hasSize(2);

        assertThat(service.verifyCreationDefaults("1", "DS")).isEqualTo(
                new CreationDefaultsCheck(Availability.VERIFIED, CodeMatch.FOUND, CodeMatch.FOUND));
        assertThat(service.verifyCreationDefaults("3", "ST")).isEqualTo(
                new CreationDefaultsCheck(Availability.VERIFIED, CodeMatch.AMBIGUOUS, CodeMatch.AMBIGUOUS));
        assertThat(service.verifyCreationDefaults("9", "KG")).isEqualTo(
                new CreationDefaultsCheck(Availability.VERIFIED, CodeMatch.NOT_FOUND, CodeMatch.NOT_FOUND));
        assertThat(service.verifyCreationDefaults(null, "DS")).isEqualTo(
                new CreationDefaultsCheck(Availability.VERIFIED, CodeMatch.NOT_REQUESTED, CodeMatch.FOUND));
    }

    @Test
    void creationDefaultListsAreOffWithoutConfigurationAndUnavailableOnAFailure() throws Exception {
        ProdisReferenceService offService = new ProdisReferenceService(new ProdisReferenceDao(provider(null)));
        assertThatThrownBy(offService::vatCodes).isInstanceOf(ProdisReferenceNotConfiguredException.class);
        assertThatThrownBy(offService::unitsOfMeasure).isInstanceOf(ProdisReferenceNotConfiguredException.class);
        assertThat(offService.verifyCreationDefaults("3", null)).isEqualTo(
                new CreationDefaultsCheck(Availability.NOT_CONFIGURED, CodeMatch.NOT_CONFIGURED,
                        CodeMatch.NOT_REQUESTED));

        PRODIS.execute("alter table vat_code rename to vat_code_unavailable");
        try {
            assertThatThrownBy(service::vatCodes).isInstanceOf(ProdisReferenceUnavailableException.class);
            assertThatThrownBy(() -> service.verifyCreationDefaults("3", null))
                    .isInstanceOf(ProdisReferenceUnavailableException.class);
        } finally {
            PRODIS.execute("alter table vat_code_unavailable rename to vat_code");
        }
    }

    @Test
    void aTooLongSearchTextIs400() {
        assertThatThrownBy(() -> service.searchSuppliers("x".repeat(101)))
                .isInstanceOf(BadRequestException.class)
                .extracting("code").isEqualTo(ProdisReferenceService.CODE_QUERY_INVALID);
        assertThat(service.searchSuppliers("x".repeat(100))).isEmpty();
    }

    // --- P1: legacy_master en catalogusrij voor de publicatie-parallelrun ---------------------------------------------

    private static CatalogArticleIdentity id(String number, String group, String scope, String reference) {
        return new CatalogArticleIdentity(number, group, scope, reference);
    }

    private static void seedCatalog() {
        long nl = PRODIS.insertLanguage("NL");
        long en = PRODIS.insertLanguage("EN");
        long supplier = PRODIS.insertSupplier("PS1", "Pub one");
        long group = PRODIS.insertGroup(supplier, "PS1|10|", "10", "");
        long active = PRODIS.insertCatalog(supplier, "S1", "S1", "ACTIVE ONE", "ACTIVE");
        long superseded = PRODIS.insertCatalog(supplier, "S1", "S1", "OLD ONE", "SUPERSEDED");

        long ref1 = PRODIS.insertArticle(supplier, group, active, "REF1", true, false);
        long price = PRODIS.insertPrice(ref1, "0", "N", new BigDecimal("12.345678"), 20.5, 5.0);
        for (int i = 1; i <= 5; i++) {
            PRODIS.insertPriceDetail(price, String.valueOf(i), i * 10.0, i * 1.5);
        }
        PRODIS.insertDescription(ref1, nl, "Hamer");
        PRODIS.insertDescription(ref1, en, "Hammer");

        // Vorm: artikel zonder prijsrij en zonder omschrijving.
        PRODIS.insertArticle(supplier, group, active, "NOPRICE", true, false);
        // Dubbel: twee artikelen met dezelfde identiteit.
        PRODIS.insertArticle(supplier, group, active, "DUPREF", true, false);
        PRODIS.insertArticle(supplier, group, active, "DUPREF", true, false);
        // Verwijderd/inactief blijft zichtbaar met vlaggen; de planner beslist.
        PRODIS.insertArticle(supplier, group, active, "GONE", false, true);
        // Hoort bij de niet-actieve bibliotheekrij: geen treffer voor deze identiteit.
        PRODIS.insertArticle(supplier, group, superseded, "OLDCAT", true, false);

        // Twee leveranciers met hetzelfde nummer.
        for (String name : List.of("Twin A", "Twin B")) {
            long twin = PRODIS.insertSupplier("PTWIN", name);
            long twinGroup = PRODIS.insertGroup(twin, "PTWIN|10|", "10", "");
            long twinCatalog = PRODIS.insertCatalog(twin, "T1", "T1", "TWIN", "ACTIVE");
            PRODIS.insertArticle(twin, twinGroup, twinCatalog, "TW1", true, false);
        }

        // Meer dan een batch.
        long bulk = PRODIS.insertSupplier("PBULK", "Bulk");
        long bulkGroup = PRODIS.insertGroup(bulk, "PBULK|10|", "10", "");
        long bulkCatalog = PRODIS.insertCatalog(bulk, "B1", "B1", "BULK", "ACTIVE");
        for (int i = 0; i < ProdisReferenceDao.CATALOG_BATCH_SIZE + 5; i++) {
            long article = PRODIS.insertArticle(bulk, bulkGroup, bulkCatalog, "BULK" + i, true, false);
            PRODIS.insertPrice(article, "0", "N", new BigDecimal(i), null, null);
        }
    }

    @Test
    void legacyMasterIsReadFromTheFirstRowAndAMissingRowMeansProdis() {
        for (String value : List.of("NONE", "PRODIS", "PROFAB")) {
            PRODIS.setLegacyMaster(value);
            assertThat(service.legacyMaster()).isEqualTo(new LegacyMasterReading(Availability.VERIFIED,
                    LegacyMasterMode.valueOf(value)));
        }
        PRODIS.setLegacyMaster(null);
        assertThat(service.legacyMaster()).isEqualTo(new LegacyMasterReading(Availability.VERIFIED,
                LegacyMasterMode.PRODIS));

        // Eerste rij op id wint.
        PRODIS.setLegacyMaster("NONE");
        PRODIS.execute("insert into system_settings (legacy_master) values ('PROFAB')");
        assertThat(service.legacyMaster().mode()).isEqualTo(LegacyMasterMode.NONE);
    }

    @Test
    void anUnreadableLegacyMasterIsUnknownNeverADefault() {
        PRODIS.setLegacyMaster("GARBAGE");
        assertThat(service.legacyMaster()).isEqualTo(new LegacyMasterReading(Availability.VERIFIED,
                LegacyMasterMode.UNKNOWN));
    }

    @Test
    void anUnavailableSourceIsUnavailableForLegacyMasterNotProdis() throws Exception {
        PRODIS.setLegacyMaster("NONE");
        PRODIS.breakSystemSettings();
        try {
            assertThat(service.legacyMaster()).isEqualTo(new LegacyMasterReading(Availability.UNAVAILABLE,
                    LegacyMasterMode.UNKNOWN));
        } finally {
            PRODIS.restoreSystemSettings();
        }
        ProdisReferenceService off = new ProdisReferenceService(new ProdisReferenceDao(provider(null)));
        assertThat(off.legacyMaster()).isEqualTo(new LegacyMasterReading(Availability.NOT_CONFIGURED,
                LegacyMasterMode.UNKNOWN));
        assertThat(off.lookupCatalogArticles(List.of(id("PS1", "PS1|10|", "S1", "REF1"))))
                .isEqualTo(new CatalogArticleLookups(Availability.NOT_CONFIGURED, List.of()));
    }

    @Test
    void aCatalogRowIsReadWithPriceDetailsAndDutchDescription() {
        CatalogArticleLookups result = service.lookupCatalogArticles(List.of(id("PS1", "PS1|10|", "S1", "REF1")));
        assertThat(result.availability()).isEqualTo(Availability.VERIFIED);
        CatalogArticleLookup lookup = result.lookups().get(0);
        assertThat(lookup.supplierCount()).isEqualTo(1);
        assertThat(lookup.articles()).hasSize(1);
        CatalogArticleRow article = lookup.articles().get(0);
        assertThat(article.active()).isTrue();
        assertThat(article.deleted()).isFalse();
        assertThat(article.descriptions()).containsExactly(new CatalogArticleDescriptionRow("Hamer", true, false));
        assertThat(article.prices()).hasSize(1);
        CatalogArticlePriceRow price = article.prices().get(0);
        assertThat(price.priceCode()).isEqualTo("0");
        assertThat(price.brutPricePolitics()).isEqualTo("N");
        assertThat(price.basePrice()).isEqualByComparingTo("12.345678");
        assertThat(price.brutPrice()).isEqualTo(20.5);
        assertThat(price.brutPricePercentage()).isEqualTo(5.0);
        assertThat(price.salesPriceLastUpdated()).isEqualTo(LocalDateTime.of(2026, 2, 3, 4, 5, 6));
        assertThat(price.details()).extracting(CatalogArticlePriceDetailRow::code)
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(price.details().get(2)).isEqualTo(new CatalogArticlePriceDetailRow("3", 30.0, 4.5, true, false));
    }

    @Test
    void notFoundDuplicateAndShapeDeviationsAreVisibleInTheLookup() {
        List<CatalogArticleLookup> lookups = service.lookupCatalogArticles(List.of(
                id("PS1", "PS1|10|", "S1", "MISSING"),
                id("PS1", "PS1|20|", "S1", "REF1"),
                id("PS1", "PS1|10|", "S2", "REF1"),
                id("PS1", "PS1|10|", "S1", "ref1"),
                id("PS1", "PS1|10|", "S1", "OLDCAT"),
                id("PS1", "PS1|10|", "S1", "DUPREF"),
                id("PS1", "PS1|10|", "S1", "NOPRICE"),
                id("PS1", "PS1|10|", "S1", "GONE"),
                id("NOSUCH", "PS1|10|", "S1", "REF1"))).lookups();
        assertThat(lookups).hasSize(9);
        for (int i : new int[] {0, 1, 2, 3, 4, 8}) {
            assertThat(lookups.get(i).articles()).as("identity %d", i).isEmpty();
        }
        assertThat(lookups.get(8).supplierCount()).isZero();
        assertThat(lookups.get(5).articles()).hasSize(2);
        CatalogArticleRow noPrice = lookups.get(6).articles().get(0);
        assertThat(noPrice.prices()).isEmpty();
        assertThat(noPrice.descriptions()).isEmpty();
        CatalogArticleRow gone = lookups.get(7).articles().get(0);
        assertThat(gone.active()).isFalse();
        assertThat(gone.deleted()).isTrue();
    }

    @Test
    void aDuplicateSupplierNumberIsReportedAsSuch() {
        CatalogArticleLookup lookup = service.lookupCatalogArticles(List.of(id("PTWIN", "PTWIN|10|", "T1", "TW1")))
                .lookups().get(0);
        assertThat(lookup.supplierCount()).isEqualTo(2);
        assertThat(lookup.articles()).hasSize(2);
    }

    @Test
    void manyIdentitiesAreReadInBatchesInInputOrderAndDuplicatesCollapse() {
        List<CatalogArticleIdentity> identities = new java.util.ArrayList<>();
        for (int i = 0; i < ProdisReferenceDao.CATALOG_BATCH_SIZE + 5; i++) {
            identities.add(id("PBULK", "PBULK|10|", "B1", "BULK" + i));
        }
        identities.add(id("PBULK", "PBULK|10|", "B1", "BULK0"));
        identities.add(id("PBULK", "PBULK|10|", "B1", "NOPE"));
        List<CatalogArticleLookup> lookups = service.lookupCatalogArticles(identities).lookups();
        assertThat(lookups).hasSize(ProdisReferenceDao.CATALOG_BATCH_SIZE + 6);
        assertThat(lookups).extracting(l -> l.identity().supplierReferenceStripped())
                .startsWith("BULK0", "BULK1").endsWith("BULK204", "NOPE");
        assertThat(lookups.subList(0, ProdisReferenceDao.CATALOG_BATCH_SIZE + 5))
                .allSatisfy(l -> assertThat(l.articles()).hasSize(1));
        assertThat(lookups.get(ProdisReferenceDao.CATALOG_BATCH_SIZE + 5).articles()).isEmpty();
        assertThat(lookups.get(204).articles().get(0).prices().get(0).basePrice()).isEqualByComparingTo("204");
        assertThat(service.lookupCatalogArticles(List.of()).lookups()).isEmpty();
    }

    @Test
    void aFailingSourceMakesTheCatalogLookupUnavailableNeverNotFound() {
        PRODIS.breakSource();
        try {
            assertThat(service.lookupCatalogArticles(List.of(id("PS1", "PS1|10|", "S1", "REF1"))))
                    .isEqualTo(new CatalogArticleLookups(Availability.UNAVAILABLE, List.of()));
        } finally {
            PRODIS.restore();
        }
    }

    @Test
    void theProjectionCarriesNoProdisIds() {
        for (Class<?> type : List.of(CatalogArticleLookup.class, CatalogArticleRow.class, CatalogArticlePriceRow.class,
                CatalogArticlePriceDetailRow.class, CatalogArticleDescriptionRow.class,
                CatalogArticleIdentity.class)) {
            assertThat(type.getRecordComponents()).extracting(RecordComponent::getName)
                    .noneMatch(name -> name.equals("id") || name.endsWith("Id") || name.endsWith("Ids"));
        }
    }

    private static ObjectProvider<ProdisReferenceJdbc> provider(ProdisReferenceJdbc bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory(bean == null ? Map.of()
                : Map.of("prodisReferenceJdbc", bean));
        return factory.getBeanProvider(ProdisReferenceJdbc.class);
    }
}
