package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.service.SetupService.RevisionView;
import be.dda.catalogimport.service.support.RevisionCopier;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Het aanmaken van een <b>opvolgrevisie</b>: een nieuwe {@link RevisionStatus#DRAFT} die een bestaande,
 * bevroren revisie van dezelfde importdefinitie verbatim kopieert
 * ({@code docs/design/revision-successor-design.md} §1, §2, §6 endpoint E2; bouwstap S1-X-2).
 *
 * <h2>Waarom deze service bestaat</h2>
 * Een bevroren revisie wordt nooit bijgewerkt (§14.14, {@code REVISION_NOT_EDITABLE}). Wie de
 * configuratie van een lopende importdefinitie wil wijzigen, heeft dus een <i>opvolger</i> nodig: een
 * kopie die als DRAFT bewerkbaar is en pas bij activatie de vorige vervangt. Tot deze bouwstap kon de
 * setup-API alleen een lege revisie maken ({@code SetupService.createRevision}) — waarna elke mapping,
 * elk filter en elke bookmarkdeclaratie opnieuw met de hand ingevoerd moest worden. Dat is precies hoe
 * een "kleine wijziging" ongemerkt een andere configuratie oplevert dan de bedoeling was.
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Verbatim kopie (R-REV-X1).</b> De kloonstap raakt <b>geen enkel</b> identiteits- of
 *       prijsbepalend veld aan; alle configuratievelden en alle vijf configuratie-kindtabellen gaan
 *       letterlijk mee. De nieuwe DRAFT is inhoudelijk identiek aan de bron, inclusief haar vier
 *       configuratiehashes (die herberekend worden en dus per definitie uitkomen op dezelfde waarde
 *       zolang niemand een veld wijzigt). Alleen een <i>latere</i> wijziging in de DRAFT verandert
 *       betekenis — zie §5 van het ontwerp.</li>
 *   <li><b>Klonen mag vanaf {@code ACTIVE} én {@code SUPERSEDED} (O2).</b> Een oudere, vervangen
 *       configuratie terug oppakken ("terugdraaien") is hetzelfde werk als een nieuwe opvolger maken.
 *       Elke andere status — met name {@code DRAFT} — geeft 409 {@code REVISION_NOT_CLONEABLE}: een
 *       DRAFT is nog niet gescreend, niet gevalideerd en niet goedgekeurd, dus kopiëren zou een
 *       onbeoordeelde configuratie vermenigvuldigen.</li>
 *   <li><b>Hoogstens één open DRAFT-opvolger per definitie (O1).</b> Bestaat er al een DRAFT op deze
 *       definitie, dan 409 {@code REVISION_DRAFT_ALREADY_EXISTS} met het bestaande revisienummer erin.
 *       Twee open DRAFTs tegelijk zouden betekenen dat twee mensen los van elkaar aan "de volgende
 *       configuratie" werken en de tweede activatie de eerste stil overschrijft. Sinds NT-13 dwingt ook
 *       {@code uk_import_definition_revision_draft} dit af (eveneens voor {@code SetupService.createRevision});
 *       een race wordt tot dezelfde 409 vertaald.</li>
 *   <li><b>{@code changeReason} is verplicht.</b> 400 {@code CHANGE_REASON_REQUIRED}. De javadoc van
 *       {@code ImportDefinitionRevision.changeReason} noemt dit al sinds §14.20 "verplicht bij een
 *       opvolgrevisie", maar geen enkele service dwong het af; dit is de eerste plaats waar dat wel
 *       gebeurt (ontdekking §9 van het ontwerp). Bestaande rijen blijven {@code null}.</li>
 *   <li><b>Nooit goedgekeurd.</b> {@code approved_at}/{@code approved_by}/{@code approved_by_subject}
 *       blijven {@code null}: een DRAFT is niet goedgekeurd. Activeren blijft een aparte, ondertekende
 *       handeling ({@code SetupService.activateRevision}).</li>
 *   <li><b>Eén transactie.</b> Een halve opvolger — scalairen gekopieerd, mappings niet — zou een
 *       revisie achterlaten die er geldig uitziet maar een andere configuratie beschrijft dan de bron.</li>
 * </ul>
 *
 * <h2>Wat hier bewust niet gebeurt</h2>
 * <ul>
 *   <li><b>Geen {@code import_link_bookmark_value} aangeraakt</b> (§2). Die waarden hangen aan de
 *       {@link be.dda.catalogimport.domain.ImportLink}, niet aan een revisienummer. Omdat de
 *       bookmarkdeclaraties hier verbatim meegaan — <b>alle scopes</b>, niet alleen {@code LINK} zoals
 *       bij materialisatie — blijft elke bestaande koppelingswaarde ook na de opvolger gedeclareerd en
 *       wordt ze dus nooit een wees.</li>
 *   <li><b>Geen validatie van de configuratie.</b> De bron is bij haar eigen activatie al met dezelfde
 *       fabrieken gevalideerd en de kopie is verbatim; opnieuw valideren zou niets nieuws kunnen
 *       vinden. De volgende validatie hoort bij het activeren van de DRAFT.</li>
 *   <li><b>Geen activatie.</b> De opvolger is DRAFT en wordt nooit vanzelf actief; dat is dezelfde
 *       grens als bij materialisatie (R-MAT, A33). Activeren heropent bij de eerstvolgende waarneming
 *       afgewezen behandelgevallen van deze definitie (R-CASE-03, §4 van het ontwerp) — een gevolg dat
 *       het activatiescherm moet melden, niet iets dat deze service stil veroorzaakt.</li>
 * </ul>
 */
@Service
@Transactional
public class RevisionSuccessorService {

    /** {@code import_definition_revision.change_reason} is varchar(500). */
    private static final int MAX_CHANGE_REASON_LENGTH = 500;
    /** {@code import_definition_revision.created_by}. */
    private static final int MAX_USER_LENGTH = 100;
    /** Wie de revisie aanmaakte wanneer er geen aangemelde gebruiker is; nooit een echte naam. */
    private static final String DEFAULT_CREATED_BY = "setup-api";

    /** O2: de twee statussen waaruit een opvolger gemaakt mag worden. */
    private static final Set<RevisionStatus> CLONEABLE =
            EnumSet.of(RevisionStatus.ACTIVE, RevisionStatus.SUPERSEDED);

    private final ImportDefinitionRevisionRepository revisions;
    private final ImportFieldMappingRepository fieldMappings;
    private final ImportRecordFilterRepository recordFilters;
    private final ImportDefinitionBookmarkRepository bookmarks;
    private final ImportDefinitionBookmarkUsageRepository usages;
    private final RevisionCopier revisionCopier;

    public RevisionSuccessorService(ImportDefinitionRevisionRepository revisions,
                                    ImportFieldMappingRepository fieldMappings,
                                    ImportRecordFilterRepository recordFilters,
                                    ImportDefinitionBookmarkRepository bookmarks,
                                    ImportDefinitionBookmarkUsageRepository usages,
                                    RevisionCopier revisionCopier) {
        this.revisions = revisions;
        this.fieldMappings = fieldMappings;
        this.recordFilters = recordFilters;
        this.bookmarks = bookmarks;
        this.usages = usages;
        this.revisionCopier = revisionCopier;
    }

    /**
     * Maakt de opvolgrevisie van {@code sourceRevisionId}: een nieuwe DRAFT met revisienummer
     * hoogste + 1 binnen dezelfde definitie, {@code based_on_revision_id} naar de bronrevisie, en een
     * verbatim kopie van alle configuratievelden en kindrijen.
     * <p>
     * <b>De volgorde van de controles</b> is die van het ontwerp (§6): eerst bestaat de bron
     * ({@code REVISION_NOT_FOUND}), dan mag die bron geklooned worden (O2), dan is er nog geen open
     * DRAFT (O1), en pas daarna wordt de inhoud van het verzoek beoordeeld. Wie een tweede opvolger
     * probeert, hoort te lezen dát er al één is — niet dat zijn wijzigingsreden ontbreekt.
     * <p>
     * <b>Twee keer aanroepen levert nooit twee opvolgers op</b>: de tweede poging stuit op O1. Er is
     * bewust geen idempotentiesleutel — dit is een eenmalige, interactieve beheerhandeling, net als de
     * materialisatiewizard (§14.18).
     *
     * @param changeReason verplicht, nooit uit de bron gekopieerd: waarom komt er een opvolger?
     * @param actor        wie tekent; naam in {@code created_by}, OIDC-subject in
     *                     {@code created_by_subject} ({@code null} = geen geverifieerde identiteit)
     * @return dezelfde {@link RevisionView} als {@code createRevision}/{@code activate} teruggeven
     * @throws NotFoundException   {@code REVISION_NOT_FOUND}
     * @throws ConflictException   {@code REVISION_NOT_CLONEABLE} (bron is geen ACTIVE/SUPERSEDED),
     *                             {@code REVISION_DRAFT_ALREADY_EXISTS} (O1)
     * @throws BadRequestException {@code CHANGE_REASON_REQUIRED}
     */
    public RevisionView createSuccessor(long sourceRevisionId, String changeReason, ActorIdentity actor) {
        ImportDefinitionRevision source = revisions.findById(sourceRevisionId)
                .orElseThrow(() -> new NotFoundException("REVISION_NOT_FOUND",
                        "Definition revision " + sourceRevisionId + " does not exist"));
        if (!CLONEABLE.contains(source.getStatus())) {
            throw new ConflictException("REVISION_NOT_CLONEABLE", "Revision " + sourceRevisionId + " is "
                    + source.getStatus() + "; only an ACTIVE or SUPERSEDED revision is copied into a "
                    + "successor, because only those have been validated and approved");
        }
        long definitionId = source.getImportDefinition().getId();
        // Eén leesquery voor beide vragen hieronder: is er al een DRAFT, en wat is het hoogste nummer?
        List<ImportDefinitionRevision> existing =
                revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(definitionId);
        Optional<ImportDefinitionRevision> openDraft = existing.stream()
                .filter(revision -> revision.getStatus() == RevisionStatus.DRAFT)
                .findFirst();
        if (openDraft.isPresent()) {
            throw SetupService.draftAlreadyExists(definitionId, openDraft.get());
        }
        String reason = requireChangeReason(changeReason);
        String createdBy = ActorNames.requireText(
                actor.username() == null || actor.username().isBlank() ? DEFAULT_CREATED_BY
                        : actor.username(), "createdBy", MAX_USER_LENGTH);
        String createdBySubject = actor.subject();
        int revisionNumber = existing.stream()
                .mapToInt(ImportDefinitionRevision::getRevisionNumber).max().orElse(0) + 1;

        // De scalaire kopie zet zelf al status DRAFT, based_on, de vier herberekende hashes en laat
        // approved_* leeg (RevisionCopier, §1). De doeldefinitie is hier de eigen definitie van de bron:
        // een opvolger blijft binnen haar definitie, in tegenstelling tot een materialisatie.
        ImportDefinitionRevision successor = revisionCopier.copyRevision(source, source.getImportDefinition(),
                revisionNumber, source, reason, createdBy, createdBySubject);
        try {
            successor = revisions.saveAndFlush(successor);
        } catch (DataIntegrityViolationException violation) {
            // NT-13: gelijktijdige tweede DRAFT op dezelfde definitie -> dezelfde 409 als de controle hierboven.
            throw SetupService.translateDraftConflict(violation, definitionId);
        }

        // De vijf kindtabellen. copyMappings/copyFilters geven bewust nog niet-opgeslagen rijen terug
        // (de materialisatiewizard vult er eerst bookmarkwaarden in); de opvolger heeft niets in te
        // vullen en schrijft ze onmiddellijk weg.
        fieldMappings.saveAll(revisionCopier.copyMappings(successor, source, createdBy,
                createdBySubject).values());
        recordFilters.saveAll(revisionCopier.copyFilters(successor, source, createdBy,
                createdBySubject).values());
        revisionCopier.copyFieldCriticalities(successor, source, createdBy, createdBySubject);
        copyAllBookmarkDeclarations(successor, source, createdBy, createdBySubject);
        revisionCopier.copyBookmarkValues(successor, source, createdBy, createdBySubject);
        fieldMappings.flush();
        recordFilters.flush();
        return SetupService.view(successor);
    }

    /**
     * De bookmarkdeclaraties van de bron plus hun usages, <b>alle scopes</b> — de bewuste afwijking van
     * materialisatie (§2). Materialisatie kopieert enkel de {@code LINK}-scope declaraties omdat de
     * {@code DEFINITION}-scope declaraties daar opgelost zijn en voortleven als bookmarkwaarde; een
     * opvolgrevisie van een <i>sjabloon</i> zou met diezelfde keuze al zijn declaraties verliezen en
     * daarmee onmaterialiseerbaar worden. Bovendien houdt het meenemen van de {@code LINK}-scope
     * declaraties elke bestaande {@code import_link_bookmark_value} gedeclareerd: zonder deze kopie werd
     * die waarde na activatie van de opvolger stilzwijgend een wees
     * ({@code LinkBookmarkValueService}, {@code declared = false}).
     */
    private void copyAllBookmarkDeclarations(ImportDefinitionRevision target,
                                             ImportDefinitionRevision source,
                                             String createdBy, String createdBySubject) {
        List<ImportDefinitionBookmark> declarations =
                bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(source.getId());
        Map<ImportDefinitionBookmark, List<ImportDefinitionBookmarkUsage>> usagesByBookmark =
                new LinkedHashMap<>();
        for (ImportDefinitionBookmark declaration : declarations) {
            usagesByBookmark.put(declaration, usages.findByBookmarkId(declaration.getId()));
        }
        revisionCopier.copyBookmarkDeclarations(target, declarations, usagesByBookmark, createdBy,
                createdBySubject);
    }

    /**
     * §1: de wijzigingsreden komt altijd uit het verzoek en wordt nooit uit de bron gekopieerd — een
     * geërfde reden zou beschrijven waarom de <i>vorige</i> revisie er kwam. Een te lange reden wordt
     * geweigerd, nooit stil afgekapt.
     */
    private static String requireChangeReason(String changeReason) {
        if (changeReason == null || changeReason.isBlank()) {
            throw new BadRequestException("CHANGE_REASON_REQUIRED", "changeReason is required when creating "
                    + "a successor revision: it is the only record of why this configuration changed");
        }
        return ActorNames.requireText(changeReason, "changeReason", MAX_CHANGE_REASON_LENGTH);
    }
}
