package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.service.SetupService.RevisionView;
import be.dda.catalogimport.testsupport.TestActors;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * EB3 (beslissingslog 2026-10-23 "Editor-API-gaten", G2): {@code PUT /setup/revisions/{rid}/filters/{fid}}.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> een recordfilter van een concept is te wijzigen zonder de evaluatievolgorde te verschuiven.
 *       <b>Implementatie:</b> {@code SetupService.replaceFilter}: verwijderen, flushen, opnieuw toevoegen met het
 *       volgnummer van het oude filter (nieuw id, een {@code sequenceNumber} in het verzoek wordt genegeerd).</li>
 *   <li><b>Regel:</b> de matchgroepregels zijn die van {@code addFilter}: groep &gt; 0 (anders 400
 *       {@code MATCH_GROUP_INVALID}), een groepsregel is EXCLUDE met COMPARE_AS_EMPTY (anders 400
 *       {@code CONFIG_FILTER_INVALID}).</li>
 *   <li><b>Regel:</b> een bookmarkgebruik op het filtervolgnummer blijft geldig. <b>Implementatie:</b> de
 *       bookmarkhercontrole gebeurt na het toevoegen, niet na het verwijderen.</li>
 *   <li><b>Regel:</b> bij een fout is er niets gewijzigd (zelfde transactie, rollback); gelijktijdig wijzigen geeft
 *       nooit een 500 maar 409 {@code REVISION_EDIT_CONFLICT}.</li>
 * </ul>
 * Rechten (403 zonder MANAGE, bereikbaar zonder setup-vlag) staan in {@code PermissionWriteEndpointsHttpTest},
 * {@code SetupApiDisabledTest} en {@code PermissionCoverageTest}.
 * <p>
 * Dezelfde annotaties als {@code RevisionUpdateTest} zodat de applicatiecontext en de kleine verbindingspool gedeeld
 * worden.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupFilterReplaceTest {

    private static final String USER = MaterialisationFixtures.USER;
    private static final String SUBJECT = "test-sub-" + MaterialisationFixtures.USER;
    private static final String PATH = "/api/catalog-import/setup/revisions/{rid}/filters/{fid}";
    private static final long UNKNOWN = 999_999_999L;
    private static final String VALID_BODY =
            "{\"sourceReference\":\"CULTUUR\",\"operator\":\"EQUALS\",\"compareValue\":\"NL\","
                    + "\"outcome\":\"INCLUDE\"}";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RevisionSuccessorService successors;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportRecordFilterRepository recordFilters;
    @Autowired
    private ImportDefinitionBookmarkRepository bookmarks;

    private MaterialisationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = MaterialisationFixtures.of(context);
    }

    // --- Vervangen ------------------------------------------------------------------------------------

    @Test
    void replacesTheFilterWithANewIdAndTheSameSequenceNumberAndIgnoresASequenceInTheBody() throws Exception {
        Draft draft = draft("RPL");
        ImportRecordFilter old = onlyFilter(draft.id());

        String body = put(draft.id(), old.getId(),
                "{\"sequenceNumber\":77,\"sourceReference\":\"MERK\",\"operator\":\"NOT_EQUALS\","
                        + "\"compareValue\":\"X\",\"outcome\":\"EXCLUDE\",\"caseSensitive\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value(draft.id()))
                .andExpect(jsonPath("$.sequenceNumber").value(old.getSequenceNumber()))
                .andExpect(jsonPath("$.sourceReference").value("MERK"))
                .andExpect(jsonPath("$.operator").value("NOT_EQUALS"))
                .andExpect(jsonPath("$.compareValue").value("X"))
                .andExpect(jsonPath("$.outcome").value("EXCLUDE"))
                .andExpect(jsonPath("$.caseSensitive").value(true))
                .andReturn().getResponse().getContentAsString();

        long newId = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertThat(newId).isNotEqualTo(old.getId());
        assertThat(recordFilters.findById(old.getId())).isEmpty();
        List<ImportRecordFilter> rows = recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(draft.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(newId);
        assertThat(rows.get(0).getSequenceNumber()).isEqualTo(old.getSequenceNumber());
        assertThat(rows.get(0).getCreatedBy()).isEqualTo(USER);
        assertThat(rows.get(0).getCreatedBySubject()).isEqualTo(SUBJECT);
    }

    // --- Matchgroep -----------------------------------------------------------------------------------

    @Test
    void aMatchGroupRuleIsAcceptedAndAnEmptyCompareValueMeansEmptyColumnInAGroup() throws Exception {
        Draft draft = draft("GRP");
        long filterId = onlyFilter(draft.id()).getId();

        put(draft.id(), filterId,
                "{\"sourceReference\":\"CULTUUR\",\"operator\":\"EQUALS\",\"compareValue\":\"\","
                        + "\"outcome\":\"EXCLUDE\",\"matchGroup\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchGroup").value(1))
                .andExpect(jsonPath("$.compareValue").value(""));

        assertThat(onlyFilter(draft.id()).getMatchGroup()).isEqualTo(1);
    }

    @Test
    void invalidMatchGroupRulesAreRefusedAndNothingIsChanged() throws Exception {
        Draft draft = draft("GRPBAD");
        ImportRecordFilter before = onlyFilter(draft.id());

        // Groep moet een positief getal zijn.
        put(draft.id(), before.getId(),
                "{\"sourceReference\":\"CULTUUR\",\"operator\":\"EQUALS\",\"compareValue\":\"NL\","
                        + "\"outcome\":\"EXCLUDE\",\"matchGroup\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATCH_GROUP_INVALID"));
        // Een groepsregel is EXCLUDE (hier INCLUDE).
        put(draft.id(), before.getId(),
                "{\"sourceReference\":\"CULTUUR\",\"operator\":\"EQUALS\",\"compareValue\":\"NL\","
                        + "\"outcome\":\"INCLUDE\",\"matchGroup\":2}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_FILTER_INVALID"));
        // En met COMPARE_AS_EMPTY (hier een andere nullBehaviour).
        put(draft.id(), before.getId(),
                "{\"sourceReference\":\"CULTUUR\",\"operator\":\"EQUALS\",\"compareValue\":\"NL\","
                        + "\"outcome\":\"EXCLUDE\",\"matchGroup\":2,\"nullBehaviour\":\"EXCLUDE\"}")
                .andExpect(status().isBadRequest());

        assertUnchanged(draft.id(), before);
    }

    // --- Bookmarkgebruik ------------------------------------------------------------------------------

    @Test
    void aBookmarkUsageOnTheFilterSequenceStaysValidAfterTheReplacement() throws Exception {
        Draft draft = draft("BMK");
        ImportDefinitionRevision revision = revisions.findById(draft.id()).orElseThrow();
        fixtures.declareOn(revision, "CULTUUR", BookmarkValueScope.DEFINITION, true, BookmarkDataType.TEXT, 1,
                BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE,
                String.valueOf(MaterialisationFixtures.FILTER_SEQUENCE));
        long oldId = onlyFilter(draft.id()).getId();

        put(draft.id(), oldId, VALID_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequenceNumber").value(MaterialisationFixtures.FILTER_SEQUENCE));

        assertThat(bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(draft.id())).hasSize(1);
        assertThat(onlyFilter(draft.id()).getId()).isNotEqualTo(oldId);
    }

    // --- Ongeldig: niets gewijzigd --------------------------------------------------------------------

    @Test
    void invalidFieldsAreRefusedWithTheirCodeAndNothingIsChanged() throws Exception {
        Draft draft = draft("INV");
        ImportRecordFilter before = onlyFilter(draft.id());

        // Ontbrekende bronreferentie, operator of uitkomst, en een lege vergelijkingswaarde buiten een groep.
        put(draft.id(), before.getId(),
                "{\"operator\":\"EQUALS\",\"compareValue\":\"NL\",\"outcome\":\"INCLUDE\"}")
                .andExpect(status().isBadRequest());
        put(draft.id(), before.getId(),
                "{\"sourceReference\":\"CULTUUR\",\"compareValue\":\"NL\",\"outcome\":\"INCLUDE\"}")
                .andExpect(status().isBadRequest());
        put(draft.id(), before.getId(),
                "{\"sourceReference\":\"CULTUUR\",\"operator\":\"EQUALS\",\"compareValue\":\"NL\"}")
                .andExpect(status().isBadRequest());
        put(draft.id(), before.getId(),
                "{\"sourceReference\":\"CULTUUR\",\"operator\":\"EQUALS\",\"compareValue\":\"\","
                        + "\"outcome\":\"INCLUDE\"}")
                .andExpect(status().isBadRequest());

        assertUnchanged(draft.id(), before);
    }

    // --- 404 / 409 ------------------------------------------------------------------------------------

    @Test
    void anUnknownRevisionOrFilterIs404AndAFilterOfAnotherRevisionIs404TooAndNothingIsChanged() throws Exception {
        Draft draft = draft("NF");
        ImportRecordFilter before = onlyFilter(draft.id());
        long foreignFilterId = onlyFilter(draft.sourceRevisionId()).getId();

        put(UNKNOWN, before.getId(), VALID_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        put(draft.id(), UNKNOWN, VALID_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILTER_NOT_FOUND"));
        put(draft.id(), foreignFilterId, VALID_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILTER_NOT_FOUND"));

        assertUnchanged(draft.id(), before);
        assertThat(recordFilters.findById(foreignFilterId)).isPresent();
    }

    @Test
    void aNonDraftRevisionIs409NotEditableAndNothingIsChanged() throws Exception {
        Draft draft = draft("ACT");
        ImportRecordFilter active = onlyFilter(draft.sourceRevisionId());

        put(draft.sourceRevisionId(), active.getId(), VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));

        assertUnchanged(draft.sourceRevisionId(), active);
    }

    // --- Gelijktijdigheid -----------------------------------------------------------------------------

    /**
     * Twee gelijktijdige PUT's op hetzelfde filter. De uitkomst hangt van de timing af; de assertie is: nooit een 500,
     * elke 409 is {@code REVISION_EDIT_CONFLICT} (een 404 {@code FILTER_NOT_FOUND} is de eerlijke uitkomst van wie na
     * de eerste commit start), minstens één 200, en er blijft precies één filter met het oorspronkelijke volgnummer.
     */
    @Test
    void concurrentPutsOnTheSameFilterGive200OrRevisionEditConflictNeverA500() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                Draft draft = draft("RACE" + round);
                ImportRecordFilter before = onlyFilter(draft.id());
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (String value : List.of("NL", "FR")) {
                    results.add(pool.submit(() -> {
                        go.await();
                        var response = put(draft.id(), before.getId(),
                                "{\"sourceReference\":\"CULTUUR\",\"operator\":\"EQUALS\",\"compareValue\":\""
                                        + value + "\",\"outcome\":\"INCLUDE\"}")
                                .andReturn().getResponse();
                        String code = response.getStatus() == 200 ? null
                                : JsonPath.read(response.getContentAsString(), "$.code");
                        if (response.getStatus() == 409) {
                            assertThat(code).isEqualTo("REVISION_EDIT_CONFLICT");
                        } else if (response.getStatus() == 404) {
                            assertThat(code).isEqualTo("FILTER_NOT_FOUND");
                        }
                        return response.getStatus();
                    }));
                }
                go.countDown();
                int ok = 0;
                for (Future<Integer> result : results) {
                    int code = result.get(30, TimeUnit.SECONDS);
                    assertThat(code).isIn(200, 404, 409);
                    if (code == 200) {
                        ok++;
                    }
                }
                assertThat(ok).isGreaterThanOrEqualTo(1);
                ImportRecordFilter after = onlyFilter(draft.id());
                assertThat(after.getSequenceNumber()).isEqualTo(before.getSequenceNumber());
                assertThat(after.getCompareValue()).isIn("NL", "FR");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Helpers --------------------------------------------------------------------------------------

    private record Draft(long id, long sourceRevisionId) {
    }

    /** Een ACTIVE revisie met één mapping en één filter, en daarvan een DRAFT-opvolger (zoals RevisionUpdateTest). */
    private Draft draft(String prefix) {
        MaterialisationFixtures.Template source =
                fixtures.template(prefix, DefinitionUsageType.OWN_DEFINITION, RevisionStatus.ACTIVE);
        RevisionView successor = successors.createSuccessor(source.revision().getId(),
                "Filter vervangen (" + prefix + ")", new ActorIdentity(USER, SUBJECT));
        return new Draft(successor.id(), source.revision().getId());
    }

    private ImportRecordFilter onlyFilter(long revisionId) {
        List<ImportRecordFilter> rows = recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** Dezelfde rij (id, volgnummer, inhoud) staat er nog: er is niets verwijderd of herschreven. */
    private void assertUnchanged(long revisionId, ImportRecordFilter before) {
        ImportRecordFilter now = onlyFilter(revisionId);
        assertThat(now.getId()).isEqualTo(before.getId());
        assertThat(now.getSequenceNumber()).isEqualTo(before.getSequenceNumber());
        assertThat(now.getSourceReference()).isEqualTo(before.getSourceReference());
        assertThat(now.getOperator()).isEqualTo(before.getOperator());
        assertThat(now.getCompareValue()).isEqualTo(before.getCompareValue());
        assertThat(now.getOutcome()).isEqualTo(before.getOutcome());
        assertThat(now.getMatchGroup()).isEqualTo(before.getMatchGroup());
    }

    private ResultActions put(long revisionId, long filterId, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put(PATH, revisionId, filterId)
                .with(TestActors.as(USER)).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
