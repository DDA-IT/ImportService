package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.LocalSourceDirectory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Zonder {@code catalogimport.local-source.directory} <b>bestaat de tweede ontvangstweg niet</b>
 * (bindend ontwerp {@code docs/decisions.md} 2026-09-27, D2): beide endpoints antwoorden 404
 * {@code LOCAL_SOURCE_NOT_CONFIGURED}, zelfde patroon als de setup-vlag in {@code SetupApiDisabledTest}.
 * <p>
 * Dit is geen vormelijkheid. Een omgeving die geen aanleverdirectory heeft, mag er ook geen inhoud van
 * kunnen laten zien: het lijst-endpoint onthult serverdirectory-inhoud. "Standaard uit, geen default" is
 * hier de beveiliging, en die wordt hier bewezen — inclusief dat de bestaande browser-upload er niets van
 * merkt (de eerste ontvangstweg blijft ongewijzigd werken).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalSourceDisabledTest {

    private static final String API = "/api/catalog-import";

    @TempDir
    static Path archiveRoot;

    /** Bewust leeg: dat is precies hoe een omgeving zonder aanleverdirectory eruitziet. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LocalSourceDirectory localSource;

    @Test
    void theDirectoryIsNotConfigured() {
        assertThat(localSource.configured()).isFalse();
    }

    @Test
    void theListingAnswers404NotConfigured() throws Exception {
        mockMvc.perform(get(API + "/local-source/files"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCAL_SOURCE_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void readingADeliveryAnswers404NotConfigured() throws Exception {
        mockMvc.perform(post(API + "/tasks/{id}/deliveries/local-source", 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"levering.csv\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCAL_SOURCE_NOT_CONFIGURED"));
    }

    /** Een ongeldige naam blijft een ongeldige naam: de aanvraag zelf wordt eerst beoordeeld. */
    @Test
    void anInvalidFileNameStaysA400EvenWhenTheDirectoryIsNotConfigured() throws Exception {
        mockMvc.perform(post(API + "/tasks/{id}/deliveries/local-source", 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"../secret.csv\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCAL_SOURCE_FILE_NAME_INVALID"));
    }

    /** De eerste ontvangstweg blijft ongemoeid: de browser-upload geeft haar eigen, bestaande foutcode. */
    @Test
    void theExistingUploadKeepsAnsweringItsOwnCodes() throws Exception {
        mockMvc.perform(multipart(API + "/tasks/{id}/deliveries", 999_999_999L)
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                "LEVERANCIER;GROEP;REFERENTIE;PRIJS\n".getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", "REF-DISABLED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }
}
