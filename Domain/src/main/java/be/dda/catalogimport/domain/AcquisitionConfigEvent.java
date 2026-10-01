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
 * Eén append-only auditregel van de ophaalconfiguratie (changeset 014-6,
 * {@code docs/design/leveringsconfiguratie-design.md} par. 3.2): taak gekoppeld/ontkoppeld, verbindingstest,
 * hostsleutelscan, buiten gebruik gesteld. Nooit bijgewerkt of verwijderd: geen setters. Patroon
 * {@link ExternalCredentialEvent}: sortering op {@code id}.
 * <p>
 * {@code source} bepaalt of {@code changedBy}/{@code changedBySubject} gevuld mogen zijn:
 * {@link AcquisitionConfigEventSource#HUMAN} draagt altijd een naam, {@link AcquisitionConfigEventSource#SYSTEM}
 * nooit (ook niet de tekst "system"). {@code detail} bevat nooit een secret en nooit een archiefpad.
 */
@Entity
@Table(name = "acquisition_config_event")
public class AcquisitionConfigEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", nullable = false, updatable = false, length = 30)
    private AcquisitionConfigEventKind eventKind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_acquisition_config_event_task"))
    private CatalogImportTask task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dc_version_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_acquisition_config_event_dc_version"))
    private DeliveryConfigurationVersion deliveryConfigurationVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_version_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_acquisition_config_event_profile_version"))
    private ConnectionProfileVersion profileVersion;

    @Column(name = "outcome_code", updatable = false, length = 60)
    private String outcomeCode;

    @Column(name = "detail", updatable = false, length = 500)
    private String detail;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    private AcquisitionConfigEventSource source;

    /** {@code null} bij {@link AcquisitionConfigEventSource#SYSTEM}. */
    @Column(name = "changed_by", updatable = false, length = 100)
    private String changedBy;

    /** OIDC-{@code sub}; {@code null} bij {@code SYSTEM} of zonder geverifieerde identiteit. */
    @Column(name = "changed_by_subject", updatable = false, length = 255)
    private String changedBySubject;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected AcquisitionConfigEvent() {
        // JPA
    }

    /**
     * @throws IllegalArgumentException een verplicht gegeven ontbreekt of de combinatie {@code source}/
     *                                  {@code changedBy} is ongeldig (de melding noemt geen waarde)
     */
    public AcquisitionConfigEvent(AcquisitionConfigEventKind eventKind, CatalogImportTask task,
                                  DeliveryConfigurationVersion deliveryConfigurationVersion,
                                  ConnectionProfileVersion profileVersion, String outcomeCode, String detail,
                                  String reason, AcquisitionConfigEventSource source, String changedBy,
                                  String changedBySubject, Instant changedAt) {
        this.eventKind = ConfigurationGuard.required(eventKind, "eventKind");
        this.task = task;
        this.deliveryConfigurationVersion = deliveryConfigurationVersion;
        this.profileVersion = profileVersion;
        this.outcomeCode = ConfigurationGuard.optionalText(outcomeCode, "outcomeCode", 60);
        this.detail = ConfigurationGuard.optionalText(detail, "detail", 500);
        this.reason = ConfigurationGuard.optionalText(reason, "reason", 500);
        this.source = ConfigurationGuard.required(source, "source");
        if (source == AcquisitionConfigEventSource.HUMAN) {
            ConfigurationGuard.requiredText(changedBy, "changedBy", 100);
        } else if (changedBy != null || changedBySubject != null) {
            throw new IllegalArgumentException("a SYSTEM event carries no changedBy or changedBySubject");
        }
        ConfigurationGuard.checkSubjectHasName(changedBy, changedBySubject, "changedBy");
        ConfigurationGuard.optionalText(changedBySubject, "changedBySubject", 255);
        this.changedBy = changedBy;
        this.changedBySubject = changedBySubject;
        this.changedAt = ConfigurationGuard.required(changedAt, "changedAt");
    }

    public Long getId() {
        return id;
    }

    public AcquisitionConfigEventKind getEventKind() {
        return eventKind;
    }

    public CatalogImportTask getTask() {
        return task;
    }

    public DeliveryConfigurationVersion getDeliveryConfigurationVersion() {
        return deliveryConfigurationVersion;
    }

    public ConnectionProfileVersion getProfileVersion() {
        return profileVersion;
    }

    public String getOutcomeCode() {
        return outcomeCode;
    }

    public String getDetail() {
        return detail;
    }

    public String getReason() {
        return reason;
    }

    public AcquisitionConfigEventSource getSource() {
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

    /** Zonder detail en reden (vrije tekst: een log mag die niet verder verspreiden). */
    @Override
    public String toString() {
        return "AcquisitionConfigEvent[id=" + id + ", eventKind=" + eventKind + ", source=" + source
                + ", changedBy=" + changedBy + ", changedAt=" + changedAt + ", outcomeCode=" + outcomeCode + "]";
    }
}
