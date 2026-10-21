package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.ImportMappingConfig.RecordFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Past de recordfilters van een revisie toe op één geparste bronregel (ontwerp fase 3,
 * R-FLT-01..R-FLT-03). Pure klasse: geen Spring, geen database, geen tijd- of omgevingsafhankelijkheid
 * — dezelfde regel levert altijd dezelfde beslissing op.
 *
 * <h2>Wanneer</h2>
 * Onmiddellijk na het parsen en vóór identiteit, prijs en referenties (R-FLT-02). Een uitgesloten
 * record krijgt géén enkele verdere controle: dat is niet alleen sneller, het voorkomt ook dat een
 * record dat bewust buiten de importscope valt een identiteits- of prijsincident veroorzaakt.
 *
 * <h2>Combinatiesemantiek van meerdere filterrijen</h2>
 * De rijen worden geëvalueerd in oplopende {@code sequence_number}. Het bronbestand zelf wordt altijd
 * volledig gelezen; het filter beslist enkel over de scope.
 * <ol>
 *   <li>Ontbreekt de <b>kolom</b> van een rij in dit bestand, dan geldt haar
 *       {@code missing_column_behaviour}: {@code BLOCK} (standaard) blokkeert de volledige levering
 *       met {@code FILTER_COLUMN_MISSING}, {@code EXCLUDE} sluit de regel uit, {@code REJECT}
 *       verwerpt ze met een probleem. Er bestaat geen stil "het filter matcht dan maar niet"
 *       (R-FLT-03).</li>
 *   <li>Is de <b>waarde</b> afwezig of leeg, dan geldt haar {@code null_behaviour}: {@code EXCLUDE}
 *       (standaard), {@code REJECT}, of {@code COMPARE_AS_EMPTY} om {@code ""} gewoon te
 *       vergelijken.</li>
 *   <li>Anders wordt vergeleken met de operator, met de per rij ingestelde hoofdlettergevoeligheid en
 *       trimming.</li>
 *   <li>De <b>eerste</b> rij die een eindbeslissing oplevert — een matchende {@code EXCLUDE} of
 *       {@code REJECT}, of een {@code EXCLUDE}/{@code REJECT} uit stap 1 of 2 — beëindigt de
 *       evaluatie met die beslissing. Zo is de uitkomst altijd te herleiden tot één filterrij.</li>
 *   <li>Zijn er één of meer {@code INCLUDE}-rijen en matcht er geen enkele, dan valt de regel buiten
 *       de scope. Zijn er geen {@code INCLUDE}-rijen, dan is alles in scope wat niet uitgesloten of
 *       verworpen is.</li>
 * </ol>
 * Kort: <em>in scope</em> = (geen {@code INCLUDE}-rijen óf minstens één {@code INCLUDE} matcht) én
 * geen {@code EXCLUDE} matcht; een matchende {@code REJECT} verwerpt de regel mét een probleem.
 * Meerdere {@code INCLUDE}-rijen staan dus in OF-verhouding (bv. {@code culture=BENL} of
 * {@code culture=BEFR}) en {@code EXCLUDE}-rijen in EN-verhouding — dat is de enige combinatie
 * waarmee zowel "importeer deze twee deelcatalogi" als "maar nooit de EOL-artikelen" uitdrukbaar is.
 * De businessanalyse legt deze combinatie niet vast (par. 5.5.1 beschrijft enkel de zes operatoren
 * per rij); dit is de gedocumenteerde invulling.
 *
 * <h2>EN-groepen (legacy-config S8f, {@code match_group})</h2>
 * Rijen met dezelfde {@code match_group} vormen samen één EN-regel: het record valt buiten de scope als <b>alle</b>
 * rijen van de groep matchen (PSIMPEXCL, beslissingslog 2026-10-21 V2 = A). Zo'n rij is altijd {@code EXCLUDE} met
 * {@code COMPARE_AS_EMPTY} (changeset 025-1, configuratievalidatie {@code CONFIG_FILTER_INVALID}). De groep wordt als
 * één eenheid geëvalueerd op de plaats van haar rij met het laagste {@code sequence_number}; haar andere rijen worden op
 * hun eigen plaats overgeslagen. Eerst wordt van elke rij van de groep de kolom gezocht: een ontbrekende kolom geeft,
 * net als bij een losse rij, het {@code missing_column_behaviour} van die rij (R-FLT-03), ook als een andere rij van
 * de groep niet zou matchen - een ontbrekende kolom blijft dus nooit stil. Daarna worden de waarden vergeleken; de
 * eerste rij die niet matcht, beëindigt de groep zonder beslissing. Een matchende groep geeft
 * {@link Decision.Kind#FILTERED_OUT} met het laagste volgnummer van de groep als beslissende rij. Rijen zonder groep
 * gedragen zich exact zoals vóór S8f.
 *
 * <h2>Wat een uitgesloten record niet is</h2>
 * Uitsluiten is geen fout: {@link Decision.Kind#FILTERED_OUT} telt in {@code filtered_out_count} en
 * levert géén probleemrij op. Verwerpen is dat wél: {@link Decision.Kind#REJECTED} telt in
 * {@code rejected_record_count} en is zichtbaar in de probleemlijst.
 */
public final class RecordFilterEvaluator {

    /**
     * De kolom waarop de importscope gedefinieerd is, staat niet in dit bronbestand. Standaard
     * blokkeert dat de volledige levering: zonder die kolom is niet vast te stellen welke records
     * tot deze import horen (R-FLT-03).
     */
    public static final String CODE_FILTER_COLUMN_MISSING = "FILTER_COLUMN_MISSING";

    /** Een filterrij met uitkomst {@code REJECT} heeft deze regel verworpen. */
    public static final String CODE_FILTER_RECORD_REJECTED = "FILTER_RECORD_REJECTED";

    /** De beslissing over één bronregel. */
    public record Decision(Kind kind, Integer decidingSequenceNumber, String fieldName, String sourceValue,
                           String message) {

        public enum Kind {
            /** De regel hoort tot de importscope en gaat door naar de verdere controles. */
            IN_SCOPE,
            /** De regel valt buiten de importscope; geen fout, wel geteld. */
            FILTERED_OUT,
            /** De regel is verworpen met een probleem. */
            REJECTED
        }

        public boolean inScope() {
            return kind == Kind.IN_SCOPE;
        }
    }

    private static final Decision IN_SCOPE =
            new Decision(Decision.Kind.IN_SCOPE, null, null, null, null);

    private final List<RecordFilter> filters;
    private final boolean hasIncludeFilter;
    /**
     * S8f: per filterrij die een groep opent (de rij met het laagste volgnummer van haar groep) alle rijen van die
     * groep, oplopend op volgnummer. Andere gegroepeerde rijen staan in {@link #grouped} en worden overgeslagen.
     */
    private final Map<RecordFilter, List<RecordFilter>> groupsByLead;
    private final Map<RecordFilter, Boolean> grouped; // identiteitsset: twee gelijke rijen blijven twee rijen

    public RecordFilterEvaluator(List<RecordFilter> filters) {
        this.filters = List.copyOf(filters);
        this.hasIncludeFilter = this.filters.stream()
                .anyMatch(filter -> filter.outcome() == FilterOutcome.INCLUDE);
        Map<Integer, List<RecordFilter>> byGroup = new LinkedHashMap<>();
        for (RecordFilter filter : this.filters) {
            if (filter.matchGroup() != null) {
                byGroup.computeIfAbsent(filter.matchGroup(), group -> new ArrayList<>()).add(filter);
            }
        }
        Map<RecordFilter, List<RecordFilter>> leads = new IdentityHashMap<>();
        Map<RecordFilter, Boolean> members = new IdentityHashMap<>();
        for (List<RecordFilter> rows : byGroup.values()) {
            List<RecordFilter> ordered = new ArrayList<>(rows);
            ordered.sort(Comparator.comparingInt(RecordFilter::sequenceNumber)); // stabiel bij gelijke nummers
            leads.put(ordered.get(0), List.copyOf(ordered));
            rows.forEach(row -> members.put(row, Boolean.TRUE));
        }
        this.groupsByLead = leads;
        this.grouped = members;
    }

    /** @return {@code true} wanneer er geen enkele filterrij is; het fase 2-gedrag blijft dan gelden */
    public boolean isEmpty() {
        return filters.isEmpty();
    }

    /**
     * Beoordeelt één geparste regel.
     *
     * @throws ScreeningBlockedException wanneer een filterkolom ontbreekt en de rij op {@code BLOCK}
     *                                   staat; dat blokkeert de volledige levering
     */
    public Decision evaluate(ParsedRow row) {
        if (filters.isEmpty()) {
            return IN_SCOPE;
        }
        boolean includeMatched = false;
        for (RecordFilter filter : filters) {
            if (grouped.containsKey(filter)) {
                List<RecordFilter> group = groupsByLead.get(filter);
                if (group == null) {
                    continue; // geëvalueerd op de plaats van de laagste rij van haar groep
                }
                Decision decision = evaluateGroup(row, group);
                if (decision != null) {
                    return decision;
                }
                continue;
            }
            Integer position = row.positions().position(filter.sourceReference());
            if (position == null) {
                return onMissingColumn(filter);
            }
            String raw = row.value(position);
            String candidate = filter.trimBeforeCompare() ? trim(raw) : raw;
            if (candidate == null || candidate.isEmpty()) {
                Decision empty = onEmptyValue(filter, raw);
                if (empty != null) {
                    return empty;
                }
                candidate = "";
            }
            if (!matches(filter, candidate)) {
                continue;
            }
            switch (filter.outcome()) {
                case INCLUDE -> includeMatched = true;
                case EXCLUDE -> {
                    return excluded(filter, raw, "matches the exclude rule "
                            + describe(filter));
                }
                case REJECT -> {
                    return rejected(filter, raw, "matches the reject rule " + describe(filter));
                }
            }
        }
        if (hasIncludeFilter && !includeMatched) {
            return new Decision(Decision.Kind.FILTERED_OUT, null, null, null,
                    "No include filter of this import definition matches this record");
        }
        return IN_SCOPE;
    }

    /**
     * S8f: één EN-groep. Eerst de kolommen (een ontbrekende kolom beslist altijd, R-FLT-03), dan de waarden; de eerste
     * rij die niet matcht beëindigt de groep zonder beslissing.
     *
     * @return de beslissing, of {@code null} als de groep niet (volledig) matcht
     */
    private static Decision evaluateGroup(ParsedRow row, List<RecordFilter> group) {
        List<Integer> positions = new ArrayList<>(group.size());
        for (RecordFilter filter : group) {
            Integer position = row.positions().position(filter.sourceReference());
            if (position == null) {
                return onMissingColumn(filter);
            }
            positions.add(position);
        }
        String leadRaw = null;
        for (int i = 0; i < group.size(); i++) {
            RecordFilter filter = group.get(i);
            String raw = row.value(positions.get(i));
            if (i == 0) {
                leadRaw = raw;
            }
            String candidate = filter.trimBeforeCompare() ? trim(raw) : raw;
            if (candidate == null || candidate.isEmpty()) {
                Decision empty = onEmptyValue(filter, raw);
                if (empty != null) {
                    return empty;
                }
                candidate = "";
            }
            if (!matches(filter, candidate)) {
                return null;
            }
        }
        RecordFilter lead = group.get(0);
        StringBuilder rules = new StringBuilder();
        for (RecordFilter filter : group) {
            rules.append(rules.isEmpty() ? "" : " and ").append(describe(filter));
        }
        return new Decision(Decision.Kind.FILTERED_OUT, lead.sequenceNumber(), lead.sourceReference(), leadRaw,
                "Record matches every rule of exclude group " + lead.matchGroup() + ": " + rules);
    }

    /** Levert altijd een eindbeslissing op of blokkeert; nooit een stil "matcht niet" (R-FLT-03). */
    private static Decision onMissingColumn(RecordFilter filter) {
        return switch (filter.missingColumnBehaviour()) {
            case BLOCK -> throw new ScreeningBlockedException(CODE_FILTER_COLUMN_MISSING,
                    filter.sourceReference(), null, filter.sourceReference(),
                    "Record filter " + filter.sequenceNumber() + " compares column '"
                            + filter.sourceReference() + "', which is not present in this source; the import "
                            + "scope of this delivery cannot be determined");
            case EXCLUDE -> excluded(filter, null, "column '" + filter.sourceReference()
                    + "' is missing from this source");
            case REJECT -> rejected(filter, null, "column '" + filter.sourceReference()
                    + "' is missing from this source");
        };
    }

    /** @return {@code null} wanneer de lege waarde gewoon vergeleken moet worden */
    private static Decision onEmptyValue(RecordFilter filter, String raw) {
        return switch (filter.nullBehaviour()) {
            case EXCLUDE -> excluded(filter, raw, "has no value for '" + filter.sourceReference() + "'");
            case REJECT -> rejected(filter, raw, "has no value for '" + filter.sourceReference() + "'");
            case COMPARE_AS_EMPTY -> null;
        };
    }

    private static boolean matches(RecordFilter filter, String candidate) {
        String value = filter.caseSensitive() ? candidate : lower(candidate);
        String compare = filter.compareValue() == null ? "" : filter.compareValue();
        compare = filter.trimBeforeCompare() ? compare.trim() : compare;
        compare = filter.caseSensitive() ? compare : lower(compare);
        return switch (filter.operator()) {
            case EQUALS -> value.equals(compare);
            case NOT_EQUALS -> !value.equals(compare);
            case BEGINS_WITH -> value.startsWith(compare);
            case ENDS_WITH -> value.endsWith(compare);
            case CONTAINS -> value.contains(compare);
            case NOT_CONTAINS -> !value.contains(compare);
        };
    }

    private static Decision excluded(RecordFilter filter, String raw, String reason) {
        return new Decision(Decision.Kind.FILTERED_OUT, filter.sequenceNumber(), filter.sourceReference(),
                raw, "Record " + reason);
    }

    private static Decision rejected(RecordFilter filter, String raw, String reason) {
        return new Decision(Decision.Kind.REJECTED, filter.sequenceNumber(), filter.sourceReference(), raw,
                filter.sourceReference() + ": '" + (raw == null ? "" : raw) + "' " + reason);
    }

    private static String describe(RecordFilter filter) {
        return filter.sequenceNumber() + " (" + filter.sourceReference() + " " + filter.operator() + " '"
                + filter.compareValue() + "')";
    }

    /** Canonicalisatie blijft buitenste trim: geen case-folding, geen Unicode-normalisatie (R-REC-09). */
    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
