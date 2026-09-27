package be.dda.catalogimport.service;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Het bestandsarchief van publicatierun-artefacten (ontwerp fase 5-PUB par. 3, "Artefact"; beslissing
 * mens 2026-09-26). Zelfde patroon en dezelfde belofte als {@link DeliveryArchiveStore}: de
 * <b>bytes staan nooit in de database</b>, enkel de referentie (relatief aan de archiefroot), de SHA-256
 * en de grootte.
 *
 * <h2>Waarom dezelfde property en geen nieuwe</h2>
 * {@code catalogimport.archive.root} is de bestaande, per omgeving verplichte archiefroot zonder default
 * ({@code application.yml}). Een tweede property zou een tweede map moeten krijgen in elke omgeving en
 * elke deploy, terwijl dit hetzelfde soort onveranderlijk bewijsstuk is. De artefacten staan in een eigen
 * submap {@value #ARTIFACT_SUBDIRECTORY}, zodat ze nooit met de bronleveringen (die op {@code <yyyy>/…}
 * staan) door elkaar kunnen lopen.
 *
 * <h2>Pad</h2>
 * {@code <root>/publication-runs/<yyyy>/<MM>/<dd>/<runId>/psimport-preview.csv}. De runId-map maakt het
 * pad uniek zonder dat er ooit een bestaand artefact overschreven kan worden: het bestand wordt met
 * {@code CREATE_NEW} aangemaakt.
 *
 * <h2>Atomair schrijven</h2>
 * Er wordt eerst naar {@code psimport-preview.csv.tmp} in dezelfde map geschreven en pas daarna
 * hernoemd ({@link StandardCopyOption#ATOMIC_MOVE}, zelfde volume). Een half geschreven artefact kan dus
 * nooit als geldig bestand blijven staan: breekt het schrijven af, dan wordt het tijdelijke bestand
 * opgeruimd en bestaat het definitieve pad niet. SHA-256 en byteteller lopen tijdens het schrijven mee
 * ({@link DigestOutputStream} + een tellende stroom), zodat het volledige artefact nooit in het geheugen
 * komt — een bundel met honderdduizenden regels moet even goed werken als een met drie.
 */
@Component
public class PublicationArtifactStore {

    /** Submap onder de archiefroot; houdt artefacten en bronleveringen gescheiden. */
    public static final String ARTIFACT_SUBDIRECTORY = "publication-runs";

    /** Vaste bestandsnaam van het PSIMPORT-CSV-artefact van één run. */
    public static final String ARTIFACT_FILE_NAME = "psimport-preview.csv";

    /** Achtervoegsel van het tijdelijke bestand vóór de atomaire hernoeming. */
    static final String TEMP_SUFFIX = ".tmp";

    /**
     * Wat de database van het artefact bewaart. De bytes zelf staan enkel op het bestandssysteem.
     *
     * @param reference relatief pad onder de archiefroot, met {@code /} als scheidingsteken
     * @param sha256Hex SHA-256 over exact de geschreven bytes, in kleine hex
     * @param byteSize  het aantal geschreven bytes
     */
    public record StoredArtifact(String reference, String sha256Hex, long byteSize) {
    }

    /**
     * De inhoud van één artefact, geschreven naar een stroom in plaats van opgebouwd in het geheugen.
     * De {@link Writer} is UTF-8 en gebufferd; de aanroeper sluit hem niet (dat doet de store).
     */
    @FunctionalInterface
    public interface ArtifactContent {

        void writeTo(Writer out) throws IOException;
    }

    private final Path root;
    private final Clock clock;

    public PublicationArtifactStore(@Value("${catalogimport.archive.root}") String archiveRoot, Clock clock) {
        if (archiveRoot == null || archiveRoot.isBlank()) {
            throw new IllegalStateException("catalogimport.archive.root must be configured");
        }
        this.root = Path.of(archiveRoot).toAbsolutePath().normalize();
        this.clock = clock;
    }

    /**
     * Schrijft het artefact van één run en geeft terug wat de database ervan bewaart.
     * <p>
     * Loopt het schrijven mis — een IO-fout, of een uitzondering uit {@code content} zelf, bijvoorbeeld
     * omdat de projectie halverwege faalt — dan blijft er <b>geen</b> artefactbestand achter: het
     * tijdelijke bestand wordt verwijderd en de (dan lege) runmap ook. De uitzondering gaat onveranderd
     * door; enkel een {@link IOException} wordt in een {@link UncheckedIOException} gewikkeld, met een
     * boodschap <b>zonder pad</b> (die hoort in het logboek, niet in {@code failure_message}).
     *
     * @throws UncheckedIOException het artefact kon niet geschreven worden
     */
    public StoredArtifact write(long runId, ArtifactContent content) {
        LocalDate today = LocalDate.now(clock);
        Path directory = root
                .resolve(ARTIFACT_SUBDIRECTORY)
                .resolve(String.format(Locale.ROOT, "%04d", today.getYear()))
                .resolve(String.format(Locale.ROOT, "%02d", today.getMonthValue()))
                .resolve(String.format(Locale.ROOT, "%02d", today.getDayOfMonth()))
                .resolve(Long.toString(runId));
        Path target = directory.resolve(ARTIFACT_FILE_NAME).normalize();
        Path temporary = directory.resolve(ARTIFACT_FILE_NAME + TEMP_SUFFIX).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Publication artifact target outside archive root");
        }

        MessageDigest digest = newDigest();
        long byteSize;
        try {
            Files.createDirectories(directory);
            try (OutputStream raw = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                CountingOutputStream counting = new CountingOutputStream(raw);
                // De Writer wordt eerst gesloten en pas daarna wordt de teller gelezen: enkel na het
                // sluiten heeft de UTF-8-encoder gegarandeerd al zijn bytes doorgegeven. Het dubbele
                // sluiten van "raw" (hier en door de buitenste try) is onschadelijk.
                try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                        new DigestOutputStream(counting, digest), StandardCharsets.UTF_8))) {
                    content.writeTo(writer);
                }
                byteSize = counting.count();
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(temporary);
            deleteQuietly(target);
            deleteDirectoryIfEmpty(directory);
            if (failure instanceof IOException io) {
                // Bewust zonder pad in de boodschap: die kan in failure_message van de run terechtkomen.
                throw new UncheckedIOException("Cannot write publication artifact for run " + runId, io);
            }
            throw (RuntimeException) failure;
        }
        return new StoredArtifact(toReference(target), HexFormat.of().formatHex(digest.digest()), byteSize);
    }

    /**
     * Opent een eerder geschreven artefact (download-endpoint, 5P-8). De referentie komt uit de database
     * maar wordt <b>altijd</b> opnieuw gevalideerd: ze moet binnen de archiefroot en binnen
     * {@value #ARTIFACT_SUBDIRECTORY} vallen. Een referentie met {@code ..} of een absoluut pad valt
     * daardoor buiten de root en wordt geweigerd — een artefactreferentie kan dus nooit een bronlevering
     * of een willekeurig bestand van de server aanwijzen.
     *
     * @throws IllegalArgumentException ongeldige referentie of een pad buiten de artefactmap
     * @throws UncheckedIOException     het bestand bestaat niet (meer) of is niet leesbaar
     */
    public InputStream open(String reference) {
        Path path = resolve(reference);
        try {
            return Files.newInputStream(path);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot open publication artifact " + reference, failure);
        }
    }

    /** {@code true} wanneer het artefact nog op het bestandssysteem staat. */
    public boolean exists(String reference) {
        return Files.isRegularFile(resolve(reference));
    }

    /**
     * Ruimt een artefact op dat nooit (geldig) geregistreerd werd. Fouten worden bewust genegeerd; een
     * achterblijvend wees-bestand is onschuldig, een uitzondering tijdens het opruimen van een al mislukte
     * run niet.
     */
    public void deleteQuietly(String reference) {
        try {
            Path path = resolve(reference);
            deleteQuietly(path);
            deleteDirectoryIfEmpty(path.getParent());
        } catch (RuntimeException ignored) {
            // Best effort.
        }
    }

    private Path resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Publication artifact reference is empty");
        }
        Path resolved;
        try {
            resolved = root.resolve(reference).normalize();
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("Invalid publication artifact reference", invalid);
        }
        Path artifacts = root.resolve(ARTIFACT_SUBDIRECTORY).normalize();
        if (!resolved.startsWith(artifacts) || resolved.equals(artifacts)) {
            throw new IllegalArgumentException("Publication artifact reference outside the artifact directory");
        }
        return resolved;
    }

    private String toReference(Path target) {
        return root.relativize(target).toString().replace('\\', '/');
    }

    private static void deleteQuietly(Path file) {
        try {
            if (Files.exists(file)) {
                file.toFile().setWritable(true); // read-only bestanden zijn op Windows niet verwijderbaar
                Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException ignored) {
            // Best effort.
        }
    }

    private void deleteDirectoryIfEmpty(Path directory) {
        try {
            if (directory != null && directory.startsWith(root) && !directory.equals(root)) {
                Files.deleteIfExists(directory); // enkel als leeg
            }
        } catch (IOException | RuntimeException ignored) {
            // Best effort.
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 not available", unavailable);
        }
    }

    /**
     * Telt de bytes die werkelijk naar het bestand gaan. Beide {@code write}-methoden zijn overschreven:
     * {@link FilterOutputStream#write(byte[], int, int)} zou anders byte per byte doorlussen.
     */
    private static final class CountingOutputStream extends FilterOutputStream {

        private long count;

        private CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            count += length;
        }

        private long count() {
            return count;
        }
    }
}
