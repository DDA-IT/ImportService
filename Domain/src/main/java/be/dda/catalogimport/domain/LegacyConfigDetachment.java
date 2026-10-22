package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Append-only audit van het loskoppelen van een gedeelde definitie (changeset 026-2 in
 * {@code 026-legacy-config-detach.sql}; {@code docs/design/g2-design.md} par. G2a; beslissingslog 2026-10-22 "G2a:
 * loskoppelen van een gedeelde definitie", V1 = A).
 * <p>
 * {@code import_link} bewaart geen definitiehistoriek: na het verleggen van een koppeling is de vroegere definitie enkel
 * nog af te lezen aan de bevroren revisie van elke oude batch ({@code import_batch.definition_revision_id}) en aan deze
 * rij. Daarom wordt een rij nooit bijgewerkt of verwijderd (alle kolommen {@code updatable = false}, geen setters).
 */
@Entity
@Table(name = "legacy_config_detachment")
public class LegacyConfigDetachment {

    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_BY_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** De herkomst (020-2) van de interface. */
    @Column(name = "origin_id", nullable = false, updatable = false)
    private Long originId;

    /** De koppeling die verlegd werd (of die op de kopie ontstond, bij een nieuwe interface). */
    @Column(name = "import_link_id", nullable = false, updatable = false)
    private Long importLinkId;

    /** De gedeelde definitie van de groep. */
    @Column(name = "from_definition_id", nullable = false, updatable = false)
    private Long fromDefinitionId;

    /** De eigen kopie. */
    @Column(name = "to_definition_id", nullable = false, updatable = false)
    private Long toDefinitionId;

    /** De ontvangst waarvan de APPLY loskoppelde, of {@code null}. */
    @Column(name = "receipt_id", updatable = false)
    private Long receiptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 20)
    private LegacyConfigDetachmentAction action;

    @Column(name = "reason", nullable = false, updatable = false, length = MAX_REASON_LENGTH)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = MAX_BY_LENGTH)
    private String createdBy;

    /** OIDC-subject van {@code createdBy}, of {@code null} = geen geverifieerde identiteit. */
    @Column(name = "created_by_subject", updatable = false, length = 255)
    private String createdBySubject;

    protected LegacyConfigDetachment() {
        // JPA
    }

    /** Een loskoppeling ({@link LegacyConfigDetachmentAction#DETACH}) als gevolg van de APPLY van een ontvangst. */
    public static LegacyConfigDetachment detach(Long originId, Long importLinkId, Long fromDefinitionId,
                                                Long toDefinitionId, Long receiptId, String reason, Instant at,
                                                String by, String bySubject) {
        LegacyConfigDetachment row = new LegacyConfigDetachment();
        row.originId = required(originId, "originId");
        row.importLinkId = required(importLinkId, "importLinkId");
        row.fromDefinitionId = required(fromDefinitionId, "fromDefinitionId");
        row.toDefinitionId = required(toDefinitionId, "toDefinitionId");
        if (fromDefinitionId.equals(toDefinitionId)) {
            throw new IllegalArgumentException("a detachment needs two different definitions");
        }
        row.receiptId = receiptId;
        row.action = LegacyConfigDetachmentAction.DETACH;
        row.reason = requiredText(reason, "reason", MAX_REASON_LENGTH);
        row.createdAt = required(at, "createdAt");
        row.createdBy = requiredText(by, "createdBy", MAX_BY_LENGTH);
        row.createdBySubject = bySubject;
        return row;
    }

    public Long getId() {
        return id;
    }

    public Long getOriginId() {
        return originId;
    }

    public Long getImportLinkId() {
        return importLinkId;
    }

    public Long getFromDefinitionId() {
        return fromDefinitionId;
    }

    public Long getToDefinitionId() {
        return toDefinitionId;
    }

    public Long getReceiptId() {
        return receiptId;
    }

    public LegacyConfigDetachmentAction getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getCreatedBySubject() {
        return createdBySubject;
    }

    @Override
    public String toString() {
        return "LegacyConfigDetachment[id=" + id + ", action=" + action + ", link=" + importLinkId + ", from="
                + fromDefinitionId + ", to=" + toDefinitionId + "]";
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String requiredText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
        return value;
    }
}
