package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportFieldTransformStepRepository;
import be.dda.catalogimport.dao.ImportFieldValueMapRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.LegacyConfigDefinitionOriginRepository;
import be.dda.catalogimport.dao.LegacyConfigReceiptRepository;
import be.dda.catalogimport.dao.LegacyConfigRevisionOriginRepository;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.LegacyConfigDefinitionOrigin;
import be.dda.catalogimport.domain.LegacyConfigReceipt;
import be.dda.catalogimport.domain.LegacyConfigRevisionOrigin;
import be.dda.catalogimport.service.LegacyConfigTranslator.LineTarget;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * De legacy-herkomst van één revisie, voor {@code GET /definitions/{id}/revisions/{rid}/legacy-origin}
 * (beslissingslog 2026-10-23 "API-uitbreiding voor de inrichtingsschermen", punt 1). Alleen-lezen; schrijft niets.
 *
 * <h2>Business rule → technische vertaling</h2>
 * <ul>
 *   <li><b>Herkomst</b>, in deze volgorde:
 *     <ol>
 *       <li>{@code MIGRATED}: de revisie heeft een eigen {@code legacy_config_revision_origin}-rij (APPLY maakte haar,
 *           of ze is revisie 1 van een losgekoppelde kopie);</li>
 *       <li>{@code INHERITED}: anders de {@code basedOnRevision}-keten binnen dezelfde definitie (hoogstens
 *           {@value #MAX_CHAIN_STEPS} stappen) tot de dichtste revisie met een herkomstrij - een handmatige
 *           opvolger;</li>
 *       <li>{@code TEMPLATE_SOURCE}: anders, voor een {@code REUSABLE_TEMPLATE}, de gedeelde definitie met
 *           {@code legacy_config_definition_origin.template_definition_id} = dit sjabloon en daarvan de revisie
 *           met {@code based_on_revision_id} = deze revisie die een herkomstrij heeft (APPLY S7 schrijft de herkomst
 *           enkel op de gedeelde definitie, niet op het sjabloon).</li>
 *     </ol>
 *     Geen van drie: {@code fromLegacy=false}, geen regels.</li>
 *   <li><b>{@code changedSinceMigration}</b>: de inhoudshash van <i>deze</i> revisie ({@link LegacyRevisionContent},
 *       dezelfde berekening als APPLY voor {@code MANUAL_CHANGES_SINCE_MIGRATION}) verschilt van
 *       {@code migrated_content_hash} van de gevonden herkomstrij.</li>
 *   <li><b>Regels</b>: enkel {@code bundle_json.definition.lines[]} van de ontvangst (contract
 *       {@code legacy-config-bundle-v1.md} par. 3), in de bewaarde volgorde, met per regel het vaste doel uit
 *       {@link LegacyConfigTranslator#targetOfLine(int)}. Nooit {@code interface} (bestemming, login, INDET), nooit
 *       {@code secret}, nooit {@code maps}/{@code excludes}. Een onleesbare {@code bundle_json} geeft
 *       {@code linesAvailable=false} en een logregel zonder inhoud (ook niet de Jackson-melding, die een stuk van de
 *       tekst kan citeren).</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class RevisionLegacyOriginService {

    static final String MIGRATED = "MIGRATED";
    static final String INHERITED = "INHERITED";
    static final String TEMPLATE_SOURCE = "TEMPLATE_SOURCE";
    /** Bovengrens op de {@code basedOnRevision}-keten; beschermt ook tegen een (onmogelijke) cyclus. */
    static final int MAX_CHAIN_STEPS = 100;

    private static final Logger LOG = LoggerFactory.getLogger(RevisionLegacyOriginService.class);

    private final ImportDefinitionRepository definitions;
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportFieldMappingRepository fieldMappings;
    private final ImportRecordFilterRepository recordFilters;
    private final ImportFieldValueMapRepository valueMaps;
    private final ImportFieldTransformStepRepository transformSteps;
    private final LegacyConfigDefinitionOriginRepository definitionOrigins;
    private final LegacyConfigRevisionOriginRepository revisionOrigins;
    private final LegacyConfigReceiptRepository receipts;
    private final ObjectMapper bundleReader = new ObjectMapper();

    public RevisionLegacyOriginService(ImportDefinitionRepository definitions,
                                       ImportDefinitionRevisionRepository revisions,
                                       ImportFieldMappingRepository fieldMappings,
                                       ImportRecordFilterRepository recordFilters,
                                       ImportFieldValueMapRepository valueMaps,
                                       ImportFieldTransformStepRepository transformSteps,
                                       LegacyConfigDefinitionOriginRepository definitionOrigins,
                                       LegacyConfigRevisionOriginRepository revisionOrigins,
                                       LegacyConfigReceiptRepository receipts) {
        this.definitions = definitions;
        this.revisions = revisions;
        this.fieldMappings = fieldMappings;
        this.recordFilters = recordFilters;
        this.valueMaps = valueMaps;
        this.transformSteps = transformSteps;
        this.definitionOrigins = definitionOrigins;
        this.revisionOrigins = revisionOrigins;
        this.receipts = receipts;
    }

    /**
     * De legacy-herkomst van een revisie.
     *
     * @param originKind             {@code MIGRATED}, {@code INHERITED} of {@code TEMPLATE_SOURCE}; {@code null} als
     *                               {@code fromLegacy=false}
     * @param originRevisionId       de revisie met de herkomstrij (bij {@code MIGRATED} deze revisie zelf)
     * @param changedSinceMigration  {@code null} als {@code fromLegacy=false}
     * @param linesAvailable         {@code false} als er geen herkomst is of {@code bundle_json} onleesbaar is
     */
    public record RevisionLegacyOriginView(long revisionId, boolean fromLegacy, String originKind,
                                           Long originRevisionId, Boolean changedSinceMigration, Long receiptId,
                                           String interfaceCode, String legacyDefinitionCode, Instant exportedAt,
                                           Instant appliedAt, boolean linesAvailable, List<LegacyLineView> lines) {
    }

    /**
     * Eén PSIMPDEF-regel zoals ontvangen ({@code fixedValue} = bundelveld {@code value}, {@code legacyFieldName} =
     * {@code field}), met het vaste doel van haar veldnummer.
     *
     * @param targetKind      {@code MAPPING}, {@code REVISION_FIELD} of {@code NONE}
     * @param targetFieldCode de catalogcode bij {@code MAPPING}
     * @param revisionField   het revisieveld bij {@code REVISION_FIELD}
     */
    public record LegacyLineView(int number, String legacyFieldName, String targetKind, String targetFieldCode,
                                 String revisionField, int column, int position, int length, String prefix,
                                 String suffix, String fixedValue, String calculation, String calculationValue,
                                 String fill, String xmlElementPath, String includeExclude,
                                 String includeExcludeData) {
    }

    /** Welke herkomstrij bij de revisie hoort, en hoe ze gevonden werd. */
    private record ResolvedOrigin(String kind, long originRevisionId, LegacyConfigRevisionOrigin row) {
    }

    /**
     * @throws NotFoundException {@code DEFINITION_NOT_FOUND}, {@code REVISION_NOT_FOUND} (ook als de revisie niet van
     *                           deze definitie is)
     */
    public RevisionLegacyOriginView getLegacyOrigin(long definitionId, long revisionId) {
        ImportDefinition definition = definitions.findById(definitionId)
                .orElseThrow(() -> new NotFoundException("DEFINITION_NOT_FOUND",
                        "Import definition " + definitionId + " does not exist"));
        ImportDefinitionRevision revision = revisions.findById(revisionId)
                .orElseThrow(() -> new NotFoundException("REVISION_NOT_FOUND",
                        "Definition revision " + revisionId + " does not exist"));
        if (!revision.getImportDefinition().getId().equals(definitionId)) {
            throw new NotFoundException("REVISION_NOT_FOUND", "Revision " + revisionId
                    + " does not belong to definition " + definitionId);
        }
        Optional<ResolvedOrigin> resolved = resolve(definition, revision);
        if (resolved.isEmpty()) {
            return new RevisionLegacyOriginView(revisionId, false, null, null, null, null, null, null, null, null,
                    false, List.of());
        }
        ResolvedOrigin origin = resolved.get();
        LegacyConfigReceipt receipt = receipts.findById(origin.row().getReceiptId())
                .orElseThrow(() -> new IllegalStateException("legacy_config_revision_origin " + origin.row().getId()
                        + " refers to a missing receipt"));
        boolean changed = !contentHash(revision).equals(origin.row().getMigratedContentHash());
        List<LegacyLineView> lines = lines(receipt, revisionId);
        return new RevisionLegacyOriginView(revisionId, true, origin.kind(), origin.originRevisionId(), changed,
                receipt.getId(), receipt.getInterfaceCode(), receipt.getLegacyDefinitionCode(),
                receipt.getExportedAt(), receipt.getAppliedAt(), lines != null, lines == null ? List.of() : lines);
    }

    private Optional<ResolvedOrigin> resolve(ImportDefinition definition, ImportDefinitionRevision revision) {
        Optional<LegacyConfigRevisionOrigin> own = revisionOrigins.findByRevisionId(revision.getId());
        if (own.isPresent()) {
            return Optional.of(new ResolvedOrigin(MIGRATED, revision.getId(), own.get()));
        }
        Optional<ResolvedOrigin> inherited = inherited(definition, revision);
        if (inherited.isPresent()) {
            return inherited;
        }
        if (definition.getUsageType() == DefinitionUsageType.REUSABLE_TEMPLATE) {
            return templateSource(definition, revision);
        }
        return Optional.empty();
    }

    /** De dichtste voorganger binnen dezelfde definitie met een herkomstrij. */
    private Optional<ResolvedOrigin> inherited(ImportDefinition definition, ImportDefinitionRevision revision) {
        Set<Long> seen = new HashSet<>();
        seen.add(revision.getId());
        ImportDefinitionRevision current = revision.getBasedOnRevision();
        for (int step = 0; step < MAX_CHAIN_STEPS && current != null; step++) {
            if (!current.getImportDefinition().getId().equals(definition.getId()) || !seen.add(current.getId())) {
                return Optional.empty();
            }
            Optional<LegacyConfigRevisionOrigin> row = revisionOrigins.findByRevisionId(current.getId());
            if (row.isPresent()) {
                return Optional.of(new ResolvedOrigin(INHERITED, current.getId(), row.get()));
            }
            current = current.getBasedOnRevision();
        }
        return Optional.empty();
    }

    /**
     * Het sjabloon zelf heeft geen herkomstrij (S7): de gedeelde definitie(s) van dit sjabloon, en daarvan de revisie
     * die een kopie is van deze sjabloonrevisie en een herkomstrij heeft. Meerdere kandidaten: de laagste revisie-id.
     */
    private Optional<ResolvedOrigin> templateSource(ImportDefinition template, ImportDefinitionRevision revision) {
        List<Long> sharedDefinitionIds = definitionOrigins.findByTemplateDefinitionIdIn(List.of(template.getId()))
                .stream().map(LegacyConfigDefinitionOrigin::getImportDefinitionId).distinct().toList();
        if (sharedDefinitionIds.isEmpty()) {
            return Optional.empty();
        }
        List<Long> copies = revisions.findByImportDefinitionIdInAndBasedOnRevisionIdOrderByIdAsc(sharedDefinitionIds,
                revision.getId()).stream().map(ImportDefinitionRevision::getId).toList();
        if (copies.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, LegacyConfigRevisionOrigin> rows = revisionOrigins.findByRevisionIdIn(copies).stream()
                .collect(Collectors.toMap(LegacyConfigRevisionOrigin::getRevisionId, Function.identity()));
        return copies.stream().filter(rows::containsKey).findFirst()
                .map(id -> new ResolvedOrigin(TEMPLATE_SOURCE, id, rows.get(id)));
    }

    /**
     * Zelfde berekening en bronnen als APPLY ({@code LegacyConfigApplyService},
     * {@code MANUAL_CHANGES_SINCE_MIGRATION}).
     */
    private String contentHash(ImportDefinitionRevision revision) {
        long id = revision.getId();
        return LegacyRevisionContent.ofRevision(revision, fieldMappings.findByRevisionIdWithTargetField(id),
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(id), valueMaps.findByRevisionId(id),
                transformSteps.findByRevisionId(id));
    }

    /**
     * Enkel {@code definition.lines[]}; {@code null} als {@code bundle_json} onleesbaar is of dat deel ontbreekt.
     */
    private List<LegacyLineView> lines(LegacyConfigReceipt receipt, long revisionId) {
        JsonNode bundle;
        try {
            bundle = bundleReader.readTree(receipt.getBundleJson());
        } catch (JsonProcessingException | IllegalArgumentException unreadable) {
            // Bewust zonder de exceptie: de Jackson-melding kan een stuk van de bundeltekst citeren.
            LOG.warn("bundle_json of legacy-config receipt {} is not readable; the legacy lines of revision {} are not "
                    + "shown", receipt.getId(), revisionId);
            return null;
        }
        JsonNode lines = bundle == null ? null : bundle.path("definition").path("lines");
        if (lines == null || !lines.isArray()) {
            LOG.warn("bundle_json of legacy-config receipt {} has no definition.lines; the legacy lines of revision {} "
                    + "are not shown", receipt.getId(), revisionId);
            return null;
        }
        List<LegacyLineView> result = new ArrayList<>(lines.size());
        for (JsonNode line : lines) {
            int number = line.path("number").asInt();
            LineTarget target = LegacyConfigTranslator.targetOfLine(number);
            result.add(new LegacyLineView(number, text(line, "field"), target.kind().name(),
                    target.targetFieldCode(), target.revisionField(), line.path("column").asInt(),
                    line.path("position").asInt(), line.path("length").asInt(), text(line, "prefix"),
                    text(line, "suffix"), text(line, "value"), text(line, "calculation"),
                    text(line, "calculationValue"), text(line, "fill"), text(line, "xmlElementPath"),
                    text(line, "includeExclude"), text(line, "includeExcludeData")));
        }
        return result;
    }

    /**
     * De tekst van een regelveld; {@code null} als het ontbreekt of JSON-null is (het contract verplicht alle
     * sleutels).
     */
    private static String text(JsonNode line, String name) {
        JsonNode value = line.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }
}
