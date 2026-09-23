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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Eén toegelaten configuratieplaats voor een {@link ImportDefinitionBookmark} (beslissingslog 23/09
 * keuze 3, changeset 006-2).
 * <p>
 * Dit is een <b>witte lijst</b>: zonder rij hier mag een bookmark nergens toegepast worden. Een
 * bookmark die overal mag landen is bij het materialiseren niet meer te overzien, en een bookmark
 * zonder enige plaats is een invulveld dat niets doet — beide zijn configuratiefouten die vroeg
 * zichtbaar moeten worden.
 * <p>
 * {@link #getTargetHint()} is nooit {@code null} maar wel vaak {@code ""}: de lege string betekent
 * "geen nadere aanduiding". Een nullable kolom zou de unieke sleutel uitschakelen — {@code NULL}
 * botst in een unique constraint niet met {@code NULL}, zodat dezelfde plaats twee keer in de witte
 * lijst zou kunnen belanden.
 */
@Entity
@Table(name = "import_definition_bookmark_usage",
        uniqueConstraints = @UniqueConstraint(name = "uk_import_definition_bookmark_usage",
                columnNames = {"bookmark_id", "place_kind", "target_hint"}))
public class ImportDefinitionBookmarkUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bookmark_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_definition_bookmark_usage_bookmark"))
    private ImportDefinitionBookmark bookmark;

    @Enumerated(EnumType.STRING)
    @Column(name = "place_kind", nullable = false, length = 40)
    private BookmarkUsagePlace placeKind;

    /**
     * Nadere aanduiding binnen de plaats, bv. de doelveldcode van de veldmapping of het
     * volgnummer van het recordfilter. {@code ""} betekent "geen nadere aanduiding", nooit
     * {@code null}.
     */
    @Column(name = "target_hint", nullable = false, length = 200)
    private String targetHint = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImportDefinitionBookmarkUsage() {
        // JPA
    }

    public ImportDefinitionBookmarkUsage(ImportDefinitionBookmark bookmark, BookmarkUsagePlace placeKind,
                                         String targetHint) {
        this.bookmark = bookmark;
        this.placeKind = placeKind;
        this.targetHint = targetHint;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public ImportDefinitionBookmark getBookmark() {
        return bookmark;
    }

    public BookmarkUsagePlace getPlaceKind() {
        return placeKind;
    }

    public void setPlaceKind(BookmarkUsagePlace placeKind) {
        this.placeKind = placeKind;
    }

    public String getTargetHint() {
        return targetHint;
    }

    public void setTargetHint(String targetHint) {
        this.targetHint = targetHint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
