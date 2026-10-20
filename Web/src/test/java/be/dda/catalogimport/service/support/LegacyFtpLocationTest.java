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
 * Slice S4: protocol en host van de legacy-bestemming {@code FTP}+{@code FTP2} ({@code Prg_1232} r.298-300, aanname A7).
 * Pure unit-test, geen Spring.
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
            "ftps://ftp.example.test/x           | UNKNOWN | ftp.example.test  | NULL | /x",
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

    @Test
    void otherLabelsAndTheSecretAreIgnored() {
        ObjectNode bundle = bundle("FTP_login", "user", "SFTP", "Y");
        bundle.putObject("secret").put("label", "FTP_password").put("value", "sftp://x.test");

        assertThat(LegacyFtpLocation.of(bundle).protocol()).isEqualTo(Protocol.NONE);
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
