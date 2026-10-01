package be.dda.catalogimport.service;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Een door {@link FetchHostPolicy} goedgekeurd verbindingsdoel (L4b, bouwstap K-4a): de genormaliseerde host staat in
 * {@code catalogimport.fetch.allowed-hosts}, de naam is <b>één keer</b> geresolved en het IP-adres is geen verboden
 * adres. Er wordt altijd op {@link #socketAddress()} verbonden, dus op dat IP-adres: de SFTP-library resolvet de naam
 * nooit een tweede keer (bescherming tegen DNS-rebinding).
 * <p>
 * De constructor is package-private: enkel {@link FetchHostPolicy} maakt een doel aan, zodat {@link SftpConnector}
 * structureel nergens anders heen kan verbinden.
 */
public final class FetchTarget {

    private final String host;
    private final int port;
    /** Een IP-adres zonder bijhorende naam ({@code InetAddress.getByAddress}): {@code getHostString()} is de literal. */
    private final InetAddress address;

    FetchTarget(String host, int port, InetAddress address) {
        this.host = host;
        this.port = port;
        this.address = address;
    }

    /** De genormaliseerde host zoals in het profiel en de allowlist (voor meldingen en events). */
    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** Het IP-adres waarop verbonden wordt. */
    public InetAddress address() {
        return address;
    }

    /** Het adres waarop de library verbindt: IP-literal + poort, zonder hostnaam (dus geen nieuwe DNS-opzoeking). */
    InetSocketAddress socketAddress() {
        return new InetSocketAddress(address, port);
    }

    @Override
    public String toString() {
        return "FetchTarget[port=" + port + "]";
    }
}
