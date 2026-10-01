package be.dda.catalogimport.dao;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * De controlewaarde per sleutel, {@code secret_key_check} (changeset 013-3,
 * {@code docs/design/credentials-sleutelbeheer-design.md} par. 5.3, A7).
 *
 * <h2>Waarom JDBC en geen JPA-entiteit</h2>
 * De primaire sleutel is het sleutel-ID (toegewezen, niet gegenereerd). Spring Data's {@code save} doet voor
 * zo'n entiteit een {@code merge}: vindt die bij een gelijktijdige opstart al een rij, dan wordt die rij
 * <b>overschreven</b> — precies de fout (ander materiaal onder dezelfde ID) die deze tabel moet detecteren. Hier
 * is schrijven daarom uitsluitend "invoegen als ze nog niet bestaat", en een bestaande rij wordt nooit
 * bijgewerkt of verwijderd. Precedent voor een tabel zonder entiteit: {@link SourceStateDao}.
 * <p>
 * De controlewaarde is ciphertext van een vaste, bekende tekst — geen secret — maar wordt toch nooit gelogd.
 */
@Repository
public class SecretKeyCheckDao {

    private static final String INSERT_IF_ABSENT = "insert into secret_key_check (key_id, check_value, created_at) "
            + "select cast(? as varchar(32)), cast(? as varchar), cast(? as timestamp with time zone) "
            + "where not exists (select 1 from secret_key_check where key_id = ?)";

    private final JdbcTemplate jdbc;

    public SecretKeyCheckDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** De opgeslagen controlewaarde van deze sleutel, of leeg wanneer er nog geen is. */
    public Optional<String> findCheckValue(String keyId) {
        List<String> values = jdbc.queryForList("select check_value from secret_key_check where key_id = ?",
                String.class, keyId);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.get(0));
    }

    /**
     * Legt de controlewaarde vast, tenzij er voor deze sleutel al een rij bestaat. Een bestaande rij wordt nooit
     * overschreven. Twee gelijktijdig opstartende instanties: de {@code not exists} vangt het gewone geval, de
     * primaire sleutel de echte race ({@link DuplicateKeyException}); de verliezer krijgt {@code false} en moet
     * de rij van de winnaar opnieuw lezen en controleren.
     *
     * @return {@code true} als deze aanroep de rij aanmaakte, {@code false} als ze al bestond
     */
    public boolean insertIfAbsent(String keyId, String checkValue, Instant at) {
        try {
            return jdbc.update(INSERT_IF_ABSENT, keyId, checkValue, OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                    keyId) == 1;
        } catch (DuplicateKeyException concurrentInsert) {
            return false;
        }
    }
}
