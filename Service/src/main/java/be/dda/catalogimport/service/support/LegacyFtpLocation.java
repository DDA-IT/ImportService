package be.dda.catalogimport.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * De legacy-bestemming van een interface ({@code FTP} + {@code FTP2}), zoals legacy ze samenstelt:
 * {@code Trim(FTP) & Trim(FTP2)} ({@code Prg_1232} r.298-300), met het protocol zoals legacy het kiest
 * ({@code Prg_1232} r.454-499 en r.622-624). Slice S4 van {@code docs/design/legacy-config-bundle-design.md} (par. 2
 * "Credential bij ontvangst", par. 5 rij {@code FTP}/..., aanname A7, verfijnd 2026-10-22); beslissingslog 2026-10-22
 * "LF: uitwerking FTP-/API-wachtwoorden bewaren (ontwerp)". Pure klasse: geen Spring, geen database, geen DNS.
 *
 * <h2>Regels ({@link #of}; C = {@code Trim(FTP) & Trim(FTP2)}, M = eerste teken van {@code FTP/LOCAL/API})</h2>
 * <ul>
 *   <li><b>Geen bestemming</b> (C leeg): {@link Protocol#NONE}.</li>
 *   <li><b>Niet eenduidig:</b> {@code FTP/LOCAL/API} of {@code SFTP} komt meer dan eens voor →
 *       {@link Protocol#UNKNOWN}. Een dubbel {@code FTP}- of {@code FTP2}-label houdt het protocol, maar heeft geen
 *       host.</li>
 *   <li><b>M = {@code L}</b> (zoals Magic {@code Left(...,1)='L'}: het ruwe eerste teken, hoofdlettergevoelig):
 *       {@link Protocol#LOCAL}, een lokaal bestand; geen host.</li>
 *   <li><b>M = {@code A}</b>: met {@code http://} of {@code https://} {@link Protocol#API} (host = de authority van C);
 *       elk ander schema of geen schema {@link Protocol#UNKNOWN}.</li>
 *   <li><b>Anders, met schema</b> (het schema wint van het label {@code SFTP}): {@code sftp://} = {@link Protocol#SFTP};
 *       {@code ftp://}, {@code ftps://}, {@code ftpes://} = {@link Protocol#FTP}; elk ander schema
 *       {@link Protocol#UNKNOWN}.</li>
 *   <li><b>Anders, zonder schema:</b> is de hint {@code SFTP} gezet (getrimd niet leeg en niet {@code N}, hoofdletter-
 *       ongevoelig), dan {@link Protocol#UNKNOWN} (V-b: wat ProDisWebbase dan doet, is niet gekend). Zonder hint
 *       {@link Protocol#FTP}, zoals Magic {@code 'ftp://'&Trim(FTP)} (r.624): de host is de authority van
 *       {@code "ftp://" + Trim(FTP)}, en die moet gelijk zijn aan de authority van {@code "ftp://" + C} (verandert
 *       {@code FTP2} de authority, dan is er geen host).</li>
 *   <li><b>Host:</b> de authority (na {@code ://}, tot {@code /}, {@code ?}, {@code #} of het einde) zonder poort,
 *       genormaliseerd met {@link HostNames} (een IPv4-adres of IPv6-literal mag). Ongeldig ({@code host = null}):
 *       lege authority, een {@code @} (userinfo hoort er volgens contract par. 2.1 nooit in te staan), een poort die
 *       geen getal 1-65535 is, meer dan één {@code :} buiten een IPv6-literal, haken die geen IPv6-literal vormen (bv.
 *       een legacy-patroon {@code [YYYY]}), of wat {@link HostNames} weigert.</li>
 *   <li><b>Poort:</b> {@code null} = niet opgegeven (de standaard van het protocol geldt).</li>
 *   <li><b>Map:</b> het pad na de authority (tot {@code ?}/{@code #}), of {@code ""}.</li>
 * </ul>
 * {@link #parse(String)} leest enkel een URL, zonder labels: het schema bepaalt het protocol (zelfde schematabel als
 * hierboven, zonder M), geen schema = {@link Protocol#UNKNOWN}.
 * <p>
 * Deze klasse ziet nooit een secret: de bundel in {@code bundle_json} heeft er geen.
 *
 * @param protocol  zie {@link Protocol}
 * @param scheme    het URL-schema in kleine letters, of {@code null} zonder schema
 * @param host      de genormaliseerde host, of {@code null} als er geen geldige host is
 * @param port      de expliciete poort, of {@code null}
 * @param directory het pad uit de URL, of {@code ""}
 */
public record LegacyFtpLocation(Protocol protocol, String scheme, String host, Integer port, String directory) {

    /** Het protocol van de bestemming (zie klasse). */
    public enum Protocol {
        /** {@code sftp://}: credential {@code SFTP_PASSWORD}. */
        SFTP,
        /** {@code ftp://}/{@code ftps://}/{@code ftpes://} of geen schema: credential {@code FTP_PASSWORD} (bewaring). */
        FTP,
        /** {@code FTP/LOCAL/API} = {@code A} met {@code http(s)://}: credential {@code API_PASSWORD} (bewaring). */
        API,
        /** {@code FTP/LOCAL/API} = {@code L}: een lokaal bestand, geen credential. */
        LOCAL,
        /** Tegenstrijdig, dubbel of onbekend: protocol niet af te leiden, geen credential. */
        UNKNOWN,
        /** Geen bestemming: {@code FTP} en {@code FTP2} leeg of afwezig. */
        NONE
    }

    static final String LABEL_FTP = "FTP";
    static final String LABEL_FTP2 = "FTP2";
    /** Legacy-keuze FTP/lokaal/API ({@code Prg_1232} r.440-499): enkel het eerste teken telt. */
    static final String LABEL_MODE = "FTP/LOCAL/API";
    /** Hint van het INDET-label {@code SFTP}; enkel gelezen als C geen schema heeft. */
    static final String LABEL_SFTP = "SFTP";
    private static final char MODE_API = 'A';
    private static final char MODE_LOCAL = 'L';
    private static final int MAX_PORT = 65535;
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("\\[[0-9A-Fa-f:.]+]");
    private static final Set<String> FTP_SCHEMES = Set.of("ftp", "ftps", "ftpes");
    private static final Set<String> API_SCHEMES = Set.of("http", "https");
    private static final String FTP_PREFIX = "ftp://";

    /** Is er een geldige host om een credential aan te binden? */
    public boolean hasValidHost() {
        return host != null;
    }

    /**
     * Leest {@code FTP}, {@code FTP2}, {@code FTP/LOCAL/API} en {@code SFTP} uit {@code interface.details} van een
     * (gevalideerde) bundel (zie klasse).
     *
     * @param bundle de bundel (de bewaarde boom of de ontvangen boom; het secret wordt niet gelezen)
     */
    public static LegacyFtpLocation of(JsonNode bundle) {
        List<String> ftp = new ArrayList<>();
        List<String> ftp2 = new ArrayList<>();
        List<String> mode = new ArrayList<>();
        List<String> sftp = new ArrayList<>();
        for (JsonNode detail : bundle.path("interface").path("details")) {
            String label = detail.path("label").asText("");
            String value = detail.path("value").asText("");
            switch (label) {
                case LABEL_FTP -> ftp.add(value);
                case LABEL_FTP2 -> ftp2.add(value);
                case LABEL_MODE -> mode.add(value);
                case LABEL_SFTP -> sftp.add(value);
                default -> {
                    // andere labels bepalen de bestemming niet
                }
            }
        }
        boolean ambiguous = ftp.size() > 1 || ftp2.size() > 1;
        String ftpPart = ftp.isEmpty() ? "" : ftp.get(0).strip();
        String url = ftpPart + (ftp2.isEmpty() ? "" : ftp2.get(0).strip());
        if (url.isEmpty()) {
            return none();
        }
        if (mode.size() > 1 || sftp.size() > 1) {
            return unknown();
        }
        char m = mode.isEmpty() || mode.get(0).isEmpty() ? 0 : mode.get(0).charAt(0);
        String name = schemeOf(url);
        if (m == MODE_LOCAL) {
            return new LegacyFtpLocation(Protocol.LOCAL, name, null, null, "");
        }
        if (m == MODE_API) {
            return name != null && API_SCHEMES.contains(name) ? located(Protocol.API, url, ambiguous) : unknown();
        }
        if (name != null) {
            return located(protocolOf(name), url, ambiguous);
        }
        boolean sftpHint = !sftp.isEmpty() && !sftp.get(0).isBlank() && !"N".equalsIgnoreCase(sftp.get(0).strip());
        if (sftpHint) {
            return unknown();
        }
        // Geen schema, geen hint: legacy bouwt 'ftp://'&Trim(FTP) (r.624, FTP2 genegeerd). De host komt dus uit FTP
        // alleen, en telt enkel als FTP+FTP2 dezelfde authority heeft.
        String[] whole = authorityAndPath(FTP_PREFIX + url);
        String[] ftpOnly = authorityAndPath(FTP_PREFIX + ftpPart);
        HostAndPort hostAndPort = ambiguous || !whole[0].equals(ftpOnly[0]) ? null : hostAndPort(whole[0]);
        return new LegacyFtpLocation(Protocol.FTP, null, hostAndPort == null ? null : hostAndPort.host(),
                hostAndPort == null ? null : hostAndPort.port(), directoryOf(whole[1]));
    }

    /** Ontleedt een samengestelde legacy-bestemming zonder labels (zie klasse). */
    public static LegacyFtpLocation parse(String url) {
        String value = url == null ? "" : url.strip();
        if (value.isEmpty()) {
            return none();
        }
        String name = schemeOf(value);
        return name == null ? unknown() : located(protocolOf(name), value, false);
    }

    private static LegacyFtpLocation none() {
        return new LegacyFtpLocation(Protocol.NONE, null, null, null, "");
    }

    private static LegacyFtpLocation unknown() {
        return new LegacyFtpLocation(Protocol.UNKNOWN, null, null, null, "");
    }

    /** Het schema in kleine letters, of {@code null} zonder schema. */
    private static String schemeOf(String url) {
        Matcher scheme = SCHEME.matcher(url);
        return scheme.find() ? scheme.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** Het protocol volgens het schema alleen (M is niet {@code A} of {@code L}). */
    private static Protocol protocolOf(String scheme) {
        if ("sftp".equals(scheme)) {
            return Protocol.SFTP;
        }
        return FTP_SCHEMES.contains(scheme) ? Protocol.FTP : Protocol.UNKNOWN;
    }

    /** Een URL met schema: host, poort en map uit de authority en het pad. */
    private static LegacyFtpLocation located(Protocol protocol, String url, boolean ambiguous) {
        String name = schemeOf(url);
        String[] parts = authorityAndPath(url);
        String directory = directoryOf(parts[1]);
        HostAndPort hostAndPort = ambiguous ? null : hostAndPort(parts[0]);
        if (hostAndPort == null) {
            return new LegacyFtpLocation(protocol, name, null, null, directory);
        }
        return new LegacyFtpLocation(protocol, name, hostAndPort.host(), hostAndPort.port(), directory);
    }

    /** {@code [authority, rest]} van een URL met schema; de rest is het pad met eventuele query/fragment. */
    private static String[] authorityAndPath(String url) {
        Matcher scheme = SCHEME.matcher(url);
        String rest = scheme.find() ? url.substring(scheme.end()) : url;
        int authorityEnd = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        return new String[] {rest.substring(0, authorityEnd), rest.substring(authorityEnd)};
    }

    private static String directoryOf(String path) {
        int query = indexOfAny(path, '?', '#');
        return query < 0 ? path : path.substring(0, query);
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
