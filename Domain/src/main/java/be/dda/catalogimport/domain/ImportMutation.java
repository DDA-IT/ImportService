package be.dda.catalogimport.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity @Table(name = "import_mutation", uniqueConstraints = @UniqueConstraint(name = "uk_mutation_candidate", columnNames = "candidate_id"))
public class ImportMutation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "batch_id") public ImportBatch batch;
    @OneToOne(optional = false) @JoinColumn(name = "candidate_id") public CandidateOffer candidate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) public MutationType type;
    @Column(precision = 19, scale = 6) public BigDecimal previousPrice;
}
