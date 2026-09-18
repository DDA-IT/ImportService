package be.dda.catalogimport.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "library_offer", uniqueConstraints = @UniqueConstraint(name = "uk_library_offer_identity", columnNames = {"library_code", "supplier_code", "supplier_reference"}))
public class LibraryOffer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 80) public String libraryCode;
    @Column(nullable = false, length = 80) public String supplierCode;
    @Column(nullable = false, length = 160) public String supplierReference;
    @Column(nullable = false, length = 80) public String groupCode;
    @Column(nullable = false, precision = 19, scale = 6) public BigDecimal price;
    @Column(nullable = false) public boolean active = true;
    @Column(nullable = false) public Instant updatedAt = Instant.now();
}
