package be.dda.catalogimport.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * Embedded SFTP-server voor de K-4a-tests (Apache MINA SSHD, enkel in test-scope gebruikt; L8). Luistert op
 * {@code 127.0.0.1} op een vrije poort, met de opgegeven hostsleutel(s), wachtwoordauthenticatie voor één login en een
 * virtuele map: {@code /} is de meegegeven map.
 * <p>
 * Telt elke wachtwoordpoging ({@link #passwordAttempts()}), zodat een test kan bewijzen dat er bij een
 * hostsleutel-mismatch of een ingetrokken credential <b>geen</b> aanmelding geprobeerd werd.
 * <p>
 * Sleutels: {@link #primaryHostKey()} is ed25519 als de installatie ed25519 zonder extra library ondersteunt, anders
 * ECDSA P-256 - zodat de rest van de tests blijft werken; of ed25519 met de JDK alleen werkt, bewijst een aparte test
 * uitdrukkelijk ({@code SftpConnectorTest}). De vingerafdrukken worden hier onafhankelijk van {@code SftpConnector}
 * berekend.
 */
public final class SftpTestServer implements AutoCloseable {

    public static final String USERNAME = "leverancier";

    private final SshServer server;
    private final AtomicInteger passwordAttempts = new AtomicInteger();

    private SftpTestServer(Path root, String password, KeyPair... hostKeys) throws IOException {
        this.server = SshServer.setUpDefaultServer();
        this.server.setHost("127.0.0.1");
        this.server.setPort(0);
        this.server.setKeyPairProvider(KeyPairProvider.wrap(hostKeys));
        this.server.setPasswordAuthenticator((user, given, session) -> {
            passwordAttempts.incrementAndGet();
            return USERNAME.equals(user) && password.equals(given);
        });
        this.server.setSubsystemFactories(Collections.<SubsystemFactory>singletonList(new SftpSubsystemFactory()));
        this.server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        this.server.start();
    }

    /** Start een server met deze sleutel(s); {@code /} is {@code root}. */
    public static SftpTestServer start(Path root, String password, KeyPair... hostKeys) throws IOException {
        return new SftpTestServer(root, password, hostKeys);
    }

    public int port() {
        return server.getPort();
    }

    public int passwordAttempts() {
        return passwordAttempts.get();
    }

    @Override
    public void close() throws IOException {
        server.stop(true);
    }

    // --- Sleutels --------------------------------------------------------------------------------------------

    /** Ondersteunt de installatie ed25519 (zonder BouncyCastle of net.i2p op het klassenpad: met de JDK alleen)? */
    public static boolean ed25519Supported() {
        return SecurityUtils.isEDDSACurveSupported();
    }

    public static KeyPair ed25519() throws GeneralSecurityException {
        return KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    }

    public static KeyPair rsa() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    public static KeyPair ecdsaP256() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** ed25519 als ondersteund, anders ECDSA P-256 (zie klassecommentaar). */
    public static KeyPair primaryHostKey() throws GeneralSecurityException {
        return ed25519Supported() ? ed25519() : ecdsaP256();
    }

    /** Het algoritme zoals een profielversie het vastpint (RSA: {@code rsa-sha2-512}). */
    public static String algorithmOf(KeyPair pair) {
        PublicKey key = pair.getPublic();
        if (key instanceof RSAPublicKey) {
            return "rsa-sha2-512";
        }
        if (key instanceof ECPublicKey) {
            return "ecdsa-sha2-nistp256";
        }
        return "ssh-ed25519";
    }

    /**
     * De verwachte OpenSSH-vingerafdruk {@code SHA256:<base64 zonder opvulling>}, onafhankelijk van de connector: RSA en
     * ECDSA P-256 uit de zelf opgebouwde SSH-wire-blob, ed25519 via de eigen routine van de library.
     */
    public static String expectedFingerprint(PublicKey key) {
        if (key instanceof RSAPublicKey rsa) {
            return sha256(rsaBlob(rsa));
        }
        if (key instanceof ECPublicKey ec) {
            return sha256(ecdsaP256Blob(ec));
        }
        return KeyUtils.getFingerPrint(key);
    }

    /** {@code string "ssh-rsa", mpint e, mpint n} (RFC 4253 par. 6.6). */
    public static byte[] rsaBlob(RSAPublicKey key) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, "ssh-rsa".getBytes(StandardCharsets.US_ASCII));
        writeString(out, key.getPublicExponent().toByteArray());
        writeString(out, key.getModulus().toByteArray());
        return out.toByteArray();
    }

    /** {@code string "ecdsa-sha2-nistp256", string "nistp256", string Q} met Q = 04 || X || Y (RFC 5656 par. 3.1). */
    public static byte[] ecdsaP256Blob(ECPublicKey key) {
        ByteArrayOutputStream point = new ByteArrayOutputStream();
        point.write(4);
        point.writeBytes(fixed(key.getW().getAffineX(), 32));
        point.writeBytes(fixed(key.getW().getAffineY(), 32));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, "ecdsa-sha2-nistp256".getBytes(StandardCharsets.US_ASCII));
        writeString(out, "nistp256".getBytes(StandardCharsets.US_ASCII));
        writeString(out, point.toByteArray());
        return out.toByteArray();
    }

    public static String sha256(byte[] blob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] fixed(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[length];
        int copy = Math.min(raw.length, length);
        System.arraycopy(raw, raw.length - copy, result, length - copy, copy);
        return result;
    }

    private static void writeString(ByteArrayOutputStream out, byte[] value) {
        out.write((value.length >>> 24) & 0xff);
        out.write((value.length >>> 16) & 0xff);
        out.write((value.length >>> 8) & 0xff);
        out.write(value.length & 0xff);
        out.writeBytes(value);
    }
}
