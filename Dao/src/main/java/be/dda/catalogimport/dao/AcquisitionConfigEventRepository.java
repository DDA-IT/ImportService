package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.AcquisitionConfigEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only auditregister van de ophaalconfiguratie (changeset 014-6): enkel toevoegen en lezen. */
public interface AcquisitionConfigEventRepository extends JpaRepository<AcquisitionConfigEvent, Long> {

    /** Het chronologisch verloop rond één taak, oplopend op {@code id} (patroon issue_case_event). */
    List<AcquisitionConfigEvent> findByTaskIdOrderByIdAsc(Long taskId);
}
