package be.dda.catalogimport.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Het actuatorcontract van S7-c (docs/decisions.md 2026-10-02): anoniem enkel {@code /actuator/health} en de twee
 * probes, zonder {@code components}/{@code details}; elk ander actuatorpad (env, beans, heapdump, info, ...) is niet
 * bereikbaar. Zonder login geeft dat 302 naar de login: die paden zijn niet vrijgegeven, en ook mét login bestaan ze
 * niet (access.default none, enkel health blootgesteld). Draait tegen de lokale PostgreSQL (db-indicator in readiness).
 */
@SpringBootTest(properties = "catalogimport.screening.recovery-on-startup=false")
@ActiveProfiles("local")
class ActuatorHealthHttpTest {

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc anonymous;

    @BeforeEach
    void setUp() {
        anonymous = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void healthIsOkAnonymouslyAndExposesOnlyTheStatus() throws Exception {
        String body = anonymous.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("status").asText()).isEqualTo("UP");
        assertThat(json.has("components")).isFalse();
        assertThat(json.has("details")).isFalse();
        // Boot voegt naast status enkel de NAMEN van de gezondheidsgroepen toe ("groups"), nooit inhoud.
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("status", "groups");
        assertThat(json.get("groups")).extracting(JsonNode::asText).containsExactlyInAnyOrder("liveness", "readiness");
    }

    @Test
    void livenessAndReadinessAreOkAnonymouslyWithoutDetails() throws Exception {
        for (String path : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            String body = anonymous.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode json = objectMapper.readTree(body);
            assertThat(json.get("status").asText()).as(path).isEqualTo("UP");
            assertThat(json.has("components")).as(path).isFalse();
            assertThat(json.has("details")).as(path).isFalse();
        }
    }

    @Test
    void everyOtherActuatorPathIsNotReachableAnonymously() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/info",
                "/actuator/metrics", "/actuator/loggers", "/actuator/mappings", "/actuator/health/db",
                "/actuator/health/diskSpace", "/actuator"}) {
            anonymous.perform(get(path))
                    .andExpect(status().isFound())
                    .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/oauth2/authorization/keycloak")));
        }
    }
}
