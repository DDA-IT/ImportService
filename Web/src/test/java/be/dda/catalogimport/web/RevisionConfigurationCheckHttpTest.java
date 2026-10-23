package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * EB4 (beslissingslog 2026-10-23 "Editor-API-gaten: ontwerp EB1-EB5"):
 * {@code GET /definitions/{d}/revisions/{r}/configuration-check}, de activatiecontrole van een revisie zonder bestand.
 *
 * <ul>
 *   <li><b>Regel (pariteit):</b> de eerste PROBLEM-code is de code die {@code activate} geeft en {@code activatable}
 *       is {@code true} precies wanneer activeren slaagt. <b>Implementatie:</b> dezelfde
 *       {@code ChainConfigurationChecks} (valuta {@code null}, zoals de activatie); bewezen per toestand hieronder.</li>
 *   <li><b>Regel:</b> alleen-lezen en geen koppeling nodig; controles per koppeling blijven in de gereedheidscontrole.</li>
 *   <li><b>Regel:</b> {@code READ} volstaat; zonder recht 403; onbekende definitie/revisie (ook van een andere
 *       definitie) 404.</li>
 * </ul>
 * Elke test bouwt een eigen definitie met unieke codes: de database is gedeeld.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RevisionConfigurationCheckHttpTest {

    private static final String API = "/api/catalog-import";
    private static final String USER = "an.janssens@example.test";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /** Uniek per testrun: het {@code local}-profiel draait op een gedeelde PostgreSQL. */
    private static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36).toUpperCase();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceOrganisationRepository organisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportDefinitionBookmarkRepository bookmarks;

    // --- Pariteit met activeren, per toestand van het concept ----------------------------------------------------------

    @Test
    void aValidDraftIsActivatableAndActivationSucceeds() throws Exception {
        ImportDefinition definition = definition("VALID", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision draft = revision(definition, 1, RevisionStatus.DRAFT);

        String body = check(definition, draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value(draft.getId()))
                .andExpect(jsonPath("$.revisionStatus").value("DRAFT"))
                .andExpect(jsonPath("$.activatable").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).isEmpty();
        assertThat(codes(body, "OK")).containsExactly("READY_DRAFT_ACTIVATABLE");
        assertThat(codes(body, "INFO")).isEmpty();
        assertThat(((Number) JsonPath.read(body, "$.checks[0].subject.id")).longValue()).isEqualTo(draft.getId());
        assertThat((String) JsonPath.read(body, "$.checks[0].subject.type")).isEqualTo("REVISION");

        // Herhaalbaar en zonder schrijven: zelfde antwoord, het concept blijft DRAFT.
        assertThat(check(definition, draft).andReturn().getResponse().getContentAsString()).isEqualTo(body);
        assertThat(statusOf(draft)).isEqualTo(RevisionStatus.DRAFT);

        activate(draft).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void aDraftWithOneConfigErrorIsNotActivatableAndCarriesTheCodeOfTheActivation400() throws Exception {
        ImportDefinition definition = definition("ONE", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision draft = revision(definition, 1, RevisionStatus.DRAFT);
        draft.setRecordBasePriceField(null);
        draft = revisions.saveAndFlush(draft);

        String body = check(definition, draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.activatable").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).containsExactly("CONFIG_PRICE_FIELD_MISSING");
        assertThat(codes(body, "OK")).isEmpty();
        assertThat((String) JsonPath.read(body, "$.checks[0].detail")).startsWith("CONFIG_PRICE_FIELD_MISSING: ");

        activate(draft).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_PRICE_FIELD_MISSING"))
                .andExpect(jsonPath("$.error").value(JsonPath.read(body, "$.checks[0].detail")));
        assertThat(statusOf(draft)).isEqualTo(RevisionStatus.DRAFT);
    }

    @Test
    void aDraftWithSeveralConfigErrorsListsThemAllAndTheFirstEqualsTheActivation400() throws Exception {
        ImportDefinition definition = definition("MANY", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision draft = revision(definition, 1, RevisionStatus.DRAFT);
        draft.setIdentitySupplierField(" ");
        draft.setIdentitySupplierGroupField(" ");
        draft.setRecordBasePriceField(null);
        draft = revisions.saveAndFlush(draft);
        declare(draft, "PRIJSBASIS", BookmarkValueScope.DEFINITION, true);

        String body = check(definition, draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.activatable").value(false))
                .andReturn().getResponse().getContentAsString();

        // Alle configuratiefouten in de volgorde van de fabriek, daarna de DEFINITION-bookmark.
        assertThat(codes(body, "PROBLEM")).containsExactly("CONFIG_IDENTITY_FIELD_MISSING",
                "CONFIG_IDENTITY_FIELD_MISSING", "CONFIG_PRICE_FIELD_MISSING", "CONFIG_REQUIRED_BOOKMARK_MISSING");
        assertThat(codes(body, "OK")).doesNotContain("READY_DRAFT_ACTIVATABLE");
        List<String> details = JsonPath.read(body, "$.checks[?(@.status == 'PROBLEM')].detail");
        assertThat(details.get(0)).isNotEqualTo(details.get(1));

        // De 400 van de activatie is de eerste rij: zelfde code, zelfde tekst.
        activate(draft).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_IDENTITY_FIELD_MISSING"))
                .andExpect(jsonPath("$.error").value(details.get(0)));
        assertThat(statusOf(draft)).isEqualTo(RevisionStatus.DRAFT);
    }

    @Test
    void aDraftWithASkippedCheckReportsItAsInformationAndIsNotActivatable() throws Exception {
        ImportDefinition definition = definition("SKIP", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision draft = revision(definition, 1, RevisionStatus.DRAFT);
        draft.setStructureDelimiter("");
        draft.setStructureQuoteChar("'");
        draft.setRecordBasePriceField(null);
        draft = revisions.saveAndFlush(draft);

        String body = check(definition, draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.activatable").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).contains("CONFIG_DELIMITER_MISSING", "CONFIG_PRICE_FIELD_MISSING");
        assertThat(codes(body, "INFO")).containsExactly("INFO_CONFIG_CHECKS_SKIPPED");
        assertThat((String) JsonPath.read(body,
                "$.checks[?(@.code == 'INFO_CONFIG_CHECKS_SKIPPED')].detail[0]"))
                .startsWith("Checks depending on [").contains("CONFIG_DELIMITER_MISSING")
                .endsWith("] were not evaluated");
        List<String> skipped = JsonPath.read(body,
                "$.checks[?(@.code == 'INFO_CONFIG_CHECKS_SKIPPED')].skippedBecause[0]");
        assertThat(skipped).contains("CONFIG_DELIMITER_MISSING");
        // De INFO-rij telt niet mee voor activatable en staat na de PROBLEM-rijen.
        List<String> all = JsonPath.read(body, "$.checks[*].code");
        assertThat(all.get(all.size() - 1)).isEqualTo("INFO_CONFIG_CHECKS_SKIPPED");

        activate(draft).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(codes(body, "PROBLEM").get(0)));
    }

    @Test
    void aMissingRequiredDefinitionBookmarkBlocksWithTheCodeOfTheActivation409() throws Exception {
        ImportDefinition definition = definition("BMK", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision draft = revision(definition, 1, RevisionStatus.DRAFT);
        declare(draft, "PRIJSBASIS", BookmarkValueScope.DEFINITION, true);
        // Een verplichte LINK-bookmark hoort bij de gereedheidscontrole van een koppeling, niet hier.
        declare(draft, "CULTUUR", BookmarkValueScope.LINK, true);

        String body = check(definition, draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.activatable").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).containsExactly("CONFIG_REQUIRED_BOOKMARK_MISSING");
        assertThat((String) JsonPath.read(body, "$.checks[0].detail")).contains("PRIJSBASIS")
                .doesNotContain("CULTUUR");

        activate(draft).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_REQUIRED_BOOKMARK_MISSING"))
                .andExpect(jsonPath("$.error").value(JsonPath.read(body, "$.checks[0].detail")));
        assertThat(statusOf(draft)).isEqualTo(RevisionStatus.DRAFT);
    }

    @Test
    void anActiveRevisionIsNotActivatableAndTheActivationGivesTheSameConflict() throws Exception {
        ImportDefinition definition = definition("ACT", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision active = revision(definition, 1, RevisionStatus.ACTIVE);

        String body = check(definition, active).andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.activatable").value(false))
                .andReturn().getResponse().getContentAsString();

        // Enkel de statusbevinding: de activatie stopt daar ook, de configuratie wordt niet beoordeeld.
        assertThat(codes(body, "PROBLEM")).containsExactly("REVISION_NOT_ACTIVATABLE");
        assertThat(codes(body, "OK")).isEmpty();
        assertThat(((Number) JsonPath.read(body, "$.checks[0].subject.id")).longValue()).isEqualTo(active.getId());

        activate(active).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_ACTIVATABLE"))
                .andExpect(jsonPath("$.error").value(JsonPath.read(body, "$.checks[0].detail")));
    }

    @Test
    void aSupersededRevisionIsNotActivatableEvenWhenItsConfigurationIsBroken() throws Exception {
        ImportDefinition definition = definition("SUP", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision superseded = revision(definition, 1, RevisionStatus.SUPERSEDED);
        superseded.setRecordBasePriceField(null);
        superseded = revisions.saveAndFlush(superseded);

        String body = check(definition, superseded).andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionStatus").value("SUPERSEDED"))
                .andExpect(jsonPath("$.activatable").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).containsExactly("REVISION_NOT_ACTIVATABLE");
        activate(superseded).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_ACTIVATABLE"))
                .andExpect(jsonPath("$.error").value(JsonPath.read(body, "$.checks[0].detail")));
    }

    // --- Definitie zonder koppeling, sjabloon en opvolger ----------------------------------------------------------------

    @Test
    void aReusableTemplateDraftWithoutLinkSkipsTheDefinitionBookmarkCheckLikeTheActivation() throws Exception {
        ImportDefinition definition = definition("TPL", DefinitionUsageType.REUSABLE_TEMPLATE);
        ImportDefinitionRevision draft = revision(definition, 1, RevisionStatus.DRAFT);
        declare(draft, "PRIJSBASIS", BookmarkValueScope.DEFINITION, true);

        String body = check(definition, draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.activatable").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).isEmpty();
        assertThat(codes(body, "OK")).containsExactly("READY_DRAFT_ACTIVATABLE");

        activate(draft).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void aSuccessorDraftNextToAnActiveVersionIsCheckedOnItsOwnAndStaysActivatable() throws Exception {
        ImportDefinition definition = definition("SUCC", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision active = revision(definition, 1, RevisionStatus.ACTIVE);
        ImportDefinitionRevision draft = revision(definition, 2, RevisionStatus.DRAFT);

        check(definition, draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value(draft.getId()))
                .andExpect(jsonPath("$.activatable").value(true))
                .andExpect(jsonPath("$.checks[0].code").value("READY_DRAFT_ACTIVATABLE"));
        check(definition, active).andExpect(status().isOk())
                .andExpect(jsonPath("$.activatable").value(false))
                .andExpect(jsonPath("$.checks[0].code").value("REVISION_NOT_ACTIVATABLE"));

        // Het opvolgerconcept laat zich activeren zoals voorspeld; de vorige versie wordt dan SUPERSEDED.
        activate(draft).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(statusOf(active)).isEqualTo(RevisionStatus.SUPERSEDED);
        check(definition, active).andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionStatus").value("SUPERSEDED"))
                .andExpect(jsonPath("$.activatable").value(false))
                .andExpect(jsonPath("$.checks[0].code").value("REVISION_NOT_ACTIVATABLE"));
    }

    @Test
    void aBrokenSuccessorDraftDoesNotAffectTheActiveVersionAndIsReportedOnItsOwn() throws Exception {
        ImportDefinition definition = definition("SUCCBAD", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision active = revision(definition, 1, RevisionStatus.ACTIVE);
        ImportDefinitionRevision draft = revision(definition, 2, RevisionStatus.DRAFT);
        draft.setRecordBasePriceField(null);
        draft = revisions.saveAndFlush(draft);

        String body = check(definition, draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.activatable").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).containsExactly("CONFIG_PRICE_FIELD_MISSING");
        activate(draft).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_PRICE_FIELD_MISSING"));
        assertThat(statusOf(active)).isEqualTo(RevisionStatus.ACTIVE);
        assertThat(statusOf(draft)).isEqualTo(RevisionStatus.DRAFT);
    }

    // --- Onbekend en rechten ---------------------------------------------------------------------------------------------

    @Test
    void anUnknownDefinitionOrRevisionIs404AndSoIsARevisionOfAnotherDefinition() throws Exception {
        ImportDefinition definition = definition("NF-A", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinition other = definition("NF-B", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision draft = revision(definition, 1, RevisionStatus.DRAFT);

        mockMvc.perform(get(API + "/definitions/{d}/revisions/{r}/configuration-check", 999_999_999L, draft.getId())
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEFINITION_NOT_FOUND"));
        mockMvc.perform(get(API + "/definitions/{d}/revisions/{r}/configuration-check", definition.getId(),
                        999_999_999L).with(as(USER, Permission.READ)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        mockMvc.perform(get(API + "/definitions/{d}/revisions/{r}/configuration-check", other.getId(), draft.getId())
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        assertThat(statusOf(draft)).isEqualTo(RevisionStatus.DRAFT);
    }

    @Test
    void readIsEnoughAndWithoutRightsIs403() throws Exception {
        ImportDefinition definition = definition("PERM", DefinitionUsageType.OWN_DEFINITION);
        ImportDefinitionRevision draft = revision(definition, 1, RevisionStatus.DRAFT);

        mockMvc.perform(get(API + "/definitions/{d}/revisions/{r}/configuration-check", definition.getId(),
                        draft.getId()).with(as(USER, Permission.READ)))
                .andExpect(status().isOk());
        mockMvc.perform(get(API + "/definitions/{d}/revisions/{r}/configuration-check", definition.getId(),
                        draft.getId()).with(withoutPermissions(USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    // --- Helpers -----------------------------------------------------------------------------------------------------------

    private ResultActions check(ImportDefinition definition, ImportDefinitionRevision revision) throws Exception {
        return mockMvc.perform(get(API + "/definitions/{d}/revisions/{r}/configuration-check", definition.getId(),
                revision.getId()).with(as(USER, Permission.READ)));
    }

    private ResultActions activate(ImportDefinitionRevision revision) throws Exception {
        return mockMvc.perform(post(API + "/setup/revisions/{id}/activate", revision.getId()).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approvedBy\":\"" + USER + "\"}"));
    }

    private RevisionStatus statusOf(ImportDefinitionRevision revision) {
        return revisions.findById(revision.getId()).orElseThrow().getStatus();
    }

    /** De codes van de checks met de gegeven status, in antwoordvolgorde. */
    private static List<String> codes(String body, String status) {
        return JsonPath.read(body, "$.checks[?(@.status == '" + status + "')].code");
    }

    private ImportDefinitionBookmark declare(ImportDefinitionRevision revision, String name, BookmarkValueScope scope,
                                             boolean required) {
        ImportDefinitionBookmark bookmark = new ImportDefinitionBookmark(revision, name, name.toLowerCase(),
                BookmarkDataType.TEXT, scope, "catalogImport.manage", SEQUENCE.incrementAndGet());
        bookmark.setRequired(required);
        return bookmarks.saveAndFlush(bookmark);
    }

    private ImportDefinition definition(String prefix, DefinitionUsageType usageType) {
        String unique = unique("CC") + "-" + prefix;
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", USER);
        definition.setUsageType(usageType);
        return definitions.saveAndFlush(definition);
    }

    /** Een geldige revisie (alle configuratiecontroles slagen) met unieke hashes per revisienummer. */
    private ImportDefinitionRevision revision(ImportDefinition definition, int number, RevisionStatus status) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, number,
                IdentityProfileKind.THREE_PART, USER);
        String digit = Integer.toString(number);
        revision.setAccessConfigHash(digit.repeat(64).substring(0, 64));
        revision.setStructureConfigHash("2".repeat(63) + digit);
        revision.setRecordRulesConfigHash("3".repeat(63) + digit);
        revision.setCompositeConfigHash("4".repeat(63) + digit);
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setStatus(status);
        return revisions.saveAndFlush(revision);
    }

    private static String unique(String prefix) {
        return prefix + RUN + SEQUENCE.incrementAndGet();
    }
}
