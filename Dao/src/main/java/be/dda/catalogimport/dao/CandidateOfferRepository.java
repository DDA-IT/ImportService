package be.dda.catalogimport.dao;
import be.dda.catalogimport.domain.CandidateOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CandidateOfferRepository extends JpaRepository<CandidateOffer, Long> { List<CandidateOffer> findByBatchId(Long batchId); }
