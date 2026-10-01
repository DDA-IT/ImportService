package be.dda.catalogimport.service;

import be.dda.catalogimport.service.support.RemoteFileSelection;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.kex.KexProposalOption;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.signature.Signature;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SFTP-client van CatalogImport op Apache MINA SSHD (bouwstap K-4a; beslissingslog 2026-09-29 L4, L5, L8, A5, A12,
 * A17, A18; {@code docs/design/leveringsconfiguratie-design.md} par. 4.3-4.4, 5 en 8). <b>Enkel client</b>: hostsleutel
 * scannen, aanmelden en een map lijsten (K-4a), en sinds K-4b in dezelfde sessie hoogstens één bestand streamend
 * downloaden ({@link #fetchOne}). Nooit schrijven, verplaatsen of verwijderen bij de leverancier (A3, {@code LEAVE}).
 *
 * <h2>Downloaden (K-4b)</h2>
 * {@link #fetchOne}: lijsten → de aanroeper kiest ({@link DownloadPlan#choose}, zonder netwerkverkeer) → het gekozen
 * bestand streamen naar {@link DownloadPlan#receive} met een <b>byte-guard</b> (meer dan de grens =
 * {@link FetchOutcomeCodes#FETCH_FILE_TOO_LARGE}, de stream stopt meteen) → <b>her-stat</b>: grootte en wijzigingstijd
 * moeten gelijk zijn aan de listing ({@link FetchOutcomeCodes#FETCH_FILE_CHANGED_DURING_TRANSFER}), het aantal
 * ontvangen bytes aan die grootte ({@link FetchOutcomeCodes#FETCH_TRANSFER_INCOMPLETE}). Een afgebroken leesstroom is
 * {@code FETCH_TRANSFER_INCOMPLETE}, een verdwenen bestand {@code FETCH_FILE_CHANGED_DURING_TRANSFER}. Een fout van de
 * aanroeper zelf (databank, lokaal archief) is geen serverfout en wordt ongewijzigd doorgegeven, nooit als
 * {@code CONNECTION_FAILED} vermomd.
 *
 * <h2>Verbinden (L4b)</h2>
 * Enkel op een {@link FetchTarget} van {@link FetchHostPolicy}: allowlist gecontroleerd, naam één keer geresolved,
 * verbonden wordt op het IP-adres (de library resolvet niets meer). Geen {@code ~/.ssh/config}
 * ({@link HostConfigEntryResolver#EMPTY}: een lokaal configbestand kan de host of een ProxyJump niet omleiden), geen
 * sleutelidentiteiten, enkel wachtwoordauthenticatie (A5).
 *
 * <h2>Hostsleutel (L5)</h2>
 * <ul>
 *   <li><b>Scan</b> ({@link #scanHostKey}): de client biedt {@code ssh-ed25519}, {@code ecdsa-sha2-nistp256/384/521},
 *       {@code rsa-sha2-512}, {@code rsa-sha2-256} aan (in die voorkeur; nooit {@code ssh-rsa} met SHA-1 of
 *       {@code ssh-dss}), noteert de getoonde sleutel en <b>weigert</b> hem: de sleuteluitwisseling stopt, er wordt nooit
 *       aangemeld.</li>
 *   <li><b>Vastgepind</b> ({@link #verifyLogin}, {@link #listDirectory}): de client biedt enkel de algoritmen van de
 *       vastgepinde sleutelsoort aan, zodat een server met meerdere sleutels de juiste toont, en vergelijkt de
 *       SHA-256-vingerafdruk van de <b>sleutel</b> (OpenSSH-vorm {@code SHA256:<base64 zonder opvulling>} over de
 *       SSH-wire-blob, zoals {@code ssh-keygen -lf}). Afwijking = {@link FetchOutcomeCodes#SFTP_HOST_KEY_MISMATCH},
 *       nooit auto-accept; het wachtwoord wordt dan niet eens ontsleuteld en er is geen aanmeldpoging.</li>
 *   <li><b>RSA-afbeelding (LC-2 open punt 3):</b> een RSA-sleutel heeft sleuteltype {@code ssh-rsa}, maar LC-2 laat enkel
 *       {@code rsa-sha2-256/512} toe als {@code host_key_algorithm}. De scan rapporteert daarom het onderhandelde
 *       signatuuralgoritme, {@code rsa-sha2-512} bij voorkeur ({@code rsa-sha2-256} als de server enkel dat kent).
 *       Een vastgepinde RSA-sleutel (256 of 512) laat de client {@code rsa-sha2-512} en {@code rsa-sha2-256} aanbieden,
 *       nooit {@code ssh-rsa}; de vergelijking gebeurt op de vingerafdruk van de sleutel, die voor beide gelijk is.</li>
 * </ul>
 *
 * <h2>Time-outs en limieten (A12, A18)</h2>
 * {@code catalogimport.fetch.connect-timeout} (default 15 s: TCP-verbinding én sleuteluitwisseling tot de hostsleutel),
 * {@code auth-timeout} (15 s), {@code idle-timeout} (60 s: geen verkeer binnen een sessie, o.a. tijdens het lijsten) en
 * {@code listing-cap} (10 000 ingangen). Meer ingangen = {@link FetchOutcomeCodes#FETCH_LISTING_TOO_LARGE}, nooit stil
 * afgekapt. Ongeldige waarden (niet positief) = de applicatie start niet.
 *
 * <h2>Lekpreventie</h2>
 * Het wachtwoord komt via {@link PasswordSource} pas binnen <b>na</b> een geslaagde hostsleutelcontrole, leeft enkel in
 * het geheugen van deze sessie en wordt nooit gelogd. Foutmeldingen zijn vaste teksten ({@link FetchFailureException}):
 * nooit de serverbanner, nooit de melding van de library; de log krijgt hoogstens het type van de oorzaak.
 */
@Component
public class SftpConnector {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(60);
    public static final int DEFAULT_LISTING_CAP = 10_000;

    /** Loginnaam bij de scan; wordt nooit verstuurd, want de scan stopt vóór de aanmelding. */
    static final String SCAN_USERNAME = "catalogimport-host-key-scan";

    /** Voorkeursvolgorde van de scan; nooit {@code ssh-rsa} (SHA-1) of {@code ssh-dss}. */
    static final List<String> SCAN_ALGORITHMS = List.of("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521", "rsa-sha2-512", "rsa-sha2-256");

    // SFTP-statuscodes (draft-ietf-secsh-filexfer); bewust als getal, onafhankelijk van de library-constanten.
    private static final int SSH_FX_NO_SUCH_FILE = 2;
    private static final int SSH_FX_PERMISSION_DENIED = 3;
    private static final int SSH_FX_NO_SUCH_PATH = 10;
    private static final int SSH_FX_NOT_A_DIRECTORY = 19;

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final Logger LOG = LoggerFactory.getLogger(SftpConnector.class);

    /** De sleutel die de server toonde, zoals een mens ze vergelijkt. */
    public record HostKey(String algorithm, String fingerprintSha256) {
    }

    /** De vastgepinde sleutel van een profielversie (L5). */
    public record PinnedHostKey(String algorithm, String fingerprintSha256) {

        public PinnedHostKey {
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(fingerprintSha256, "fingerprintSha256");
        }
    }

    /** Een gewoon bestand uit de maplijst; {@code modifiedAt} is {@code null} als de server geen tijd gaf. */
    public record RemoteFile(String name, long size, Instant modifiedAt) {
    }

    /**
     * Resultaat van {@link #listDirectory}: {@code entryCount} = alle ingangen van de map (zonder {@code .} en
     * {@code ..}), {@code matchedFiles} = de gewone bestanden die de selectie kiest, in de volgorde van de server.
     */
    public record Listing(String presentedHostKeyFingerprint, int entryCount, List<RemoteFile> matchedFiles) {
    }

    /**
     * Levert het wachtwoord, pas nadat de hostsleutel klopt. Mag {@link CredentialUndecryptableException} of
     * {@link SecretsNotConfiguredException} gooien; die worden {@link FetchOutcomeCodes#CREDENTIAL_UNDECRYPTABLE}.
     */
    @FunctionalInterface
    public interface PasswordSource {
        String password();
    }

    /**
     * Keuze en bestemming van een download (K-4b). Beide methoden draaien binnen de open SFTP-sessie; een
     * {@link RuntimeException} die ze gooien (behalve {@link FetchFailureException}) wordt ongewijzigd aan de aanroeper
     * van {@link #fetchOne} teruggegeven.
     */
    public interface DownloadPlan {

        /** Kiest na de listing hoogstens één bestand uit {@link Listing#matchedFiles()}; {@code null} = niets ophalen. */
        RemoteFile choose(Listing listing);

        /** Leest de begrensde stream volledig (bv. naar het archief); sluit hem niet. */
        void receive(RemoteFile file, InputStream content);
    }

    /**
     * Resultaat van {@link #fetchOne}: de listing, het gedownloade bestand ({@code null} als er niets gekozen werd) en
     * het aantal ontvangen bytes (gelijk aan de grootte uit de listing, anders was het een fout).
     */
    public record FetchResult(Listing listing, RemoteFile downloaded, long transferredBytes) {
    }

    /** Sleutelsoort, afgeleid van het algoritme of van de sleutel zelf. */
    enum KeyFamily {
        ED25519, ECDSA_P256, ECDSA_P384, ECDSA_P521, RSA, OTHER
    }

    private final Duration connectTimeout;
    private final Duration authTimeout;
    private final Duration idleTimeout;
    private final int listingCap;

    @Autowired
    public SftpConnector(@Value("${catalogimport.fetch.connect-timeout:PT15S}") Duration connectTimeout,
                         @Value("${catalogimport.fetch.auth-timeout:PT15S}") Duration authTimeout,
                         @Value("${catalogimport.fetch.idle-timeout:PT60S}") Duration idleTimeout,
                         @Value("${catalogimport.fetch.listing-cap:10000}") int listingCap) {
        this.connectTimeout = positive(connectTimeout, "catalogimport.fetch.connect-timeout");
        this.authTimeout = positive(authTimeout, "catalogimport.fetch.auth-timeout");
        this.idleTimeout = positive(idleTimeout, "catalogimport.fetch.idle-timeout");
        if (listingCap < 1) {
            throw new IllegalStateException("catalogimport.fetch.listing-cap must be at least 1");
        }
        this.listingCap = listingCap;
    }

    public int listingCap() {
        return listingCap;
    }

    // --- Publieke operaties ----------------------------------------------------------------------------------

    /**
     * Verbindt zonder aanmelding en geeft de getoonde hostsleutel (algoritme + SHA-256-vingerafdruk). De sleutel wordt
     * geweigerd: de sessie stopt na de sleuteluitwisseling.
     *
     * @throws FetchFailureException {@link FetchOutcomeCodes#CONNECTION_FAILED}
     */
    public HostKey scanHostKey(FetchTarget target) {
        Objects.requireNonNull(target, "target");
        HostKeyVerifier verifier = new HostKeyVerifier(null);
        return withClient(verifier, signatures(SCAN_ALGORITHMS), client -> {
            long deadline = System.nanoTime() + connectTimeout.toNanos();
            ClientSession session = connect(client, target, SCAN_USERNAME, deadline);
            try {
                Presented shown = awaitHostKey(session, verifier, deadline);
                return new HostKey(shown.algorithm(), shown.fingerprint());
            } finally {
                closeQuietly(session);
            }
        });
    }

    /**
     * Verbinding, vastgepinde hostsleutel en aanmelding.
     *
     * @return de getoonde (en dus vastgepinde) sleutel
     * @throws FetchFailureException {@code CONNECTION_FAILED}, {@code SFTP_HOST_KEY_MISMATCH},
     *                               {@code CREDENTIAL_UNDECRYPTABLE}, {@code SFTP_AUTHENTICATION_FAILED}
     */
    public HostKey verifyLogin(FetchTarget target, String username, PinnedHostKey pinned, PasswordSource password) {
        return withAuthenticatedSession(target, username, pinned, password,
                (session, shown) -> new HostKey(shown.algorithm(), shown.fingerprint()));
    }

    /**
     * Zoals {@link #verifyLogin}, plus de map lijsten (geen recursie, A17) en de selectie toepassen. Geen download.
     *
     * @throws FetchFailureException ook {@code REMOTE_DIRECTORY_NOT_FOUND}, {@code REMOTE_PERMISSION_DENIED},
     *                               {@code FETCH_LISTING_TOO_LARGE}
     */
    public Listing listDirectory(FetchTarget target, String username, PinnedHostKey pinned, PasswordSource password,
                                 String remoteDirectory, RemoteFileSelection selection) {
        Objects.requireNonNull(remoteDirectory, "remoteDirectory");
        Objects.requireNonNull(selection, "selection");
        return withAuthenticatedSession(target, username, pinned, password,
                (session, shown) -> list(session, remoteDirectory, selection, shown.fingerprint()));
    }

    /**
     * Zoals {@link #listDirectory}, en daarna in dezelfde sessie hoogstens één bestand downloaden (K-4b, zie
     * klassedocumentatie). {@code byteLimit} is de byte-guard: meer bytes dan dit = {@code FETCH_FILE_TOO_LARGE}.
     *
     * @throws FetchFailureException de codes van {@link #listDirectory}, plus {@code FETCH_FILE_TOO_LARGE},
     *                               {@code FETCH_FILE_CHANGED_DURING_TRANSFER}, {@code FETCH_TRANSFER_INCOMPLETE},
     *                               {@code REMOTE_PERMISSION_DENIED} (bestand niet leesbaar)
     */
    public FetchResult fetchOne(FetchTarget target, String username, PinnedHostKey pinned, PasswordSource password,
                                String remoteDirectory, RemoteFileSelection selection, long byteLimit,
                                DownloadPlan plan) {
        Objects.requireNonNull(remoteDirectory, "remoteDirectory");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(plan, "plan");
        if (byteLimit < 0) {
            throw new IllegalArgumentException("byteLimit must not be negative");
        }
        return withAuthenticatedSession(target, username, pinned, password,
                (session, shown) -> fetchIn(session, remoteDirectory, selection, byteLimit, plan, shown.fingerprint()));
    }

    /** Het absolute pad van een bestand in de (absolute, genormaliseerde) externe map (A17). */
    public static String remotePath(String directory, String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    // --- Sessie ------------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface ClientWork<T> {
        T run(SshClient client);
    }

    @FunctionalInterface
    private interface SessionWork<T> {
        T run(ClientSession session, Presented shown);
    }

    /** De getoonde sleutel: gerapporteerd algoritme, vingerafdruk en soort. */
    private record Presented(String algorithm, String fingerprint, KeyFamily family) {
    }

    private <T> T withAuthenticatedSession(FetchTarget target, String username, PinnedHostKey pinned,
                                           PasswordSource password, SessionWork<T> work) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(password, "password");
        List<NamedFactory<Signature>> offered = signatures(algorithmsFor(familyOfAlgorithm(pinned.algorithm())));
        if (offered.isEmpty()) {
            throw connectionFailed("The pinned host key algorithm is not supported by this installation");
        }
        HostKeyVerifier verifier = new HostKeyVerifier(pinned);
        return withClient(verifier, offered, client -> {
            long deadline = System.nanoTime() + connectTimeout.toNanos();
            ClientSession session = connect(client, target, username, deadline);
            try {
                Presented shown = awaitHostKey(session, verifier, deadline);
                if (!verifier.accepted) {
                    throw new FetchFailureException(FetchOutcomeCodes.SFTP_HOST_KEY_MISMATCH, "The server presented "
                            + "another host key than the pinned one; nothing was sent. Check the fingerprint with the "
                            + "supplier through another channel (a changed key needs a new profile version)",
                            shown.fingerprint());
                }
                authenticate(session, password, shown.fingerprint());
                return work.run(session, shown);
            } finally {
                closeQuietly(session);
            }
        });
    }

    private <T> T withClient(HostKeyVerifier verifier, List<NamedFactory<Signature>> offered, ClientWork<T> work) {
        SshClient client = newClient(verifier, offered);
        try {
            startClient(client);
            return work.run(client);
        } catch (FetchFailureException failure) {
            throw failure;
        } catch (CallbackFailure callback) {
            // Een fout van de aanroeper (databank, lokaal archief) is geen fout van de server: ongewijzigd doorgeven.
            throw callback.original;
        } catch (RuntimeException unexpected) {
            // Enkel het type: de melding van de library kan de serverbanner bevatten.
            LOG.warn("Unexpected failure while contacting an external SFTP server ({})",
                    unexpected.getClass().getName());
            throw connectionFailed("Unexpected failure while contacting the server");
        } finally {
            stopQuietly(client);
        }
    }

    private SshClient newClient(HostKeyVerifier verifier, List<NamedFactory<Signature>> offered) {
        SshClient client = SshClient.setUpDefaultClient();
        // Nooit ~/.ssh/config: host, poort of ProxyJump mogen niet buiten de allowlist om wijzigen.
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setServerKeyVerifier(verifier);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        List<UserAuthFactory> authentication = new ArrayList<>();
        authentication.add(UserAuthPasswordFactory.INSTANCE);
        client.setUserAuthFactories(authentication);
        client.setSignatureFactories(offered);
        CoreModuleProperties.IDLE_TIMEOUT.set(client, idleTimeout);
        CoreModuleProperties.NIO2_READ_TIMEOUT.set(client, idleTimeout);
        CoreModuleProperties.AUTH_TIMEOUT.set(client, authTimeout);
        return client;
    }

    private static void startClient(SshClient client) {
        try {
            client.start();
        } catch (Exception failure) {
            LOG.warn("The SFTP client could not be started ({})", failure.getClass().getName());
            throw connectionFailed("The SFTP client could not be started");
        }
    }

    private ClientSession connect(SshClient client, FetchTarget target, String username, long deadline) {
        ConnectFuture future;
        try {
            future = client.connect(username, target.socketAddress());
        } catch (IOException | RuntimeException failure) {
            LOG.info("SFTP connection could not be set up ({})", failure.getClass().getName());
            throw connectionFailed("The connection could not be set up");
        }
        boolean done;
        try {
            done = future.await(remainingMillis(deadline));
        } catch (IOException interrupted) {
            throw connectionFailed("Interrupted while connecting");
        }
        if (!done) {
            throw connectionFailed("No connection within the connect time-out");
        }
        if (!future.isConnected()) {
            Throwable cause = future.getException();
            LOG.info("SFTP connection failed ({})", cause == null ? "no cause" : cause.getClass().getName());
            throw connectionFailed("The connection was refused or the host is unreachable");
        }
        return future.getSession();
    }

    /** Wacht tot de verifier de sleutel zag, hoogstens tot de connect-deadline. */
    private static Presented awaitHostKey(ClientSession session, HostKeyVerifier verifier, long deadline) {
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw connectionFailed("The server did not complete the SSH key exchange within the connect time-out");
            }
            try {
                return verifier.presented.get(Math.min(remaining, POLL_NANOS), TimeUnit.NANOSECONDS);
            } catch (TimeoutException notYet) {
                if (!session.isOpen() && !verifier.presented.isDone()) {
                    throw connectionFailed("The server closed the connection before presenting a host key (no common "
                            + "host key algorithm - possibly not the pinned key type - or not an SSH server)");
                }
            } catch (ExecutionException unreadable) {
                throw connectionFailed("The host key presented by the server could not be read");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw connectionFailed("Interrupted while connecting");
            }
        }
    }

    private void authenticate(ClientSession session, PasswordSource source, String fingerprint) {
        String secret;
        try {
            secret = source.password();
        } catch (CredentialUndecryptableException | SecretsNotConfiguredException undecryptable) {
            throw new FetchFailureException(FetchOutcomeCodes.CREDENTIAL_UNDECRYPTABLE, "The stored credential cannot "
                    + "be decrypted; a user with the right to manage credentials must enter the password again",
                    fingerprint);
        }
        if (secret == null || secret.isEmpty()) {
            throw new FetchFailureException(FetchOutcomeCodes.CREDENTIAL_UNDECRYPTABLE,
                    "The stored credential has no usable value", fingerprint);
        }
        // Via een provider en niet via addPasswordIdentity: die laatste logt op DEBUG een digest van het wachtwoord.
        session.setPasswordIdentityProvider(PasswordIdentityProvider.wrapPasswords(secret));
        AuthFuture auth;
        boolean done;
        try {
            auth = session.auth();
            done = auth.await(authTimeout.toMillis());
        } catch (IOException failure) {
            LOG.info("SFTP authentication could not be completed ({})", failure.getClass().getName());
            throw connectionFailed("The connection was lost during authentication", fingerprint);
        }
        if (!done) {
            throw connectionFailed("The server did not answer the authentication within the auth time-out",
                    fingerprint);
        }
        if (auth.isSuccess()) {
            return;
        }
        if (auth.isFailure() || noMoreAuthenticationMethods(auth.getException())) {
            throw new FetchFailureException(FetchOutcomeCodes.SFTP_AUTHENTICATION_FAILED,
                    "The server refused the user name and password", fingerprint);
        }
        Throwable cause = auth.getException();
        LOG.info("SFTP authentication failed ({})", cause == null ? "no cause" : cause.getClass().getName());
        throw connectionFailed("The connection was lost during authentication", fingerprint);
    }

    private static boolean noMoreAuthenticationMethods(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SshException ssh
                    && ssh.getDisconnectCode() == SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    // --- Map lijsten -------------------------------------------------------------------------------------------

    private Listing list(ClientSession session, String directory, RemoteFileSelection selection, String fingerprint) {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            return listEntries(sftp, directory, selection, fingerprint);
        } catch (SftpException refused) {
            throw sftpFailure(refused.getStatus(), fingerprint);
        } catch (IOException failure) {
            LOG.info("SFTP listing failed ({})", failure.getClass().getName());
            throw connectionFailed("The SFTP session failed while listing the remote directory", fingerprint);
        }
    }

    /** De map lijsten en de selectie toepassen (K-4a; sinds K-4b gedeeld door {@link #list} en {@link #fetchIn}). */
    private Listing listEntries(SftpClient sftp, String directory, RemoteFileSelection selection, String fingerprint)
            throws IOException {
        SftpClient.Attributes attributes = sftp.stat(directory);
        if (attributes == null || !attributes.isDirectory()) {
            throw new FetchFailureException(FetchOutcomeCodes.REMOTE_DIRECTORY_NOT_FOUND,
                    "The remote directory does not exist or is not a directory", fingerprint);
        }
        int entries = 0;
        List<RemoteFile> matched = new ArrayList<>();
        try (SftpClient.CloseableHandle handle = sftp.openDir(directory)) {
            List<SftpClient.DirEntry> batch = sftp.readDir(handle);
            while (batch != null && !batch.isEmpty()) {
                for (SftpClient.DirEntry entry : batch) {
                    String name = entry.getFilename();
                    if (name == null || ".".equals(name) || "..".equals(name)) {
                        continue;
                    }
                    entries++;
                    if (entries > listingCap) {
                        throw new FetchFailureException(FetchOutcomeCodes.FETCH_LISTING_TOO_LARGE, "The remote "
                                + "directory holds more than " + listingCap + " entries; nothing is cut off "
                                + "silently, ask the supplier to clean up or use a more specific directory",
                                fingerprint);
                    }
                    SftpClient.Attributes file = entry.getAttributes();
                    // Enkel gewone bestanden: geen submappen (A17, geen recursie) en geen symlinks.
                    if (file == null || !file.isRegularFile() || !selection.matches(name)) {
                        continue;
                    }
                    FileTime modified = file.getModifyTime();
                    matched.add(new RemoteFile(name, file.getSize(), modified == null ? null : modified.toInstant()));
                }
                batch = sftp.readDir(handle);
            }
        }
        return new Listing(fingerprint, entries, List.copyOf(matched));
    }

    // --- Downloaden (K-4b) ---------------------------------------------------------------------------------------

    private FetchResult fetchIn(ClientSession session, String directory, RemoteFileSelection selection,
                                long byteLimit, DownloadPlan plan, String fingerprint) {
        SftpClient sftp;
        try {
            sftp = SftpClientFactory.instance().createSftpClient(session);
        } catch (IOException failure) {
            LOG.info("SFTP subsystem could not be opened ({})", failure.getClass().getName());
            throw connectionFailed("The SFTP session failed while listing the remote directory", fingerprint);
        }
        try {
            Listing listing;
            try {
                listing = listEntries(sftp, directory, selection, fingerprint);
            } catch (SftpException refused) {
                throw sftpFailure(refused.getStatus(), fingerprint);
            } catch (IOException failure) {
                LOG.info("SFTP listing failed ({})", failure.getClass().getName());
                throw connectionFailed("The SFTP session failed while listing the remote directory", fingerprint);
            }
            RemoteFile chosen = callback(() -> plan.choose(listing));
            if (chosen == null) {
                return new FetchResult(listing, null, 0L);
            }
            String path = remotePath(directory, chosen.name());
            long transferred = download(sftp, path, chosen, byteLimit, plan, fingerprint);
            verifyUnchanged(sftp, path, chosen, transferred, fingerprint);
            return new FetchResult(listing, chosen, transferred);
        } finally {
            closeResourceQuietly(sftp);
        }
    }

    private long download(SftpClient sftp, String path, RemoteFile file, long byteLimit, DownloadPlan plan,
                          String fingerprint) {
        InputStream raw;
        try {
            raw = sftp.read(path);
        } catch (SftpException refused) {
            throw transferFailure(refused.getStatus(), fingerprint);
        } catch (IOException failure) {
            LOG.info("SFTP download could not be started ({})", failure.getClass().getName());
            throw incomplete("The download could not be started", fingerprint);
        }
        GuardedInputStream guarded = new GuardedInputStream(raw, byteLimit, fingerprint);
        try {
            callback(() -> {
                plan.receive(file, guarded);
                return null;
            });
            return guarded.count();
        } catch (CallbackFailure receiveFailed) {
            // De ontvanger (het archief) verpakt een leesfout van de bron; enkel die is een fout van de overdracht.
            IOException readFailure = guarded.readFailure();
            if (readFailure instanceof SftpException refused) {
                throw transferFailure(refused.getStatus(), fingerprint);
            }
            if (readFailure != null) {
                LOG.info("SFTP download broke off ({})", readFailure.getClass().getName());
                throw incomplete("The transfer broke off before the whole file was received", fingerprint);
            }
            throw receiveFailed;
        } finally {
            closeResourceQuietly(raw);
        }
    }

    /** Her-stat na de download: zelfde grootte en wijzigingstijd als de listing, en evenveel bytes ontvangen. */
    private static void verifyUnchanged(SftpClient sftp, String path, RemoteFile listed, long transferred,
                                        String fingerprint) {
        SftpClient.Attributes after;
        try {
            after = sftp.stat(path);
        } catch (SftpException refused) {
            throw transferFailure(refused.getStatus(), fingerprint);
        } catch (IOException failure) {
            LOG.info("SFTP check after the download failed ({})", failure.getClass().getName());
            throw incomplete("The file could not be checked again after the download", fingerprint);
        }
        FileTime modified = after == null ? null : after.getModifyTime();
        Instant modifiedAt = modified == null ? null : modified.toInstant();
        if (after == null || after.getSize() != listed.size() || !Objects.equals(modifiedAt, listed.modifiedAt())) {
            throw new FetchFailureException(FetchOutcomeCodes.FETCH_FILE_CHANGED_DURING_TRANSFER, "The file changed "
                    + "while it was being downloaded (size or modification time differs from the listing); nothing was "
                    + "registered, a later run fetches it again", fingerprint);
        }
        if (transferred != listed.size()) {
            throw incomplete("The number of bytes received differs from the file size", fingerprint);
        }
    }

    private static FetchFailureException transferFailure(int status, String fingerprint) {
        return switch (status) {
            case SSH_FX_NO_SUCH_FILE, SSH_FX_NO_SUCH_PATH -> new FetchFailureException(
                    FetchOutcomeCodes.FETCH_FILE_CHANGED_DURING_TRANSFER,
                    "The file disappeared between the listing and the download; nothing was registered", fingerprint);
            case SSH_FX_PERMISSION_DENIED -> new FetchFailureException(FetchOutcomeCodes.REMOTE_PERMISSION_DENIED,
                    "The server refuses to read the file for this login", fingerprint);
            default -> incomplete("The SFTP server answered with status " + status + " during the download",
                    fingerprint);
        };
    }

    private static FetchFailureException incomplete(String message, String fingerprint) {
        return new FetchFailureException(FetchOutcomeCodes.FETCH_TRANSFER_INCOMPLETE,
                message + "; nothing was registered", fingerprint);
    }

    /** Voert een callback van de aanroeper uit; zijn eigen fouten worden gemarkeerd zodat ze niet vermomd worden. */
    private static <T> T callback(Supplier<T> work) {
        try {
            return work.get();
        } catch (FetchFailureException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CallbackFailure(failure);
        }
    }

    /** Draagt een fout van de aanroeper ongewijzigd door {@link #withClient} heen. */
    private static final class CallbackFailure extends RuntimeException {

        private final RuntimeException original;

        CallbackFailure(RuntimeException original) {
            super(null, null, false, false);
            this.original = original;
        }
    }

    /**
     * Telt de gelezen bytes en stopt zodra er meer dan {@code limit} binnenkomen ({@code FETCH_FILE_TOO_LARGE}); onthoudt
     * een leesfout van de bron, zodat een afgebroken overdracht te onderscheiden is van een fout van de ontvanger.
     */
    static final class GuardedInputStream extends FilterInputStream {

        private final long limit;
        private final String fingerprint;
        private long count;
        private IOException readFailure;

        GuardedInputStream(InputStream in, long limit, String fingerprint) {
            super(in);
            this.limit = limit;
            this.fingerprint = fingerprint;
        }

        @Override
        public int read() throws IOException {
            int value;
            try {
                value = super.read();
            } catch (IOException failure) {
                readFailure = failure;
                throw failure;
            }
            if (value >= 0) {
                count++;
                checkLimit();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read;
            try {
                read = super.read(buffer, offset, length);
            } catch (IOException failure) {
                readFailure = failure;
                throw failure;
            }
            if (read > 0) {
                count += read;
                checkLimit();
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            throw new IOException("skip is not supported while downloading");
        }

        private void checkLimit() {
            if (count > limit) {
                throw new FetchFailureException(FetchOutcomeCodes.FETCH_FILE_TOO_LARGE, "The file grew beyond the "
                        + "maximum size of " + limit + " bytes while it was being downloaded; the download was stopped "
                        + "and nothing was registered", fingerprint);
            }
        }

        long count() {
            return count;
        }

        IOException readFailure() {
            return readFailure;
        }
    }

    private static FetchFailureException sftpFailure(int status, String fingerprint) {
        return switch (status) {
            case SSH_FX_NO_SUCH_FILE, SSH_FX_NO_SUCH_PATH, SSH_FX_NOT_A_DIRECTORY -> new FetchFailureException(
                    FetchOutcomeCodes.REMOTE_DIRECTORY_NOT_FOUND,
                    "The remote directory does not exist or is not a directory", fingerprint);
            case SSH_FX_PERMISSION_DENIED -> new FetchFailureException(FetchOutcomeCodes.REMOTE_PERMISSION_DENIED,
                    "The server refuses to list the remote directory for this login", fingerprint);
            default -> connectionFailed("The SFTP server answered with status " + status, fingerprint);
        };
    }

    // --- Hostsleutel ---------------------------------------------------------------------------------------------

    /**
     * Noteert de getoonde sleutel. {@code pinned == null} (scan): altijd weigeren, zodat er nooit aangemeld wordt.
     * Anders enkel aanvaarden bij dezelfde sleutelsoort én dezelfde vingerafdruk.
     */
    private static final class HostKeyVerifier implements ServerKeyVerifier {

        private final PinnedHostKey pinned;
        private final CompletableFuture<Presented> presented = new CompletableFuture<>();
        private volatile boolean accepted;

        HostKeyVerifier(PinnedHostKey pinned) {
            this.pinned = pinned;
        }

        @Override
        public boolean verifyServerKey(ClientSession clientSession, SocketAddress remoteAddress, PublicKey serverKey) {
            try {
                KeyFamily family = familyOfKey(serverKey);
                String negotiated = clientSession.getNegotiatedKexParameter(KexProposalOption.SERVERKEYS);
                Presented shown = new Presented(reportedAlgorithm(family, negotiated), fingerprint(serverKey), family);
                boolean ok = pinned != null && family != KeyFamily.OTHER
                        && family == familyOfAlgorithm(pinned.algorithm())
                        && shown.fingerprint().equals(pinned.fingerprintSha256());
                this.accepted = ok;
                presented.complete(shown);
                return ok;
            } catch (RuntimeException unreadable) {
                presented.completeExceptionally(unreadable);
                return false;
            }
        }
    }

    /**
     * SHA-256 van de SSH-wire-blob van de sleutel, OpenSSH-vorm {@code SHA256:<base64 zonder opvulling>} (zelfde vorm
     * als LC-2 valideert en als {@code ssh-keygen -lf} toont).
     */
    static String fingerprint(PublicKey key) {
        ByteArrayBuffer buffer = new ByteArrayBuffer();
        buffer.putRawPublicKey(key);
        byte[] blob = buffer.getCompactData();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 not available", unavailable);
        }
    }

    static KeyFamily familyOfKey(PublicKey key) {
        if (key instanceof RSAPublicKey) {
            return KeyFamily.RSA;
        }
        if (key instanceof ECPublicKey ec) {
            return switch (ec.getParams().getCurve().getField().getFieldSize()) {
                case 256 -> KeyFamily.ECDSA_P256;
                case 384 -> KeyFamily.ECDSA_P384;
                case 521 -> KeyFamily.ECDSA_P521;
                default -> KeyFamily.OTHER;
            };
        }
        String algorithm = key.getAlgorithm();
        if ("EdDSA".equalsIgnoreCase(algorithm) || "Ed25519".equalsIgnoreCase(algorithm)) {
            return KeyFamily.ED25519;
        }
        return KeyFamily.OTHER;
    }

    static KeyFamily familyOfAlgorithm(String algorithm) {
        if (algorithm == null) {
            return KeyFamily.OTHER;
        }
        return switch (algorithm) {
            case "ssh-ed25519" -> KeyFamily.ED25519;
            case "ecdsa-sha2-nistp256" -> KeyFamily.ECDSA_P256;
            case "ecdsa-sha2-nistp384" -> KeyFamily.ECDSA_P384;
            case "ecdsa-sha2-nistp521" -> KeyFamily.ECDSA_P521;
            case "rsa-sha2-256", "rsa-sha2-512" -> KeyFamily.RSA;
            default -> KeyFamily.OTHER;
        };
    }

    /** De algoritmen die de client voor een vastgepinde sleutelsoort aanbiedt; RSA nooit met SHA-1. */
    static List<String> algorithmsFor(KeyFamily family) {
        return switch (family) {
            case ED25519 -> List.of("ssh-ed25519");
            case ECDSA_P256 -> List.of("ecdsa-sha2-nistp256");
            case ECDSA_P384 -> List.of("ecdsa-sha2-nistp384");
            case ECDSA_P521 -> List.of("ecdsa-sha2-nistp521");
            case RSA -> List.of("rsa-sha2-512", "rsa-sha2-256");
            case OTHER -> List.of();
        };
    }

    /**
     * Het algoritme dat de scan rapporteert: het onderhandelde als het bij de sleutel past en in de LC-2-lijst staat,
     * anders het voorkeursalgoritme van de sleutelsoort (RSA: {@code rsa-sha2-512}; nooit {@code ssh-rsa}).
     */
    static String reportedAlgorithm(KeyFamily family, String negotiated) {
        if (negotiated != null && ConnectionProfileService.HOST_KEY_ALGORITHMS.contains(negotiated)
                && familyOfAlgorithm(negotiated) == family) {
            return negotiated;
        }
        return switch (family) {
            case ED25519 -> "ssh-ed25519";
            case ECDSA_P256 -> "ecdsa-sha2-nistp256";
            case ECDSA_P384 -> "ecdsa-sha2-nistp384";
            case ECDSA_P521 -> "ecdsa-sha2-nistp521";
            case RSA -> "rsa-sha2-512";
            case OTHER -> negotiated;
        };
    }

    /** De ondersteunde signatuurfabrieken voor deze namen, in die volgorde. */
    static List<NamedFactory<Signature>> signatures(List<String> names) {
        List<NamedFactory<Signature>> result = new ArrayList<>();
        for (String name : names) {
            for (BuiltinSignatures candidate : BuiltinSignatures.values()) {
                if (candidate.getName().equals(name) && candidate.isSupported()) {
                    result.add(candidate);
                    break;
                }
            }
        }
        return result;
    }

    // --- Hulp ------------------------------------------------------------------------------------------------

    private static long remainingMillis(long deadline) {
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static void closeQuietly(ClientSession session) {
        try {
            session.close(true);
        } catch (Exception ignored) {
            // Sluiten na een fout: niets meer te melden.
        }
    }

    private static void closeResourceQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // Sluiten na afloop of na een fout: de uitkomst staat al vast.
        }
    }

    private static void stopQuietly(SshClient client) {
        try {
            client.stop();
        } catch (Exception ignored) {
            // Idem.
        }
    }

    private static FetchFailureException connectionFailed(String message) {
        return new FetchFailureException(FetchOutcomeCodes.CONNECTION_FAILED, message);
    }

    private static FetchFailureException connectionFailed(String message, String fingerprint) {
        return new FetchFailureException(FetchOutcomeCodes.CONNECTION_FAILED, message, fingerprint);
    }

    private static Duration positive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(property + " must be a positive duration");
        }
        return value;
    }

    @Override
    public String toString() {
        return "SftpConnector[connectTimeout=" + connectTimeout + ", authTimeout=" + authTimeout + ", idleTimeout="
                + idleTimeout + ", listingCap=" + listingCap + "]";
    }
}
