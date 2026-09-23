package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.FieldDataType;
import be.dda.catalogimport.domain.FieldOwner;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityClass;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import be.dda.catalogimport.domain.RevisionCriticalityField;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.service.support.ImportMappingConfig.FieldMapping;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bouwstap 3h-1 (ontwerp fase 3, par. 15.1, changesets 004-2b en 004-15): de kritiek-vlag per kolom
 * als <b>configuratie</b>. De vlag wordt in deze stap enkel geladen en bevraagbaar gemaakt
 * ({@link ImportMappingConfig#criticalityOf(String)}); er wordt nog niets mee geteld of beslist
 * (dat is bouwstap 3h-2).
 * <p>
 * Twee verdedigingslinies worden bewezen. De <b>database</b> weert een schendende rij (de checks van
 * 004-2b en 004-15); de <b>Java-validatie</b> ({@code CONFIG_FIELD_CRITICALITY_INVALID}) weert dezelfde
 * schending voor een configuratie die niet via de database binnenkwam en wordt in
 * {@code MappingConfigValidationTest} getest. Codes zijn per test uniek omdat de H2-database gedeeld is.
 */
@SpringBootTest
@ActiveProfiles("local")
class FieldCriticalityTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

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
    private ImportRevisionFieldCriticalityRepository criticalities;
    @Autowired
    private SourceStructureConfigFactory structureFactory;
    @Autowired
    private ImportMappingConfigFactory mappingFactory;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // --- Standaard van de soort, zonder overrule-rij ----------------------------------------------

    @Test
    void appliesTheDefaultOfEachKindWhenThereIsNoOverruleRow() {
        ImportDefinitionRevision revision = inMemoryRevision(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE);
        ImportFieldMapping supporting = mapping(revision, 1, supportingField(), "E_LEV");
        ImportFieldMapping akp = mapping(revision, 2, priceField("AKP_PCT", "AKP"), "AKP");
        akp.setPriceComponentCode("AKP");
        ImportFieldMapping ean = mapping(revision, 3, referenceField(), "EAN13");
        ean.setReferenceType("EAN");

        ImportMappingConfig config = plainFactory().from(revision,
                structure(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, "KORTING", "MUNT"),
                List.of(supporting, akp, ean), List.of());

        // Identiteit: kritiek en niet instelbaar. Basisprijs en munt: kritiek. Omschrijving: niet.
        assertThat(config.criticalityOf("LEVERANCIER")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("GROEP")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("REFERENTIE")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("KORTING")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("PRIJS")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("MUNT")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("OMSCHRIJVING")).isEqualTo(Criticality.NON_CRITICAL);
        // Gemapte velden: hun eigen vlag, onder hun logische veldnaam. Prijscomponent en referentie
        // zijn standaard kritiek; een gewoon gemapt veld niet.
        assertThat(config.criticalityOf("Externe leveranciersidentiteit")).isEqualTo(Criticality.NON_CRITICAL);
        assertThat(config.criticalityOf("AKP_PCT")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("EAN-barcode")).isEqualTo(Criticality.CRITICAL);
    }

    /** Bij een driedelige identiteit wordt de kortingscodekolom niet gelezen: er kan geen fout op vallen. */
    @Test
    void ignoresTheDiscountCodeColumnOfAThreePartIdentity() {
        ImportMappingConfig config = plainFactory().from(inMemoryRevision(IdentityProfileKind.THREE_PART),
                structure(IdentityProfileKind.THREE_PART, "KORTING", null), List.of(), List.of());

        // Onbekend is fail-safe kritiek; het bewijs dat de kolom er niet in zit is de map zelf.
        assertThat(config.criticalities()).doesNotContainKey("KORTING").doesNotContainKey("MUNT");
        assertThat(config.criticalities()).containsOnlyKeys("LEVERANCIER", "GROEP", "REFERENTIE", "PRIJS",
                "OMSCHRIJVING");
    }

    @Test
    void mapsEachRevisionFieldKeyToItsDocumentedDefault() {
        assertThat(RevisionCriticalityField.values()).extracting(RevisionCriticalityField::name)
                .containsExactly("SUPPLIER", "SUPPLIER_GROUP", "SUPPLIER_REFERENCE", "DISCOUNT_CODE",
                        "BASE_PRICE", "CURRENCY", "DESCRIPTION");
        for (RevisionCriticalityField field : RevisionCriticalityField.values()) {
            Criticality expected = field == RevisionCriticalityField.DESCRIPTION
                    ? Criticality.NON_CRITICAL : Criticality.CRITICAL;
            assertThat(field.defaultCriticality()).as(field.name()).isEqualTo(expected);
        }
        assertThat(RevisionCriticalityField.values()).filteredOn(RevisionCriticalityField::isIdentity)
                .extracting(RevisionCriticalityField::name)
                .containsExactly("SUPPLIER", "SUPPLIER_GROUP", "SUPPLIER_REFERENCE", "DISCOUNT_CODE");
        assertThat(RevisionCriticalityField.byKey("NONSENSE")).isEmpty();
        assertThat(RevisionCriticalityField.byKey(null)).isEmpty();
    }

    // --- Overrule ------------------------------------------------------------------------------

    @Test
    void anOverruleMakesTheBasePriceNonCritical() {
        ImportDefinitionRevision revision = inMemoryRevision(IdentityProfileKind.THREE_PART);

        ImportMappingConfig config = plainFactory().from(revision,
                structure(IdentityProfileKind.THREE_PART, null, "MUNT"), List.of(), List.of(), List.of(
                        new ImportRevisionFieldCriticality(null, "BASE_PRICE", Criticality.NON_CRITICAL),
                        new ImportRevisionFieldCriticality(null, "CURRENCY", Criticality.NON_CRITICAL)));

        assertThat(config.criticalityOf("PRIJS")).isEqualTo(Criticality.NON_CRITICAL);
        assertThat(config.criticalityOf("MUNT")).isEqualTo(Criticality.NON_CRITICAL);
        // Wat niet overruled is, houdt zijn standaard.
        assertThat(config.criticalityOf("LEVERANCIER")).isEqualTo(Criticality.CRITICAL);
    }

    @Test
    void anOverruleMakesTheDescriptionCritical() {
        ImportMappingConfig config = plainFactory().from(inMemoryRevision(IdentityProfileKind.THREE_PART),
                structure(IdentityProfileKind.THREE_PART, null, null), List.of(), List.of(), List.of(
                        new ImportRevisionFieldCriticality(null, "DESCRIPTION", Criticality.CRITICAL)));

        assertThat(config.criticalityOf("OMSCHRIJVING")).isEqualTo(Criticality.CRITICAL);
    }

    // --- criticalityOf: fail-safe en botsing --------------------------------------------------------

    @Test
    void anUnknownOrNullFieldNameIsCriticalBecauseAnUnattributableErrorMustNeverPassAsNonCritical() {
        ImportMappingConfig config = plainFactory().from(inMemoryRevision(IdentityProfileKind.THREE_PART),
                structure(IdentityProfileKind.THREE_PART, null, null), List.of(), List.of());

        assertThat(config.criticalityOf("NIET_BESTAANDE_KOLOM")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf(null)).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("")).isEqualTo(Criticality.CRITICAL);
        // Een configuratie zonder enige mapping of structuur doet hetzelfde.
        assertThat(new ImportMappingConfig(1, List.of(), List.of()).criticalityOf("X"))
                .isEqualTo(Criticality.CRITICAL);
    }

    /**
     * Twee namespaces (bronreferenties van de revisie-eigen velden en logische veldnamen van de
     * mappings) delen één map. Botsen ze, dan wint de strengste in beide richtingen.
     */
    @Test
    void aCollisionBetweenTheTwoNamespacesResolvesToCritical() {
        ImportDefinitionRevision revision = inMemoryRevision(IdentityProfileKind.THREE_PART);
        // Een mapping die toevallig dezelfde naam draagt als de (niet-kritieke) omschrijvingskolom...
        ImportFieldCatalogEntry sameNameAsDescriptionColumn = new ImportFieldCatalogEntry("E_SUPPLIER",
                "OMSCHRIJVING", FieldDataType.TEXT, FieldOwner.CATALOG_SOURCE, IdentityClass.SUPPORTING, 130);
        ImportFieldMapping critical = mapping(revision, 1, sameNameAsDescriptionColumn, "E_LEV");
        critical.setCriticality(Criticality.CRITICAL);
        // ...en een niet-kritieke mapping met dezelfde naam als de (kritieke) basisprijskolom.
        ImportFieldCatalogEntry sameNameAsPriceColumn = new ImportFieldCatalogEntry("SUPPLIER_BARCODE",
                "PRIJS", FieldDataType.TEXT, FieldOwner.CATALOG_SOURCE, IdentityClass.SUPPORTING, 140);
        ImportFieldMapping nonCritical = mapping(revision, 2, sameNameAsPriceColumn, "BARCODE");
        nonCritical.setCriticality(Criticality.NON_CRITICAL);

        ImportMappingConfig config = plainFactory().from(revision,
                structure(IdentityProfileKind.THREE_PART, null, null), List.of(critical, nonCritical),
                List.of());

        assertThat(config.criticalityOf("OMSCHRIJVING")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("PRIJS")).isEqualTo(Criticality.CRITICAL);
    }

    @Test
    void mergesTheStrictestFlagPerKeyRegardlessOfOrder() {
        FieldMapping nonCritical = fieldMapping("A", "Naam", Criticality.NON_CRITICAL);
        FieldMapping critical = fieldMapping("B", "Naam", Criticality.CRITICAL);

        assertThat(ImportMappingConfig.criticalityMap(Map.of(), List.of(nonCritical, critical)))
                .containsEntry("Naam", Criticality.CRITICAL);
        assertThat(ImportMappingConfig.criticalityMap(Map.of(), List.of(critical, nonCritical)))
                .containsEntry("Naam", Criticality.CRITICAL);
        assertThat(ImportMappingConfig.criticalityMap(Map.of("Naam", Criticality.CRITICAL),
                List.of(nonCritical))).containsEntry("Naam", Criticality.CRITICAL);
        assertThat(ImportMappingConfig.criticalityMap(Map.of("Naam", Criticality.NON_CRITICAL),
                List.of(nonCritical))).containsEntry("Naam", Criticality.NON_CRITICAL);
        assertThat(Criticality.strictest(Criticality.NON_CRITICAL, Criticality.NON_CRITICAL))
                .isEqualTo(Criticality.NON_CRITICAL);
    }

    // --- Database: overrule-tabel (004-15) --------------------------------------------------------

    @Test
    void aRevisionIdentityFieldCanNeverBeStoredAsNonCritical() {
        ImportDefinitionRevision revision = persistedRevision("IDNC", IdentityProfileKind.THREE_PART);

        for (String key : List.of("SUPPLIER", "SUPPLIER_GROUP", "SUPPLIER_REFERENCE", "DISCOUNT_CODE")) {
            // Via JPA...
            assertThatThrownBy(() -> criticalities.saveAndFlush(
                    new ImportRevisionFieldCriticality(revision.getId(), key, Criticality.NON_CRITICAL)))
                    .as(key).isInstanceOf(DataIntegrityViolationException.class);
            // ...en via rauwe SQL: de check zit in de database, niet in de entiteit.
            assertThatThrownBy(() -> insertOverrule(revision.getId(), key, "NON_CRITICAL"))
                    .as(key).isInstanceOf(DataIntegrityViolationException.class);
            // Kritiek herhaalt enkel de standaard en mag wel.
            assertThatCode(() -> insertOverrule(revision.getId(), key, "CRITICAL"))
                    .as(key).doesNotThrowAnyException();
        }
        assertThat(criticalities.findByDefinitionRevisionId(revision.getId())).hasSize(4)
                .allSatisfy(row -> assertThat(row.getCriticality()).isEqualTo(Criticality.CRITICAL));
    }

    @Test
    void anUnknownFieldKeyOrCriticalityValueIsRefusedByTheDatabase() {
        ImportDefinitionRevision revision = persistedRevision("KEY", IdentityProfileKind.THREE_PART);

        assertThatThrownBy(() -> criticalities.saveAndFlush(
                new ImportRevisionFieldCriticality(revision.getId(), "BRAND", Criticality.NON_CRITICAL)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOverrule(revision.getId(), "PRICE", "NON_CRITICAL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOverrule(revision.getId(), "BASE_PRICE", "MAYBE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(criticalities.findByDefinitionRevisionId(revision.getId())).isEmpty();
    }

    @Test
    void storesAnOverruleOncePerFieldAndPerExistingRevision() {
        ImportDefinitionRevision revision = persistedRevision("ONCE", IdentityProfileKind.THREE_PART);

        ImportRevisionFieldCriticality saved = criticalities.saveAndFlush(
                new ImportRevisionFieldCriticality(revision.getId(), "BASE_PRICE", Criticality.NON_CRITICAL));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(criticalities.findByDefinitionRevisionId(revision.getId())).singleElement()
                .satisfies(row -> {
                    assertThat(row.getFieldKey()).isEqualTo("BASE_PRICE");
                    assertThat(row.getCriticality()).isEqualTo(Criticality.NON_CRITICAL);
                });
        // pk (revisie, veld): een tweede rij voor hetzelfde veld kan niet.
        assertThatThrownBy(() -> insertOverrule(revision.getId(), "BASE_PRICE", "CRITICAL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // fk: een overrule hoort bij een bestaande revisie.
        assertThatThrownBy(() -> insertOverrule(-1L, "BASE_PRICE", "CRITICAL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Een overrule van een andere revisie telt niet mee.
        ImportDefinitionRevision other = persistedRevision("ONCE2", IdentityProfileKind.THREE_PART);
        assertThat(criticalities.findByDefinitionRevisionId(other.getId())).isEmpty();
    }

    // --- Database: mappingkolom (004-2b) ----------------------------------------------------------

    @Test
    void aCriticalReferenceMappingCanNeverBeStoredAsNonCritical() {
        ImportDefinitionRevision revision = persistedRevision("REFNC", IdentityProfileKind.THREE_PART);
        // Via JPA: een uitdrukkelijk NON_CRITICAL referentiemapping faalt op de databasecheck.
        ImportFieldMapping explicit = referenceMapping(revision, 1, "EAN", "EAN13");
        explicit.setCriticality(Criticality.NON_CRITICAL);
        assertThatThrownBy(() -> fieldMappings.saveAndFlush(explicit))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Via rauwe SQL, ook zonder de kolom te noemen: de database-default NON_CRITICAL schendt de check.
        assertThatThrownBy(() -> jdbc.update("insert into import_field_mapping (definition_revision_id, "
                + "sequence_number, target_field_code, value_kind, source_reference, data_type, field_owner, "
                + "identity_class, reference_type, created_at) values (?, 2, 'PIM_ID', 'SOURCE_FIELD', 'PIM', "
                + "'TEXT', 'CRITICAL_REFERENCE', 'ARTICLE_REFERENCE', 'PIM_ID', ?)", revision.getId(),
                OffsetDateTime.now())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into import_field_mapping (definition_revision_id, "
                + "sequence_number, target_field_code, value_kind, source_reference, data_type, field_owner, "
                + "identity_class, reference_type, criticality, created_at) values (?, 2, 'PIM_ID', "
                + "'SOURCE_FIELD', 'PIM', 'TEXT', 'CRITICAL_REFERENCE', 'ARTICLE_REFERENCE', 'PIM_ID', "
                + "'NON_CRITICAL', ?)", revision.getId(), OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Een waarde buiten de set kan al helemaal niet.
        assertThatThrownBy(() -> jdbc.update("insert into import_field_mapping (definition_revision_id, "
                + "sequence_number, target_field_code, value_kind, source_reference, data_type, field_owner, "
                + "identity_class, criticality, created_at) values (?, 3, 'E_SUPPLIER', 'SOURCE_FIELD', 'X', "
                + "'TEXT', 'CATALOG_SOURCE', 'SUPPORTING', 'MAYBE', ?)", revision.getId(),
                OffsetDateTime.now())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fieldMappings.findByRevisionIdWithTargetField(revision.getId())).isEmpty();
    }

    @Test
    void derivesTheDefaultOfAMappingFromItsKindAndKeepsAnExplicitFlag() {
        ImportDefinitionRevision revision = persistedRevision("MAPDEF", IdentityProfileKind.THREE_PART);
        ImportFieldMapping plain = fieldMappings.saveAndFlush(
                plainMapping(revision, 1, "E_SUPPLIER", "E_LEV"));
        ImportFieldMapping price = fieldMappings.saveAndFlush(priceMapping(revision, 2, "AKP_PCT", "AKP"));
        ImportFieldMapping reference = fieldMappings.saveAndFlush(referenceMapping(revision, 3, "EAN", "EAN13"));
        // Instelbaar: een gewoon veld mag kritiek, een prijscomponent mag niet-kritiek.
        ImportFieldMapping plainCritical = plainMapping(revision, 4, "SUPPLIER_BARCODE", "BARCODE");
        plainCritical.setCriticality(Criticality.CRITICAL);
        fieldMappings.saveAndFlush(plainCritical);
        ImportFieldMapping priceNonCritical = priceMapping(revision, 5, "VKP1_PCT", "VKP1");
        priceNonCritical.setCriticality(Criticality.NON_CRITICAL);
        fieldMappings.saveAndFlush(priceNonCritical);

        Map<String, String> stored = storedCriticality(revision.getId());
        assertThat(stored).containsEntry("E_SUPPLIER", "NON_CRITICAL")
                .containsEntry("AKP_PCT", "CRITICAL")
                .containsEntry("EAN", "CRITICAL")
                .containsEntry("SUPPLIER_BARCODE", "CRITICAL")
                .containsEntry("VKP1_PCT", "NON_CRITICAL");
        assertThat(plain.getCriticality()).isEqualTo(Criticality.NON_CRITICAL);
        assertThat(price.getCriticality()).isEqualTo(Criticality.CRITICAL);
        assertThat(reference.getCriticality()).isEqualTo(Criticality.CRITICAL);
    }

    /** De database-default is NON_CRITICAL: een gewone gemapte kolom is niet kritiek tenzij iemand dat zegt. */
    @Test
    void aMappingInsertedWithoutTheColumnDefaultsToNonCritical() {
        ImportDefinitionRevision revision = persistedRevision("DBDEF", IdentityProfileKind.THREE_PART);

        jdbc.update("insert into import_field_mapping (definition_revision_id, sequence_number, "
                + "target_field_code, value_kind, source_reference, data_type, field_owner, identity_class, "
                + "created_at) values (?, 1, 'E_SUPPLIER', 'SOURCE_FIELD', 'E_LEV', 'TEXT', 'CATALOG_SOURCE', "
                + "'SUPPORTING', ?)", revision.getId(), OffsetDateTime.now());

        assertThat(storedCriticality(revision.getId())).containsEntry("E_SUPPLIER", "NON_CRITICAL");
    }

    // --- Backfill (004-2b) ---------------------------------------------------------------------------

    /**
     * De backfill van changeset 004-2b wordt hier letterlijk uit het changelogbestand gelezen en
     * uitgevoerd op rijen in de toestand van vóór de migratie (default NON_CRITICAL). Alles gebeurt in
     * één transactie die teruggedraaid wordt, zodat andere tests geen rij zien verschuiven.
     */
    @Test
    void theBackfillMakesExistingPriceComponentAndReferenceMappingsCriticalAndLeavesOthersAlone() {
        ImportDefinitionRevision revision = persistedRevision("BACKFILL", IdentityProfileKind.THREE_PART);
        String backfill = backfillStatement();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // Rijen zoals ze vóór de migratie bestonden: kolom op de default NON_CRITICAL. Een
            // referentiemapping kan in die toestand na de check niet meer bestaan; ze wordt daarom
            // ingevoegd met de waarde die de backfill zou moeten opleveren en apart bewezen hieronder.
            insertRawMapping(revision.getId(), 1, "AKP_PCT", "PRICE_CONTROL", "NONE", "AKP", null);
            insertRawMapping(revision.getId(), 2, "E_SUPPLIER", "CATALOG_SOURCE", "SUPPORTING", null, null);
            insertRawMapping(revision.getId(), 3, "SUPPLIER_BARCODE", "CATALOG_SOURCE", "SUPPORTING", null,
                    null);
            assertThat(storedCriticality(revision.getId())).containsEntry("AKP_PCT", "NON_CRITICAL");

            jdbc.update(backfill);

            assertThat(storedCriticality(revision.getId()))
                    .containsEntry("AKP_PCT", "CRITICAL")
                    .containsEntry("E_SUPPLIER", "NON_CRITICAL")
                    .containsEntry("SUPPLIER_BARCODE", "NON_CRITICAL");
            // Een referentiemapping die de backfill zou raken, bestaat alleen als ze al kritiek is (de
            // check weert de andere toestand); de backfill laat haar kritiek.
            insertRawMapping(revision.getId(), 4, "EAN", "CRITICAL_REFERENCE", "ARTICLE_REFERENCE", null,
                    "EAN", "CRITICAL");
            jdbc.update(backfill);
            assertThat(storedCriticality(revision.getId())).containsEntry("EAN", "CRITICAL");
            status.setRollbackOnly();
        });

        assertThat(fieldMappings.findByRevisionIdWithTargetField(revision.getId())).isEmpty();
    }

    /**
     * De volgorde in de changeset is de eigenlijke bescherming van bestaande data: eerst de kolom, dan de
     * backfill, dan pas de check op referentiemappings. Stond de check vóór de backfill, dan schond elke
     * bestaande referentiemapping (default NON_CRITICAL) hem.
     */
    @Test
    void theChangesetBackfillsBeforeItAddsTheReferenceCheck() throws Exception {
        String changelog = changelog();
        int changeset = changelog.indexOf("--changeset catalogimport:004-2b-");
        int addColumn = changelog.indexOf("add column criticality", changeset);
        int backfill = changelog.indexOf("update import_field_mapping set criticality", changeset);
        int check = changelog.indexOf("ck_import_field_mapping_reference_critical", changeset);

        assertThat(changeset).isPositive();
        assertThat(addColumn).isGreaterThan(changeset);
        assertThat(backfill).isGreaterThan(addColumn);
        assertThat(check).isGreaterThan(backfill);
    }

    // --- Laden: één keer per batch, uit de database ----------------------------------------------------

    @Test
    void loadsTheFlagsOfTheMappingsAndTheOverrulesFromTheDatabaseInOnePass() {
        ImportDefinitionRevision revision = persistedRevision("LOAD", IdentityProfileKind.THREE_PART);
        fieldMappings.saveAndFlush(plainMapping(revision, 1, "E_SUPPLIER", "E_LEV"));
        fieldMappings.saveAndFlush(referenceMapping(revision, 2, "EAN", "EAN13"));
        ImportFieldMapping akp = priceMapping(revision, 3, "AKP_PCT", "AKP");
        akp.setCriticality(Criticality.NON_CRITICAL);
        fieldMappings.saveAndFlush(akp);
        ImportFieldMapping inactive = plainMapping(revision, 4, "SUPPLIER_BARCODE", "BARCODE");
        inactive.setCriticality(Criticality.CRITICAL);
        inactive.setActive(false);
        fieldMappings.saveAndFlush(inactive);
        criticalities.saveAndFlush(
                new ImportRevisionFieldCriticality(revision.getId(), "BASE_PRICE", Criticality.NON_CRITICAL));
        criticalities.saveAndFlush(
                new ImportRevisionFieldCriticality(revision.getId(), "DESCRIPTION", Criticality.CRITICAL));

        ImportMappingConfig config = mappingFactory.from(revision, structureFactory.from(revision));

        assertThat(config.criticalityOf("PRIJS")).isEqualTo(Criticality.NON_CRITICAL);
        assertThat(config.criticalityOf("OMSCHRIJVING")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("LEVERANCIER")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("Externe leveranciersidentiteit")).isEqualTo(Criticality.NON_CRITICAL);
        assertThat(config.criticalityOf("EAN-barcode")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("Aankoopprijs in procent van de basisprijs"))
                .isEqualTo(Criticality.NON_CRITICAL);
        // Een inactieve mapping doet niet mee: haar naam is dan onbekend, dus fail-safe kritiek.
        assertThat(config.criticalities()).doesNotContainKey("Leveranciersbarcode");
        assertThat(config.fields()).extracting(FieldMapping::criticality)
                .containsExactly(Criticality.NON_CRITICAL, Criticality.CRITICAL, Criticality.NON_CRITICAL);
    }

    @Test
    void aRevisionWithoutAnyFlagRowBehavesLikeTheDefaultsOfTheKind() {
        ImportDefinitionRevision revision = persistedRevision("NOROWS", IdentityProfileKind.THREE_PART);

        ImportMappingConfig config = mappingFactory.from(revision, structureFactory.from(revision));

        assertThat(config.hasFields()).isFalse();
        assertThat(config.criticalityOf("PRIJS")).isEqualTo(Criticality.CRITICAL);
        assertThat(config.criticalityOf("OMSCHRIJVING")).isEqualTo(Criticality.NON_CRITICAL);
    }

    // --- Hulpmiddelen ---------------------------------------------------------------------------------

    private static ImportMappingConfigFactory plainFactory() {
        return new ImportMappingConfigFactory(null, null);
    }

    private static SourceStructureConfig structure(IdentityProfileKind kind, String discountField,
                                                   String currencyField) {
        return new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"', true, 1,
                FieldReferenceKind.HEADER_NAME, null, kind, "LEVERANCIER", "GROEP", "REFERENTIE",
                discountField, "PRIJS", "OMSCHRIJVING", 2,
                new PricePolicy(currencyField, false, false, new BigDecimal("0.01")));
    }

    private static ImportDefinitionRevision inMemoryRevision(IdentityProfileKind kind) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1, kind, "beheerder@example.test");
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setRecordCanonicalisationVersion(2);
        return revision;
    }

    private ImportDefinitionRevision persistedRevision(String prefix, IdentityProfileKind kind) {
        String unique = "FC" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation,
                unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1, kind,
                "beheerder@example.test");
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
        revision.setRecordCanonicalisationVersion(2);
        revision.setStatus(RevisionStatus.ACTIVE);
        return revisions.saveAndFlush(revision);
    }

    private void insertOverrule(Long revisionId, String fieldKey, String criticality) {
        jdbc.update("insert into import_revision_field_criticality (definition_revision_id, field_key, "
                + "criticality, created_at) values (?, ?, ?, ?)", revisionId, fieldKey, criticality,
                OffsetDateTime.now());
    }

    private void insertRawMapping(Long revisionId, int sequence, String target, String owner,
                                  String identityClass, String priceComponent, String referenceType) {
        insertRawMapping(revisionId, sequence, target, owner, identityClass, priceComponent, referenceType,
                null);
    }

    private void insertRawMapping(Long revisionId, int sequence, String target, String owner,
                                  String identityClass, String priceComponent, String referenceType,
                                  String criticality) {
        String type = priceComponent != null ? "DECIMAL" : "TEXT";
        if (criticality == null) {
            jdbc.update("insert into import_field_mapping (definition_revision_id, sequence_number, "
                    + "target_field_code, value_kind, source_reference, data_type, field_owner, "
                    + "identity_class, price_component_code, reference_type, created_at) "
                    + "values (?, ?, ?, 'SOURCE_FIELD', 'X', ?, ?, ?, ?, ?, ?)", revisionId, sequence, target,
                    type, owner, identityClass, priceComponent, referenceType, OffsetDateTime.now());
        } else {
            jdbc.update("insert into import_field_mapping (definition_revision_id, sequence_number, "
                    + "target_field_code, value_kind, source_reference, data_type, field_owner, "
                    + "identity_class, price_component_code, reference_type, criticality, created_at) "
                    + "values (?, ?, ?, 'SOURCE_FIELD', 'X', ?, ?, ?, ?, ?, ?, ?)", revisionId, sequence,
                    target, type, owner, identityClass, priceComponent, referenceType, criticality,
                    OffsetDateTime.now());
        }
    }

    /** {@code target_field_code} → opgeslagen {@code criticality} van alle mappings van deze revisie. */
    private Map<String, String> storedCriticality(Long revisionId) {
        Map<String, String> stored = new java.util.LinkedHashMap<>();
        jdbc.query("select target_field_code, criticality from import_field_mapping "
                        + "where definition_revision_id = ?",
                row -> {
                    stored.put(row.getString("target_field_code"), row.getString("criticality"));
                }, revisionId);
        return stored;
    }

    private static String changelog() throws java.io.IOException {
        try (java.io.InputStream in = new ClassPathResource("db/changelog/004-import-rules-core.sql")
                .getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** De {@code update}-instructie van de backfill, letterlijk uit de changeset (zonder afsluitende ;). */
    private static String backfillStatement() {
        try {
            Matcher matcher = Pattern.compile("(?s)update import_field_mapping set criticality[^;]*")
                    .matcher(changelog());
            assertThat(matcher.find()).as("the backfill of changeset 004-2b is present").isTrue();
            return matcher.group();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot read the changelog", failure);
        }
    }

    private ImportFieldMapping plainMapping(ImportDefinitionRevision revision, int sequence, String code,
                                            String sourceReference) {
        return mapping(revision, sequence, fieldCatalog.findById(code).orElseThrow(), sourceReference);
    }

    private ImportFieldMapping priceMapping(ImportDefinitionRevision revision, int sequence, String code,
                                            String sourceReference) {
        ImportFieldCatalogEntry target = fieldCatalog.findById(code).orElseThrow();
        ImportFieldMapping mapping = mapping(revision, sequence, target, sourceReference);
        mapping.setPriceComponentCode(target.getPriceComponentCode());
        return mapping;
    }

    private ImportFieldMapping referenceMapping(ImportDefinitionRevision revision, int sequence, String code,
                                                String sourceReference) {
        ImportFieldCatalogEntry target = fieldCatalog.findById(code).orElseThrow();
        ImportFieldMapping mapping = mapping(revision, sequence, target, sourceReference);
        mapping.setReferenceType(target.getReferenceType());
        return mapping;
    }

    private static ImportFieldMapping mapping(ImportDefinitionRevision revision, int sequence,
                                              ImportFieldCatalogEntry target, String sourceReference) {
        ImportFieldMapping mapping = new ImportFieldMapping(revision, sequence, target,
                FieldValueKind.SOURCE_FIELD, target.getDataType(), target.getDefaultOwner(),
                target.getIdentityClass());
        mapping.setSourceReference(sourceReference);
        return mapping;
    }

    private static ImportFieldCatalogEntry supportingField() {
        return new ImportFieldCatalogEntry("E_SUPPLIER", "Externe leveranciersidentiteit",
                FieldDataType.TEXT, FieldOwner.CATALOG_SOURCE, IdentityClass.SUPPORTING, 130);
    }

    private static ImportFieldCatalogEntry priceField(String code, String componentCode) {
        ImportFieldCatalogEntry field = new ImportFieldCatalogEntry(code, code, FieldDataType.DECIMAL,
                FieldOwner.PRICE_CONTROL, IdentityClass.NONE, 20);
        field.setPriceComponentCode(componentCode);
        return field;
    }

    private static ImportFieldCatalogEntry referenceField() {
        ImportFieldCatalogEntry ean = new ImportFieldCatalogEntry("EAN", "EAN-barcode", FieldDataType.TEXT,
                FieldOwner.CRITICAL_REFERENCE, IdentityClass.ARTICLE_REFERENCE, 90);
        ean.setReferenceType("EAN");
        ean.setOwnerChangeable(false);
        return ean;
    }

    /** Een bestaand FieldMapping-record met enkel de velden die deze test nodig heeft. */
    private static FieldMapping fieldMapping(String code, String name, Criticality criticality) {
        return new FieldMapping(1, code, name, FieldValueKind.SOURCE_FIELD, "X", null, null, null, null,
                FieldDataType.TEXT, false, null, null, false, false,
                be.dda.catalogimport.domain.FieldTransformKind.NONE, null, null,
                ImportMappingConfig.ValueFormat.DEFAULT, FieldOwner.CATALOG_SOURCE, IdentityClass.NONE, null,
                null, null, criticality);
    }
}
