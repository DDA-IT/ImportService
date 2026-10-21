package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.LegacyConfigDefinitionOrigin;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Changeset 020-3 ({@code 021-legacy-config-definition-origin.sql}); slice S2 van de legacy-config-migratie. */
public interface LegacyConfigDefinitionOriginRepository extends JpaRepository<LegacyConfigDefinitionOrigin, Long> {

    Optional<LegacyConfigDefinitionOrigin>
            findByOriginEnvironmentAndOriginDossierAndSupplierCodeAndDefinitionContentHash(
                    String originEnvironment, String originDossier, String supplierCode, String definitionContentHash);

    /** De herkomstrijen van een definitie; leeg = een handmatige definitie (nooit een migratiedoel). */
    List<LegacyConfigDefinitionOrigin> findByImportDefinitionIdOrderByIdAsc(Long importDefinitionId);

    boolean existsByImportDefinitionId(Long importDefinitionId);

    /**
     * S7: de groepen van een leverancier in een omgeving/dossier, om een gedeelde definitie ook op de legacy-code te
     * herkennen ({@code legacy_definition_codes}); vaste volgorde op id.
     */
    List<LegacyConfigDefinitionOrigin> findByOriginEnvironmentAndOriginDossierAndSupplierCodeOrderByIdAsc(
            String originEnvironment, String originDossier, String supplierCode);
}
