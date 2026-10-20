package be.dda.catalogimport.service;

import java.util.List;
import java.util.UUID;

/**
 * Het PREVIEW/APPLY-rapport van één legacy-config-ontvangst ({@code docs/design/legacy-config-bundle-design.md} par. 5,
 * slice S2). PREVIEW = APPLY-resultaat: dezelfde opbouw, zonder dat er iets geschreven wordt.
 * <p>
 * Elk legacy-element krijgt een {@link Item} met een status:
 * <ul>
 *   <li>{@value #MIGRATED}: overgenomen naar {@code target};</li>
 *   <li>{@value #NOT_MIGRATED}: bewust niet overgenomen (latere slice of nooit), gedrag verandert niet stil;</li>
 *   <li>{@value #WARNING}: overgenomen of weggelaten met een gedragsverschil dat een mens moet zien;</li>
 *   <li>{@value #BLOCKING}: APPLY is onmogelijk zolang dit element niet opgelost is.</li>
 * </ul>
 * Een rapport met minstens één {@value #BLOCKING}-item heeft {@code outcome} {@value #OUTCOME_BLOCKED}. Het rapport
 * bevat nooit een secret (de bundel in {@code bundle_json} heeft er geen).
 *
 * @param receiptId     de ontvangst
 * @param contentHash   de {@code contentHash} van die ontvangst
 * @param receiptStatus de huidige status van de ontvangst (APPLY kan enkel op {@code RECEIVED})
 * @param outcome       {@value #OUTCOME_APPLICABLE} of {@value #OUTCOME_BLOCKED}
 * @param planned       wat APPLY zou doen (of deed)
 * @param sharedWith    andere interfaces die dezelfde definitie delen; in S2 altijd leeg (delen volgt in S7)
 * @param items         per legacy-element de uitkomst, in vaste volgorde
 */
public record LegacyConfigReport(long receiptId, String contentHash, String receiptStatus, String outcome,
                                 Planned planned, List<String> sharedWith, List<Item> items) {

    public static final String MIGRATED = "MIGRATED";
    public static final String NOT_MIGRATED = "NOT_MIGRATED";
    public static final String WARNING = "WARNING";
    public static final String BLOCKING = "BLOCKING";

    public static final String OUTCOME_APPLICABLE = "APPLICABLE";
    public static final String OUTCOME_BLOCKED = "BLOCKED";

    /** Wat APPLY doet met een bestaand of nieuw doel. */
    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_REUSE = "REUSE";
    public static final String ACTION_SUCCESSOR_DRAFT = "SUCCESSOR_DRAFT";
    public static final String ACTION_UNCHANGED = "UNCHANGED";
    public static final String ACTION_NONE = "NONE";

    public LegacyConfigReport {
        sharedWith = List.copyOf(sharedWith);
        items = List.copyOf(items);
    }

    /** De codes van de blokkerende items, in rapportvolgorde (zonder dubbels). */
    public List<String> blockingCodes() {
        return items.stream().filter(item -> BLOCKING.equals(item.status())).map(Item::code).distinct().toList();
    }

    /**
     * Eén legacy-element.
     *
     * @param element bv. {@code PSIMPDEF#9}, {@code INDET:Separator}, {@code PSIMDHFD}, {@code PLAN:link}
     * @param target  het doel in CatalogImport (bv. {@code revision.identitySupplierField=3}) of {@code null}
     * @param message uitleg voor de beheerder; nooit een secret
     */
    public record Item(String element, String status, String code, String target, String message) {
    }

    /**
     * @param sourceOrganisation leverancier (find-or-create)
     * @param definition         eigen definitie: nieuw, opvolgende DRAFT of ongewijzigd
     * @param link               koppeling (nieuw of hergebruikt, nooit gewijzigd)
     * @param task               MANUAL-taak (nieuw of hergebruikt, nooit gewijzigd)
     * @param credential         de overgenomen credential (S4), of {@code null} als de herkomst er geen heeft en deze
     *                           ontvangst geen secret droeg
     */
    public record Planned(PlannedOrganisation sourceOrganisation, PlannedDefinition definition, PlannedLink link,
                          PlannedTask task, PlannedCredential credential) {
    }

    /**
     * De credential van de herkomst (slice S4); nooit een waarde. APPLY koppelt ze aan niets: een verbindingsprofiel
     * vraagt een hostsleutel (L5) en blijft een menselijke stap; het rapportitem {@code CONNECTION_NOT_MIGRATED} toont
     * het voorstel met deze {@code credentialRef}.
     *
     * @param credentialRef de credential van de herkomst ({@code legacy_config_origin.credential_id}), of {@code null}
     * @param secretOutcome wat er bij ontvangst met het secret van <b>deze</b> ontvangst gebeurde
     *                      ({@code STORED}, {@code DISCARDED_*}), of {@code null} als er geen secret meekwam
     */
    public record PlannedCredential(UUID credentialRef, String secretOutcome) {
    }

    public record PlannedOrganisation(String action, Long id, String code, String name) {
    }

    /**
     * @param revisionAction  {@value #ACTION_CREATE} (revisie 1 van een nieuwe definitie),
     *                        {@value #ACTION_SUCCESSOR_DRAFT}, {@value #ACTION_UNCHANGED} of {@value #ACTION_NONE}
     * @param basedOnRevision de bronrevisie van een opvolgende DRAFT
     * @param revisionId      na APPLY: de nieuwe revisie
     */
    public record PlannedDefinition(String action, Long id, String code, String name, String revisionAction,
                                    Long basedOnRevision, Long revisionId) {
    }

    public record PlannedLink(String action, Long id, String code, String libraryCode) {
    }

    public record PlannedTask(String action, Long id, String name) {
    }
}
