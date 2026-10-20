package be.dda.catalogimport.domain;

/**
 * Status van een {@link LegacyConfigReceipt} (changeset 020-1; {@code docs/design/legacy-config-bundle-design.md}
 * par. 2 en 4).
 * <ul>
 *   <li>{@link #RECEIVED} — open: de laatste ontvangen inhoud van deze herkomst, nog niet toegepast. Hoogstens een per
 *       herkomst ({@code uk_legacy_config_receipt_open}).</li>
 *   <li>{@link #APPLIED} — toegepast (APPLY, vanaf S2).</li>
 *   <li>{@link #SUPERSEDED} — vervangen door een nieuwere ontvangst van dezelfde herkomst voor ze toegepast werd.</li>
 *   <li>{@link #DISCARDED} — door een mens verworpen (latere slice).</li>
 * </ul>
 */
public enum LegacyConfigReceiptStatus {
    RECEIVED,
    APPLIED,
    SUPERSEDED,
    DISCARDED
}
