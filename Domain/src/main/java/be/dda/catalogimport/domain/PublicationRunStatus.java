package be.dda.catalogimport.domain;

/**
 * Levenscyclus van één {@link PublicationRun} (docs/design/fase5-pub-design.md par. 3). 5-PUB-a zet
 * {@link #REQUESTED}, {@link #PREPARING}, {@link #SIMULATED} en {@link #FAILED}; de PRODUCTION-run (P5c,
 * docs/design/publicatie-prodis-db-direct-design.md par. 2.4) zet daarnaast {@link #PUBLISHING} en {@link #APPLIED}. De
 * overige zes zijn gedeclareerd en worden nooit gezet.
 */
public enum PublicationRunStatus {

    /** Gebruikt: run aangevraagd, nog niet gestart. */
    REQUESTED,

    /** Gebruikt: het artefact wordt opgebouwd. */
    PREPARING,

    /**
     * Toegevoegd in P4a (changeset 027-2), gezet sinds P5c: de PRODUCTION-run verstuurt zijn items in chunks.
     * Niet-terminaal: de run houdt de actieve marker vast.
     */
    PUBLISHING,

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

    /**
     * Gezet sinds P5c, terminaal: de PRODUCTION-run is afgerond, elk item heeft een definitief antwoord of werd niet
     * verstuurd. Zegt niet dat elk item toegepast is: de bundel toont {@code PUBLISHED} of {@code PARTIALLY_PUBLISHED},
     * elk item zijn eigen uitkomst. Enkel een PRODUCTION-run bereikt deze status (changeset 028-1).
     */
    APPLIED,

    /** Gedeclareerd, nooit gezet (5-PUB-b/c): door ProDis geweigerd. */
    REJECTED_BY_PRODIS,

    /** Gedeclareerd, nooit gezet (5-PUB-b/c): herstel vereist. */
    RECOVERY_REQUIRED;

    /**
     * @return {@code true} bij {@link #SIMULATED}, {@link #FAILED} en (sinds P5c, gemelde gedragswijziging)
     *     {@link #APPLIED}: de run is afgerond en houdt de actieve marker niet meer vast. De nooit-gezette statussen
     *     gelden voorlopig als niet-terminaal. In de database is {@code APPLIED} enkel terminaal voor een
     *     PRODUCTION-run (changeset 028-1), de enige die het kan bereiken.
     */
    public boolean isTerminal() {
        return this == SIMULATED || this == FAILED || this == APPLIED;
    }

    /** @return {@code true} voor de vier statussen die 5-PUB-a effectief zet. */
    public boolean isUsedInSimulation() {
        return this == REQUESTED || this == PREPARING || this == SIMULATED || this == FAILED;
    }
}
