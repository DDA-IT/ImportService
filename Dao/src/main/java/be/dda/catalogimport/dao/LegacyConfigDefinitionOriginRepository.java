package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.LegacyConfigDefinitionOrigin;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Changeset 020-3 ({@code 021-legacy-config-definition-origin.sql}); slice S2 van de legacy-config-migratie. Sinds G2a
 * (changeset 026-1) staan hier twee soorten rijen: groepen en losgekoppelde kopieën
 * ({@link LegacyConfigDefinitionOrigin#isDetached()}). De groepsherkenning leest <b>enkel groepen</b>: een kopie trekt
 * nooit leden aan en mag dezelfde inhoudshash hebben als haar groep.
 */
public interface LegacyConfigDefinitionOriginRepository extends JpaRepository<LegacyConfigDefinitionOrigin, Long> {

    /**
     * De <b>groep</b> met deze groepssleutel (hoogstens één: {@code uk_legacy_config_definition_origin} met
     * {@code group_marker = TRUE}). Een losgekoppelde kopie met dezelfde hash telt niet mee (G2a, 026-1).
     */
    @Query("select o from LegacyConfigDefinitionOrigin o where o.originEnvironment = :environment "
            + "and o.originDossier = :dossier and o.supplierCode = :supplierCode "
            + "and o.definitionContentHash = :definitionContentHash and o.groupMarker = true")
    Optional<LegacyConfigDefinitionOrigin>
            findByOriginEnvironmentAndOriginDossierAndSupplierCodeAndDefinitionContentHash(
                    @Param("environment") String originEnvironment, @Param("dossier") String originDossier,
                    @Param("supplierCode") String supplierCode,
                    @Param("definitionContentHash") String definitionContentHash);

    /** De herkomstrijen van een definitie; leeg = een handmatige definitie (nooit een migratiedoel). */
    List<LegacyConfigDefinitionOrigin> findByImportDefinitionIdOrderByIdAsc(Long importDefinitionId);

    boolean existsByImportDefinitionId(Long importDefinitionId);

    /**
     * De herkomstrijen (groepen én losgekoppelde kopieën) van meerdere definities in één query — voor
     * {@code GET /definitions} (beslissingslog 2026-10-23 "API-uitbreiding voor de inrichtingsschermen", punt 5).
     */
    List<LegacyConfigDefinitionOrigin> findByImportDefinitionIdIn(Collection<Long> importDefinitionIds);

    /**
     * De groepen waarvan een van deze definities het sjabloon is ({@code template_definition_id}, S7) — idem, om een
     * gemigreerd sjabloon te herkennen. Een losgekoppelde kopie heeft nooit een sjabloon.
     */
    List<LegacyConfigDefinitionOrigin> findByTemplateDefinitionIdIn(Collection<Long> templateDefinitionIds);

    /**
     * S7: de <b>groepen</b> van een leverancier in een omgeving/dossier, om een gedeelde definitie ook op de legacy-code
     * te herkennen ({@code legacy_definition_codes}); vaste volgorde op id. Losgekoppelde kopieën (G2a) niet.
     */
    @Query("select o from LegacyConfigDefinitionOrigin o where o.originEnvironment = :environment "
            + "and o.originDossier = :dossier and o.supplierCode = :supplierCode and o.groupMarker = true "
            + "order by o.id asc")
    List<LegacyConfigDefinitionOrigin> findByOriginEnvironmentAndOriginDossierAndSupplierCodeOrderByIdAsc(
            @Param("environment") String originEnvironment, @Param("dossier") String originDossier,
            @Param("supplierCode") String supplierCode);

    /**
     * G2a: de losgekoppelde kopie van een interface (hoogstens één: {@code uk_legacy_config_definition_origin_detached}).
     */
    Optional<LegacyConfigDefinitionOrigin> findByOriginEnvironmentAndOriginDossierAndDetachedInterfaceCode(
            String originEnvironment, String originDossier, String detachedInterfaceCode);
}
