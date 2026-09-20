package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.IssueGroupDao;
import be.dda.catalogimport.dao.IssueGroupDao.CodeTotals;
import be.dda.catalogimport.dao.IssueGroupDao.GroupRow;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.RowIssueDao.IssueRow;
import be.dda.catalogimport.domain.DeviationDirection;
import be.dda.catalogimport.domain.IssueIncidentKind;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.IssueSignature;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pass E4 uit ontwerp fase 3 par. 3.1: de <b>aggregatiepass</b>. Ze draait eenmalig, ná de
 * detectiepassen (E1 classificatie, E2 referentiecontrole, E3 prijscontrole) en vóór de
 * mutatiegeneratie (E5), en vat gelijksoortige vaststellingen samen in {@code import_issue_group}
 * (R-THR-04, R-ISS-03, R-PRI-14, R-REF-07).
 *
 * <h2>Waarom deze pass bestaat</h2>
 * Een leverancier die zijn volledige catalogus 20% duurder maakt, levert geen 40.000 losse
 * problemen op maar één gebeurtenis. Tienduizend losse meldingen zijn voor een mens onleesbaar en
 * verbergen juist het patroon (businessanalyse par. 14.12/par. 16.2). Deze pass telt daarom niet
 * alleen samen, ze geeft ook aan wanneer een reeks omslaat in een <b>bulkincident</b>.
 *
 * <h2>De twee drempels, en hoe ze zich verhouden</h2>
 * <ul>
 *   <li><b>Groeperen</b> vanaf {@value #GROUP_MIN_OCCURRENCES} gelijke signaturen. Dit is een
 *       <b>technische</b> groeperingsdrempel, geen leveringsdrempel: onder tien voorvallen is er
 *       geen patroon maar een handvol losse fouten, en een groep zou meer verbergen dan tonen.</li>
 *   <li><b>Bulkincident</b> wanneer het aandeel in de gecontroleerde scope
 *       {@code bulk_incident_share_percent} van de revisie overschrijdt (default 1%). De
 *       vergelijking gebeurt in decimale rekenkunde: {@code aantal × 100 > percentage × scope},
 *       nooit met drijvende komma en nooit met een deling die kan afronden. Exact op de grens is
 *       <b>niet</b> overschreden.</li>
 * </ul>
 * De groeperingsdrempel gaat voor: zonder groep is er ook geen bulkincident. Een levering van 50
 * records met 6 identieke fouten haalt de 1%-regel ruimschoots (12%), maar blijft onder de tien en
 * levert dus zes gewone regelfouten op. Bij een kleine scope werkt een percentage dus grof — dat is
 * de bewuste keuze van de mens (beslissingslog 20/09): wie een kleine leverancier strenger of
 * losser wil beoordelen, zet het percentage van die revisie anders.
 * <p>
 * <b>Er is geen absolute ondergrens meer</b> (het ontwerp kende "100 records of 1%"). Elke drempel
 * is altijd een percentage van de omvang: een vaste grens van honderd maakt een koppeling met
 * tweehonderd artikelen onbruikbaar en een koppeling met een miljoen artikelen overgevoelig. De
 * instelling is bewust <b>niet</b> {@code creation_threshold_share_percent}: dat is de grens van de
 * creatiedrempel, een andere regel over een andere hoeveelheid. Eén instelling voor twee regels zou
 * betekenen dat wie de creatiedrempel verruimt, stilzwijgend ook de bulkdetectie uitschakelt.
 *
 * <h2>Scope: waartegen het percentage gemeten wordt</h2>
 * Per incidentsoort, en telkens de hoeveelheid die werkelijk gecontroleerd is — nooit een
 * verzonnen noemer:
 * <ul>
 *   <li>{@link IssueIncidentKind#GENERIC}: de records van de levering die binnen de importscope
 *       vielen ({@code raw_record_count − filtered_out_count}). Een uitgefilterd record is nooit
 *       gecontroleerd en hoort dus niet in de noemer.</li>
 *   <li>{@link IssueIncidentKind#PRICE}: het aantal werkelijk uitgevoerde prijsvergelijkingen — de
 *       bestaande aanbiedingen waarvan een bedrag gewijzigd is. Een nieuwe aanbieding heeft geen
 *       referentie en wordt niet vergeleken.</li>
 *   <li>{@link IssueIncidentKind#IDENTITY}: het aantal kandidaten dat een gemapte kritieke
 *       referentie draagt.</li>
 * </ul>
 * Is de scope onbekend of 0 (bijvoorbeeld bij een levering die geblokkeerd werd vóór het bestand
 * gelezen was), dan blijven {@code scope_record_count} en {@code share_percent} leeg en is er
 * <b>geen</b> bulkincident: zonder noemer bestaat een percentage niet, en een noemer raden is
 * erger dan geen oordeel. De groep zelf blijft wel bestaan, met haar werkelijke aantal.
 *
 * <h2>Wat deze pass bewust niet doet</h2>
 * <ul>
 *   <li><b>Geen patroonherkenning</b> (aanname A20): {@code dominant_factor} en
 *       {@code pattern_description} blijven leeg. "Alle EAN's kregen hetzelfde voorvoegsel" is een
 *       waardevolle vaststelling, maar een half werkende gok erover is schadelijker dan geen.</li>
 *   <li><b>Geen statuswijziging op mutaties</b> en <b>geen herziening van
 *       {@code validation_result}</b>: drempels, eindoordeel en {@code AWAITING_APPROVAL} horen bij
 *       bouwstap 3h.</li>
 *   <li><b>Geen vervanging van de individuele meldingen.</b> Een bulkincident komt er bovenop. Bij
 *       kritieke referenties is dat een harde eis (R-REF-07: individuele audit blijft).</li>
 * </ul>
 *
 * <h2>Idempotent en hervatbaar</h2>
 * Alles wat hier gebeurt is herberekening of een {@code where not exists}: koppelen gebeurt enkel
 * voor nog niet gekoppelde rijen, het aantal voorbeelden wordt <b>herteld</b> (niet opgeteld), de
 * drempels worden opnieuw toegepast en een bulkmelding wordt enkel geschreven wanneer ze nog niet
 * bestaat. Tweemaal draaien levert dus exact dezelfde toestand op als één keer draaien.
 */
@Service
public class IssueAggregationService {

    /**
     * Groeperen vanaf tien gelijke foutsignaturen. Bewust een <b>technische</b> groeperingsdrempel
     * en geen leveringsdrempel: ze bepaalt wanneer samenvatten iets toevoegt, niet wanneer een
     * levering beoordeeld moet worden. Daarom is dit de enige grens in dit project die een vast
     * aantal mag zijn.
     */
    public static final int GROUP_MIN_OCCURRENCES = 10;

    /** Schaal van elk percentage in dit project (par. 3.4). */
    private static final int PERCENTAGE_SCALE = 12;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Wat de aggregatie vastgesteld heeft; beide aantallen zijn metingen en nooit schattingen.
     *
     * @param groupCount         het aantal overgebleven groepen (dus vanaf de groeperingsdrempel)
     * @param bulkIncidentCount  het aantal daarvan dat als bulkincident aangemerkt is
     */
    public record Aggregation(long groupCount, long bulkIncidentCount) {
    }

    private final IssueGroupDao groups;
    private final RowIssueDao rowIssues;
    private final TransactionTemplate transaction;

    public IssueAggregationService(IssueGroupDao groups, RowIssueDao rowIssues,
                                   PlatformTransactionManager transactionManager) {
        this.groups = groups;
        this.rowIssues = rowIssues;
        // Standaardpropagatie: draait de aanroeper al in een transactie (de afrondende transactie van
        // een geblokkeerde levering), dan wordt die gebruikt in plaats van een tweede te openen.
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Voert pass E4 uit voor één batch.
     *
     * @param scopeSource        levert de gecontroleerde scope per incidentsoort; wordt hoogstens
     *                           één keer per soort aangeroepen en alleen voor een soort die
     *                           werkelijk voorkomt, zodat een levering zonder prijs- of
     *                           referentiegroepen geen enkele extra query kost. {@code null} als
     *                           antwoord betekent "onbekend" en nooit 0
     * @param bulkSharePercent   {@code bulk_incident_share_percent} van de revisie; nooit
     *                           {@code null} (default 1)
     * @param maxSampleRowsPerCode de voorbeeldcap per foutcode, enkel voor de tekst van de
     *                           cap-melding
     */
    public Aggregation aggregate(long batchId, Long deliveryFileId,
                                 Function<IssueIncidentKind, Long> scopeSource,
                                 BigDecimal bulkSharePercent, int maxSampleRowsPerCode) {
        BigDecimal sharePercent = bulkSharePercent == null ? BigDecimal.ONE : bulkSharePercent;
        return transaction.execute(status -> {
            groups.linkIssueRows(batchId);
            groups.refreshSampleCounts(batchId);
            applyThresholds(batchId, scopeSource, sharePercent);
            groups.deleteBelowThreshold(batchId, GROUP_MIN_OCCURRENCES);
            recordBulkIncidents(batchId, deliveryFileId, sharePercent);
            recordCapNotices(batchId, deliveryFileId, maxSampleRowsPerCode);
            return new Aggregation(groups.countByBatchId(batchId), groups.countBulkIncidents(batchId));
        });
    }

    /**
     * Zet per groep de scope, het aandeel en het bulkoordeel. Groepen onder de groeperingsdrempel
     * krijgen uitdrukkelijk <i>geen</i> oordeel: ze worden zo dadelijk verwijderd, en blijven ze
     * staan omdat hun aantal anders verloren zou gaan, dan is "geen patroon vastgesteld" het juiste
     * antwoord.
     */
    private void applyThresholds(long batchId, Function<IssueIncidentKind, Long> scopeSource,
                                 BigDecimal sharePercent) {
        Map<IssueIncidentKind, Long> scopes = new EnumMap<>(IssueIncidentKind.class);
        for (GroupRow group : groups.findByBatchId(batchId)) {
            if (group.occurrenceCount() < GROUP_MIN_OCCURRENCES) {
                groups.applyThreshold(group.id(), null, null, false);
                continue;
            }
            IssueIncidentKind kind = IssueIncidentKind.valueOf(group.incidentKind());
            // Bewust geen computeIfAbsent: een onbekende scope is null, en dan zou de bron bij elke
            // volgende groep opnieuw bevraagd worden.
            if (!scopes.containsKey(kind)) {
                scopes.put(kind, scopeSource.apply(kind));
            }
            Long scope = scopes.get(kind);
            BigDecimal share = share(group.occurrenceCount(), scope);
            groups.applyThreshold(group.id(), scope, share,
                    isBulk(group.occurrenceCount(), scope, sharePercent));
        }
    }

    /**
     * Het aandeel van deze groep in de gecontroleerde scope, schaal {@value #PERCENTAGE_SCALE}
     * (par. 3.4). {@code null} wanneer de scope onbekend of 0 is: dan bestaat het aandeel niet en
     * wordt er niets verzonnen — geen 0%, geen 100%.
     */
    private static BigDecimal share(long occurrences, Long scope) {
        if (scope == null || scope <= 0) {
            return null;
        }
        return BigDecimal.valueOf(occurrences).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(scope), PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * De bulkregel: {@code aantal × 100 > percentage × scope}. Bewust een vermenigvuldiging en geen
     * deling — een deling zou moeten afronden en juist op de grens het verkeerde antwoord kunnen
     * geven. Exact op de grens is <b>niet</b> overschreden, net als bij de prijsafwijking.
     * <p>
     * Zonder bruikbare scope is er geen bulkincident: een percentage zonder noemer bestaat niet, en
     * "dan maar blokkeren" of "dan maar niets" op basis van een geraden noemer is allebei fout. De
     * groep blijft wel bestaan met haar werkelijke aantal, zodat de vaststelling niet verdwijnt.
     */
    private static boolean isBulk(long occurrences, Long scope, BigDecimal sharePercent) {
        if (scope == null || scope <= 0 || sharePercent == null) {
            return false;
        }
        return BigDecimal.valueOf(occurrences).multiply(HUNDRED)
                .compareTo(sharePercent.multiply(BigDecimal.valueOf(scope))) > 0;
    }

    /**
     * Eén samenvattende melding per bulkgroep van een prijs- of identiteitsincident (R-PRI-14,
     * R-REF-07). Een gewone foutgroep krijgt geen extra foutcode: daar is {@code is_bulk_incident}
     * op de groep de volledige vaststelling.
     * <p>
     * De melding komt <b>naast</b> de individuele meldingen, niet in de plaats ervan.
     */
    private void recordBulkIncidents(long batchId, Long deliveryFileId, BigDecimal sharePercent) {
        List<IssueRow> issues = new ArrayList<>();
        List<GroupRow> bulkGroups = new ArrayList<>();
        Instant now = Instant.now();
        for (GroupRow group : groups.findByBatchId(batchId)) {
            if (!group.bulkIncident()) {
                continue;
            }
            IssueIncidentKind kind = IssueIncidentKind.valueOf(group.incidentKind());
            String code = switch (kind) {
                case PRICE -> ImportIssueCatalog.BULK_PRICE_INCIDENT;
                case IDENTITY -> ImportIssueCatalog.BULK_IDENTITY_INCIDENT;
                case GENERIC, CREATION -> null;
            };
            if (code == null
                    || rowIssues.countByBatchIdAndSignature(batchId, code, group.signature()) > 0) {
                continue;
            }
            bulkGroups.add(group);
            issues.add(ImportIssueCatalog.issue(batchId, deliveryFileId, null, code,
                    fieldNameOf(group), String.valueOf(group.occurrenceCount()), expectedValueOf(group),
                    messageOf(kind, group, sharePercent), signatureOf(kind, group), null, now));
        }
        rowIssues.insertBatch(issues);
        for (GroupRow group : bulkGroups) {
            String code = IssueIncidentKind.valueOf(group.incidentKind()) == IssueIncidentKind.PRICE
                    ? ImportIssueCatalog.BULK_PRICE_INCIDENT : ImportIssueCatalog.BULK_IDENTITY_INCIDENT;
            groups.linkIssueRowsToGroup(batchId, group.id(), code, group.signature());
        }
    }

    /**
     * Eén {@code ROW_ISSUE_RECORDING_CAPPED} per batch en foutcode, met het <b>werkelijke</b> aantal
     * uit de groepen — niet het aantal bewaarde voorbeeldrijen (R-ISS-03).
     * <p>
     * Dit vervangt de deelmeldingen die de detectiepassen zelf schreven. Die telden enkel wat één
     * doorloop vaststelde, zodat een levering die na een onderbreking hervat werd twéé meldingen met
     * twee deelaantallen kon krijgen — een lezer kon dan niet meer zien hoeveel fouten er werkelijk
     * waren. Nu is er precies één melding per foutcode, geschreven ná de laatste detectiepass, en is
     * ze idempotent: bestaat ze al, dan komt er geen tweede bij.
     */
    private void recordCapNotices(long batchId, Long deliveryFileId, int maxSampleRowsPerCode) {
        List<IssueRow> notices = new ArrayList<>();
        Instant now = Instant.now();
        for (CodeTotals totals : groups.totalsByCode(batchId)) {
            if (totals.occurrenceCount() <= totals.recordedSampleCount()) {
                continue;
            }
            IssueSignature.Signature signature = IssueSignature.forIssueCode(totals.issueCode());
            if (rowIssues.countByBatchIdAndSignature(batchId,
                    ImportIssueCatalog.ROW_ISSUE_RECORDING_CAPPED, signature.value()) > 0) {
                continue;
            }
            notices.add(ImportIssueCatalog.issue(batchId, deliveryFileId, null,
                    ImportIssueCatalog.ROW_ISSUE_RECORDING_CAPPED, null,
                    String.valueOf(totals.occurrenceCount()), totals.issueCode(),
                    "rowIssueSamples: '" + maxSampleRowsPerCode + "' is the maximum number of example "
                            + "rows kept per issue code; " + totals.issueCode() + "="
                            + totals.occurrenceCount() + " occurrences, "
                            + totals.recordedSampleCount() + " example rows kept. The full count is "
                            + "recorded in the issue group, never in the number of example rows",
                    signature, null, now));
        }
        rowIssues.insertBatch(notices);
    }

    private static IssueSignature.Signature signatureOf(IssueIncidentKind kind, GroupRow group) {
        return kind == IssueIncidentKind.PRICE
                ? IssueSignature.price(group.priceComponentCode(),
                        DeviationDirection.valueOf(group.deviationDirection()))
                : IssueSignature.identity(group.referenceType(), incidentKindOf(group));
    }

    private static String fieldNameOf(GroupRow group) {
        return group.priceComponentCode() != null ? group.priceComponentCode() : group.referenceType();
    }

    private static String expectedValueOf(GroupRow group) {
        return group.signature() + ";scope=" + (group.scopeRecordCount() == null ? ""
                : group.scopeRecordCount()) + ";share=" + (group.sharePercent() == null ? ""
                : group.sharePercent().toPlainString());
    }

    /** Het soort incident uit de signatuur {@code TYPE=EAN|KIND=CHANGED}. */
    private static String incidentKindOf(GroupRow group) {
        int marker = group.signature().indexOf("|KIND=");
        return marker < 0 ? "" : group.signature().substring(marker + "|KIND=".length());
    }

    private static String messageOf(IssueIncidentKind kind, GroupRow group, BigDecimal sharePercent) {
        String scope = group.scopeRecordCount() == null ? "an unknown number of"
                : String.valueOf(group.scopeRecordCount());
        String share = group.sharePercent() == null ? "" : " (" + group.sharePercent().toPlainString()
                + "% of the checked scope)";
        String threshold = "threshold " + sharePercent.toPlainString() + "% of the scope";
        if (kind == IssueIncidentKind.PRICE) {
            return group.priceComponentCode() + ": '" + group.occurrenceCount() + "' prices deviate "
                    + group.deviationDirection() + " beyond the configured limit within one delivery, "
                    + "out of " + scope + " compared prices" + share + "; grouped as one bulk price "
                    + "incident (" + threshold + "). The individual deviations stay recorded and no "
                    + "price or percentage was changed";
        }
        return group.referenceType() + ": '" + group.occurrenceCount() + "' critical reference "
                + "incidents of kind " + incidentKindOf(group) + " within one delivery, out of " + scope
                + " candidates carrying references" + share + "; grouped as one bulk identity incident ("
                + threshold + "). Every individual incident stays recorded and keeps blocking "
                + "regardless of volume";
    }
}
