package be.dda.catalogimport.domain;

/**
 * Levenscyclus van één {@link PublicationBundle} (ontwerp fase 4, docs/design/fase4-publication-bundle-
 * design.md par. 4). Fase 4 zet enkel {@link #ASSEMBLING}, {@link #FROZEN} en {@link #CANCELLED}; de
 * overige vier zijn door Fase 5 gedeclareerd en worden door Fase 4 niet gebruikt.
 */
public enum PublicationBundleStatus {

    /** Bundel wordt samengesteld: batches toevoegen/verwijderen, mutaties beoordelen. */
    ASSEMBLING,

    /** Bevroren: geen nieuwe leden of beslissingen meer, klaar voor publicatie. */
    FROZEN,

    /** Geannuleerd; batches komen weer vrij, niet-terminale mutaties worden EXPIRED. Terminaal. */
    CANCELLED,

    /** Fase 5 (niet gebruikt door Fase 4): publicatie loopt. */
    PUBLISHING,

    /** Fase 5 (niet gebruikt door Fase 4): een deel is gepubliceerd, een deel niet. */
    PARTIALLY_PUBLISHED,

    /** Fase 5 (niet gebruikt door Fase 4): volledig gepubliceerd. Terminaal. */
    PUBLISHED,

    /** Fase 5 (niet gebruikt door Fase 4): publicatie technisch mislukt. */
    PUBLICATION_FAILED;

    /**
     * @return {@code false} enkel bij {@link #CANCELLED} — een gepubliceerde bundel geeft haar
     *     batches NOOIT vrij, ook niet nadat de publicatie is afgerond.
     */
    public boolean holdsBatches() {
        return this != CANCELLED;
    }

    /** @return {@code true} enkel bij {@link #ASSEMBLING}: enige status die nog wijzigingen aanvaardt. */
    public boolean acceptsChanges() {
        return this == ASSEMBLING;
    }
}
