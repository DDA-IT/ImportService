package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.IssueCaseDao;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Koppelt de issuegroepen van één gescreende levering aan hun <b>behandelgeval</b> en past de
 * heropeningsregel toe (docs/design/issue-case-design.md par. 2 en par. 3, bouwstap S2-B1b,
 * R-CASE-01 t/m R-CASE-04).
 *
 * <h2>Waar dit in de screening hangt</h2>
 * In {@code DeliveryScreeningService.aggregate(Context)}, direct ná
 * {@link IssueAggregationService#aggregate}. Bewust dáár en niet eerder: die pass eindigt met
 * {@code deleteBelowThreshold(...)}, dus pas daarna staat vast welke groepen blijven bestaan. Een
 * geval wordt zo nooit aangemaakt voor een groep die meteen daarna verwijderd wordt. Dat ene hookpunt
 * dekt zowel het normale pad ({@code mutate()}) als het geblokkeerde pad ({@code block()}) - beide
 * gaan door dezelfde private helper. De signatuur van {@link IssueAggregationService} blijft
 * ongewijzigd.
 *
 * <h2>Wat dit uitdrukkelijk <b>niet</b> doet (D3)</h2>
 * Geen enkele wijziging aan {@code validation_result}, {@code import_batch.status},
 * {@code import_mutation.status}, een drempel, {@code catalog_source_state} of een bundelbeslissing.
 * Een behandelgeval is administratief: het laat niets door en houdt niets tegen. Deze service
 * schrijft uitsluitend in {@code issue_case}, {@code issue_case_event} en
 * {@code import_issue_group.issue_case_id}.
 *
 * <h2>Transactie</h2>
 * Standaardpropagatie, net als {@link IssueAggregationService}: draait de aanroeper al in een
 * transactie (de afrondende transactie van een geblokkeerde levering), dan wordt die gebruikt in
 * plaats van een tweede te openen. Er is wél altijd een transactie nodig, want stap 0 zet een
 * {@code for update} op de koppeling.
 *
 * <h2>Idempotent</h2>
 * Tweemaal dezelfde batch synchroniseren levert exact dezelfde toestand op: geen tweede geval, geen
 * dubbel event, geen dubbele telling. Elke stap van {@link IssueCaseDao} is een
 * {@code insert ... where not exists}, een herberekening, of een {@code update} waarvan de
 * statuswaarde zelf het predicaat is.
 */
@Service
public class IssueCaseSyncService {

    /**
     * Wat de synchronisatie vastgesteld heeft; alle drie zijn metingen.
     *
     * @param createdCases       het aantal nieuw aangemaakte behandelgevallen
     * @param linkedObservations het aantal issuegroepen dat aan zijn geval gekoppeld is
     * @param reopenedCases      het aantal gevallen dat door deze waarneming heropend is (par. 2)
     */
    public record CaseSync(int createdCases, int linkedObservations, int reopenedCases) {

        static final CaseSync NOTHING = new CaseSync(0, 0, 0);
    }

    private final IssueCaseDao issueCases;
    private final TransactionTemplate transaction;

    public IssueCaseSyncService(IssueCaseDao issueCases, PlatformTransactionManager transactionManager) {
        this.issueCases = issueCases;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Voert de vijf stappen van ontwerp par. 3 uit voor één batch, in die volgorde.
     * <p>
     * Heeft de batch geen enkele issuegroep, dan is er niets te synchroniseren: dan wordt de koppeling
     * ook niet vergrendeld. De overgrote meerderheid van de leveringen komt hier dus zonder één
     * schrijfactie doorheen.
     *
     * @param batchId              de gescreende batch
     * @param importLinkId         de koppeling van die batch; identiteitsdeel 1 van het geval (D1) en
     *                             de sleutel waarop gelijktijdige screenings geserialiseerd worden
     * @param definitionRevisionId de revisie waaronder deze batch gescreend is; de input van de
     *                             heropeningsregel voor een afgewezen geval (R-CASE-02/R-CASE-03)
     */
    public CaseSync sync(long batchId, long importLinkId, long definitionRevisionId) {
        return transaction.execute(status -> {
            if (!issueCases.hasGroups(batchId)) {
                return CaseSync.NOTHING;
            }
            Instant now = Instant.now();
            // Stap 0: serialiseren op de koppeling.
            issueCases.lockImportLink(importLinkId);
            // Stap 1 en 2: het geval en zijn geboorteregel.
            int created = issueCases.insertMissingCases(batchId, importLinkId, now);
            issueCases.insertCreatedEvents(batchId, importLinkId, now);
            // Stap 3: de waarneming aan het geval hangen.
            int linked = issueCases.linkGroups(batchId, importLinkId);
            // Stap 4 en 5: hertellen, dan de heropeningsregel. De twee regels van par. 2 sluiten
            // elkaar uit (een geval heeft één status), dus de som is het werkelijke aantal.
            List<Long> observed = issueCases.findCaseIdsByBatchId(batchId);
            issueCases.recount(observed, now);
            int reopened = issueCases.reopenOnRecurrence(observed, batchId, now)
                    + issueCases.reopenOnRuleChange(observed, batchId, definitionRevisionId, now);
            return new CaseSync(created, linked, reopened);
        });
    }
}
