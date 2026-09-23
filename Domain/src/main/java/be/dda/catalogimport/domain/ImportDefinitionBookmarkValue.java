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
 * De ingevulde waarde van een {@link BookmarkValueScope#DEFINITION}-bookmark, vastgelegd op de
 * afgeleide {@link ImportDefinitionRevision} op het moment van materialisatie (beslissingslog 23/09
 * keuze 1, changeset 006-3).
 * <p>
 * <b>Dit is een snapshot, geen verwijzing.</b> Materialisatie schrijft de waarde létterlijk in de
 * doelkolom van de afgeleide revisie: {@code import_field_mapping.fixed_value} met
 * {@link FieldValueKind#FIXED_VALUE}, of {@code import_record_filter.compare_value}. Voor
 * recordfilters is dat niet één van twee opties maar de enige die werkt zonder een bestaande tabel te
 * wijzigen (fase3-rules-design.md §2, aanvullingen bij 004-2 en 004-3). Deze rij is daardoor de enige
 * plek waar nog af te lezen is dát een vaste waarde uit een sjabloon kwam, en uit welke sjabloonversie
 * ({@link #getSourceTemplateRevision()}).
 * <p>
 * <b>{@code valueText} is nooit {@code null} en mag {@code ""} zijn.</b> De lege string betekent
 * "uitdrukkelijk leeg ingevuld"; "niet ingevuld" wordt uitgedrukt door de <i>afwezigheid</i> van een
 * rij. Dat onderscheid is hetzelfde als bij de aanbiedingsidentiteit (beslissingslog 18/09: {@code null}
 * en {@code ""} zijn verschillende toestanden) en mag hier niet op één waarde samenvallen.
 */
@Entity
@Table(name = "import_definition_bookmark_value",
        uniqueConstraints = @UniqueConstraint(name = "uk_import_definition_bookmark_value",
                columnNames = {"definition_revision_id", "bookmark_name"}))
public class ImportDefinitionBookmarkValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** De afgeleide revisie waarin deze waarde vastgezet is. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_revision_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_definition_bookmark_value_revision"))
    private ImportDefinitionRevision definitionRevision;

    /**
     * Naam van de bookmark, niet een verwijzing naar de declaratierij: die hangt aan de
     * <i>sjabloonrevisie</i> en verdwijnt bij elke opvolgversie.
     */
    @Column(name = "bookmark_name", nullable = false, length = 60)
    private String bookmarkName;

    /** Gedenormaliseerd meebewaard, zodat de waarde leesbaar blijft als de declaratie verdwenen is. */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 40)
    private BookmarkDataType dataType = BookmarkDataType.TEXT;

    /** Nooit {@code null}; {@code ""} betekent "uitdrukkelijk leeg ingevuld". */
    @Column(name = "value_text", nullable = false, length = 500)
    private String valueText;

    /** De sjabloonrevisie waaruit gematerialiseerd is, of {@code null} bij handmatig invullen. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_template_revision_id",
            foreignKey = @ForeignKey(name = "fk_import_definition_bookmark_value_template"))
    private ImportDefinitionRevision sourceTemplateRevision;

    @Column(name = "filled_at", nullable = false)
    private Instant filledAt;

    @Column(name = "filled_by", nullable = false, length = 100)
    private String filledBy;

    protected ImportDefinitionBookmarkValue() {
        // JPA
    }

    public ImportDefinitionBookmarkValue(ImportDefinitionRevision definitionRevision, String bookmarkName,
                                         BookmarkDataType dataType, String valueText, String filledBy) {
        this.definitionRevision = definitionRevision;
        this.bookmarkName = bookmarkName;
        this.dataType = dataType;
        this.valueText = valueText;
        this.filledBy = filledBy;
    }

    @PrePersist
    void onPersist() {
        if (filledAt == null) {
            filledAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public ImportDefinitionRevision getDefinitionRevision() {
        return definitionRevision;
    }

    public String getBookmarkName() {
        return bookmarkName;
    }

    public BookmarkDataType getDataType() {
        return dataType;
    }

    public void setDataType(BookmarkDataType dataType) {
        this.dataType = dataType;
    }

    public String getValueText() {
        return valueText;
    }

    public void setValueText(String valueText) {
        this.valueText = valueText;
    }

    public ImportDefinitionRevision getSourceTemplateRevision() {
        return sourceTemplateRevision;
    }

    public void setSourceTemplateRevision(ImportDefinitionRevision sourceTemplateRevision) {
        this.sourceTemplateRevision = sourceTemplateRevision;
    }

    public Instant getFilledAt() {
        return filledAt;
    }

    public String getFilledBy() {
        return filledBy;
    }

    public void setFilledBy(String filledBy) {
        this.filledBy = filledBy;
    }
}
