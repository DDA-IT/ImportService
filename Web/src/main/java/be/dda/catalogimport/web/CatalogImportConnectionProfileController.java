package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConnectionProfileService;
import be.dda.catalogimport.service.ConnectionProfileService.ConnectionProfileDetail;
import be.dda.catalogimport.service.ConnectionProfileService.ConnectionProfileSummary;
import be.dda.catalogimport.service.ConnectionProfileService.NewConnectionProfile;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verbindingsprofielen (bouwstap LC-2, {@code docs/design/leveringsconfiguratie-design.md} par. 6; beslissingslog
 * 2026-09-29 L3-L5, L7).
 *
 * <h2>Rechten</h2>
 * <b>Alle</b> endpoints vragen {@code MANAGE}, ook de GET's - zelfde keuze als bij de credentials (K-3): het detail
 * toont host, login en vingerafdruk (L7b), en de lijst MANAGE maken houdt één regel voor de hele ophaalconfiguratie. De
 * lijst bevat bewust enkel kopgegevens (code, naam, actief, laatste versie), zodat ze later additief voor {@code READ}
 * geopend kan worden zonder iets gevoeligs te tonen. Niet achter {@code catalogimport.setup-api.enabled} (L7a). Recht
 * eerst: 403 {@code PERMISSION_DENIED} komt uit de interceptor, vóór 400/404/409.
 *
 * <h2>Statuscodes</h2>
 * 201 bij aanmaken, anders 200. 400 {@code CONNECTION_PROFILE_*} (invoer, o.a. {@code ..._AUTH_METHOD_NOT_SUPPORTED},
 * {@code ..._HOST_KEY_FINGERPRINT_INVALID}); 404 {@code CONNECTION_PROFILE_NOT_FOUND}, {@code CREDENTIAL_NOT_FOUND};
 * 409 {@code CONNECTION_PROFILE_CODE_EXISTS}, {@code CONNECTION_PROFILE_CREDENTIAL_NOT_ACTIVE},
 * {@code ..._CREDENTIAL_HOST_MISMATCH}, {@code ..._CREDENTIAL_KIND_MISMATCH}. Geen secret in de body of het antwoord:
 * daarom de gewone {@code @RequestBody} (conventie), niet het zelf inlezen van K-3.
 */
@RestController
@RequestMapping("/api/catalog-import/connection-profiles")
public class CatalogImportConnectionProfileController {

    /** Body van {@code POST /connection-profiles}; {@code port} weglaten = 22. */
    public record CreateConnectionProfileRequest(String code, String name, String host, Integer port, String username,
                                                 String authMethod, String credentialRef, String hostKeyAlgorithm,
                                                 String hostKeyFingerprintSha256, String reason) {
    }

    private final ConnectionProfileService profiles;
    private final CurrentActor currentActor;

    public CatalogImportConnectionProfileController(ConnectionProfileService profiles, CurrentActor currentActor) {
        this.profiles = profiles;
        this.currentActor = currentActor;
    }

    @RequiresPermission(Permission.MANAGE)
    @GetMapping
    List<ConnectionProfileSummary> list() {
        return profiles.list();
    }

    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/{profileId}")
    ConnectionProfileDetail get(@PathVariable("profileId") long profileId) {
        return profiles.get(profileId);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConnectionProfileDetail create(@RequestBody(required = false) CreateConnectionProfileRequest request) {
        ActorIdentity actor = currentActor.signer(null, "actor");
        NewConnectionProfile input = request == null ? null : new NewConnectionProfile(request.code(), request.name(),
                request.host(), request.port(), request.username(), request.authMethod(), request.credentialRef(),
                request.hostKeyAlgorithm(), request.hostKeyFingerprintSha256(), request.reason());
        return profiles.create(input, actor);
    }
}
