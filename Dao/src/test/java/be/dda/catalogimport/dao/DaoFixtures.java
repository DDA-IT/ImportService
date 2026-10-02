package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

/**
 * Minimale keten organisatie, definitie, revisie, koppeling, taak, levering, batch. Alle codes dragen een
 * UUID-suffix: de tests rollen terug maar delen een database die ook andere data bevat.
 */
@TestComponent
class DaoFixtures {

    static final String USER = "tester@example.test";

    @Autowired
    private SourceOrganisationRepository organisations;
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

    /** Keten tot en met een eerste batch (poging 1); gebruik {@link Chain#newBatch(int)} voor meer pogingen. */
    Chain chain() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation("ORG-" + suffix, "Organisatie " + suffix, SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, "DEF-" + suffix, "Catalogus " + suffix, USER));
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
        revision = revisions.saveAndFlush(revision);
        ImportLink link = links.saveAndFlush(
                new ImportLink("LINK-" + suffix, "Koppeling " + suffix, definition, organisation, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, "taak-" + suffix, TaskTriggerType.MANUAL));
        Delivery delivery = deliveries.saveAndFlush(new Delivery(task, "manual:" + suffix, Instant.now()));
        return new Chain(link, revision, delivery);
    }

    /** Keten plus een open batch (poging 1). */
    ImportBatch batch() {
        return batches.saveAndFlush(chain().newBatch(1));
    }

    record Chain(ImportLink link, ImportDefinitionRevision revision, Delivery delivery) {

        ImportBatch newBatch(int attemptNo) {
            return new ImportBatch(delivery, link, revision, attemptNo, USER);
        }
    }
}
