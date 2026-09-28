package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportDefinitionRepository extends JpaRepository<ImportDefinition, Long> {

    /** Natuurlijke sleutel: bronorganisatie + code. */
    Optional<ImportDefinition> findBySourceOrganisationIdAndCode(Long sourceOrganisationId, String code);

    List<ImportDefinition> findBySourceOrganisationId(Long sourceOrganisationId);

    /** De sjablonen ({@code REUSABLE_TEMPLATE}), gepagineerd; toegevoegd in bouwstap 5b. */
    Page<ImportDefinition> findByUsageType(DefinitionUsageType usageType, Pageable pageable);

    /**
     * De definities die uit één sjabloon gematerialiseerd zijn — de keuzelijst van
     * {@code GET /templates/{id}/materialisations} (bouwstap 5d, ontwerp §6).
     * <p>
     * De sortering staat <b>uitdrukkelijk</b> in de query en niet in de {@link Pageable}: een
     * gepagineerde lijst zonder {@code order by} mag per pagina een andere volgorde teruggeven, en dan
     * kan dezelfde definitie op twee pagina's staan of op geen enkele — precies het probleem dat
     * {@code GET /bundles} met een expliciete sortering opgelost heeft (beslissingslog 23/09, vraag Q4).
     * {@code code} is uniek binnen één bronorganisatie en alle afgeleide definities erven die van het
     * sjabloon (A31), dus {@code code} alleen volstaat al; {@code id} staat er als gegarandeerde
     * tiebreaker achter.
     */
    @Query(value = "select d from ImportDefinition d where d.basedOnDefinition.id = :templateId "
            + "order by d.code asc, d.id asc",
            countQuery = "select count(d) from ImportDefinition d where d.basedOnDefinition.id = :templateId")
    Page<ImportDefinition> findMaterialisedFrom(@Param("templateId") Long templateId, Pageable pageable);

    /**
     * Opzoeklijst voor scherm 1a (S1-B1, buiten {@code catalogimport.setup-api.enabled}): alle
     * importdefinities, optioneel gefilterd op bronorganisatie en gebruikstype, oplopend op {@code code}.
     * {@code join fetch} op {@code sourceOrganisation} ({@code LAZY}) omdat {@code DefinitionRow} haar
     * code toont en dat anders per rij een extra query zou kosten.
     */
    @Query(value = "select d from ImportDefinition d join fetch d.sourceOrganisation "
            + "where (:sourceOrganisationId is null or d.sourceOrganisation.id = :sourceOrganisationId) "
            + "and (:usageType is null or d.usageType = :usageType) order by d.code asc",
            countQuery = "select count(d) from ImportDefinition d "
                    + "where (:sourceOrganisationId is null or d.sourceOrganisation.id = :sourceOrganisationId) "
                    + "and (:usageType is null or d.usageType = :usageType)")
    Page<ImportDefinition> findDefinitionRows(@Param("sourceOrganisationId") Long sourceOrganisationId,
                                              @Param("usageType") DefinitionUsageType usageType, Pageable pageable);
}
