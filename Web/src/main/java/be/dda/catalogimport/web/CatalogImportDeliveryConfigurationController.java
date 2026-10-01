package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.DeliveryConfigurationService;
import be.dda.catalogimport.service.DeliveryConfigurationService.ConditionGroupInput;
import be.dda.catalogimport.service.DeliveryConfigurationService.ConditionInput;
import be.dda.catalogimport.service.DeliveryConfigurationService.DeliveryConfigurationDetail;
import be.dda.catalogimport.service.DeliveryConfigurationService.DeliveryConfigurationSummary;
import be.dda.catalogimport.service.DeliveryConfigurationService.NewDeliveryConfiguration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leveringsconfiguraties (bouwstap LC-2, {@code docs/design/leveringsconfiguratie-design.md} par. 3.2 en 6;
 * beslissingslog 2026-09-29 L3, L7, A3, A12, A17).
 *
 * <h2>Rechten</h2>
 * Alle endpoints {@code MANAGE}, ook de GET's (het detail toont de externe map, L7b; zelfde keuze als credentials en
 * profielen). De lijst toont enkel kopgegevens. Niet achter de setup-vlag (L7a). Recht eerst.
 *
 * <h2>Statuscodes</h2>
 * 201 bij aanmaken, anders 200. 400 {@code DELIVERY_CONFIGURATION_*} (invoer); 404
 * {@code DELIVERY_CONFIGURATION_NOT_FOUND}, {@code CONNECTION_PROFILE_VERSION_NOT_FOUND}; 409
 * {@code DELIVERY_CONFIGURATION_CODE_EXISTS}. {@code postFetchAction} is geen invoer: altijd {@code LEAVE} (A3).
 */
@RestController
@RequestMapping("/api/catalog-import/delivery-configurations")
public class CatalogImportDeliveryConfigurationController {

    /** Eén voorwaarde: {@code kind} uit {@code NAME_EQUALS}, {@code NAME_STARTS_WITH}, ..., {@code EXTENSION_IS}. */
    public record ConditionRequest(String kind, String value, Boolean caseSensitive) {
    }

    /** Eén groep: EN binnen de groep; groepen onderling OF. */
    public record ConditionGroupRequest(List<ConditionRequest> conditions) {
    }

    /** Body van {@code POST /delivery-configurations}; limieten weglaten = de default (A12). */
    public record CreateDeliveryConfigurationRequest(String code, String name, Long connectionProfileVersionId,
                                                     String remoteDirectory, String selectionMode,
                                                     List<ConditionGroupRequest> conditionGroups,
                                                     Integer minFileAgeSeconds, Long maxFileBytes, String reason) {
    }

    private final DeliveryConfigurationService configurations;
    private final CurrentActor currentActor;

    public CatalogImportDeliveryConfigurationController(DeliveryConfigurationService configurations,
                                                        CurrentActor currentActor) {
        this.configurations = configurations;
        this.currentActor = currentActor;
    }

    @RequiresPermission(Permission.MANAGE)
    @GetMapping
    List<DeliveryConfigurationSummary> list() {
        return configurations.list();
    }

    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/{configurationId}")
    DeliveryConfigurationDetail get(@PathVariable("configurationId") long configurationId) {
        return configurations.get(configurationId);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DeliveryConfigurationDetail create(@RequestBody(required = false) CreateDeliveryConfigurationRequest request) {
        ActorIdentity actor = currentActor.signer(null, "actor");
        return configurations.create(request == null ? null : toInput(request), actor);
    }

    private static NewDeliveryConfiguration toInput(CreateDeliveryConfigurationRequest request) {
        List<ConditionGroupInput> groups = request.conditionGroups() == null ? null
                : request.conditionGroups().stream().map(group -> group == null ? null
                        : new ConditionGroupInput(group.conditions() == null ? null : group.conditions().stream()
                                .map(c -> c == null ? null : new ConditionInput(c.kind(), c.value(), c.caseSensitive()))
                                .toList()))
                .toList();
        return new NewDeliveryConfiguration(request.code(), request.name(), request.connectionProfileVersionId(),
                request.remoteDirectory(), request.selectionMode(), groups, request.minFileAgeSeconds(),
                request.maxFileBytes(), request.reason());
    }
}
