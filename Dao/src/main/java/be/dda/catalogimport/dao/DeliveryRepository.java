package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.Delivery;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    /**
     * Welke van deze idempotentiesleutels bij deze taak al een levering hebben: één query voor alle matchende bestanden
     * van een ophaalrun (K-4b, design par. 4.1 stap 4). De aanroeper geeft hoogstens de listing-cap (10 000) sleutels.
     */
    @Query("select d.idempotencyKey from Delivery d where d.task.id = :taskId and d.idempotencyKey in :keys")
    List<String> findExistingIdempotencyKeys(@Param("taskId") Long taskId, @Param("keys") Collection<String> keys);

    /**
     * Lookup voor de retry-bescherming: dezelfde technische leveringsregistratie mag geen tweede
     * {@code Delivery}-rij maken. Zie {@link Delivery} — dit is nadrukkelijk geen inhoudshash.
     */
    Optional<Delivery> findByTaskIdAndIdempotencyKey(Long taskId, String idempotencyKey);

    List<Delivery> findByTaskIdOrderByReceivedAtDesc(Long taskId);

    List<Delivery> findByTaskRunId(Long taskRunId);
}
