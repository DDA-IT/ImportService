package be.dda.catalogimport.web;

import be.dda.catalogimport.testsupport.ProdisReferenceTestDatabase;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.springframework.test.context.TestPropertySource;

/**
 * Gedeelde opbouw voor de tests met een geconfigureerde Prodis-leesbron (legacy-config S5): dezelfde context als
 * {@link LegacyConfigS2TestBase}, maar {@code catalogimport.prodis-db.*} wijst naar een H2-database in het geheugen met
 * de drie Prodis-tabellen ({@code prodis-reference-test-schema.sql}). Alle subklassen delen één context en één H2-bron;
 * elke test gebruikt eigen leveranciersnummers (die van {@link #applicable()} zijn willekeurig).
 */
@TestPropertySource(properties = {
        "catalogimport.prodis-db.url=" + LegacyConfigProdisTestBase.PRODIS_URL,
        "catalogimport.prodis-db.username=" + ProdisReferenceTestDatabase.USERNAME,
        "catalogimport.prodis-db.password=" + ProdisReferenceTestDatabase.PASSWORD,
        "catalogimport.prodis-db.connect-timeout=PT1S",
        "catalogimport.prodis-db.query-timeout=PT2S"})
abstract class LegacyConfigProdisTestBase extends LegacyConfigS2TestBase {

    static final String PRODIS_URL =
            "jdbc:h2:mem:prodis-reference-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    /** De ArtImp_BibNr van de golden fixture (en dus van {@link #applicable()}). */
    static final String LEGACY_LIBRARY = "12";
    static final String PRODIS_REFERENCE = "/api/catalog-import/prodis-reference";

    static final ProdisReferenceTestDatabase PRODIS = ProdisReferenceTestDatabase.at(PRODIS_URL);

    /** Een toepasbare bundel met de leverancier die in Prodis bestaat. */
    record Fixture(ObjectNode bundle, String supplier, long prodisSupplierId) {
    }

    static Fixture withProdisSupplier(String prodisName) throws IOException {
        ObjectNode bundle = applicable();
        String supplier = supplier(bundle);
        return new Fixture(bundle, supplier, PRODIS.insertSupplier(supplier, prodisName));
    }

    static void catalog(Fixture fixture, String scopeKey, String legacyNumber, String name, String status) {
        PRODIS.insertCatalog(fixture.prodisSupplierId(), scopeKey, legacyNumber, name, status);
    }
}
