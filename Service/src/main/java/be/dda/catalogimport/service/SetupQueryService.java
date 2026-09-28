package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alleen-lezen inrichtingslijsten voor scherm 1a (S1-B1, beslissingslog 27/09 "Heropening scherm 1a/1b",
 * keuze A1): bronorganisatie → definitie → revisie, tot op koppelingenniveau (dat laatste blijft
 * {@link ImportLinkQueryService}). Bewust een eigen, kleine service naast het vlaggedekte
 * {@link SetupService} — die twee blijven ongemoeid. Schrijft niets.
 * <p>
 * Paginering: {@code page} 0-gebaseerd, {@code size} standaard {@value #DEFAULT_PAGE_SIZE} en begrensd
 * tot {@value #MAX_PAGE_SIZE}, zelfde contract als {@link ImportLinkQueryService}.
 */
@Service
@Transactional(readOnly = true)
public class SetupQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    public record SourceOrganisationRow(long id, String code, String name, String type, boolean active) {

        private static SourceOrganisationRow of(SourceOrganisation organisation) {
            return new SourceOrganisationRow(organisation.getId(), organisation.getCode(), organisation.getName(),
                    organisation.getOrganisationType().name(), organisation.isActive());
        }
    }

    /** {@code activeRevisionId} is {@code null} als er geen {@link RevisionStatus#ACTIVE}-revisie is. */
    public record DefinitionRow(long id, String code, String name, String usageType, long sourceOrganisationId,
                                String sourceOrganisationCode, Long activeRevisionId) {
    }

    public record RevisionRow(long id, long definitionId, int revisionNumber, String status) {

        private static RevisionRow of(ImportDefinitionRevision revision) {
            return new RevisionRow(revision.getId(), revision.getImportDefinition().getId(),
                    revision.getRevisionNumber(), revision.getStatus().name());
        }
    }

    private final SourceOrganisationRepository organisations;
    private final ImportDefinitionRepository definitions;
    private final ImportDefinitionRevisionRepository revisions;

    public SetupQueryService(SourceOrganisationRepository organisations, ImportDefinitionRepository definitions,
                             ImportDefinitionRevisionRepository revisions) {
        this.organisations = organisations;
        this.definitions = definitions;
        this.revisions = revisions;
    }

    /**
     * Alle bronorganisaties, oplopend op {@code code}, optioneel gefilterd op {@code active}.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<SourceOrganisationRow> listSourceOrganisations(Boolean active, Integer page, Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<SourceOrganisation> result = organisations.findOrganisationRows(active, pageRequest);
        return PageResult.of(result, SourceOrganisationRow::of);
    }

    /**
     * Alle importdefinities, oplopend op {@code code}, optioneel gefilterd op bronorganisatie en
     * gebruikstype.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<DefinitionRow> listDefinitions(Long sourceOrganisationId, DefinitionUsageType usageType,
                                                      Integer page, Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<ImportDefinition> result = definitions.findDefinitionRows(sourceOrganisationId, usageType, pageRequest);
        return PageResult.of(result, this::definitionRow);
    }

    /**
     * Alle revisies van één definitie, oplopend op {@code revisionNumber}.
     *
     * @throws NotFoundException        {@code DEFINITION_NOT_FOUND}
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<RevisionRow> listRevisions(long definitionId, Integer page, Integer size) {
        if (!definitions.existsById(definitionId)) {
            throw new NotFoundException("DEFINITION_NOT_FOUND",
                    "Import definition " + definitionId + " does not exist");
        }
        PageRequest pageRequest = pageRequest(page, size);
        Page<ImportDefinitionRevision> result = revisions.findByDefinitionId(definitionId, pageRequest);
        return PageResult.of(result, RevisionRow::of);
    }

    private DefinitionRow definitionRow(ImportDefinition definition) {
        Long activeRevisionId = revisions
                .findByImportDefinitionIdAndStatus(definition.getId(), RevisionStatus.ACTIVE)
                .map(ImportDefinitionRevision::getId)
                .orElse(null);
        return new DefinitionRow(definition.getId(), definition.getCode(), definition.getDescription(),
                definition.getUsageType().name(), definition.getSourceOrganisation().getId(),
                definition.getSourceOrganisation().getCode(), activeRevisionId);
    }

    /** Sortering staat al vast in de {@code @Query} van de gebruikte repositorymethode. */
    private static PageRequest pageRequest(Integer page, Integer size) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE), Sort.unsorted());
    }
}
