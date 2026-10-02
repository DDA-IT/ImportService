package be.dda.catalogimport.config;

import be.dda.catalogimport.service.StagingPurgeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Geplande opruiming van de kandidaatstaging (stap 7, S7-P3; docs/decisions.md 2026-10-02 "Stap 7 uitgewerkt"):
 * roept {@link StagingPurgeService#purge(boolean)} op volgens {@code catalogimport.staging-retention.cron} (default
 * dagelijks 03:30, tijdzone van de JVM).
 * <p>
 * <b>Standaard UIT</b> (mens): enkel actief met {@code catalogimport.staging-retention.enabled=true}, per omgeving aan
 * te zetten. Gekozen voor {@link ConditionalOnProperty} in plaats van een controle in de methode: zonder die property
 * bestaat deze bean niet, wordt er geen scheduler gestart en wordt de cron niet eens geïnterpreteerd. Zo verandert er
 * in een omgeving die de opruiming niet aanzet (en in elke bestaande test) niets.
 * <p>
 * <b>{@link EnableScheduling} staat bewust hier, mee onder dezelfde voorwaarde.</b> Er bestaat geen andere
 * {@code @Scheduled}-methode in de applicatie. Komt er ooit een tweede geplande taak bij, dan moet
 * {@code @EnableScheduling} naar een onvoorwaardelijke configuratieklasse verhuizen; anders loopt die taak enkel
 * wanneer de opruiming aanstaat.
 * <p>
 * Meerdere instanties mogen de taak tegelijk draaien: elke batch wordt onder haar rijslot met {@code NOWAIT} behandeld,
 * dus de tweede instantie slaat een bezette batch over en een afgewerkte batch wordt nooit opnieuw geselecteerd.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "catalogimport.staging-retention", name = "enabled", havingValue = "true")
public class StagingPurgeSchedule {

    private final StagingPurgeService purge;

    public StagingPurgeSchedule(StagingPurgeService purge) {
        this.purge = purge;
    }

    /** Eén echte run (geen proefrun); het rapport wordt door de service gelogd. */
    @Scheduled(cron = "${catalogimport.staging-retention.cron:0 30 3 * * *}")
    public void run() {
        purge.purge(false);
    }
}
