package be.dda.catalogimport.service;

import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import java.util.Objects;

/**
 * De veldregels van een revisie, uit {@link SetupService} gehaald (stap 9, S9-a; geen gedragswijziging):
 * drempels en scalaire velden toepassen en de identiteitsbewaking R-REV-X3. Enkel statische methodes zonder
 * eigen toestand; de controle R-REV-X2 blijft in {@link SetupService} omdat ze de bronstaat bevraagt.
 */
final class RevisionFieldRules {

    private RevisionFieldRules() {
    }

    /**
     * Thresholds zijn altijd een percentage (beslissingslog 20/09). {@code null} laat de bestaande
     * default staan; een negatief percentage wordt geweigerd in plaats van stil op 0 gezet.
     */
    static void applyThresholds(ImportDefinitionRevision revision, SetupService.RevisionThresholds command) {
        if (command.creationThresholdSharePercent() != null) {
            revision.setCreationThresholdSharePercent(
                    SetupInput.requireNotNegative(command.creationThresholdSharePercent(), "creationThresholdSharePercent"));
        }
        if (command.maxCriticalSharePercent() != null) {
            revision.setMaxCriticalSharePercent(
                    SetupInput.requireNotNegative(command.maxCriticalSharePercent(), "maxCriticalSharePercent"));
        }
        if (command.maxRejectedSharePercent() != null) {
            revision.setMaxRejectedSharePercent(
                    SetupInput.requireNotNegative(command.maxRejectedSharePercent(), "maxRejectedSharePercent"));
        }
        if (command.bulkIncidentSharePercent() != null) {
            revision.setBulkIncidentSharePercent(
                    SetupInput.requireNotNegative(command.bulkIncidentSharePercent(), "bulkIncidentSharePercent"));
        }
        if (command.priceDeviationPercent() != null) {
            revision.setPriceDeviationPercent(
                    SetupInput.requireNotNegative(command.priceDeviationPercent(), "priceDeviationPercent"));
        }
        if (command.priceDeviationSeverity() != null) {
            revision.setPriceDeviationSeverity(command.priceDeviationSeverity());
        }
        if (command.priceDerivationTolerance() != null) {
            revision.setPriceDerivationTolerance(
                    SetupInput.requireNotNegative(command.priceDerivationTolerance(), "priceDerivationTolerance"));
        }
        if (command.basePriceZeroAllowed() != null) {
            revision.setBasePriceZeroAllowed(command.basePriceZeroAllowed());
        }
        if (command.basePriceNegativeAllowed() != null) {
            revision.setBasePriceNegativeAllowed(command.basePriceNegativeAllowed());
        }
    }

    /**
     * Zet elk veld dat het verzoek noemt; {@code null} laat de bestaande waarde staan. De regels per veld
     * zijn letterlijk die van {@link SetupService#createRevision(long, SetupService.CreateRevisionCommand, ActorIdentity)} —
     * verplichte velden via {@link SetupInput#requireText}, optionele via {@link SetupInput#optionalText} (waarbij {@code ""}
     * "uitdrukkelijk leeg" betekent) en de drempels via de gedeelde {@link #applyThresholds}.
     */
    static void applyScalars(ImportDefinitionRevision revision, SetupService.UpdateRevisionCommand command) {
        if (command.identityProfileKind() != null) {
            revision.setIdentityProfileKind(command.identityProfileKind());
        }
        // Legacy-config S6 (M3): leverancier en groep komen uit precies één van kolom of vaste waarde. Een lege tekst
        // wist (zoals bij de optionele velden), zodat wie overstapt de andere bron uitdrukkelijk wist; blijft er daarna
        // geen bron over, dan volgt dezelfde 400 als vroeger (SUPPLIER_FIELD_REQUIRED). Nooit een stille wissel.
        if (command.supplierField() != null) {
            revision.setIdentitySupplierField(SetupInput.optionalText(command.supplierField(), "supplierField",
                    SetupService.MAX_FIELD_REFERENCE_LENGTH));
        }
        if (command.supplierFixedValue() != null) {
            revision.setIdentitySupplierFixedValue(SetupInput.optionalText(command.supplierFixedValue(),
                    "supplierFixedValue", SetupService.MAX_IDENTITY_FIXED_VALUE_LENGTH));
        }
        if (command.supplierGroupField() != null) {
            revision.setIdentitySupplierGroupField(SetupInput.optionalText(command.supplierGroupField(),
                    "supplierGroupField", SetupService.MAX_FIELD_REFERENCE_LENGTH));
        }
        if (command.supplierGroupFixedValue() != null) {
            revision.setIdentitySupplierGroupFixedValue(SetupInput.optionalText(command.supplierGroupFixedValue(),
                    "supplierGroupFixedValue", SetupService.MAX_IDENTITY_FIXED_VALUE_LENGTH));
        }
        // Enkel het deel dat het verzoek noemt: een verzoek dat de identiteit niet aanraakt, beoordeelt ze ook niet
        // (zelfde gedrag als vóór S6; de databasecheck blijft het vangnet).
        if (command.supplierField() != null || command.supplierFixedValue() != null) {
            requireOneSource(revision.getIdentitySupplierField(), revision.getIdentitySupplierFixedValue(),
                    "supplierField", "supplierFixedValue");
        }
        if (command.supplierGroupField() != null || command.supplierGroupFixedValue() != null) {
            requireOneSource(revision.getIdentitySupplierGroupField(), revision.getIdentitySupplierGroupFixedValue(),
                    "supplierGroupField", "supplierGroupFixedValue");
        }
        if (command.identityUppercase() != null) {
            revision.setIdentityUppercase(command.identityUppercase());
        }
        if (command.supplierReferenceField() != null) {
            revision.setIdentitySupplierReferenceField(SetupInput.requireText(command.supplierReferenceField(),
                    "supplierReferenceField", SetupService.MAX_FIELD_REFERENCE_LENGTH));
        }
        if (command.discountCodeField() != null) {
            revision.setIdentityDiscountCodeField(SetupInput.optionalText(command.discountCodeField(),
                    "discountCodeField", SetupService.MAX_FIELD_REFERENCE_LENGTH));
        }
        // Het paar profiel + kortingscodeveld wordt beoordeeld op de toestand ná de wijziging, niet op wat
        // het verzoek meebracht: wie enkel het profiel omzet, moet hier al de leesbare fout krijgen in
        // plaats van een databasefout op ck_import_definition_revision_identity.
        requireIdentityConsistency(revision.getIdentityProfileKind(), revision.getIdentityDiscountCodeField());
        if (command.basePriceField() != null) {
            revision.setRecordBasePriceField(
                    SetupInput.requireText(command.basePriceField(), "basePriceField", SetupService.MAX_FIELD_REFERENCE_LENGTH));
        }
        if (command.descriptionField() != null) {
            revision.setRecordDescriptionField(SetupInput.optionalText(command.descriptionField(), "descriptionField",
                    SetupService.MAX_FIELD_REFERENCE_LENGTH));
        }
        if (command.currencyField() != null) {
            revision.setRecordCurrencyField(SetupInput.optionalText(command.currencyField(), "currencyField",
                    SetupService.MAX_FIELD_REFERENCE_LENGTH));
        }
        if (command.delimiter() != null) {
            revision.setStructureDelimiter(SetupInput.requireText(command.delimiter(), "delimiter", 1));
        }
        if (command.quoteChar() != null) {
            // "" betekent hier uitdrukkelijk: deze bron kent geen quoting.
            revision.setStructureQuoteChar(command.quoteChar().isEmpty() ? null
                    : SetupInput.requireText(command.quoteChar(), "quoteChar", 1));
        }
        if (command.charset() != null) {
            revision.setStructureCharset(SetupInput.requireText(command.charset(), "charset", 40));
        }
        if (command.hasHeader() != null) {
            revision.setStructureHasHeader(command.hasHeader());
        }
        if (command.headerLineNumber() != null) {
            revision.setStructureHeaderLineNumber(command.headerLineNumber());
        }
        if (command.fieldReferenceKind() != null) {
            revision.setStructureFieldReferenceKind(
                    SetupInput.requireText(command.fieldReferenceKind(), "fieldReferenceKind", 20));
        }
        if (command.expectedColumnCount() != null) {
            revision.setStructureExpectedColumnCount(command.expectedColumnCount());
        }
        if (command.canonicalisationVersion() != null) {
            revision.setRecordCanonicalisationVersion(command.canonicalisationVersion());
        }
        applyThresholds(revision, command);
        if (command.changeReason() != null) {
            revision.setChangeReason(
                    SetupInput.optionalText(command.changeReason(), "changeReason", SetupService.MAX_CHANGE_REASON_LENGTH));
        }
    }

    /**
     * De identiteits- en canonicalisatievelden zoals ze <b>vóór</b> de wijziging op de revisie stonden:
     * R-REV-X2 en R-REV-X3 zijn beide een vergelijking tussen oud en nieuw, niet een controle op wat het
     * verzoek meebracht. Wie een veld op exact dezelfde waarde zet, wijzigt niets en heeft dus ook geen
     * bevestiging nodig.
     */
    record IdentityBefore(IdentityProfileKind profileKind, String supplierField,
                                  String supplierGroupField, String supplierReferenceField,
                                  String discountCodeField, int canonicalisationVersion,
                                  String supplierFixedValue, String supplierGroupFixedValue,
                                  boolean identityUppercase) {

        static IdentityBefore of(ImportDefinitionRevision revision) {
            return new IdentityBefore(revision.getIdentityProfileKind(), revision.getIdentitySupplierField(),
                    revision.getIdentitySupplierGroupField(), revision.getIdentitySupplierReferenceField(),
                    revision.getIdentityDiscountCodeField(), revision.getRecordCanonicalisationVersion(),
                    revision.getIdentitySupplierFixedValue(), revision.getIdentitySupplierGroupFixedValue(),
                    revision.isIdentityUppercase());
        }

        /**
         * R-REV-X3: het profiel of een van de vier identiteitsvelden verschilt van de huidige waarde. Sinds
         * legacy-config S6 ook een vaste leverancier/groep of de hoofdletterschakelaar: elk van die drie verandert de
         * canonieke identiteitstekst.
         */
        boolean identityChangedIn(ImportDefinitionRevision revision) {
            return profileKind != revision.getIdentityProfileKind()
                    || !Objects.equals(supplierField, revision.getIdentitySupplierField())
                    || !Objects.equals(supplierGroupField, revision.getIdentitySupplierGroupField())
                    || !Objects.equals(supplierReferenceField, revision.getIdentitySupplierReferenceField())
                    || !Objects.equals(discountCodeField, revision.getIdentityDiscountCodeField())
                    || !Objects.equals(supplierFixedValue, revision.getIdentitySupplierFixedValue())
                    || !Objects.equals(supplierGroupFixedValue, revision.getIdentitySupplierGroupFixedValue())
                    || identityUppercase != revision.isIdentityUppercase();
        }

        /** Legacy-config S6: de vaste leverancier, of de vorm waarin ze gecontroleerd wordt, wijzigt. */
        boolean supplierFixedValueChangedIn(ImportDefinitionRevision revision) {
            return !Objects.equals(supplierFixedValue, revision.getIdentitySupplierFixedValue())
                    || identityUppercase != revision.isIdentityUppercase();
        }

        /** Legacy-config S6: de vaste groep, haar leverancier of de vorm waarin ze gecontroleerd wordt, wijzigt. */
        boolean groupFixedValueChangedIn(ImportDefinitionRevision revision) {
            return !Objects.equals(supplierGroupFixedValue, revision.getIdentitySupplierGroupFixedValue())
                    || supplierFixedValueChangedIn(revision);
        }
    }

    /**
     * Legacy-config S6 (beslissing M3): leverancier en groep komen elk uit precies één van bronkolom of vaste waarde —
     * dezelfde regel als de databasechecks {@code ck_import_definition_revision_supplier_source}/{@code _group_source}
     * (changeset 022-2), hier vóór het flushen zodat het een leesbare 400 wordt.
     * <ul>
     *   <li>geen van beide → {@code SUPPLIER_FIELD_REQUIRED} / {@code SUPPLIER_GROUP_FIELD_REQUIRED} (zelfde code als
     *       vóór S6);</li>
     *   <li>beide → {@code SUPPLIER_FIXED_VALUE_INVALID} / {@code SUPPLIER_GROUP_FIXED_VALUE_INVALID}.</li>
     * </ul>
     */
    static void requireOneIdentitySource(ImportDefinitionRevision revision) {
        requireOneSource(revision.getIdentitySupplierField(), revision.getIdentitySupplierFixedValue(),
                "supplierField", "supplierFixedValue");
        requireOneSource(revision.getIdentitySupplierGroupField(), revision.getIdentitySupplierGroupFixedValue(),
                "supplierGroupField", "supplierGroupFixedValue");
    }

    private static void requireOneSource(String field, String fixedValue, String fieldName, String fixedName) {
        if (field == null && fixedValue == null) {
            throw new BadRequestException(SetupInput.fieldCode(fieldName, "REQUIRED"), fieldName
                    + " must not be blank (or give " + fixedName + ")");
        }
        if (field != null && fixedValue != null) {
            throw new BadRequestException(SetupInput.fieldCode(fixedName, "INVALID"), fieldName + " and "
                    + fixedName + " are mutually exclusive: exactly one of a source field or a fixed value "
                    + "determines this identity part (send \"\" to clear the other one)");
        }
    }

    /**
     * R-REV-X3 (§5). Patroon {@code MATERIALISATION_MODE_REQUIRED}: er is geen default en geen stille
     * correctie, want een geraden keuze bepaalt hier of de volledige catalogus van deze koppeling opnieuw
     * als nieuw beschouwd wordt.
     */
    static void requireIdentityChangeAcknowledged(ImportDefinitionRevision revision,
                                                          IdentityBefore before,
                                                          SetupService.UpdateRevisionCommand command) {
        if (!before.identityChangedIn(revision)
                || Boolean.TRUE.equals(command.acknowledgeIdentityChange())) {
            return;
        }
        throw new ConflictException("IDENTITY_CHANGE_NOT_ACKNOWLEDGED", "Revision " + revision.getId()
                + " changes the offer identity (identityProfileKind or one of the identity fields). That "
                + "changes the canonical identity text, so every existing offer comes back as NEW. Resend "
                + "with acknowledgeIdentityChange=true if that is intended; nothing was saved");
    }

    /**
     * De databasecheck {@code ck_import_definition_revision_identity} houdt profiel en kortingscodeveld
     * consistent: {@code null} (niet gemapt) en {@code ""} (expliciet leeg) zijn verschillende toestanden.
     * Deze controle staat vóór het flushen, zodat een verkeerde combinatie een leesbare 400 oplevert in
     * plaats van een databasefout.
     */
    static void requireIdentityConsistency(IdentityProfileKind identityKind, String discountCodeField) {
        if (identityKind == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE && discountCodeField == null) {
            throw new BadRequestException(SetupInput.fieldCode("discountCodeField", "REQUIRED"),
                    "identityProfileKind FOUR_PART_WITH_DISCOUNT_CODE requires discountCodeField");
        }
        if (identityKind == IdentityProfileKind.THREE_PART && discountCodeField != null) {
            throw new BadRequestException(SetupInput.fieldCode("discountCodeField", "INVALID"),
                    "identityProfileKind THREE_PART must not carry a "
                    + "discountCodeField; use FOUR_PART_WITH_DISCOUNT_CODE when the discount code is mapped");
        }
    }
}
