package be.dda.catalogimport.dao;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Het schrijfpad naar {@code catalog_source_state}, de laatst aanvaarde bronstaat per
 * aanbiedingsidentiteit binnen een importkoppeling (design par. 3). Enkel de
 * {@code accept-baseline}-actie schrijft hier (beslissingslog 18/09): de screening zelf nooit, en een
 * ongewijzigde regel raakt de bronstaat nooit aan — ook {@code updated_at} niet.
 * <p>
 * <b>JDBC-only.</b> De tabel heeft bewust geen entiteit: nieuwe rijen komen set-based rechtstreeks uit
 * {@code import_candidate_stage} ({@code insert ... select}), de binaire hashkolommen verlaten de
 * database daarbij niet. Enkel de gewijzigde rijen ({@code CHANGED}) worden per rij bijgewerkt in een
 * JDBC-batch: dat zijn er per definitie een fractie van de levering.
 * <p>
 * <b>Hervatbaar.</b> Elke schrijfoperatie is idempotent: een insert slaat identiteiten over die al
 * bestaan, een update raakt enkel rijen waarvan de gecombineerde vingerafdruk nog afwijkt. Een
 * onderbroken of herhaalde acceptatie kan dus vanaf het begin herstarten zonder dubbele of foutieve
 * rijen, en zonder {@code updated_at} te verschuiven van wat al klaar was.
 * <p>
 * <b>Transactiegrens.</b> Deze DAO opent zelf geen transactie; de baseline-service bepaalt de
 * chunkgrens (dezelfde {@code catalogimport.screening.mutation-chunk-size} als de mutatiegeneratie).
 */
@Repository
public class SourceStateDao {

    /** Zelfde standaard als {@link MutationDao#DEFAULT_CHUNK_SIZE}. */
    public static final int DEFAULT_CHUNK_SIZE = MutationDao.DEFAULT_CHUNK_SIZE;

    /**
     * Nieuwe aanbiedingen: alles wat {@code NEW} geclassificeerd is, tenzij de identiteit binnen deze
     * koppeling al bestaat (herhaalde of hervatte acceptatie). {@code identity_profile_kind} komt uit
     * de definitierevisie van de batch en wordt als parameter meegegeven, nooit hardcoded.
     */
    private static final String INSERT_NEW_FROM_STAGE = "insert into catalog_source_state ("
            + "import_link_id, identity_hash, identity_supplier, identity_supplier_group, "
            + "identity_supplier_reference, identity_discount_code, identity_discount_state, "
            + "identity_profile_kind, article_fingerprint, price_fingerprint, reference_fingerprint, "
            + "combined_fingerprint, "
            + "base_price, base_price_currency, state_origin, last_change_delivery_id, "
            + "last_change_batch_id, active, accepted_by, accepted_at, created_at, updated_at) "
            + "select cast(? as bigint), stage.identity_hash, stage.identity_supplier, "
            + "stage.identity_supplier_group, stage.identity_supplier_reference, "
            + "stage.identity_discount_code, stage.identity_discount_state, cast(? as varchar(40)), "
            + "stage.article_fingerprint, stage.price_fingerprint, stage.reference_fingerprint, "
            + "stage.combined_fingerprint, "
            + "stage.base_price, stage.base_price_currency, cast(? as varchar(30)), cast(? as bigint), "
            + "cast(? as bigint), true, cast(? as varchar(100)), cast(? as timestamp with time zone), "
            + "cast(? as timestamp with time zone), cast(? as timestamp with time zone) "
            + "from import_candidate_stage stage "
            + "where stage.batch_id = ? and stage.classification = 'NEW' "
            + "  and stage.row_number > ? and stage.row_number <= ? "
            + "  and not exists (select 1 from catalog_source_state existing "
            + "      where existing.import_link_id = ? and existing.identity_hash = stage.identity_hash)";

    private static final String SELECT_CHANGED_FROM_STAGE = "select identity_hash, article_fingerprint, "
            + "price_fingerprint, reference_fingerprint, combined_fingerprint, base_price, "
            + "base_price_currency "
            + "from import_candidate_stage where batch_id = ? and classification = 'CHANGED' "
            + "and row_number > ? and row_number <= ? order by row_number";

    /**
     * Enkel rijen waarvan de gecombineerde vingerafdruk nog afwijkt: dat maakt de update idempotent en
     * laat {@code updated_at} ongemoeid voor wat een eerdere (onderbroken) poging al bijwerkte.
     */
    private static final String UPDATE_CHANGED = "update catalog_source_state set article_fingerprint = ?, "
            + "price_fingerprint = ?, reference_fingerprint = ?, combined_fingerprint = ?, base_price = ?, "
            + "base_price_currency = ?, "
            + "state_origin = ?, last_change_delivery_id = ?, last_change_batch_id = ?, accepted_by = ?, "
            + "accepted_at = ?, updated_at = ? "
            + "where import_link_id = ? and identity_hash = ? and combined_fingerprint <> ?";

    /**
     * Rijen van deze batch waarvan de bronstaat sinds de screening veranderd is. Een {@code NEW}-regel
     * waarvan de identiteit al bestaat met een andere vingerafdruk, of een {@code CHANGED}-regel
     * waarvan de bronstaat niet meer de "voor"-toestand van de mutatie is (en ook nog niet de
     * "na"-toestand), is gescreend tegen een verouderde bronstaat. Zo'n batch aanvaarden zou
     * stilzwijgend overschrijven of overslaan. Rijen die door een onderbroken eigen poging al
     * bijgewerkt zijn (bronstaat = kandidaat) tellen niet mee.
     */
    private static final String COUNT_STALE_ROWS = "select count(*) from import_candidate_stage stage "
            + "left join catalog_source_state state "
            + "  on state.import_link_id = ? and state.identity_hash = stage.identity_hash "
            + "left join import_mutation mutation "
            + "  on mutation.batch_id = stage.batch_id and mutation.source_row_number = stage.row_number "
            + " and mutation.action_type <> 'IMPORT_MARKER' "
            + "where stage.batch_id = ? and stage.classification in ('NEW', 'CHANGED') "
            + "  and ((state.id is null and stage.classification = 'CHANGED') "
            + "    or (state.id is not null and state.combined_fingerprint <> stage.combined_fingerprint "
            + "        and (mutation.before_combined_fingerprint is null "
            + "             or state.combined_fingerprint <> mutation.before_combined_fingerprint)))";

    private record ChangedRow(byte[] identityHash, byte[] articleFingerprint, byte[] priceFingerprint,
                              byte[] referenceFingerprint, byte[] combinedFingerprint,
                              BigDecimal basePrice, String basePriceCurrency) {
    }

    /**
     * Wat elke geschreven bronstaatrij van deze acceptatie meekrijgt. {@code identityProfileKind} is de
     * naam van het identiteitsprofiel van de definitierevisie waaronder de batch gescreend werd.
     */
    public record AcceptanceContext(long importLinkId, long batchId, long deliveryId,
                                    String identityProfileKind, String stateOrigin, String acceptedBy,
                                    Instant acceptedAt, Instant writtenAt) {
    }

    private final JdbcTemplate jdbc;
    private final int chunkSize;

    public SourceStateDao(JdbcTemplate jdbc,
                          @Value("${catalogimport.screening.mutation-chunk-size:" + DEFAULT_CHUNK_SIZE + "}")
                          int chunkSize) {
        this.jdbc = jdbc;
        this.chunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
    }

    /**
     * Het hoogste regelnummer van de volgende chunk regels ná {@code afterRowNumber} die de bronstaat
     * moeten raken ({@code NEW} of {@code CHANGED}), of {@code null} als er geen meer zijn.
     * {@code UNCHANGED} telt niet mee: die regels raken de bronstaat nooit aan.
     */
    public Long nextChunkBoundary(long batchId, long afterRowNumber) {
        List<Long> boundary = jdbc.queryForList("select max(chunk.row_number) from ("
                + "select row_number from import_candidate_stage where batch_id = ? and row_number > ? "
                + "and classification in ('NEW', 'CHANGED') order by row_number limit ?) chunk",
                Long.class, batchId, afterRowNumber, chunkSize);
        return boundary.isEmpty() ? null : boundary.get(0);
    }

    /** Zie {@link #COUNT_STALE_ROWS}. */
    public long countRowsStaleSinceScreening(long batchId, long importLinkId) {
        Long count = jdbc.queryForObject(COUNT_STALE_ROWS, Long.class, importLinkId, batchId);
        return count == null ? 0L : count;
    }

    /**
     * Schrijft de {@code NEW}-regels van één chunk als nieuwe bronstaatrijen.
     *
     * @return het aantal werkelijk geschreven rijen (bij een hervatting minder dan het aantal regels)
     */
    public int insertNewFromStage(AcceptanceContext context, long fromExclusive, long toInclusive) {
        return jdbc.update(INSERT_NEW_FROM_STAGE, statement -> {
            statement.setLong(1, context.importLinkId());
            statement.setString(2, context.identityProfileKind());
            statement.setString(3, context.stateOrigin());
            statement.setLong(4, context.deliveryId());
            statement.setLong(5, context.batchId());
            statement.setString(6, context.acceptedBy());
            statement.setObject(7, utc(context.acceptedAt()));
            statement.setObject(8, utc(context.writtenAt()));
            statement.setObject(9, utc(context.writtenAt()));
            statement.setLong(10, context.batchId());
            statement.setLong(11, fromExclusive);
            statement.setLong(12, toInclusive);
            statement.setLong(13, context.importLinkId());
        });
    }

    /**
     * Werkt de bronstaatrijen bij voor de {@code CHANGED}-regels van één chunk: nieuwe
     * vingerafdrukken en basisprijs, andere {@code last_change_*}, {@code accepted_*} en
     * {@code updated_at}. Rijen die al de nieuwe vingerafdruk hebben, blijven onaangeroerd.
     *
     * @return het aantal werkelijk bijgewerkte rijen
     */
    public int updateChangedFromStage(AcceptanceContext context, long fromExclusive, long toInclusive) {
        List<ChangedRow> rows = jdbc.query(SELECT_CHANGED_FROM_STAGE,
                (resultSet, index) -> new ChangedRow(resultSet.getBytes(1), resultSet.getBytes(2),
                        resultSet.getBytes(3), resultSet.getBytes(4), resultSet.getBytes(5),
                        resultSet.getBigDecimal(6), resultSet.getString(7)),
                context.batchId(), fromExclusive, toInclusive);
        if (rows.isEmpty()) {
            return 0;
        }
        int[] counts = jdbc.batchUpdate(UPDATE_CHANGED, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ChangedRow row = rows.get(index);
                statement.setBytes(1, row.articleFingerprint());
                statement.setBytes(2, row.priceFingerprint());
                if (row.referenceFingerprint() == null) {
                    statement.setNull(3, Types.BINARY);
                } else {
                    statement.setBytes(3, row.referenceFingerprint());
                }
                statement.setBytes(4, row.combinedFingerprint());
                statement.setBigDecimal(5, row.basePrice());
                if (row.basePriceCurrency() == null) {
                    statement.setNull(6, Types.VARCHAR);
                } else {
                    statement.setString(6, row.basePriceCurrency());
                }
                statement.setString(7, context.stateOrigin());
                statement.setLong(8, context.deliveryId());
                statement.setLong(9, context.batchId());
                statement.setString(10, context.acceptedBy());
                statement.setObject(11, utc(context.acceptedAt()));
                statement.setObject(12, utc(context.writtenAt()));
                statement.setLong(13, context.importLinkId());
                statement.setBytes(14, row.identityHash());
                statement.setBytes(15, row.combinedFingerprint());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
        int updated = 0;
        for (int count : counts) {
            // SUCCESS_NO_INFO (-2) telt als geslaagd zonder aantal; H2 en PostgreSQL geven wel aantallen.
            updated += Math.max(count, 0);
        }
        return updated;
    }

    public long countByImportLinkId(long importLinkId) {
        Long count = jdbc.queryForObject("select count(*) from catalog_source_state where import_link_id = ?",
                Long.class, importLinkId);
        return count == null ? 0L : count;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
