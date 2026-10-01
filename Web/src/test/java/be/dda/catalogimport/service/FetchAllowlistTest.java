package be.dda.catalogimport.service;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.web.Permission;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap K-4a, de allowlist van uitgaande verbindingen (beslissingslog 2026-09-29 L4b;
 * {@code docs/design/leveringsconfiguratie-design.md} par. 5 en 11, Ontdekking 3: SSRF en poortscan).
 *
 * <ul>
 *   <li><b>Regel:</b> fail-closed. Niet gezet = scan en beide tests bestaan niet: 404 {@code FETCH_NOT_CONFIGURED},
 *       zonder event. <b>Bewijs:</b> deze Spring-context heeft de property bewust leeg.</li>
 *   <li><b>Regel:</b> gezet maar ongeldig = de applicatie start niet. <b>Bewijs:</b> de constructor weigert lege
 *       ingangen, wildcards, schema's en ongeldige namen.</li>
 *   <li><b>Regel:</b> exacte, genormaliseerde hostnamen; een host buiten de lijst is {@code FETCH_HOST_NOT_ALLOWED}.</li>
 *   <li><b>Regel:</b> DNS één keer resolven, op dat IP verbinden; loopback enkel met de dev/test-toelating;
 *       link-local (metadata-adres), multicast, "any", {@code 0.0.0.0/8} en broadcast altijd geweigerd, ook als maar één
 *       van de adressen zo is.</li>
 * </ul>
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false",
        "catalogimport.fetch.allowed-hosts="})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FetchAllowlistTest {

    private static final String API = "/api/catalog-import";
    private static final String USER = "an.janssens@example.test";

    @TempDir
    static Path archiveRoot;
    @TempDir
    static Path localSourceRoot;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> localSourceRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FetchHostPolicy contextPolicy;

    // --- Niet geconfigureerd (HTTP) ----------------------------------------------------------------------------

    @Test
    void withoutTheAllowlistScanAndTestsAnswer404AndWriteNothing() throws Exception {
        assertThat(contextPolicy.configured()).isFalse();
        long before = eventCount();

        mockMvc.perform(post(API + "/connection-profiles/host-key-scan").with(as(USER, Permission.MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"host\":\"sftp.example.test\",\"port\":22}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(FetchOutcomeCodes.FETCH_NOT_CONFIGURED));
        for (String path : List.of("/connection-profile-versions/{id}/test", "/delivery-configuration-versions/{id}/test")) {
            mockMvc.perform(post(API + path, 999_999_999L).with(as(USER, Permission.MANAGE)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(FetchOutcomeCodes.FETCH_NOT_CONFIGURED));
        }

        assertThat(eventCount()).isEqualTo(before);
    }

    // --- Opstartvalidatie ------------------------------------------------------------------------------------------

    @Test
    void anInvalidAllowlistStopsTheStartup() {
        for (String invalid : List.of("sftp.a.test,,sftp.b.test", ",", "sftp.a.test,", "*.example.test",
                "sftp://sftp.a.test", "user@sftp.a.test", "sftp a.test", "sftp.a.test/in")) {
            assertThatThrownBy(() -> new FetchHostPolicy(invalid, false))
                    .as(invalid).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catalogimport.fetch.allowed-hosts");
        }
    }

    @Test
    void blankMeansNotConfiguredAndEveryUseIs404() {
        for (String blank : new String[] {null, "", "   "}) {
            FetchHostPolicy policy = new FetchHostPolicy(blank, false);
            assertThat(policy.configured()).isFalse();
            Throwable thrown = catchThrowable(() -> policy.resolve("sftp.example.test", 22));
            assertThat(thrown).isInstanceOf(NotFoundException.class);
            assertThat(((NotFoundException) thrown).getCode()).isEqualTo(FetchOutcomeCodes.FETCH_NOT_CONFIGURED);
        }
    }

    // --- Exacte namen ------------------------------------------------------------------------------------------------

    @Test
    void entriesAreNormalisedAndMatchedExactly() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        FetchHostPolicy policy = new FetchHostPolicy(" SFTP.Lev-Een.TEST. , sftp.lev-twee.test", false, host -> {
            lookups.incrementAndGet();
            return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
        });

        assertThat(policy.isAllowed("sftp.lev-een.test")).isTrue();
        assertThat(policy.isAllowed("  SFTP.LEV-EEN.TEST.")).isTrue();
        assertThat(policy.isAllowed("lev-een.test")).as("no domain matching").isFalse();
        assertThat(policy.isAllowed("x.sftp.lev-een.test")).as("no subdomain matching").isFalse();
        assertThat(policy.isAllowed("sftp.lev-drie.test")).isFalse();

        FetchTarget target = policy.resolve("SFTP.LEV-EEN.TEST", 2222);
        assertThat(target.host()).isEqualTo("sftp.lev-een.test");
        assertThat(target.port()).isEqualTo(2222);
        // Verbonden wordt op de IP-literal, zonder naam: de library resolvet niets meer (DNS-rebinding).
        assertThat(target.socketAddress().getHostString()).isEqualTo("93.184.216.34");
        assertThat(lookups).as("exactly one DNS lookup").hasValue(1);

        assertThat(code(() -> policy.resolve("sftp.lev-drie.test", 22))).isEqualTo(FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED);
        assertThat(lookups).as("a host outside the list is never looked up").hasValue(1);
        assertThatThrownBy(() -> policy.resolve("sftp.lev-een.test", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnresolvableNameIsAConnectionFailure() {
        FetchHostPolicy policy = new FetchHostPolicy("sftp.bestaat-niet.test", false, host -> {
            throw new UnknownHostException("test");
        });

        assertThat(code(() -> policy.resolve("sftp.bestaat-niet.test", 22)))
                .isEqualTo(FetchOutcomeCodes.CONNECTION_FAILED);
    }

    // --- Verboden adressen ---------------------------------------------------------------------------------------------

    @Test
    void loopbackIsRefusedWithoutTheDevelopmentPermission() throws Exception {
        FetchHostPolicy strict = new FetchHostPolicy("127.0.0.1,localhost,[::1],sftp.lokaal.test", false,
                host -> host.equals("sftp.lokaal.test")
                        ? new InetAddress[] {InetAddress.getByName("127.0.0.2")}
                        : InetAddress.getAllByName(host));
        for (String host : List.of("127.0.0.1", "localhost", "[::1]", "sftp.lokaal.test")) {
            assertThat(code(() -> strict.resolve(host, 22))).as(host).isEqualTo(FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED);
        }

        FetchHostPolicy development = new FetchHostPolicy("127.0.0.1", true);
        assertThat(development.resolve("127.0.0.1", 22).address().isLoopbackAddress()).isTrue();
    }

    @Test
    void linkLocalMulticastAnyAndBroadcastAreAlwaysRefusedEvenWithLoopbackAllowed() throws Exception {
        for (String address : List.of("169.254.169.254", "224.0.0.1", "0.0.0.0", "0.1.2.3", "255.255.255.255",
                "fe80::1", "ff02::1", "::")) {
            FetchHostPolicy policy = new FetchHostPolicy("sftp.lev.test", true,
                    host -> new InetAddress[] {InetAddress.getByName(address)});
            assertThat(code(() -> policy.resolve("sftp.lev.test", 22))).as(address)
                    .isEqualTo(FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED);
        }
    }

    @Test
    void oneForbiddenAddressAmongSeveralRefusesTheHost() throws Exception {
        FetchHostPolicy policy = new FetchHostPolicy("sftp.lev.test", false, host -> new InetAddress[] {
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")});

        assertThat(code(() -> policy.resolve("sftp.lev.test", 22))).isEqualTo(FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED);
    }

    @Test
    void privateAddressesAreAllowedWhenTheHostIsListed() throws Exception {
        FetchHostPolicy policy = new FetchHostPolicy("sftp.vpn.test", false,
                host -> new InetAddress[] {InetAddress.getByName("10.20.30.40")});

        assertThat(policy.resolve("sftp.vpn.test", 22).socketAddress().getHostString()).isEqualTo("10.20.30.40");
    }

    // --- Helpers -------------------------------------------------------------------------------------------------------

    private static String code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(FetchFailureException.class);
        return ((FetchFailureException) thrown).getCode();
    }

    private long eventCount() {
        return jdbc.queryForObject("select count(*) from acquisition_config_event", Long.class);
    }
}
