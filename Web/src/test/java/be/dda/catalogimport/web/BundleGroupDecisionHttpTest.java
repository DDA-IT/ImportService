package be.dda.catalogimport.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.BundleCancellationService;
import be.dda.catalogimport.service.BundleDecisionService;
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.BundleDecisionService.GroupDecisionView;
import be.dda.catalogimport.service.BundleFreezeService;
import be.dda.catalogimport.service.BundleQueryService;
import be.dda.catalogimport.service.PublicationBundleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Het HTTP-contract van de groepsactie {@code POST /bundles/{id}/decisions} (bouwstap 4d), zonder
 * Spring-context: enkel de controller, de bestaande {@link ApiExceptionHandler} en een gemokte service.
 * <p>
 * Twee dingen worden hier bewezen die {@code BundleGroupDecisionTest} (die op serviceniveau werkt) niet
 * kan aantonen: dat de body correct op de getypeerde filter bindt, en dat de <b>nieuwe</b> 400 met
 * stabiele code {@code DECISION_FILTER_REQUIRED} er ook echt uitkomt — {@link BadRequestException} erft
 * van {@link IllegalArgumentException}, waarvoor al een handler zonder code bestond. Zou Spring de
 * bredere handler kiezen, dan zou de code stilzwijgend uit het antwoord verdwijnen.
 */
class BundleGroupDecisionHttpTest {

    private static final long BUNDLE_ID = 42L;

    private BundleDecisionService decisionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        decisionService = Mockito.mock(BundleDecisionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CatalogImportBundleController(Mockito.mock(PublicationBundleService.class),
                        decisionService, Mockito.mock(BundleFreezeService.class),
                        Mockito.mock(BundleCancellationService.class), Mockito.mock(BundleQueryService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void theRequestBodyBindsToTheTypedFilterAndTheResultIsReturned() throws Exception {
        Mockito.when(decisionService.decideGroup(eq(BUNDLE_ID), eq(BundleDecisionKind.APPROVE),
                        eq("an.janssens@example.test"), eq("Nagekeken"),
                        eq(new DecisionFilter(7L, MutationStatus.AWAITING_APPROVAL, "BULK_PRICE_INCIDENT",
                                MutationActionType.UPDATE))))
                .thenReturn(new GroupDecisionView(3L, 25L,
                        "batchId=7;status=AWAITING_APPROVAL;statusReason=BULK_PRICE_INCIDENT;actionType=UPDATE"));

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/decisions", BUNDLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decisionKind":"APPROVE","decidedBy":"an.janssens@example.test",
                                 "reason":"Nagekeken",
                                 "filter":{"batchId":7,"status":"AWAITING_APPROVAL",
                                           "statusReason":"BULK_PRICE_INCIDENT","actionType":"UPDATE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionId").value(3))
                .andExpect(jsonPath("$.affectedCount").value(25))
                .andExpect(jsonPath("$.selectionFilter")
                        .value("batchId=7;status=AWAITING_APPROVAL;statusReason=BULK_PRICE_INCIDENT;"
                                + "actionType=UPDATE"));
    }

    /** Niets geraakt: een antwoord zonder beslissingsregel, geen fout. */
    @Test
    void anActionThatAffectedNothingAnswersWithoutADecisionId() throws Exception {
        Mockito.when(decisionService.decideGroup(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any())).thenReturn(new GroupDecisionView(null, 0L, "batchId=7"));

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/decisions", BUNDLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionKind\":\"APPROVE\",\"decidedBy\":\"an\",\"filter\":{\"batchId\":7}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionId").doesNotExist())
                .andExpect(jsonPath("$.affectedCount").value(0));
    }

    @Test
    void anEmptyFilterAnswersFourHundredWithTheStableCode() throws Exception {
        Mockito.when(decisionService.decideGroup(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any()))
                .thenThrow(new BadRequestException(BundleDecisionService.CODE_DECISION_FILTER_REQUIRED,
                        "A group decision requires at least one filter field"));

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/decisions", BUNDLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionKind\":\"APPROVE\",\"decidedBy\":\"an\",\"filter\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DECISION_FILTER_REQUIRED"))
                .andExpect(jsonPath("$.error").value("A group decision requires at least one filter field"));
    }

    /** Het bestaande 400-antwoord zonder code blijft exact zoals het was. */
    @Test
    void anOrdinaryIllegalArgumentStillAnswersFourHundredWithoutACode() throws Exception {
        Mockito.when(decisionService.decideGroup(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any())).thenThrow(new IllegalArgumentException("Missing decidedBy"));

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/decisions", BUNDLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionKind\":\"APPROVE\",\"filter\":{\"batchId\":7}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing decidedBy"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }
}
