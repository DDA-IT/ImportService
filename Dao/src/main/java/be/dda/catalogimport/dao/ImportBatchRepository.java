package be.dda.catalogimport.dao;
import be.dda.catalogimport.domain.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> { Optional<ImportBatch> findByDefinitionIdAndContentHash(Long definitionId, String contentHash); }
