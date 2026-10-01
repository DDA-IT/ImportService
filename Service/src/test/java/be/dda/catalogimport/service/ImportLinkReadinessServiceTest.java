package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.service.ChainConfigurationChecks.IntakeConfiguration;
import be.dda.catalogimport.service.ChainConfigurationChecks.Problem;
import be.dda.catalogimport.service.ImportLinkReadinessService.CheckStatus;
import be.dda.catalogimport.service.ImportLinkReadinessService.LinkReadiness;
import be.dda.catalogimport.service.ImportLinkReadinessService.ReadinessCheck;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImportLinkReadinessServiceTest {

    private ImportLinkRepository links;
    private CatalogImportTaskRepository tasks;
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
        ImportDefinitionRevisionRepository revisions = mock(ImportDefinitionRevisionRepository.class);
        link = mock(ImportLink.class);
        ImportDefinition definition = mock(ImportDefinition.class);
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
}
