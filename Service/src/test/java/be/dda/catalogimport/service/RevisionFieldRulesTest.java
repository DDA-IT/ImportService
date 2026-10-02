package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.service.SetupService.UpdateRevisionCommand;
import java.math.BigDecimal;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** {@link RevisionFieldRules}: drempels, scalaire velden en de identiteitsbewaking R-REV-X3. */
class RevisionFieldRulesTest {

    /** Eenvoudige builder: elk veld dat niet gezet wordt, blijft {@code null} ("ongewijzigd"). */
    private static final class Cmd {
        String delimiter;
        String quoteChar;
        String supplierField;
        String discountCodeField;
        String basePriceField;
        String descriptionField;
        String changeReason;
        IdentityProfileKind kind;
        Integer canonicalisationVersion;
        BigDecimal creation;
        BigDecimal maxCritical;
        BigDecimal maxRejected;
        BigDecimal bulk;
        BigDecimal deviation;
        BigDecimal tolerance;
        RowIssueSeverity severity;
        Boolean zeroAllowed;
        Boolean negativeAllowed;
        Boolean acknowledge;

        UpdateRevisionCommand build() {
            return new UpdateRevisionCommand(delimiter, quoteChar, null, null, null, null, null, kind,
                    supplierField, null, null, discountCodeField, basePriceField,
                    descriptionField, null, canonicalisationVersion, creation, maxCritical, maxRejected, bulk,
                    deviation, severity, zeroAllowed, negativeAllowed, tolerance, changeReason, acknowledge, null);
        }
    }

    private static UpdateRevisionCommand cmd(Consumer<Cmd> setup) {
        Cmd c = new Cmd();
        setup.accept(c);
        return c.build();
    }

    private static ImportDefinitionRevision revision() {
        return new ImportDefinitionRevision(null, 1, IdentityProfileKind.THREE_PART, "tester");
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BadRequestException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    // --- applyThresholds ---------------------------------------------------------------------------

    @Test
    void applyThresholdsSetsEveryGivenValue() {
        ImportDefinitionRevision revision = revision();
        UpdateRevisionCommand command = cmd(c -> {
            c.creation = new BigDecimal("1");
            c.maxCritical = new BigDecimal("2");
            c.maxRejected = new BigDecimal("3");
            c.bulk = new BigDecimal("4");
            c.deviation = new BigDecimal("5");
            c.severity = RowIssueSeverity.WARNING;
            c.tolerance = new BigDecimal("0.5");
            c.zeroAllowed = true;
            c.negativeAllowed = true;
        });

        RevisionFieldRules.applyThresholds(revision, command);

        assertThat(revision.getCreationThresholdSharePercent()).isEqualByComparingTo("1");
        assertThat(revision.getMaxCriticalSharePercent()).isEqualByComparingTo("2");
        assertThat(revision.getMaxRejectedSharePercent()).isEqualByComparingTo("3");
        assertThat(revision.getBulkIncidentSharePercent()).isEqualByComparingTo("4");
        assertThat(revision.getPriceDeviationPercent()).isEqualByComparingTo("5");
        assertThat(revision.getPriceDeviationSeverity()).isEqualTo(RowIssueSeverity.WARNING);
        assertThat(revision.getPriceDerivationTolerance()).isEqualByComparingTo("0.5");
        assertThat(revision.isBasePriceZeroAllowed()).isTrue();
        assertThat(revision.isBasePriceNegativeAllowed()).isTrue();
    }

    @Test
    void applyThresholdsLeavesExistingValuesWhenNull() {
        ImportDefinitionRevision revision = revision();
        BigDecimal creation = revision.getCreationThresholdSharePercent();
        BigDecimal deviation = revision.getPriceDeviationPercent();
        boolean zero = revision.isBasePriceZeroAllowed();

        RevisionFieldRules.applyThresholds(revision, UpdateRevisionCommand.empty());

        assertThat(revision.getCreationThresholdSharePercent()).isEqualTo(creation);
        assertThat(revision.getPriceDeviationPercent()).isEqualTo(deviation);
        assertThat(revision.isBasePriceZeroAllowed()).isEqualTo(zero);
    }

    @Test
    void applyThresholdsRefusesANegativePercentageWithAFieldCode() {
        BigDecimal negative = new BigDecimal("-1");
        assertThresholdRefused(c -> c.creation = negative, "CREATION_THRESHOLD_SHARE_PERCENT_INVALID");
        assertThresholdRefused(c -> c.maxCritical = negative, "MAX_CRITICAL_SHARE_PERCENT_INVALID");
        assertThresholdRefused(c -> c.maxRejected = negative, "MAX_REJECTED_SHARE_PERCENT_INVALID");
        assertThresholdRefused(c -> c.bulk = negative, "BULK_INCIDENT_SHARE_PERCENT_INVALID");
        assertThresholdRefused(c -> c.deviation = negative, "PRICE_DEVIATION_PERCENT_INVALID");
        assertThresholdRefused(c -> c.tolerance = negative, "PRICE_DERIVATION_TOLERANCE_INVALID");
    }

    private static void assertThresholdRefused(Consumer<Cmd> setup, String code) {
        ImportDefinitionRevision revision = revision();
        UpdateRevisionCommand command = cmd(setup);
        assertCode(() -> RevisionFieldRules.applyThresholds(revision, command), code);
    }

    @Test
    void applyThresholdsAcceptsZero() {
        ImportDefinitionRevision revision = revision();

        RevisionFieldRules.applyThresholds(revision, cmd(c -> c.deviation = BigDecimal.ZERO));

        assertThat(revision.getPriceDeviationPercent()).isEqualByComparingTo("0");
    }

    // --- applyScalars ------------------------------------------------------------------------------

    @Test
    void applyScalarsSetsGivenFieldsTrimmedAndKeepsTheRest() {
        ImportDefinitionRevision revision = revision();
        String charsetBefore = revision.getStructureCharset();

        RevisionFieldRules.applyScalars(revision, cmd(c -> {
            c.delimiter = " ; ";
            c.supplierField = " SUP ";
            c.basePriceField = "PRICE";
            c.descriptionField = "  ";
            c.canonicalisationVersion = 3;
            c.changeReason = " nieuwe export ";
            c.deviation = new BigDecimal("7");
        }));

        assertThat(revision.getStructureDelimiter()).isEqualTo(";");
        assertThat(revision.getIdentitySupplierField()).isEqualTo("SUP");
        assertThat(revision.getRecordBasePriceField()).isEqualTo("PRICE");
        assertThat(revision.getRecordDescriptionField()).isNull();
        assertThat(revision.getRecordCanonicalisationVersion()).isEqualTo(3);
        assertThat(revision.getChangeReason()).isEqualTo("nieuwe export");
        assertThat(revision.getPriceDeviationPercent()).isEqualByComparingTo("7");
        assertThat(revision.getStructureCharset()).isEqualTo(charsetBefore);
    }

    @Test
    void applyScalarsTreatsAnEmptyQuoteCharAsNoQuoting() {
        ImportDefinitionRevision revision = revision();

        RevisionFieldRules.applyScalars(revision, cmd(c -> c.quoteChar = ""));

        assertThat(revision.getStructureQuoteChar()).isNull();
    }

    @Test
    void applyScalarsRefusesBlankRequiredAndTooLongValues() {
        ImportDefinitionRevision revision = revision();
        UpdateRevisionCommand blank = cmd(c -> c.supplierField = " ");
        UpdateRevisionCommand tooLong = cmd(c -> c.delimiter = ";;");

        assertCode(() -> RevisionFieldRules.applyScalars(revision, blank), "SUPPLIER_FIELD_REQUIRED");
        assertCode(() -> RevisionFieldRules.applyScalars(revision, tooLong), "DELIMITER_TOO_LONG");
    }

    @Test
    void applyScalarsRefusesAProfileThatDoesNotFitTheDiscountCodeField() {
        // Enkel het profiel omzetten terwijl er geen kortingscodeveld is: leesbare fout, geen databasefout.
        ImportDefinitionRevision revision = revision();
        UpdateRevisionCommand command = cmd(c -> c.kind = IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE);

        assertCode(() -> RevisionFieldRules.applyScalars(revision, command), "DISCOUNT_CODE_FIELD_REQUIRED");
    }

    // --- requireIdentityConsistency ----------------------------------------------------------------

    @Test
    void identityConsistencyAcceptsMatchingPairs() {
        RevisionFieldRules.requireIdentityConsistency(IdentityProfileKind.THREE_PART, null);
        RevisionFieldRules.requireIdentityConsistency(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, "DISC");
        RevisionFieldRules.requireIdentityConsistency(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, "");
    }

    @Test
    void identityConsistencyRefusesMissingAndSuperfluousDiscountCodeField() {
        assertThatThrownBy(() -> RevisionFieldRules.requireIdentityConsistency(
                IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, null))
                .isInstanceOfSatisfying(BadRequestException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("DISCOUNT_CODE_FIELD_REQUIRED");
                    assertThat(e.getMessage())
                            .isEqualTo("identityProfileKind FOUR_PART_WITH_DISCOUNT_CODE requires discountCodeField");
                });
        assertCode(() -> RevisionFieldRules.requireIdentityConsistency(IdentityProfileKind.THREE_PART, "DISC"),
                "DISCOUNT_CODE_FIELD_INVALID");
    }

    // --- IdentityBefore + R-REV-X3 -----------------------------------------------------------------

    @Test
    void identityBeforeDetectsNoChangeWhenNothingChanged() {
        ImportDefinitionRevision revision = revision();
        revision.setIdentitySupplierField("SUP");
        RevisionFieldRules.IdentityBefore before = RevisionFieldRules.IdentityBefore.of(revision);

        revision.setIdentitySupplierField("SUP");

        assertThat(before.identityChangedIn(revision)).isFalse();
        assertThat(before.canonicalisationVersion()).isEqualTo(revision.getRecordCanonicalisationVersion());
    }

    @Test
    void identityBeforeDetectsAChangeInEachIdentityField() {
        assertIdentityChanged(r -> r.setIdentitySupplierField("OTHER"));
        assertIdentityChanged(r -> r.setIdentitySupplierGroupField("OTHER"));
        assertIdentityChanged(r -> r.setIdentitySupplierReferenceField("OTHER"));
        assertIdentityChanged(r -> r.setIdentityDiscountCodeField("OTHER"));
        assertIdentityChanged(r -> r.setIdentityProfileKind(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE));
    }

    private static void assertIdentityChanged(Consumer<ImportDefinitionRevision> change) {
        ImportDefinitionRevision revision = revision();
        RevisionFieldRules.IdentityBefore before = RevisionFieldRules.IdentityBefore.of(revision);
        change.accept(revision);
        assertThat(before.identityChangedIn(revision)).isTrue();
    }

    @Test
    void identityChangeWithoutAcknowledgementIsRefused() {
        ImportDefinitionRevision revision = revision();
        RevisionFieldRules.IdentityBefore before = RevisionFieldRules.IdentityBefore.of(revision);
        revision.setIdentitySupplierField("OTHER");
        UpdateRevisionCommand notAcknowledged = cmd(c -> c.acknowledge = false);

        assertThatThrownBy(() -> RevisionFieldRules.requireIdentityChangeAcknowledged(
                revision, before, UpdateRevisionCommand.empty()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDENTITY_CHANGE_NOT_ACKNOWLEDGED"));
        assertThatThrownBy(() -> RevisionFieldRules.requireIdentityChangeAcknowledged(
                revision, before, notAcknowledged))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void identityChangeWithAcknowledgementOrWithoutChangeIsAccepted() {
        ImportDefinitionRevision revision = revision();
        RevisionFieldRules.IdentityBefore before = RevisionFieldRules.IdentityBefore.of(revision);

        // Geen wijziging: ook zonder bevestiging toegestaan.
        RevisionFieldRules.requireIdentityChangeAcknowledged(revision, before, UpdateRevisionCommand.empty());

        revision.setIdentitySupplierField("OTHER");
        RevisionFieldRules.requireIdentityChangeAcknowledged(revision, before, cmd(c -> c.acknowledge = true));
    }
}
