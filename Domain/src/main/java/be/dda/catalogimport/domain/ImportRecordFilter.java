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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Eén recordfilter van een bevroren {@link ImportDefinitionRevision} (ontwerp fase 3, par. 2 004-3,
 * R-FLT-01..R-FLT-04).
 * <p>
 * <b>Het filter is de importscope, niet een schermfilter</b> (businessanalyse par. 14.4). Het bepaalt
 * welke records tot deze import horen, en dus waarop de creatiedrempel, de duplicaatcontrole en later
 * het volledigheidsbewijs rekenen. Een uitgesloten record wordt daarom geteld
 * ({@code filtered_out_count}) en nooit zomaar overgeslagen.
 * <p>
 * <b>Het bronbestand wordt altijd volledig gelezen</b>: header, structuur, parsefouten en het ruwe
 * recordaantal worden over 100% gecontroleerd. Het filter draait daarna, onmiddellijk na het parsen
 * en vóór identiteit, prijs en referenties (R-FLT-02).
 * <p>
 * Een filterwijziging hoort een nieuwe revisie te zijn en mag nooit records verwijderen die voortaan
 * buiten de scope vallen; deze rijen horen daarom bij een bevroren revisie en worden niet bijgewerkt.
 */
@Entity
@Table(name = "import_record_filter",
        uniqueConstraints = @UniqueConstraint(name = "uk_import_record_filter_sequence",
                columnNames = {"definition_revision_id", "sequence_number"}))
public class ImportRecordFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_revision_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_record_filter_revision"))
    private ImportDefinitionRevision definitionRevision;

    /** Evaluatievolgorde binnen de revisie; uniek per revisie. */
    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "filter_stage", nullable = false, length = 20)
    private FilterStage filterStage = FilterStage.SOURCE_FIELD;

    /** Headernaam of 1-gebaseerde kolomindex van de bronkolom. */
    @Column(name = "source_reference", nullable = false, length = 200)
    private String sourceReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false, length = 20)
    private FilterOperator operator;

    @Column(name = "compare_value", nullable = false, length = 500)
    private String compareValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private FilterOutcome outcome;

    @Column(name = "case_sensitive", nullable = false)
    private boolean caseSensitive;

    @Column(name = "trim_before_compare", nullable = false)
    private boolean trimBeforeCompare = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "null_behaviour", nullable = false, length = 20)
    private FilterNullBehaviour nullBehaviour = FilterNullBehaviour.EXCLUDE;

    @Enumerated(EnumType.STRING)
    @Column(name = "missing_column_behaviour", nullable = false, length = 20)
    private MissingColumnBehaviour missingColumnBehaviour = MissingColumnBehaviour.BLOCK;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    protected ImportRecordFilter() {
        // JPA
    }

    public ImportRecordFilter(ImportDefinitionRevision definitionRevision, int sequenceNumber,
                              String sourceReference, FilterOperator operator, String compareValue,
                              FilterOutcome outcome) {
        this.definitionRevision = definitionRevision;
        this.sequenceNumber = sequenceNumber;
        this.sourceReference = sourceReference;
        this.operator = operator;
        this.compareValue = compareValue;
        this.outcome = outcome;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public ImportDefinitionRevision getDefinitionRevision() {
        return definitionRevision;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public FilterStage getFilterStage() {
        return filterStage;
    }

    public void setFilterStage(FilterStage filterStage) {
        this.filterStage = filterStage;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public FilterOperator getOperator() {
        return operator;
    }

    public void setOperator(FilterOperator operator) {
        this.operator = operator;
    }

    public String getCompareValue() {
        return compareValue;
    }

    public void setCompareValue(String compareValue) {
        this.compareValue = compareValue;
    }

    public FilterOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(FilterOutcome outcome) {
        this.outcome = outcome;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    public boolean isTrimBeforeCompare() {
        return trimBeforeCompare;
    }

    public void setTrimBeforeCompare(boolean trimBeforeCompare) {
        this.trimBeforeCompare = trimBeforeCompare;
    }

    public FilterNullBehaviour getNullBehaviour() {
        return nullBehaviour;
    }

    public void setNullBehaviour(FilterNullBehaviour nullBehaviour) {
        this.nullBehaviour = nullBehaviour;
    }

    public MissingColumnBehaviour getMissingColumnBehaviour() {
        return missingColumnBehaviour;
    }

    public void setMissingColumnBehaviour(MissingColumnBehaviour missingColumnBehaviour) {
        this.missingColumnBehaviour = missingColumnBehaviour;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
