package be.dda.catalogimport.dao;
import be.dda.catalogimport.domain.CatalogSource;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CatalogSourceRepository extends JpaRepository<CatalogSource, Long> { }
