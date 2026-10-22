package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.service.PublicationArtifactStore.StoredArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P2c: {@link PublicationArtifactStore#writeSibling} en {@link PublicationArtifactStore#siblingReference} — een extra
 * artefact van dezelfde run komt in de map van het eerste artefact (ook als de klok intussen een dag verder staat), wordt
 * nooit overschreven, laat bij een fout geen bestand achter, en een bestandsnaam of referentie kan nooit buiten de
 * artefactmap wijzen.
 */
class PublicationArtifactStoreSiblingTest {

    @TempDir
    Path root;

    @Test
    void aSiblingLandsNextToTheFirstArtifactEvenAfterMidnightAndIsNeverOverwritten() throws IOException {
        StoredArtifact first = store("2026-10-22T23:59:59Z").write(5L, out -> out.write("csv"));
        PublicationArtifactStore nextDay = store("2026-10-23T00:00:01Z");

        StoredArtifact sibling = nextDay.writeSibling(first.reference(), "extra.json", out -> out.write("{}\n"));

        assertThat(first.reference()).isEqualTo("publication-runs/2026/10/22/5/psimport-preview.csv");
        assertThat(sibling.reference()).isEqualTo("publication-runs/2026/10/22/5/extra.json");
        assertThat(sibling.byteSize()).isEqualTo(3L);
        try (InputStream in = nextDay.open(sibling.reference())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{}\n");
        }
        assertThatThrownBy(() -> nextDay.writeSibling(first.reference(), "extra.json", out -> out.write("x")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(root.resolve(sibling.reference()))).isEqualTo("{}\n");
    }

    @Test
    void aFailingSiblingLeavesNoFileAndTheFirstArtifactIntact() throws IOException {
        PublicationArtifactStore store = store("2026-10-22T10:00:00Z");
        StoredArtifact first = store.write(6L, out -> out.write("csv"));

        assertThatThrownBy(() -> store.writeSibling(first.reference(), "broken.json", out -> {
            out.write("half");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        try (Stream<Path> files = Files.list(root.resolve(first.reference()).getParent())) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("psimport-preview.csv");
        }
        assertThat(store.exists(store.siblingReference(first.reference(), "broken.json"))).isFalse();
    }

    @Test
    void fileNamesAndReferencesCannotEscapeTheArtifactDirectory() {
        PublicationArtifactStore store = store("2026-10-22T10:00:00Z");
        StoredArtifact first = store.write(7L, out -> out.write("csv"));

        for (String name : new String[] {"../escape.json", "..", "a/b.json", "a\\b.json", "Upper.json", "",
                "x.json.tmp", PublicationArtifactStore.ARTIFACT_FILE_NAME}) {
            assertThatThrownBy(() -> store.siblingReference(first.reference(), name))
                    .as(name).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> store.siblingReference(null, "x.json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.siblingReference("../outside/file.csv", "x.json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.writeSibling("2026/10/22/1/delivery.csv", "x.json", out -> out.write("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PublicationArtifactStore store(String instant) {
        return new PublicationArtifactStore(root.toString(), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
