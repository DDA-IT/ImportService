package be.dda.catalogimport.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * De legacy-bestemming van een interface ({@code FTP} + {@code FTP2}), zoals legacy ze samenstelt:
 * {@code Trim(FTP) & Trim(FTP2)} ({@code Prg_1232} r.298-300). Slice S4 van
 * {@code docs/design/legacy-config-bundle-design.md} (par. 2 "Credential bij ontvangst", par. 5 rij {@code FTP}/...,
 * aanname A7). Pure klasse: geen Spring, geen database, geen DNS.
 *
 * <h2>Regels</h2>
 * <ul>
 *   <li><b>Protocol (A7):</b> enkel uit het URL-schema van de samengestelde waarde; het INDET-label {@code SFTP} is
 *       hoogstens een hint en wordt hier niet gelezen. {@code sftp://} = {@link Protocol#SFTP}; {@code ftp://} =
 *       {@link Protocol#FTP}; elk ander schema of geen schema = {@link Protocol#UNKNOWN}.</li>
 *   <li><b>Geen bestemming</b> (beide labels leeg of afwezig): {@link Protocol#NONE}.</li>
 *   <li><b>Host:</b> de authority (na {@code ://}, tot {@code /}, {@code ?}, {@code #} of het einde) zonder poort,
 *       genormaliseerd met {@link HostNames}. Ongeldig ({@code host = null}): lege authority, een {@code @} (userinfo
 *       hoort er volgens contract par. 2.1 nooit in te staan), een poort die geen getal 1-65535 is, meer dan één
 *       {@code :} buiten een IPv6-literal, haken die geen IPv6-literal vormen (bv. een legacy-patroon {@code [YYYY]}),
 *       of wat {@link HostNames} weigert. Een label dat meer dan eens voorkomt, is niet eenduidig: ook ongeldig.</li>
 *   <li><b>Poort:</b> {@code null} = niet opgegeven (de standaard van het protocol geldt).</li>
 *   <li><b>Map:</b> het pad na de authority (tot {@code ?}/{@code #}), of {@code ""}.</li>
 * </ul>
 * Deze klasse ziet nooit een secret: de bundel in {@code bundle_json} heeft er geen.
 *
 * @param protocol  zie {@link Protocol}
 * @param scheme    het URL-schema in kleine letters, of {@code null} zonder schema
 * @param host      de genormaliseerde host, of {@code null} als er geen geldige host is
 * @param port      de expliciete poort, of {@code null}
 * @param directory het pad uit de URL, of {@code ""}
 */
public record LegacyFtpLocation(Protocol protocol, String scheme, String host, Integer port, String directory) {

    /** Het protocol volgens het URL-schema. */
    public enum Protocol {
        /** {@code sftp://}: het enige protocol waarvoor een credential wordt overgenomen. */
        SFTP,
        /** {@code ftp://}: niet ondersteund (CatalogImport kent enkel SFTP). */
        FTP,
        /** Een ander schema of geen schema: protocol niet af te leiden. */
        UNKNOWN,
        /** Geen bestemming: {@code FTP} en {@code FTP2} leeg of afwezig. */
        NONE
    }

    static final String LABEL_FTP = "FTP";
    static final String LABEL_FTP2 = "FTP2";
    private static final int MAX_PORT = 65535;
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("\\[[0-9A-Fa-f:.]+]");

    /** Is er een geldige host om een credential aan te binden? */
    public boolean hasValidHost() {
        return host != null;
    }

    /**
     * Leest {@code FTP} en {@code FTP2} uit {@code interface.details} van een (gevalideerde) bundel.
     *
     * @param bundle de bundel (de bewaarde boom of de ontvangen boom; het secret wordt niet gelezen)
     */
    public static LegacyFtpLocation of(JsonNode bundle) {
        List<String> ftp = new ArrayList<>();
        List<String> ftp2 = new ArrayList<>();
        for (JsonNode detail : bundle.path("interface").path("details")) {
            String label = detail.path("label").asText("");
            if (LABEL_FTP.equals(label)) {
                ftp.add(detail.path("value").asText(""));
            } else if (LABEL_FTP2.equals(label)) {
                ftp2.add(detail.path("value").asText(""));
            }
        }
        boolean ambiguous = ftp.size() > 1 || ftp2.size() > 1;
        String url = (ftp.isEmpty() ? "" : ftp.get(0).strip()) + (ftp2.isEmpty() ? "" : ftp2.get(0).strip());
        return parse(url, ambiguous);
    }

    /** Ontleedt een samengestelde legacy-bestemming (zie klasse). */
    public static LegacyFtpLocation parse(String url) {
        return parse(url == null ? "" : url.strip(), false);
    }

    private static LegacyFtpLocation parse(String url, boolean ambiguous) {
        if (url.isEmpty()) {
            return new LegacyFtpLocation(Protocol.NONE, null, null, null, "");
        }
        Matcher scheme = SCHEME.matcher(url);
        if (!scheme.find()) {
            return new LegacyFtpLocation(Protocol.UNKNOWN, null, null, null, "");
        }
        String name = scheme.group(1).toLowerCase(Locale.ROOT);
        Protocol protocol = switch (name) {
            case "sftp" -> Protocol.SFTP;
            case "ftp" -> Protocol.FTP;
            default -> Protocol.UNKNOWN;
        };
        String rest = url.substring(scheme.end());
        int authorityEnd = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = rest.substring(0, authorityEnd);
        String path = rest.substring(authorityEnd);
        int query = indexOfAny(path, '?', '#');
        String directory = query < 0 ? path : path.substring(0, query);
        if (ambiguous) {
            return new LegacyFtpLocation(protocol, name, null, null, directory);
        }
        HostAndPort hostAndPort = hostAndPort(authority);
        if (hostAndPort == null) {
            return new LegacyFtpLocation(protocol, name, null, null, directory);
        }
        return new LegacyFtpLocation(protocol, name, hostAndPort.host(), hostAndPort.port(), directory);
    }

    private record HostAndPort(String host, Integer port) {
    }

    /** {@code null} = geen geldige host (zie klasse). */
    private static HostAndPort hostAndPort(String authority) {
        if (authority.isEmpty() || authority.indexOf('@') >= 0) {
            return null;
        }
        String host;
        String port = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = authority.substring(0, close + 1);
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) {
                    return null;
                }
                port = after.substring(1);
            }
            if (!IPV6_LITERAL.matcher(host).matches()) {
                return null;
            }
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0 && authority.indexOf(':', colon + 1) >= 0) {
                return null;
            }
            host = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? null : authority.substring(colon + 1);
            if (host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
                return null;
            }
        }
        Integer portNumber = null;
        if (port != null) {
            if (!PORT.matcher(port).matches()) {
                return null;
            }
            int value = Integer.parseInt(port);
            if (value < 1 || value > MAX_PORT) {
                return null;
            }
            portNumber = value;
        }
        String normalized = HostNames.normalize(host);
        return normalized == null ? null : new HostAndPort(normalized, portNumber);
    }

    private static int indexOfAny(String text, char first, char second) {
        int a = text.indexOf(first);
        int b = text.indexOf(second);
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }
}
