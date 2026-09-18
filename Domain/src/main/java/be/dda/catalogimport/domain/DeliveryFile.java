package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Eén fysiek bestand binnen een {@link Delivery}.
 * <p>
 * De <b>inhoud staat niet in de database</b>: {@link #getArchiveReference()} verwijst naar het
 * immutable object in het bronarchief/objectopslag (§14.13, §15.3). Dat is een bewuste keuze voor
 * de lange termijn — bestanden van meer dan een miljoen regels horen niet in een relationele kolom.
 * De database bewaart enkel het bewijs: naam, referentie, hash, grootte en ontvangsttijd.
 */
@Entity
@Table(name = "delivery_file",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_delivery_file_sequence",
                        columnNames = {"delivery_id", "sequence_number"}),
                @UniqueConstraint(name = "uk_delivery_file_name",
                        columnNames = {"delivery_id", "file_name"})
        })
public class DeliveryFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_delivery_file_delivery"))
    private Delivery delivery;

    /** Volgnummer binnen een meerdelige levering; houdt de bestandset deterministisch geordend. */
    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    /** Oorspronkelijke bestandsnaam zoals aangeleverd. */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /** Sleutel/pad van het gearchiveerde object; nooit de inhoud zelf. */
    @Column(name = "archive_reference", nullable = false, length = 1000)
    private String archiveReference;

    /** Hexadecimale inhoudshash, standaard SHA-256. */
    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    /** Expliciet bewaard zodat een latere algoritmewissel geen bestaande hashes ongeldig maakt. */
    @Column(name = "hash_algorithm", nullable = false, length = 20)
    private String hashAlgorithm = "SHA-256";

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DeliveryFile() {
        // JPA
    }

    public DeliveryFile(Delivery delivery, int sequenceNumber, String fileName,
                        String archiveReference, String contentHash, long byteSize) {
        this.delivery = delivery;
        this.sequenceNumber = sequenceNumber;
        this.fileName = fileName;
        this.archiveReference = archiveReference;
        this.contentHash = contentHash;
        this.byteSize = byteSize;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (receivedAt == null) {
            receivedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getArchiveReference() {
        return archiveReference;
    }

    public void setArchiveReference(String archiveReference) {
        this.archiveReference = archiveReference;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public void setHashAlgorithm(String hashAlgorithm) {
        this.hashAlgorithm = hashAlgorithm;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
