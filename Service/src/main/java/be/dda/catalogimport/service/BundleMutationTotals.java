package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.PublicationBundleDao.MutationStatusCount;
import java.util.List;

/**
 * De inhoudelijke tellers van één Publicatiebundel, afgeleid uit
 * {@link be.dda.catalogimport.dao.PublicationBundleDao#countByStatus} (ontwerp fase 4 par. 2, de tien
 * tellers op {@code publication_bundle}).
 * <p>
 * <b>Waarom één gedeelde plek.</b> Dezelfde tellers worden twee keer gebruikt: live in
 * {@code BundleQueryService.getBundle} zolang de bundel {@code ASSEMBLING} is, en één keer definitief
 * vastgelegd op de rij zelf bij het bevriezen ({@code BundleFreezeService}, bouwstap 4e). Zouden die
 * twee elk hun eigen optelling doen, dan kan de bevroren teller stilzwijgend iets anders betekenen dan
 * wat de gebruiker vlak vóór het bevriezen op zijn scherm zag — en juist dát getal is waarvoor hij
 * tekende.
 * <p>
 * <b>Afbakening.</b> {@code contentMutationCount} en de vier statustellers gaan uitsluitend over
 * inhoudelijke mutaties ({@code CREATE}/{@code UPDATE}); de {@code IMPORT_MARKER} telt nooit mee.
 * {@code identityIncidentCount} telt de {@code IDENTITY_REFERENCE_INCIDENT}-mutaties, ongeacht hun
 * status: die krijgen in Fase 4 geen beslispad (ontwerp par. 3.6) en zijn dus geen deelverzameling van
 * de vier statustellers.
 */
record BundleMutationTotals(long contentMutationCount, long readyCount, long rejectedCount,
                            long blockedCount, long expiredCount, long identityIncidentCount) {

    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String IDENTITY_REFERENCE_INCIDENT = "IDENTITY_REFERENCE_INCIDENT";

    /**
     * Telt de per {@code (action_type, status)} gegroepeerde aantallen op. Tekstwaarden, geen enums:
     * dit komt rechtstreeks van de databasegrens.
     */
    static BundleMutationTotals of(List<MutationStatusCount> counts) {
        long contentMutations = 0;
        long ready = 0;
        long rejected = 0;
        long blocked = 0;
        long expired = 0;
        long identityIncidents = 0;
        for (MutationStatusCount count : counts) {
            if (CREATE.equals(count.actionType()) || UPDATE.equals(count.actionType())) {
                contentMutations += count.count();
                switch (count.status()) {
                    case "READY_FOR_PUBLICATION" -> ready += count.count();
                    case "REJECTED" -> rejected += count.count();
                    case "BLOCKED" -> blocked += count.count();
                    case "EXPIRED" -> expired += count.count();
                    default -> {
                        // PLANNED/AWAITING_APPROVAL/SKIPPED/... hebben geen eigen teller op de bundel.
                    }
                }
            } else if (IDENTITY_REFERENCE_INCIDENT.equals(count.actionType())) {
                identityIncidents += count.count();
            }
        }
        return new BundleMutationTotals(contentMutations, ready, rejected, blocked, expired, identityIncidents);
    }
}
