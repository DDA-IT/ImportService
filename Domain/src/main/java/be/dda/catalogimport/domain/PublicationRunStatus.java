package be.dda.catalogimport.domain;

/**
 * Levenscyclus van één {@link PublicationRun} (docs/design/fase5-pub-design.md par. 3). 5-PUB-a zet enkel
 * {@link #REQUESTED}, {@link #PREPARING}, {@link #SIMULATED} en {@link #FAILED}; de overige zeven zijn
 * gedeclareerd voor 5-PUB-b/c en worden nooit gezet.
 */
public enum PublicationRunStatus {

    /** Gebruikt: run aangevraagd, nog niet gestart. */
    REQUESTED,

    /** Gebruikt: het artefact wordt opgebouwd. */
    PREPARING,

    /**
     * Gebruikt, terminaal (geslaagd): artefact geschreven, GEEN enkel operationeel effect. Nooit te
     * verwarren met {@link #APPLIED}.
     */
    SIMULATED,

    /** Gebruikt, terminaal: de run is technisch mislukt (met failure_code). */
    FAILED,

    /** Gedeclareerd, nooit gezet (5-PUB-b/c): wacht op het doelcontract. */
    WAITING_FOR_TARGET_CONTRACT,

    /** Gedeclareerd, nooit gezet (5-PUB-b/c): klaar om aan te leveren. */
    READY_FOR_DELIVERY,

    /** Gedeclareerd, nooit gezet (5-PUB-b/c): naar PSIMPORT geschreven. */
    WRITTEN_TO_PSIMPORT,

    /** Gedeclareerd, nooit gezet (5-PUB-b/c): uitkomst onbekend. */
    RESULT_UNKNOWN,

    /** Gedeclareerd, nooit gezet (5-PUB-b/c): echt toegepast in het doelsysteem. */
    APPLIED,

    /** Gedeclareerd, nooit gezet (5-PUB-b/c): door ProDis geweigerd. */
    REJECTED_BY_PRODIS,

    /** Gedeclareerd, nooit gezet (5-PUB-b/c): herstel vereist. */
    RECOVERY_REQUIRED;

    /**
     * @return {@code true} bij {@link #SIMULATED} en {@link #FAILED}: de run is afgerond en houdt de
     *     actieve marker niet meer vast. De nooit-gezette statussen gelden voorlopig als niet-terminaal.
     */
    public boolean isTerminal() {
        return this == SIMULATED || this == FAILED;
    }

    /** @return {@code true} voor de vier statussen die 5-PUB-a effectief zet. */
    public boolean isUsedInSimulation() {
        return this == REQUESTED || this == PREPARING || this == SIMULATED || this == FAILED;
    }
}
