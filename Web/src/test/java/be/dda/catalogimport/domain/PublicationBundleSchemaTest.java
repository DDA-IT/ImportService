package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.PublicationBundleBatchRepository;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationDecisionRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bouwstap 4a (docs/design/fase4-publication-bundle-design.md par. 2 en 6): bewijst dat changeset 005
 * migreert, dat Hibernate de nieuwe entiteiten met {@code ddl-auto: validate} aanvaardt en dat de
 * databaseconstraints van het publicatiebundelschema werkelijk afdwingen wat ze beloven. Codes zijn
 * per test uniek omdat de H2-database gedeeld is (zie {@link ScreeningSchemaTest}).
 */
@SpringBootTest
@ActiveProfiles("local")
class PublicationBundleSchemaTest {

    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
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
    @Autowired
    private ImportMutationRepository mutations;
    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private PublicationBundleBatchRepository bundleBatches;
    @Autowired
    private PublicationDecisionRepository decisions;
    @Autowired
    private JdbcTemplate jdbc;

    // --- publication_bundle ---------------------------------------------------------------------

    @Test
    void persistsABundleAndAppliesTheDocumentedIdempotencyKey() {
        String bundleReference = bundleRef("OK");
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleReference, PublicationTargetMode.SIMULATION, "tester@example.test"));

        PublicationBundle found = bundles.findById(bundle.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(PublicationBundleStatus.ASSEMBLING);
        assertThat(found.getIdempotencyKey()).isEqualTo("bundle:" + bundleReference);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getBatchCount()).isNull();
        assertThat(bundles.findByBundleReference(bundleReference)).isPresent();
        assertThat(bundles.findByIdempotencyKey("bundle:" + bundleReference)).isPresent();
    }

    @Test
    void refusesABundleWithoutAnExplicitTargetModeBecauseThereIsNoDefault() {
        PublicationBundle bundle = new PublicationBundle(bundleRef("NOMODE"), null, "tester@example.test");

        assertThatThrownBy(() -> bundles.saveAndFlush(bundle))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsTwoBundlesWithTheSameBundleReference() {
        String duplicateRef = bundleRef("DUP");
        bundles.saveAndFlush(new PublicationBundle(duplicateRef, PublicationTargetMode.SIMULATION, "tester@example.test"));

        assertThatThrownBy(() -> bundles.saveAndFlush(
                new PublicationBundle(duplicateRef, PublicationTargetMode.TRIAL_LIBRARY, "tester@example.test")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAFrozenBundleWithoutFrozenByOrContentHash() {
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("FRZ-BAD"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        bundle.setStatus(PublicationBundleStatus.FROZEN);

        // Status FROZEN gezet zonder de gecombineerde recordFreeze-aanroep -> ck_publication_bundle_frozen.
        assertThatThrownBy(() -> bundles.saveAndFlush(bundle))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsAFrozenBundleWithFrozenByAtAndContentHashAllPresent() {
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("FRZ-OK"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        bundle.recordFreeze("freezer@example.test", Instant.now(), "Alle mutaties beoordeeld",
                sha256("bundle-content"));

        assertThatCode(() -> bundles.saveAndFlush(bundle)).doesNotThrowAnyException();
        PublicationBundle found = bundles.findById(bundle.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(PublicationBundleStatus.FROZEN);
        assertThat(found.getContentHash()).isEqualTo(sha256("bundle-content"));
    }

    @Test
    void refusesACancelledBundleWithoutACancelledReason() {
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("CNC-BAD"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        bundle.setStatus(PublicationBundleStatus.CANCELLED);

        assertThatThrownBy(() -> bundles.saveAndFlush(bundle))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsACancelledBundleWithTheFullCancellationAudit() {
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("CNC-OK"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        bundle.recordCancellation("canceller@example.test", Instant.now(), "Foutieve koppeling geselecteerd");

        assertThatCode(() -> bundles.saveAndFlush(bundle)).doesNotThrowAnyException();
        assertThat(bundles.findById(bundle.getId()).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.CANCELLED);
    }

    // --- publication_bundle_batch: de kernconstraint --------------------------------------------

    @Test
    void allowsOnlyOneActiveMembershipPerBatchAcrossAllBundles() {
        Scenario s = scenario("PBB");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        PublicationBundle bundleA = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("PBB-A"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        PublicationBundle bundleB = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("PBB-B"), PublicationTargetMode.SIMULATION, "tester@example.test"));

        PublicationBundleBatch membership = bundleBatches.saveAndFlush(
                new PublicationBundleBatch(bundleA, batch, s.link(), "tester@example.test"));
        assertThat(membership.getActiveMarker()).isTrue();

        // uk_publication_bundle_batch_active: dezelfde batch kan geen tweede actief lidmaatschap krijgen.
        assertThatThrownBy(() -> bundleBatches.saveAndFlush(
                new PublicationBundleBatch(bundleB, batch, s.link(), "tester@example.test")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Na verwijdering (active_marker NULL, removed-velden gevuld) mag een NIEUW lidmaatschap wél.
        membership.recordRemoval("remover@example.test", Instant.now(), "Batch uit bundel A gehaald");
        bundleBatches.saveAndFlush(membership);
        assertThat(membership.getActiveMarker()).isNull();

        assertThatCode(() -> bundleBatches.saveAndFlush(
                new PublicationBundleBatch(bundleB, batch, s.link(), "tester@example.test")))
                .doesNotThrowAnyException();
        assertThat(bundleBatches.findByBatchIdAndActiveMarkerIsNotNull(batch.getId()))
                .hasValueSatisfying(active -> assertThat(active.getBundle().getId()).isEqualTo(bundleB.getId()));
    }

    @Test
    void rejectsTheSameBundleAndBatchCombinationTwice() {
        Scenario s = scenario("PBBDUP");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("PBBDUP"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        PublicationBundleBatch first = bundleBatches.saveAndFlush(
                new PublicationBundleBatch(bundle, batch, s.link(), "tester@example.test"));
        first.recordRemoval("remover@example.test", Instant.now(), "Opnieuw testen");
        bundleBatches.saveAndFlush(first);

        // uk_publication_bundle_batch_bundle: (bundle, batch) is uniek, ook na verwijdering.
        assertThatThrownBy(() -> bundleBatches.saveAndFlush(
                new PublicationBundleBatch(bundle, batch, s.link(), "tester@example.test")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAPartiallyFilledRemovalOnAMembership() {
        Scenario s = scenario("PBBPART");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("PBBPART"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        PublicationBundleBatch membership = bundleBatches.saveAndFlush(
                new PublicationBundleBatch(bundle, batch, s.link(), "tester@example.test"));

        // Rechtstreeks op de database: removed_by gevuld maar removed_at/removed_reason niet.
        assertThatThrownBy(() -> jdbc.update("update publication_bundle_batch set removed_by = ? where id = ?",
                        "sneaky", membership.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- publication_decision --------------------------------------------------------------------

    @Test
    void refusesAMutationScopedDecisionWithoutAMutationReference() {
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("DECMUT"), PublicationTargetMode.SIMULATION, "tester@example.test"));

        PublicationDecision decision = new PublicationDecision(bundle, null, BundleDecisionKind.APPROVE,
                BundleDecisionScope.MUTATION, 1, "decider@example.test", "Goedgekeurd");

        assertThatThrownBy(() -> decisions.saveAndFlush(decision))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAGroupScopedDecisionThatStillCarriesAMutationReference() {
        Scenario s = scenario("DECGRP");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("DECGRP"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        String mutationKey = "DECGRP" + Long.toString(System.nanoTime(), 36) + ":1:H1:OFFER";
        ImportMutation mutation = mutations.saveAndFlush(createMutation(batch, mutationKey));

        PublicationDecision decision = new PublicationDecision(bundle, mutation, BundleDecisionKind.APPROVE,
                BundleDecisionScope.GROUP, 5, "decider@example.test", "Groepsactie");

        assertThatThrownBy(() -> decisions.saveAndFlush(decision))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsAWellFormedMutationScopedDecision() {
        Scenario s = scenario("DECOK");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("DECOK"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        String mutationKey = "DECOK" + Long.toString(System.nanoTime(), 36) + ":1:H1:OFFER";
        ImportMutation mutation = mutations.saveAndFlush(createMutation(batch, mutationKey));

        PublicationDecision decision = decisions.saveAndFlush(new PublicationDecision(bundle, mutation,
                BundleDecisionKind.APPROVE, BundleDecisionScope.MUTATION, 1, "decider@example.test",
                "Goedgekeurd"));

        assertThat(decision.getId()).isNotNull();
        assertThat(decision.getDecidedAt()).isNotNull();
        assertThat(decisions.findByBundleIdOrderByDecidedAtAsc(bundle.getId())).hasSize(1);
        assertThat(decisions.findByMutationIdOrderByDecidedAtAsc(mutation.getId())).hasSize(1);
    }

    // --- import_mutation: de vier decide-velden -----------------------------------------------

    @Test
    void refusesAMutationWithOnlyOneOfTheFourDecideFieldsFilled() {
        Scenario s = scenario("MDECPART");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        String mutationKey = "MDECPART" + Long.toString(System.nanoTime(), 36) + ":1:H1:OFFER";
        ImportMutation mutation = mutations.saveAndFlush(createMutation(batch, mutationKey));

        assertThatThrownBy(() -> jdbc.update("update import_mutation set decided_by = ? where id = ?",
                        "sneaky", mutation.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesARejectedMutationWithoutADecisionReference() {
        Scenario s = scenario("MREJNODEC");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        String mutationKey = "MREJNODEC" + Long.toString(System.nanoTime(), 36) + ":1:H1:OFFER";
        ImportMutation mutation = mutations.saveAndFlush(createMutation(batch, mutationKey));
        mutation.setStatus(MutationStatus.REJECTED);

        // ck_import_mutation_rejected_decided: REJECTED vereist decision_id, dus ook decided_by/at/from.
        assertThatThrownBy(() -> mutations.saveAndFlush(mutation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsARejectedMutationWithAFullyRecordedRejectDecision() {
        Scenario s = scenario("MREJOK");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("MREJOK"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        String mutationKey = "MREJOK" + Long.toString(System.nanoTime(), 36) + ":1:H1:OFFER";
        ImportMutation mutation = mutations.saveAndFlush(createMutation(batch, mutationKey));
        PublicationDecision decision = decisions.saveAndFlush(new PublicationDecision(bundle, mutation,
                BundleDecisionKind.REJECT, BundleDecisionScope.MUTATION, 1, "decider@example.test",
                "Prijs klopt niet met de leveranciersfactuur"));

        Instant decidedAt = Instant.now();
        mutation.recordDecision("decider@example.test", decidedAt, MutationStatus.PLANNED, decision.getId());
        mutation.setStatus(MutationStatus.REJECTED);

        assertThatCode(() -> mutations.saveAndFlush(mutation)).doesNotThrowAnyException();
        ImportMutation found = mutations.findById(mutation.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(MutationStatus.REJECTED);
        assertThat(found.getDecisionId()).isEqualTo(decision.getId());
        assertThat(found.getDecidedBy()).isEqualTo("decider@example.test");
        assertThat(found.getDecidedFromStatus()).isEqualTo(MutationStatus.PLANNED);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private String bundleRef(String prefix) {
        long nanoSeq = System.nanoTime();
        return "BND-" + prefix + "-" + Long.toString(nanoSeq, 36);
    }

    private ImportMutation createMutation(ImportBatch batch, String idempotencyKey) {
        ImportMutation mutation = new ImportMutation(batch, MutationActionType.CREATE,
                MutationTargetDomain.OFFER, MutationStatus.PLANNED, idempotencyKey);
        mutation.setIdentitySupplier("LEV");
        mutation.setIdentitySupplierGroup("GRP");
        mutation.setIdentitySupplierReference("REF-" + idempotencyKey);
        return mutation;
    }

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ImportDefinition definition(String prefix) {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(prefix + "-ORG", prefix + "-ORG BV", SourceOrganisationType.SUPPLIER));
        return definitions.saveAndFlush(
                new ImportDefinition(organisation, prefix + "-DEF", prefix + " catalogus", "beheerder@example.test"));
    }

    private ImportDefinitionRevision newRevision(ImportDefinition definition, int revisionNumber) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, revisionNumber,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("LEV_GROEP");
        revision.setIdentitySupplierReferenceField("LEV_REFERENTIE");
        revision.setStructureDelimiter(";");
        return revision;
    }

    /** Volledige keten tot en met levering, zodat batches aangemaakt kunnen worden. */
    private Scenario scenario(String prefix) {
        String unique = prefix + "-" + Long.toString(System.nanoTime(), 36);
        ImportDefinition definition = definition(unique);
        ImportDefinitionRevision revision = revisions.saveAndFlush(newRevision(definition, 1));
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + "-LINK koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        Delivery delivery = deliveries.saveAndFlush(new Delivery(task, "manual:" + unique, Instant.now()));
        return new Scenario(revision, link, delivery);
    }

    private final class Scenario {
        private final ImportDefinitionRevision revision;
        private final ImportLink link;
        private final Delivery delivery;

        private Scenario(ImportDefinitionRevision revision, ImportLink link, Delivery delivery) {
            this.revision = revision;
            this.link = link;
            this.delivery = delivery;
        }

        ImportLink link() {
            return link;
        }

        ImportBatch newBatch(int attemptNo) {
            return new ImportBatch(delivery, link, revision, attemptNo, "tester@example.test");
        }
    }
}
