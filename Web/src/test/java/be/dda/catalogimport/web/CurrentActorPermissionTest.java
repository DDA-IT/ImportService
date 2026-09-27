package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Bouwstap 5B-1 (docs/design/fase5-perm-design.md par. 1, 2 en 3): {@code CurrentActor.require} — de
 * plek waar de hiërarchie, de foutvolgorde en de cache per verzoek samenkomen. Databasevrij en zonder
 * Spring-context; enkel een {@code SecurityContext} en een nep-verzoek.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel (V2):</b> {@code approve} impliceert {@code manage} en {@code read}.
 *       <b>Implementatie:</b> {@code require} toetst tegen de <i>effectieve</i> set.</li>
 *   <li><b>Regel (V1, "recht eerst"):</b> {@code SYSTEM_ACTOR_FORBIDDEN} bij MANAGE/APPROVE gaat vóór de
 *       rechtencheck; 503 gaat vóór 403 {@code PERMISSION_DENIED}. <b>Implementatie:</b> de volgorde in
 *       {@code require}; hier bewezen doordat de bron in het {@code system}-geval nooit bevraagd wordt.</li>
 *   <li><b>Regel:</b> intrekken van een recht werkt onmiddellijk. <b>Implementatie:</b> de cache leeft
 *       in een <i>requestattribuut</i>, dus hoogstens één verzoek lang — nooit een toepassingscache.</li>
 *   <li><b>Regel:</b> de foutboodschap lekt nooit de bron of andermans rechten.
 *       <b>Implementatie:</b> ze noemt enkel de ontbrekende rechtcode; hier vastgepind.</li>
 * </ul>
 */
class CurrentActorPermissionTest {

    private static final String USER = "an.janssens@example.test";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // --- Normaal scenario + hiërarchie ------------------------------------------------------------------

    @Test
    void anApproverMayAlsoManageAndRead() {
        signIn(USER);
        CurrentActor actor = actorWith(Permission.APPROVE);

        assertThat(actor.require(Permission.APPROVE).username()).isEqualTo(USER);
        assertThat(actor.require(Permission.MANAGE).username()).isEqualTo(USER);
        assertThat(actor.require(Permission.READ).username()).isEqualTo(USER);
    }

    @Test
    void aManagerMayReadButNeverApprove() {
        signIn(USER);
        CurrentActor actor = actorWith(Permission.MANAGE);

        assertThat(actor.require(Permission.READ).username()).isEqualTo(USER);
        assertThatThrownBy(() -> actor.require(Permission.APPROVE))
                .isInstanceOf(ActorNotAllowedException.class)
                .hasFieldOrPropertyWithValue("code", CurrentActor.PERMISSION_DENIED);
    }

    @Test
    void aReaderMayNeitherManageNorApprove() {
        signIn(USER);
        CurrentActor actor = actorWith(Permission.READ);

        assertThat(actor.require(Permission.READ).username()).isEqualTo(USER);
        assertThatThrownBy(() -> actor.require(Permission.MANAGE))
                .isInstanceOf(ActorNotAllowedException.class)
                .hasFieldOrPropertyWithValue("code", CurrentActor.PERMISSION_DENIED);
        assertThatThrownBy(() -> actor.require(Permission.APPROVE))
                .isInstanceOf(ActorNotAllowedException.class)
                .hasFieldOrPropertyWithValue("code", CurrentActor.PERMISSION_DENIED);
    }

    // --- Ontbrekende rechten: de boodschap lekt niets ------------------------------------------------------

    @Test
    void theDenialNamesOnlyTheMissingCodeAndNeverTheSourceOrOtherRights() {
        signIn(USER);
        CurrentActor actor = actorWith(Permission.READ);

        assertThatThrownBy(() -> actor.require(Permission.APPROVE))
                .isInstanceOf(ActorNotAllowedException.class)
                .hasMessageContaining("catalogImport.approve")
                .hasMessageNotContaining("catalogImport.read")
                .hasMessageNotContaining("catalogImport.manage")
                .hasMessageNotContaining(USER);
    }

    @Test
    void aUserWithoutAnyRightIsDeniedEvenForRead() {
        signIn(USER);
        CurrentActor actor = actorWith();

        assertThatThrownBy(() -> actor.require(Permission.READ))
                .isInstanceOf(ActorNotAllowedException.class)
                .hasFieldOrPropertyWithValue("code", CurrentActor.PERMISSION_DENIED);
    }

    // --- Volgorde: system vóór de rechtencheck ----------------------------------------------------------------

    /**
     * Dat {@code system} niet mag beheren of goedkeuren is een eigenschap van {@code system} zelf: de bron
     * wordt daarvoor niet eens bevraagd. De teller bewijst dat de volgorde echt zo ligt.
     */
    @Test
    void systemIsRefusedForManageAndApproveBeforeTheSourceIsConsulted() {
        signIn("SyStEm");
        AtomicInteger calls = new AtomicInteger();
        CurrentActor actor = new CurrentActor(countingSource(calls, Permission.APPROVE));

        assertThatThrownBy(() -> actor.require(Permission.MANAGE))
                .isInstanceOf(ActorNotAllowedException.class)
                .hasFieldOrPropertyWithValue("code", CurrentActor.SYSTEM_ACTOR_FORBIDDEN);
        assertThatThrownBy(() -> actor.require(Permission.APPROVE))
                .isInstanceOf(ActorNotAllowedException.class)
                .hasFieldOrPropertyWithValue("code", CurrentActor.SYSTEM_ACTOR_FORBIDDEN);
        assertThat(calls).hasValue(0);
    }

    /** Lezen mag {@code system} wél — mits het recht er is. */
    @Test
    void systemMayStillRead() {
        signIn("system");
        CurrentActor actor = actorWith(Permission.READ);

        assertThat(actor.require(Permission.READ).username()).isEqualTo("system");
    }

    // --- Bron onbereikbaar: 503, nooit stil "geen rechten" -------------------------------------------------------

    @Test
    void anUnavailableSourceIsNeverTreatedAsAnEmptySet() {
        signIn(USER);
        CurrentActor actor = new CurrentActor(identity -> {
            throw new PermissionSourceUnavailableException("stub is down");
        });

        assertThatThrownBy(() -> actor.require(Permission.READ))
                .isInstanceOf(PermissionSourceUnavailableException.class);
    }

    /** En dat wordt 503 met de stabiele code, in dezelfde vorm {@code {error, code}} als de rest. */
    @Test
    void theHandlerTurnsAnUnavailableSourceIntoA503WithoutNamingTheSource() {
        ResponseEntity<Map<String, String>> response = new ApiExceptionHandler()
                .permissionSourceUnavailable(new PermissionSourceUnavailableException("Prodis timed out at host x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("code", "PERMISSION_SOURCE_UNAVAILABLE");
        assertThat(response.getBody().get("error")).doesNotContain("Prodis").doesNotContain("host x");
    }

    // --- Cache per verzoek: één bevraging, en nooit langer dan het verzoek ------------------------------------------

    @Test
    void theSourceIsConsultedOncePerRequestNoMatterHowManyChecks() {
        signIn(USER);
        AtomicInteger calls = new AtomicInteger();
        CurrentActor actor = new CurrentActor(countingSource(calls, Permission.APPROVE));

        actor.require(Permission.READ);
        actor.require(Permission.MANAGE);
        actor.require(Permission.APPROVE);

        assertThat(calls).hasValue(1);
    }

    /** Een volgend verzoek bevraagt opnieuw: een ingetrokken recht werkt onmiddellijk. */
    @Test
    void aNextRequestConsultsTheSourceAgainSoARevocationTakesEffectImmediately() {
        signIn(USER);
        AtomicInteger calls = new AtomicInteger();
        CurrentActor actor = new CurrentActor(countingSource(calls, Permission.APPROVE));
        actor.require(Permission.APPROVE);

        newRequest();

        actor.require(Permission.APPROVE);
        assertThat(calls).hasValue(2);
    }

    /** Buiten een verzoek (directe aanroep) is er geen cache — en ook geen fout. */
    @Test
    void withoutARequestThereIsNoCacheAndNoFailure() {
        signIn(USER);
        RequestContextHolder.resetRequestAttributes();
        AtomicInteger calls = new AtomicInteger();
        CurrentActor actor = new CurrentActor(countingSource(calls, Permission.READ));

        actor.require(Permission.READ);
        actor.require(Permission.READ);

        assertThat(calls).hasValue(2);
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private static CurrentActor actorWith(Permission... granted) {
        Set<Permission> raw = Set.of(granted);
        return new CurrentActor(identity -> raw);
    }

    private static PermissionSource countingSource(AtomicInteger calls, Permission... granted) {
        Set<Permission> raw = Set.of(granted);
        return identity -> {
            calls.incrementAndGet();
            return raw;
        };
    }

    /** Meldt de gebruiker aan en start een vers nep-verzoek (waar de cache in leeft). */
    private static void signIn(String username) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("token")
                .subject("test-sub-" + username)
                .claim("preferred_username", username)
                .build();
        OidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken,
                "preferred_username");
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));
        newRequest();
    }

    private static void newRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
}
