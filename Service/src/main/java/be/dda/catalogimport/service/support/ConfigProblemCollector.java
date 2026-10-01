package be.dda.catalogimport.service.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Groepeert de bestaande werpplaatsen van {@link SourceStructureConfigFactory} en
 * {@link ImportMappingConfigFactory} in <b>takken</b> (NT-14a par. 1, optie a').
 * <ul>
 *   <li>{@link Mode#FIRST}: een tak die een {@link ScreeningBlockedException} gooit, gooit
 *       <b>hetzelfde object</b> opnieuw. De fabriek gedraagt zich dus exact zoals zonder collector:
 *       zelfde eerste code, tekst en volgorde. Dit is de stand van screening, upload, ophaalrun,
 *       activatie, setup en materialisatie.</li>
 *   <li>{@link Mode#ALL}: de fout wordt een {@link ConfigFinding} en de fabriek gaat verder met de
 *       volgende tak. Een tak die steunt op een tak die faalde, wordt niet uitgevoerd; de fabriek meldt
 *       dat met {@link #skip(String)}.</li>
 * </ul>
 * Enkel {@link ScreeningBlockedException} wordt verzameld; elke andere exception gaat in beide standen
 * ongewijzigd door. Niet thread-safe: één collector per controle.
 */
public final class ConfigProblemCollector {

    /** De stand van de collector. */
    public enum Mode {
        /** Eerste fout gooien, zoals vóór NT-14. */
        FIRST,
        /** Alle onafhankelijk te beoordelen fouten verzamelen. */
        ALL
    }

    /**
     * De uitkomst van één tak.
     *
     * @param value      de waarde van de tak, of {@code null} wanneer {@code failed}
     * @param failed     de tak faalde of kon niet beoordeeld worden
     * @param failedCode de code van de fout of van de grondoorzaak, of {@code null}
     */
    public record Outcome<T>(T value, boolean failed, String failedCode) {

        /** Een geslaagde tak. */
        public static <T> Outcome<T> passed(T value) {
            return new Outcome<>(value, false, null);
        }

        /** Een tak zonder bruikbare waarde, met de code van de grondoorzaak. */
        public static <T> Outcome<T> unavailable(String failedCode) {
            return new Outcome<>(null, true, failedCode);
        }
    }

    private final Mode mode;
    private final List<ConfigFinding> findings = new ArrayList<>();
    private final Set<String> skippedBecause = new LinkedHashSet<>();

    private ConfigProblemCollector(Mode mode) {
        this.mode = mode;
    }

    /** Gooit de eerste fout, zoals vóór NT-14. */
    public static ConfigProblemCollector first() {
        return new ConfigProblemCollector(Mode.FIRST);
    }

    /** Verzamelt alle onafhankelijk te beoordelen fouten. */
    public static ConfigProblemCollector all() {
        return new ConfigProblemCollector(Mode.ALL);
    }

    public Mode mode() {
        return mode;
    }

    /** {@code true} in {@link Mode#ALL}. */
    public boolean collectsAll() {
        return mode == Mode.ALL;
    }

    /**
     * Voert één tak met een waarde uit.
     *
     * @param revisionField de revisiekolom waar een fout van deze tak op slaat, of {@code null}
     * @return de waarde, of in {@link Mode#ALL} een mislukte uitkomst met de code van de fout
     * @throws ScreeningBlockedException in {@link Mode#FIRST}: exact het object dat de tak gooide
     */
    public <T> Outcome<T> branch(String revisionField, Supplier<T> block) {
        try {
            return Outcome.passed(block.get());
        } catch (ScreeningBlockedException blocked) {
            report(blocked, revisionField);
            return Outcome.unavailable(blocked.getCode());
        }
    }

    /**
     * Voert één tak zonder waarde uit (een controle).
     *
     * @see #branch(String, Supplier)
     */
    public Outcome<Void> check(String revisionField, Runnable block) {
        return branch(revisionField, () -> {
            block.run();
            return null;
        });
    }

    /**
     * Meldt één fout.
     *
     * @return altijd {@code null} in {@link Mode#ALL}
     * @throws ScreeningBlockedException in {@link Mode#FIRST}: hetzelfde object
     */
    public <T> T report(ScreeningBlockedException blocked, String revisionField) {
        if (mode == Mode.FIRST) {
            throw blocked;
        }
        findings.add(ConfigFinding.of(blocked, revisionField));
        return null;
    }

    /**
     * Een afhankelijke controle wordt niet beoordeeld omdat de controle waarop ze steunt faalde.
     *
     * @param dependsOnCode de code van die grondoorzaak; elke code komt één keer in het resultaat
     * @return een uitkomst zonder waarde, met dezelfde grondoorzaak
     */
    public <T> Outcome<T> skip(String dependsOnCode) {
        skippedBecause.add(dependsOnCode);
        return Outcome.unavailable(dependsOnCode);
    }

    /** Het aantal tot nu toe verzamelde fouten (in {@link Mode#FIRST} altijd 0). */
    public int findingCount() {
        return findings.size();
    }

    /** Het resultaat tot nu toe. */
    public ConfigCheckReport toReport() {
        return new ConfigCheckReport(findings, new ArrayList<>(skippedBecause));
    }
}
