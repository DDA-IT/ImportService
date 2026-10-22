package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.PublicationBundleSnapshotDao.SnapshotHasher;
import java.math.BigDecimal;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * P4b (design {@code publicatie-prodis-db-direct-design.md} par. 2.5 en 6; {@code fase5-pub-design.md} par. 1 en 7;
 * beslissing 2026-09-26 "Hash"): de canonieke serialisatie van de {@code snapshot_hash}, los van de database, met
 * <b>letterlijke</b> hashes.
 * <ul>
 *   <li><b>Spec v1 byte-identiek:</b> {@link #PRE_P4B_V1_HASH} is berekend met een verbatim kopie van de v1-code van
 *       {@code PublicationBundleSnapshotDao.computeSnapshotHash} op commit {@code 9356a35} (vóór P4b), en onafhankelijk
 *       met een letterlijk opgebouwde tekst volgens de gedocumenteerde spec. De huidige v1-route moet die waarde exact
 *       opnieuw opleveren — ook als de componenten een {@code before_percentage} dragen (v1 leest die niet).</li>
 *   <li><b>Spec v2:</b> {@link #V2_HASH} is berekend uit de letterlijk opgebouwde v2-tekst (een extra veld
 *       {@code before_percentage} achteraan elke componentgroep). Deterministisch, onafhankelijk van de
 *       componentvolgorde, en anders dan zonder {@code before_percentage}.</li>
 * </ul>
 * Puur JUnit: geen Spring-context, geen database.
 */
class SnapshotHashSpecTest {

    /** Pre-P4b (commit 9356a35) v1-hash van {@link #feed}; zie de klassedocumentatie. */
    private static final String PRE_P4B_V1_HASH = "f488be6f1c5ed80744c0d11e1efebe47553aa05e40d0189b2e1863ff40cbb15d";

    /** v2-hash van {@link #feed} met de voor-percentages 75 / 110 / null. */
    private static final String V2_HASH = "5519df2158d916de49139965d1a600c16fd4b3c47beda70514de613268183e2f";

    @Test
    void specVersion1ReproducesTheLiteralPreP4bHashAndIgnoresBeforePercentages() {
        assertThat(hex(feed(new SnapshotHasher("1"), true, false))).isEqualTo(PRE_P4B_V1_HASH);
        // Ook zonder voor-percentages: v1 leest het veld gewoon niet.
        assertThat(hex(feed(new SnapshotHasher("1"), false, false))).isEqualTo(PRE_P4B_V1_HASH);
    }

    @Test
    void specVersion2ProducesTheLiteralHashOfTheDocumentedText() {
        assertThat(hex(feed(new SnapshotHasher("2"), true, false))).isEqualTo(V2_HASH);
    }

    @Test
    void specVersion2IsDeterministicIndependentOfComponentOrderAndDiffersFromV1() {
        byte[] first = feed(new SnapshotHasher("2"), true, false);
        byte[] again = feed(new SnapshotHasher("2"), true, false);
        byte[] reversed = feed(new SnapshotHasher("2"), true, true);

        assertThat(again).isEqualTo(first);
        assertThat(reversed).isEqualTo(first);
        assertThat(hex(first)).isNotEqualTo(PRE_P4B_V1_HASH);
    }

    @Test
    void specVersion2DiffersWithoutBeforePercentageAndOnEveryChangedBeforeValue() {
        byte[] with = feed(new SnapshotHasher("2"), true, false);
        byte[] without = feed(new SnapshotHasher("2"), false, false);
        assertThat(without).isNotEqualTo(with);
        // Zonder voor-percentages is v2 nog steeds niet de v1-tekst: elke componentgroep draagt "null".
        assertThat(hex(without)).isNotEqualTo(PRE_P4B_V1_HASH);

        SnapshotHasher changed = new SnapshotHasher("2");
        changed.row(1001, "VALUE", "Boormachine");
        changed.component("AKP", new BigDecimal("80.000000"), new BigDecimal("80.000000000000"), "EUR", "OK",
                new BigDecimal("75"));
        changed.component("VKP1", new BigDecimal("120.000000"), new BigDecimal("120.000000000000"), "EUR", "OK",
                new BigDecimal("110.000000000001"));
        changed.row(1002, "EMPTY", "");
        changed.row(1003, "NOT_MAPPED", null);
        changed.component("VKP2", null, new BigDecimal("33.333333333333"), null, "OK", null);
        assertThat(changed.finish()).isNotEqualTo(with);
    }

    /** Schaal telt niet mee (stripTrailingZeros), zoals bij percentage en bedrag. */
    @Test
    void trailingZerosOfBeforePercentageDoNotChangeTheHash() {
        SnapshotHasher scaled = new SnapshotHasher("2");
        scaled.row(1001, "VALUE", "Boormachine");
        scaled.component("AKP", new BigDecimal("80.000000"), new BigDecimal("80.000000000000"), "EUR", "OK",
                new BigDecimal("75.000000000000"));
        scaled.component("VKP1", new BigDecimal("120.000000"), new BigDecimal("120.000000000000"), "EUR", "OK",
                new BigDecimal("110.000000000000"));
        scaled.row(1002, "EMPTY", "");
        scaled.row(1003, "NOT_MAPPED", null);
        scaled.component("VKP2", null, new BigDecimal("33.333333333333"), null, "OK", null);
        assertThat(hex(scaled.finish())).isEqualTo(V2_HASH);
    }

    /** Zonder prijscomponenten is er geen voor-percentage: beide versies serialiseren gelijk (gedocumenteerd). */
    @Test
    void rowsWithoutComponentsSerialiseIdenticallyInBothVersions() {
        SnapshotHasher v1 = new SnapshotHasher("1");
        SnapshotHasher v2 = new SnapshotHasher("2");
        for (SnapshotHasher hasher : new SnapshotHasher[] {v1, v2}) {
            hasher.row(7, "VALUE", "Hamer");
            hasher.row(8, "NOT_MAPPED", null);
        }
        assertThat(v2.finish()).isEqualTo(v1.finish());
    }

    @Test
    void noRowsGiveNoHash() {
        assertThat(new SnapshotHasher("1").finish()).isNull();
        assertThat(new SnapshotHasher("2").finish()).isNull();
    }

    @Test
    void anUnknownSpecVersionIsRefusedAndNeverFallsBackSilently() {
        for (String version : new String[] {null, "", "3", "v2", " 2", "02"}) {
            assertThatThrownBy(() -> new SnapshotHasher(version))
                    .as("version '%s'", version)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown snapshot spec version");
        }
    }

    @Test
    void theCurrentSpecVersionIs2() {
        assertThat(PublicationBundleSnapshotDao.SNAPSHOT_SPEC_VERSION).isEqualTo("2");
        assertThat(PublicationBundleSnapshotDao.SNAPSHOT_SPEC_VERSION_1).isEqualTo("1");
        assertThat(PublicationBundleSnapshotDao.SNAPSHOT_SPEC_VERSION_2).isEqualTo("2");
    }

    /**
     * Vaste invoer, zoals de {@code left join} hem aanlevert (rij herhaald per component): mutatie 1001 met AKP en VKP1,
     * 1002 zonder componenten en lege omschrijving, 1003 niet gemapt met een VKP2 zonder bedrag en zonder munt.
     *
     * @param withBefore voor-percentages 75 / 110 / null meegeven, anders overal {@code null}
     * @param reversed   de componenten van 1001 in omgekeerde volgorde aanbieden
     */
    private static byte[] feed(SnapshotHasher hasher, boolean withBefore, boolean reversed) {
        BigDecimal akpBefore = withBefore ? new BigDecimal("75") : null;
        BigDecimal vkp1Before = withBefore ? new BigDecimal("110.000000000000") : null;
        if (reversed) {
            hasher.row(1001, "VALUE", "Boormachine");
            hasher.component("VKP1", new BigDecimal("120.000000"), new BigDecimal("120.000000000000"), "EUR", "OK",
                    vkp1Before);
            hasher.row(1001, "VALUE", "Boormachine");
            hasher.component("AKP", new BigDecimal("80.000000"), new BigDecimal("80.000000000000"), "EUR", "OK",
                    akpBefore);
        } else {
            hasher.row(1001, "VALUE", "Boormachine");
            hasher.component("AKP", new BigDecimal("80.000000"), new BigDecimal("80.000000000000"), "EUR", "OK",
                    akpBefore);
            hasher.row(1001, "VALUE", "Boormachine");
            hasher.component("VKP1", new BigDecimal("120.000000"), new BigDecimal("120.000000000000"), "EUR", "OK",
                    vkp1Before);
        }
        hasher.row(1002, "EMPTY", "");
        hasher.row(1003, "NOT_MAPPED", null);
        hasher.component("VKP2", null, new BigDecimal("33.333333333333"), null, "OK", null);
        return hasher.finish();
    }

    private static String hex(byte[] hash) {
        return HexFormat.of().formatHex(hash);
    }
}
