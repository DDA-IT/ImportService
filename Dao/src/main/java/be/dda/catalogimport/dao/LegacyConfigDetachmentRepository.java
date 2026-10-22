package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.LegacyConfigDetachment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Changeset 026-2 ({@code 026-legacy-config-detach.sql}): append-only audit van het loskoppelen van een gedeelde
 * definitie (G2a). Enkel invoegen en lezen; een rij wordt nooit bijgewerkt of verwijderd.
 */
public interface LegacyConfigDetachmentRepository extends JpaRepository<LegacyConfigDetachment, Long> {

    /** De historiek van één koppeling, oudste eerst. */
    List<LegacyConfigDetachment> findByImportLinkIdOrderByIdAsc(Long importLinkId);

    /** De laatste loskoppeling naar deze (kopie)definitie, voor het rapport ({@code DETACHED_DEFINITION}). */
    Optional<LegacyConfigDetachment> findFirstByToDefinitionIdOrderByIdDesc(Long toDefinitionId);
}
