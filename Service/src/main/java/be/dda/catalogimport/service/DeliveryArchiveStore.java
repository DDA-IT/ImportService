package be.dda.catalogimport.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Immutable bronarchief voor ontvangen leveringsbestanden op het bestandssysteem (design par. 6).
 * <p>
 * De bytes van een levering staan <b>nooit in de database</b>: de database bewaart enkel de
 * {@code archive_reference} (relatief aan {@code catalogimport.archive.root}), de SHA-256 en de
 * grootte. Pad: {@code <root>/<yyyy>/<MM>/<dd>/<uuid>/<sanitizedFileName>}; de unieke
 * uuid-map zorgt dat twee leveringen met dezelfde bestandsnaam nooit botsen en dat een
 * ontvangen bestand nooit overschreven wordt.
 * <p>
 * Het bestand wordt gestreamd (8 KiB buffer): hash en byteteller lopen tijdens het schrijven mee,
 * het volledige bestand komt nooit in het geheugen. Na het schrijven wordt het bestand waar
 * mogelijk read-only gezet.
 * <p>
 * Archiveren gebeurt buiten elke databasetransactie (design par. 9, stap A): een wees-object bij
 * een crash tussen archiveren en registreren is onschuldig; {@link #deleteQuietly(String)} dient
 * uitsluitend om zo'n object op te ruimen wanneer registratie mislukt of een levering een retry
 * blijkt te zijn.
 */
@Component
public class DeliveryArchiveStore {

    /** Maximale lengte van de gesaneerde bestandsnaam in het archiefpad. */
    static final int MAX_FILE_NAME_LENGTH = 200;
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final String FALLBACK_FILE_NAME = "file";

    /** Resultaat van {@link #store}: alles wat de database van het archiefobject bewaart. */
    public record ArchivedObject(String archiveReference, String sha256Hex, long byteSize) {
    }

    private final Path root;

    public DeliveryArchiveStore(@Value("${catalogimport.archive.root}") String archiveRoot) {
        if (archiveRoot == null || archiveRoot.isBlank()) {
            throw new IllegalStateException("catalogimport.archive.root must be configured");
        }
        this.root = Path.of(archiveRoot).toAbsolutePath().normalize();
    }

    /**
     * Schrijft de stream naar het archief en geeft referentie, SHA-256 en grootte terug. De stream
     * wordt niet gesloten; dat blijft de verantwoordelijkheid van de aanroeper.
     */
    public ArchivedObject store(InputStream content, String originalFileName) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path directory = root
                .resolve(String.format(Locale.ROOT, "%04d", today.getYear()))
                .resolve(String.format(Locale.ROOT, "%02d", today.getMonthValue()))
                .resolve(String.format(Locale.ROOT, "%02d", today.getDayOfMonth()))
                .resolve(UUID.randomUUID().toString());
        Path target = directory.resolve(sanitizeFileName(originalFileName)).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Archive target outside archive root");
        }

        MessageDigest digest = newDigest();
        long byteSize = 0;
        try {
            Files.createDirectories(directory);
            try (DigestInputStream in = new DigestInputStream(content, digest);
                 OutputStream out = Files.newOutputStream(target,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    byteSize += read;
                }
            }
        } catch (IOException | RuntimeException failure) {
            deleteFileAndDirectory(target);
            if (failure instanceof IOException io) {
                throw new UncheckedIOException("Cannot archive delivery file", io);
            }
            throw (RuntimeException) failure;
        }
        // Immutable waar het platform dat toelaat; falen hiervan is geen fout.
        target.toFile().setReadOnly();
        return new ArchivedObject(toReference(target), HexFormat.of().formatHex(digest.digest()), byteSize);
    }

    /** Opent een gearchiveerd object. Een referentie buiten de archiefroot wordt geweigerd. */
    public InputStream open(String archiveReference) {
        Path path = resolve(archiveReference);
        try {
            return Files.newInputStream(path);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot open archived object " + archiveReference, failure);
        }
    }

    /**
     * Ruimt een object op dat niet geregistreerd werd. Enkel bedoeld voor mislukte registratie of een
     * identieke retry; nooit voor een geregistreerde levering. Fouten worden bewust genegeerd.
     */
    public void deleteQuietly(String archiveReference) {
        try {
            deleteFileAndDirectory(resolve(archiveReference));
        } catch (RuntimeException ignored) {
            // Best effort: een achterblijvend wees-object is onschuldig.
        }
    }

    private Path resolve(String archiveReference) {
        if (archiveReference == null || archiveReference.isBlank()) {
            throw new IllegalArgumentException("Archive reference is empty");
        }
        Path resolved;
        try {
            resolved = root.resolve(archiveReference).normalize();
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("Invalid archive reference", invalid);
        }
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("Archive reference outside archive root");
        }
        return resolved;
    }

    private String toReference(Path target) {
        return root.relativize(target).toString().replace('\\', '/');
    }

    private void deleteFileAndDirectory(Path file) {
        try {
            if (Files.exists(file)) {
                file.toFile().setWritable(true); // read-only bestanden zijn op Windows niet verwijderbaar
                Files.deleteIfExists(file);
            }
            Path parent = file.getParent();
            if (parent != null && parent.startsWith(root) && !parent.equals(root)) {
                Files.deleteIfExists(parent); // enkel als leeg
            }
        } catch (IOException | RuntimeException ignored) {
            // Best effort.
        }
    }

    /**
     * Reduceert een aangeleverde bestandsnaam tot een veilige basename: geen padscheidingstekens,
     * geen {@code ..}, geen stuurtekens of voor Windows verboden tekens, hoogstens
     * {@value #MAX_FILE_NAME_LENGTH} tekens. De originele naam blijft ongewijzigd in
     * {@code delivery_file.file_name}; dit betreft enkel het archiefpad.
     */
    static String sanitizeFileName(String original) {
        String name = original == null ? "" : original;
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) {
            name = name.substring(cut + 1);
        }
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean forbidden = c < 0x20 || c == 0x7f || "<>:\"|?*".indexOf(c) >= 0;
            cleaned.append(forbidden ? '_' : c);
        }
        name = cleaned.toString().strip();
        int start = 0;
        while (start < name.length() && name.charAt(start) == '.') {
            start++;
        }
        int end = name.length();
        while (end > start && (name.charAt(end - 1) == '.' || Character.isWhitespace(name.charAt(end - 1)))) {
            end--;
        }
        name = name.substring(start, end);
        if (name.isEmpty()) {
            return FALLBACK_FILE_NAME;
        }
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (base.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            name = "_" + name;
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 20 ? name.substring(dot) : "";
            name = name.substring(0, MAX_FILE_NAME_LENGTH - extension.length()) + extension;
        }
        return name;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 not available", unavailable);
        }
    }
}
