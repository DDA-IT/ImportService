package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Eén append-only auditregel van een {@link ExternalCredential} (changeset 013-2,
 * {@code docs/design/credentials-sleutelbeheer-design.md} par. 5.2). Nooit bijgewerkt of verwijderd: er zijn geen
 * setters. Patroon {@link IssueCaseEvent}: sortering op {@code id}, geen {@code sequence_no}.
 * <p>
 * <b>Nooit een waarde en nooit een hash van het secret.</b> {@code previousKeyId}/{@code newKeyId} zijn
 * sleutel-ID's uit de sleutelring, geen sleutelmateriaal. {@code source} bepaalt of {@code changedBy}/
 * {@code changedBySubject} gevuld mogen zijn: {@link ExternalCredentialEventSource#HUMAN} draagt altijd een naam,
 * {@link ExternalCredentialEventSource#SYSTEM} nooit (ook niet de tekst "system").
 */
@Entity
@Table(name = "external_credential_event")
public class ExternalCredentialEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_external_credential_event_credential"))
    private ExternalCredential credential;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", nullable = false, updatable = false, length = 30)
    private ExternalCredentialEventKind eventKind;

    @Column(name = "reason", nullable = false, updatable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    private ExternalCredentialEventSource source;

    /** {@code null} bij {@link ExternalCredentialEventSource#SYSTEM}. */
    @Column(name = "changed_by", updatable = false, length = 100)
    private String changedBy;

    /** OIDC-{@code sub}; {@code null} bij {@code SYSTEM} of zonder geverifieerde identiteit. */
    @Column(name = "changed_by_subject", updatable = false, length = 255)
    private String changedBySubject;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    /** Sleutel-ID van de vorige waarde; {@code null} bij {@link ExternalCredentialEventKind#CREATED}. */
    @Column(name = "previous_key_id", updatable = false, length = 32)
    private String previousKeyId;

    /** Sleutel-ID van de nieuwe waarde; {@code null} bij {@link ExternalCredentialEventKind#REVOKED}. */
    @Column(name = "new_key_id", updatable = false, length = 32)
    private String newKeyId;

    protected ExternalCredentialEvent() {
        // JPA
    }

    public ExternalCredentialEvent(ExternalCredential credential, ExternalCredentialEventKind eventKind,
                                   String reason, ExternalCredentialEventSource source, String changedBy,
                                   String changedBySubject, Instant changedAt, String previousKeyId,
                                   String newKeyId) {
        this.credential = credential;
        this.eventKind = eventKind;
        this.reason = reason;
        this.source = source;
        this.changedBy = changedBy;
        this.changedBySubject = changedBySubject;
        this.changedAt = changedAt;
        this.previousKeyId = previousKeyId;
        this.newKeyId = newKeyId;
    }

    public Long getId() {
        return id;
    }

    public ExternalCredential getCredential() {
        return credential;
    }

    public ExternalCredentialEventKind getEventKind() {
        return eventKind;
    }

    public String getReason() {
        return reason;
    }

    public ExternalCredentialEventSource getSource() {
        return source;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public String getChangedBySubject() {
        return changedBySubject;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getPreviousKeyId() {
        return previousKeyId;
    }

    public String getNewKeyId() {
        return newKeyId;
    }

    /**
     * Zonder de credential zelf (die wordt lui geladen) en zonder de reden (vrije tekst van een mens: een
     * onvoorzichtig ingevoerde reden mag niet via een log verder verspreid worden).
     */
    @Override
    public String toString() {
        return "ExternalCredentialEvent[id=" + id + ", eventKind=" + eventKind + ", source=" + source
                + ", changedBy=" + changedBy + ", changedAt=" + changedAt + ", previousKeyId=" + previousKeyId
                + ", newKeyId=" + newKeyId + "]";
    }
}
