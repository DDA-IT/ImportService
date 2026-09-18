package be.dda.catalogimport.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "import_definition", uniqueConstraints = @UniqueConstraint(name = "uk_definition_version", columnNames = {"source_id", "version"}))
public class ImportDefinition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "source_id") public CatalogSource source;
    @Column(nullable = false) public int version;
    @Column(nullable = false, length = 80) public String libraryCode;
    @Column(nullable = false, length = 100) public String supplierColumn;
    @Column(nullable = false, length = 100) public String referenceColumn;
    @Column(nullable = false, length = 100) public String groupColumn;
    @Column(nullable = false, length = 100) public String priceColumn;
    @Column(nullable = false) public Instant createdAt = Instant.now();
}
