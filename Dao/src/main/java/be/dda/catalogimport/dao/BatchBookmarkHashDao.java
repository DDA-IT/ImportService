package be.dda.catalogimport.dao;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * De vingerafdruk van de LINK-scope bookmarkwaarden waarmee één batch gedraaid heeft
 * ({@code import_batch.bookmark_values_hash}, changeset 006-6, beslissingslog 23/09 keuze 5,
 * sjabloon-materialisatie-design.md §7).
 *
 * <h2>Waarom dit bestaat</h2>
 * De ingevulde bookmarkwaarden bepalen mee wat er gelezen, gefilterd en gepubliceerd wordt. De
 * revisiehashes bevriezen de rest van de configuratie; zonder deze vingerafdruk is achteraf niet meer
 * te bewijzen met welke invulwaarden een levering gescreend is. Alleen LINK-scope waarden: de
 * DEFINITION-scope waarden liggen al vast in de revisie waarnaar de batch verwijst (A36).
 *
 * <h2>Waarom hier en niet via JPA</h2>
 * {@code bookmark_values_hash} is bewust <b>niet</b> op {@code ImportBatch} gemapt (changeset 006-6).
 * {@link #setBookmarkValuesHash} zet daarom exact die ene kolom, met dezelfde redenering als de
 * vijf-kolom-update van een bundelbeslissing ({@link PublicationBundleDao#decideMutation}): een
 * volledige {@code save()} van de entiteit zou élke gemapte kolom meeschrijven, uit een in-memory
 * beeld in plaats van uit de databank.
 *
 * <h2>Canonieke vorm</h2>
 * Per rij {@code naam ␟ waarde ␞}, gesorteerd op naam; SHA-256 over de UTF-8-bytes.
 * Dezelfde scheidingstekens als {@link PublicationBundleDao#computeContentHash}. Er wordt
 * <b>in Java</b> gesorteerd en niet met {@code order by}: de sorteervolgorde van de databank hangt van
 * haar collatie af (underscore telt in de ene wél en in de andere niet mee), en dan zou dezelfde
 * invulling in H2 en PostgreSQL een verschillende hash kunnen opleveren.
 */
@Repository
public class BatchBookmarkHashDao {

    /** Scheidt naam van waarde binnen één rij (unit separator, U+001F). */
    private static final char HASH_FIELD_SEPARATOR = '';
    /** Sluit één rij af (record separator, U+001E). */
    private static final char HASH_RECORD_SEPARATOR = '';

    private final JdbcTemplate jdbc;

    public BatchBookmarkHashDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * De SHA-256 over alle bookmarkwaarden van een koppeling zoals ze op dit moment in de databank
     * staan.
     * <p>
     * <b>{@code null} wanneer de koppeling geen enkele bookmarkwaarde heeft</b> — "geen
     * bookmarkwaarden van toepassing", nooit stil de hash van een lege reeks (§7). Een koppeling
     * <i>met</i> één uitdrukkelijk lege waarde ({@code value_text = ''}) krijgt dus wél een hash: dat
     * is een ingevulde toestand, geen afwezigheid (R-BMK-03).
     * <p>
     * Rijen waarvan de naam in de actieve revisie niet meer gedeclareerd is (wezen) tellen hier
     * <b>mee</b>: deze vingerafdruk beschrijft de toestand van de koppeling op dat moment, en een wees
     * weglaten zou net het bewijsmateriaal verbergen dat hij achterliet.
     */
    public byte[] computeLinkBookmarkValuesHash(long importLinkId) {
        List<String[]> rows = new ArrayList<>();
        jdbc.query("select bookmark_name, value_text from import_link_bookmark_value where import_link_id = ?",
                rs -> {
                    rows.add(new String[] {rs.getString(1), rs.getString(2)});
                }, importLinkId);
        if (rows.isEmpty()) {
            return null;
        }
        rows.sort(Comparator.comparing(row -> row[0]));
        MessageDigest digest = newSha256();
        StringBuilder canonical = new StringBuilder(rows.size() * 64);
        for (String[] row : rows) {
            canonical.append(row[0]).append(HASH_FIELD_SEPARATOR)
                    .append(row[1] == null ? "" : row[1]).append(HASH_RECORD_SEPARATOR);
        }
        digest.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    /**
     * Zet uitsluitend {@code bookmark_values_hash} op één batch.
     * <p>
     * Er wordt nooit een {@code null}-hash geschreven: de kolom staat na het invoegen van de batch al
     * op {@code null} en "geen bookmarkwaarden" is precies die toestand. Een {@code null} meegeven zou
     * bovendien een ongetypeerde bindparameter op een {@code bytea}-kolom opleveren.
     *
     * @return het aantal bijgewerkte rijen: 1 bij succes, 0 wanneer de batch niet (meer) bestaat
     * @throws IllegalArgumentException wanneer {@code hash} {@code null} is
     */
    public int setBookmarkValuesHash(long batchId, byte[] hash) {
        if (hash == null) {
            throw new IllegalArgumentException("bookmark_values_hash must not be set to null explicitly");
        }
        return jdbc.update("update import_batch set bookmark_values_hash = ? where id = ?", hash, batchId);
    }

    /** Leest de vingerafdruk terug; {@code null} wanneer er geen gezet is. */
    public byte[] findBookmarkValuesHash(long batchId) {
        return jdbc.queryForObject("select bookmark_values_hash from import_batch where id = ?",
                byte[].class, batchId);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is verplicht in elke Java-implementatie; hier komen betekent een kapotte JVM.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
