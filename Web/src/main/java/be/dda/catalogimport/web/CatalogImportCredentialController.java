package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.CredentialService;
import be.dda.catalogimport.service.CredentialService.CredentialEventView;
import be.dda.catalogimport.service.CredentialService.CredentialView;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Credentials van externe bronnen (bouwstap K-3, {@code docs/design/leveringsconfiguratie-design.md} par. 6;
 * beslissingslog 2026-09-29 V1, V2, L7).
 *
 * <h2>Rechten</h2>
 * <b>Alle</b> endpoints vragen {@code MANAGE}, ook de GET's: design par. 6 legt "Credentials lezen" op MANAGE, en elk
 * antwoord bevat {@code boundHost}, wat volgens L7b host-gevoelig is (precedent D8, {@code GET /local-source/files}).
 * Niet achter {@code catalogimport.setup-api.enabled} (L7a). Recht eerst: 403 {@code PERMISSION_DENIED} komt uit de
 * interceptor, vóór het lezen van de body en dus vóór elke 400/404/409.
 *
 * <h2>Statuscodes</h2>
 * 201 bij aanmaken, anders 200. 400 {@code CREDENTIAL_LABEL_REQUIRED}, {@code CREDENTIAL_SECRET_KIND_INVALID},
 * {@code CREDENTIAL_SECRET_KIND_NOT_SUPPORTED}, {@code CREDENTIAL_HOST_INVALID}, {@code CREDENTIAL_SECRET_REQUIRED},
 * {@code CREDENTIAL_SECRET_TOO_LONG}, {@code CREDENTIAL_REASON_REQUIRED}, {@link #CODE_REQUEST_UNREADABLE}; 404
 * {@code CREDENTIAL_NOT_FOUND}; 409 {@code SECRETS_NOT_CONFIGURED}, {@code CREDENTIAL_ALREADY_REVOKED}.
 *
 * <h2>Lekpreventie (V1)</h2>
 * Geen antwoord bevat de waarde, de ciphertext of een sleutel-ID. De body wordt door deze controller zelf gelezen
 * (zie {@link #readBody}), zodat geen Spring-log de body of een Jackson-foutmelding met een stuk van de invoer toont;
 * de request-records overschrijven daarbij {@code toString} zonder de waarde (verdediging in de diepte). Volgorde:
 * recht (interceptor) → {@code system}/identiteit → body lezen (400) → service (400/404/409).
 */
@RestController
@RequestMapping("/api/catalog-import/credentials")
public class CatalogImportCredentialController {

    /** 400: de body is geen leesbare JSON of ontbreekt. */
    public static final String CODE_REQUEST_UNREADABLE = "CREDENTIAL_REQUEST_UNREADABLE";

    /** Body van {@code POST /credentials}; {@code toString} zonder de waarde. */
    public record CreateCredentialRequest(String label, String secretKind, String boundHost, String secret,
                                          String reason) {
        @Override
        public String toString() {
            return "CreateCredentialRequest[label=" + label + ", secretKind=" + secretKind + ", boundHost=" + boundHost
                    + ", secret=(" + (secret == null ? "absent" : "redacted") + "), reason=(redacted)]";
        }
    }

    /** Body van {@code PUT /credentials/{ref}/secret}; {@code toString} zonder de waarde. */
    public record ReplaceSecretRequest(String secret, String reason) {
        @Override
        public String toString() {
            return "ReplaceSecretRequest[secret=(" + (secret == null ? "absent" : "redacted") + "), reason=(redacted)]";
        }
    }

    /** Body van {@code POST /credentials/{ref}/revoke}. */
    public record RevokeCredentialRequest(String reason) {
        @Override
        public String toString() {
            return "RevokeCredentialRequest[reason=(redacted)]";
        }
    }

    private final CredentialService credentials;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;

    public CatalogImportCredentialController(CredentialService credentials, CurrentActor currentActor,
                                             ObjectMapper objectMapper) {
        this.credentials = credentials;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
    }

    @RequiresPermission(Permission.MANAGE)
    @GetMapping
    List<CredentialView> list() {
        return credentials.list();
    }

    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/{credentialRef}")
    CredentialView get(@PathVariable("credentialRef") String credentialRef) {
        return credentials.get(credentialRef);
    }

    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/{credentialRef}/events")
    List<CredentialEventView> events(@PathVariable("credentialRef") String credentialRef) {
        return credentials.events(credentialRef);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CredentialView create(HttpServletRequest httpRequest) {
        ActorIdentity actor = signer();
        CreateCredentialRequest request = readBody(httpRequest, CreateCredentialRequest.class);
        return credentials.create(request.label(), request.secretKind(), request.boundHost(), request.secret(),
                request.reason(), actor);
    }

    /** Vervangen ({@code ACTIVE}) of heractiveren ({@code REVOKED}), beslissingslog 2026-09-29 K-3. */
    @RequiresPermission(Permission.MANAGE)
    @PutMapping("/{credentialRef}/secret")
    CredentialView replaceSecret(@PathVariable("credentialRef") String credentialRef, HttpServletRequest httpRequest) {
        ActorIdentity actor = signer();
        ReplaceSecretRequest request = readBody(httpRequest, ReplaceSecretRequest.class);
        return credentials.replaceSecret(credentialRef, request.secret(), request.reason(), actor);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/{credentialRef}/revoke")
    CredentialView revoke(@PathVariable("credentialRef") String credentialRef, HttpServletRequest httpRequest) {
        ActorIdentity actor = signer();
        RevokeCredentialRequest request = readBody(httpRequest, RevokeCredentialRequest.class);
        return credentials.revoke(credentialRef, request.reason(), actor);
    }

    /**
     * Leest de JSON-body zelf in plaats van via {@code @RequestBody}. Reden (lekpreventie, V1): bij een onleesbare body
     * logt Spring de Jackson-melding op DEBUG ({@code InvocableHandlerMethod}) en op WARN
     * ({@code DefaultHandlerExceptionResolver}), en die melding herhaalt een niet-geciteerd token uit de invoer - dat
     * kan het wachtwoord zijn. Hier wordt een leesfout een vaste 400 zonder oorzaak-keten en zonder log. Zelfde
     * {@link ObjectMapper} als de rest van de API (onbekende velden worden genegeerd, zoals elders).
     */
    private <T> T readBody(HttpServletRequest httpRequest, Class<T> type) {
        T body;
        try {
            body = objectMapper.readValue(httpRequest.getInputStream(), type);
        } catch (IOException unreadable) {
            throw unreadableBody();
        }
        if (body == null) {
            throw unreadableBody();
        }
        return body;
    }

    private static BadRequestException unreadableBody() {
        return new BadRequestException(CODE_REQUEST_UNREADABLE, "The request body is missing or is not valid JSON");
    }

    /** Geen actorveld in deze requests: de naam komt altijd uit het token (5-AUTH); {@code system} tekent nooit. */
    private ActorIdentity signer() {
        return currentActor.signer(null, "actor");
    }
}
