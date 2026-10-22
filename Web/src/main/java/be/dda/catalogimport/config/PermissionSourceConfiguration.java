package be.dda.catalogimport.config;

import be.dda.catalogimport.web.ConfiguredPermissionSource;
import be.dda.catalogimport.web.PermissionProperties;
import be.dda.catalogimport.web.PermissionSource;
import be.dda.catalogimport.web.ProdisPermissionSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * De bronkeuze voor rechten (5B-7, {@code docs/design/fase5-perm-5b7-design.md} par. 3; beslissingslog 2026-10-22):
 * {@code catalogimport.permissions.source} = {@code CONFIGURED} of {@code PRODIS} maakt <b>precies één</b>
 * {@link PermissionSource}-bean. YAML en Prodis worden nooit samengevoegd.
 * <ul>
 *   <li>Een onbekende of lege waarde laat de applicatie niet starten (strikte binding in
 *       {@link PermissionProperties#setSource(String)}).</li>
 *   <li>{@code PRODIS} zonder {@code catalogimport.permissions.prodis.account-url} laat de applicatie niet starten.</li>
 *   <li>{@code PRODIS} met niet-lege {@code grants}: één WARN "grants genegeerd" (zonder usernames); de toekenningen
 *       worden dan niet gelezen en niet gevalideerd.</li>
 * </ul>
 * Eén {@code @Bean}-methode in plaats van twee voorwaardelijke componenten: zo kan er nooit nul of twee bronnen zijn,
 * en faalt een onbekende waarde op de binding met de propertynaam in de fout. De Prodis-afhankelijkheden
 * (OAuth2-repositories, {@link ObjectMapper}, {@link Clock}) worden enkel bij {@code PRODIS} opgehaald.
 * <p>
 * In tests wint de {@code @Primary TestPermissionSource}; deze bean wordt dan nog steeds gebouwd en gevalideerd.
 */
@Configuration(proxyBeanMethods = false)
public class PermissionSourceConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionSourceConfiguration.class);

    @Bean
    PermissionSource permissionSource(PermissionProperties properties,
                                      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                      ObjectProvider<OAuth2AuthorizedClientRepository> authorizedClients,
                                      ObjectProvider<ObjectMapper> objectMapper,
                                      ObjectProvider<Clock> clock) {
        return switch (properties.source()) {
            case CONFIGURED -> {
                LOG.info("Permission source: CONFIGURED ({}.grants)", PermissionProperties.PREFIX);
                yield new ConfiguredPermissionSource(properties);
            }
            case PRODIS -> {
                if (!properties.getGrants().isEmpty()) {
                    LOG.warn("{}.source=PRODIS: the {} entries under {}.grants are ignored (permissions come only "
                                    + "from Prodis; YAML and Prodis are never combined)", PermissionProperties.PREFIX,
                            properties.getGrants().size(), PermissionProperties.PREFIX);
                }
                ProdisPermissionSource source = new ProdisPermissionSource(properties.getProdis(), clientRegistrations,
                        authorizedClients.getObject(), objectMapper.getObject(), clock.getObject());
                LOG.info("Permission source: PRODIS (GET /api/account, session cache {})",
                        properties.getProdis().getCacheTtl());
                yield source;
            }
        };
    }
}
