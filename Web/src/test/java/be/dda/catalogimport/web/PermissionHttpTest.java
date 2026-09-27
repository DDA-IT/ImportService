package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Bouwstap 5B-1 (bindend ontwerp {@code docs/design/fase5-perm-design.md} par. 1, 3, 6 en 7): de eerste
 * endpoints met een <b>recht per actie</b>. Geannoteerd zijn de vijf goedkeurende bundelendpoints:
 * {@code approve}, {@code reject}, de groepsactie {@code decisions}, {@code freeze} en {@code cancel},
 * allemaal {@code @RequiresPermission(APPROVE)} (beslissingslog 2026-09-25, A2).
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> alleen wie mag goedkeuren, keurt goed — fail-closed. <b>Implementatie:</b> 403
 *       {@code PERMISSION_DENIED} in een interceptor vóór de handler; er wordt <i>niets</i> geschreven,
 *       wat hier telkens via JDBC wordt nagekeken.</li>
 *   <li><b>Regel (V2, hiërarchie):</b> {@code approve} impliceert {@code manage} en {@code read}, niet
 *       omgekeerd. <b>Implementatie:</b> {@code as(user, MANAGE)} en {@code as(user, READ)} worden
 *       geweigerd op een APPROVE-endpoint; {@code as(user, APPROVE)} komt erdoor.</li>
 *   <li><b>Regel (V1, "recht eerst"):</b> het ontbrekende recht weegt zwaarder dan een verkeerd
 *       ingevuld veld of een onbekende bundel. <b>Implementatie:</b> 403 gaat vóór 400
 *       {@code ACTOR_FIELD_MISMATCH} en vóór 404 {@code BUNDLE_NOT_FOUND}. Dit wijzigt bewust de
 *       volgorde uit {@code fase5-auth-design.md} par. 13.1.</li>
 *   <li><b>Regel:</b> {@code system} beheert of keurt nooit goed. <b>Implementatie:</b> 403
 *       {@code SYSTEM_ACTOR_FORBIDDEN} <i>vóór</i> de rechtencheck — bewezen met een {@code system} die
 *       géén enkel recht heeft en tóch die code krijgt in plaats van {@code PERMISSION_DENIED}.</li>
 *   <li><b>Data:</b> een weigering wordt gelogd (WARN), niet bewaard; er komt geen rij bij.</li>
 * </ul>
 *
 * <p><b>Nog niet bewijsbaar in 5B-1.</b> Dat een {@code APPROVE}-gebruiker ook mag lezen en beheren is
 * over HTTP nog geen bewijs: de GET's en de MANAGE-endpoints dragen pas in 5B-2/5B-3 een annotatie en
 * komen in de soepele modus dus sowieso door. De hiërarchie zelf is databasevrij bewezen in
 * {@code PermissionHierarchyTest} en {@code CurrentActorPermissionTest}.</p>
 *
 * <p>Elke test bouwt een eigen keten met unieke codes: de database is gedeeld.</p>
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PermissionHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String BASE = "/api/catalog-import/bundles";
    private static final String BATCHES = "/api/catalog-import/batches";
    private static final String USER = "an.janssens@example.test";
    private static final String OTHER = "piet.willems@example.test";
    private static final String SETUP = "setup@example.test";
    private static final String REASON = "{\"reason\":\"Nagekeken\"}";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
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

    // --- Zonder recht: alle vijf 403 PERMISSION_DENIED, en niets geschreven ---------------------------------

    /**
     * De kern van 5B-1. Alle vijf de geannoteerde endpoints worden door een rechtenloze gebruiker
     * geprobeerd; alle vijf geven 403 {@code PERMISSION_DENIED} en er blijft geen enkel spoor achter.
     * <p>
     * Let op {@code freeze}: die bundel heeft nog onbesliste mutaties en zou mét het recht 409 geven.
     * Dat het hier 403 is, is precies de volgorde uit ontwerp par. 3 (recht vóór 409).
     */
    @Test
    void withoutTheApprovePermissionAllFiveEndpointsAre403AndNothingIsWritten() throws Exception {
        Scenario s = assembling("DENY");
        long mutationId = mutationIds(s).get(0);

        denied(post(BASE + "/{id}/mutations/{m}/approve", s.bundleId(), mutationId), "{}");
        denied(post(BASE + "/{id}/mutations/{m}/reject", s.bundleId(), mutationId), REASON);
        denied(post(BASE + "/{id}/decisions", s.bundleId()),
                "{\"decisionKind\":\"APPROVE\",\"filter\":{\"batchId\":" + s.batchId() + "}}");
        denied(post(BASE + "/{id}/freeze", s.bundleId()), REASON);
        denied(post(BASE + "/{id}/cancel", s.bundleId()), REASON);

        assertNothingWritten(s);
    }

    /** Dubbele input: twee identieke geweigerde pogingen laten nog steeds niets achter. */
    @Test
    void repeatingADeniedAttemptStillWritesNothing() throws Exception {
        Scenario s = assembling("DENYTWICE");
        long mutationId = mutationIds(s).get(0);

        denied(post(BASE + "/{id}/mutations/{m}/approve", s.bundleId(), mutationId), "{}");
        denied(post(BASE + "/{id}/mutations/{m}/approve", s.bundleId(), mutationId), "{}");

        assertNothingWritten(s);
    }

    /** De foutboodschap noemt de ontbrekende rechtcode en niets anders — geen bron, geen andermans rechten. */
    @Test
    void theDenialNamesTheMissingRightCodeAndNothingElse() throws Exception {
        Scenario s = assembling("MSG");

        String body = json(post(BASE + "/{id}/cancel", s.bundleId()), withoutPermissions(USER), REASON)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("catalogImport.approve");
        assertThat(body).doesNotContain("catalogImport.read").doesNotContain("catalogImport.manage")
                .doesNotContain("test-sub-").doesNotContain(USER);
    }

    // --- Met recht: ongewijzigd gedrag ------------------------------------------------------------------------

    /** Vier van de vijf endpoints, mét {@code APPROVE}: ze doen precies wat ze in Fase 4 al deden. */
    @Test
    void withTheApprovePermissionApproveRejectGroupAndCancelStillWork() throws Exception {
        Scenario s = assembling("ALLOW");
        List<Long> mutations = mutationIds(s);

        json(post(BASE + "/{id}/mutations/{m}/approve", s.bundleId(), mutations.get(0)),
                as(USER, Permission.APPROVE), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mutation.status").value("READY_FOR_PUBLICATION"));
        json(post(BASE + "/{id}/mutations/{m}/reject", s.bundleId(), mutations.get(1)),
                as(USER, Permission.APPROVE), "{\"reason\":\"Prijs klopt niet\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mutation.status").value("REJECTED"));
        json(post(BASE + "/{id}/decisions", s.bundleId()), as(USER, Permission.APPROVE),
                "{\"decisionKind\":\"APPROVE\",\"reason\":\"Rest nagekeken\",\"filter\":{\"batchId\":"
                        + s.batchId() + "}}")
                .andExpect(status().isOk());
        json(post(BASE + "/{id}/cancel", s.bundleId()), as(USER, Permission.APPROVE), REASON)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(decisionCount(s.bundleId())).isPositive();
    }

    /** Het vijfde endpoint apart, want bevriezen vraagt een bundel waarin alles beslisbaar is. */
    @Test
    void withTheApprovePermissionFreezeStillWorks() throws Exception {
        long bundleId = freezable("FRZ");

        json(post(BASE + "/{id}/freeze", bundleId), as(USER, Permission.APPROVE), REASON)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    // --- Hiërarchie -------------------------------------------------------------------------------------------

    /**
     * {@code MANAGE} en {@code READ} zijn geen goedkeuring, {@code APPROVE} wel. De eerste twee laten
     * niets achter; pas de derde schrijft een beslissing.
     */
    @Test
    void manageAndReadMayNotApproveButApproveMay() throws Exception {
        Scenario s = assembling("HIER");
        long mutationId = mutationIds(s).get(0);
        String path = BASE + "/{id}/mutations/{m}/approve";

        json(post(path, s.bundleId(), mutationId), as(USER, Permission.MANAGE), "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(path, s.bundleId(), mutationId), as(USER, Permission.READ), "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertNothingWritten(s);

        json(post(path, s.bundleId(), mutationId), as(USER, Permission.APPROVE), "{}")
                .andExpect(status().isOk());
        assertThat(decisionCount(s.bundleId())).isEqualTo(1);
    }

    /**
     * Strikte modus (5B-3): de soepele modus bestaat niet meer. Een GET vraagt {@code READ}: zonder enig
     * recht is dat 403, met {@code READ} 200. (Vervangt {@code anUnannotatedEndpointIsStillOpenInLenientMode};
     * "niet-geannoteerde handler = 403" is databasevrij bewezen in {@code PermissionInterceptorTest}.)
     */
    @Test
    void aGetWithoutAnyRightIs403AndWithReadIs200() throws Exception {
        Scenario s = assembling("STRICT");

        mockMvc.perform(get(BASE + "/{id}", s.bundleId()).with(withoutPermissions(USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(get(BASE + "/{id}", s.bundleId()).with(as(USER, Permission.READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSEMBLING"));
    }

    // --- system: vóór de rechtencheck --------------------------------------------------------------------------

    /**
     * {@code system} heeft hier <b>geen enkel recht</b>. Kwam de rechtencheck eerst, dan zou dit
     * {@code PERMISSION_DENIED} geven; het is {@code SYSTEM_ACTOR_FORBIDDEN}, dus de volgorde uit
     * ontwerp par. 3 klopt.
     */
    @Test
    void systemIsRefusedWithSystemActorForbiddenBeforeTheRightsCheck() throws Exception {
        Scenario s = assembling("SYS");
        long mutationId = mutationIds(s).get(0);

        json(post(BASE + "/{id}/mutations/{m}/approve", s.bundleId(), mutationId),
                withoutPermissions("system"), "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        json(post(BASE + "/{id}/cancel", s.bundleId()), withoutPermissions("SyStEm"), REASON)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertNothingWritten(s);
    }

    // --- Volgorde: recht eerst -----------------------------------------------------------------------------------

    /**
     * Geen recht + een actorveld met een andere naam: 403 {@code PERMISSION_DENIED}, niet 400
     * {@code ACTOR_FIELD_MISMATCH}. Mét het recht is het weer gewoon 400 — dat gedrag uit 5A-4 is niet
     * gewijzigd, enkel voorafgegaan.
     */
    @Test
    void aMissingRightOutweighsAWrongActorName() throws Exception {
        Scenario s = assembling("ORDER1");
        long mutationId = mutationIds(s).get(0);
        String path = BASE + "/{id}/mutations/{m}/approve";
        String wrongName = "{\"decidedBy\":\"" + OTHER + "\"}";

        json(post(path, s.bundleId(), mutationId), withoutPermissions(USER), wrongName)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(path, s.bundleId(), mutationId), as(USER, Permission.APPROVE), wrongName)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));

        assertNothingWritten(s);
    }

    /**
     * Geen recht + een onbekende bundel: 403 {@code PERMISSION_DENIED}, niet 404
     * {@code BUNDLE_NOT_FOUND}. Anders zou een rechtenloze gebruiker aan de statuscode kunnen aflezen
     * welke bundelnummers bestaan.
     */
    @Test
    void aMissingRightOutweighsAnUnknownBundle() throws Exception {
        long unknown = 999_999_999L;

        json(post(BASE + "/{id}/cancel", unknown), withoutPermissions(USER), REASON)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(BASE + "/{id}/cancel", unknown), as(USER, Permission.APPROVE), REASON)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private void denied(MockHttpServletRequestBuilder request, String body) throws Exception {
        json(request, withoutPermissions(USER), body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    private ResultActions json(MockHttpServletRequestBuilder request, RequestPostProcessor actor, String body)
            throws Exception {
        return this.mockMvc.perform(request.with(actor).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Niets geschreven: de bundel staat nog ASSEMBLING, er is geen beslissing en geen mutatie besliste. */
    private void assertNothingWritten(Scenario s) {
        assertThat(this.jdbc.queryForObject("select status from publication_bundle where id = ?", String.class,
                s.bundleId())).isEqualTo("ASSEMBLING");
        assertThat(decisionCount(s.bundleId())).isZero();
        Long decided = this.jdbc.queryForObject(
                "select count(*) from import_mutation where batch_id = ? and decision_id is not null",
                Long.class, s.batchId());
        assertThat(decided).isZero();
    }

    private long decisionCount(long bundleId) {
        Long count = this.jdbc.queryForObject("select count(*) from publication_decision where bundle_id = ?",
                Long.class, bundleId);
        return count == null ? 0L : count;
    }

    /** De beslisbare mutaties van deze batch, oplopend; de {@code IMPORT_MARKER} zit er niet bij. */
    private List<Long> mutationIds(Scenario s) {
        return this.jdbc.queryForList("select id from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE') order by id", Long.class, s.batchId());
    }

    /**
     * Een {@code ASSEMBLING}-bundel met één lid: de eerste levering van een nieuwe koppeling, dus drie
     * {@code CREATE}-mutaties op {@code AWAITING_APPROVAL} plus de marker. Opzet identiek aan
     * {@code BundleActorHttpTest.scenario}, zodat deze test enkel over rechten gaat.
     */
    private Scenario assembling(String prefix) throws Exception {
        String unique = unique(prefix);
        CatalogImportTask task = fixture(unique);
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 1; index <= 3; index++) {
            csv.append("ACME;G1;R").append(index).append(";1").append(index).append(",00;Artikel ")
                    .append(index).append('\n');
        }
        long batchId = upload(task.getId(), unique + "-LEV", csv.toString());

        String created = json(post(BASE), as(SETUP),
                "{\"bundleReference\":\"BND-" + unique + "\",\"targetMode\":\"SIMULATION\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long bundleId = ((Number) JsonPath.read(created, "$.id")).longValue();
        json(post(BASE + "/{id}/batches", bundleId), as(SETUP), "{\"batchIds\":[" + batchId + "]}")
                .andExpect(status().isOk());
        return new Scenario(bundleId, batchId);
    }

    /**
     * Een bundel die écht bevroren kan worden: eerste levering als nulmeting aanvaard, tweede levering
     * met dezelfde identiteiten en andere prijzen, dus enkel <b>geplande</b> prijswijzigingen die het
     * bevriezen zelf in bulk goedkeurt. Zelfde opzet als {@code FreezeActorHttpTest.plannedBundle}.
     */
    private long freezable(String prefix) throws Exception {
        String unique = unique(prefix);
        CatalogImportTask task = fixture(unique);
        long first = upload(task.getId(), unique + "-LEV1", rows(100));
        this.mockMvc.perform(post(BATCHES + "/{id}/accept-baseline", first).with(as(SETUP))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Nulmeting\"}"))
                .andExpect(status().isOk());
        long second = upload(task.getId(), unique + "-LEV2", rows(125));

        String created = json(post(BASE), as(SETUP),
                "{\"bundleReference\":\"BND-" + unique + "\",\"targetMode\":\"SIMULATION\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long bundleId = ((Number) JsonPath.read(created, "$.id")).longValue();
        json(post(BASE + "/{id}/batches", bundleId), as(SETUP), "{\"batchIds\":[" + second + "]}")
                .andExpect(status().isOk());
        return bundleId;
    }

    private static String rows(int priceCents) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 0; index < 5; index++) {
            int cents = priceCents + index * 25;
            csv.append("ACME;G1;R").append(index + 1).append(';').append(cents / 100).append(',')
                    .append(String.format("%02d", cents % 100)).append(";Artikel ").append(index + 1).append('\n');
        }
        return csv.toString();
    }

    private long upload(long taskId, String reference, String csv) throws Exception {
        String body = this.mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", taskId)
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", reference)
                        .param("uploadedBy", SETUP).with(as(SETUP)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.batchId")).longValue();
    }

    private static String unique(String prefix) {
        return "PH" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
    }

    private CatalogImportTask fixture(String unique) {
        SourceOrganisation organisation = this.sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = this.definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1, IdentityProfileKind.THREE_PART,
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
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        this.revisions.saveAndFlush(revision);
        SourceOrganisation supplier = this.sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = this.links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        return this.tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
    }

    private record Scenario(long bundleId, long batchId) {
    }
}
