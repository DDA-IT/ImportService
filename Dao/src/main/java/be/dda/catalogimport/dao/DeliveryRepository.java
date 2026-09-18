package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.Delivery;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    /**
     * Lookup voor de retry-bescherming: dezelfde technische leveringsregistratie mag geen tweede
     * {@code Delivery}-rij maken. Zie {@link Delivery} — dit is nadrukkelijk geen inhoudshash.
     */
    Optional<Delivery> findByTaskIdAndIdempotencyKey(Long taskId, String idempotencyKey);

    List<Delivery> findByTaskIdOrderByReceivedAtDesc(Long taskId);

    List<Delivery> findByTaskRunId(Long taskRunId);
}
