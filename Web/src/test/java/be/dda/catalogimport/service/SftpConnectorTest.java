package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import be.dda.catalogimport.domain.DeliveryFileConditionKind;
import be.dda.catalogimport.service.SftpConnector.HostKey;
import be.dda.catalogimport.service.SftpConnector.Listing;
import be.dda.catalogimport.service.SftpConnector.PasswordSource;
import be.dda.catalogimport.service.SftpConnector.PinnedHostKey;
import be.dda.catalogimport.service.SftpConnector.RemoteFile;
import be.dda.catalogimport.service.support.RemoteFileSelection;
import be.dda.catalogimport.service.support.RemoteFileSelection.Condition;
import be.dda.catalogimport.testsupport.SftpTestServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bouwstap K-4a: de SFTP-adapter tegen een embedded MINA-server ({@code docs/design/leveringsconfiguratie-design.md}
 * par. 4.3-4.4, 5 en 8; beslissingslog 2026-09-29 L4b, L5, L8, A5, A12, A17, A18). Geen Spring, geen database.
 *
 * <ul>
 *   <li><b>Regel L5:</b> enkel de vastgepinde sleutel wordt aanvaard. <b>Bewijs:</b> juiste vingerafdruk → aanmelding en
 *       listing; verkeerde → {@code SFTP_HOST_KEY_MISMATCH}, <b>zonder</b> wachtwoordpoging op de server en zonder dat
 *       het wachtwoord zelfs maar opgevraagd werd.</li>
 *   <li><b>RSA-afbeelding:</b> een RSA-sleutel wordt als {@code rsa-sha2-512} gerapporteerd; een pin op
 *       {@code rsa-sha2-256} met dezelfde vingerafdruk werkt ook (vergelijking op de sleutel).</li>
 *   <li><b>A17/A18/A12:</b> geen recursie, enkel gewone bestanden; meer dan de listing-cap = fout, nooit afgekapt;
 *       exact de cap mag; een niet-antwoordende poort faalt binnen de connect-time-out.</li>
 *   <li><b>Voorwaarden:</b> EN binnen een groep, OF tussen groepen, {@code ALL_FILES}.</li>
 *   <li><b>L8/ed25519 (K-4a):</b> ssh-ed25519 hostsleutels worden ondersteund (met BouncyCastle).</li>
 * </ul>
 */
class SftpConnectorTest {

    private static final String PASSWORD = "Sftp-K4a-Wachtw00rd!";

    @TempDir
    static Path root;

    private static KeyPair primaryKey;
    private static SftpTestServer server;

    private final FetchHostPolicy policy = new FetchHostPolicy("127.0.0.1", true, InetAddress::getAllByName);
    private final SftpConnector connector = new SftpConnector(Duration.ofSeconds(3), Duration.ofSeconds(5),
            Duration.ofSeconds(10), 50);

    @BeforeAll
    static void startServer() throws Exception {
        Path in = Files.createDirectories(root.resolve("in"));
        for (String name : List.of("PRIJS_2026.csv", "prijs_oud.CSV", "voorraad.csv", "PRIJS_2026.txt",
                "archief.tar.gz")) {
            Files.writeString(in.resolve(name), "inhoud van " + name, StandardCharsets.UTF_8);
        }
        Files.createDirectories(in.resolve("PRIJS_submap.csv")); // een map die op de voorwaarden lijkt
        Files.writeString(in.resolve("PRIJS_submap.csv").resolve("dieper.csv"), "niet recursief");
        Path exact = Files.createDirectories(root.resolve("precies"));
        Path big = Files.createDirectories(root.resolve("groot"));
        for (int i = 1; i <= 5; i++) {
            Files.writeString(exact.resolve("f" + i + ".csv"), "x");
            Files.writeString(big.resolve("f" + i + ".csv"), "x");
        }
        Files.writeString(big.resolve("f6.csv"), "x");
        primaryKey = SftpTestServer.primaryHostKey();
        server = SftpTestServer.start(root, PASSWORD, primaryKey);
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    // --- Normaal pad ---------------------------------------------------------------------------------------------

    @Test
    void thePinnedKeyAndTheRightPasswordGiveAListingOfRegularFilesOnly() {
        Listing listing = connector.listDirectory(target(), SftpTestServer.USERNAME, pinned(), () -> PASSWORD, "/in",
                RemoteFileSelection.allFiles());

        assertThat(listing.presentedHostKeyFingerprint()).isEqualTo(expectedFingerprint());
        // 5 bestanden + 1 submap; de submap telt als ingang maar wordt nooit gekozen (geen recursie, A17).
        assertThat(listing.entryCount()).isEqualTo(6);
        assertThat(listing.matchedFiles()).extracting(RemoteFile::name).containsExactlyInAnyOrder("PRIJS_2026.csv",
                "prijs_oud.CSV", "voorraad.csv", "PRIJS_2026.txt", "archief.tar.gz");
        RemoteFile voorraad = listing.matchedFiles().stream().filter(f -> f.name().equals("voorraad.csv")).findFirst()
                .orElseThrow();
        assertThat(voorraad.size()).isEqualTo("inhoud van voorraad.csv".getBytes(StandardCharsets.UTF_8).length);
        assertThat(voorraad.modifiedAt()).isNotNull();
    }

    @Test
    void theScanReportsTheKeyAndNeverLogsIn() {
        int attempts = server.passwordAttempts();

        HostKey key = connector.scanHostKey(target());

        assertThat(key.algorithm()).isEqualTo(SftpTestServer.algorithmOf(primaryKey));
        assertThat(key.fingerprintSha256()).isEqualTo(expectedFingerprint());
        assertThat(ConnectionProfileService.requireFingerprint(key.fingerprintSha256()))
                .as("the scan result is accepted as-is by the LC-2 validation").isEqualTo(key.fingerprintSha256());
        assertThat(server.passwordAttempts()).isEqualTo(attempts);
    }

    // --- Hostsleutel en aanmelding ---------------------------------------------------------------------------------

    @Test
    void aWrongFingerprintIsAMismatchWithoutAnyLoginAttempt() {
        int attempts = server.passwordAttempts();
        AtomicBoolean asked = new AtomicBoolean();
        PasswordSource password = () -> {
            asked.set(true);
            return PASSWORD;
        };
        PinnedHostKey wrong = new PinnedHostKey(SftpTestServer.algorithmOf(primaryKey), randomFingerprint());

        FetchFailureException failure = failureOf(
                () -> connector.verifyLogin(target(), SftpTestServer.USERNAME, wrong, password));

        assertThat(failure.getCode()).isEqualTo(FetchOutcomeCodes.SFTP_HOST_KEY_MISMATCH);
        assertThat(failure.getPresentedHostKeyFingerprint()).isEqualTo(expectedFingerprint());
        assertThat(asked).as("the password is never even decrypted").isFalse();
        assertThat(server.passwordAttempts()).as("no login attempt reached the server").isEqualTo(attempts);
        assertThat(failure.getMessage()).doesNotContain(PASSWORD).doesNotContain("SSH-2.0");
    }

    @Test
    void aWrongPasswordIsAnAuthenticationFailure() {
        int attempts = server.passwordAttempts();

        FetchFailureException failure = failureOf(
                () -> connector.verifyLogin(target(), SftpTestServer.USERNAME, pinned(), () -> "Fout-Wachtwoord-1"));

        assertThat(failure.getCode()).isEqualTo(FetchOutcomeCodes.SFTP_AUTHENTICATION_FAILED);
        assertThat(failure.getPresentedHostKeyFingerprint()).isEqualTo(expectedFingerprint());
        assertThat(server.passwordAttempts()).isGreaterThan(attempts);
        assertThat(failure.getMessage()).doesNotContain("Fout-Wachtwoord-1");

        HostKey ok = connector.verifyLogin(target(), SftpTestServer.USERNAME, pinned(), () -> PASSWORD);
        assertThat(ok.fingerprintSha256()).isEqualTo(expectedFingerprint());
    }

    @Test
    void anUndecryptableCredentialIsReportedAfterTheHostKeyAndBeforeAnyLogin() {
        int attempts = server.passwordAttempts();

        FetchFailureException failure = failureOf(
                () -> connector.verifyLogin(target(), SftpTestServer.USERNAME, pinned(), () -> {
                    throw new CredentialUndecryptableException("test");
                }));

        assertThat(failure.getCode()).isEqualTo(FetchOutcomeCodes.CREDENTIAL_UNDECRYPTABLE);
        assertThat(failure.getPresentedHostKeyFingerprint()).isEqualTo(expectedFingerprint());
        assertThat(server.passwordAttempts()).isEqualTo(attempts);
    }

    // --- Map -------------------------------------------------------------------------------------------------------

    @Test
    void aMissingDirectoryOrAFileInsteadOfADirectoryIsNotFound() {
        for (String directory : List.of("/bestaat-niet", "/in/voorraad.csv")) {
            FetchFailureException failure = failureOf(
                    () -> connector.listDirectory(target(), SftpTestServer.USERNAME, pinned(), () -> PASSWORD,
                            directory, RemoteFileSelection.allFiles()));
            assertThat(failure.getCode()).as(directory).isEqualTo(FetchOutcomeCodes.REMOTE_DIRECTORY_NOT_FOUND);
            assertThat(failure.getPresentedHostKeyFingerprint()).isEqualTo(expectedFingerprint());
        }
    }

    @Test
    void conditionsAreAndWithinAGroupAndOrBetweenGroups() {
        RemoteFileSelection selection = RemoteFileSelection.conditions(List.of(
                new Condition(1, DeliveryFileConditionKind.NAME_STARTS_WITH, "PRIJS_", true),
                new Condition(1, DeliveryFileConditionKind.EXTENSION_IS, "csv", false),
                new Condition(2, DeliveryFileConditionKind.NAME_EQUALS, "voorraad.csv", true)));

        Listing listing = connector.listDirectory(target(), SftpTestServer.USERNAME, pinned(), () -> PASSWORD, "/in",
                selection);

        // Groep 1: PRIJS_2026.csv (prijs_oud.CSV valt af op het hoofdlettergevoelige voorvoegsel, PRIJS_2026.txt op de
        // extensie, PRIJS_submap.csv is een map). Groep 2: voorraad.csv.
        assertThat(listing.matchedFiles()).extracting(RemoteFile::name)
                .containsExactlyInAnyOrder("PRIJS_2026.csv", "voorraad.csv");
        assertThat(listing.entryCount()).isEqualTo(6);
    }

    @Test
    void theSelectionRulesAreLiteralAndHonourCaseSensitivity() {
        RemoteFileSelection insensitive = RemoteFileSelection.conditions(List.of(
                new Condition(1, DeliveryFileConditionKind.NAME_STARTS_WITH, "prijs_", false),
                new Condition(1, DeliveryFileConditionKind.NAME_ENDS_WITH, ".csv", false)));
        assertThat(insensitive.matches("PRIJS_2026.csv")).isTrue();
        assertThat(insensitive.matches("prijs_oud.CSV")).isTrue();
        assertThat(insensitive.matches("PRIJS_2026.txt")).isFalse();

        RemoteFileSelection extension = RemoteFileSelection.conditions(List.of(
                new Condition(1, DeliveryFileConditionKind.EXTENSION_IS, "tar.gz", true)));
        assertThat(extension.matches("archief.tar.gz")).isTrue();
        assertThat(extension.matches("archief.gz")).isFalse();
        assertThat(extension.matches("archieftar.gz")).isFalse();

        RemoteFileSelection contains = RemoteFileSelection.conditions(List.of(
                new Condition(1, DeliveryFileConditionKind.NAME_CONTAINS, "*", true)));
        assertThat(contains.matches("PRIJS_2026.csv")).as("no wildcards: * is literal").isFalse();
        assertThat(contains.matches("a*b")).isTrue();

        assertThat(RemoteFileSelection.allFiles().matches("wat-dan-ook")).isTrue();
        assertThatThrownBy(() -> RemoteFileSelection.conditions(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moreEntriesThanTheListingCapIsRefusedAndExactlyTheCapIsAllowed() {
        SftpConnector capped = new SftpConnector(Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10), 5);

        Listing exact = capped.listDirectory(target(), SftpTestServer.USERNAME, pinned(), () -> PASSWORD, "/precies",
                RemoteFileSelection.allFiles());
        assertThat(exact.entryCount()).isEqualTo(5);
        assertThat(exact.matchedFiles()).hasSize(5);

        FetchFailureException failure = failureOf(
                () -> capped.listDirectory(target(), SftpTestServer.USERNAME, pinned(), () -> PASSWORD, "/groot",
                        RemoteFileSelection.allFiles()));
        assertThat(failure.getCode()).isEqualTo(FetchOutcomeCodes.FETCH_LISTING_TOO_LARGE);
        assertThat(failure.getMessage()).contains("5");
    }

    // --- RSA ---------------------------------------------------------------------------------------------------------

    @Test
    void anRsaHostKeyIsReportedAsRsaSha2512AndPinnedOnTheKeyFingerprint() throws Exception {
        KeyPair rsa = SftpTestServer.rsa();
        String expected = SftpTestServer.sha256(SftpTestServer.rsaBlob((RSAPublicKey) rsa.getPublic()));
        try (SftpTestServer rsaServer = SftpTestServer.start(root, PASSWORD, rsa)) {
            FetchTarget target = policy.resolve("127.0.0.1", rsaServer.port());

            HostKey scanned = connector.scanHostKey(target);
            assertThat(scanned.algorithm()).isEqualTo("rsa-sha2-512");
            assertThat(scanned.fingerprintSha256()).isEqualTo(expected);
            assertThat(ConnectionProfileService.HOST_KEY_ALGORITHMS).contains(scanned.algorithm());

            for (String algorithm : List.of("rsa-sha2-512", "rsa-sha2-256")) {
                HostKey ok = connector.verifyLogin(target, SftpTestServer.USERNAME,
                        new PinnedHostKey(algorithm, expected), () -> PASSWORD);
                assertThat(ok.fingerprintSha256()).as(algorithm).isEqualTo(expected);
            }
            FetchFailureException mismatch = failureOf(
                    () -> connector.verifyLogin(target, SftpTestServer.USERNAME,
                            new PinnedHostKey("rsa-sha2-512", randomFingerprint()), () -> PASSWORD));
            assertThat(mismatch.getCode()).isEqualTo(FetchOutcomeCodes.SFTP_HOST_KEY_MISMATCH);
            assertThat(mismatch.getPresentedHostKeyFingerprint()).isEqualTo(expected);
        }
    }

    // --- ed25519-hostsleutels (L8, design par. 8, K-4a) ---------------------------------------------------------------

    @Test
    void ed25519HostKeysAreSupported() throws Exception {
        assertThat(SftpTestServer.ed25519Supported())
                .as("MINA SSHD 2.15.0 + BouncyCastle supports ssh-ed25519 host keys; if this fails, ed25519 needs "
                        + "a different configuration or library version")
                .isTrue();

        KeyPair ed25519 = SftpTestServer.ed25519();
        try (SftpTestServer edServer = SftpTestServer.start(root, PASSWORD, ed25519)) {
            FetchTarget target = policy.resolve("127.0.0.1", edServer.port());
            HostKey scanned = connector.scanHostKey(target);
            assertThat(scanned.algorithm()).isEqualTo("ssh-ed25519");
            assertThat(scanned.fingerprintSha256()).isEqualTo(SftpTestServer.expectedFingerprint(ed25519.getPublic()));
            HostKey ok = connector.verifyLogin(target, SftpTestServer.USERNAME,
                    new PinnedHostKey("ssh-ed25519", scanned.fingerprintSha256()), () -> PASSWORD);
            assertThat(ok.fingerprintSha256()).isEqualTo(scanned.fingerprintSha256());
        }
    }

    // --- Time-outs en onbereikbaar ------------------------------------------------------------------------------------

    @Test
    void aPortThatAcceptsButNeverAnswersFailsWithinTheConnectTimeout() throws Exception {
        SftpConnector quick = new SftpConnector(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(5), 50);
        // Een socket die luistert maar nooit accept() doet: de TCP-verbinding lukt (backlog), er komt nooit een banner.
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            FetchTarget target = policy.resolve("127.0.0.1", silent.getLocalPort());
            long start = System.nanoTime();

            FetchFailureException failure = failureOf(
                    () -> quick.scanHostKey(target));

            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(failure.getCode()).isEqualTo(FetchOutcomeCodes.CONNECTION_FAILED);
            assertThat(failure.getPresentedHostKeyFingerprint()).isNull();
            assertThat(elapsedMillis).as("fails within the connect time-out (1 s) plus margin").isLessThan(5_000);
        }
    }

    @Test
    void aClosedPortIsAConnectionFailure() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = probe.getLocalPort();
        }
        FetchTarget target = policy.resolve("127.0.0.1", closedPort);

        FetchFailureException failure = failureOf(
                () -> connector.verifyLogin(target, SftpTestServer.USERNAME, pinned(), () -> PASSWORD));

        assertThat(failure.getCode()).isEqualTo(FetchOutcomeCodes.CONNECTION_FAILED);
    }

    // --- Configuratie --------------------------------------------------------------------------------------------------

    @Test
    void invalidTimeoutsOrListingCapStopTheStartup() {
        assertThatThrownBy(() -> new SftpConnector(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("connect-timeout");
        assertThatThrownBy(() -> new SftpConnector(Duration.ofSeconds(1), Duration.ofSeconds(-1), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth-timeout");
        assertThatThrownBy(() -> new SftpConnector(Duration.ofSeconds(1), Duration.ofSeconds(1), null, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("idle-timeout");
        assertThatThrownBy(() -> new SftpConnector(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("listing-cap");
        assertThat(new SftpConnector(SftpConnector.DEFAULT_CONNECT_TIMEOUT, SftpConnector.DEFAULT_AUTH_TIMEOUT,
                SftpConnector.DEFAULT_IDLE_TIMEOUT, SftpConnector.DEFAULT_LISTING_CAP).listingCap()).isEqualTo(10_000);
    }

    // --- Helpers -------------------------------------------------------------------------------------------------------

    private FetchTarget target() {
        return policy.resolve("127.0.0.1", server.port());
    }

    private static PinnedHostKey pinned() {
        return new PinnedHostKey(SftpTestServer.algorithmOf(primaryKey), expectedFingerprint());
    }

    private static String expectedFingerprint() {
        return SftpTestServer.expectedFingerprint(primaryKey.getPublic());
    }

    /** De verwachte {@link FetchFailureException} (zelfde patroon als de andere tests: {@code catchThrowable}). */
    static FetchFailureException failureOf(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(FetchFailureException.class);
        return (FetchFailureException) thrown;
    }

    static String randomFingerprint() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, SftpConnectorTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
