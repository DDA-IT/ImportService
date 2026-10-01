package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.SecretKeyCheckDao;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bouwstap K-2a: de opstartcontrole {@code secret_key_check} ({@link SecretKeyCheckVerifier};
 * {@code docs/design/credentials-sleutelbeheer-design.md} par. 5.3, beslissingslog 2026-09-29 V7 en A7).
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> een sleutel die niet bij deze database hoort, laat de applicatie niet starten (A7).
 *       <b>Implementatie:</b> per geconfigureerde sleutel een controlewaarde in {@code secret_key_check}; bestaat
 *       ze, dan moet ze ontsleutelen tot de bekende tekst, anders {@link IllegalStateException} tijdens het
 *       opstarten van de context.</li>
 *   <li><b>Regel:</b> geen config = functie uit (V7). <b>Implementatie:</b> de controle doet dan niets.</li>
 *   <li><b>Regel:</b> sleutelverlies is geen opstartfout (V5). <b>Implementatie:</b> een sleutel-ID in
 *       {@code external_credential} buiten de ring geeft enkel een waarschuwing.</li>
 * </ul>
 * De gedeelde Spring-context (profiel {@code local}, zonder secrets-config) levert enkel de echte DAO's en de
 * PostgreSQL-database. De scenario's bouwen per test een eigen {@link SecretsService} met een uniek sleutel-ID,
 * zodat ze elkaar en herhaalde runs op een blijvend schema niet beïnvloeden. Het echte opstartpad
 * ({@link org.springframework.beans.factory.SmartInitializingSingleton}) wordt bewezen met een
 * {@link ApplicationContextRunner} bovenop dezelfde database.
 */
@SpringBootTest
@ActiveProfiles("local")
class SecretKeyCheckStartupTest {

    private static final String KEY_1 = b64(21);
    private static final String KEY_2 = b64(22);
    private static final String KEY_3 = b64(23);

    @Autowired
    private SecretKeyCheckDao checks;
    @Autowired
    private ExternalCredentialRepository credentials;
    @Autowired
    private SecretKeyCheckVerifier contextVerifier;
    @Autowired
    private JdbcTemplate jdbc;

    private Logger logger;
    private ListAppender<ILoggingEvent> log;
    private Level originalLevel;

    @BeforeEach
    void startCapturingLogs() {
        this.logger = (Logger) LoggerFactory.getLogger(SecretKeyCheckVerifier.class);
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

    // --- Eerste en volgende start ---------------------------------------------------------------------

    @Test
    void theFirstStartRecordsAControlValueThatDecryptsWithThatKey() {
        String keyId = keyId();
        SecretsService secrets = new SecretsService(keyId + ":" + KEY_1, keyId);
        assertThat(checks.findCheckValue(keyId)).isEmpty();

        verifier(secrets).verify();

        String stored = checks.findCheckValue(keyId).orElseThrow();
        assertThat(stored).startsWith("v1:" + keyId + ":").doesNotContain(SecretsService.KEY_CHECK_TEXT);
        assertThat(secrets.matchesKeyCheckValue(keyId, stored)).isTrue();
        assertThat(messages()).anyMatch(message -> message.contains("recorded for key id '" + keyId + "'"));
        assertNoKeyMaterialLogged();
    }

    @Test
    void aSecondStartWithTheSameKeyPassesAndChangesNothing() {
        String keyId = keyId();
        verifier(new SecretsService(keyId + ":" + KEY_1, keyId)).verify();
        String first = checks.findCheckValue(keyId).orElseThrow();

        // Een nieuwe instantie met hetzelfde materiaal: zoals een herstart.
        assertThatCode(() -> verifier(new SecretsService(keyId + ":" + KEY_1, keyId)).verify())
                .doesNotThrowAnyException();

        assertThat(checks.findCheckValue(keyId)).contains(first);
        assertThat(messages()).anyMatch(message -> message.contains("key id '" + keyId + "' verified"));
    }

    /** A7: ander sleutelmateriaal onder dezelfde ID hoort niet bij deze database — niet starten. */
    @Test
    void anotherKeyUnderTheSameIdFailsFastWithoutLeakingKeyMaterial() {
        String keyId = keyId();
        verifier(new SecretsService(keyId + ":" + KEY_1, keyId)).verify();
        String first = checks.findCheckValue(keyId).orElseThrow();

        assertThatThrownBy(() -> verifier(new SecretsService(keyId + ":" + KEY_2, keyId)).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'" + keyId + "'")
                .hasMessageContaining("does not belong to this database")
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(KEY_1).doesNotContain(KEY_2)
                        .doesNotContain(first));

        // De rij van de oorspronkelijke sleutel blijft onaangeroerd: niets overschreven.
        assertThat(checks.findCheckValue(keyId)).contains(first);
        assertNoKeyMaterialLogged();
    }

    /** Niet enkel de actieve sleutel: ook een oude (ontsleutel-)sleutel in de ring moet hier thuishoren. */
    @Test
    void everyConfiguredKeyIsCheckedNotOnlyTheActiveOne() {
        String active = keyId();
        String old = keyId();
        verifier(new SecretsService(active + ":" + KEY_1 + "," + old + ":" + KEY_2, active)).verify();
        assertThat(checks.findCheckValue(active)).isPresent();
        assertThat(checks.findCheckValue(old)).isPresent();

        // De oude sleutel-ID krijgt ander materiaal: de actieve klopt nog, toch start de applicatie niet.
        assertThatThrownBy(() -> verifier(new SecretsService(active + ":" + KEY_1 + "," + old + ":" + KEY_3,
                active)).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'" + old + "'");
    }

    @Test
    void anAlteredOrMisplacedControlValueFailsFast() {
        String keyId = keyId();
        checks.insertIfAbsent(keyId, "niet-versleuteld", Instant.now());
        assertThatThrownBy(() -> verifier(new SecretsService(keyId + ":" + KEY_1, keyId)).verify())
                .isInstanceOf(IllegalStateException.class);

        // Een geldige controlewaarde van sleutel A, gekopieerd naar de rij van sleutel B: weigeren.
        String a = keyId();
        String b = keyId();
        SecretsService both = new SecretsService(a + ":" + KEY_1 + "," + b + ":" + KEY_2, a);
        checks.insertIfAbsent(b, both.keyCheckValue(a), Instant.now());
        assertThatThrownBy(() -> verifier(both).verify()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'" + b + "'");
    }

    /** De controlewaarde heeft haar eigen associated data: nooit als credential te lezen, en omgekeerd. */
    @Test
    void theControlValueAndACredentialCanNeverBeSwapped() {
        String keyId = keyId();
        SecretsService secrets = new SecretsService(keyId + ":" + KEY_1, keyId);
        UUID ref = UUID.randomUUID();

        String control = secrets.keyCheckValue(keyId);
        assertThatThrownBy(() -> secrets.decrypt(control, ref, "SFTP_PASSWORD"))
                .isInstanceOf(CredentialUndecryptableException.class);
        String credential = secrets.encrypt(SecretsService.KEY_CHECK_TEXT, ref, "SFTP_PASSWORD");
        assertThat(secrets.matchesKeyCheckValue(keyId, credential)).isFalse();
        assertThat(secrets.matchesKeyCheckValue(keyId, control)).isTrue();
        assertThat(secrets.matchesKeyCheckValue(keyId, null)).isFalse();
        assertThatThrownBy(() -> secrets.keyCheckValue("not-in-ring")).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Geen config en sleutelverlies --------------------------------------------------------------------

    /** V7: zonder sleutelring doet de controle niets — geen rij, geen waarschuwing, geen fout. */
    @Test
    void withoutConfigurationNothingHappens() {
        Long before = countKeyChecks();

        assertThatCode(() -> verifier(new SecretsService("", "")).verify()).doesNotThrowAnyException();
        // De bean van de gedeelde context (zonder config) is dezelfde klasse en deed bij het opstarten niets.
        assertThatCode(() -> contextVerifier.verify()).doesNotThrowAnyException();

        assertThat(countKeyChecks()).isEqualTo(before);
        assertThat(this.log.list).isEmpty();
    }

    /** V5/A6: een verloren sleutel is geen opstartfout; de credentials zijn (afgeleid) UNDECRYPTABLE. */
    @Test
    void aCredentialKeyIdOutsideTheRingOnlyWarns() {
        String lostKeyId = keyId();
        String ciphertext = "v1:" + lostKeyId + ":AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(), "Verloren sleutel",
                ExternalCredentialSecretKind.SFTP_PASSWORD, "sftp.example.test", ciphertext, lostKeyId,
                "beheerder@example.test", null, Instant.now()));
        String current = keyId();

        assertThatCode(() -> verifier(new SecretsService(current + ":" + KEY_1, current)).verify())
                .doesNotThrowAnyException();

        List<ILoggingEvent> warnings = this.log.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("'" + lostKeyId + "'"))
                .toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage()).contains("CREDENTIAL_UNDECRYPTABLE")
                .doesNotContain(ciphertext);
        // De sleutel die wél in de ring staat, geeft geen waarschuwing.
        assertThat(this.log.list).noneMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("'" + current + "'"));
    }

    // --- Het echte opstartpad -------------------------------------------------------------------------

    /**
     * Bewijst dat de controle werkelijk bij het opstarten van een context loopt (als
     * {@code SmartInitializingSingleton}) en dat een foute sleutel het opstarten afbreekt.
     */
    @Test
    void theCheckRunsWhenAContextStartsAndAWrongKeyStopsTheStart() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(SecretKeyCheckDao.class, () -> checks)
                .withBean(ExternalCredentialRepository.class, () -> credentials)
                .withUserConfiguration(SecretsService.class, SecretKeyCheckVerifier.class);
        String keyId = keyId();
        Long before = countKeyChecks();

        // Zonder config: de context start en er gebeurt niets.
        runner.run(context -> assertThat(context).hasNotFailed());
        assertThat(countKeyChecks()).isEqualTo(before);

        // Eerste start met een sleutel: de rij wordt aangemaakt.
        runner.withPropertyValues("catalogimport.secrets.keys=" + keyId + ":" + KEY_1,
                        "catalogimport.secrets.active-key-id=" + keyId)
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(checks.findCheckValue(keyId)).isPresent();

        // Tweede start met dezelfde sleutel: ok.
        runner.withPropertyValues("catalogimport.secrets.keys=" + keyId + ":" + KEY_1,
                        "catalogimport.secrets.active-key-id=" + keyId)
                .run(context -> assertThat(context).hasNotFailed());

        // Ander materiaal onder dezelfde ID: de context start niet.
        runner.withPropertyValues("catalogimport.secrets.keys=" + keyId + ":" + KEY_2,
                        "catalogimport.secrets.active-key-id=" + keyId)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    assertThat(chain(failure)).anyMatch(cause -> cause instanceof IllegalStateException
                            && cause.getMessage().contains("'" + keyId + "'"));
                    assertThat(chain(failure)).allSatisfy(cause -> assertThat(String.valueOf(cause.getMessage()))
                            .doesNotContain(KEY_1).doesNotContain(KEY_2));
                });
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private SecretKeyCheckVerifier verifier(SecretsService secrets) {
        return new SecretKeyCheckVerifier(secrets, checks, credentials);
    }

    /** Uniek per aanroep, geldig volgens {@code [A-Za-z0-9-]{1,32}}. */
    private static String keyId() {
        return "kcs-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Long countKeyChecks() {
        return jdbc.queryForObject("select count(*) from secret_key_check", Long.class);
    }

    private List<String> messages() {
        return this.log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertNoKeyMaterialLogged() {
        assertThat(messages()).allSatisfy(message -> assertThat(message)
                .doesNotContain(KEY_1).doesNotContain(KEY_2).doesNotContain(KEY_3)
                .doesNotContain(SecretsService.KEY_CHECK_TEXT));
    }

    private static List<Throwable> chain(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        for (Throwable current = failure; current != null && !causes.contains(current);
             current = current.getCause()) {
            causes.add(current);
        }
        return causes;
    }

    private static String b64(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
