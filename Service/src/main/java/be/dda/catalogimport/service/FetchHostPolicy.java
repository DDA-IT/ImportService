package be.dda.catalogimport.service;

import be.dda.catalogimport.service.support.HostNames;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Welke hosts CatalogImport zelf mag contacteren voor hostsleutelscan, verbindingstest en (K-4b) ophalen: bouwstap
 * K-4a, beslissingslog 2026-09-29 L4b; {@code docs/design/leveringsconfiguratie-design.md} par. 5 en 11 (Ontdekking 3:
 * zonder deze begrenzing zijn scan en test een SSRF- en poortscanmiddel).
 *
 * <h2>Configuratie</h2>
 * <ul>
 *   <li>{@code catalogimport.fetch.allowed-hosts} (env-var {@code CATALOGIMPORT_FETCH_ALLOWED_HOSTS}): <b>exacte</b>
 *       hostnamen, komma-gescheiden in één string (zelfde vorm als {@code catalogimport.secrets.keys}), elk
 *       genormaliseerd met {@link HostNames} (kleine letters, geen punt achteraan). Geen wildcards, geen domeinen,
 *       geen CIDR. Beheerd door infra (design par. 12). Een YAML-lijst wordt niet gelezen: de functie blijft dan uit.</li>
 *   <li>{@code catalogimport.fetch.allow-loopback} (default {@code false}): laat loopback-adressen ({@code 127.0.0.0/8},
 *       {@code ::1}) toe. <b>Enkel voor ontwikkeling en tests</b> (embedded SFTP-server op 127.0.0.1); een WARN bij
 *       opstart maakt het zichtbaar.</li>
 * </ul>
 * <b>Uit of aan (fail-closed).</b> Niet gezet of leeg = de functie bestaat niet: {@link #requireConfigured()} geeft
 * 404 {@link FetchOutcomeCodes#FETCH_NOT_CONFIGURED}. Gezet maar ongeldig (lege ingang, ongeldige hostnaam of wildcard)
 * = {@link IllegalStateException} bij opstart (patroon {@code LocalSourceDirectory}/{@code SecretsService}).
 *
 * <h2>Resolutie (L4b)</h2>
 * {@link #resolve} controleert de allowlist, resolvet de naam <b>één keer</b> en geeft een {@link FetchTarget} met het
 * IP-adres waarop verbonden wordt. Geweigerd ({@link FetchOutcomeCodes#FETCH_HOST_NOT_ALLOWED}) wordt elk resultaat
 * waarin <b>een</b> adres loopback (tenzij toegelaten), link-local ({@code 169.254.0.0/16} met cloud-metadata,
 * {@code fe80::/10}), multicast, "any" ({@code 0.0.0.0}, {@code ::}), {@code 0.0.0.0/8} of de IPv4-broadcast is. Private
 * adressen ({@code 10/8}, {@code 192.168/16}, ...) zijn niet verboden: een leverancier kan via VPN bereikbaar zijn, en
 * de host moet sowieso uitdrukkelijk in de allowlist staan. Bij meerdere adressen wordt het eerste gebruikt (geen
 * herhaalpoging op een volgend adres).
 */
@Component
public class FetchHostPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(FetchHostPolicy.class);

    /** Eén DNS-opzoeking; vervangbaar in tests (package-private). */
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** {@code null} = niet geconfigureerd. */
    private final Set<String> allowedHosts;
    private final boolean allowLoopback;
    private final Resolver resolver;

    @Autowired
    public FetchHostPolicy(@Value("${catalogimport.fetch.allowed-hosts:}") String allowedHosts,
                           @Value("${catalogimport.fetch.allow-loopback:false}") boolean allowLoopback) {
        this(allowedHosts, allowLoopback, InetAddress::getAllByName);
    }

    FetchHostPolicy(String allowedHosts, boolean allowLoopback, Resolver resolver) {
        this.allowLoopback = allowLoopback;
        this.resolver = resolver;
        if (allowedHosts == null || allowedHosts.isBlank()) {
            this.allowedHosts = null;
            LOG.info("catalogimport.fetch.allowed-hosts is not set; host key scan, connection tests and fetching "
                    + "from external sources are disabled");
            return;
        }
        Set<String> hosts = new LinkedHashSet<>();
        String[] entries = allowedHosts.split(",", -1);
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].isBlank()) {
                throw new IllegalStateException("catalogimport.fetch.allowed-hosts: entry " + (i + 1)
                        + " is empty (use a comma-separated list of exact host names)");
            }
            String host = HostNames.normalize(entries[i]);
            if (host == null) {
                throw new IllegalStateException("catalogimport.fetch.allowed-hosts: entry " + (i + 1)
                        + " is not a valid exact host name (only ASCII letters, digits and . - _ : [ ]; no wildcards)");
            }
            hosts.add(host);
        }
        this.allowedHosts = Set.copyOf(hosts);
        LOG.info("Fetch allowlist loaded: {} host(s)", this.allowedHosts.size());
        if (allowLoopback) {
            LOG.warn("catalogimport.fetch.allow-loopback=true: loopback addresses may be contacted. Only for "
                    + "development and tests, never in production");
        }
    }

    /** {@code false} wanneer de allowlist niet gezet is: scan, test en ophalen bestaan dan niet. */
    public boolean configured() {
        return allowedHosts != null;
    }

    /** @throws NotFoundException 404 {@link FetchOutcomeCodes#FETCH_NOT_CONFIGURED} */
    public void requireConfigured() {
        if (!configured()) {
            throw new NotFoundException(FetchOutcomeCodes.FETCH_NOT_CONFIGURED, "Contacting external sources (host key "
                    + "scan, connection test, fetch) is not configured on this environment");
        }
    }

    /** {@code true} als de (ruwe of genormaliseerde) host exact in de allowlist staat. Geen DNS. */
    public boolean isAllowed(String host) {
        requireConfigured();
        String normalized = HostNames.normalize(host);
        return normalized != null && allowedHosts.contains(normalized);
    }

    /**
     * Allowlist, één DNS-opzoeking en adrescontrole.
     *
     * @throws NotFoundException     404 {@link FetchOutcomeCodes#FETCH_NOT_CONFIGURED}
     * @throws FetchFailureException {@link FetchOutcomeCodes#FETCH_HOST_NOT_ALLOWED} (niet in de lijst of verboden
     *                               adres) of {@link FetchOutcomeCodes#CONNECTION_FAILED} (naam niet te resolven)
     */
    public FetchTarget resolve(String host, int port) {
        requireConfigured();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port is out of range");
        }
        String normalized = HostNames.normalize(host);
        if (normalized == null || !allowedHosts.contains(normalized)) {
            throw notAllowed("The host is not in catalogimport.fetch.allowed-hosts of this environment");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(normalized);
        } catch (UnknownHostException | SecurityException unresolvable) {
            throw new FetchFailureException(FetchOutcomeCodes.CONNECTION_FAILED, "The host name could not be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new FetchFailureException(FetchOutcomeCodes.CONNECTION_FAILED, "The host name could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (forbidden(address)) {
                throw notAllowed("The host resolves to a loopback, link-local, multicast or unspecified address, "
                        + "which is never contacted");
            }
        }
        InetAddress chosen;
        try {
            // Zonder naam: de library ziet enkel de literal en resolvet niets meer (DNS-rebinding, L4b).
            chosen = InetAddress.getByAddress(addresses[0].getAddress());
        } catch (UnknownHostException impossible) {
            throw new FetchFailureException(FetchOutcomeCodes.CONNECTION_FAILED, "The host name could not be resolved");
        }
        return new FetchTarget(normalized, port, chosen);
    }

    boolean forbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address.isLoopbackAddress()) {
            return !allowLoopback;
        }
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            boolean thisNetwork = bytes[0] == 0;
            boolean broadcast = (bytes[0] & 0xff) == 255 && (bytes[1] & 0xff) == 255 && (bytes[2] & 0xff) == 255
                    && (bytes[3] & 0xff) == 255;
            return thisNetwork || broadcast;
        }
        return false;
    }

    private static FetchFailureException notAllowed(String message) {
        return new FetchFailureException(FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED, message);
    }

    @Override
    public String toString() {
        return "FetchHostPolicy[configured=" + configured() + ", allowLoopback=" + allowLoopback + "]";
    }
}
