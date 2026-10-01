package be.dda.catalogimport.web;

import be.dda.catalogimport.service.SetupService;
import be.dda.catalogimport.service.SetupService.Overview;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /setup/overview}: de volledige inrichtingsboom in één antwoord, inclusief de {@code taskId}
 * die een upload nodig heeft — een ontwikkelhulp (README, {@code DemoDataSeeder}).
 *
 * <h2>Blijft achter de setup-vlag</h2>
 * Deze controller bestaat alleen wanneer {@code catalogimport.setup-api.enabled=true} staat; zonder de vlag
 * antwoordt het pad met 404, ongeacht de rechten. Met de vlag aan vraagt het {@code READ}. Dat is een bewuste
 * uitzondering (beslissingslog 2026-09-30 "Nieuwe leverancier + taak (NT-spoor)", V2 = a): de schrijfpaden
 * van de inrichting kwamen in NT-3 achter de vlag vandaan ({@link CatalogImportSetupController}), dit
 * overzicht niet. De schermen gebruiken het niet; zij lezen via {@link CatalogImportSetupQueryController},
 * {@code GET /import-links} en {@code GET /tasks}.
 * <p>
 * Pad, antwoord en recht zijn ongewijzigd; de handler stond vóór NT-3 in {@link CatalogImportSetupController}.
 */
@RestController
@RequestMapping("/api/catalog-import/setup")
@ConditionalOnProperty(prefix = "catalogimport.setup-api", name = "enabled", havingValue = "true")
public class CatalogImportSetupOverviewController {

    private final SetupService setup;

    public CatalogImportSetupOverviewController(SetupService setup) {
        this.setup = setup;
    }

    /** Wat er geconfigureerd staat, inclusief de {@code taskId} die een upload nodig heeft. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/overview")
    Overview overview() {
        return setup.overview();
    }
}
