package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.PublicationRunItemRepository;
import be.dda.catalogimport.dao.PublicationRunRepository;
import be.dda.catalogimport.domain.PublicationRun;
import be.dda.catalogimport.domain.PublicationRunItem;
import be.dda.catalogimport.domain.PublicationRunItemStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.service.ProdisPublicationRunService.ProductionRunView;
import be.dda.catalogimport.service.PublicationRunService.PublicationRunView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * De API-kant van de publicatieruns (slice P6, {@code docs/design/publicatie-prodis-db-direct-design.md} par. 6 rij P6): één
 * ingang voor de Web-laag die {@code SIMULATION} ({@link PublicationRunService}) en {@code PRODUCTION}
 * ({@link ProdisPublicationRunService}) achter hetzelfde runcontract ({@link PublicationRunView}) samenbrengt, plus de
 * itemlijst van een run. Geen eigen businesslogica: starten, voortzetten en alle controles blijven in de twee services.
 *
 * <h2>Starten</h2>
 * De modus wordt eerst geparsed (400 {@code PUBLICATION_MODE_REQUIRED/_UNKNOWN}). {@code PRODUCTION} gaat naar
 * {@link ProdisPublicationRunService#startProductionRun}; elke andere modus naar {@link PublicationRunService#requestRun}
 * (ongewijzigd: {@code SIMULATION} werkt, {@code TRIAL_LIBRARY} geeft 409 {@code PUBLICATION_MODE_NOT_ENABLED}). Het antwoord is in
 * beide gevallen de run zoals {@link PublicationRunService#getRun} ze toont, dus met {@code itemCounts}, {@code done} en
 * {@code legacyMasterAtStart}.
 *
 * <h2>Itemlijst</h2>
 * Items oplopend op id, optioneel gefilterd op status, gepagineerd zoals de bundellijsten ({@link BundleQueryService}).
 * {@code prior} en {@code result} zijn de Prodis-toestanden ({@code ArticleState} uit het parallelrunrapport: bedragen als
 * tekst) uit {@code prior_json} ({@code prior}) en {@code result_json} ({@code target}); {@code null} als er geen is. De
 * identiteit komt uit het bevroren verzoek ({@code request_json}); Prodis-id's, hashes en het subject van de verzender worden
 * nooit getoond. Een run zonder items (SIMULATION) geeft een lege pagina; een onbekende run 404.
 */
@Service
public class PublicationRunApiService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** De identiteit van het item in Prodis-businesscodes ({@code libraryCode} = {@code scopeKey} van het verzoek). */
    public record ItemIdentity(String supplierNumber, String groupLegacyCode, String supplierReference,
                               String libraryCode) {
    }

    /** Een melding bij een item; de tekst is Engels en bedoeld voor "technische details". */
    public record ItemWarning(String code, String severity, String field, String message) {
    }

    /**
     * Eén item van een PRODUCTION-run.
     *
     * @param prior  de Prodis-toestand vóór het schrijven, {@code null} als die niet gelezen werd
     * @param result de toestand na toepassing ({@code target} van het resultaat), {@code null} als er niet gerekend kon worden
     */
    public record PublicationItemView(long mutationId, ItemIdentity identity, String status, String reasonCode,
                                      String reasonDetail, JsonNode prior, JsonNode result, List<ItemWarning> warnings,
                                      String legacyMaster, int sendCount, String lastErrorCode) {
    }

    private final PublicationRunService runs;
    private final ProdisPublicationRunService production;
    private final PublicationRunRepository runRepository;
    private final PublicationRunItemRepository items;

    public PublicationRunApiService(PublicationRunService runs, ProdisPublicationRunService production,
                                    PublicationRunRepository runRepository, PublicationRunItemRepository items) {
        this.runs = runs;
        this.production = production;
        this.runRepository = runRepository;
        this.items = items;
    }

    /**
     * Vraagt een run aan in de gevraagde modus (zie de klassebeschrijving).
     *
     * @throws BadRequestException {@code PUBLICATION_MODE_REQUIRED}, {@code PUBLICATION_MODE_UNKNOWN}
     * @throws ConflictException   zie {@link PublicationRunService#requestRun} en {@link ProdisPublicationRunService#startProductionRun}
     */
    public PublicationRunView request(long bundleId, String targetMode, ActorIdentity actor) {
        PublicationTargetMode mode = PublicationRunService.parseMode(targetMode);
        if (mode == PublicationTargetMode.PRODUCTION) {
            ProductionRunView started = production.startProductionRun(bundleId, actor);
            return runs.getRun(started.runId());
        }
        return runs.requestRun(bundleId, mode, actor);
    }

    /**
     * Verwerkt de volgende chunk van een PRODUCTION-run en toont de run daarna.
     *
     * @throws NotFoundException {@code PUBLICATION_RUN_NOT_FOUND}
     * @throws ConflictException zie {@link ProdisPublicationRunService#continueRun}
     */
    public PublicationRunView continueRun(long runId, ActorIdentity actor) {
        production.continueRun(runId, actor);
        return runs.getRun(runId);
    }

    /**
     * De items van een run.
     *
     * @param status {@code null} = alle statussen
     * @throws NotFoundException        {@code PUBLICATION_RUN_NOT_FOUND}
     * @throws IllegalArgumentException ongeldige paginering
     */
    @Transactional(readOnly = true)
    public PageResult<PublicationItemView> listItems(long runId, PublicationRunItemStatus status, Integer page,
                                                     Integer size) {
        PublicationRun run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException(
                PublicationRunService.CODE_RUN_NOT_FOUND, "Publication run " + runId + " not found"));
        PageRequest pageRequest = BundleQueryService.pageRequest(page, size, Sort.by(Sort.Direction.ASC, "id"));
        Page<PublicationRunItem> result = status == null ? items.findByRunId(run.getId(), pageRequest)
                : items.findByRunIdAndStatus(run.getId(), status, pageRequest);
        return PageResult.of(result, PublicationRunApiService::itemView);
    }

    private static PublicationItemView itemView(PublicationRunItem item) {
        JsonNode request = parse(item.getRequestJson(), item, "request_json");
        JsonNode identity = request == null ? null : request.path("identity");
        return new PublicationItemView(item.getMutation().getId(),
                new ItemIdentity(text(identity, "supplierNumber"), text(identity, "groupLegacyCode"),
                        text(identity, "supplierReference"), text(identity, "scopeKey")),
                item.getStatus().name(), item.getReasonCode(), item.getReasonDetail(),
                state(parse(item.getPriorJson(), item, "prior_json"), "prior"),
                state(parse(item.getResultJson(), item, "result_json"), "target"), warnings(item),
                item.getLegacyMaster(), item.getSendCount(), item.getLastErrorCode());
    }

    private static List<ItemWarning> warnings(PublicationRunItem item) {
        JsonNode array = parse(item.getWarningsJson(), item, "warnings_json");
        List<ItemWarning> warnings = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                warnings.add(new ItemWarning(text(node, "code"), text(node, "severity"), text(node, "field"),
                        text(node, "message")));
            }
        }
        return warnings;
    }

    /** De toestand onder {@code name}; {@code null} als het document of het veld ontbreekt of {@code null} is. */
    private static JsonNode state(JsonNode document, String name) {
        if (document == null) {
            return null;
        }
        JsonNode state = document.get(name);
        return state == null || state.isNull() ? null : state;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Een onleesbare opgeslagen JSON is een fout (500), geen stil lege waarde: wat getoond wordt, klopt of ontbreekt niet stil. */
    private static JsonNode parse(String json, PublicationRunItem item, String column) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Publication item " + item.getId() + " has unreadable " + column, failure);
        }
    }
}
