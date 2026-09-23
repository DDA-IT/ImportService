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
 * De ingevulde waarde van een {@link BookmarkValueScope#LINK}-bookmark, per {@link ImportLink}
 * (beslissingslog 23/09 keuze 1 en 2, changeset 006-4).
 * <p>
 * <b>Bewust géén verwijzing naar {@link ImportDefinitionBookmark}.</b> De declaratie hangt aan een
 * <i>sjabloonrevisie</i> en verdwijnt bij elke opvolgversie; de koppeling blijft wél bestaan. Met een
 * foreign key zou elke nieuwe sjabloonversie de ingevulde leverancierswaarden ongeldig maken of
 * meeslepen. De koppeling gebeurt daarom op {@link #getBookmarkName()}, en
 * {@link #getDataType()} staat gedenormaliseerd mee zodat de waarde leesbaar blijft wanneer de
 * declaratie van toen niet meer bestaat (zelfde redenering als R-ISS-02 voor issuerijen).
 * <p>
 * <b>Keerzijde die de servicelaag moet opvangen:</b> een naam die in de nieuwe sjabloonversie niet
 * meer gedeclareerd is, blijft hier als wees staan. Dat is gewild — de waarde is auditmateriaal —
 * maar de materialisatie moet zo'n wees tonen en niet stilzwijgend toepassen.
 * <p>
 * <b>Wijzigingsaudit.</b> {@link #getPreviousValueText()}, {@link #getUpdatedAt()} en
 * {@link #getUpdatedBy()} horen bij elkaar: een bookmarkwaarde bepaalt mee wat er geïmporteerd en
 * gepubliceerd wordt, dus een wijziging mag nooit alleen in een logregel bestaan. De databasechecks
 * {@code ck_import_link_bookmark_value_updated} en {@code ck_import_link_bookmark_value_previous}
 * beletten een halve audit.
 * <p>
 * <b>Nog niet gebouwd (servicelaag):</b> de andere helft van beslissing 23/09 keuze 5 — een
 * LINK-waarde mag niet meer wijzigbaar zijn zolang de koppeling een open (niet-terminale) batch heeft.
 * Die regel zit hier niet in de database en ook niet in deze entiteit.
 */
@Entity
@Table(name = "import_link_bookmark_value",
        uniqueConstraints = @UniqueConstraint(name = "uk_import_link_bookmark_value",
                columnNames = {"import_link_id", "bookmark_name"}))
public class ImportLinkBookmarkValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_link_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_link_bookmark_value_link"))
    private ImportLink importLink;

    @Column(name = "bookmark_name", nullable = false, length = 60)
    private String bookmarkName;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 40)
    private BookmarkDataType dataType = BookmarkDataType.TEXT;

    /** Nooit {@code null}; {@code ""} betekent "uitdrukkelijk leeg ingevuld". */
    @Column(name = "value_text", nullable = false, length = 500)
    private String valueText;

    /** De waarde vóór de laatste wijziging; enkel gevuld zodra er een wijziging geweest is. */
    @Column(name = "previous_value_text", length = 500)
    private String previousValueText;

    @Column(name = "filled_at", nullable = false)
    private Instant filledAt;

    @Column(name = "filled_by", nullable = false, length = 100)
    private String filledBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    protected ImportLinkBookmarkValue() {
        // JPA
    }

    public ImportLinkBookmarkValue(ImportLink importLink, String bookmarkName, BookmarkDataType dataType,
                                   String valueText, String filledBy) {
        this.importLink = importLink;
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

    /**
     * Legt een wijziging volledig vast: nieuwe waarde, vorige waarde, wie en wanneer. De drie
     * auditvelden worden altijd samen gezet, zoals de databasechecks eisen.
     */
    public void recordChange(String newValueText, String updatedBy, Instant updatedAt) {
        this.previousValueText = this.valueText;
        this.valueText = newValueText;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public ImportLink getImportLink() {
        return importLink;
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

    public String getPreviousValueText() {
        return previousValueText;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
