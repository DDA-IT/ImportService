package be.dda.catalogimport.service.support;

import java.util.List;

/**
 * Het resultaat van een volledige configuratiecontrole (NT-14a par. 1 en 2).
 * <p>
 * <b>Invariant.</b> {@code findings().get(0)} is exact de fout die dezelfde fabriek in de gewone
 * stand ({@code from(...)}) als eerste gooit; geen worp betekent een lege lijst.
 * <p>
 * "Alle fouten" betekent alle <b>onafhankelijk</b> te beoordelen fouten. Een controle die steunt op
 * een waarde die zelf fout is, wordt niet beoordeeld; de code van die grondoorzaak staat dan in
 * {@link #skippedBecause()} (elke code één keer, in volgorde van eerste voorkomen). Na herstel kunnen
 * er dus nog fouten bijkomen — dat wordt nooit stil weggelaten.
 *
 * @param findings       de fouten, in de volgorde waarin de fabrieken ze tegenkomen
 * @param skippedBecause de codes van de grondoorzaken waardoor een afhankelijke controle niet
 *                       beoordeeld kon worden
 */
public record ConfigCheckReport(List<ConfigFinding> findings, List<String> skippedBecause) {

    public ConfigCheckReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        skippedBecause = skippedBecause == null ? List.of() : List.copyOf(skippedBecause);
    }

    /** {@code true} wanneer er minstens één configuratiefout is. */
    public boolean hasFindings() {
        return !findings.isEmpty();
    }
}
