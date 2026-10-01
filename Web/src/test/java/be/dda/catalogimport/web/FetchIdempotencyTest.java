package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.FetchOutcomeCodes;
import be.dda.catalogimport.service.FetchRunService;
import be.dda.catalogimport.service.FetchTarget;
import be.dda.catalogimport.service.SftpConnector;
import be.dda.catalogimport.service.SftpConnector.DownloadPlan;
import be.dda.catalogimport.service.SftpConnector.FetchResult;
import be.dda.catalogimport.service.SftpConnector.PasswordSource;
import be.dda.catalogimport.service.SftpConnector.PinnedHostKey;
import be.dda.catalogimport.service.support.RemoteFileSelection;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bouwstap K-4b: idempotentie en gelijktijdigheid van de ophaalrun en het racevenster A10
 * ({@code docs/design/leveringsconfiguratie-design.md} par. 4.2 en 11; beslissingslog 2026-09-29 A4, L6, A10, LC-2 open
 * punt 2).
 *
 * <ul>
 *   <li><b>Regel A4:</b> hetzelfde remote object (pad, wijzigingstijd, grootte) is één levering per taak; een tweede run
 *       is {@code NO_NEW_FILE} met {@code ALREADY_FETCHED}. Nieuwe wijzigingstijd of grootte = nieuwe levering.
 *       <b>Data:</b> {@code uk_delivery_idempotency} (laatste verdediging), {@code fetch_file_observation}.</li>
 *   <li><b>Regel:</b> hoogstens één lopende run per taak, ook bij gelijktijdige aanvragen (rijslot +
 *       {@code uk_task_run_concurrency}); tijdens de run kan de koppeling niet wijzigen.</li>
 *   <li><b>Regel A4/L6:</b> herkoppelen naar een nieuwe DC-versie haalt niet alles opnieuw op (sleutel zonder DC-versie,
 *       watermark per taak).</li>
 *   <li><b>Regel A10 (racevenster):</b> een upload die haar voorcontrole haalt vlak vóór een koppeling commit, start
 *       nooit een run op die taak: de registratie wacht op het rijslot en ziet daarna de koppeling.</li>
 * </ul>
 */
class FetchIdempotencyTest extends FetchTestSupport {

    @Test
    void theSameFileTwiceIsOneDeliveryAndTheSecondRunIsNoNewFile() throws Exception {
        Chain chain = chain("TWICE");
        remoteFile(chain, "levering.csv", hoursAgo(1), csv(1));
        long archivedBefore = archivedFileCount();

        fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.FETCHED));
        String second = fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.NO_NEW_FILE))
                .andReturn().getResponse().getContentAsString();

        assertThat(decisions(second)).containsEntry("levering.csv", "ALREADY_FETCHED");
        assertThat(readOrNull(second, "$.deliveryId")).isNull();
        assertThat(deliveryCount(chain.taskId())).isEqualTo(1L);
        assertThat(runCount(chain.taskId())).isEqualTo(2L);
        assertThat(archivedFileCount()).as("the second run downloads nothing").isEqualTo(archivedBefore + 1);
    }

    @Test
    void aNewModificationTimeOrANewSizeIsANewDelivery() throws Exception {
        Chain chain = chain("MTIME");
        remoteFile(chain, "levering.csv", hoursAgo(2), csv(1));
        fetch(chain.taskId()).andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.FETCHED));

        // Nieuwe wijzigingstijd, zelfde inhoud: een nieuw remote object (A4), dus een nieuwe levering.
        Files.setLastModifiedTime(remotePath(chain, "levering.csv"), FileTime.from(hoursAgo(1)));
        fetch(chain.taskId()).andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.FETCHED));

        // Zelfde wijzigingstijd en naam als de watermark, andere grootte: ook nieuw (nooit "ouder dan de watermark").
        FileTime kept = Files.getLastModifiedTime(remotePath(chain, "levering.csv"));
        Files.writeString(remotePath(chain, "levering.csv"), "ACME;G1;R9;11,00;Artikel 9\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        Files.setLastModifiedTime(remotePath(chain, "levering.csv"), kept);
        fetch(chain.taskId()).andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.FETCHED));

        List<String> keys = jdbc.queryForList("select idempotency_key from delivery where task_id = ? order by id",
                String.class, chain.taskId());
        assertThat(keys).hasSize(3).doesNotHaveDuplicates();
        assertThat(keys.get(2)).isEqualTo(expectedKey(chain, "levering.csv"));
    }

    @Test
    void rebindingToANewDeliveryConfigurationVersionDoesNotFetchEverythingAgain() throws Exception {
        Chain chain = chain("REBIND");
        remoteFile(chain, "a.csv", hoursAgo(3), csv(1));
        remoteFile(chain, "b.csv", hoursAgo(2), csv(2));
        remoteFile(chain, "c.csv", hoursAgo(1), csv(3));
        fetch(chain.taskId()).andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.FETCHED));

        long newVersion = dcVersion(chain.profileVersionId(), chain.directory(), null, null);
        bindings.bind(chain.taskId(), newVersion, "Nieuwe configuratie", ACTOR);

        String body = fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.NO_NEW_FILE))
                .andExpect(jsonPath("$.deliveryConfigurationVersionId").value(newVersion))
                .andReturn().getResponse().getContentAsString();

        assertThat(decisions(body)).containsEntry("c.csv", "ALREADY_FETCHED")
                .containsEntry("a.csv", "OLDER_THAN_WATERMARK").containsEntry("b.csv", "OLDER_THAN_WATERMARK");
        assertThat(deliveryCount(chain.taskId())).isEqualTo(1L);
    }

    /**
     * Twee gelijktijdige "Nu ophalen" op dezelfde taak. De winnaar blijft (deterministisch) in de verbinding hangen tot
     * de verliezer geantwoord heeft: die verliezer botst dus gegarandeerd op een lopende run, niet op toeval.
     */
    @Test
    void twoConcurrentFetchesOnTheSameTaskGiveExactlyOneRun() throws Exception {
        Chain chain = chain("CONC");
        remoteFile(chain, "levering.csv", hoursAgo(1), csv(1));
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch loserAnswered = new CountDownLatch(1);
        FetchRunService slow = serviceWith(holdingConnector(loserAnswered));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    go.await();
                    try {
                        return slow.fetch(chain.taskId(), ACTOR).outcomeCode();
                    } catch (ConflictException conflict) {
                        loserAnswered.countDown();
                        return conflict.getCode();
                    }
                }));
            }
            go.countDown();
            assertThat(loserAnswered.await(60, TimeUnit.SECONDS)).isTrue();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(60, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder(FetchOutcomeCodes.FETCHED,
                    FetchRunService.CODE_RUN_IN_PROGRESS);
        } finally {
            loserAnswered.countDown();
            pool.shutdownNow();
        }
        assertThat(runCount(chain.taskId())).isEqualTo(1L);
        assertThat(deliveryCount(chain.taskId())).isEqualTo(1L);
    }

    /** Tijdens een lopende ophaalrun: een tweede fetch (HTTP) en (ont)koppelen zijn 409 TASK_RUN_IN_PROGRESS. */
    @Test
    void whileAFetchRunsNeitherASecondFetchNorABindingChangeIsPossible() throws Exception {
        Chain chain = chain("BUSY");
        remoteFile(chain, "levering.csv", hoursAgo(1), csv(1));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        FetchRunService slow = serviceWith(new SftpConnector(Duration.ofSeconds(3), Duration.ofSeconds(5),
                Duration.ofSeconds(10), 10_000) {
            @Override
            public FetchResult fetchOne(FetchTarget target, String username, PinnedHostKey pinned,
                                        PasswordSource password, String remoteDirectory,
                                        RemoteFileSelection selection, long byteLimit, DownloadPlan plan) {
                entered.countDown();
                awaitQuietly(release);
                return super.fetchOne(target, username, pinned, password, remoteDirectory, selection, byteLimit, plan);
            }
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = pool.submit(() -> slow.fetch(chain.taskId(), ACTOR).outcomeCode());
            assertThat(entered.await(60, TimeUnit.SECONDS)).isTrue();

            fetch(chain.taskId()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(FetchRunService.CODE_RUN_IN_PROGRESS));
            Throwable unbind = catchThrowable(() -> bindings.unbind(chain.taskId(), "Tijdens de run", ACTOR));
            assertThat(unbind).isInstanceOf(ConflictException.class);
            assertThat(((ConflictException) unbind).getCode()).isEqualTo("TASK_RUN_IN_PROGRESS");

            release.countDown();
            assertThat(first.get(60, TimeUnit.SECONDS)).isEqualTo(FetchOutcomeCodes.FETCHED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(runCount(chain.taskId())).isEqualTo(1L);
    }

    /**
     * Racevenster A10 (LC-2 open punt 2): de koppeling staat open (niet gecommit) op het moment dat een upload haar
     * voorcontrole doet; de upload ziet dus nog geen Leveringsconfiguratie, archiveert en wil registreren. De registratie
     * wacht op hetzelfde rijslot als de koppeling en ziet na diens commit de configuratie: 409
     * {@code TASK_HAS_DELIVERY_CONFIGURATION}, geen run, geen levering, archief opgeruimd.
     */
    @Test
    void anUploadThatPassedItsPreCheckJustBeforeABindingCommitsNeverStartsARunOnThatTask() throws Exception {
        CatalogImportTask task = task(link(unique("RACE")));
        long dcVersionId = unboundDcVersion("RACEDC");
        long archivedBefore = archivedFileCount();
        CountDownLatch bound = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate bindingTransaction = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        MvcResult upload;
        try {
            Future<?> binder = pool.submit(() -> bindingTransaction.executeWithoutResult(status -> {
                bindings.bind(task.getId(), dcVersionId, "Gelijktijdig met een upload", ACTOR);
                bound.countDown();
                awaitQuietly(release);
            }));
            assertThat(bound.await(60, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> uploader = pool.submit(() -> mockMvc.perform(
                    multipart(API + "/tasks/{id}/deliveries", task.getId())
                            .file(new MockMultipartFile("file", "race.csv", "text/csv",
                                    csv(1).getBytes(StandardCharsets.UTF_8)))
                            .param("deliveryReference", unique("REF")).with(as(USER, Permission.MANAGE)))
                    .andReturn());
            // Wacht tot de registratie van de upload op het rijslot wacht (of, zonder slot, al klaar zou zijn).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            boolean waitingOnLock = false;
            while (!uploader.isDone() && !waitingOnLock && System.nanoTime() < deadline) {
                waitingOnLock = lockWaits() > 0;
                Thread.sleep(50);
            }
            assertThat(waitingOnLock).as("the upload registration waits on the task row lock of the binding").isTrue();

            release.countDown();
            binder.get(60, TimeUnit.SECONDS);
            upload = uploader.get(60, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(upload.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(upload.getResponse().getContentAsString(), "$.code"))
                .isEqualTo(DeliveryIntakeService.CODE_TASK_HAS_DELIVERY_CONFIGURATION);
        assertThat(runCount(task.getId())).as("never an upload run on a task with a delivery configuration").isZero();
        assertThat(deliveryCount(task.getId())).isZero();
        assertThat(archivedFileCount()).as("the archived upload was cleaned up").isEqualTo(archivedBefore);
        assertThat(jdbc.queryForObject("select delivery_configuration_version_id from catalog_import_task where id = ?",
                Long.class, task.getId())).isEqualTo(dcVersionId);
    }

    /** K-4b: nieuwe upload- en servermapruns leggen hun herkomst vast; antwoord en statuscode blijven ongewijzigd. */
    @Test
    void uploadAndServerDirectoryRunsRecordTheirTriggerSource() throws Exception {
        CatalogImportTask uploadTask = task(link(unique("TSUP")));
        CatalogImportTask localTask = task(link(unique("TSLOC")));
        String localName = "k4b-" + UUID.randomUUID() + ".csv";
        Files.writeString(LOCAL_SOURCE_ROOT.resolve(localName), csv(2), StandardCharsets.UTF_8);

        mockMvc.perform(multipart(API + "/tasks/{id}/deliveries", uploadTask.getId())
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                csv(1).getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", unique("REF")).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SCREENED"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(API + "/tasks/{id}/deliveries/local-source", localTask.getId())
                        .with(as(USER, Permission.MANAGE))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"" + localName + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SCREENED"));

        assertThat(jdbc.queryForObject("select trigger_source from task_run where task_id = ?", String.class,
                uploadTask.getId())).isEqualTo("UPLOAD");
        assertThat(jdbc.queryForObject("select trigger_source from task_run where task_id = ?", String.class,
                localTask.getId())).isEqualTo("LOCAL_DIRECTORY");
        assertThat(jdbc.queryForObject("select count(*) from task_run where task_id in (?, ?) and "
                + "(delivery_configuration_version_id is not null or outcome_code is not null "
                + "or pending_file_count is not null)", Long.class, uploadTask.getId(), localTask.getId()))
                .as("the fetch columns stay empty for upload and server directory").isZero();
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    /** Een connector die in de verbinding blijft hangen tot {@code release} vrijgegeven is. */
    private static SftpConnector holdingConnector(CountDownLatch release) {
        return new SftpConnector(Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10), 10_000) {
            @Override
            public FetchResult fetchOne(FetchTarget target, String username, PinnedHostKey pinned,
                                        PasswordSource password, String remoteDirectory,
                                        RemoteFileSelection selection, long byteLimit, DownloadPlan plan) {
                awaitQuietly(release);
                return super.fetchOne(target, username, pinned, password, remoteDirectory, selection, byteLimit, plan);
            }
        };
    }

    /** Een Leveringsconfiguratie-versie op een eigen (lege) map, nog aan geen taak gekoppeld. */
    private long unboundDcVersion(String prefix) throws Exception {
        String directory = "/" + unique(prefix).toLowerCase();
        Files.createDirectories(sftpRoot.resolve(directory.substring(1)));
        UUID credentialRef = credentialService.create("K4b " + SEQUENCE.incrementAndGet(), "SFTP_PASSWORD",
                "127.0.0.1", PASSWORD, "Ophaaltest", ACTOR).credentialRef();
        return dcVersion(profileVersion(credentialRef, fingerprint()), directory, null, null);
    }

    /** Sessies van deze database die op een slot wachten (PostgreSQL). */
    private long lockWaits() {
        Long waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
                + "and wait_event_type = 'Lock'", Long.class);
        return waiting == null ? 0L : waiting;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
