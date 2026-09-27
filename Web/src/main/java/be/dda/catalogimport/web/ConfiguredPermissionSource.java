package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * De lokale rechtenbron: toekenningen uit {@code application*.yml} (Fase 5-PERM, ontwerp par. 2;
 * beslissingslog 2026-09-26 "5-PERM = nu bouwen met een lokaal instelbare rechtenbron"). De
 * Prodis-adapter is de laatste, losse stap (5B-7) en vervangt of vergezelt deze bron dan.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> fail-closed — lege configuratie betekent dat niemand iets heeft.
 *       <b>Implementatie:</b> geen wildcard, geen default, in geen enkel profiel; een onbekende
 *       gebruiker krijgt een lege set.</li>
 *   <li><b>Regel:</b> {@code system} mag lezen maar nooit beheren of goedkeuren (beslissingslog
 *       2026-09-25). <b>Implementatie:</b> de applicatie <b>start niet</b> als een toekenning
 *       {@code system} {@code manage} of {@code approve} geeft — dezelfde fail-fast-conventie als
 *       {@code SecurityConfiguration.requireUsableRegistration} (opstarten weigeren met een
 *       {@link IllegalStateException}), niet een stille correctie tijdens het draaien.</li>
 *   <li><b>Regel:</b> een toekenning mag nooit stilzwijgend verdwijnen. <b>Implementatie:</b> een
 *       blanco {@code username} of een onbekende rechtwaarde is óók een opstartfout; een typfout in
 *       een rechtenbron die "gewoon minder rechten" oplevert, zou pas bij de eerste 403 opvallen.</li>
 * </ul>
 *
 * <p>Matching gebeurt getrimd en hoofdletterongevoelig (ontwerp par. 2). Twee regels voor dezelfde
 * gebruiker worden samengevoegd (unie) — dat is wat een lezer van de yml verwacht.</p>
 *
 * <p>Deze bron levert de <b>ruwe</b> codes; de hiërarchie past {@link CurrentActor} toe. Ze is nooit
 * onbereikbaar en gooit dus nooit {@link PermissionSourceUnavailableException}.</p>
 */
@Component
public class ConfiguredPermissionSource implements PermissionSource {

    /** Nooit een beheerder of goedkeurder, ook niet als de yml dat vraagt. */
    static final String SYSTEM_USER = "system";

    private static final String PREFIX = "catalogimport.permissions.grants";

    private final Map<String, Set<Permission>> grantsByUsername;

    public ConfiguredPermissionSource(PermissionProperties properties) {
        this.grantsByUsername = Collections.unmodifiableMap(read(properties.getGrants()));
    }

    @Override
    public Set<Permission> permissionsOf(ActorIdentity actor) {
        if (actor == null || actor.username() == null) {
            return Set.of();
        }
        return this.grantsByUsername.getOrDefault(key(actor.username()), Set.of());
    }

    /** Het aantal gebruikers met minstens één recht; enkel voor tests en opstartdiagnose. */
    int grantedUserCount() {
        return this.grantsByUsername.size();
    }

    private static Map<String, Set<Permission>> read(List<PermissionProperties.Grant> grants) {
        Map<String, Set<Permission>> byUsername = new HashMap<>();
        for (int index = 0; index < grants.size(); index++) {
            PermissionProperties.Grant grant = grants.get(index);
            String where = PREFIX + "[" + index + "]";
            if (grant == null || grant.getUsername() == null || grant.getUsername().isBlank()) {
                throw new IllegalStateException(where + ".username is missing or blank");
            }
            String key = key(grant.getUsername());
            Set<Permission> rights = parseRights(grant.getRights(), where);
            if (SYSTEM_USER.equals(key) && (rights.contains(Permission.MANAGE) || rights.contains(Permission.APPROVE))) {
                throw new IllegalStateException(where + ": '" + SYSTEM_USER + "' may read but never manage or "
                        + "approve; remove the manage/approve right from this grant");
            }
            byUsername.computeIfAbsent(key, ignored -> EnumSet.noneOf(Permission.class)).addAll(rights);
        }
        return byUsername;
    }

    private static Set<Permission> parseRights(List<String> rights, String where) {
        EnumSet<Permission> parsed = EnumSet.noneOf(Permission.class);
        for (String raw : rights) {
            // Een leeg element ("read, , manage" of een lege lijstregel) is onbedoeld maar onschadelijk.
            if (raw == null || raw.isBlank()) {
                continue;
            }
            parsed.add(Permission.parse(raw).orElseThrow(() -> new IllegalStateException(
                    where + ".rights contains the unknown right '" + raw.trim() + "'; known values are "
                            + Permission.knownValues())));
        }
        return parsed;
    }

    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
