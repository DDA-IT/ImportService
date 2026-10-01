package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import be.dda.catalogimport.service.SftpConnector.DownloadPlan;
import be.dda.catalogimport.service.SftpConnector.FetchResult;
import be.dda.catalogimport.service.SftpConnector.Listing;
import be.dda.catalogimport.service.SftpConnector.PinnedHostKey;
import be.dda.catalogimport.service.SftpConnector.RemoteFile;
import be.dda.catalogimport.service.support.RemoteFileSelection;
import be.dda.catalogimport.testsupport.SftpTestServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bouwstap K-4b: de download van {@link SftpConnector#fetchOne} tegen een embedded MINA-server
 * ({@code docs/design/leveringsconfiguratie-design.md} par. 4.1 stap 6-7 en 4.3). Geen Spring, geen database.
 * <p>
 * Het racegedrag ("gewijzigd tijdens de overdracht", "groeit tijdens het streamen") wordt deterministisch nagebootst:
 * {@link DownloadPlan#choose} draait na de listing en vóór de download, dus de test wijzigt het bestand dáár.
 *
 * <ul>
 *   <li><b>Normaal:</b> het gekozen bestand komt byte voor byte binnen; geen keuze = geen download.</li>
 *   <li><b>Byte-guard:</b> groeit het bestand na de listing voorbij de grens, dan {@code FETCH_FILE_TOO_LARGE} en de
 *       stream stopt (hoogstens de grens aan bytes gelezen).</li>
 *   <li><b>Her-stat:</b> andere wijzigingstijd of grootte na de download = {@code FETCH_FILE_CHANGED_DURING_TRANSFER};
 *       verdwenen bestand idem.</li>
 *   <li><b>Fout van de aanroeper</b> (bv. het lokale archief) komt ongewijzigd terug, nooit als {@code CONNECTION_FAILED}.</li>
 * </ul>
 */
class SftpDownloadTest {

    private static final String PASSWORD = "Sftp-K4b-Wachtw00rd!";

    @TempDir
    static Path root;

    private static KeyPair hostKey;
    private static SftpTestServer server;

    private final FetchHostPolicy policy = new FetchHostPolicy("127.0.0.1", true, InetAddress::getAllByName);
    private final SftpConnector connector = new SftpConnector(Duration.ofSeconds(3), Duration.ofSeconds(5),
            Duration.ofSeconds(10), 50);

    @BeforeAll
    static void startServer() throws Exception {
        Files.createDirectories(root.resolve("in"));
        hostKey = SftpTestServer.primaryHostKey();
        server = SftpTestServer.start(root, PASSWORD, hostKey);
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void theChosenFileArrivesCompletelyAndUnchanged() throws Exception {
        String name = write("normaal.csv", "LEVERANCIER;PRIJS\nACME;10,00\n");
        ByteArrayOutputStream received = new ByteArrayOutputStream();

        FetchResult result = fetch(listing -> { }, name, received, 1_000);

        assertThat(result.downloaded()).isNotNull();
        assertThat(result.downloaded().name()).isEqualTo(name);
        assertThat(result.transferredBytes()).isEqualTo(result.downloaded().size());
        assertThat(received.toString(StandardCharsets.UTF_8)).isEqualTo("LEVERANCIER;PRIJS\nACME;10,00\n");
    }

    @Test
    void withoutAChoiceNothingIsDownloaded() throws Exception {
        write("niet-gekozen.csv", "x");
        AtomicReference<Boolean> received = new AtomicReference<>(false);

        FetchResult result = connector.fetchOne(target(), SftpTestServer.USERNAME, pinned(), () -> PASSWORD, "/in",
                RemoteFileSelection.allFiles(), 1_000, new DownloadPlan() {
                    @Override
                    public RemoteFile choose(Listing listing) {
                        return null;
                    }

                    @Override
                    public void receive(RemoteFile file, InputStream content) {
                        received.set(true);
                    }
                });

        assertThat(result.downloaded()).isNull();
        assertThat(result.listing().matchedFiles()).isNotEmpty();
        assertThat(received.get()).isFalse();
    }

    @Test
    void aFileThatGrowsBeyondTheLimitWhileStreamingIsStoppedAsTooLarge() throws Exception {
        String name = write("groeit.csv", "klein");
        ByteArrayOutputStream received = new ByteArrayOutputStream();

        Throwable thrown = catchThrowable(() -> fetch(listing -> append(name, "x".repeat(5_000)), name, received, 100));

        assertThat(thrown).isInstanceOf(FetchFailureException.class);
        assertThat(((FetchFailureException) thrown).getCode()).isEqualTo(FetchOutcomeCodes.FETCH_FILE_TOO_LARGE);
        assertThat(received.size()).as("never more than the limit is passed on").isLessThanOrEqualTo(100);
    }

    @Test
    void anotherModificationTimeAfterTheDownloadIsChangedDuringTransfer() throws Exception {
        String name = write("herschreven.csv", "zelfde lengte");
        Instant later = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(1, ChronoUnit.HOURS);

        Throwable thrown = catchThrowable(() -> fetch(listing -> touch(name, later), name,
                new ByteArrayOutputStream(), 1_000));

        assertThat(((FetchFailureException) thrown).getCode())
                .isEqualTo(FetchOutcomeCodes.FETCH_FILE_CHANGED_DURING_TRANSFER);
    }

    @Test
    void aFileThatGrowsWithinTheLimitIsChangedDuringTransfer() throws Exception {
        String name = write("aangevuld.csv", "begin");

        Throwable thrown = catchThrowable(() -> fetch(listing -> append(name, "-meer"), name,
                new ByteArrayOutputStream(), 1_000));

        assertThat(((FetchFailureException) thrown).getCode())
                .isEqualTo(FetchOutcomeCodes.FETCH_FILE_CHANGED_DURING_TRANSFER);
    }

    @Test
    void aFileThatDisappearsBeforeTheDownloadIsChangedDuringTransfer() throws Exception {
        String name = write("verdwijnt.csv", "weg");

        Throwable thrown = catchThrowable(() -> fetch(listing -> delete(name), name, new ByteArrayOutputStream(),
                1_000));

        assertThat(((FetchFailureException) thrown).getCode())
                .isEqualTo(FetchOutcomeCodes.FETCH_FILE_CHANGED_DURING_TRANSFER);
    }

    @Test
    void aFailureOfTheReceiverIsPassedOnUnchangedAndNeverDisguisedAsConnectionFailed() throws Exception {
        String name = write("archief-vol.csv", "inhoud");
        UncheckedIOException diskFull = new UncheckedIOException("disk full", new IOException("disk full"));

        Throwable thrown = catchThrowable(() -> connector.fetchOne(target(), SftpTestServer.USERNAME, pinned(),
                () -> PASSWORD, "/in", RemoteFileSelection.allFiles(), 1_000, new DownloadPlan() {
                    @Override
                    public RemoteFile choose(Listing listing) {
                        return byName(listing, name);
                    }

                    @Override
                    public void receive(RemoteFile file, InputStream content) {
                        throw diskFull;
                    }
                }));

        assertThat(thrown).isSameAs(diskFull);
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    private FetchResult fetch(Consumer<Listing> afterListing, String name, ByteArrayOutputStream received, long limit) {
        return connector.fetchOne(target(), SftpTestServer.USERNAME, pinned(), () -> PASSWORD, "/in",
                RemoteFileSelection.allFiles(), limit, new DownloadPlan() {
                    @Override
                    public RemoteFile choose(Listing listing) {
                        afterListing.accept(listing);
                        return byName(listing, name);
                    }

                    @Override
                    public void receive(RemoteFile file, InputStream content) {
                        try {
                            content.transferTo(received);
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }
                });
    }

    private static RemoteFile byName(Listing listing, String name) {
        return listing.matchedFiles().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private FetchTarget target() {
        return policy.resolve("127.0.0.1", server.port());
    }

    private static PinnedHostKey pinned() {
        return new PinnedHostKey(SftpTestServer.algorithmOf(hostKey),
                SftpTestServer.expectedFingerprint(hostKey.getPublic()));
    }

    /** Schrijft een bestand met een wijzigingstijd ruim in het verleden (op de seconde). */
    private static String write(String name, String content) throws IOException {
        Path file = root.resolve("in").resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(2, ChronoUnit.HOURS)));
        return name;
    }

    private static void append(String name, String more) {
        try {
            Path file = root.resolve("in").resolve(name);
            FileTime before = Files.getLastModifiedTime(file);
            Files.writeString(file, more, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            // Zelfde wijzigingstijd: enkel de grootte verraadt de wijziging (of de byte-guard grijpt in).
            Files.setLastModifiedTime(file, before);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void touch(String name, Instant modified) {
        try {
            Files.setLastModifiedTime(root.resolve("in").resolve(name), FileTime.from(modified));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void delete(String name) {
        try {
            Files.delete(root.resolve("in").resolve(name));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
