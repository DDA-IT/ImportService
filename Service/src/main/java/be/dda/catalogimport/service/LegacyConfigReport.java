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
 * <h2>Aanmaakstandaard van de koppeling (slice CD-B3)</h2>
 * Beslissingslog 2026-10-23 "Btw-code en eenheid: aanmaakstandaard per koppeling", "Aanmaakstandaard per koppeling:
 * uitwerking (CD)" en "Vlag 'standaarden overschrijven' op de importdefinitie". De vaste legacy-waarde van PSIMPDEF 51
 * (btw-code) en 45 (eenheid) wordt de aanmaakstandaard van de koppeling ({@code import_link.default_vat_code} /
 * {@code default_unit_code}); nooit een veld per regel. Codes (element {@code PSIMPDEF#51} of {@code PSIMPDEF#45}):
 * <ul>
 *   <li>{@value #CREATION_DEFAULT_VAT} / {@value #CREATION_DEFAULT_UNIT} ({@value #MIGRATED}): de vaste waarde wordt
 *       (of is al) de standaard van de koppeling; target {@code link.defaultVatCode=<code>} /
 *       {@code link.defaultUnitCode=<code>};</li>
 *   <li>{@value #CREATION_DEFAULT_FROM_COLUMN_NOT_MIGRATED} ({@value #WARNING}): de regel leest een bronkolom in plaats
 *       van een vaste waarde; een standaard per koppeling kan geen waarde per lijn zijn, er wordt niets gezet;</li>
 *   <li>{@value #CREATION_DEFAULT_DUPLICATED}, {@value #CREATION_DEFAULT_VALUE_EMPTY},
 *       {@value #CREATION_DEFAULT_VALUE_TOO_LONG} ({@value #WARNING}): dubbele regel, lege waarde of langer dan 50
 *       tekens; er wordt niets gezet (nooit afgekapt);</li>
 *   <li>{@value #CREATION_DEFAULT_NOT_YET_SUPPORTED} ({@value #NOT_MIGRATED}): PSIMPDEF 50/59 (rekeningen) of 1
 *       (artikelgroep) met een vaste waarde; later (V3), de waarde staat in de boodschap;</li>
 *   <li>Prodis-controle: {@value #PRODIS_VAT_CODE} / {@value #PRODIS_UNIT} ({@value #MIGRATED}, gevonden),
 *       {@value #PRODIS_VAT_CODE_NOT_FOUND} / {@value #PRODIS_UNIT_NOT_FOUND} ({@value #WARNING}, niet gezet, V2),
 *       {@value #PRODIS_REFERENCE_AMBIGUOUS} ({@value #WARNING}, meer dan één actieve rij, wel gezet), of
 *       {@code PRODIS_REFERENCE_NOT_VERIFIED} ({@value #WARNING}, niet geconfigureerd of onbereikbaar, wel gezet);</li>
 *   <li>bestaande koppeling, per veld: {@value #CREATION_DEFAULT_DIFFERS_NOT_CHANGED} ({@value #WARNING}, vlag uit,
 *       beide waarden; vervangt het {@value #MIGRATED}-item), {@value #CREATION_DEFAULT_OVERWRITTEN} ({@value #WARNING},
 *       vlag aan, de vorige waarde kwam uit legacy) of {@value #CREATION_DEFAULT_MANUAL_OVERWRITTEN} ({@value #WARNING},
 *       vlag aan, de vorige waarde was manueel gezet); beide met de oude en de nieuwe waarde.</li>
 * </ul>
 *
 * @param receiptId     de ontvangst
 * @param contentHash   de {@code contentHash} van die ontvangst
 * @param receiptStatus de huidige status van de ontvangst (APPLY kan enkel op {@code RECEIVED})
 * @param outcome       {@value #OUTCOME_APPLICABLE} of {@value #OUTCOME_BLOCKED}
 * @param planned       wat APPLY zou doen (of deed)
 * @param sharedWith    (S7) de andere interfaces (codes, gesorteerd) die de gedeelde definitie delen of zullen delen: de
 *                      interfaces waarvan de koppeling al op die definitie staat, plus die waarvan de laatste ontvangst
 *                      (niet {@code DISCARDED}) dezelfde leverancier en {@code definitionContentHash} heeft; telt mee in
 *                      de {@code previewFingerprint}
 * @param items         per legacy-element de uitkomst, in vaste volgorde
 * @param prodisReference (S5) of leverancier en bibliotheek tegen de alleen-lezen Prodis-database gecontroleerd zijn:
 *                      {@code VERIFIED}, {@code UNAVAILABLE} (geconfigureerd maar onbereikbaar; de fingerprint begint
 *                      dan met {@value #FINGERPRINT_UNVERIFIED_PREFIX}) of {@code NOT_CONFIGURED} (functie uit, gedrag S2)
 */
public record LegacyConfigReport(long receiptId, String contentHash, String receiptStatus, String outcome,
                                 Planned planned, List<String> sharedWith, List<Item> items, String prodisReference) {

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
    /**
     * G2a ({@code planned.link.action}): de bestaande koppeling wordt verlegd naar de eigen kopie van de gedeelde
     * definitie (beslissingslog 2026-10-22 "G2a", V1 = A); code, taak, leveringsconfiguratie en historiek blijven.
     */
    public static final String ACTION_REPOINT = "REPOINT";

    /**
     * Begin van de {@code previewFingerprint} van een rapport dat Prodis niet kon raadplegen (design par. 6:
     * fingerprint "unverified"). Een geverifieerd rapport heeft nooit dit begin, dus een APPLY met een niet-geverifieerde
     * preview faalt met {@code LEGACY_PREVIEW_STALE} zodra Prodis weer bereikbaar is, en omgekeerd.
     */
    public static final String FINGERPRINT_UNVERIFIED_PREFIX = "unverified:";

    // --- Aanmaakstandaard van de koppeling (CD-B3; zie de klassebeschrijving) -------------------------------------

    /** PSIMPDEF 51 met een vaste waarde: de btw-code wordt de aanmaakstandaard van de koppeling. */
    public static final String CREATION_DEFAULT_VAT = "CREATION_DEFAULT_VAT";
    /** PSIMPDEF 45 met een vaste waarde: de eenheid wordt de aanmaakstandaard van de koppeling. */
    public static final String CREATION_DEFAULT_UNIT = "CREATION_DEFAULT_UNIT";
    /** PSIMPDEF 51/45 leest een bronkolom in plaats van een vaste waarde: niets gezet. */
    public static final String CREATION_DEFAULT_FROM_COLUMN_NOT_MIGRATED = "CREATION_DEFAULT_FROM_COLUMN_NOT_MIGRATED";
    /** PSIMPDEF 51/45 komt meer dan eens voor: welke legacy toepast is niet bepaald, niets gezet. */
    public static final String CREATION_DEFAULT_DUPLICATED = "CREATION_DEFAULT_DUPLICATED";
    /** PSIMPDEF 51/45 zonder (niet-blanco) vaste waarde en zonder bronkolom: niets gezet. */
    public static final String CREATION_DEFAULT_VALUE_EMPTY = "CREATION_DEFAULT_VALUE_EMPTY";
    /** De vaste waarde van PSIMPDEF 51/45 is langer dan 50 tekens ({@code import_link}): niets gezet, nooit afgekapt. */
    public static final String CREATION_DEFAULT_VALUE_TOO_LONG = "CREATION_DEFAULT_VALUE_TOO_LONG";
    /** PSIMPDEF 50/59 (rekeningen) of 1 (artikelgroep) met een vaste waarde: als aanmaakstandaard nog niet (V3). */
    public static final String CREATION_DEFAULT_NOT_YET_SUPPORTED = "CREATION_DEFAULT_NOT_YET_SUPPORTED";
    /** De btw-code bestaat precies één keer onder de actieve Prodis-rijen. */
    public static final String PRODIS_VAT_CODE = "PRODIS_VAT_CODE";
    /** De eenheid bestaat precies één keer onder de actieve Prodis-rijen. */
    public static final String PRODIS_UNIT = "PRODIS_UNIT";
    /** De btw-code is geen actieve Prodis-rij: niet gezet (V2); zelfde code als de setup-API (400 daar). */
    public static final String PRODIS_VAT_CODE_NOT_FOUND = SetupService.CODE_PRODIS_VAT_CODE_NOT_FOUND;
    /** De eenheid is geen actieve Prodis-rij: niet gezet (V2); zelfde code als de setup-API (400 daar). */
    public static final String PRODIS_UNIT_NOT_FOUND = SetupService.CODE_PRODIS_UNIT_NOT_FOUND;
    /** Meer dan één actieve Prodis-rij met die code: wel gezet; zelfde code als de setup-API. */
    public static final String PRODIS_REFERENCE_AMBIGUOUS = SetupService.WARNING_PRODIS_REFERENCE_AMBIGUOUS;
    /** Bestaande koppeling, vlag "standaarden overschrijven" uit: de waarde verschilt en blijft (beide waarden). */
    public static final String CREATION_DEFAULT_DIFFERS_NOT_CHANGED = "CREATION_DEFAULT_DIFFERS_NOT_CHANGED";
    /** Bestaande koppeling, vlag aan: een waarde uit een vorige overname wordt overschreven (oud en nieuw). */
    public static final String CREATION_DEFAULT_OVERWRITTEN = "CREATION_DEFAULT_OVERWRITTEN";
    /** Bestaande koppeling, vlag aan: een manueel gezette waarde wordt overschreven (oud en nieuw, oud was manueel). */
    public static final String CREATION_DEFAULT_MANUAL_OVERWRITTEN = "CREATION_DEFAULT_MANUAL_OVERWRITTEN";

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
     * @param definition         de gedeelde definitie ({@code OWN_DEFINITION}, S7): nieuw, hergebruikt, opvolgende DRAFT
     *                           of ongewijzigd
     * @param link               koppeling (nieuw of hergebruikt; enkel bij loskoppelen verlegd, G2a)
     * @param task               MANUAL-taak (nieuw of hergebruikt, nooit gewijzigd)
     * @param credential         de overgenomen credential (S4), of {@code null} als de herkomst er geen heeft en deze
     *                           ontvangst geen secret droeg
     * @param template           (S7, additief) het {@code REUSABLE_TEMPLATE} waarop de gedeelde definitie gebaseerd is
     * @param detach             (G2a, additief) het loskoppelen van de gedeelde definitie, of {@code null} als deze
     *                           APPLY niet loskoppelt
     */
    public record Planned(PlannedOrganisation sourceOrganisation, PlannedDefinition definition, PlannedLink link,
                          PlannedTask task, PlannedCredential credential, PlannedTemplate template,
                          PlannedDetach detach) {

        /** Zonder loskoppelen (vorm van vóór G2a). */
        public Planned(PlannedOrganisation sourceOrganisation, PlannedDefinition definition, PlannedLink link,
                       PlannedTask task, PlannedCredential credential, PlannedTemplate template) {
            this(sourceOrganisation, definition, link, task, credential, template, null);
        }
    }

    /**
     * G2a ({@code docs/design/g2-design.md} par. G2a; beslissingslog 2026-10-22 "G2a: loskoppelen van een gedeelde
     * definitie", V1 = A en V2 = A): de interface krijgt een eigen kopie ({@code planned.definition}, actie
     * {@value #ACTION_CREATE}) van de gedeelde definitie; een bestaande koppeling wordt verlegd
     * ({@code planned.link.action} {@value #ACTION_REPOINT}), een nieuwe interface krijgt haar koppeling meteen op de
     * kopie.
     *
     * @param fromDefinitionId   de gedeelde definitie van de groep
     * @param fromDefinitionCode haar code
     * @param toDefinitionCode   de code van de kopie ({@code LEGACY-<interface>} of {@code detachedDefinitionCode})
     * @param copiedRevisionId   de ACTIVE revisie van de gedeelde definitie die als revisie 1 gekopieerd en geactiveerd
     *                           wordt, of {@code null} als de gedeelde definitie geen ACTIVE revisie heeft
     * @param carriesActive      {@code true}: revisie 1 is die ACTIVE kopie en de nieuwe legacy-inhoud wordt de
     *                           opvolgende DRAFT (revisie 2); {@code false}: revisie 1 is een DRAFT met de nieuwe inhoud
     */
    public record PlannedDetach(Long fromDefinitionId, String fromDefinitionCode, String toDefinitionCode,
                                Long copiedRevisionId, boolean carriesActive) {
    }

    /**
     * Het sjabloon van de groep (S7, beslissingslog 2026-10-21 "Legacy-config S7: gedeelde definities"): altijd
     * {@code DRAFT}, door de mens te activeren; APPLY maakt het enkel aan ({@value #ACTION_CREATE}) en wijzigt het nooit
     * ({@value #ACTION_REUSE}). {@value #ACTION_NONE}: de gedeelde definitie heeft geen sjabloon (gemigreerd vóór S7).
     *
     * @param code       {@code <legacy-code>-SJ}
     * @param revisionId na APPLY: de (DRAFT-)sjabloonrevisie die APPLY aanmaakte, anders {@code null}
     */
    public record PlannedTemplate(String action, Long id, String code, Long revisionId) {
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

    /**
     * @param libraryCode       de bibliotheekcode ({@code supplier_catalog.scope_key}), of {@code null} als ze niet bepaald is
     * @param libraryCandidates (S5) de Prodis-bibliotheken waaruit de mens moet kiezen als {@code ArtImp_BibNr} geen of
     *                          meer dan één match heeft, of als een gegeven {@code libraryCode} niet bij de leverancier
     *                          hoort; anders leeg
     */
    public record PlannedLink(String action, Long id, String code, String libraryCode,
                              List<LibraryCandidate> libraryCandidates) {

        public PlannedLink {
            libraryCandidates = libraryCandidates == null ? List.of() : List.copyOf(libraryCandidates);
        }
    }

    /**
     * Een bibliotheek van de leverancier in Prodis (één per {@code scope_key}; de {@code ACTIVE}-rij als die er is).
     *
     * @param scopeKey     {@code supplier_catalog.scope_key} = de mogelijke {@code libraryCode}
     * @param catalogName  {@code supplier_catalog.catalog_name}
     * @param legacyNumber {@code supplier_catalog.legacy_number}
     * @param status       {@code supplier_catalog.status}
     */
    public record LibraryCandidate(String scopeKey, String catalogName, String legacyNumber, String status) {
    }

    public record PlannedTask(String action, Long id, String name) {
    }
}
