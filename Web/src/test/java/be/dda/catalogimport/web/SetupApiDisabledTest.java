package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * De setup-API is een ontwikkelhulp zonder authenticatie en moet daarom <b>standaard uit</b> staan:
 * zonder {@code catalogimport.setup-api.enabled=true} bestaat de controller niet en antwoordt elk
 * setup-pad met 404.
 * <p>
 * Dit is geen vormelijkheid. Wie deze endpoints bereikt, kan een importdefinitie en haar drempels
 * bepalen en zo de controle op een leverancierscatalogus uitschakelen. Zolang Fase 5 (authenticatie)
 * er niet is, is "standaard uit" de enige verdediging — en die wordt hier bewezen.
 * <p>
 * De bestaande upload- en batchendpoints blijven wél gewoon bestaan; de vlag raakt alleen de setup-API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupApiDisabledTest {

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext context;

    @Test
    void theSetupControllerDoesNotExistWithoutTheExplicitFlag() {
        assertThat(context.getBeanNamesForType(CatalogImportSetupController.class)).isEmpty();
    }

    @Test
    void everySetupPathAnswers404WhenTheFlagIsNotSet() throws Exception {
        mockMvc.perform(get("/api/catalog-import/setup/overview"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/catalog-import/setup/source-organisations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X\",\"name\":\"X\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/catalog-import/setup/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"X\",\"code\":\"X\",\"name\":\"X\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/catalog-import/setup/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":1,\"code\":\"X\",\"name\":\"X\",\"supplierCode\":\"X\","
                                + "\"libraryCode\":\"X\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/catalog-import/setup/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"linkId\":1,\"name\":\"X\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theExistingEndpointsKeepAnsweringTheirOwnCodes() throws Exception {
        // Geen 404 op het pad zelf maar de gewone, bestaande foutcode: de vlag raakt niets anders.
        mockMvc.perform(get("/api/catalog-import/deliveries/{id}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("DELIVERY_NOT_FOUND"));
    }
}
