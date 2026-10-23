package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository.LinkCredentialStatus;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.domain.ExternalCredentialStatus;
import be.dda.catalogimport.domain.ImportLink;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alleen-lezen opzoeklijst van importkoppelingen (Scherm 0/3, D14, bouwstap S0-B3): dient enkel om
 * koppelingsnamen te tonen in plaats van een kaal id, en schrijft niets. Bewust een eigen, kleine
 * service naast {@code LinkBookmarkValueService}/{@code CatalogImportLinkController} — die twee horen
 * bij de materialisatiewizard (achter {@code catalogimport.setup-api.enabled}) en blijven ongemoeid.
 * <p>
 * Paginering: {@code page} 0-gebaseerd, {@code size} standaard {@value #DEFAULT_PAGE_SIZE} en begrensd
 * tot {@value #MAX_PAGE_SIZE}, zelfde contract als {@link BatchQueryService}/{@link BundleQueryService}.
 */
@Service
@Transactional(readOnly = true)
public class ImportLinkQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    /** {@code credentialState}: minstens één gekoppelde credential is {@code ACTIVE}. */
    public static final String CREDENTIAL_STATE_STORED = "STORED";
    /** {@code credentialState}: er zijn gekoppelde credentials, maar allemaal ingetrokken ({@code REVOKED}). */
    public static final String CREDENTIAL_STATE_REVOKED = "REVOKED";
    /** {@code credentialState}: geen enkele credential gekoppeld. */
    public static final String CREDENTIAL_STATE_NONE = "NONE";

    /**
     * Eén koppeling, met leverancierscode/-naam meegeleverd zodat de UI geen tweede opzoekactie doet.
     * {@code importDefinitionId} is additief (S1-B1): sluit de boom van scherm 1a tot op koppelingenniveau.
     * <p>
     * {@code credentialState} is additief (B3, beslissingslog 2026-10-23 "API-uitbreiding voor de
     * inrichtingsschermen", punt 3), nooit {@code null}: {@value #CREDENTIAL_STATE_STORED},
     * {@value #CREDENTIAL_STATE_REVOKED} of {@value #CREDENTIAL_STATE_NONE}. Gekoppeld = via de legacy-herkomst
     * ({@code legacy_config_origin.credential_id}) of via een taak → leveringsconfiguratieversie →
     * verbindingsprofielversie. Enkel statusinformatie (L7b, READ): nooit {@code credentialRef}, label, host of
     * ciphertext.
     * <p>
     * De aanmaakstandaard is additief (CD-B2, beslissingslog 2026-10-23 "Aanmaakstandaard per koppeling: uitwerking
     * (CD)"): {@code defaultVatCode}/{@code defaultUnitCode} ({@code null} = geen standaard),
     * {@code creationDefaultsOrigin} ({@code LEGACY}, {@code MANUAL} of {@code null} = nooit gezet) en de audit van de
     * laatste wijziging ({@code creationDefaultsUpdatedAt}/{@code creationDefaultsUpdatedBy}; het subject blijft intern).
     */
    public record ImportLinkRow(Long id, String code, String name, String supplierCode, String supplierName,
                                String libraryCode, boolean active, long importDefinitionId,
                                String credentialState, String defaultVatCode, String defaultUnitCode,
                                String creationDefaultsOrigin, Instant creationDefaultsUpdatedAt,
                                String creationDefaultsUpdatedBy) {

        private static ImportLinkRow of(ImportLink link, String credentialState) {
            return new ImportLinkRow(link.getId(), link.getCode(), link.getName(),
                    link.getSupplierOrganisation().getCode(), link.getSupplierOrganisation().getName(),
                    link.getLibraryCode(), link.isActive(), link.getImportDefinition().getId(), credentialState,
                    link.getDefaultVatCode(), link.getDefaultUnitCode(),
                    link.getCreationDefaultsOrigin() == null ? null : link.getCreationDefaultsOrigin().name(),
                    link.getCreationDefaultsUpdatedAt(), link.getCreationDefaultsUpdatedBy());
        }
    }

    private final ImportLinkRepository links;
    private final ExternalCredentialRepository credentials;

    public ImportLinkQueryService(ImportLinkRepository links, ExternalCredentialRepository credentials) {
        this.links = links;
        this.credentials = credentials;
    }

    /**
     * Alle koppelingen, oplopend op {@code code}, optioneel gefilterd op {@code active} en (additief,
     * S1-B1) op {@code importDefinitionId}.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<ImportLinkRow> listImportLinks(Boolean active, Long importDefinitionId, Integer page,
                                                      Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<ImportLink> result = links.findLinkRows(active, importDefinitionId, pageRequest);
        Map<Long, String> states = credentialStates(result.getContent().stream().map(ImportLink::getId).toList());
        return PageResult.of(result,
                link -> ImportLinkRow.of(link, states.getOrDefault(link.getId(), CREDENTIAL_STATE_NONE)));
    }

    /**
     * {@code credentialState} per koppeling van de pagina, met twee projectiequeries over de koppeling-id's (geen
     * query per rij). {@code ACTIVE} wint van {@code REVOKED}; een koppeling zonder enige credential ontbreekt in de
     * map (= {@value #CREDENTIAL_STATE_NONE}).
     */
    private Map<Long, String> credentialStates(List<Long> linkIds) {
        Map<Long, String> states = new HashMap<>();
        if (linkIds.isEmpty()) {
            return states;
        }
        merge(states, credentials.findLegacyOriginCredentialStatuses(linkIds));
        merge(states, credentials.findTaskChainCredentialStatuses(linkIds));
        return states;
    }

    private static void merge(Map<Long, String> states, List<LinkCredentialStatus> rows) {
        for (LinkCredentialStatus row : rows) {
            if (row.getStatus() == ExternalCredentialStatus.ACTIVE) {
                states.put(row.getImportLinkId(), CREDENTIAL_STATE_STORED);
            } else {
                states.putIfAbsent(row.getImportLinkId(), CREDENTIAL_STATE_REVOKED);
            }
        }
    }

    /** Sortering staat al vast in de {@code @Query} van {@code findLinkRows}. */
    private static PageRequest pageRequest(Integer page, Integer size) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE), Sort.unsorted());
    }
}
