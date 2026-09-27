package be.dda.catalogimport.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.web.ActorNotAllowedException;
import be.dda.catalogimport.web.CurrentActor;
import be.dda.catalogimport.web.NoPermissionRequired;
import be.dda.catalogimport.web.Permission;
import be.dda.catalogimport.web.PermissionSource;
import be.dda.catalogimport.web.PermissionSourceUnavailableException;
import be.dda.catalogimport.web.RequiresPermission;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Bouwstappen 5B-1/5B-3 (docs/design/fase5-perm-design.md par. 3 en 6): de interceptor in <b>strikte modus</b>
 * en de auditregel. Databasevrij en zonder Spring-context.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> een niet-geannoteerde handler is dicht (fail-closed). <b>Implementatie:</b>
 *       403 {@code PERMISSION_DENIED} en één ERROR-regel; een archtest ({@code PermissionCoverageTest})
 *       voorkomt dat het ooit gebeurt.</li>
 *   <li><b>Regel:</b> een geweigerde poging wordt gelogd, niet bewaard. <b>Implementatie:</b> één
 *       WARN-regel {@code PERMISSION_DENIED user=... required=... METHOD padtemplate}.</li>
 *   <li><b>Regel:</b> de log lekt geen subject, body of querystring, en geen id's.
 *       <b>Implementatie:</b> enkel het <i>padtemplate</i>, nooit de concrete URI; hier bewezen met een
 *       verzoek dat een querystring én een id draagt.</li>
 * </ul>
 */
class PermissionInterceptorTest {

    private static final String USER = "an.janssens@example.test";
    private static final String TEMPLATE = "/api/catalog-import/bundles/{bundleId}/freeze";

    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private Logger logger;
    private ListAppender<ILoggingEvent> log;
    private Level originalLevel;

    @BeforeEach
    void startCapturingLogs() {
        this.logger = (Logger) LoggerFactory.getLogger(PermissionInterceptor.class);
        this.originalLevel = this.logger.getLevel();
        this.logger.setLevel(Level.DEBUG);
        this.log = new ListAppender<>();
        this.log.start();
        this.logger.addAppender(this.log);
    }

    @AfterEach
    void stopCapturingLogs() {
        this.logger.detachAppender(this.log);
        this.logger.setLevel(this.originalLevel);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // --- Geen handlermethode: niets te controleren -------------------------------------------------------

    @Test
    void aHandlerThatIsNotAMethodPassesUntouched() {
        signIn(USER);

        assertThat(interceptor().preHandle(request(), this.response, "a-static-resource")).isTrue();
        assertThat(messages()).isEmpty();
    }

    // --- Strikte modus (5B-3): geen annotatie = dicht, 403 + ERROR-log ---------------------------------------

    @Test
    void anUnannotatedHandlerIsRefusedWith403AndAnErrorLog() {
        signIn(USER);
        AtomicInteger calls = new AtomicInteger();
        PermissionInterceptor interceptor = interceptor(calls, Permission.APPROVE);

        assertThatThrownBy(() -> interceptor.preHandle(request(), this.response, handler("unannotated")))
                .isInstanceOf(ActorNotAllowedException.class)
                .hasFieldOrPropertyWithValue("code", CurrentActor.PERMISSION_DENIED);

        assertThat(calls).hasValue(0);
        assertThat(this.log.list).singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
        assertThat(messages()).singleElement().asString()
                .contains("No permission annotation").contains(TEMPLATE);
    }

    /** {@code @NoPermissionRequired} is een bewuste uitzondering: geen check en ook geen ruis in de log. */
    @Test
    void anExplicitlyExemptedHandlerIsNotCheckedAndLogsNothing() {
        signIn(USER);
        AtomicInteger calls = new AtomicInteger();

        assertThat(interceptor(calls).preHandle(request(), this.response, handler("exempted"))).isTrue();

        assertThat(calls).hasValue(0);
        assertThat(messages()).isEmpty();
    }

    // --- Normaal scenario: met recht erdoor ------------------------------------------------------------------

    @Test
    void anAnnotatedHandlerPassesWhenTheUserHasTheRight() {
        signIn(USER);

        assertThat(interceptor(Permission.APPROVE).preHandle(request(), this.response, handler("needsApprove")))
                .isTrue();
        assertThat(messages()).isEmpty();
    }

    /** De hiërarchie werkt ook hier: {@code approve} voldoet aan een {@code manage}-eis. */
    @Test
    void anApproverPassesAManageHandler() {
        signIn(USER);

        assertThat(interceptor(Permission.APPROVE).preHandle(request(), this.response, handler("needsManage")))
                .isTrue();
    }

    // --- Zonder recht: 403 PERMISSION_DENIED + één WARN-auditregel ----------------------------------------------

    @Test
    void withoutTheRightItThrowsPermissionDeniedAndLogsExactlyOneWarnAuditLine() {
        signIn(USER);
        PermissionInterceptor interceptor = interceptor(Permission.READ);

        assertThatThrownBy(() -> interceptor.preHandle(request(), this.response, handler("needsApprove")))
                .isInstanceOf(ActorNotAllowedException.class)
                .hasFieldOrPropertyWithValue("code", CurrentActor.PERMISSION_DENIED);

        assertThat(this.log.list).singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertThat(messages()).singleElement().asString().isEqualTo(
                "PERMISSION_DENIED user=" + USER + " required=catalogImport.approve POST " + TEMPLATE);
    }

    /** De auditregel draagt nooit het subject, de querystring of de concrete URI met haar id's. */
    @Test
    void theAuditLineNeverCarriesTheSubjectTheQueryStringOrTheConcreteUri() {
        signIn(USER);
        MockHttpServletRequest request = request();
        request.setRequestURI("/api/catalog-import/bundles/4711/freeze");
        request.setQueryString("status=AWAITING_APPROVAL&identityHash=abc123");
        PermissionInterceptor interceptor = interceptor(Permission.MANAGE);

        assertThatThrownBy(() -> interceptor.preHandle(request, this.response, handler("needsApprove")))
                .isInstanceOf(ActorNotAllowedException.class);

        assertThat(messages()).singleElement().asString()
                .doesNotContain("test-sub-")
                .doesNotContain("4711")
                .doesNotContain("identityHash")
                .contains(TEMPLATE);
    }

    /** Zonder padtemplate wordt er nooit op de ruwe URI teruggevallen — die draagt id's. */
    @Test
    void withoutAPathTemplateTheAuditLineSaysUnknownInsteadOfTheUri() {
        signIn(USER);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/catalog-import/bundles/4711/freeze");
        PermissionInterceptor interceptor = interceptor(Permission.READ);

        assertThatThrownBy(() -> interceptor.preHandle(request, this.response, handler("needsApprove")))
                .isInstanceOf(ActorNotAllowedException.class);

        assertThat(messages()).singleElement().asString()
                .contains(PermissionInterceptor.UNKNOWN).doesNotContain("4711");
    }

    // --- Volgorde: system vóór de rechtencheck, en geen auditregel voor iets anders dan een weigering -------------

    @Test
    void systemIsRefusedWithSystemActorForbiddenAndWithoutAPermissionDeniedAuditLine() {
        signIn("system");
        PermissionInterceptor interceptor = interceptor(Permission.APPROVE);

        assertThatThrownBy(() -> interceptor.preHandle(request(), this.response, handler("needsApprove")))
                .isInstanceOf(ActorNotAllowedException.class)
                .hasFieldOrPropertyWithValue("code", CurrentActor.SYSTEM_ACTOR_FORBIDDEN);

        assertThat(messages()).isEmpty();
    }

    // --- Bron onbereikbaar: doorgeven als 503, nooit als weigering ------------------------------------------------

    @Test
    void anUnavailableSourcePropagatesAndIsNotLoggedAsADenial() {
        signIn(USER);
        CurrentActor actor = new CurrentActor(identity -> {
            throw new PermissionSourceUnavailableException("stub is down");
        });
        PermissionInterceptor interceptor = new PermissionInterceptor(actor);

        assertThatThrownBy(() -> interceptor.preHandle(request(), this.response, handler("needsApprove")))
                .isInstanceOf(PermissionSourceUnavailableException.class);

        assertThat(messages()).isEmpty();
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    private PermissionInterceptor interceptor(Permission... granted) {
        return interceptor(new AtomicInteger(), granted);
    }

    private PermissionInterceptor interceptor(AtomicInteger calls, Permission... granted) {
        Set<Permission> raw = Set.of(granted);
        PermissionSource source = identity -> {
            calls.incrementAndGet();
            return raw;
        };
        return new PermissionInterceptor(new CurrentActor(source));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/catalog-import/bundles/1/freeze");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, TEMPLATE);
        return request;
    }

    private static HandlerMethod handler(String methodName) {
        try {
            Method method = StubController.class.getDeclaredMethod(methodName);
            return new HandlerMethod(new StubController(), method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> messages() {
        return this.log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static void signIn(String username) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("token")
                .subject("test-sub-" + username)
                .claim("preferred_username", username)
                .build();
        OidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken,
                "preferred_username");
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    /** Vier handlervormen: geannoteerd (twee rechten), uitgezonderd, en vergeten. */
    @SuppressWarnings("unused")
    static final class StubController {

        @RequiresPermission(Permission.APPROVE)
        void needsApprove() {
        }

        @RequiresPermission(Permission.MANAGE)
        void needsManage() {
        }

        @NoPermissionRequired
        void exempted() {
        }

        void unannotated() {
        }
    }
}
