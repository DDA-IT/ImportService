package be.dda.catalogimport.config;

import be.dda.catalogimport.web.CurrentActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registreert {@link PermissionInterceptor} (Fase 5-PERM, ontwerp par. 3).
 * <p>
 * Enkel op {@code /api/**}: dat is exact het gebied dat {@code SecurityConfiguration} als
 * geauthenticeerde JSON-API behandelt. {@code /actuator/health} en de probes {@code /actuator/health/liveness|readiness}
 * zijn daar publiek en blijven dat; een rechtencheck erop zou ze onbruikbaar maken voor een load balancer.
 * <p>
 * Bewust <b>geen</b> {@code @EnableWebMvc}: die zou de Boot-autoconfiguratie van Spring MVC uitschakelen
 * en daarmee bestaande conventies (Jackson, foutafhandeling, multipart) stilzwijgend wijzigen. Een kale
 * {@link WebMvcConfigurer} vult de autoconfiguratie enkel aan.
 * <p>
 * {@link CurrentActor} wordt via een {@link ObjectProvider} opgehaald: {@code WebMvcConfigurer}-beans
 * worden vroeg aangemaakt, en een rechtstreekse constructorafhankelijkheid zou {@code CurrentActor} en
 * zijn {@code PermissionSource} mee naar voren trekken vóór de gewone beanfase.
 */
@Configuration
public class PermissionWebConfiguration implements WebMvcConfigurer {

    static final String API_PATTERN = "/api/**";

    private final ObjectProvider<CurrentActor> currentActor;

    public PermissionWebConfiguration(ObjectProvider<CurrentActor> currentActor) {
        this.currentActor = currentActor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PermissionInterceptor(this.currentActor.getObject()))
                .addPathPatterns(API_PATTERN);
    }
}
