package be.dda.catalogimport.dao;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Het behandelgeval als <b>set-based</b> bewerking: het koppelen van de issuegroepen van één batch aan
 * hun {@code issue_case} en het toepassen van de heropeningsregel
 * (docs/design/issue-case-design.md par. 2 en par. 3, R-CASE-01 t/m R-CASE-04, bouwstap S2-B1b).
 *
 * <h2>Waarom hier JDBC en niet JPA</h2>
 * Precedent {@code ImportMutation} + {@link MutationDao}: {@code issue_case} heeft zowel een
 * JPA-entiteit (leesendpoints, statuswijziging door een mens - S2-B2/S2-B3) als deze JDBC-DAO voor de
 * synchronisatie binnen de screening. Zelfde regel als {@link IssueGroupDao}: binnen één transactie
 * wordt hier uitsluitend geschreven en <b>nooit</b> via JPA teruggelezen.
 *
 * <h2>De vijf stappen (ontwerp par. 3), in deze volgorde</h2>
 * <ol start="0">
 *   <li>{@link #lockImportLink(long)} - serialiseert twee gelijktijdige screenings van dezelfde
 *       koppeling. {@code uk_issue_case_identity} blijft daarnaast de harde zekering.</li>
 *   <li>{@link #insertMissingCases(long, long, Instant)} - {@code insert ... where not exists}.</li>
 *   <li>{@link #insertCreatedEvents(long, long, Instant)} - het {@code CREATED}-event, idempotent
 *       door een {@code not exists}-guard.</li>
 *   <li>{@link #linkGroups(long, long)} - {@code import_issue_group.issue_case_id} vullen.</li>
 *   <li>{@link #recount(List, Instant)} - tellers en {@code first_seen_*}/{@code last_seen_*}
 *       <b>herberekenen</b>, nooit optellen.</li>
 *   <li>{@link #reopenOnRecurrence(List, long, Instant)} en
 *       {@link #reopenOnRuleChange(List, long, long, Instant)} - de heropeningsregel van par. 2.</li>
 * </ol>
 *
 * <h2>Idempotent</h2>
 * Elke stap is ofwel een {@code insert ... where not exists}, ofwel een herberekening, ofwel een
 * {@code update} waarvan de statuswaarde zelf het predicaat is. Tweemaal dezelfde batch
 * synchroniseren levert dus exact dezelfde toestand op: geen tweede geval, geen dubbel event, geen
 * dubbele telling.
 *
 * <h2>Transactiegrens</h2>
 * Deze DAO opent zelf geen transactie. Stap 0 heeft er wel één nodig (een {@code for update} zonder
 * transactie zou meteen weer vrijgegeven worden); de aanroeper
 * ({@code IssueCaseSyncService}) zorgt daarvoor.
 */
@Repository
public class IssueCaseDao {

    /** De vier statuswaarden van {@code ck_issue_case_status}; hier enkel als SQL-literal nodig. */
    private static final String STATUS_AWAITING_REVIEW = "AWAITING_REVIEW";

    /**
     * Stap 1. Eén nieuw geval per issuegroep van deze batch waarvoor nog geen geval met dezelfde
     * identiteit {@code (import_link_id, issue_code, signature)} bestaat (D1/R-CASE-01).
     * <p>
     * De classificatievelden worden gedenormaliseerd uit de groep overgenomen en daarna nooit meer
     * bijgewerkt (R-ISS-02-motivatie). {@code observation_count} staat hier op 1 omdat
     * {@code ck_issue_case_observations} geen geval zonder waarneming toelaat zolang er geen
     * menselijke actie op staat; stap 4 hertelt het meteen daarna tot het werkelijke aantal.
     * <p>
     * De tijden komen uit de groep en vallen terug op {@code import_batch.created_at}:
     * {@code first_detected_at}/{@code last_detected_at} zijn nullable (changeset 004-4) en "nu"
     * verzinnen zou een meting vervalsen.
     */
    private static final String INSERT_MISSING_CASES = "insert into issue_case ("
            + "import_link_id, issue_code, signature, severity, issue_domain, control_level, impact_scope, "
            + "incident_kind, price_component_code, reference_type, status, observation_count, "
            + "total_occurrence_count, first_seen_at, last_seen_at, first_seen_batch_id, last_seen_batch_id, "
            + "last_seen_revision_id, reopen_count, created_at, updated_at) "
            + "select cast(? as bigint), g.issue_code, g.signature, g.severity, g.issue_domain, "
            + "  g.control_level, g.impact_scope, g.incident_kind, g.price_component_code, g.reference_type, "
            + "  cast('" + STATUS_AWAITING_REVIEW + "' as varchar(30)), 1, g.occurrence_count, "
            + "  coalesce(g.first_detected_at, b.created_at), coalesce(g.last_detected_at, b.created_at), "
            + "  g.batch_id, g.batch_id, b.definition_revision_id, 0, "
            + "  cast(? as timestamp with time zone), cast(? as timestamp with time zone) "
            + "from import_issue_group g join import_batch b on b.id = g.batch_id "
            + "where g.batch_id = ? and g.issue_case_id is null "
            + "  and not exists (select 1 from issue_case c where c.import_link_id = ? "
            + "    and c.issue_code = g.issue_code and c.signature = g.signature)";

    /**
     * Stap 2. Het {@code CREATED}-event van elk geval dat er nog geen heeft. Alleen een geval dat in
     * stap 1 net aangemaakt is, mist dat event - elk ander geval kreeg het bij zijn eigen aanmaak.
     * De {@code not exists}-guard maakt dit idempotent door constructie (append-only tabel, R-CASE-04).
     * <p>
     * Er wordt op identiteit gejoind en niet op {@code issue_case_id}: het koppelen is stap 3 en
     * gebeurt dus pas hierna (volgorde uit het ontwerp).
     */
    private static final String INSERT_CREATED_EVENTS = "insert into issue_case_event ("
            + "issue_case_id, event_kind, previous_status, new_status, reason, source, changed_by, "
            + "changed_by_subject, changed_at, observation_batch_id) "
            + "select c.id, cast('CREATED' as varchar(30)), null, c.status, cast(? as varchar(500)), "
            + "  cast('SYSTEM' as varchar(20)), null, null, cast(? as timestamp with time zone), "
            + "  cast(? as bigint) "
            + "from issue_case c "
            + "where c.import_link_id = ? "
            + "  and exists (select 1 from import_issue_group g where g.batch_id = ? "
            + "    and g.issue_code = c.issue_code and g.signature = c.signature) "
            + "  and not exists (select 1 from issue_case_event e where e.issue_case_id = c.id "
            + "    and e.event_kind = 'CREATED')";

    /** Stap 3. Patroon {@link IssueGroupDao#linkIssueRows(long)}: één update over de hele batch. */
    private static final String LINK_GROUPS = "update import_issue_group set issue_case_id = ("
            + "select c.id from issue_case c where c.import_link_id = ? "
            + "  and c.issue_code = import_issue_group.issue_code "
            + "  and c.signature = import_issue_group.signature) "
            + "where batch_id = ? and issue_case_id is null "
            + "  and exists (select 1 from issue_case c where c.import_link_id = ? "
            + "    and c.issue_code = import_issue_group.issue_code "
            + "    and c.signature = import_issue_group.signature)";

    /**
     * Stap 4. Alle tellers en tijden van een geval worden <b>herberekend</b> uit de gekoppelde
     * groepen, nooit opgeteld: dan levert een tweede doorloop exact hetzelfde getal op (patroon
     * {@link IssueGroupDao#refreshSampleCounts(long)}).
     * <p>
     * {@code last_seen_*} schuift daardoor alleen op wanneer deze batch werkelijk de recentste
     * waarneming is, en {@code first_seen_*} alleen wanneer ze ouder is - de {@code order by ... limit
     * 1} kiest per geval de groep die het uiterste tijdstip draagt, ongeacht uit welke batch die komt.
     * <p>
     * De {@code coalesce(..., <oude waarde>)} eromheen is geen luxe maar noodzaak: op het herstelpad
     * ({@code ScreeningRecoveryService}) worden de groepen van een onderbroken poging verwijderd en
     * blijft er mogelijk geen enkele waarneming over. {@code first_seen_at}/{@code last_seen_at} zijn
     * {@code not null}, dus dan blijft de laatst bekende meting staan in plaats van dat de update
     * afbreekt.
     */
    private static final String RECOUNT = "update issue_case set "
            + "observation_count = (select count(*) from import_issue_group g "
            + "    where g.issue_case_id = issue_case.id), "
            + "total_occurrence_count = (select coalesce(sum(g.occurrence_count), 0) "
            + "    from import_issue_group g where g.issue_case_id = issue_case.id), "
            + "first_seen_at = coalesce((select min(coalesce(g.first_detected_at, b.created_at)) "
            + "    from import_issue_group g join import_batch b on b.id = g.batch_id "
            + "    where g.issue_case_id = issue_case.id), first_seen_at), "
            + "first_seen_batch_id = coalesce((select g.batch_id "
            + "    from import_issue_group g join import_batch b on b.id = g.batch_id "
            + "    where g.issue_case_id = issue_case.id "
            + "    order by coalesce(g.first_detected_at, b.created_at) asc, g.batch_id asc "
            + "    limit 1), first_seen_batch_id), "
            + "last_seen_at = coalesce((select max(coalesce(g.last_detected_at, b.created_at)) "
            + "    from import_issue_group g join import_batch b on b.id = g.batch_id "
            + "    where g.issue_case_id = issue_case.id), last_seen_at), "
            + "last_seen_batch_id = coalesce((select g.batch_id "
            + "    from import_issue_group g join import_batch b on b.id = g.batch_id "
            + "    where g.issue_case_id = issue_case.id "
            + "    order by coalesce(g.last_detected_at, b.created_at) desc, g.batch_id desc "
            + "    limit 1), last_seen_batch_id), "
            + "last_seen_revision_id = coalesce((select b.definition_revision_id "
            + "    from import_issue_group g join import_batch b on b.id = g.batch_id "
            + "    where g.issue_case_id = issue_case.id "
            + "    order by coalesce(g.last_detected_at, b.created_at) desc, g.batch_id desc "
            + "    limit 1), last_seen_revision_id), "
            + "updated_at = cast(? as timestamp with time zone) "
            + "where id in (";

    /**
     * Stap 5, eerste regel (par. 2): een geval dat {@code CORRECTED} of {@code AUTO_RESOLVED} was en
     * opnieuw vastgesteld wordt, heropent <b>altijd</b> - een correctie die door een nieuwe
     * vaststelling tegengesproken wordt, is per definitie niet afgehandeld (R-CASE-03).
     */
    private static final String RECURRENCE_STATUSES = "('CORRECTED', 'AUTO_RESOLVED')";

    private final JdbcTemplate jdbc;

    public IssueCaseDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Heeft deze batch überhaupt een issuegroep? Zonder groep is er niets te synchroniseren en hoeft
     * de koppeling ook niet vergrendeld te worden - de overgrote meerderheid van de leveringen komt
     * hier dus zonder één schrijfactie en zonder één slot doorheen.
     */
    public boolean hasGroups(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_issue_group where batch_id = ?",
                Long.class, batchId);
        return count != null && count > 0;
    }

    /**
     * Stap 0 (ontwerp par. 3): serialiseert twee gelijktijdige screenings van dezelfde koppeling. Twee
     * leveringen die tegelijk hetzelfde nieuwe probleem vaststellen, zouden anders beide een geval
     * willen aanmaken; de tweede wacht nu tot de eerste gecommit is en ziet dan het bestaande geval.
     * {@code uk_issue_case_identity} blijft de harde zekering eronder.
     * <p>
     * Moet binnen een transactie gebeuren, anders is het slot onmiddellijk weer vrij.
     */
    public void lockImportLink(long importLinkId) {
        jdbc.queryForList("select 1 from import_link where id = ? for update", Integer.class, importLinkId);
    }

    /**
     * Stap 1: één nieuw geval per nog ongedekte issuegroep van deze batch.
     *
     * @return het aantal nieuw aangemaakte gevallen
     */
    public int insertMissingCases(long batchId, long importLinkId, Instant at) {
        OffsetDateTime now = utc(at);
        return jdbc.update(INSERT_MISSING_CASES, importLinkId, now, now, batchId, importLinkId);
    }

    /**
     * Stap 2: het {@code CREATED}-event van elk nieuw geval van deze batch.
     *
     * @return het aantal geschreven events; 0 wanneer elk betrokken geval er al één had
     */
    public int insertCreatedEvents(long batchId, long importLinkId, Instant at) {
        String reason = "CREATED: eerste vaststelling in batch " + batchId;
        return jdbc.update(INSERT_CREATED_EVENTS, reason, utc(at), batchId, importLinkId, batchId);
    }

    /**
     * Stap 3: elke nog ongekoppelde groep van deze batch aan haar geval hangen.
     *
     * @return het aantal gekoppelde groepen
     */
    public int linkGroups(long batchId, long importLinkId) {
        return jdbc.update(LINK_GROUPS, importLinkId, batchId, importLinkId);
    }

    /**
     * De gevallen waarop de groepen van deze batch wijzen. Op het herstelpad moet deze lijst
     * <b>vóór</b> het verwijderen van de groepen opgevraagd worden: daarna is de verwijzing weg.
     */
    public List<Long> findCaseIdsByBatchId(long batchId) {
        return jdbc.queryForList("select distinct issue_case_id from import_issue_group "
                + "where batch_id = ? and issue_case_id is not null order by issue_case_id",
                Long.class, batchId);
    }

    /**
     * Stap 4: tellers en tijden herberekenen uit de gekoppelde groepen.
     *
     * @return het aantal bijgewerkte gevallen
     */
    public int recount(List<Long> caseIds, Instant at) {
        if (caseIds.isEmpty()) {
            return 0;
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(utc(at));
        parameters.addAll(caseIds);
        return jdbc.update(RECOUNT + placeholders(caseIds.size()) + ")", parameters.toArray());
    }

    /**
     * Stap 5, regel {@code CORRECTED}/{@code AUTO_RESOLVED} (par. 2): altijd heropenen naar
     * {@code AWAITING_REVIEW}, met {@code reopen_count + 1} en een zichtbare reden in een
     * {@code SYSTEM}-event náást de eerdere beslissing (R-CASE-04: de eerdere reden wordt nooit
     * overschreven).
     * <p>
     * {@code decision_revision_id} blijft bewust staan: dat is de revisie waaronder de laatste
     * <b>menselijke</b> beslissing genomen is, en een systeemheropening is geen menselijke beslissing.
     * {@code status_changed_by}/{@code _by_subject} gaan op {@code null} - ontwerp par. 1: "{@code null}
     * bij een systeemheropening".
     *
     * @return het aantal heropende gevallen
     */
    public int reopenOnRecurrence(List<Long> caseIds, long batchId, Instant at) {
        if (caseIds.isEmpty()) {
            return 0;
        }
        String reason = "REOPENED_RECURRENCE: opnieuw vastgesteld in batch " + batchId;
        OffsetDateTime now = utc(at);
        String filter = " and id in (" + placeholders(caseIds.size()) + ")";
        List<Object> eventParameters = new ArrayList<>(List.of(reason, now, batchId));
        eventParameters.addAll(caseIds);
        jdbc.update("insert into issue_case_event (issue_case_id, event_kind, previous_status, "
                + "new_status, reason, source, changed_by, changed_by_subject, changed_at, "
                + "observation_batch_id) "
                + "select id, cast('STATUS_CHANGE' as varchar(30)), status, "
                + "  cast('" + STATUS_AWAITING_REVIEW + "' as varchar(30)), cast(? as varchar(500)), "
                + "  cast('SYSTEM' as varchar(20)), null, null, cast(? as timestamp with time zone), "
                + "  cast(? as bigint) "
                + "from issue_case where status in " + RECURRENCE_STATUSES + filter,
                eventParameters.toArray());
        List<Object> updateParameters = new ArrayList<>(List.of(reason, now, now));
        updateParameters.addAll(caseIds);
        return jdbc.update("update issue_case set status = '" + STATUS_AWAITING_REVIEW + "', "
                + "reopen_count = reopen_count + 1, status_reason = cast(? as varchar(500)), "
                + "status_changed_at = cast(? as timestamp with time zone), status_changed_by = null, "
                + "status_changed_by_subject = null, updated_at = cast(? as timestamp with time zone) "
                + "where status in " + RECURRENCE_STATUSES + filter, updateParameters.toArray());
    }

    /**
     * Stap 5, regel {@code REJECTED} (par. 2, R-CASE-02/R-CASE-03). Zolang dezelfde regels gelden -
     * dezelfde {@code import_definition_revision} als waaronder de afwijzing genomen is - blijft het
     * geval {@code REJECTED} en worden enkel de tellers bijgewerkt: de onderdrukking van een
     * identieke herlevering is gewild, maar nooit onzichtbaar. Een <b>andere</b> revisie (of een
     * afwijzing zonder vastgelegde revisie) heft de onderdrukking op.
     * <p>
     * De reden draagt beide revisienummers, zodat achteraf te zien is welke regelwijziging de
     * heropening veroorzaakte.
     *
     * @param definitionRevisionId de revisie waaronder déze batch gescreend is
     * @return het aantal heropende gevallen
     */
    public int reopenOnRuleChange(List<Long> caseIds, long batchId, long definitionRevisionId, Instant at) {
        if (caseIds.isEmpty()) {
            return 0;
        }
        OffsetDateTime now = utc(at);
        String reasonExpression = "'REOPENED_RULES_CHANGED: revisie ' "
                + "|| coalesce(cast(decision_revision_id as varchar), 'onbekend') "
                + "|| ' → ' || cast(? as varchar)";
        String predicate = " where status = 'REJECTED' "
                + "  and (decision_revision_id is null or decision_revision_id <> cast(? as bigint)) "
                + "  and id in (" + placeholders(caseIds.size()) + ")";
        List<Object> eventParameters = new ArrayList<>(List.of(definitionRevisionId, now, batchId,
                definitionRevisionId));
        eventParameters.addAll(caseIds);
        jdbc.update("insert into issue_case_event (issue_case_id, event_kind, previous_status, "
                + "new_status, reason, source, changed_by, changed_by_subject, changed_at, "
                + "observation_batch_id) "
                + "select id, cast('STATUS_CHANGE' as varchar(30)), status, "
                + "  cast('" + STATUS_AWAITING_REVIEW + "' as varchar(30)), " + reasonExpression + ", "
                + "  cast('SYSTEM' as varchar(20)), null, null, cast(? as timestamp with time zone), "
                + "  cast(? as bigint) "
                + "from issue_case" + predicate, eventParameters.toArray());
        List<Object> updateParameters = new ArrayList<>(List.of(definitionRevisionId, now, now,
                definitionRevisionId));
        updateParameters.addAll(caseIds);
        return jdbc.update("update issue_case set status = '" + STATUS_AWAITING_REVIEW + "', "
                + "reopen_count = reopen_count + 1, status_reason = " + reasonExpression + ", "
                + "status_changed_at = cast(? as timestamp with time zone), status_changed_by = null, "
                + "status_changed_by_subject = null, updated_at = cast(? as timestamp with time zone) "
                + predicate, updateParameters.toArray());
    }

    /**
     * Herstelpad (ontwerp par. 3, laatste alinea): verwijdert de gevallen die na het weghalen van de
     * groepen van een mislukte poging <b>geen resterende gekoppelde groep meer hebben</b> én waaraan
     * nog nooit een mens geraakt heeft. Een geval waar al een mens aan raakte blijft staan met
     * {@code observation_count = 0} - exact wat {@code ck_issue_case_observations} toelaat.
     *
     * <p><b>Important technical constraint discovered.</b> {@code issue_case_event} is in het ontwerp
     * append-only ("nooit bijgewerkt, nooit verwijderd"), maar
     * {@code fk_issue_case_event_case} maakt het onmogelijk een geval te verwijderen zolang zijn
     * {@code CREATED}-event bestaat. Bij dit herstelpad verdwijnt het geval zelf omdat het nooit
     * geldig bestaan heeft; zijn geboorteregel gaat daarom mee. De append-only-regel blijft gelden
     * voor elk geval dat blijft bestaan: daar wordt nooit een event gewijzigd of verwijderd. Voorstel
     * om dit als uitzondering in {@code docs/design/issue-case-design.md} par. 1 vast te leggen.
     *
     * @return het aantal verwijderde gevallen
     */
    public int deleteWithoutObservationsOrHumanAction(List<Long> caseIds) {
        if (caseIds.isEmpty()) {
            return 0;
        }
        String selection = "select id from issue_case where status_changed_at is null "
                + "and id in (" + placeholders(caseIds.size()) + ") "
                + "and not exists (select 1 from import_issue_group g where g.issue_case_id = issue_case.id)";
        Object[] parameters = caseIds.toArray();
        jdbc.update("delete from issue_case_event where issue_case_id in (" + selection + ")", parameters);
        return jdbc.update("delete from issue_case where id in (" + selection + ")", parameters);
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
