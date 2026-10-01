package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ExternalCredentialEventRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.SecretKeyCheckDao;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialEvent;
import be.dda.catalogimport.domain.ExternalCredentialEventKind;
import be.dda.catalogimport.domain.ExternalCredentialEventSource;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Bouwstap K-2b: herversleutelen bij opstart ({@link SecretsRotationService};
 * {@code docs/design/credentials-sleutelbeheer-design.md} par. 5.2 en 6, beslissingslog 2026-09-29 V4/V5/A6/A7).
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> na een sleutelrotatie staat elke actieve waarde onder de actieve sleutel, met een auditspoor per
 *       rij. <b>Implementatie:</b> batch per rij in een eigen transactie, event {@code REENCRYPTED} als {@code SYSTEM}.</li>
 *   <li><b>Regel:</b> herstarten of twee instanties tegelijk mogen niets dubbel doen. <b>Implementatie:</b>
 *       optimistische guard in de update; enkel bij 1 geraakte rij een event.</li>
 *   <li><b>Regel:</b> sleutelverlies en corrupte waarden breken het opstarten niet (V5). <b>Implementatie:</b>
 *       overslaan met WARN zonder waarde.</li>
 * </ul>
 * Elke test bouwt eigen, unieke sleutel-ID's, zodat rijen van andere tests op een blijvend schema nooit in de
 * kandidatenlijst van een test terechtkomen (de selectie is beperkt tot de ring van die test).
 */
@SpringBootTest
@ActiveProfiles("local")
class SecretsRotationTest {

    private static final String KEY_A = b64(31);
    private static final String KEY_B = b64(32);
    private static final String KEY_C = b64(33);
    private static final String HOST = "sftp.example.test";
    private static final String KIND = ExternalCredentialSecretKind.SFTP_PASSWORD.name();

    @Autowired
    private ExternalCredentialRepository credentials;
    @Autowired
    private ExternalCredentialEventRepository events;
    @Autowired
    private SecretKeyCheckDao checks;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private Clock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private Logger logger;
    private ListAppender<ILoggingEvent> log;
    private Level originalLevel;

    @BeforeEach
    void startCapturingLogs() {
        this.logger = (Logger) LoggerFactory.getLogger(SecretsRotationService.class);
        this.originalLevel = this.logger.getLevel();
        this.logger.setLevel(Level.DEBUG);
        this.log = new ListAppender<>();
        this.log.start();
        this.logger.addAppender(this.log);
    }

    @AfterEach
    void stopCapturingLogs() {
        this.logger.detachAppender(this.log);
        this.logger.setLevel(this.originalLevel);
    }

    // --- Rotatie A -> B -----------------------------------------------------------------------------------

    @Test
    void rotationReEncryptsEveryActiveRowUnderTheOldKeyWithOneSystemEventEach() {
        String a = keyId();
        String b = keyId();
        SecretsService oldRing = new SecretsService(a + ":" + KEY_A, a);
        SecretsService newRing = new SecretsService(a + ":" + KEY_A + "," + b + ":" + KEY_B, b);
        ExternalCredential first = store(oldRing, "geheim-een", "Eerste");
        ExternalCredential second = store(oldRing, "geheim-twee", "Tweede");
        // Lees referentiewaarden uit de database (microseconde-precisie) om later vergelijking
        // met `after` (ook uit DB) betrouwbaar te maken. In-memory entity heeft 100-ns-precisie.
        ExternalCredential firstFromDb = credentials.findById(first.getId()).orElseThrow();
        Instant updatedAt = firstFromDb.getSecretUpdatedAt();
        String updatedBy = firstFromDb.getSecretUpdatedBy();
        String oldCiphertext = first.getCiphertext();

        Map<String, Integer> rotated = rotation(newRing).rotate();

        assertThat(rotated).containsExactly(Map.entry(a, 2));
        for (ExternalCredential before : List.of(first, second)) {
            ExternalCredential after = credentials.findById(before.getId()).orElseThrow();
            assertThat(after.getEncryptionKeyId()).isEqualTo(b);
            assertThat(after.getCiphertext()).startsWith("v1:" + b + ":").isNotEqualTo(before.getCiphertext());
            List<ExternalCredentialEvent> trail = events.findByCredentialIdOrderByIdAsc(before.getId());
            assertThat(trail).hasSize(1);
            ExternalCredentialEvent event = trail.get(0);
            assertThat(event.getEventKind()).isEqualTo(ExternalCredentialEventKind.REENCRYPTED);
            assertThat(event.getSource()).isEqualTo(ExternalCredentialEventSource.SYSTEM);
            assertThat(event.getChangedBy()).isNull();
            assertThat(event.getChangedBySubject()).isNull();
            assertThat(event.getPreviousKeyId()).isEqualTo(a);
            assertThat(event.getNewKeyId()).isEqualTo(b);
            assertThat(event.getReason()).isEqualTo(SecretsRotationService.REASON);
        }
        // Zelfde plaintext, nu enkel met B (een ring zonder A kan het lezen).
        SecretsService onlyB = new SecretsService(b + ":" + KEY_B, b);
        assertThat(decrypt(onlyB, first.getId(), first)).isEqualTo("geheim-een");
        assertThat(decrypt(onlyB, second.getId(), second)).isEqualTo("geheim-twee");
        // De waarde is niet veranderd: secret_updated_* blijft.
        ExternalCredential after = credentials.findById(first.getId()).orElseThrow();
        assertThat(after.getSecretUpdatedAt()).isEqualTo(updatedAt);
        assertThat(after.getSecretUpdatedBy()).isEqualTo(updatedBy);
        assertThat(oldCiphertext).isNotEqualTo(after.getCiphertext());
        // Log: enkel aantallen en sleutel-ID's.
        assertThat(messages()).anyMatch(m -> m.contains("2 credential(s) re-encrypted from key id '" + a + "'"));
        assertNoSecretsLogged("geheim-een", "geheim-twee");
    }

    @Test
    void aSecondRunDoesNothing() {
        String a = keyId();
        String b = keyId();
        ExternalCredential row = store(new SecretsService(a + ":" + KEY_A, a), "geheim", "Herstart");
        SecretsService ring = new SecretsService(a + ":" + KEY_A + "," + b + ":" + KEY_B, b);
        SecretsRotationService service = rotation(ring);

        assertThat(service.rotate()).containsExactly(Map.entry(a, 1));
        String afterFirst = credentials.findById(row.getId()).orElseThrow().getCiphertext();
        assertThat(service.rotate()).isEmpty();
        assertThat(rotation(ring).rotate()).isEmpty();

        assertThat(credentials.findById(row.getId()).orElseThrow().getCiphertext()).isEqualTo(afterFirst);
        assertThat(events.findByCredentialIdOrderByIdAsc(row.getId())).hasSize(1);
    }

    @Test
    void rowsAlreadyUnderTheActiveKeyAreNotTouched() {
        String a = keyId();
        SecretsService ring = new SecretsService(a + ":" + KEY_A, a);
        ExternalCredential row = store(ring, "geheim", "Al actief");

        assertThat(rotation(ring).rotate()).isEmpty();

        assertThat(credentials.findById(row.getId()).orElseThrow().getCiphertext()).isEqualTo(row.getCiphertext());
        assertThat(events.findByCredentialIdOrderByIdAsc(row.getId())).isEmpty();
    }

    @Test
    void aRevokedRowIsNeverTouched() {
        String a = keyId();
        String b = keyId();
        ExternalCredential revoked = store(new SecretsService(a + ":" + KEY_A, a), "geheim", "Ingetrokken");
        ExternalCredential live = store(new SecretsService(a + ":" + KEY_A, a), "levend", "Levend");
        jdbc.update("update external_credential set ciphertext = null, encryption_key_id = null, "
                + "status = 'REVOKED', revoked_at = now(), revoked_by = 'beheerder', "
                + "revoked_reason = 'Leverancier gestopt' where id = ?", revoked.getId());

        Map<String, Integer> rotated = rotation(
                new SecretsService(a + ":" + KEY_A + "," + b + ":" + KEY_B, b)).rotate();

        assertThat(rotated).containsExactly(Map.entry(a, 1));
        ExternalCredential after = credentials.findById(revoked.getId()).orElseThrow();
        assertThat(after.getCiphertext()).isNull();
        assertThat(after.getEncryptionKeyId()).isNull();
        assertThat(events.findByCredentialIdOrderByIdAsc(revoked.getId())).isEmpty();
        assertThat(events.findByCredentialIdOrderByIdAsc(live.getId())).hasSize(1);
    }

    // --- Onbekende sleutel, corrupte waarde, geen config --------------------------------------------------

    @Test
    void aRowUnderAKeyIdOutsideTheRingIsLeftAloneWithoutFailure() {
        String lost = keyId();
        String current = keyId();
        ExternalCredential row = store(new SecretsService(lost + ":" + KEY_C, lost), "verloren", "Verloren sleutel");
        SecretsService ring = new SecretsService(current + ":" + KEY_A, current);

        assertThat(rotation(ring).rotate()).isEmpty();

        ExternalCredential after = credentials.findById(row.getId()).orElseThrow();
        assertThat(after.getCiphertext()).isEqualTo(row.getCiphertext());
        assertThat(after.getEncryptionKeyId()).isEqualTo(lost);
        assertThat(events.findByCredentialIdOrderByIdAsc(row.getId())).isEmpty();
        assertThat(this.log.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void aCorruptRowIsSkippedWithAWarningAndTheOtherRowsAreStillRotated() {
        String a = keyId();
        String b = keyId();
        SecretsService oldRing = new SecretsService(a + ":" + KEY_A, a);
        ExternalCredential good = store(oldRing, "goed-geheim", "Goed");
        String corrupt = "v1:" + a + ":AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        ExternalCredential broken = credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(), "Corrupt",
                ExternalCredentialSecretKind.SFTP_PASSWORD, HOST, corrupt, a, "beheerder@example.test", null,
                Instant.now()));
        ExternalCredential alsoGood = store(oldRing, "ook-goed", "Ook goed");

        Map<String, Integer> rotated = rotation(
                new SecretsService(a + ":" + KEY_A + "," + b + ":" + KEY_B, b)).rotate();

        assertThat(rotated).containsExactly(Map.entry(a, 2));
        assertThat(credentials.findById(good.getId()).orElseThrow().getEncryptionKeyId()).isEqualTo(b);
        assertThat(credentials.findById(alsoGood.getId()).orElseThrow().getEncryptionKeyId()).isEqualTo(b);
        ExternalCredential after = credentials.findById(broken.getId()).orElseThrow();
        assertThat(after.getCiphertext()).isEqualTo(corrupt);
        assertThat(after.getEncryptionKeyId()).isEqualTo(a);
        assertThat(events.findByCredentialIdOrderByIdAsc(broken.getId())).isEmpty();
        List<ILoggingEvent> warnings = this.log.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage()).contains("CREDENTIAL_UNDECRYPTABLE")
                .contains("'" + a + "'");
        assertNoSecretsLogged("goed-geheim", "ook-goed", corrupt);
    }

    @Test
    void withoutConfigurationNothingHappens() {
        String a = keyId();
        ExternalCredential row = store(new SecretsService(a + ":" + KEY_A, a), "geheim", "Zonder config");
        SecretsRotationService service = rotation(new SecretsService("", ""));

        assertThat(service.rotate()).isEmpty();
        service.afterSingletonsInstantiated();

        assertThat(credentials.findById(row.getId()).orElseThrow().getCiphertext()).isEqualTo(row.getCiphertext());
        assertThat(events.findByCredentialIdOrderByIdAsc(row.getId())).isEmpty();
        assertThat(this.log.list).isEmpty();
    }

    // --- Ordening t.o.v. de sleutelcontrole -----------------------------------------------------------------

    /** De opstartcallback controleert eerst de sleutels; een sleutel die hier niet thuishoort, herversleutelt niets. */
    @Test
    void startupVerifiesTheKeysFirstAndRotatesNothingWhenAKeyDoesNotBelong() {
        String a = keyId();
        String b = keyId();
        SecretsService original = new SecretsService(a + ":" + KEY_A, a);
        ExternalCredential row = store(original, "geheim", "Ordening");
        new SecretKeyCheckVerifier(original, checks, credentials).verify();

        // Ander materiaal onder ID a (tikfout / andere omgeving), actieve sleutel b.
        SecretsService foreign = new SecretsService(a + ":" + KEY_C + "," + b + ":" + KEY_B, b);
        assertThatThrownBy(() -> rotation(foreign).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'" + a + "'");
        assertThat(credentials.findById(row.getId()).orElseThrow().getEncryptionKeyId()).isEqualTo(a);
        assertThat(events.findByCredentialIdOrderByIdAsc(row.getId())).isEmpty();

        // Met het juiste materiaal: controle slaagt en de rotatie loopt in dezelfde callback.
        SecretsService right = new SecretsService(a + ":" + KEY_A + "," + b + ":" + KEY_B, b);
        rotation(right).afterSingletonsInstantiated();
        assertThat(credentials.findById(row.getId()).orElseThrow().getEncryptionKeyId()).isEqualTo(b);
        assertThat(events.findByCredentialIdOrderByIdAsc(row.getId())).hasSize(1);
    }

    // --- Gelijktijdigheid -----------------------------------------------------------------------------------

    /** Twee instanties lezen dezelfde momentopname: enkel de eerste slaagt, de tweede raakt 0 rijen en schrijft niets. */
    @Test
    void aStaleSnapshotCannotRotateTheSameRowTwice() {
        String a = keyId();
        String b = keyId();
        ExternalCredential row = store(new SecretsService(a + ":" + KEY_A, a), "geheim", "Guard");
        SecretsRotationService service = rotation(new SecretsService(a + ":" + KEY_A + "," + b + ":" + KEY_B, b));

        assertThat(service.rotateRow(row)).isTrue();
        assertThat(service.rotateRow(row)).isFalse();

        assertThat(events.findByCredentialIdOrderByIdAsc(row.getId())).hasSize(1);
        assertThat(credentials.findById(row.getId()).orElseThrow().getEncryptionKeyId()).isEqualTo(b);
    }

    @Test
    void twoInstancesRotatingAtTheSameTimeWriteOneEventPerRow() throws Exception {
        String a = keyId();
        String b = keyId();
        SecretsService oldRing = new SecretsService(a + ":" + KEY_A, a);
        List<ExternalCredential> rows = List.of(store(oldRing, "een", "P1"), store(oldRing, "twee", "P2"),
                store(oldRing, "drie", "P3"));
        SecretsService newRing = new SecretsService(a + ":" + KEY_A + "," + b + ":" + KEY_B, b);
        SecretsRotationService one = rotation(newRing);
        SecretsRotationService two = rotation(newRing);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Integer>> first = pool.submit(() -> {
                go.await();
                return one.rotate();
            });
            Future<Map<String, Integer>> second = pool.submit(() -> {
                go.await();
                return two.rotate();
            });
            go.countDown();
            int total = first.get().getOrDefault(a, 0) + second.get().getOrDefault(a, 0);
            assertThat(total).isEqualTo(rows.size());
        } finally {
            pool.shutdownNow();
        }

        SecretsService onlyB = new SecretsService(b + ":" + KEY_B, b);
        for (ExternalCredential row : rows) {
            assertThat(events.findByCredentialIdOrderByIdAsc(row.getId())).hasSize(1);
            ExternalCredential after = credentials.findById(row.getId()).orElseThrow();
            assertThat(after.getEncryptionKeyId()).isEqualTo(b);
            assertThat(decrypt(onlyB, row.getId(), row)).isNotBlank();
        }
    }

    // --- Operatorhulp ---------------------------------------------------------------------------------------

    @Test
    void theRowCountPerKeyIdShowsWhenAnOldKeyCanBeRemoved() {
        String a = keyId();
        String b = keyId();
        store(new SecretsService(a + ":" + KEY_A, a), "geheim", "Telling");
        assertThat(rowsUnder(a)).isEqualTo(1);

        rotation(new SecretsService(a + ":" + KEY_A + "," + b + ":" + KEY_B, b)).rotate();

        assertThat(rowsUnder(a)).isZero();
        assertThat(rowsUnder(b)).isEqualTo(1);
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private SecretsRotationService rotation(SecretsService secrets) {
        return new SecretsRotationService(secrets, new SecretKeyCheckVerifier(secrets, checks, credentials),
                credentials, events, transactionManager, clock);
    }

    private ExternalCredential store(SecretsService secrets, String plaintext, String label) {
        UUID ref = UUID.randomUUID();
        String ciphertext = secrets.encrypt(plaintext, ref, KIND);
        return credentials.saveAndFlush(new ExternalCredential(ref, label,
                ExternalCredentialSecretKind.SFTP_PASSWORD, HOST, ciphertext, secrets.activeKeyId(),
                "beheerder@example.test", null, Instant.now()));
    }

    private String decrypt(SecretsService secrets, Long id, ExternalCredential original) {
        ExternalCredential current = credentials.findById(id).orElseThrow();
        return secrets.decrypt(current.getCiphertext(), original.getCredentialRef(), KIND);
    }

    private long rowsUnder(String keyId) {
        return credentials.countRowsPerEncryptionKeyId().stream()
                .filter(count -> keyId.equals(count.getEncryptionKeyId()))
                .mapToLong(ExternalCredentialRepository.KeyIdCount::getRowCount).sum();
    }

    private static String keyId() {
        return "rot-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private List<String> messages() {
        return this.log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertNoSecretsLogged(String... values) {
        assertThat(messages()).allSatisfy(message -> {
            for (String value : values) {
                assertThat(message).doesNotContain(value);
            }
            assertThat(message).doesNotContain(KEY_A).doesNotContain(KEY_B).doesNotContain(KEY_C)
                    .doesNotContain("v1:");
        });
    }

    private static String b64(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
