package be.dda.catalogimport.service.support;

import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Hoe een bronbestand van een revisie fysiek gelezen wordt (formaat, tekenset, scheidingsteken, aanhalingsteken,
 * header), los van de veldbetekenis. Gebouwd door {@link SourceStructureConfigFactory#layoutOf} met <b>dezelfde</b>
 * regels als {@link SourceStructureConfigFactory#from}, maar zonder dat een fout in de veldconfiguratie (bv.
 * {@code CONFIG_PRICE_FIELD_MISSING}) het lezen van de kolommen tegenhoudt (EB5/G3, kolomvoorbeeld van de
 * proefinlezing).
 * <p>
 * Een onderdeel dat de regels niet doorstaat, is {@code null}; {@link #invalidCodes()} noemt dan de
 * {@code CONFIG_*}-codes in fabrieksvolgorde. Er wordt nooit geraden of teruggevallen op een standaard.
 *
 * @param charset          de tekenset, of {@code null} wanneer ze ontbreekt of onbekend is
 * @param delimiter        het kolomscheidingsteken, of {@code null} wanneer het ontbreekt of ongeldig is
 * @param quote            het aanhalingsteken, of {@code null} bij geen quoting <b>of</b> een ongeldig aanhalingsteken
 *                         (zie {@link #invalidCodes()})
 * @param hasHeader        heeft het bestand een headerregel
 * @param headerLineNumber het geconfigureerde (1-gebaseerde) regelnummer van de header
 * @param referenceKind    hoe velden naar kolommen verwijzen, of {@code null} wanneer de waarde onbekend is; speelt
 *                         voor het fysiek lezen geen rol en maakt de indeling dus niet onbruikbaar
 * @param invalidCodes     de codes waardoor het bestand met deze indeling niet te lezen is; leeg = bruikbaar
 */
public record SourceLayout(Charset charset, Character delimiter, Character quote, boolean hasHeader,
                           int headerLineNumber, FieldReferenceKind referenceKind, List<String> invalidCodes) {

    public SourceLayout {
        invalidCodes = List.copyOf(invalidCodes);
    }

    /** {@code true} wanneer het bestand met deze indeling in regels en kolommen te splitsen is. */
    public boolean usable() {
        return invalidCodes.isEmpty();
    }

    /** Het aanhalingsteken als {@code char}; {@code ' '} betekent "geen quoting", zoals {@link SourceStructureConfig#quoteChar()}. */
    public char quoteChar() {
        return quote == null ? ' ' : quote;
    }
}
