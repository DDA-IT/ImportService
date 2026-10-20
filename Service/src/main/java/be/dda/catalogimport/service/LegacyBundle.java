package be.dda.catalogimport.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;

/**
 * Een volledig gevalideerde legacy-config-bundel v1 ({@code docs/design/legacy-config-bundle-v1.md}), met beide
 * hashes door CatalogImport herberekend en gelijk bevonden. Enkel {@link LegacyBundleParser} maakt dit aan.
 *
 * @param withoutSecret de ontvangen boom met {@code "secret": null} — wat als {@code bundle_json} bewaard mag worden
 * @param secret        het meegestuurde secret of {@code null}; {@code toString} zonder waarde
 */
public record LegacyBundle(int version, String environment, String dossier, String interfaceCode,
                           OffsetDateTime exportedAt, String contentHash, String definitionContentHash,
                           String definitionStatus, String definitionCode, ObjectNode withoutSecret,
                           LegacySecret secret) {

    /** Zonder de boom: enkel herkomst, hash en of er een secret is. */
    @Override
    public String toString() {
        return "LegacyBundle[origin=" + environment + "/" + dossier + "/" + interfaceCode + ", contentHash="
                + contentHash + ", definitionStatus=" + definitionStatus + ", secret="
                + (secret == null ? "absent" : "[REDACTED]") + "]";
    }
}
