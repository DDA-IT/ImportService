package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.FetchFileObservation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Waarnemingen van ophaalruns (changeset 015-2, bouwstap K-4b). Append-only: enkel toevoegen en lezen. */
public interface FetchFileObservationRepository extends JpaRepository<FetchFileObservation, Long> {

    /** Alle waarnemingen van één run, in de volgorde waarin ze vastgelegd werden (oudste bestand eerst). */
    List<FetchFileObservation> findByTaskRunIdOrderByIdAsc(Long taskRunId);

    /**
     * De <b>watermark</b> van een taak (L6): de laatst vastgelegde waarneming met een geregistreerde levering, dus het
     * laatst opgehaalde bestand van deze taak via een ophaalrun. Leeg = er werd voor deze taak nog nooit iets opgehaald.
     */
    Optional<FetchFileObservation> findFirstByTaskRunTaskIdAndDeliveryIsNotNullOrderByIdDesc(Long taskId);
}
