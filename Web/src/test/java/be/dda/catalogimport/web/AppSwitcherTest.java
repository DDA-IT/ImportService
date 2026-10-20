package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.web.AppSwitcher.AppLink;
import be.dda.catalogimport.web.ProdisAccountRelay.ProdisAccount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * BE-SW: welke apps de productwisselaar toont (docs/decisions.md 2026-10-20, zoals Prodis'
 * {@code product-switcher.tsx}) en de cache per sessie. De relay is gemockt; zijn eigen gedrag staat in
 * {@link ProdisAccountRelayTest}.
 * <ul>
 *   <li><b>Regel:</b> volgorde prodis, profin, prodev; CatalogImport zelf nooit.</li>
 *   <li><b>Regel:</b> prodis als {@code prodis-url} gezet is; profin bij System Admin of {@code profin.access} én
 *       {@code prodis-url}; prodev als Prodis een {@code prodevUrl} teruggeeft.</li>
 *   <li><b>Regel:</b> relay mislukt of niet ingesteld = enkel de Prodis-link.</li>
 *   <li><b>Implementatie:</b> een geslaagd antwoord {@code cache-ttl}, een mislukking {@code failure-cache-ttl}
 *       lang hergebruikt in de sessie, enkel voor dezelfde login.</li>
 * </ul>
 */
class AppSwitcherTest {

    private static final String PRODIS = "http://localhost:9060";
    private static final String PRODEV = "https://prodev.example.test";
    private static final ActorIdentity ACTOR = new ActorIdentity("an.janssens", "sub-an");

    private final ProdisAccountRelay relay = mock(ProdisAccountRelay.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T08:00:00Z"));
    private final Authentication principal = new TestingAuthenticationToken("an.janssens", null, "ROLE_USER");

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        response = new MockHttpServletResponse();
    }

    // --- Zichtbaarheidsregels --------------------------------------------------------------------------

    @Test
    void withoutRelayOnlyTheProdisLink() {
        AppSwitcher switcher = switcher(properties(PRODIS, null));

        assertThat(switcher.appsFor(ACTOR, principal, request, response))
                .containsExactly(new AppLink("prodis", PRODIS));
        verify(relay, never()).fetch(any(), any(), any());
    }

    @Test
    void withNothingConfiguredNoApps() {
        assertThat(switcher(properties(null, null)).appsFor(ACTOR, principal, request, response)).isEmpty();
    }

    @Test
    void aSystemAdminSeesProfin() {
        relayAnswers(new ProdisAccount(true, false, null));

        assertThat(switcher(relayed()).appsFor(ACTOR, principal, request, response)).containsExactly(
                new AppLink("prodis", PRODIS), new AppLink("profin", PRODIS + "/dashboard/profin"));
    }

    @Test
    void profinAccessShowsProfin() {
        relayAnswers(new ProdisAccount(false, true, null));

        assertThat(switcher(relayed()).appsFor(ACTOR, principal, request, response)).containsExactly(
                new AppLink("prodis", PRODIS), new AppLink("profin", PRODIS + "/dashboard/profin"));
    }

    @Test
    void neitherSystemAdminNorProfinAccessMeansNoProfin() {
        relayAnswers(new ProdisAccount(false, false, null));

        assertThat(switcher(relayed()).appsFor(ACTOR, principal, request, response))
                .containsExactly(new AppLink("prodis", PRODIS));
    }

    @Test
    void aProdevUrlShowsProdev() {
        relayAnswers(new ProdisAccount(false, false, PRODEV));

        assertThat(switcher(relayed()).appsFor(ACTOR, principal, request, response))
                .containsExactly(new AppLink("prodis", PRODIS), new AppLink("prodev", PRODEV));
    }

    @Test
    void everythingInTheFixedOrder() {
        relayAnswers(new ProdisAccount(true, true, PRODEV));

        assertThat(switcher(relayed()).appsFor(ACTOR, principal, request, response)).extracting(AppLink::id)
                .containsExactly("prodis", "profin", "prodev");
    }

    @Test
    void withoutProdisUrlNoProfinButProdevStays() {
        relayAnswers(new ProdisAccount(true, true, PRODEV));

        assertThat(switcher(properties(null, "http://localhost:8080/api/account"))
                .appsFor(ACTOR, principal, request, response))
                .containsExactly(new AppLink("prodev", PRODEV));
    }

    @Test
    void aFailedRelayFallsBackToTheProdisLink() {
        when(relay.fetch(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(switcher(relayed()).appsFor(ACTOR, principal, request, response))
                .containsExactly(new AppLink("prodis", PRODIS));
    }

    @Test
    void theListIsImmutable() {
        List<AppLink> apps = AppSwitcher.apps(PRODIS, null);

        assertThat(apps).isUnmodifiable();
    }

    // --- Cache per sessie (idempotentie, herhaling) ------------------------------------------------------

    @Test
    void aSuccessfulAnswerIsReusedWithinTheCacheTtl() {
        relayAnswers(new ProdisAccount(true, false, null));
        AppSwitcher switcher = switcher(relayed());

        switcher.appsFor(ACTOR, principal, request, response);
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        List<AppLink> second = switcher.appsFor(ACTOR, principal, request, response);

        assertThat(second).extracting(AppLink::id).containsExactly("prodis", "profin");
        verify(relay, times(1)).fetch(any(), any(), any());

        clock.advance(Duration.ofSeconds(1));
        switcher.appsFor(ACTOR, principal, request, response);
        verify(relay, times(2)).fetch(any(), any(), any());
    }

    @Test
    void aFailureIsRememberedOnlyForTheShorterFailureTtl() {
        when(relay.fetch(any(), any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ProdisAccount(true, false, null)));
        AppSwitcher switcher = switcher(relayed());

        assertThat(switcher.appsFor(ACTOR, principal, request, response)).extracting(AppLink::id).containsExactly("prodis");
        clock.advance(Duration.ofSeconds(59));
        assertThat(switcher.appsFor(ACTOR, principal, request, response)).extracting(AppLink::id).containsExactly("prodis");
        verify(relay, times(1)).fetch(any(), any(), any());

        clock.advance(Duration.ofSeconds(1));
        assertThat(switcher.appsFor(ACTOR, principal, request, response)).extracting(AppLink::id)
                .containsExactly("prodis", "profin");
        verify(relay, times(2)).fetch(any(), any(), any());
    }

    @Test
    void anotherLoginInTheSameSessionFetchesAgain() {
        when(relay.fetch(any(), any(), any()))
                .thenReturn(Optional.of(new ProdisAccount(true, false, null)))
                .thenReturn(Optional.of(new ProdisAccount(false, false, null)));
        AppSwitcher switcher = switcher(relayed());

        switcher.appsFor(ACTOR, principal, request, response);
        List<AppLink> other = switcher.appsFor(new ActorIdentity("piet.peeters", "sub-piet"), principal, request, response);

        assertThat(other).extracting(AppLink::id).containsExactly("prodis");
        verify(relay, times(2)).fetch(any(), any(), any());
    }

    @Test
    void aSameUsernameWithAnotherSubjectFetchesAgain() {
        relayAnswers(new ProdisAccount(true, false, null));
        AppSwitcher switcher = switcher(relayed());

        switcher.appsFor(ACTOR, principal, request, response);
        switcher.appsFor(new ActorIdentity(ACTOR.username(), "sub-other"), principal, request, response);

        verify(relay, times(2)).fetch(any(), any(), any());
    }

    @Test
    void withoutSessionNothingIsCached() {
        relayAnswers(new ProdisAccount(true, false, null));
        MockHttpServletRequest sessionless = new MockHttpServletRequest();
        AppSwitcher switcher = switcher(relayed());

        switcher.appsFor(ACTOR, principal, sessionless, response);
        switcher.appsFor(ACTOR, principal, sessionless, response);

        assertThat(sessionless.getSession(false)).isNull();
        verify(relay, times(2)).fetch(any(), any(), any());
    }

    @Test
    void aClockThatWentBackwardsDoesNotKeepAStaleEntryForever() {
        relayAnswers(new ProdisAccount(true, false, null));
        AppSwitcher switcher = switcher(relayed());

        switcher.appsFor(ACTOR, principal, request, response);
        clock.advance(Duration.ofHours(-1));
        switcher.appsFor(ACTOR, principal, request, response);

        verify(relay, times(2)).fetch(any(), any(), any());
    }

    @Test
    void anInvalidatedSessionDoesNotBreakTheAnswer() {
        relayAnswers(new ProdisAccount(true, false, null));
        MockHttpSession session = new MockHttpSession() {
            @Override
            public void setAttribute(String name, Object value) {
                throw new IllegalStateException("invalidated");
            }
        };
        request.setSession(session);

        assertThat(switcher(relayed()).appsFor(ACTOR, principal, request, response)).extracting(AppLink::id)
                .containsExactly("prodis", "profin");
    }

    // --- Hulpmiddelen ----------------------------------------------------------------------------------

    private void relayAnswers(ProdisAccount account) {
        when(relay.fetch(any(), any(), any())).thenReturn(Optional.of(account));
    }

    private AppSwitcher switcher(AppSwitcherProperties properties) {
        return new AppSwitcher(properties, relay, clock);
    }

    private static AppSwitcherProperties relayed() {
        return properties(PRODIS, "http://localhost:8080/api/account");
    }

    private static AppSwitcherProperties properties(String prodisUrl, String accountUrl) {
        AppSwitcherProperties properties = new AppSwitcherProperties();
        properties.setProdisUrl(prodisUrl);
        properties.setProdisAccountUrl(accountUrl);
        return properties;
    }

    /** Een klok die de test vooruit (of achteruit) zet. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
