package be.dda.catalogimport.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "catalog_source", uniqueConstraints = @UniqueConstraint(name = "uk_source_code", columnNames = "code"))
public class CatalogSource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 80) public String code;
    @Column(nullable = false, length = 200) public String name;
    @Column(nullable = false) public Instant createdAt = Instant.now();
    protected CatalogSource() { }
    public CatalogSource(String code, String name) { this.code = code; this.name = name; }
}
