package be.dda.catalogimport.dao;
import be.dda.catalogimport.domain.LibraryOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
public interface LibraryOfferRepository extends JpaRepository<LibraryOffer, Long> { Optional<LibraryOffer> findByLibraryCodeAndSupplierCodeAndSupplierReference(String libraryCode, String supplierCode, String supplierReference); List<LibraryOffer> findByLibraryCode(String libraryCode); }
