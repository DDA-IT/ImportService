package be.dda.catalogimport.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity @Table(name = "candidate_offer", uniqueConstraints = @UniqueConstraint(name = "uk_candidate_identity", columnNames = {"batch_id", "supplier_code", "supplier_reference"}))
public class CandidateOffer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "batch_id") public ImportBatch batch;
    @Column(nullable = false, length = 80) public String supplierCode;
    @Column(nullable = false, length = 160) public String supplierReference;
    @Column(nullable = false, length = 80) public String groupCode;
    @Column(nullable = false, precision = 19, scale = 6) public BigDecimal price;
    @Column(nullable = false) public int sourceLine;
}
