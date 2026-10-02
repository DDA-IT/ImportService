package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.IssueGroupDao;
import be.dda.catalogimport.dao.PriceDeviationDao;
import be.dda.catalogimport.dao.PriceDeviationDao.DeviationRow;
import be.dda.catalogimport.dao.PriceDeviationDao.MissingReferenceCounts;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.RowIssueDao.IssueRow;
import be.dda.catalogimport.service.DeliveryScreeningService.Context;
import be.dda.catalogimport.service.DeliveryScreeningService.PriceControl;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.IssueSignature;
import be.dda.catalogimport.service.support.IssueTally;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator.Reference;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator.ReferenceKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * De beoordeling van één chunk van de prijsafwijkingspass (stap E3) en de samenvattende melding over
 * ontbrekende referenties, uit {@link DeliveryScreeningService} gehaald (stap 9, S9-b; geen
 * gedragswijziging). Bewust geen Spring-bean en zonder eigen transactie of verwerkingsclaim: de
 * poortcheck, de veldnaamopzoeking, het hervatpunt, de chunklus en elke transactie (met haar
 * fencing-update) blijven in {@link DeliveryScreeningService}; deze klasse levert enkel de body die
 * binnen die bestaande transactie draait. De DAO's zijn dezelfde instanties als die van de
 * screeningservice.
 */
final class PriceControlPass {

    private final PriceDeviationDao deviations;
    private final RowIssueDao rowIssues;
    private final IssueGroupDao issueGroups;
    private final int maxSampleRowsPerCode;

    PriceControlPass(PriceDeviationDao deviations, RowIssueDao rowIssues, IssueGroupDao issueGroups,
                     int maxSampleRowsPerCode) {
        this.deviations = deviations;
        this.rowIssues = rowIssues;
        this.issueGroups = issueGroups;
        this.maxSampleRowsPerCode = maxSampleRowsPerCode;
    }

    void evaluateChunk(Context context, PriceControl control, Map<String, String> fieldNames,
                       PricePassProgress pass, long fromExclusive, long toInclusive) {
        List<DeviationRow> candidates = deviations.findCandidates(context.batchId(),
                context.importLinkId(), control.shortWindow(), control.longWindow(), fromExclusive,
                toInclusive);
        if (candidates.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        List<IssueRow> issues = new ArrayList<>();
        for (DeviationRow candidate : candidates) {
            PriceDeviationEvaluator.Result result = PriceDeviationEvaluator.evaluate(
                    candidate.componentCode(),
                    fieldNames.getOrDefault(candidate.componentCode(), candidate.componentCode()),
                    candidate.newAmount(),
                    Reference.previous(candidate.previousAmount()),
                    new Reference(ReferenceKind.AVG50, candidate.averageShort(), control.shortWindow()),
                    new Reference(ReferenceKind.AVG200, candidate.averageLong(), control.longWindow()),
                    control.deviationPercent());
            if (!result.exceeded()) {
                continue;
            }
            // De groepering van R-PRI-14: zelfde component én zelfde richting. Élke overschrijding
            // telt mee, ook boven de voorbeeldcap - anders zou een bulkincident afhangen van hoeveel
            // voorbeelden er toevallig bewaard zijn.
            IssueSignature.Signature signature = IssueSignature.price(result.componentCode(),
                    result.direction());
            pass.tally.add(PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED, signature,
                    control.severity(), candidate.rowNumber(), now);
            // Dezelfde voorbeeldcap als elke andere foutcode (R-ISS-03): boven de cap worden er geen
            // voorbeelden meer bewaard, maar het aantal blijft geteld.
            if (pass.recorded >= maxSampleRowsPerCode) {
                continue;
            }
            pass.recorded++;
            issues.add(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                    candidate.rowNumber(), PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED,
                    result.fieldName(), result.newAmount().toPlainString(), result.expectedValue(),
                    result.message(), signature, control.severity(), now));
        }
        rowIssues.insertBatch(issues);
        // De aantallen van deze chunk, in dezelfde transactie als haar hervatpunt.
        issueGroups.accumulate(context.batchId(), pass.tally.drain());
    }

    /**
     * R-PRI-11: één samenvattende INFO-melding per levering over de referenties die ontbraken, nooit
     * één per record — bij een eerste historiekopbouw zou dat miljoenen identieke rijen opleveren.
     * De aantallen worden over de <b>volledige</b> batch uit de database geteld, zodat ze ook na een
     * hervatte pass kloppen; bestaat de melding al, dan wordt er geen tweede geschreven.
     */
    void recordMissingReferenceSummary(Context context, PriceControl control) {
        if (rowIssues.countByBatchIdAndIssueCode(context.batchId(),
                PriceDeviationEvaluator.CODE_PRICE_REFERENCE_NOT_AVAILABLE) > 0) {
            return;
        }
        MissingReferenceCounts counts = deviations.countMissingReferences(context.batchId(),
                context.importLinkId(), control.shortWindow(), control.longWindow());
        if (counts.total() == 0) {
            return;
        }
        rowIssues.insertBatch(List.of(ImportIssueCatalog.issue(context.batchId(),
                context.deliveryFileId(), null,
                PriceDeviationEvaluator.CODE_PRICE_REFERENCE_NOT_AVAILABLE, null, null, null,
                "priceReferences: '" + counts.total() + "' of " + (counts.evaluated() * 3)
                        + " price comparisons had no usable reference ("
                        + ReferenceKind.PREVIOUS.notAvailableStatus() + "=" + counts.previousMissing()
                        + ", " + ReferenceKind.AVG50.notAvailableStatus() + "="
                        + counts.shortAverageMissing()
                        + ", " + ReferenceKind.AVG200.notAvailableStatus() + "="
                        + counts.longAverageMissing()
                        + "); no deviation was computed for those and no price or percentage was changed",
                Instant.now())));
    }

    /**
     * Lopende stand van één prijscontrolepass; enkel binnen
     * {@code DeliveryScreeningService#controlPrices(Context)}.
     */
    static final class PricePassProgress {
        /** Aantal reeds bewaarde voorbeeldrijen met deze foutcode, inclusief een eerdere doorloop. */
        private long recorded;
        /**
         * De werkelijke aantallen per signatuur van de lopende chunk; ze worden per chunk
         * weggeschreven, samen met het hervatpunt.
         */
        private final IssueTally tally = new IssueTally();

        PricePassProgress(long alreadyRecorded) {
            this.recorded = alreadyRecorded;
        }
    }
}
