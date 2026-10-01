package be.dda.catalogimport.service.support;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normaliseert een hostnaam tot de vorm waarin hij aan een credential gebonden wordt ({@code external_credential.bound_host},
 * L4a; K-2a-nazorg (b) in {@code docs/decisions.md} 2026-09-29). Pure klasse: geen Spring, geen database, geen DNS.
 * <p>
 * <b>Waarom.</b> De host-binding ({@code uk_external_credential_host_binding} en later de samengestelde FK vanuit de
 * profielversie, LC-1) vergelijkt strings exact. Een host die enkel in hoofdletters, witruimte aan de rand of een punt
 * achteraan verschilt, is dezelfde host en moet dus exact dezelfde tekst opleveren. De databasecheck
 * {@code ck_external_credential_bound_host_normalized} weigert elke niet-genormaliseerde vorm; normaliseren gebeurt hier.
 * Een latere profielversie (LC-2) hoort dezelfde normalisatie te gebruiken, anders past de FK niet.
 * <p>
 * <b>Stappen:</b> trim aan de buitenkant, kleine letters ({@link Locale#ROOT}), één punt achteraan weg (de
 * volledig gekwalificeerde vorm {@code sftp.example.com.}).
 * <p>
 * <b>Ongeldig</b> (resultaat {@code null}, de aanroeper geeft zijn eigen foutcode): leeg na normalisatie, langer dan
 * {@value #MAX_LENGTH} tekens, of een teken buiten ASCII-letters, cijfers en {@code . - _ : [ ]} (de laatste drie voor
 * IPv6-literals). Daarmee vallen ook witruimte binnenin, een schema ({@code sftp://}), een pad, een gebruikersnaam
 * ({@code user@}) en niet-ASCII-tekens weg: een geïnternationaliseerde naam hoort in zijn punycodevorm
 * ({@code xn--...}). Die beperking tot ASCII houdt de Java-kleine-letters en de PostgreSQL-{@code lower()} van de
 * databasecheck gegarandeerd gelijk. Er wordt nooit iets stil weggelaten buiten de drie stappen hierboven.
 */
public final class HostNames {

    /** Lengte van {@code external_credential.bound_host} ({@code varchar(255)}). */
    public static final int MAX_LENGTH = 255;

    /**
     * Getoetst <b>vóór</b> de kleine letters: zo kan een niet-ASCII-teken (bv. het Kelvin-teken, dat in Java tot
     * {@code k} verkleint) nooit stil in een ASCII-letter veranderen.
     */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:\\[\\]-]+");

    private HostNames() {
    }

    /**
     * @return de genormaliseerde host, of {@code null} als de invoer geen geldige host is
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String host = raw.strip();
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > MAX_LENGTH || host.endsWith(".") || !ALLOWED.matcher(host).matches()) {
            return null;
        }
        return host.toLowerCase(Locale.ROOT);
    }
}
