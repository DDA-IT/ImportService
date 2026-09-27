package be.dda.catalogimport.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * De lokaal ingestelde rechtentoekenningen (Fase 5-PERM, ontwerp par. 2), gelezen door
 * {@link ConfiguredPermissionSource}:
 *
 * <pre>
 * catalogimport:
 *   permissions:
 *     grants:
 *       - username: jan.peeters
 *         rights: read, manage, approve
 * </pre>
 *
 * <b>Waarom een lijst en geen map?</b> Het ontwerp legt dit vast als "Important technical constraint
 * discovered": Spring Boot bindt een {@code Map<String, ...>} met een punt in de sleutel
 * ({@code jan.peeters}) alleen met bracket-notatie. Zonder brackets splitst de binder op de punt en
 * <b>verdwijnt de toekenning stilzwijgend</b>. Bij een rechtenbron is dat onzichtbaar gedrag; daarom
 * staat de naam in een gewoon veld {@code username}.
 * <p>
 * Geen wildcard, geen default, in geen enkel profiel: ontbreekt {@code grants}, dan heeft niemand iets.
 */
@Component
@ConfigurationProperties(prefix = "catalogimport.permissions")
public class PermissionProperties {

    private List<Grant> grants = new ArrayList<>();

    public List<Grant> getGrants() {
        return this.grants;
    }

    public void setGrants(List<Grant> grants) {
        this.grants = (grants == null) ? new ArrayList<>() : grants;
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
}
