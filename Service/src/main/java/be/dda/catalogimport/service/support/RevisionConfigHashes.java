package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.ImportDefinitionRevision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * De configuratiehash van één laag van een {@code ImportDefinitionRevision} (businessanalyse §14.14).
 * <p>
 * <b>Verhuisd uit {@code SetupService}</b> (sjabloon-materialisatie-design.md §2, §3; bouwstap 5a), met
 * <b>exact hetzelfde gedrag</b>: dezelfde canonieke opbouw (laagnaam, gevolgd door elk onderdeel,
 * telkens voorafgegaan door {@code U+001F} — unit separator) en dezelfde SHA-256/hex-omzetting. De
 * verhuizing bestaat omdat de materialisatiewizard een DEFINITION-scope bookmarkwaarde in een
 * revisieveld kan schrijven (bv. {@code REVISION_IDENTITY_FIELD}), waarna de vier hashes van de
 * afgeleide revisie herberekend moeten worden — dezelfde berekening die {@code SetupService} al bij het
 * aanmaken van een revisie gebruikt, nu op één plek gedeeld in plaats van gedupliceerd.
 * <p>
 * Geen enkele bestaande publieke methode of hashwaarde verandert door deze verhuizing: de tests in
 * {@code RevisionConfigHashesTest} bewijzen dit met een byte-vergelijking tegen de oude, nu verwijderde
 * private implementatie in {@code SetupService}.
 */
public final class RevisionConfigHashes {

    private RevisionConfigHashes() {
        // Enkel statische helpers.
    }

    /**
     * De configuratiehash van één laag. De revisie draagt drie laaghashes plus een samengestelde hash
     * (§14.14): ze maken zichtbaar dát een configuratie verschilt. Een verzonnen constante zou dat
     * onderscheid stilzwijgend wegnemen.
     */
    public static String hash(String layer, String... parts) {
        StringBuilder canonical = new StringBuilder(layer);
        for (String part : parts) {
            canonical.append('').append(part);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required but not available", impossible);
        }
    }

    /**
     * Zet de <b>vier</b> hashes van {@code revision} op basis van haar huidige veldwaarden: de drie
     * laaghashes (toegang, structuur, recordregels) en de samengestelde hash daarover (§14.14).
     * <p>
     * <b>Toegevoegd in bouwstap 5c</b>, met exact dezelfde canonieke opbouw en dezelfde volgorde van
     * onderdelen als de berekening die {@code SetupService.createRevision} sinds Fase 1 doet — die
     * methode roept deze nu aan in plaats van de vier regels zelf te herhalen. De reden is niet
     * netheid maar juistheid: de materialisatiewizard moet dezelfde vier hashes op een <i>afgeleide</i>
     * revisie zetten nadat een DEFINITION-scope bookmarkwaarde een revisieveld ingevuld heeft
     * (sjabloon-materialisatie-design.md §2). Twee implementaties naast elkaar zouden op termijn uit
     * elkaar kunnen lopen, en dan dragen twee inhoudelijk verschillende revisies dezelfde
     * configuratiehash — precies wat §14.14 onmogelijk moet maken.
     * <p>
     * De hash beschrijft dus altijd de revisie zoals ze op dát moment is: roep deze methode aan
     * <b>nadat</b> alle configuratievelden gezet zijn. Een revisie die inhoudelijk gelijk is aan haar
     * bron krijgt terecht dezelfde hashes — de hash zegt "deze configuratie", niet "deze rij".
     */
    public static void applyAll(ImportDefinitionRevision revision) {
        revision.setAccessConfigHash(hash("access", revision.getIdentityProfileKind().name(),
                revision.getAccessDeliverySetKind()));
        revision.setStructureConfigHash(hash("structure", revision.getStructureFormat(),
                revision.getStructureCharset(), revision.getStructureDelimiter(),
                String.valueOf(revision.getStructureQuoteChar()),
                String.valueOf(revision.isStructureHasHeader()),
                String.valueOf(revision.getStructureHeaderLineNumber()),
                revision.getStructureFieldReferenceKind(),
                String.valueOf(revision.getStructureExpectedColumnCount())));
        revision.setRecordRulesConfigHash(hash("record", revision.getIdentityProfileKind().name(),
                revision.getIdentitySupplierField(), revision.getIdentitySupplierGroupField(),
                revision.getIdentitySupplierReferenceField(),
                String.valueOf(revision.getIdentityDiscountCodeField()),
                revision.getRecordBasePriceField(), String.valueOf(revision.getRecordDescriptionField()),
                String.valueOf(revision.getRecordCurrencyField()),
                String.valueOf(revision.getRecordCanonicalisationVersion())));
        revision.setCompositeConfigHash(hash("composite", revision.getAccessConfigHash(),
                revision.getStructureConfigHash(), revision.getRecordRulesConfigHash()));
    }
}
