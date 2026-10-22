package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.service.support.LegacyFtpLocation.Protocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Slice S4: protocol en host van de legacy-bestemming {@code FTP}+{@code FTP2} ({@code Prg_1232} r.298-300, aanname A7);
 * LF (beslissingslog 2026-10-22 "LF: uitwerking FTP-/API-wachtwoorden bewaren"): {@code FTP/LOCAL/API}, hint
 * {@code SFTP} en bestemmingen zonder schema ({@code Prg_1232} r.454-499, r.622-624). Pure unit-test, geen Spring.
 */
class LegacyFtpLocationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "sftp://sftp.example.test/exports/   | SFTP    | sftp.example.test | NULL | /exports/",
            "SFTP://SFTP.Example.TEST.           | SFTP    | sftp.example.test | NULL | ''",
            "sftp://files.example.test:2222/in   | SFTP    | files.example.test | 2222 | /in",
            "sftp://[2001:DB8::1]:22/x?y         | SFTP    | [2001:db8::1]     | 22   | /x",
            "sftp://host.test#frag               | SFTP    | host.test         | NULL | ''",
            "ftp://ftp.example.test/x            | FTP     | ftp.example.test  | NULL | /x",
            "ftps://ftp.example.test/x           | FTP     | ftp.example.test  | NULL | /x",
            "FTPES://ftp.example.test:990/x      | FTP     | ftp.example.test  | 990  | /x",
            "https://api.example.test/x          | UNKNOWN | api.example.test  | NULL | /x",
            "sftp.example.test                   | UNKNOWN | NULL              | NULL | ''",
            "sftp:///exports/                    | SFTP    | NULL              | NULL | /exports/",
            "sftp://host.test:0/x                | SFTP    | NULL              | NULL | /x",
            "sftp://host.test:65536/x            | SFTP    | NULL              | NULL | /x",
            "sftp://host.test:22a/x              | SFTP    | NULL              | NULL | /x",
            "sftp://a:b:c/x                      | SFTP    | NULL              | NULL | /x",
            "sftp://user@host.test/x             | SFTP    | NULL              | NULL | /x",
            "sftp://host.[YYYY].test/x           | SFTP    | NULL              | NULL | /x",
            "sftp://[not-ipv6]/x                 | SFTP    | NULL              | NULL | /x",
            "sftp://[::1/x                       | SFTP    | NULL              | NULL | /x",
            "sftp://hôst.test/x                  | SFTP    | NULL              | NULL | /x"})
    void parsesProtocolHostPortAndDirectory(String url, Protocol protocol, String host, Integer port,
                                            String directory) {
        LegacyFtpLocation location = LegacyFtpLocation.parse(url);

        assertThat(location.protocol()).isEqualTo(protocol);
        assertThat(location.host()).isEqualTo(host);
        assertThat(location.hasValidHost()).isEqualTo(host != null);
        assertThat(location.port()).isEqualTo(port);
        assertThat(location.directory()).isEqualTo(directory);
    }

    @Test
    void anEmptyDestinationIsNone() {
        assertThat(LegacyFtpLocation.parse("   ").protocol()).isEqualTo(Protocol.NONE);
        assertThat(LegacyFtpLocation.parse(null).protocol()).isEqualTo(Protocol.NONE);
        assertThat(LegacyFtpLocation.of(bundle()).protocol()).isEqualTo(Protocol.NONE);
    }

    @Test
    void ftpAndFtp2AreTrimmedAndConcatenatedAsInLegacy() {
        LegacyFtpLocation location = LegacyFtpLocation.of(bundle("FTP", " sftp://files. ", "FTP2", " example.test/in "));

        assertThat(location.protocol()).isEqualTo(Protocol.SFTP);
        assertThat(location.host()).isEqualTo("files.example.test");
        assertThat(location.directory()).isEqualTo("/in");
    }

    @Test
    void aDuplicatedFtpLabelIsAmbiguousAndHasNoHost() {
        LegacyFtpLocation location = LegacyFtpLocation.of(bundle("FTP", "sftp://a.test", "FTP", "sftp://b.test"));

        assertThat(location.protocol()).isEqualTo(Protocol.SFTP);
        assertThat(location.hasValidHost()).isFalse();
    }

    // --- LF: FTP/LOCAL/API, hint SFTP, geen schema -----------------------------------------------------------------

    /** De echte gevallen uit de eerste push (beslissingslog 2026-10-22): host zonder schema = FTP, zoals Magic. */
    @Test
    void aHostWithoutSchemeAndWithoutSftpHintIsFtpAsInLegacy() {
        LegacyFtpLocation vrooam = LegacyFtpLocation.of(bundle("FTP", "ftp.vrooamgrossier.be", "SFTP", "N",
                "FTP/LOCAL/API", "F"));
        assertLocation(vrooam, Protocol.FTP, "ftp.vrooamgrossier.be", null, "");
        assertThat(vrooam.scheme()).isNull();

        assertLocation(LegacyFtpLocation.of(bundle("FTP", " DX.Kramp.com. ")), Protocol.FTP, "dx.kramp.com", null, "");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "84.199.80.12")), Protocol.FTP, "84.199.80.12", null, "");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "84.199.80.12:2121")), Protocol.FTP, "84.199.80.12", 2121,
                "");
        // Een lege hint telt niet als gezet, net als N (hoofdletterongevoelig, getrimd).
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftp.example.test", "SFTP", " n ")), Protocol.FTP,
                "ftp.example.test", null, "");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftp.example.test", "SFTP", "  ")), Protocol.FTP,
                "ftp.example.test", null, "");
    }

    @Test
    void ftp2MayAddAPathButNeverChangeTheHost() {
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftp.example.test", "FTP2", "/in/")), Protocol.FTP,
                "ftp.example.test", null, "/in/");
        // FTP2 verlengt de authority: legacy (r.624) gebruikt FTP alleen, dus geen eenduidige host.
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftp.example", "FTP2", ".test/in")), Protocol.FTP, null,
                null, "/in");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftp.example.test", "FTP2", ":2121/in")), Protocol.FTP,
                null, null, "/in");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "", "FTP2", "ftp.example.test")), Protocol.FTP, null, null,
                "");
    }

    @Test
    void anInvalidAuthorityWithoutSchemeHasNoHost() {
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftp.[YYYY].test/in")), Protocol.FTP, null, null, "/in");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "user@ftp.example.test")), Protocol.FTP, null, null, "");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftp.example.test:99999")), Protocol.FTP, null, null, "");
    }

    @Test
    void withoutSchemeTheSftpHintMakesTheProtocolUnknown() {
        for (String hint : new String[] {"Y", "y", "J", " Y "}) {
            assertLocation(LegacyFtpLocation.of(bundle("FTP", "sftp.example.test", "SFTP", hint)), Protocol.UNKNOWN,
                    null, null, "");
        }
    }

    @Test
    void aSchemeWinsOverTheSftpHint() {
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "sftp://sftp.example.test/x", "SFTP", "N")), Protocol.SFTP,
                "sftp.example.test", null, "/x");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftp://ftp.example.test/x", "SFTP", "Y")), Protocol.FTP,
                "ftp.example.test", null, "/x");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftps://ftp.example.test/x", "FTP/LOCAL/API", "F")),
                Protocol.FTP, "ftp.example.test", null, "/x");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "sftp://sftp.example.test", "FTP/LOCAL/API", "F")),
                Protocol.SFTP, "sftp.example.test", null, "");
    }

    /** Intercars: {@code FTP/LOCAL/API} = A, https-endpoint met een datumpatroon in FTP2. */
    @Test
    void modeAWithHttpsIsAnApiBoundToTheAuthority() {
        LegacyFtpLocation intercars = LegacyFtpLocation.of(bundle(
                "FTP", "https://data.webapi.intercars.eu/customer/P00675/ProductInformation/",
                "FTP2", "ProductInformation_[YYYY]-[MM]-[DD].csv.zip", "FTP/LOCAL/API", "A"));

        assertLocation(intercars, Protocol.API, "data.webapi.intercars.eu", null,
                "/customer/P00675/ProductInformation/ProductInformation_[YYYY]-[MM]-[DD].csv.zip");
        assertThat(intercars.scheme()).isEqualTo("https");
        // Alleen het eerste teken telt, zoals Magic Left(...,1).
        LegacyFtpLocation http = LegacyFtpLocation.of(bundle("FTP", "http://API.Example.TEST:8080/v1",
                "FTP/LOCAL/API", "API"));
        assertLocation(http, Protocol.API, "api.example.test", 8080, "/v1");
        assertThat(http.scheme()).isEqualTo("http");
        // Een datumpatroon in de authority: geen host.
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "https://data-[YYYY].example.test/x", "FTP/LOCAL/API",
                "A")), Protocol.API, null, null, "/x");
    }

    @Test
    void modeAWithoutAnHttpSchemeIsUnknownAndAnHttpSchemeWithoutModeAIsUnknown() {
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "data.webapi.intercars.eu/x", "FTP/LOCAL/API", "A")),
                Protocol.UNKNOWN, null, null, "");
        assertThat(LegacyFtpLocation.of(bundle("FTP", "sftp://sftp.example.test/x", "FTP/LOCAL/API", "A")).protocol())
                .isEqualTo(Protocol.UNKNOWN);
        assertThat(LegacyFtpLocation.of(bundle("FTP", "ftp://ftp.example.test/x", "FTP/LOCAL/API", "A")).protocol())
                .isEqualTo(Protocol.UNKNOWN);
        assertThat(LegacyFtpLocation.of(bundle("FTP", "https://api.example.test/x", "FTP/LOCAL/API", "F")).protocol())
                .isEqualTo(Protocol.UNKNOWN);
        assertThat(LegacyFtpLocation.of(bundle("FTP", "https://api.example.test/x")).protocol())
                .isEqualTo(Protocol.UNKNOWN);
        // Hoofdlettergevoelig zoals Magic: "a" is geen A.
        assertThat(LegacyFtpLocation.of(bundle("FTP", "https://api.example.test/x", "FTP/LOCAL/API", "a")).protocol())
                .isEqualTo(Protocol.UNKNOWN);
    }

    @Test
    void modeLIsALocalFileWithoutHostWhateverTheValue() {
        for (String ftp : new String[] {"C:\\import\\kramp", "sftp://sftp.example.test/x", "ftp.example.test"}) {
            LegacyFtpLocation local = LegacyFtpLocation.of(bundle("FTP", ftp, "FTP/LOCAL/API", "L"));
            assertThat(local.protocol()).as(ftp).isEqualTo(Protocol.LOCAL);
            assertThat(local.hasValidHost()).as(ftp).isFalse();
        }
        // Zonder bestemming blijft het NONE, ook met L of A.
        assertThat(LegacyFtpLocation.of(bundle("FTP", " ", "FTP/LOCAL/API", "L")).protocol()).isEqualTo(Protocol.NONE);
        assertThat(LegacyFtpLocation.of(bundle("FTP/LOCAL/API", "A")).protocol()).isEqualTo(Protocol.NONE);
    }

    @Test
    void aDuplicatedModeOrSftpLabelIsUnknown() {
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftp.example.test", "FTP/LOCAL/API", "F",
                "FTP/LOCAL/API", "F")), Protocol.UNKNOWN, null, null, "");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "sftp://sftp.example.test", "SFTP", "N", "SFTP", "N")),
                Protocol.UNKNOWN, null, null, "");
        // Een dubbel FTP- of FTP2-label: protocol zoals hierboven, maar geen host (bestaand gedrag).
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "a.test", "FTP", "b.test")), Protocol.FTP, null, null, "");
        assertLocation(LegacyFtpLocation.of(bundle("FTP", "ftp://a.test", "FTP2", "/x", "FTP2", "/y")), Protocol.FTP,
                null, null, "/x");
    }

    @Test
    void otherLabelsAndTheSecretAreIgnored() {
        ObjectNode bundle = bundle("FTP_login", "user", "SFTP", "Y");
        bundle.putObject("secret").put("label", "FTP_password").put("value", "sftp://x.test");

        assertThat(LegacyFtpLocation.of(bundle).protocol()).isEqualTo(Protocol.NONE);
    }

    private static void assertLocation(LegacyFtpLocation location, Protocol protocol, String host, Integer port,
                                       String directory) {
        assertThat(location.protocol()).isEqualTo(protocol);
        assertThat(location.host()).isEqualTo(host);
        assertThat(location.hasValidHost()).isEqualTo(host != null);
        assertThat(location.port()).isEqualTo(port);
        assertThat(location.directory()).isEqualTo(directory);
    }

    private static ObjectNode bundle(String... labelValues) {
        ObjectNode bundle = JSON.createObjectNode();
        ArrayNode details = bundle.putObject("interface").putArray("details");
        for (int i = 0; i < labelValues.length; i += 2) {
            details.addObject().put("sequence", i).put("label", labelValues[i]).put("value", labelValues[i + 1]);
        }
        return bundle;
    }
}
