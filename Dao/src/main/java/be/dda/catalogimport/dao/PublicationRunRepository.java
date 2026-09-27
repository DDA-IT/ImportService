package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationRun;
import be.dda.catalogimport.domain.PublicationTargetMode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PublicationRunRepository extends JpaRepository<PublicationRun, Long> {

    List<PublicationRun> findByBundleIdOrderByIdAsc(Long bundleId);

    Optional<PublicationRun> findByIdempotencyKey(String idempotencyKey);

    /**
     * Het aantal niet-terminale runs van deze bundel ({@code active_marker is not null}, bouwstap 5P-7).
     * Bedoeld voor de <b>nette</b> foutmelding {@code PUBLICATION_RUN_IN_PROGRESS}; de harde zekering
     * blijft {@code uk_publication_run_active} in de database, want alleen die houdt ook stand bij twee
     * gelijktijdige aanvragen.
     */
    long countByBundleIdAndActiveMarkerIsNotNull(Long bundleId);

    /**
     * Het hoogste {@code attempt} van deze bundel in deze modus, of 0 wanneer er nog geen run bestaat.
     * Per modus en niet over de bundel heen: {@code attempt} telt de pogingen naar één doel, en de
     * idempotentiesleutel {@code run:<bundleId>:<mode>:<attempt>} draagt de modus al.
     */
    @Query("select coalesce(max(r.attempt), 0) from PublicationRun r "
            + "where r.bundle.id = :bundleId and r.targetMode = :targetMode")
    Integer findMaxAttempt(@Param("bundleId") Long bundleId,
                           @Param("targetMode") PublicationTargetMode targetMode);
}
