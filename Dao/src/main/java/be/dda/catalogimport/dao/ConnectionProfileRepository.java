package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ConnectionProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Koppen van verbindingsprofielen (changeset 014-1). */
public interface ConnectionProfileRepository extends JpaRepository<ConnectionProfile, Long> {

    Optional<ConnectionProfile> findByCode(String code);

    /** Lijst (LC-2): vaste volgorde op code, dan id. */
    List<ConnectionProfile> findAllByOrderByCodeAscIdAsc();
}
