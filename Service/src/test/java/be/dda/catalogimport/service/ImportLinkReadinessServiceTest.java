package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.service.ChainConfigurationChecks.ActivationReport;
import be.dda.catalogimport.service.ChainConfigurationChecks.IntakeConfiguration;
import be.dda.catalogimport.service.ChainConfigurationChecks.Problem;
import be.dda.catalogimport.service.ChainConfigurationChecks.ReportedProblem;
import be.dda.catalogimport.service.ImportLinkReadinessService.CheckStatus;
import be.dda.catalogimport.service.ImportLinkReadinessService.LinkReadiness;
import be.dda.catalogimport.service.ImportLinkReadinessService.LinkReadinessSummary;
import be.dda.catalogimport.service.ImportLinkReadinessService.ReadinessCheck;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImportLinkReadinessServiceTest {

    private ImportLinkRepository links;
    private CatalogImportTaskRepository tasks;
    private ImportDefinitionRevisionRepository revisions;
    private ImportDefinition definition;
    private ChainConfigurationChecks checks;
    private ImportLink link;
    private ImportDefinitionRevision active;
    private CatalogImportTask task;
    private ImportLinkReadinessService service;

    @BeforeEach
    void setUp() {
        links = mock(ImportLinkRepository.class);
        tasks = mock(CatalogImportTaskRepository.class);
        checks = mock(ChainConfigurationChecks.class);
        revisions = mock(ImportDefinitionRevisionRepository.class);
        link = mock(ImportLink.class);
        definition = mock(ImportDefinition.class);
        active = mock(ImportDefinitionRevision.class);
        task = mock(CatalogImportTask.class);
        when(definition.getId()).thenReturn(7L);
        when(link.getId()).thenReturn(1L);
        when(link.isActive()).thenReturn(true);
        when(link.getLibraryCode()).thenReturn("LIB");
        when(link.getImportDefinition()).thenReturn(definition);
        when(active.getId()).thenReturn(11L);
        when(task.getId()).thenReturn(21L);
        when(links.findById(1L)).thenReturn(Optional.of(link));
        when(tasks.findByImportLinkIdOrderByIdAsc(1L)).thenReturn(List.of(task));
        when(checks.manualIntakeProblems(task)).thenReturn(List.of());
        service = new ImportLinkReadinessService(links, revisions, tasks, checks);
    }

    @Test
    void readyWhenNoProblem() {
        when(checks.intakeConfiguration(any(), anyString())).thenReturn(new IntakeConfiguration(active, List.of()));

        LinkReadiness result = service.readiness(1L);

        assertThat(result.ready()).isTrue();
        assertThat(result.linkId()).isEqualTo(1L);
        assertThat(result.checks()).extracting(ReadinessCheck::code).containsExactly(
                ImportLinkReadinessService.READY_LINK_ACTIVE,
                ImportLinkReadinessService.READY_ACTIVE_REVISION,
                ImportLinkReadinessService.READY_PRICE_FIELD,
                ImportLinkReadinessService.READY_LINK_BOOKMARKS,
                ImportLinkReadinessService.READY_TASK_ACCEPTS_UPLOAD,
                ImportLinkReadinessService.INFO_LIBRARY_NOT_VERIFIED);
        assertThat(result.checks()).noneMatch(check -> check.status() == CheckStatus.PROBLEM);
    }

    @Test
    void notReadyWhenPriceFieldMissing() {
        Problem problem = new Problem(ChainConfigurationChecks.CODE_PRICE_FIELD_MISSING, "no price field",
                new ConflictException(ChainConfigurationChecks.CODE_PRICE_FIELD_MISSING, "no price field"));
        when(checks.intakeConfiguration(any(), anyString()))
                .thenReturn(new IntakeConfiguration(active, List.of(problem)));

        LinkReadiness result = service.readiness(1L);

        assertThat(result.ready()).isFalse();
        assertThat(result.checks()).anySatisfy(check -> {
            assertThat(check.code()).isEqualTo(ChainConfigurationChecks.CODE_PRICE_FIELD_MISSING);
            assertThat(check.status()).isEqualTo(CheckStatus.PROBLEM);
        });
        assertThat(result.checks()).extracting(ReadinessCheck::code)
                .doesNotContain(ImportLinkReadinessService.READY_PRICE_FIELD);
    }

    @Test
    void notReadyWhenLinkHasNoTask() {
        when(checks.intakeConfiguration(any(), anyString())).thenReturn(new IntakeConfiguration(active, List.of()));
        when(tasks.findByImportLinkIdOrderByIdAsc(1L)).thenReturn(List.of());

        LinkReadiness result = service.readiness(1L);

        assertThat(result.ready()).isFalse();
        assertThat(result.checks()).extracting(ReadinessCheck::code)
                .contains(ImportLinkReadinessService.LINK_HAS_NO_TASK);
    }

    @Test
    void unknownLinkIsNotFound() {
        when(links.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readiness(2L))
                .isInstanceOfSatisfying(NotFoundException.class, e -> assertThat(e.getCode()).isEqualTo("LINK_NOT_FOUND"));
    }

    // --- Bulk (beslissingslog 2026-10-23 "API-uitbreiding voor de inrichtingsschermen", punt 4) -----------------------

    /**
     * Tien koppelingen op één gedeelde definitie zonder actieve revisie, met één concept: de concepten worden één keer
     * gelezen en de activatiecontrole één keer gedaan; koppelingen en taken elk in één query. Per koppeling blijven de
     * intakeconfiguratie en de LINK-bookmarks.
     */
    @Test
    void tenLinksOnOneSharedDefinitionCheckTheDraftOnlyOnce() {
        ImportDefinitionRevision draft = draft(31L, 1);
        when(revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(7L)).thenReturn(List.of(draft));
        ActivationReport report = new ActivationReport(List.of(configProblem("CONFIG_PRICE_FIELD_MISSING")), List.of());
        when(checks.activationProblemsAll(eq(draft), any())).thenReturn(report);
        when(checks.intakeConfiguration(any(), anyString())).thenReturn(noActiveRevision());
        when(checks.requiredLinkBookmarkProblem(any(), any(), anyString())).thenReturn(Optional.empty());
        List<ImportLink> tenLinks = new ArrayList<>();
        List<CatalogImportTask> tenTasks = new ArrayList<>();
        for (long id = 101; id <= 110; id++) {
            ImportLink each = link(id, "EUR");
            tenLinks.add(each);
            tenTasks.add(task(1000 + id, each));
        }
        List<Long> ids = LongStream.rangeClosed(101, 110).boxed().toList();
        when(links.findAllById(ids)).thenReturn(tenLinks);
        when(tasks.findByImportLinkIdInOrderByImportLinkIdAscIdAsc(ids)).thenReturn(tenTasks);

        List<LinkReadinessSummary> result = service.readinessSummaries(ids);

        assertThat(result).extracting(LinkReadinessSummary::linkId).containsExactlyElementsOf(ids);
        assertThat(result).allSatisfy(summary -> {
            assertThat(summary.ready()).isFalse();
            assertThat(summary.problems()).extracting(ReadinessCheck::code)
                    .containsExactly("NO_ACTIVE_REVISION", "CONFIG_PRICE_FIELD_MISSING");
            assertThat(summary.problems()).allMatch(check -> check.status() == CheckStatus.PROBLEM);
        });
        verify(checks, times(1)).activationProblemsAll(any(), any());
        verify(revisions, times(1)).findByImportDefinitionIdOrderByRevisionNumberDesc(anyLong());
        verify(links, times(1)).findAllById(any());
        verify(tasks, times(1)).findByImportLinkIdInOrderByImportLinkIdAscIdAsc(anyCollection());
        verify(tasks, never()).findByImportLinkIdOrderByIdAsc(anyLong());
        verify(checks, times(10)).intakeConfiguration(any(), anyString());
        verify(checks, times(10)).requiredLinkBookmarkProblem(any(), eq(draft), anyString());
    }

    /** De valuta hoort bij de sleutel: twee valuta's op hetzelfde concept geven twee controles, elk met hun valuta. */
    @Test
    void theActivationCheckIsSharedPerDraftAndCurrencyOnly() {
        ImportDefinitionRevision draft = draft(31L, 1);
        when(revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(7L)).thenReturn(List.of(draft));
        when(checks.activationProblemsAll(any(), any())).thenReturn(new ActivationReport(List.of(), List.of()));
        when(checks.intakeConfiguration(any(), anyString())).thenReturn(noActiveRevision());
        when(checks.requiredLinkBookmarkProblem(any(), any(), anyString())).thenReturn(Optional.empty());
        List<ImportLink> four = List.of(link(201, "EUR"), link(202, "USD"), link(203, "EUR"), link(204, null));
        List<Long> ids = List.of(201L, 202L, 203L, 204L);
        when(links.findAllById(ids)).thenReturn(four);
        when(tasks.findByImportLinkIdInOrderByImportLinkIdAscIdAsc(ids)).thenReturn(List.of());

        service.readinessSummaries(ids);

        verify(checks, times(1)).activationProblemsAll(draft, "EUR");
        verify(checks, times(1)).activationProblemsAll(draft, "USD");
        verify(checks, times(1)).activationProblemsAll(draft, null);
        verify(checks, times(3)).activationProblemsAll(any(), any());
    }

    /** Pariteit op serviceniveau: oordeel en PROBLEM-regels van de bulk zijn die van de enkelvoudige controle. */
    @Test
    void theBulkVerdictAndProblemsEqualTheSingleReadiness() {
        Problem price = new Problem(ChainConfigurationChecks.CODE_PRICE_FIELD_MISSING, "no price field",
                new ConflictException(ChainConfigurationChecks.CODE_PRICE_FIELD_MISSING, "no price field"));
        when(checks.intakeConfiguration(any(), anyString()))
                .thenReturn(new IntakeConfiguration(active, List.of(price)));
        when(links.findAllById(List.of(1L))).thenReturn(List.of(link));
        when(task.getImportLink()).thenReturn(link);
        when(tasks.findByImportLinkIdInOrderByImportLinkIdAscIdAsc(List.of(1L))).thenReturn(List.of(task));

        LinkReadiness single = service.readiness(1L);
        List<LinkReadinessSummary> bulk = service.readinessSummaries(List.of(1L));

        assertThat(bulk).singleElement().satisfies(summary -> {
            assertThat(summary.linkId()).isEqualTo(single.linkId());
            assertThat(summary.ready()).isEqualTo(single.ready()).isFalse();
            assertThat(summary.problems()).containsExactlyElementsOf(single.checks().stream()
                    .filter(check -> check.status() == CheckStatus.PROBLEM).toList());
        });
    }

    @Test
    void theRequestOrderIsKeptAndDuplicatesAreAnsweredOnce() {
        when(checks.intakeConfiguration(any(), anyString())).thenReturn(new IntakeConfiguration(active, List.of()));
        ImportLink three = link(3, null);
        ImportLink five = link(5, null);
        CatalogImportTask taskOfThree = task(30, three);
        when(links.findAllById(List.of(5L, 3L))).thenReturn(List.of(three, five));
        when(tasks.findByImportLinkIdInOrderByImportLinkIdAscIdAsc(List.of(5L, 3L)))
                .thenReturn(List.of(taskOfThree));

        List<LinkReadinessSummary> result = service.readinessSummaries(List.of(5L, 3L, 5L, 3L));

        assertThat(result).extracting(LinkReadinessSummary::linkId).containsExactly(5L, 3L);
        assertThat(result.get(0).ready()).isFalse();
        assertThat(result.get(0).problems()).extracting(ReadinessCheck::code)
                .containsExactly(ImportLinkReadinessService.LINK_HAS_NO_TASK);
        assertThat(result.get(1).ready()).isTrue();
        assertThat(result.get(1).problems()).isEmpty();
    }

    @Test
    void anEmptyMissingTooLongOrNullContainingListIsABadRequestWithAStableCode() {
        List<Long> tooMany = LongStream.rangeClosed(1, 101).boxed().toList();
        List<Long> withNull = Arrays.asList(1L, null);
        for (List<Long> invalid : Arrays.asList(null, List.<Long>of(), tooMany, withNull)) {
            assertThatThrownBy(() -> service.readinessSummaries(invalid))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            e -> assertThat(e.getCode()).isEqualTo("READINESS_LINK_IDS_INVALID"));
        }
        verify(links, never()).findAllById(any());
    }

    /** Grens: precies 100 opgegeven ids (dubbels meegeteld) is toegelaten. */
    @Test
    void exactlyOneHundredIdsIsAllowed() {
        when(checks.intakeConfiguration(any(), anyString())).thenReturn(new IntakeConfiguration(active, List.of()));
        List<Long> hundred = new ArrayList<>(LongStream.rangeClosed(1, 99).boxed().toList());
        hundred.add(1L);
        List<Long> distinct = LongStream.rangeClosed(1, 99).boxed().toList();
        List<ImportLink> found = distinct.stream().map(id -> link(id, null)).toList();
        when(links.findAllById(distinct)).thenReturn(found);
        when(tasks.findByImportLinkIdInOrderByImportLinkIdAscIdAsc(distinct)).thenReturn(List.of());

        assertThat(service.readinessSummaries(hundred)).hasSize(99);
    }

    @Test
    void theFirstUnknownIdInRequestOrderIsNotFound() {
        ImportLink three = link(3, null);
        when(links.findAllById(List.of(3L, 8L, 9L))).thenReturn(List.of(three));

        assertThatThrownBy(() -> service.readinessSummaries(List.of(3L, 8L, 9L)))
                .isInstanceOfSatisfying(NotFoundException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("LINK_NOT_FOUND");
                    assertThat(e.getMessage()).isEqualTo("Import link 8 does not exist");
                });
        verify(tasks, never()).findByImportLinkIdInOrderByImportLinkIdAscIdAsc(anyCollection());
    }

    private ImportLink link(long id, String currency) {
        ImportLink each = mock(ImportLink.class);
        when(each.getId()).thenReturn(id);
        when(each.isActive()).thenReturn(true);
        when(each.getLibraryCode()).thenReturn("LIB");
        when(each.getImportDefinition()).thenReturn(definition);
        when(each.getDefaultCurrency()).thenReturn(currency);
        return each;
    }

    private CatalogImportTask task(long id, ImportLink owner) {
        CatalogImportTask each = mock(CatalogImportTask.class);
        when(each.getId()).thenReturn(id);
        when(each.getImportLink()).thenReturn(owner);
        when(checks.manualIntakeProblems(each)).thenReturn(List.of());
        return each;
    }

    private static ImportDefinitionRevision draft(long id, int number) {
        ImportDefinitionRevision draft = mock(ImportDefinitionRevision.class);
        when(draft.getId()).thenReturn(id);
        when(draft.getRevisionNumber()).thenReturn(number);
        when(draft.getStatus()).thenReturn(RevisionStatus.DRAFT);
        return draft;
    }

    private static IntakeConfiguration noActiveRevision() {
        return new IntakeConfiguration(null, List.of(new Problem(ChainConfigurationChecks.CODE_NO_ACTIVE_REVISION,
                "no active revision", new ConflictException(ChainConfigurationChecks.CODE_NO_ACTIVE_REVISION,
                "no active revision"))));
    }

    private static ReportedProblem configProblem(String code) {
        return new ReportedProblem(new Problem(code, code + ": problem", new BadRequestException(code, "problem")),
                null, null);
    }
}
