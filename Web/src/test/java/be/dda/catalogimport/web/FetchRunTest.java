package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.FetchHostPolicy;
import be.dda.catalogimport.service.FetchOutcomeCodes;
import be.dda.catalogimport.service.FetchRunService;
import be.dda.catalogimport.service.FetchTarget;
import be.dda.catalogimport.service.NotFoundException;
import be.dda.catalogimport.service.SecretsService;
import be.dda.catalogimport.service.SftpConnector;
import be.dda.catalogimport.service.SftpConnector.DownloadPlan;
import be.dda.catalogimport.service.SftpConnector.FetchResult;
import be.dda.catalogimport.service.SftpConnector.Listing;
import be.dda.catalogimport.service.SftpConnector.PasswordSource;
import be.dda.catalogimport.service.SftpConnector.PinnedHostKey;
import be.dda.catalogimport.service.SftpConnector.RemoteFile;
import be.dda.catalogimport.service.TaskRunView;
import be.dda.catalogimport.service.support.RemoteFileSelection;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Bouwstap K-4b: "Nu ophalen" end-to-end tegen een embedded MINA SFTP-server
 * ({@code docs/design/leveringsconfiguratie-design.md} par. 4, 6, 9; beslissingslog 2026-09-29 L4-L6, A3, A4, A9, A12,
 * A13, V1).
 *
 * <ul>
 *   <li><b>Regel L6 (eerste run):</b> enkel het recentste matchende bestand, oudere {@code OLDER_THAN_WATERMARK}.
 *       <b>Data:</b> {@code task_run} ({@code MANUAL_FETCH}, DC-versie, {@code FETCHED}), {@code fetch_file_observation},
 *       {@code delivery} ({@code source_kind = SFTP}, sleutel A4), archief, batch, screening zoals bij de upload.</li>
 *   <li><b>Regel L6 (daarna):</b> chronologisch, één per run, {@code pending_file_count}; een nieuw bestand ouder dan de
 *       watermark wordt nooit opgehaald.</li>
 *   <li><b>Regel A12:</b> {@code TOO_YOUNG}, {@code TOO_LARGE} (listing) en de byte-guard tijdens het streamen.</li>
 *   <li><b>Fouten:</b> gewijzigd tijdens de overdracht, verkeerd wachtwoord, verkeerde hostsleutel, ontbrekende map:
 *       201 met een {@code FAILED}-run, geen levering, taak niet geblokkeerd.</li>
 *   <li><b>Voorcontroles:</b> ingetrokken credential, geen DC, onbekende taak, geen allowlist, geen sleutelring: 4xx
 *       zonder run.</li>
 *   <li><b>V1:</b> het wachtwoord komt in geen antwoord, runrij of logregel voor.</li>
 * </ul>
 */
class FetchRunTest extends FetchTestSupport {

    private Logger root;
    private ListAppender<ILoggingEvent> log;
    private final List<Logger> raised = new ArrayList<>();
    private final List<Level> originalLevels = new ArrayList<>();

    @BeforeEach
    void captureEveryLogLine() {
        raise("be.dda.catalogimport", Level.TRACE);
        raise("org.apache.sshd.client", Level.DEBUG);
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

    // --- L6: eerste run en daarna ------------------------------------------------------------------------------

    @Test
    void theFirstRunTakesOnlyTheMostRecentFileAndRegistersItLikeAnUpload() throws Exception {
        Chain chain = chain("FIRST");
        remoteFile(chain, "a.csv", hoursAgo(3), csv(1));
        remoteFile(chain, "b.csv", hoursAgo(2), csv(2));
        remoteFile(chain, "c.csv", hoursAgo(1), csv(3));
        long archivedBefore = archivedFileCount();

        String body = fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.triggerSource").value("MANUAL_FETCH"))
                .andExpect(jsonPath("$.triggeredBy").value(USER))
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.FETCHED))
                .andExpect(jsonPath("$.pendingFileCount").value(0))
                .andExpect(jsonPath("$.deliveryConfigurationVersionId").value(chain.dcVersionId()))
                .andExpect(jsonPath("$.batchStatus").value("SCREENED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(decisions(body)).containsEntry("a.csv", "OLDER_THAN_WATERMARK")
                .containsEntry("b.csv", "OLDER_THAN_WATERMARK").containsEntry("c.csv", "SELECTED").hasSize(3);
        long runId = longAt(body, "$.id");
        long deliveryId = longAt(body, "$.deliveryId");
        long batchId = longAt(body, "$.batchId");

        // Levering: SFTP, sleutel A4, gekoppeld aan deze run; bestand in het archief met de juiste inhoud en hash.
        Map<String, Object> delivery = jdbc.queryForMap("select * from delivery where id = ?", deliveryId);
        assertThat(delivery).containsEntry("source_kind", "SFTP");
        assertThat(((Number) delivery.get("task_run_id")).longValue()).isEqualTo(runId);
        assertThat(delivery.get("idempotency_key")).isEqualTo(expectedKey(chain, "c.csv"));
        Map<String, Object> file = jdbc.queryForMap("select * from delivery_file where delivery_id = ?", deliveryId);
        assertThat(file).containsEntry("file_name", "c.csv");
        Path archived = ARCHIVE_ROOT.resolve((String) file.get("archive_reference"));
        assertThat(Files.readString(archived, StandardCharsets.UTF_8)).isEqualTo(csv(3));
        assertThat(file.get("content_hash")).isEqualTo(sha256Hex(csv(3)));
        assertThat(archivedFileCount()).isEqualTo(archivedBefore + 1);
        Map<String, Object> batch = jdbc.queryForMap("select * from import_batch where id = ?", batchId);
        assertThat(((Number) batch.get("task_run_id")).longValue()).isEqualTo(runId);
        assertThat(batch).containsEntry("created_by", USER).containsEntry("created_by_subject", "test-sub-" + USER);

        // Run: waarneming van de gekozen levering, DC-versie, geen concurrency-token meer (afgesloten door de screening).
        Map<String, Object> run = jdbc.queryForMap("select * from task_run where id = ?", runId);
        assertThat(run).containsEntry("trigger_source", "MANUAL_FETCH").containsEntry("status", "COMPLETED");
        assertThat(run.get("concurrency_token")).isNull();
        assertThat(((Number) jdbc.queryForObject("select delivery_id from fetch_file_observation where task_run_id = ? "
                + "and decision = 'SELECTED'", Long.class, runId)).longValue()).isEqualTo(deliveryId);

        // Runlijst (READ, zonder waarnemingen) en rundetail (READ, met waarnemingen).
        String list = runList(chain.taskId()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(longAt(list, "$[0].id")).isEqualTo(runId);
        assertThat(readOrNull(list, "$[0].observations")).isNull();
        runDetail(runId).andExpect(status().isOk()).andExpect(jsonPath("$.observations.length()").value(3))
                .andExpect(jsonPath("$.deliveryId").value(deliveryId));
    }

    @Test
    void laterRunsFetchChronologicallyOneFilePerRunAndNeverAnythingOlderThanTheWatermark() throws Exception {
        Chain chain = chain("CHRONO");
        remoteFile(chain, "a.csv", hoursAgo(3), csv(1));
        remoteFile(chain, "c.csv", hoursAgo(1), csv(3));
        assertThat(decisions(fetchBody(chain.taskId()))).containsEntry("c.csv", "SELECTED");

        remoteFile(chain, "d.csv", minutesAgo(50), csv(4));
        remoteFile(chain, "e.csv", minutesAgo(40), csv(5));
        remoteFile(chain, "x-laat-geplaatst.csv", hoursAgo(5), csv(6));

        String second = fetchBody(chain.taskId());
        assertThat(JsonPathValue.string(second, "$.outcomeCode")).isEqualTo(FetchOutcomeCodes.FETCHED);
        assertThat(longAt(second, "$.pendingFileCount")).isEqualTo(1L);
        assertThat(decisions(second)).containsEntry("x-laat-geplaatst.csv", "OLDER_THAN_WATERMARK")
                .containsEntry("a.csv", "OLDER_THAN_WATERMARK").containsEntry("c.csv", "ALREADY_FETCHED")
                .containsEntry("d.csv", "SELECTED").containsEntry("e.csv", "DEFERRED");

        String third = fetchBody(chain.taskId());
        assertThat(longAt(third, "$.pendingFileCount")).isZero();
        assertThat(decisions(third)).containsEntry("d.csv", "ALREADY_FETCHED").containsEntry("e.csv", "SELECTED");

        String fourth = fetchBody(chain.taskId());
        assertThat(JsonPathValue.string(fourth, "$.status")).isEqualTo("COMPLETED");
        assertThat(JsonPathValue.string(fourth, "$.outcomeCode")).isEqualTo(FetchOutcomeCodes.NO_NEW_FILE);
        assertThat(readOrNull(fourth, "$.deliveryId")).isNull();
        assertThat(decisions(fourth)).doesNotContainValue("SELECTED")
                .containsEntry("x-laat-geplaatst.csv", "OLDER_THAN_WATERMARK");
        assertThat(deliveryCount(chain.taskId())).isEqualTo(3L);
        assertThat(jdbc.queryForList("select file_name from delivery_file f join delivery d on d.id = f.delivery_id "
                + "where d.task_id = ? order by d.id", String.class, chain.taskId()))
                .containsExactly("c.csv", "d.csv", "e.csv");
    }

    @Test
    void anEmptyDirectoryIsNoNewFile() throws Exception {
        Chain chain = chain("EMPTY");

        String body = fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.NO_NEW_FILE))
                .andExpect(jsonPath("$.pendingFileCount").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(decisions(body)).isEmpty();
        assertThat(runs.findByTaskIdAndConcurrencyTokenIsNotNull(chain.taskId())).isEmpty();
    }

    // --- A12: te jong, te groot, byte-guard ------------------------------------------------------------------------

    @Test
    void aTooYoungFileIsSkippedVisiblyAndFetchedOnceItIsOldEnough() throws Exception {
        Chain chain = chain("YOUNG");
        remoteFile(chain, "nieuw.csv", Instant.now().minusSeconds(10), csv(1));

        String first = fetchBody(chain.taskId());
        assertThat(JsonPathValue.string(first, "$.outcomeCode")).isEqualTo(FetchOutcomeCodes.NO_NEW_FILE);
        assertThat(decisions(first)).containsEntry("nieuw.csv", "TOO_YOUNG");
        assertThat(deliveryCount(chain.taskId())).isZero();

        Files.setLastModifiedTime(remotePath(chain, "nieuw.csv"), FileTime.from(hoursAgo(1)));
        String second = fetchBody(chain.taskId());
        assertThat(JsonPathValue.string(second, "$.outcomeCode")).isEqualTo(FetchOutcomeCodes.FETCHED);
        assertThat(decisions(second)).containsEntry("nieuw.csv", "SELECTED");
    }

    @Test
    void aFileLargerThanTheMaximumInTheListingIsTooLargeAndNotDownloaded() throws Exception {
        Chain chain = chain("LARGE", PASSWORD, fingerprint(), null, 100L);
        remoteFile(chain, "groot.csv", hoursAgo(1), csv(1) + "x".repeat(200));
        long archivedBefore = archivedFileCount();

        String body = fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.NO_NEW_FILE))
                .andReturn().getResponse().getContentAsString();

        assertThat(decisions(body)).containsEntry("groot.csv", "TOO_LARGE");
        assertThat(JsonPathValue.string(body, "$.outcomeMessage")).contains("TOO_LARGE");
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    @Test
    void aFileThatGrowsBeyondTheMaximumWhileStreamingFailsWithTheByteGuardAndLeavesNothing() throws Exception {
        Chain chain = chain("GUARD", PASSWORD, fingerprint(), null, 100L);
        remoteFile(chain, "groeit.csv", hoursAgo(1), csv(1));
        long archivedBefore = archivedFileCount();
        FetchRunService service = serviceWith(mutatingAfterChoice(chosen -> append(remotePath(chain, chosen.name()),
                "x".repeat(500))));

        TaskRunView run = service.fetch(chain.taskId(), ACTOR);

        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.outcomeCode()).isEqualTo(FetchOutcomeCodes.FETCH_FILE_TOO_LARGE);
        assertThat(run.deliveryId()).isNull();
        assertThat(run.observations()).singleElement().satisfies(o -> {
            assertThat(o.decision()).isEqualTo("SELECTED");
            assertThat(o.deliveryId()).isNull();
        });
        assertThat(deliveryCount(chain.taskId())).isZero();
        assertThat(archivedFileCount()).as("nothing half written stays in the archive").isEqualTo(archivedBefore);
        assertThat(runs.findByTaskIdAndConcurrencyTokenIsNotNull(chain.taskId())).isEmpty();
    }

    // --- Fouten aan de kant van de server: 201 met een FAILED-run --------------------------------------------------

    @Test
    void aFileChangedDuringTheTransferFailsAndIsFetchedByALaterRun() throws Exception {
        Chain chain = chain("CHANGED");
        remoteFile(chain, "wijzigt.csv", hoursAgo(2), csv(1));
        long archivedBefore = archivedFileCount();
        Instant rewritten = hoursAgo(1);
        FetchRunService service = serviceWith(mutatingAfterChoice(chosen -> touch(remotePath(chain, chosen.name()),
                rewritten)));

        TaskRunView failed = service.fetch(chain.taskId(), ACTOR);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.outcomeCode()).isEqualTo(FetchOutcomeCodes.FETCH_FILE_CHANGED_DURING_TRANSFER);
        assertThat(failed.deliveryId()).isNull();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);

        // Een volgende run haalt het (nu stabiele) bestand wél op, met de nieuwe wijzigingstijd in de sleutel.
        String next = fetchBody(chain.taskId());
        assertThat(JsonPathValue.string(next, "$.outcomeCode")).isEqualTo(FetchOutcomeCodes.FETCHED);
        long deliveryId = longAt(next, "$.deliveryId");
        assertThat(jdbc.queryForObject("select idempotency_key from delivery where id = ?", String.class, deliveryId))
                .isEqualTo(expectedKey(chain, "wijzigt.csv")).endsWith(":" + rewritten.getEpochSecond() + ":"
                        + csv(1).getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void aWrongPasswordIsAFailedRunWithoutDeliveryAndTheTaskIsNotBlocked() throws Exception {
        Chain chain = chain("AUTH", WRONG_PASSWORD, fingerprint(), null, null);
        remoteFile(chain, "levering.csv", hoursAgo(1), csv(1));

        String body = fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.SFTP_AUTHENTICATION_FAILED))
                .andReturn().getResponse().getContentAsString();

        assertThat(readOrNull(body, "$.deliveryId")).isNull();
        assertThat(readOrNull(body, "$.pendingFileCount")).as("nothing was listed").isNull();
        assertThat(decisions(body)).isEmpty();
        assertThat(deliveryCount(chain.taskId())).isZero();
        assertThat(runs.findByTaskIdAndConcurrencyTokenIsNotNull(chain.taskId())).isEmpty();
        // Niet geblokkeerd: een nieuwe poging maakt een nieuwe run, en ontkoppelen mag.
        long firstRun = longAt(body, "$.id");
        String again = fetchBody(chain.taskId());
        assertThat(longAt(again, "$.id")).isNotEqualTo(firstRun);
        assertThat(bindings.unbind(chain.taskId(), "Terug naar upload", ACTOR).deliveryConfigurationVersionId())
                .isNull();
    }

    @Test
    void anotherHostKeyIsAFailedRunWithoutAnyLoginAttempt() throws Exception {
        Chain chain = chain("HOSTKEY", PASSWORD, randomFingerprint(), null, null);
        remoteFile(chain, "levering.csv", hoursAgo(1), csv(1));
        int attempts = server.passwordAttempts();

        fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.SFTP_HOST_KEY_MISMATCH));

        assertThat(server.passwordAttempts()).isEqualTo(attempts);
        assertThat(deliveryCount(chain.taskId())).isZero();
    }

    @Test
    void aMissingRemoteDirectoryIsAFailedRun() throws Exception {
        Chain chain = chain("NODIR");
        Files.delete(sftpRoot.resolve(chain.directory().substring(1)));

        fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.REMOTE_DIRECTORY_NOT_FOUND));
    }

    // --- Voorcontroles: 4xx zonder run ---------------------------------------------------------------------------

    @Test
    void aRevokedCredentialIs409WithoutRunAndWithoutContactingTheServer() throws Exception {
        Chain chain = chain("REVOKED");
        remoteFile(chain, "levering.csv", hoursAgo(1), csv(1));
        credentialService.revoke(chain.credentialRef().toString(), "Leverancier gestopt", ACTOR);
        int attempts = server.passwordAttempts();

        fetch(chain.taskId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(FetchOutcomeCodes.CREDENTIAL_REVOKED));

        assertThat(runCount(chain.taskId())).isZero();
        assertThat(server.passwordAttempts()).isEqualTo(attempts);
    }

    @Test
    void withoutDeliveryConfigurationOrForAnUnknownTaskNothingIsStarted() throws Exception {
        long withoutConfiguration = task(link(unique("NODC"))).getId();

        fetch(withoutConfiguration).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(FetchRunService.CODE_NO_DELIVERY_CONFIGURATION));
        fetch(999_999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        assertThat(runCount(withoutConfiguration)).isZero();
    }

    @Test
    void withoutAllowlistOrKeyRingTheFetchIsRefusedWithoutRun() throws Exception {
        Chain chain = chain("NOCONF");
        remoteFile(chain, "levering.csv", hoursAgo(1), csv(1));
        FetchRunService withoutAllowlist = serviceWith(new FetchHostPolicy("", false), secrets, newConnector());
        FetchRunService withoutKeyRing = serviceWith(hostPolicy, new SecretsService("", ""), newConnector());

        Throwable notConfigured = catchThrowable(() -> withoutAllowlist.fetch(chain.taskId(), ACTOR));
        Throwable noSecrets = catchThrowable(() -> withoutKeyRing.fetch(chain.taskId(), ACTOR));

        assertThat(notConfigured).isInstanceOf(NotFoundException.class);
        assertThat(((NotFoundException) notConfigured).getCode()).isEqualTo(FetchOutcomeCodes.FETCH_NOT_CONFIGURED);
        assertThat(noSecrets).isInstanceOf(ConflictException.class);
        assertThat(((ConflictException) noSecrets).getCode()).isEqualTo("SECRETS_NOT_CONFIGURED");
        assertThat(runCount(chain.taskId())).isZero();
    }

    @Test
    void aReadOnlyUserMayNotFetchButMayReadTheRuns() throws Exception {
        Chain chain = chain("READER");

        mockMvc.perform(post(API + "/tasks/{id}/fetch-runs", chain.taskId()).with(as(USER, Permission.READ)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(runCount(chain.taskId())).isZero();
        runList(chain.taskId()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    // --- V1: geen wachtwoord in antwoord, runrij of log -----------------------------------------------------------

    @Test
    void noAnswerRunRowOrLogLineContainsThePasswordOrADigestOfIt() throws Exception {
        Chain ok = chain("LEAKOK");
        remoteFile(ok, "levering.csv", hoursAgo(1), csv(1));
        Chain wrong = chain("LEAKNOK", WRONG_PASSWORD, fingerprint(), null, null);
        remoteFile(wrong, "levering.csv", hoursAgo(1), csv(2));

        List<String> answers = List.of(fetchBody(ok.taskId()), fetchBody(wrong.taskId()), fetchBody(ok.taskId()));

        List<String> forbidden = List.of(PASSWORD, WRONG_PASSWORD, digest(PASSWORD), digest(WRONG_PASSWORD));
        for (String answer : answers) {
            for (String value : forbidden) {
                assertThat(answer).doesNotContain(value);
            }
            assertThat(answer).doesNotContain("SSH-2.0").doesNotContain("ciphertext").doesNotContain("127.0.0.1");
        }
        for (Map<String, Object> row : jdbc.queryForList("select * from task_run where task_id in (?, ?)",
                ok.taskId(), wrong.taskId())) {
            for (Object column : row.values()) {
                for (String value : forbidden) {
                    assertThat(String.valueOf(column)).doesNotContain(value);
                }
            }
        }
        assertThat(this.log.list).as("captured log lines").isNotEmpty();
        assertLogsDoNotContain(forbidden);
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    /** Een connector die na de keuze van de service (en vóór de download) het gekozen bestand wijzigt. */
    private static SftpConnector mutatingAfterChoice(Consumer<RemoteFile> mutation) {
        return new SftpConnector(java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(10), 10_000) {
            @Override
            public FetchResult fetchOne(FetchTarget target, String username, PinnedHostKey pinned,
                                        PasswordSource password, String remoteDirectory,
                                        RemoteFileSelection selection, long byteLimit, DownloadPlan plan) {
                return super.fetchOne(target, username, pinned, password, remoteDirectory, selection, byteLimit,
                        new DownloadPlan() {
                            @Override
                            public RemoteFile choose(Listing listing) {
                                RemoteFile chosen = plan.choose(listing);
                                if (chosen != null) {
                                    mutation.accept(chosen);
                                }
                                return chosen;
                            }

                            @Override
                            public void receive(RemoteFile file, InputStream content) {
                                plan.receive(file, content);
                            }
                        });
            }
        };
    }

    private static void append(Path file, String more) {
        try {
            FileTime before = Files.getLastModifiedTime(file);
            Files.writeString(file, more, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            Files.setLastModifiedTime(file, before);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void touch(Path file, Instant modified) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(modified));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String sha256Hex(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Zoals een library een wachtwoord zou "vingerafdrukken": SHA-256 over de UTF-8-bytes, base64 zonder opvulling. */
    private static String digest(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().withoutPadding().encodeToString(hash);
    }

    private void raise(String loggerName, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        raised.add(logger);
        originalLevels.add(logger.getLevel());
        logger.setLevel(level);
    }

    private void assertLogsDoNotContain(List<String> forbidden) {
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

    /** Kleine leeshulp: een tekstwaarde uit een JSON-antwoord. */
    private static final class JsonPathValue {
        private static String string(String body, String path) {
            Object value = readOrNull(body, path);
            return value == null ? null : value.toString();
        }
    }
}
