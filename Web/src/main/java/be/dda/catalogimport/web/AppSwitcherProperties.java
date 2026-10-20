package be.dda.catalogimport.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * De productwisselaar linksboven (docs/decisions.md 2026-10-20 "Frontend-ombouw volgens de Prodis-mock +
 * productwisselaar"): waar Prodis staat en waar CatalogImport Prodis' {@code GET /api/account} kan vragen
 * welke andere apps (ProFin, ProDev) de gebruiker mag zien.
 *
 * <pre>
 * catalogimport:
 *   app-switcher:
 *     prodis-url: http://localhost:9060                       # link voor de gebruiker; leeg = geen Prodis-link
 *     prodis-account-url: http://localhost:8080/api/account   # token relay; leeg = geen relay (enkel de Prodis-link)
 *     connect-timeout: PT2S
 *     read-timeout: PT2S
 *     cache-ttl: PT5M
 *     failure-cache-ttl: PT1M
 * </pre>
 *
 * Zoals {@code ProdevProperties} in Prodis: blanco = niet ingesteld; elke andere waarde moet een absolute
 * http(s)-URL zijn, anders start de applicatie niet (een tikfout zou anders een dode link of een stil
 * mislukkende relay opleveren). Een onbekende sleutel onder {@code catalogimport.app-switcher} laat de
 * applicatie ook niet starten. De duurwaarden moeten strikt positief zijn.
 * <p>
 * Geen defaults voor de URL's in {@code application.yml} of {@code demo}: enkel de lokale profielen
 * ({@code local}, {@code ewoutdev}) zetten ze.
 */
@Component
@ConfigurationProperties(prefix = "catalogimport.app-switcher", ignoreUnknownFields = false)
public class AppSwitcherProperties {

    static final String PREFIX = "catalogimport.app-switcher";
    private static final String INVALID_URL =
            "%s must be an absolute http(s) URL such as https://prodis.example.com, or empty; got '%s'";

    /** Startpagina van de Prodis-SPA (voor de gebruiker), zonder slash op het einde; {@code null} = geen Prodis-link. */
    private String prodisUrl;

    /** Backend-endpoint {@code GET /api/account} van Prodis; {@code null} = geen relay. */
    private String prodisAccountUrl;

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(2);

    /** Hoe lang een geslaagd relay-antwoord per sessie hergebruikt wordt. */
    private Duration cacheTtl = Duration.ofMinutes(5);

    /** Hoe lang een mislukte relay per sessie onthouden wordt (Prodis onbereikbaar mag niet elke /me vertragen). */
    private Duration failureCacheTtl = Duration.ofMinutes(1);

    public String getProdisUrl() {
        return this.prodisUrl;
    }

    public void setProdisUrl(String prodisUrl) {
        this.prodisUrl = normalizeUrl(PREFIX + ".prodis-url", prodisUrl);
    }

    public String getProdisAccountUrl() {
        return this.prodisAccountUrl;
    }

    public void setProdisAccountUrl(String prodisAccountUrl) {
        this.prodisAccountUrl = normalizeUrl(PREFIX + ".prodis-account-url", prodisAccountUrl);
    }

    public Duration getConnectTimeout() {
        return this.connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = positive(PREFIX + ".connect-timeout", connectTimeout);
    }

    public Duration getReadTimeout() {
        return this.readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = positive(PREFIX + ".read-timeout", readTimeout);
    }

    public Duration getCacheTtl() {
        return this.cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = positive(PREFIX + ".cache-ttl", cacheTtl);
    }

    public Duration getFailureCacheTtl() {
        return this.failureCacheTtl;
    }

    public void setFailureCacheTtl(Duration failureCacheTtl) {
        this.failureCacheTtl = positive(PREFIX + ".failure-cache-ttl", failureCacheTtl);
    }

    /** {@code true} als de relay naar Prodis' {@code /api/account} ingesteld is. */
    boolean relayConfigured() {
        return this.prodisAccountUrl != null;
    }

    /**
     * {@code null} voor blanco; anders getrimd en zonder slashes op het einde.
     *
     * @throws IllegalArgumentException geen absolute http(s)-URL met host (de binder meldt dit onder de property)
     */
    static String normalizeUrl(String property, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!isAbsoluteHttpUrl(trimmed)) {
            throw new IllegalArgumentException(INVALID_URL.formatted(property, value));
        }
        return trimmed;
    }

    /** Absolute {@code http}/{@code https}-URL met een host; alles anders (ook {@code javascript:}) is ongeldig. */
    static boolean isAbsoluteHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = (uri.getScheme() == null) ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static Duration positive(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration; got '" + value + "'");
        }
        return value;
    }
}
