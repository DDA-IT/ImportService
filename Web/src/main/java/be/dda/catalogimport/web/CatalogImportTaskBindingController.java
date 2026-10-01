package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.TaskDeliveryConfigurationService;
import be.dda.catalogimport.service.TaskDeliveryConfigurationService.TaskBindingView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Taakkoppeling aan een Leveringsconfiguratie-versie (bouwstap LC-2, {@code docs/design/leveringsconfiguratie-design.md}
 * par. 6; beslissingslog 2026-09-29 L2, L3, L7, A10, A11). Een eigen controller naast {@link CatalogImportTaskController}
 * (zelfde basispad, andere methode en pad), zodat de bestaande alleen-lezen takenlijst ongewijzigd blijft.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code PUT /tasks/{taskId}/delivery-configuration} {@code {versionId, reason}}: koppelen of herkoppelen; 200 met
 *       de koppeling. Dezelfde versie opnieuw = 200 zonder wijziging of event.</li>
 *   <li>{@code DELETE /tasks/{taskId}/delivery-configuration} met body {@code {reason}}: ontkoppelen; 200 met de
 *       (lege) koppeling. De reden staat bewust in de body en niet in de querystring: zo belandt vrije tekst niet in
 *       toegangslogs, en het is dezelfde vorm als de PUT.</li>
 * </ul>
 * Beide vragen {@code MANAGE} (niet achter de setup-vlag, L7a). Foutcodes: 400 {@code TASK_BINDING_VERSION_REQUIRED},
 * {@code TASK_BINDING_REASON_REQUIRED}; 404 {@code TASK_NOT_FOUND}, {@code DELIVERY_CONFIGURATION_VERSION_NOT_FOUND};
 * 409 {@code TASK_RUN_IN_PROGRESS}, {@code IMPORT_LINK_HAS_DELIVERY_CONFIGURATION_TASK} (A11),
 * {@code TASK_HAS_NO_DELIVERY_CONFIGURATION}. Recht eerst (403 vóór 400/404/409).
 */
@RestController
@RequestMapping("/api/catalog-import/tasks")
public class CatalogImportTaskBindingController {

    /** Body van de PUT. */
    public record BindDeliveryConfigurationRequest(Long versionId, String reason) {
    }

    /** Body van de DELETE. */
    public record UnbindDeliveryConfigurationRequest(String reason) {
    }

    private final TaskDeliveryConfigurationService bindings;
    private final CurrentActor currentActor;

    public CatalogImportTaskBindingController(TaskDeliveryConfigurationService bindings, CurrentActor currentActor) {
        this.bindings = bindings;
        this.currentActor = currentActor;
    }

    @RequiresPermission(Permission.MANAGE)
    @PutMapping("/{taskId}/delivery-configuration")
    TaskBindingView bind(@PathVariable("taskId") long taskId,
                         @RequestBody(required = false) BindDeliveryConfigurationRequest request) {
        ActorIdentity actor = currentActor.signer(null, "actor");
        return bindings.bind(taskId, request == null ? null : request.versionId(),
                request == null ? null : request.reason(), actor);
    }

    @RequiresPermission(Permission.MANAGE)
    @DeleteMapping("/{taskId}/delivery-configuration")
    TaskBindingView unbind(@PathVariable("taskId") long taskId,
                           @RequestBody(required = false) UnbindDeliveryConfigurationRequest request) {
        ActorIdentity actor = currentActor.signer(null, "actor");
        return bindings.unbind(taskId, request == null ? null : request.reason(), actor);
    }
}
