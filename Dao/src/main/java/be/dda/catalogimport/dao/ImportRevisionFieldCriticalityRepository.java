package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * De kritiek-overrules van de revisie-eigen velden (ontwerp fase 3, par. 15.1, changeset 004-15).
 * <p>
 * Ze worden exact één keer per batch gelezen, in stap B' samen met de veldmappings: een query per
 * bronregel zou bij een miljoen regels een miljoen queries betekenen.
 */
public interface ImportRevisionFieldCriticalityRepository
        extends JpaRepository<ImportRevisionFieldCriticality, ImportRevisionFieldCriticality.Key> {

    List<ImportRevisionFieldCriticality> findByDefinitionRevisionId(Long definitionRevisionId);
}
