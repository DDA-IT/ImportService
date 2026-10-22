package be.dda.catalogimport.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Component;

/**
 * Token relay naar Prodis' {@code GET /api/account} voor de productwisselaar (docs/decisions.md 2026-10-20
 * "Frontend-ombouw volgens de Prodis-mock + productwisselaar"). Het transport (sessietoken met refresh,
 * Bearer, geen redirects, bodygrens, timeouts, parsen naar JSON) zit sinds slice 5B-7a in
 * {@link ProdisAccountClient}; deze klasse leest daaruit <b>enkel</b> {@code systemAdmin}, {@code permissions}
 * (alleen om {@value #PROFIN_ACCESS} te zien) en {@code prodevUrl}, en is de fail-soft-wrapper eromheen.
 * <p>
 * <b>Geen rechtenbron.</b> De rechten van CatalogImport zelf blijven bij {@link PermissionSource}
 * ({@code ConfiguredPermissionSource}); dit antwoord bepaalt enkel welke links de wisselaar toont. De
 * Prodis-rechtenadapter (5B-7) is een aparte stap met andere regels (fail-closed).
 * <p>
 * <b>Fail-soft.</b> Elke fout — geen token in de sessie, refresh mislukt, timeout, geen verbinding, een
 * niet-2xx-antwoord (ook een redirect: die wordt nooit gevolgd), een te groot antwoord of onverwachte JSON —
 * geeft {@link Optional#empty()} en één WARN-regel zonder token, body of gebruikersgegevens.
 */
@Component
public class ProdisAccountRelay {

    /** Zelfde registratie als {@code SecurityConfiguration.REGISTRATION_ID} (oauth2Login). */
    static final String REGISTRATION_ID = ProdisAccountClient.REGISTRATION_ID;

    /** Het Prodis-recht dat ProFin in de wisselaar zichtbaar maakt (Prodis {@code PROFIN_ACCESS}). */
    static final String PROFIN_ACCESS = "profin.access";

    /** Bovengrens voor het antwoord van {@code /api/account}; een normaal antwoord is enkele kB. */
    static final int MAX_BODY_BYTES = ProdisAccountClient.MAX_BODY_BYTES;

    private static final Logger LOG = LoggerFactory.getLogger(ProdisAccountRelay.class);

    /** Wat de wisselaar uit Prodis' accountantwoord haalt; {@code prodevUrl} is {@code null} of een absolute http(s)-URL. */
    public record ProdisAccount(boolean systemAdmin, boolean profinAccess, String prodevUrl) implements Serializable {
    }

    private final AppSwitcherProperties properties;
    private final ProdisAccountClient client;

    public ProdisAccountRelay(AppSwitcherProperties properties,
                              ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                              OAuth2AuthorizedClientRepository authorizedClients,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.client = new ProdisAccountClient(properties, clientRegistrations, authorizedClients, objectMapper);
    }

    /**
     * Prodis' accountgegevens voor de aangemelde gebruiker, of leeg bij elke fout (en als de relay niet
     * ingesteld is — dan zonder log). Gooit nooit.
     */
    public Optional<ProdisAccount> fetch(Authentication principal, HttpServletRequest request,
                                         HttpServletResponse response) {
        if (!this.properties.relayConfigured()) {
            return Optional.empty();
        }
        try {
            return Optional.of(map(this.client.fetchAccount(principal, request, response)));
        } catch (ProdisAccountException e) {
            return failed(e.getMessage());
        } catch (RuntimeException e) {
            return failed(describe(e));
        }
    }

    /** Strikt op type, mild op afwezigheid: een ontbrekend veld is "niet gezet", een veld van het verkeerde type is een fout. */
    private static ProdisAccount map(JsonNode root) {
        boolean systemAdmin = systemAdmin(root.get("systemAdmin"));
        boolean profinAccess = hasProfinAccess(root.get("permissions"));
        String prodevUrl = prodevUrl(root.get("prodevUrl"));
        return new ProdisAccount(systemAdmin, profinAccess, prodevUrl);
    }

    private static boolean systemAdmin(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw ProdisAccountException.other("unexpected JSON: systemAdmin is not a boolean");
        }
        return node.booleanValue();
    }

    private static boolean hasProfinAccess(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isArray()) {
            throw ProdisAccountException.other("unexpected JSON: permissions is not an array");
        }
        boolean found = false;
        for (JsonNode permission : node) {
            if (!permission.isTextual()) {
                throw ProdisAccountException.other("unexpected JSON: permissions contains a non-string");
            }
            found |= PROFIN_ACCESS.equals(permission.textValue().trim());
        }
        return found;
    }

    /** Een ongeldige ProDev-URL laat enkel ProDev weg (nooit een niet-http(s)-link in de wisselaar), de rest blijft bruikbaar. */
    private static String prodevUrl(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw ProdisAccountException.other("unexpected JSON: prodevUrl is not a string");
        }
        String value = node.textValue().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!AppSwitcherProperties.isAbsoluteHttpUrl(value)) {
            LOG.warn("App switcher: Prodis returned a prodevUrl that is not an absolute http(s) URL; ProDev is not offered");
            return null;
        }
        return value;
    }

    private static Optional<ProdisAccount> failed(String reason) {
        LOG.warn("App switcher: Prodis account relay failed ({}); offering only the configured Prodis link", reason);
        return Optional.empty();
    }

    /** Klassenamen van de fout en haar oorzaak; nooit de boodschap (die kan URL-parameters of serverteksten bevatten). */
    private static String describe(RuntimeException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return (root == e) ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + " caused by " + root.getClass().getSimpleName();
    }
}
