package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConnectionTestService;
import be.dda.catalogimport.service.ConnectionTestService.ConnectionTestView;
import be.dda.catalogimport.service.ConnectionTestService.HostKeyScanView;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hostsleutelscan en verbindingstests (bouwstap K-4a, {@code docs/design/leveringsconfiguratie-design.md} par. 5 en 6;
 * beslissingslog 2026-09-29 L4, L5, L7, A16).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /connection-profiles/host-key-scan} {@code {host, port?}} → {@code {outcome, failureCode,
 *       hostKeyAlgorithm, hostKeyFingerprintSha256}}; verbindt zonder aanmelding.</li>
 *   <li>{@code POST /connection-profile-versions/{id}/test} → {@code {outcome, failureCode,
 *       presentedHostKeyFingerprint, matchedFiles: null, truncated: false}}; verbinding, hostsleutel, aanmelding.</li>
 *   <li>{@code POST /delivery-configuration-versions/{id}/test} → idem met {@code matchedFiles [{name, size,
 *       modifiedAt}]} (hoogstens 100, nieuwste eerst) en {@code truncated}; geen download.</li>
 * </ul>
 *
 * <h2>Rechten</h2>
 * Alle drie {@code MANAGE} (L7: scan- en testlistings tonen host, vingerafdruk en bestandsnamen), niet achter
 * {@code catalogimport.setup-api.enabled} (L7a); in tweede lijn de allowlist {@code catalogimport.fetch.allowed-hosts}
 * (L4b). Recht eerst: 403 komt uit de interceptor, vóór elke 400/404/409.
 *
 * <h2>Statuscodes</h2>
 * 200 met {@code outcome = OK | FAILED} voor elk resultaat aan de kant van de server (ook
 * {@code SFTP_HOST_KEY_MISMATCH}, {@code SFTP_AUTHENTICATION_FAILED}, {@code CREDENTIAL_REVOKED}, en bij de tests
 * {@code FETCH_HOST_NOT_ALLOWED}). Anders, in deze volgorde: 400 {@code CONNECTION_PROFILE_HOST_INVALID}/
 * {@code _PORT_INVALID} (scan); 404 {@code FETCH_NOT_CONFIGURED} (allowlist niet gezet), dan
 * {@code CONNECTION_PROFILE_VERSION_NOT_FOUND}/{@code DELIVERY_CONFIGURATION_VERSION_NOT_FOUND}; 409
 * {@code FETCH_HOST_NOT_ALLOWED} (scan), {@code SECRETS_NOT_CONFIGURED} (tests). Geen secret in body of antwoord, dus de
 * gewone {@code @RequestBody}.
 */
@RestController
@RequestMapping("/api/catalog-import")
public class CatalogImportConnectionTestController {

    /** Body van de scan; {@code port} weglaten = 22. */
    public record HostKeyScanRequest(String host, Integer port) {
    }

    private final ConnectionTestService tests;
    private final CurrentActor currentActor;

    public CatalogImportConnectionTestController(ConnectionTestService tests, CurrentActor currentActor) {
        this.tests = tests;
        this.currentActor = currentActor;
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/connection-profiles/host-key-scan")
    HostKeyScanView scanHostKey(@RequestBody(required = false) HostKeyScanRequest request) {
        ActorIdentity actor = currentActor.signer(null, "actor");
        return tests.scanHostKey(request == null ? null : request.host(), request == null ? null : request.port(),
                actor);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/connection-profile-versions/{versionId}/test")
    ConnectionTestView testProfileVersion(@PathVariable("versionId") long versionId) {
        ActorIdentity actor = currentActor.signer(null, "actor");
        return tests.testProfileVersion(versionId, actor);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/delivery-configuration-versions/{versionId}/test")
    ConnectionTestView testDeliveryConfigurationVersion(@PathVariable("versionId") long versionId) {
        ActorIdentity actor = currentActor.signer(null, "actor");
        return tests.testDeliveryConfigurationVersion(versionId, actor);
    }
}
