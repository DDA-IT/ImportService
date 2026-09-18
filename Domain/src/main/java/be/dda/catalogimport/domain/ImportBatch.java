package be.dda.catalogimport.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "import_batch", uniqueConstraints = @UniqueConstraint(name = "uk_batch_idempotency", columnNames = {"definition_id", "content_hash"}))
public class ImportBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "definition_id") public ImportDefinition definition;
    @Column(nullable = false, length = 64) public String contentHash;
    @Column(nullable = false, length = 255) public String originalFileName;
    @Column(nullable = false, columnDefinition = "bytea") public byte[] originalContent;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) public ImportStatus status = ImportStatus.RECEIVED;
    @Column(nullable = false) public Instant receivedAt = Instant.now();
    public long recordCount; public long issueCount;
}
