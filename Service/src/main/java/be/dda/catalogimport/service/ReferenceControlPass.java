package be.dda.catalogimport.service;

import static be.dda.catalogimport.service.DeliveryScreeningService.CODE_IDENTITY_REFERENCE_INCIDENT;
import static be.dda.catalogimport.service.DeliveryScreeningService.CODE_REFERENCE_LINK_PROPOSED;

import be.dda.catalogimport.dao.IssueGroupDao;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.ReferenceControlDao;
import be.dda.catalogimport.dao.ReferenceControlDao.IncidentMutation;
import be.dda.catalogimport.dao.ReferenceControlDao.MatchUpdate;
import be.dda.catalogimport.dao.ReferenceControlDao.ReferenceCandidate;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.RowIssueDao.IssueRow;
import be.dda.catalogimport.service.DeliveryScreeningService.Context;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.IssueSignature;
import be.dda.catalogimport.service.support.IssueTally;
import be.dda.catalogimport.service.support.ReferenceControlEvaluator;
import be.dda.catalogimport.service.support.ReferenceControlEvaluator.RecordOutcome;
import be.dda.catalogimport.service.support.ReferenceControlEvaluator.ReferenceOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * De beoordeling van één chunk van de referentiecontrolepass (stap E2), uit
 * {@link DeliveryScreeningService} gehaald (stap 9, S9-b; geen gedragswijziging). Bewust geen
 * Spring-bean en zonder eigen transactie of verwerkingsclaim: de poortcheck, de veldnaamopzoeking, het
 * hervatpunt, de chunklus en elke transactie (met haar fencing-update) blijven in
 * {@link DeliveryScreeningService}; deze klasse levert enkel de body die binnen die bestaande transactie
 * draait. De DAO's zijn dezelfde instanties als die van de screeningservice.
 */
final class ReferenceControlPass {

    private final ReferenceControlDao referenceControl;
    private final RowIssueDao rowIssues;
    private final IssueGroupDao issueGroups;
    private final int maxSampleRowsPerCode;

    ReferenceControlPass(ReferenceControlDao referenceControl, RowIssueDao rowIssues, IssueGroupDao issueGroups,
                         int maxSampleRowsPerCode) {
        this.referenceControl = referenceControl;
        this.rowIssues = rowIssues;
        this.issueGroups = issueGroups;
        this.maxSampleRowsPerCode = maxSampleRowsPerCode;
    }

    void evaluateReferenceChunk(Context context, Map<String, String> fieldNames,
                                ReferencePassProgress pass, long fromExclusive, long toInclusive) {
        List<ReferenceCandidate> candidates = referenceControl.findCandidates(context.batchId(),
                context.importLinkId(), context.libraryCode(), fromExclusive, toInclusive);
        if (candidates.isEmpty()) {
            return;
        }
        List<MatchUpdate> updates = new ArrayList<>();
        List<Long> heldRows = new ArrayList<>();
        List<IncidentMutation> incidents = new ArrayList<>();
        List<IssueRow> issues = new ArrayList<>();
        Instant now = Instant.now();
        // De DAO levert op regelnummer geordend; één regel is dus één aaneengesloten blok.
        List<ReferenceCandidate> group = new ArrayList<>();
        for (ReferenceCandidate candidate : candidates) {
            if (!group.isEmpty() && group.get(0).rowNumber() != candidate.rowNumber()) {
                evaluateRecord(context, fieldNames, pass, group, updates, heldRows, incidents, issues, now);
                group = new ArrayList<>();
            }
            group.add(candidate);
        }
        evaluateRecord(context, fieldNames, pass, group, updates, heldRows, incidents, issues, now);

        referenceControl.markMatchResults(context.batchId(), updates);
        referenceControl.classifyIdentityIncidents(context.batchId(), heldRows,
                MutationDao.IDENTITY_INCIDENT_CLASSIFICATION);
        referenceControl.insertIncidentMutations(context.mutationContext(), incidents, now);
        rowIssues.insertBatch(issues);
        // De aantallen van deze chunk, in dezelfde transactie als haar hervatpunt: een hervatte pass
        // telt daardoor nooit een incident dubbel.
        issueGroups.accumulate(context.batchId(), pass.tally.drain());
    }

    private void evaluateRecord(Context context, Map<String, String> fieldNames,
                                ReferencePassProgress pass, List<ReferenceCandidate> group,
                                List<MatchUpdate> updates, List<Long> heldRows,
                                List<IncidentMutation> incidents, List<IssueRow> issues, Instant now) {
        if (group.isEmpty()) {
            return;
        }
        long rowNumber = group.get(0).rowNumber();
        RecordOutcome outcome = ReferenceControlEvaluator.evaluate(rowNumber, group);
        for (ReferenceOutcome reference : outcome.references()) {
            updates.add(new MatchUpdate(rowNumber, reference.referenceType(),
                    reference.result().name(), reference.matchedSourceStateId()));
        }
        if (outcome.hasIncident()) {
            heldRows.add(rowNumber);
            for (ReferenceOutcome incident : outcome.incidents()) {
                String fieldName = fieldNames.getOrDefault(incident.referenceType(),
                        incident.referenceType());
                String message = ReferenceControlEvaluator.message(incident, fieldName);
                incidents.add(new IncidentMutation(rowNumber, incident.referenceType(),
                        incident.result().name(), incident.beforeValue(), incident.afterValue(),
                        message));
                // Élk incident telt in zijn groep, ook boven de voorbeeldcap: de groepering op
                // type + soort incident is precies wat een bulktransformatie zichtbaar maakt
                // (R-REF-07).
                IssueSignature.Signature signature = IssueSignature.identity(incident.referenceType(),
                        incident.result().name());
                pass.tally.add(CODE_IDENTITY_REFERENCE_INCIDENT, signature, null, rowNumber, now);
                if (pass.recordedIncidents >= maxSampleRowsPerCode) {
                    continue;
                }
                pass.recordedIncidents++;
                issues.add(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                        rowNumber, CODE_IDENTITY_REFERENCE_INCIDENT, fieldName,
                        incident.afterValue(), incident.result().name(), message, signature, null,
                        now));
            }
            return;
        }
        if (!outcome.linkProposed()) {
            return;
        }
        // R-ID-03: geen incident maar een vaststelling - deze nieuwe aanbieding hoort bij hetzelfde
        // artikel als een bestaande. De aanbieding wordt gewoon aangemaakt en de bestaande
        // aanbiedingsidentiteit blijft onaangeroerd.
        ReferenceOutcome matching = outcome.references().stream()
                .filter(reference -> outcome.proposedSourceStateId()
                        .equals(reference.matchedSourceStateId()))
                .findFirst().orElse(null);
        String fieldName = matching == null ? null
                : fieldNames.getOrDefault(matching.referenceType(), matching.referenceType());
        String value = matching == null ? null : matching.afterValue();
        pass.tally.add(CODE_REFERENCE_LINK_PROPOSED, IssueSignature.generic(fieldName), null,
                rowNumber, now);
        if (pass.recordedProposed >= maxSampleRowsPerCode) {
            return;
        }
        pass.recordedProposed++;
        issues.add(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(), rowNumber,
                CODE_REFERENCE_LINK_PROPOSED, fieldName, value,
                String.valueOf(outcome.proposedSourceStateId()),
                (fieldName == null ? "criticalReference" : fieldName) + ": '" + value
                        + "' already identifies offer " + outcome.proposedSourceStateId()
                        + " in this library; this is another supplier offer for the same article. The "
                        + "new offer is created under the normal creation policy and the existing offer "
                        + "identity is never replaced", now));
    }

    /**
     * Lopende stand van één referentiecontrolepass; enkel binnen
     * {@code DeliveryScreeningService#controlReferences(Context)}.
     */
    static final class ReferencePassProgress {
        /** Reeds bewaarde voorbeeldrijen per code, inclusief die van een eerdere doorloop. */
        private long recordedIncidents;
        private long recordedProposed;
        /**
         * De werkelijke aantallen per signatuur van de lopende chunk. Ze worden per chunk
         * weggeschreven, samen met het hervatpunt: het totaal over de hele batch staat in
         * {@code import_issue_group} en niet in dit object, zodat een hervatte pass niet met een
         * deelaantal eindigt.
         */
        private final IssueTally tally = new IssueTally();

        ReferencePassProgress(long recordedIncidents, long recordedProposed) {
            this.recordedIncidents = recordedIncidents;
            this.recordedProposed = recordedProposed;
        }
    }
}
