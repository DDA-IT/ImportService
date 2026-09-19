package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportRecordFilter;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * De recordfilters van een bevroren revisie (ontwerp fase 3, par. 2 004-3).
 * <p>
 * Exact één keer per batch gelezen, in stap B' vóór het bestand geopend wordt; de evaluatie zelf
 * gebeurt daarna in het geheugen ({@code RecordFilterEvaluator}), zonder query per bronregel.
 */
public interface ImportRecordFilterRepository extends JpaRepository<ImportRecordFilter, Long> {

    List<ImportRecordFilter> findByDefinitionRevisionIdOrderBySequenceNumberAsc(Long definitionRevisionId);
}
