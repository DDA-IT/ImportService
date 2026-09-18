package be.dda.catalogimport.dao;
import be.dda.catalogimport.domain.ImportMutation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ImportMutationRepository extends JpaRepository<ImportMutation, Long> { List<ImportMutation> findByBatchId(Long batchId); }
