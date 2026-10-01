package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.service.DeliveryArchiveStore.ArchivedObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fase 2b: archiefopslag zonder Spring-context; de root is een {@code @TempDir}. */
class DeliveryArchiveStoreTest {

    @TempDir
    Path root;

    @Test
    void storesTheBytesUnderADatedUuidFolderWithCorrectHashAndSize() throws Exception {
        DeliveryArchiveStore store = new DeliveryArchiveStore(root.toString());
        byte[] bytes = "LEVERANCIER;GROEP;REF;PRIJS\nA;B;1;1,50\n".getBytes(StandardCharsets.UTF_8);

        ArchivedObject archived = store.store(new ByteArrayInputStream(bytes), "levering.csv");

        assertThat(archived.byteSize()).isEqualTo(bytes.length);
        assertThat(archived.sha256Hex()).isEqualTo(sha256Hex(bytes));
        assertThat(archived.archiveReference())
                .matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/levering\\.csv");
        assertThat(root.resolve(archived.archiveReference())).exists().hasBinaryContent(bytes);
        try (InputStream in = store.open(archived.archiveReference())) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void streamsALargeFileAcrossManyBuffersWithoutLosingBytes() throws Exception {
        DeliveryArchiveStore store = new DeliveryArchiveStore(root.toString());
        byte[] bytes = new byte[8 * 1024 * 37 + 123];
        new Random(42).nextBytes(bytes);

        ArchivedObject archived = store.store(new ByteArrayInputStream(bytes), "groot.csv");

        assertThat(archived.byteSize()).isEqualTo(bytes.length);
        assertThat(archived.sha256Hex()).isEqualTo(sha256Hex(bytes));
        assertThat(root.resolve(archived.archiveReference())).hasBinaryContent(bytes);
    }

    @Test
    void archivesAnEmptyStreamAsAZeroByteObjectWithTheKnownEmptyHash() {
        DeliveryArchiveStore store = new DeliveryArchiveStore(root.toString());

        ArchivedObject archived = store.store(new ByteArrayInputStream(new byte[0]), "leeg.csv");

        assertThat(archived.byteSize()).isZero();
        assertThat(archived.sha256Hex())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void neverWritesTwiceToTheSamePathForTheSameFileName() {
        DeliveryArchiveStore store = new DeliveryArchiveStore(root.toString());

        ArchivedObject first = store.store(new ByteArrayInputStream("een".getBytes(StandardCharsets.UTF_8)), "x.csv");
        ArchivedObject second = store.store(new ByteArrayInputStream("twee".getBytes(StandardCharsets.UTF_8)), "x.csv");

        assertThat(first.archiveReference()).isNotEqualTo(second.archiveReference());
        assertThat(root.resolve(first.archiveReference())).hasContent("een");
        assertThat(root.resolve(second.archiveReference())).hasContent("twee");
    }

    @Test
    void sanitisesTraversalAndPathSeparatorsInTheFileNameButStaysInsideTheRoot() throws Exception {
        DeliveryArchiveStore store = new DeliveryArchiveStore(root.toString());

        for (String hostile : new String[] {"../evil.csv", "..\\evil.csv", "a/b/../../evil.csv", "/etc/evil.csv",
                "C:\\Windows\\evil.csv"}) {
            ArchivedObject archived = store.store(new ByteArrayInputStream(new byte[] {1}), hostile);

            assertThat(archived.archiveReference()).as(hostile).endsWith("/evil.csv")
                    .doesNotContain("..").doesNotContain("\\");
            assertThat(root.resolve(archived.archiveReference()).normalize()).startsWith(root);
        }
        try (var walk = Files.walk(root)) {
            assertThat(walk.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
                    .containsOnly("evil.csv");
        }
        // Niets ontsnapt naar de map boven de root.
        assertThat(root.getParent().resolve("evil.csv")).doesNotExist();
    }

    @Test
    void sanitisesDegenerateNamesAndLimitsTheLengthToTwoHundredCharacters() {
        assertThat(DeliveryArchiveStore.sanitizeFileName(null)).isEqualTo("file");
        assertThat(DeliveryArchiveStore.sanitizeFileName("..")).isEqualTo("file");
        assertThat(DeliveryArchiveStore.sanitizeFileName("   ")).isEqualTo("file");
        assertThat(DeliveryArchiveStore.sanitizeFileName("a<b>:c?.csv")).isEqualTo("a_b__c_.csv");
        assertThat(DeliveryArchiveStore.sanitizeFileName("NUL.csv")).isEqualTo("_NUL.csv");

        String longName = "x".repeat(300) + ".csv";
        String sanitised = DeliveryArchiveStore.sanitizeFileName(longName);
        assertThat(sanitised).hasSize(200).endsWith(".csv");
    }

    @Test
    void refusesToOpenReferencesOutsideTheArchiveRoot() throws IOException {
        DeliveryArchiveStore store = new DeliveryArchiveStore(root.resolve("archive").toString());
        Path outside = Files.writeString(root.resolve("geheim.txt"), "geheim");

        assertThatThrownBy(() -> store.open("../geheim.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open("2026/09/18/../../../../geheim.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open(outside.toString())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open(".")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesAnArchivedObjectAndItsFolderQuietlyAndNeverTouchesFilesOutsideTheRoot() throws IOException {
        DeliveryArchiveStore store = new DeliveryArchiveStore(root.resolve("archive").toString());
        Path outside = Files.writeString(root.resolve("blijft.txt"), "blijft");
        ArchivedObject archived = store.store(new ByteArrayInputStream(new byte[] {1, 2, 3}), "weg.csv");
        Path file = root.resolve("archive").resolve(archived.archiveReference());
        assertThat(file).exists();

        store.deleteQuietly(archived.archiveReference());

        assertThat(file).doesNotExist();
        assertThat(file.getParent()).doesNotExist();
        assertThatCode(() -> store.deleteQuietly(archived.archiveReference())).doesNotThrowAnyException();
        assertThatCode(() -> store.deleteQuietly("../blijft.txt")).doesNotThrowAnyException();
        assertThat(outside).exists();
    }

    @Test
    void removesAPartiallyWrittenObjectWhenTheSourceStreamFails() throws IOException {
        DeliveryArchiveStore store = new DeliveryArchiveStore(root.toString());
        InputStream failing = new InputStream() {
            private int count;

            @Override
            public int read() throws IOException {
                if (count++ > 10) {
                    throw new IOException("boom");
                }
                return 'x';
            }
        };

        assertThatThrownBy(() -> store.store(failing, "kapot.csv")).isInstanceOf(java.io.UncheckedIOException.class);
        try (var walk = Files.walk(root)) {
            assertThat(walk.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void requiresAConfiguredRoot() {
        assertThatThrownBy(() -> new DeliveryArchiveStore(" ")).isInstanceOf(IllegalStateException.class);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
