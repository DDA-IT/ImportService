package be.dda.catalogimport.dao;
import be.dda.catalogimport.domain.ImportDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ImportDefinitionRepository extends JpaRepository<ImportDefinition, Long> { }
