package be.dda.catalogimport.config;

import be.dda.catalogimport.web.ActorNotAllowedException;
import be.dda.catalogimport.web.CurrentActor;
import be.dda.catalogimport.web.NoPermissionRequired;
import be.dda.catalogimport.web.Permission;
import be.dda.catalogimport.web.RequiresPermission;
import be.dda.catalogimport.web.TechnicalClientOnly;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * De enige rechtencheck per actie (Fase 5-PERM, bindend ontwerp {@code docs/design/fase5-perm-design.md}
 * par. 3). Leest per handlermethode {@link RequiresPermission} of {@link NoPermissionRequired} en
 * gebruikt {@link CurrentActor#require(Permission)}.
 * <p>
 * <b>Geen tweede autorisatielaag:</b> geen method security, geen padregels in
 * {@code SecurityConfiguration}. Omdat deze interceptor in {@code config} leeft, blijft
 * {@code CurrentActor} buiten {@code config} de enige lezer van de {@code SecurityContext}.
 *
 * <h2>Technische client (M2, 2026-10-20)</h2>
 * Een handler met {@link TechnicalClientOnly} vraagt geen catalogImport-recht maar een JWT-aanmelding met de
 * bevoegdheid uit de annotatie ({@link CurrentActor#technicalClient(String)}); een BFF-sessie krijgt er 403
 * {@code PERMISSION_DENIED}. In de praktijk bereikt een sessie zo'n handler niet eens: de JWT-keten
 * ({@code LegacyConfigPushSecurityConfiguration}) bedient dat pad en geeft 401.
 *
 * <h2>Strikte modus (5B-3)</h2>
 * Een <b>niet-geannoteerde</b> handlermethode is dicht: 403 {@code PERMISSION_DENIED} plus een ERROR-log.
 * Er is geen soepele modus meer en geen schakelaar. {@code PermissionCoverageTest} dwingt af dat geen enkele
 * {@code @*Mapping}-methode zonder annotatie bestaat.
 *
 * <h2>Audit</h2>
 * Een geweigerde poging wordt <b>gelogd, niet bewaard</b> (ontwerp par. 6): één WARN-regel
 * {@code PERMISSION_DENIED user=<username> required=<code> <METHOD> <padtemplate>}. Bewust géén
 * subject (dat hoort in geen enkel antwoord of logregel, 5-AUTH A6), géén body en géén querystring —
 * die dragen leveranciers-, prijs- en filtergegevens die in een toegangslog niets te zoeken hebben.
 * Het <b>padtemplate</b> ({@code /api/catalog-import/bundles/{bundleId}/freeze}) wordt gelogd, niet de
 * concrete URI, zodat er geen id's in de log belanden.
 */
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionInterceptor.class);

    /**
     * Wat er in de auditregel komt als het padtemplate of de naam niet vast te stellen is. Voor het pad
     * bewust géén terugval op de ruwe URI: die draagt id's.
     */
    static final String UNKNOWN = "(unknown)";

    private final CurrentActor currentActor;

    public PermissionInterceptor(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // Statische bronnen, foutpagina's, actuator: geen handlermethode, dus geen annotatie mogelijk.
            return true;
        }
        if (handlerMethod.hasMethodAnnotation(NoPermissionRequired.class)) {
            return true;
        }
        TechnicalClientOnly technical = handlerMethod.getMethodAnnotation(TechnicalClientOnly.class);
        if (technical != null) {
            // M2 (2026-10-20): enkel een JWT-aanmelding met deze bevoegdheid; een BFF-sessie nooit. Tweede
            // verdedigingslijn naast de JWT-keten, die dit pad al afschermt.
            try {
                this.currentActor.technicalClient(technical.value());
            } catch (ActorNotAllowedException denied) {
                LOG.warn("TECHNICAL_CLIENT_DENIED required={} {} {}", technical.value(), request.getMethod(),
                        pathTemplate(request));
                throw denied;
            }
            return true;
        }
        RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            // Strikt (5B-3): een ontbrekende annotatie is dicht, nooit open.
            LOG.error("No permission annotation on {} {} ({}); refused (fail-closed)",
                    request.getMethod(), pathTemplate(request), handlerMethod.getShortLogMessage());
            throw new ActorNotAllowedException(CurrentActor.PERMISSION_DENIED,
                    "Deze actie is niet vrijgegeven: er is geen recht voor gedefinieerd.");
        }
        try {
            this.currentActor.require(required.value());
        } catch (ActorNotAllowedException denied) {
            if (CurrentActor.PERMISSION_DENIED.equals(denied.getCode())) {
                LOG.warn("PERMISSION_DENIED user={} required={} {} {}", usernameForAudit(),
                        required.value().code(), request.getMethod(), pathTemplate(request));
            }
            throw denied;
        }
        return true;
    }

    /**
     * De naam voor de auditregel. {@code require} is al voorbij de identiteitscontrole geraakt voor het
     * een {@code PERMISSION_DENIED} kon gooien, dus dit lukt; mislukt het toch, dan mag de auditregel
     * nooit de eigenlijke 403 vervangen.
     */
    private String usernameForAudit() {
        try {
            return this.currentActor.current().username();
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    private static String pathTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return (pattern == null) ? UNKNOWN : pattern.toString();
    }
}
