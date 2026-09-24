package be.dda.catalogimport.web;

import be.dda.catalogimport.service.PageResult;
import be.dda.catalogimport.service.TaskQueryService;
import be.dda.catalogimport.service.TaskQueryService.TaskRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alleen-lezen takenlijst (Scherm 2, bouwstap B-B1): laat de UI een taak kiezen voor een levering.
 * <p>
 * <b>Bewust NIET achter {@code catalogimport.setup-api.enabled}</b> (beslissingslog 23/09, D14
 * "autorisatiegrens is lezen-vs-schrijven"): dit endpoint schrijft niets, net zoals
 * {@code GET /import-links}. Het botst niet met {@code POST /tasks/{taskId}/deliveries} in
 * {@link CatalogImportDeliveryController}: andere HTTP-methode en ander pad.
 */
@RestController
@RequestMapping("/api/catalog-import/tasks")
public class CatalogImportTaskController {

    private final TaskQueryService queries;

    public CatalogImportTaskController(TaskQueryService queries) {
        this.queries = queries;
    }

    /** Alle taken, oplopend op koppelingscode, naam en id; optioneel gefilterd op koppeling en {@code active}. */
    @GetMapping
    PageResult<TaskRow> tasks(@RequestParam(value = "importLinkId", required = false) Long importLinkId,
                              @RequestParam(value = "active", required = false) Boolean active,
                              @RequestParam(value = "page", required = false) Integer page,
                              @RequestParam(value = "size", required = false) Integer size) {
        return queries.listTasks(importLinkId, active, page, size);
    }
}
