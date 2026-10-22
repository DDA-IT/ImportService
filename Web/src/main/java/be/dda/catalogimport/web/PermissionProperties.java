package be.dda.catalogimport.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * De instellingen van de rechtenbron (Fase 5-PERM, ontwerp par. 2; 5B-7, {@code docs/design/fase5-perm-5b7-design.md}
 * par. 3):
 *
 * <pre>
 * catalogimport:
 *   permissions:
 *     source: CONFIGURED            # CONFIGURED (grants hieronder) of PRODIS (Prodis GET /api/account)
 *     grants:                       # enkel bij CONFIGURED; bij PRODIS genegeerd (WARN bij het opstarten)
 *       - username: jan.peeters
 *         rights: read, manage, approve
 *     prodis:                       # enkel bij PRODIS
 *       account-url: ${CATALOG_PRODIS_ACCOUNT_URL:}   # verplicht bij PRODIS, anders start de applicatie niet
 *       connect-timeout: PT2S
 *       read-timeout: PT2S
 *       cache-ttl: PT30S            # geslaagd antwoord, per sessie (beslissingslog 2026-10-22, V1 = B)
 * </pre>
 *
 * <b>Bronkeuze.</b> {@code source} kiest precies één bron; YAML en Prodis worden nooit samengevoegd (beslissingslog
 * 2026-10-22). Een onbekende of lege waarde laat de applicatie niet starten. Zonder {@code source} geldt in de code
 * {@link Source#CONFIGURED} (het gedrag van vóór 5B-7); de productiedefault {@code PRODIS} hoort in
 * {@code application.yml} (V2 = A, slice 5B-7d).
 * <p>
 * <b>Waarom een lijst en geen map voor {@code grants}?</b> Het ontwerp legt dit vast als "Important technical
 * constraint discovered": Spring Boot bindt een {@code Map<String, ...>} met een punt in de sleutel
 * ({@code jan.peeters}) alleen met bracket-notatie. Zonder brackets splitst de binder op de punt en
 * <b>verdwijnt de toekenning stilzwijgend</b>. Bij een rechtenbron is dat onzichtbaar gedrag; daarom
 * staat de naam in een gewoon veld {@code username}.
 * <p>
 * Geen wildcard, geen default, in geen enkel profiel: ontbreekt {@code grants}, dan heeft niemand iets.
 */
@Component
@ConfigurationProperties(prefix = PermissionProperties.PREFIX)
public class PermissionProperties {

    public static final String PREFIX = "catalogimport.permissions";

    /** Welke rechtenbron actief is; precies één. */
    public enum Source {
        /** De toekenningen uit {@code catalogimport.permissions.grants} ({@link ConfiguredPermissionSource}). */
        CONFIGURED,
        /** Prodis {@code GET /api/account} met het sessietoken ({@link ProdisPermissionSource}). */
        PRODIS
    }

    private Source source = Source.CONFIGURED;

    private List<Grant> grants = new ArrayList<>();

    private final Prodis prodis = new Prodis();

    /** De gekozen bron; nooit {@code null}. */
    public Source source() {
        return this.source;
    }

    /** De gekozen bron als tekst (voor de binder); gebruik {@link #source()} in code. */
    public String getSource() {
        return this.source.name();
    }

    /**
     * Getrimd en hoofdletterongevoelig ({@code prodis} = {@code PRODIS}). Bewust als tekst gebonden en niet als enum:
     * Spring Boot zet een <b>lege</b> enumwaarde om naar {@code null} en roept de setter dan niet aan, waardoor een lege
     * omgevingsvariabele stil op de default zou terugvallen. Leeg of onbekend is hier een opstartfout.
     */
    public void setSource(String value) {
        String trimmed = (value == null) ? "" : value.trim();
        for (Source candidate : Source.values()) {
            if (candidate.name().equalsIgnoreCase(trimmed)) {
                this.source = candidate;
                return;
            }
        }
        throw new IllegalArgumentException(PREFIX + ".source must be CONFIGURED or PRODIS; got '" + trimmed + "'");
    }

    public List<Grant> getGrants() {
        return this.grants;
    }

    public void setGrants(List<Grant> grants) {
        this.grants = (grants == null) ? new ArrayList<>() : grants;
    }

    public Prodis getProdis() {
        return this.prodis;
    }

    /** Eén toekenning: welke gebruiker, welke ruwe rechten. */
    public static class Grant {

        private String username;

        private List<String> rights = new ArrayList<>();

        public String getUsername() {
            return this.username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        /**
         * De ruwe rechten. Zowel {@code rights: read, manage} (komma-gescheiden, zoals in het ontwerp)
         * als de YAML-lijstvorm binden hierop.
         */
        public List<String> getRights() {
            return this.rights;
        }

        public void setRights(List<String> rights) {
            this.rights = (rights == null) ? new ArrayList<>() : rights;
        }
    }

    /**
     * De Prodis-rechtenbron (5B-7). Eigen properties, los van {@code catalogimport.app-switcher}: de productwisselaar
     * is fail-soft en optioneel, deze bron fail-closed en verplicht bij {@code PRODIS}. Beide lezen in
     * {@code application.yml} wel dezelfde omgevingsvariabele {@code CATALOG_PRODIS_ACCOUNT_URL}.
     * <p>
     * Blanco URL = niet ingesteld; elke andere waarde moet een absolute http(s)-URL zijn (zelfde regel als
     * {@link AppSwitcherProperties}). Elke duur moet strikt positief zijn. Anders start de applicatie niet.
     */
    public static class Prodis {

        static final String PREFIX = PermissionProperties.PREFIX + ".prodis";

        /** Backend-endpoint {@code GET /api/account} van Prodis; {@code null} = niet ingesteld. */
        private String accountUrl;

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(2);

        /** Hoe lang een geslaagd antwoord per sessie hergebruikt wordt; fouten worden nooit gecachet. */
        private Duration cacheTtl = Duration.ofSeconds(30);

        public String getAccountUrl() {
            return this.accountUrl;
        }

        public void setAccountUrl(String accountUrl) {
            this.accountUrl = AppSwitcherProperties.normalizeUrl(PREFIX + ".account-url", accountUrl);
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

        private static Duration positive(String property, Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(property + " must be a positive duration; got '" + value + "'");
            }
            return value;
        }
    }
}
