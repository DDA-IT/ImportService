package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ExternalCredentialEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only auditregister van een credential (changeset 013-2): enkel toevoegen en lezen. */
public interface ExternalCredentialEventRepository extends JpaRepository<ExternalCredentialEvent, Long> {

    /** Het chronologisch verloop van één credential, oplopend op {@code id} (patroon issue_case_event). */
    List<ExternalCredentialEvent> findByCredentialIdOrderByIdAsc(Long credentialId);
}
