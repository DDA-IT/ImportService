package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.testsupport.TestSecurityConfiguration;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap 5B-4: {@code GET /me} moet 503 {@code PERMISSION_SOURCE_UNAVAILABLE} geven als
 * de rechtenbron onbereikbaar is, nooit 200 met een lege lijst.
 * <p>
 * Aparte testklasse, omdat we een PermissionSource willen injecteren die een exception gooit.
 * De {@link TestConfiguration} definieert een @Primary bean die {@link TestActors}-login met
 * rechten zou onderdrukken, dus we gebruiken hier geen as() maar een direct-ingestelde login
 * met een lege claim (of we gebruiken Mockito).
 */
@SpringBootTest(properties = {
        "catalogimport.screening.recovery-on-startup=false",
        // Alleen in deze testklasse: de @Bean "testPermissionSource" vervangt de gescande TestPermissionSource.
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MePermissionSourceUnavailableHttpTest {

    private static final String ME = "/api/catalog-import/me";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class UnavailablePermissionSourceConfiguration {

        /**
         * Een {@link PermissionSource} die altijd een exception gooit. De bean draagt bewust dezelfde naam als de
         * gescande {@link be.dda.catalogimport.testsupport.TestPermissionSource} ("testPermissionSource"): een
         * {@code @Bean}-methode vervangt een gescande component met dezelfde naam. Zo blijft er precies één
         * {@code @Primary}-bron over (twee gaf NoUniqueBeanDefinitionException).
         */
        @Bean(name = "testPermissionSource")
        @Primary
        PermissionSource unavailablePermissionSource() {
            return identity -> {
                throw new PermissionSourceUnavailableException("Permission source is unavailable for testing");
            };
        }
    }

    /**
     * {@code GET /me} is {@code @NoPermissionRequired}, maar de exception moet nog steeds doorgaan.
     * Dit bewezen: 503 en de code "PERMISSION_SOURCE_UNAVAILABLE", niet 200 met `[]`.
     */
    @Test
    void meWith503WhenPermissionSourceIsUnavailable() throws Exception {
        mockMvc.perform(get(ME).with(as("unavailable.test@example.test")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERMISSION_SOURCE_UNAVAILABLE"));
    }

}
