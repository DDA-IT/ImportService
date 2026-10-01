package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bouwstap K-2a, lekbewijs ({@code docs/design/credentials-sleutelbeheer-design.md} par. 2.5; beslissingslog
 * 2026-09-29 V1): een secret in zuivere tekst komt <b>nergens</b> voor — niet in een logregel (van welke logger
 * ook, met onze pakketten op TRACE en de SQL-log aan), niet in een exceptiemelding of stacktrace, niet in
 * {@code toString()} van een entiteit of component, en niet in de database.
 * <p>
 * Er is bewust nog geen {@code CredentialService} (K-3): het pad wordt hier rechtstreeks met {@link SecretsService}
 * en de repositories gelopen, precies zoals K-3 het zal doen — versleutelen vóór de entiteit bestaat, de entiteit
 * krijgt enkel de ciphertext.
 * <p>
 * De sleutel staat enkel in deze testklasse (V6), onder een per run uniek sleutel-ID.
 */
@SpringBootTest
@ActiveProfiles("local")
class CredentialLeakDetectionTest {

    /** Een waarde die nergens toevallig voorkomt, zodat elke vondst een echt lek is. */
    private static final String PLAIN = "LeakCanary-Wachtw00rd-7f3a9c!";
    private static final String KEY = b64(31);
    private static final String OTHER_KEY = b64(32);
    private static final String KIND = ExternalCredentialSecretKind.SFTP_PASSWORD.name();
    private static final String USER = "beheerder@example.test";

    @Autowired
    private ExternalCredentialRepository credentials;
    @Autowired
    private ExternalCredentialEventRepository events;
    @Autowired
    private SecretKeyCheckDao keyChecks;
    @Autowired
    private JdbcTemplate jdbc;

    private final String keyId = "leak-" + UUID.randomUUID().toString().substring(0, 8);
    private final SecretsService secrets = new SecretsService(keyId + ":" + KEY, keyId);

    private Logger root;
    private ListAppender<ILoggingEvent> log;
    private final List<Logger> raised = new ArrayList<>();
    private final List<Level> originalLevels = new ArrayList<>();

    @BeforeEach
    void captureEveryLogLine() {
        raise("be.dda.catalogimport", Level.TRACE);
        raise("org.hibernate.SQL", Level.DEBUG);
        raise("org.springframework.jdbc.core", Level.DEBUG);
        this.root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        this.log = new ListAppender<>();
        this.log.start();
        this.root.addAppender(this.log);
    }

    @AfterEach
    void stopCapturing() {
        this.root.detachAppender(this.log);
        for (int i = 0; i < raised.size(); i++) {
            raised.get(i).setLevel(originalLevels.get(i));
        }
    }

    // --- Het normale pad ----------------------------------------------------------------------------------

    @Test
    void thePlaintextAppearsNowhereAlongTheWholeRoundTrip() {
        UUID ref = UUID.randomUUID();
        String ciphertext = secrets.encrypt(PLAIN, ref, KIND);
        String payload = ciphertext.split(":")[2];

        ExternalCredential saved = credentials.saveAndFlush(new ExternalCredential(ref, "Lektest SFTP",
                ExternalCredentialSecretKind.SFTP_PASSWORD, "sftp.example.test", ciphertext,
                secrets.activeKeyId(), USER, "sub-lektest", Instant.now()));
        ExternalCredentialEvent created = events.saveAndFlush(new ExternalCredentialEvent(saved,
                ExternalCredentialEventKind.CREATED, "Nieuwe credential voor leverancier",
                ExternalCredentialEventSource.HUMAN, USER, "sub-lektest", Instant.now(), null,
                secrets.activeKeyId()));
        new SecretKeyCheckVerifier(secrets, keyChecks, credentials).verify();

        ExternalCredential reloaded = credentials.findByCredentialRef(ref).orElseThrow();
        // De enige plek waar de zuivere tekst bestaat: de return van decrypt, op de server.
        assertThat(secrets.decrypt(reloaded.getCiphertext(), ref, KIND)).isEqualTo(PLAIN);
        assertThat(reloaded.getCiphertext()).isEqualTo(ciphertext).doesNotContain(PLAIN);
        assertThat(reloaded.getEncryptionKeyId()).isEqualTo(keyId);

        // toString van entiteiten en componenten: geen zuivere tekst, geen ciphertext, geen sleutel.
        for (Object shown : List.of(saved, reloaded, created, secrets,
                new SecretKeyCheckVerifier(secrets, keyChecks, credentials))) {
            assertThat(shown.toString()).as(shown.getClass().getSimpleName())
                    .doesNotContain(PLAIN).doesNotContain(ciphertext).doesNotContain(payload).doesNotContain(KEY);
        }

        // De database: geen enkele kolom van de rij of haar events bevat de zuivere tekst.
        assertRowsDoNotContain(jdbc.queryForList("select * from external_credential where credential_ref = ?",
                ref), PLAIN);
        assertRowsDoNotContain(jdbc.queryForList("select * from external_credential_event where credential_id = ?",
                saved.getId()), PLAIN);
        assertRowsDoNotContain(jdbc.queryForList("select * from secret_key_check where key_id = ?", keyId), KEY);

        assertThat(this.log.list).as("captured log lines").isNotEmpty();
        assertLogsDoNotContain(PLAIN, KEY, payload);
    }

    // --- Elke foutweg ----------------------------------------------------------------------------------

    @Test
    void noFailureCarriesThePlaintextTheCiphertextOrKeyMaterial() {
        UUID ref = UUID.randomUUID();
        String ciphertext = secrets.encrypt(PLAIN, ref, KIND);
        String payload = ciphertext.split(":")[2];
        byte[] tampered = Base64.getDecoder().decode(payload);
        tampered[tampered.length - 1] ^= 0x01;

        List<Throwable> failures = new ArrayList<>();
        failures.add(catchThrowable(() -> secrets.decrypt(ciphertext, UUID.randomUUID(), KIND)));
        failures.add(catchThrowable(() -> secrets.decrypt(ciphertext, ref, "SSH_PRIVATE_KEY")));
        failures.add(catchThrowable(() -> secrets.decrypt("v1:" + keyId + ":"
                + Base64.getEncoder().encodeToString(tampered), ref, KIND)));
        failures.add(catchThrowable(() -> new SecretsService("other-id:" + OTHER_KEY, "other-id")
                .decrypt(ciphertext, ref, KIND)));
        failures.add(catchThrowable(() -> new SecretsService(keyId + ":" + OTHER_KEY, keyId)
                .decrypt(ciphertext, ref, KIND)));
        failures.add(catchThrowable(() -> new SecretsService("", "").encrypt(PLAIN, ref, KIND)));
        failures.add(catchThrowable(() -> secrets.encrypt(PLAIN, ref, "A|B")));
        failures.add(catchThrowable(() -> new SecretsService(keyId + ":" + KEY + ",x:" + KEY, keyId)));
        // Fail-fast bij opstart: eerst de echte sleutel vastleggen, dan ander materiaal onder dezelfde ID.
        new SecretKeyCheckVerifier(secrets, keyChecks, credentials).verify();
        failures.add(catchThrowable(() -> new SecretKeyCheckVerifier(
                new SecretsService(keyId + ":" + OTHER_KEY, keyId), keyChecks, credentials).verify()));
        // Een databasefout op een rij met ciphertext (dubbele credential_ref).
        credentials.saveAndFlush(credential(ref, ciphertext));
        failures.add(catchThrowable(() -> credentials.saveAndFlush(credential(ref, ciphertext))));

        assertThat(failures).doesNotContainNull();
        assertThat(failures.get(0)).isInstanceOf(CredentialUndecryptableException.class);
        assertThat(failures.get(3)).isInstanceOf(CredentialUndecryptableException.class);
        assertThat(failures.get(4)).isInstanceOf(CredentialUndecryptableException.class);
        assertThat(failures.get(5)).isInstanceOf(SecretsNotConfiguredException.class);
        assertThat(failures.get(8)).isInstanceOf(IllegalStateException.class);
        assertThat(failures.get(9)).isInstanceOf(DataIntegrityViolationException.class);
        for (Throwable failure : failures) {
            String full = stackTrace(failure);
            assertThat(full).as(failure.getClass().getSimpleName())
                    .doesNotContain(PLAIN).doesNotContain(ciphertext).doesNotContain(payload)
                    .doesNotContain(KEY).doesNotContain(OTHER_KEY);
        }
        assertLogsDoNotContain(PLAIN, KEY, OTHER_KEY, payload);
    }

    /**
     * Decisions 2026-09-29 K-2a (a): {@code logServerErrorDetail=false}. Zonder die pgjdbc-property draagt een
     * checkfout de hele rij ("Failing row contains (...)"), dus ook de ciphertext. Hier schendt een rij met een
     * herkenbare ciphertext een check; noch de exceptieketen, noch de gecapteerde log mag hem tonen.
     */
    @Test
    void aCheckViolationDoesNotEchoTheFailingRowWithItsCiphertext() {
        String marker = "CiphertextMarker" + UUID.randomUUID().toString().replace("-", "");
        String ciphertext = "v1:" + keyId + ":" + marker;

        // ciphertext gevuld maar encryption_key_id null: schendt ck_external_credential_key_id.
        Throwable failure = catchThrowable(() -> jdbc.update("insert into external_credential (credential_ref, "
                        + "label, secret_kind, bound_host, ciphertext, encryption_key_id, status, created_at, "
                        + "created_by) values (?, 'Lekrij', 'SFTP_PASSWORD', 'sftp.example.test', ?, null, "
                        + "'ACTIVE', now(), ?)", UUID.randomUUID(), ciphertext, USER));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(failure.getMessage()).contains("ck_external_credential_key_id");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertThat(String.valueOf(cause.getMessage())).as(cause.getClass().getSimpleName())
                    .doesNotContain(marker).doesNotContain("Failing row contains");
        }
        assertThat(stackTrace(failure)).doesNotContain(marker).doesNotContain("Failing row contains");
        assertLogsDoNotContain(marker, "Failing row contains");
    }

    /**
     * V1 structureel: geen entiteit- of repositorymethode geeft een ontsleutelde waarde; ontsleutelen bestaat
     * enkel in {@link SecretsService}.
     */
    @Test
    void noEntityOrRepositoryMethodExposesADecryptedValue() {
        for (Class<?> type : List.of(ExternalCredential.class, ExternalCredentialEvent.class,
                ExternalCredentialRepository.class, ExternalCredentialEventRepository.class,
                SecretKeyCheckDao.class, SecretKeyCheckVerifier.class)) {
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                assertThat(name).as(type.getSimpleName() + "." + method.getName())
                        .doesNotContain("decrypt").doesNotContain("plain").doesNotContain("password")
                        .isNotEqualTo("getsecret");
            }
        }
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private ExternalCredential credential(UUID ref, String ciphertext) {
        return new ExternalCredential(ref, "Lektest dubbel", ExternalCredentialSecretKind.SFTP_PASSWORD,
                "sftp.example.test", ciphertext, keyId, USER, null, Instant.now());
    }

    private void raise(String loggerName, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        raised.add(logger);
        originalLevels.add(logger.getLevel());
        logger.setLevel(level);
    }

    private void assertLogsDoNotContain(String... forbidden) {
        for (ILoggingEvent event : this.log.list) {
            StringBuilder text = new StringBuilder(event.getFormattedMessage() == null ? ""
                    : event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
                for (StackTraceElementProxy frame : proxy.getStackTraceElementProxyArray()) {
                    text.append('\n').append(frame.getSTEAsString());
                }
            }
            for (String value : forbidden) {
                assertThat(text.toString()).as("log line of " + event.getLoggerName()).doesNotContain(value);
            }
        }
    }

    private static void assertRowsDoNotContain(List<Map<String, Object>> rows, String forbidden) {
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> column : row.entrySet()) {
                assertThat(String.valueOf(column.getValue())).as(column.getKey()).doesNotContain(forbidden);
            }
        }
    }

    private static String stackTrace(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static String b64(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
