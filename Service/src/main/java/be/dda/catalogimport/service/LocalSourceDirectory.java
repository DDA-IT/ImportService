package be.dda.catalogimport.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * De <b>beheerde servermap</b> waaruit een levering ingelezen kan worden: de tweede ontvangstweg naast de
 * browser-upload (beslissingslog 2026-09-27, "tweede ontvangstweg — levering inlezen uit een beheerde
 * servermap", D1-D4 en D9).
 * <p>
 * <b>Wat deze klasse is.</b> De spiegel van {@link DeliveryArchiveStore}, met een eigen root
 * ({@code catalogimport.local-source.directory}), dezelfde containment-check en dezelfde streaming: een
 * bestand komt nooit volledig in het geheugen. Eén vlakke map voor alle leveranciers, geen recursie, geen
 * submappen, geen extensiefilter (D9). Alleen <b>lezen</b>: er wordt hier niets geschreven, verplaatst of
 * verwijderd — het bronbestand blijft na het inlezen ongemoeid (D7), opruimen is een operatortaak.
 * <p>
 * <b>Uit of aan.</b> De property is optioneel en heeft geen default. Niet gezet = de tweede ontvangstweg
 * bestaat niet; {@link #configured()} is dan {@code false} en elke leesoperatie geeft 404
 * {@link #NOT_CONFIGURED} (D2). Is ze wél gezet maar onbruikbaar, dan start de applicatie <b>niet</b> op
 * (D3): een stille "feature werkt niet"-toestand zou pas bij de eerste levering opvallen.
 * <p>
 * <b>Beveiliging (verplicht, niet optioneel).</b> De client levert altijd een <i>kale bestandsnaam</i> en
 * nooit een pad:
 * <ol>
 *   <li>naamvalidatie weigert padscheidingstekens, {@code :}, NUL en andere controletekens, {@code ..},
 *       een naam die met een punt begint, en een lege of te lange naam;</li>
 *   <li>de naam wordt <b>nooit met de root geconcateneerd</b>: de map wordt gelijst en er moet een exacte
 *       naammatch tussen de bestaande ingangen zijn (whitelist-resolutie);</li>
 *   <li>daarna nog een {@code toRealPath()}-containmentcheck tegen de root (defence in depth), en</li>
 *   <li>symlinks worden altijd geweigerd, ook als ze binnen de root zouden blijven.</li>
 * </ol>
 * <b>Een absoluut pad komt nooit in een foutboodschap</b> die de API teruggeeft — enkel de bestandsnaam. Het
 * volledige pad gaat uitsluitend naar de logger; de startupfouten hieronder zijn daarop de enige
 * uitzondering, want die verschijnen nooit in een HTTP-antwoord.
 */
@Component
public class LocalSourceDirectory {

    /** 404: {@code catalogimport.local-source.directory} is niet gezet — deze ontvangstweg bestaat niet (D2). */
    public static final String NOT_CONFIGURED = "LOCAL_SOURCE_NOT_CONFIGURED";

    /** 409: de map was bij het opstarten in orde maar is nu onbereikbaar (losgekoppelde share, rechten weg). */
    public static final String DIRECTORY_UNAVAILABLE = "LOCAL_SOURCE_DIRECTORY_UNAVAILABLE";

    /** 400: de opgegeven naam is geen kale bestandsnaam of is anderszins onaanvaardbaar. */
    public static final String FILE_NAME_INVALID = "LOCAL_SOURCE_FILE_NAME_INVALID";

    /** 404: er staat geen bestand met die naam in de map. */
    public static final String FILE_NOT_FOUND = "LOCAL_SOURCE_FILE_NOT_FOUND";

    /** 409: de ingang bestaat maar is geen gewoon bestand (map, symlink, device). */
    public static final String FILE_NOT_REGULAR = "LOCAL_SOURCE_FILE_NOT_REGULAR";

    /**
     * 409: het bestand is tussen de hashpas en de intake-stream van grootte of wijzigingstijd veranderd
     * (Q1). De aanroeper stelt dit vast, niet deze klasse; de code staat hier omdat alle
     * {@code LOCAL_SOURCE_*}-codes bij elkaar horen. Er wordt dan <b>niets</b> geregistreerd: een levering
     * half uit oude en half uit nieuwe bytes mag niet bestaan.
     */
    public static final String FILE_CHANGED = "LOCAL_SOURCE_FILE_CHANGED";

    /** Bovengrens van een bestandsnaam; ruim onder de {@code delivery_file.file_name}-kolom van varchar(500). */
    static final int MAX_FILE_NAME_LENGTH = 255;

    private static final int BUFFER_SIZE = 8 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(LocalSourceDirectory.class);

    /**
     * Eén bestand in de map, zoals de API het toont: nooit een pad, enkel de naam. {@code lastModifiedAt}
     * is de wijzigingstijd van het bestandssysteem — samen met {@code byteSize} de vingerafdruk waarmee
     * vastgesteld wordt of het bestand tussen de hashpas en de intake gewijzigd is (Q1).
     */
    public record LocalSourceFile(String fileName, long byteSize, Instant lastModifiedAt) {
    }

    /** {@code null} wanneer de property niet gezet is: de ontvangstweg bestaat dan niet. */
    private final Path root;

    public LocalSourceDirectory(@Value("${catalogimport.local-source.directory:}") String localSourceDirectory,
                                @Value("${catalogimport.archive.root}") String archiveRoot) {
        if (localSourceDirectory == null || localSourceDirectory.isBlank()) {
            this.root = null;
            LOG.info("catalogimport.local-source.directory is not set; reading deliveries from a server "
                    + "directory is disabled");
            return;
        }
        Path candidate;
        try {
            candidate = Path.of(localSourceDirectory).toAbsolutePath().normalize();
        } catch (InvalidPathException invalid) {
            throw new IllegalStateException("catalogimport.local-source.directory is not a valid path: "
                    + localSourceDirectory, invalid);
        }
        if (!Files.exists(candidate)) {
            throw new IllegalStateException("catalogimport.local-source.directory does not exist: " + candidate);
        }
        if (!Files.isDirectory(candidate)) {
            throw new IllegalStateException("catalogimport.local-source.directory is not a directory: " + candidate);
        }
        if (!Files.isReadable(candidate)) {
            throw new IllegalStateException("catalogimport.local-source.directory is not readable: " + candidate);
        }
        // Geen overlap met het bronarchief: het archief is immutable bewijsmateriaal en mag nooit ook een
        // aanleverplek zijn waar een mens bestanden in zet of overschrijft.
        Path archive = archiveRoot == null || archiveRoot.isBlank()
                ? null : Path.of(archiveRoot).toAbsolutePath().normalize();
        if (archive != null && overlaps(candidate, archive)) {
            throw new IllegalStateException("catalogimport.local-source.directory (" + candidate + ") overlaps "
                    + "catalogimport.archive.root (" + archive + "); the source archive must never also be an "
                    + "intake directory");
        }
        this.root = candidate;
        LOG.info("Local source directory for deliveries: {}", candidate);
    }

    /** {@code false} wanneer de property niet gezet is; beide endpoints geven dan 404 {@link #NOT_CONFIGURED}. */
    public boolean configured() {
        return this.root != null;
    }

    /**
     * De gewone, zichtbare bestanden in de map, gesorteerd op {@code lastModifiedAt} aflopend en bij gelijke
     * tijd op {@code fileName} oplopend (stabiel, zodat twee opvragingen dezelfde volgorde geven).
     * Submappen, symlinks, verborgen bestanden (naam begint met een punt) en namen die de validatie niet
     * halen worden overgeslagen: wat niet in deze lijst staat, is ook niet in te lezen.
     * <p>
     * Een ingang die tijdens het lijsten verdwijnt wordt overgeslagen, niet als fout gemeld: een lijst van
     * een map die een mens op dat moment vult, is per definitie een momentopname.
     *
     * @param max hoogstens zoveel bestanden; de aanroeper vraagt er bewust één te veel op om te weten of er
     *            meer zijn dan hij toont
     * @throws NotFoundException {@link #NOT_CONFIGURED}
     * @throws ConflictException {@link #DIRECTORY_UNAVAILABLE}
     */
    public List<LocalSourceFile> list(int max) {
        Path directory = requireConfigured();
        if (max <= 0) {
            throw new IllegalArgumentException("max must be positive");
        }
        List<LocalSourceFile> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                describe(entry).ifPresent(files::add);
            }
        } catch (IOException failure) {
            throw unavailable(failure);
        }
        // Expliciet getypeerde lambda: een methodereferentie in een geketende comparator kan javac niet
        // afleiden. Aflopend op tijd, oplopend op naam - stabiel, zodat twee opvragingen hetzelfde geven.
        files.sort(Comparator.comparing((LocalSourceFile file) -> file.lastModifiedAt()).reversed()
                .thenComparing(LocalSourceFile::fileName));
        return files.size() > max ? List.copyOf(files.subList(0, max)) : List.copyOf(files);
    }

    /**
     * Naam, grootte en wijzigingstijd van één bestand — de vingerafdruk die vóór de hashpas vastgelegd en
     * vlak vóór de intake-stream opnieuw gecontroleerd wordt (Q1).
     *
     * @throws NotFoundException   {@link #NOT_CONFIGURED} of {@link #FILE_NOT_FOUND}
     * @throws ConflictException   {@link #DIRECTORY_UNAVAILABLE} of {@link #FILE_NOT_REGULAR}
     * @throws BadRequestException {@link #FILE_NAME_INVALID}
     */
    public LocalSourceFile stat(String fileName) {
        String name = validateFileName(fileName);
        Path file = resolve(name);
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw notRegular(name);
            }
            return new LocalSourceFile(name, attributes.size(), attributes.lastModifiedTime().toInstant());
        } catch (IOException gone) {
            // Verdwenen tussen de lijst en de stat: dat is "niet gevonden", geen technische fout.
            LOG.info("Local source file '{}' could not be read: {}", name, gone.toString());
            throw notFound(name);
        }
    }

    /**
     * Opent het bestand om te lezen. De aanroeper sluit de stream; er wordt niets gewijzigd aan het
     * bronbestand (D7).
     *
     * @throws NotFoundException   {@link #NOT_CONFIGURED} of {@link #FILE_NOT_FOUND}
     * @throws ConflictException   {@link #DIRECTORY_UNAVAILABLE} of {@link #FILE_NOT_REGULAR}
     * @throws BadRequestException {@link #FILE_NAME_INVALID}
     */
    public InputStream open(String fileName) {
        String name = validateFileName(fileName);
        Path file = resolve(name);
        try {
            return Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException gone) {
            LOG.info("Local source file '{}' could not be opened: {}", name, gone.toString());
            throw notFound(name);
        }
    }

    /**
     * De SHA-256 van de inhoud in hex, gestreamd met een buffer van {@value #BUFFER_SIZE} bytes — het
     * bestand komt nooit in het geheugen (zelfde patroon als {@link DeliveryArchiveStore#store}).
     * <p>
     * Dit is de extra leespas die Q1 (optie A) bewust kost: de server leidt de {@code deliveryReference}
     * zelf af, zodat hetzelfde bestand via browser óf servermap dezelfde levering is.
     */
    public String sha256Hex(String fileName) {
        MessageDigest digest = newDigest();
        try (DigestInputStream in = new DigestInputStream(open(fileName), digest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (in.read(buffer) != -1) {
                // De digest loopt mee in read(); de bytes zelf zijn hier niet nodig.
            }
        } catch (IOException failure) {
            LOG.info("Local source file '{}' could not be hashed: {}", fileName, failure.toString());
            throw notFound(fileName);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Whitelist-resolutie: de map wordt gelijst en er moet een <b>exacte</b> naammatch zijn. De naam van de
     * client wordt nooit aan de root geconcateneerd, dus kan hij ook nooit buiten de map wijzen. Daarna nog
     * twee onafhankelijke controles: geen symlink, en een {@code toRealPath()} die binnen de root blijft.
     */
    private Path resolve(String name) {
        Path directory = requireConfigured();
        Path match = null;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().equals(name)) {
                    match = entry;
                    break;
                }
            }
        } catch (IOException failure) {
            throw unavailable(failure);
        }
        if (match == null) {
            throw notFound(name);
        }
        if (Files.isSymbolicLink(match)) {
            LOG.warn("Refused local source entry '{}': symbolic links are never read", name);
            throw notRegular(name);
        }
        Path real;
        Path realRoot;
        try {
            real = match.toRealPath();
            realRoot = directory.toRealPath();
        } catch (IOException gone) {
            LOG.info("Local source entry '{}' could not be resolved: {}", name, gone.toString());
            throw notFound(name);
        }
        if (real.getParent() == null || !real.getParent().equals(realRoot)
                || !real.getFileName().toString().equals(name)) {
            // Defence in depth: hier komen we alleen als het bestandssysteem iets anders oplevert dan de
            // ingang die we net gelijst hebben. Het pad gaat naar de log, nooit naar de API.
            LOG.warn("Refused local source entry '{}': resolved path {} is not directly inside {}", name, real,
                    realRoot);
            throw notRegular(name);
        }
        return real;
    }

    /** Een ingang uit de lijst, of leeg wanneer ze niet aangeboden mag worden. */
    private Optional<LocalSourceFile> describe(Path entry) {
        String name = entry.getFileName().toString();
        if (name.startsWith(".") || !isAcceptableFileName(name)) {
            return Optional.empty();
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                return Optional.empty(); // submap, symlink, device: niet in te lezen (D9)
            }
            return Optional.of(new LocalSourceFile(name, attributes.size(),
                    attributes.lastModifiedTime().toInstant()));
        } catch (IOException disappeared) {
            return Optional.empty();
        }
    }

    private Path requireConfigured() {
        if (this.root == null) {
            throw new NotFoundException(NOT_CONFIGURED,
                    "Reading deliveries from a server directory is not configured on this environment");
        }
        return this.root;
    }

    /**
     * Kale bestandsnaam, nooit een pad. Geweigerd worden: leeg of te lang, padscheidingstekens, {@code :},
     * NUL en alle andere controletekens, de voor Windows verboden tekens, {@code ..} op welke plaats ook,
     * een naam die met een punt begint (verborgen bestanden worden ook niet gelijst), en alles wat het
     * bestandssysteem niet als één naamcomponent ziet.
     */
    static String validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BadRequestException(FILE_NAME_INVALID, "fileName is required");
        }
        String name = fileName;
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            throw new BadRequestException(FILE_NAME_INVALID,
                    "fileName exceeds " + MAX_FILE_NAME_LENGTH + " characters");
        }
        if (!isAcceptableFileName(name)) {
            // Bewust zonder de aangeleverde waarde in de boodschap: die kan een pad of stuurtekens bevatten.
            throw new BadRequestException(FILE_NAME_INVALID,
                    "fileName must be a plain file name in the configured directory: no path separators, "
                            + "no drive letters, no '..', no control characters, and not starting with a dot");
        }
        return name;
    }

    private static boolean isAcceptableFileName(String name) {
        if (name.isBlank() || name.length() > MAX_FILE_NAME_LENGTH || name.startsWith(".")
                || name.contains("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || "/\\:<>\"|?*".indexOf(c) >= 0) {
                return false;
            }
        }
        if (!name.equals(name.strip())) {
            return false; // een naam met rand-witruimte verwijst op Windows naar een ander bestand
        }
        try {
            Path asPath = Path.of(name);
            return !asPath.isAbsolute() && asPath.getNameCount() == 1
                    && asPath.getFileName().toString().equals(name);
        } catch (InvalidPathException invalid) {
            return false;
        }
    }

    private ConflictException unavailable(IOException failure) {
        // De oorzaak (en het pad) staan in de log; het antwoord noemt enkel dat de map niet leesbaar is.
        LOG.error("Local source directory {} is unavailable", this.root, failure);
        return new ConflictException(DIRECTORY_UNAVAILABLE,
                "The configured server directory cannot be read right now; ask an administrator to check it");
    }

    private static NotFoundException notFound(String name) {
        return new NotFoundException(FILE_NOT_FOUND,
                "No file named '" + name + "' is available in the configured server directory");
    }

    private static ConflictException notRegular(String name) {
        return new ConflictException(FILE_NOT_REGULAR,
                "'" + name + "' is not a regular file in the configured server directory");
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 not available", unavailable);
        }
    }
}
