package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.LegacyConfigRevisionOrigin;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Changeset 020-4 ({@code 021-legacy-config-definition-origin.sql}); slice S2 van de legacy-config-migratie. */
public interface LegacyConfigRevisionOriginRepository extends JpaRepository<LegacyConfigRevisionOrigin, Long> {

    Optional<LegacyConfigRevisionOrigin> findByRevisionId(Long revisionId);

    List<LegacyConfigRevisionOrigin> findByRevisionIdIn(Collection<Long> revisionIds);
}
