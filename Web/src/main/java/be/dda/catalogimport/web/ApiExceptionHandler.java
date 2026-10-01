package be.dda.catalogimport.web;
import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
@RestControllerAdvice public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    /** 500: een interne fout ({@link IllegalStateException}) die nergens vertaald werd (analyse-opvolging stap 3a). */
    public static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";
    /** 413: de upload is groter dan {@code spring.servlet.multipart.max-file-size}/{@code max-request-size}. */
    public static final String CODE_UPLOAD_TOO_LARGE = "UPLOAD_TOO_LARGE";
    /** 400: de aanvraagbody ontbreekt of is niet te lezen (geen geldige JSON, verkeerd type, onbekende enumwaarde). */
    public static final String CODE_REQUEST_BODY_UNREADABLE = "REQUEST_BODY_UNREADABLE";
    static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred";
    static final String UPLOAD_TOO_LARGE_MESSAGE = "The upload is larger than the maximum allowed upload size";
    static final String REQUEST_BODY_UNREADABLE_MESSAGE = "The request body is missing or could not be read";

    /**
     * De foutbody {@code {error, code}}, null-veilig: een ontbrekende boodschap of code wordt weggelaten in plaats
     * van een NullPointerException in de handler (die anders zelf een naamloze 500 zou worden).
     */
    static Map<String,String> body(String error, String code) {
        Map<String,String> body = new LinkedHashMap<>();
        if (error != null) { body.put("error", error); }
        if (code != null) { body.put("code", code); }
        return Collections.unmodifiableMap(body);
    }

    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,String>> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(body(error.getMessage(), null)); }
    // Analyse-opvolging stap 3a (bewuste contractwijziging: was 400 met de interne tekst). Een IllegalStateException
    // die tot hier komt, is een interne fout (ontbrekende configuratie of credential, archieffout, programmeerfout):
    // 500 met een VASTE boodschap; de oorzaak met stacktrace staat enkel in het serverlogboek.
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<Map<String,String>> internal(IllegalStateException error) { LOG.error("{}: unhandled {}", CODE_INTERNAL_ERROR, error.getClass().getSimpleName(), error); return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(INTERNAL_ERROR_MESSAGE, CODE_INTERNAL_ERROR)); }
    // Additief contract: naast "error" bevat het antwoord een stabiele "code".
    // BadRequestException erft van IllegalArgumentException; Spring kiest de meest specifieke handler,
    // dus bestaande 400-antwoorden (zonder code) blijven exact zoals ze waren.
    @ExceptionHandler(BadRequestException.class) ResponseEntity<Map<String,String>> badRequest(BadRequestException error) { return ResponseEntity.badRequest().body(body(error.getMessage(), error.getCode())); }
    @ExceptionHandler(NotFoundException.class) ResponseEntity<Map<String,String>> notFound(NotFoundException error) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(error.getMessage(), error.getCode())); }
    @ExceptionHandler(ConflictException.class) ResponseEntity<Map<String,String>> conflict(ConflictException error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(body(error.getMessage(), error.getCode())); }
    // Fase 5-AUTH (additief): de aangemelde gebruiker mag dit niet -> 403 met ACTOR_IDENTITY_INVALID (5A-1)
    // of SYSTEM_ACTOR_FORBIDDEN (5A-2). ACTOR_FIELD_MISMATCH is bewust GEEN 403 maar een 400 en loopt via
    // BadRequestException hierboven: de aanvraag zelf deugt niet, de gebruiker mag de actie wel (ontwerp A5).
    @ExceptionHandler(ActorNotAllowedException.class) ResponseEntity<Map<String,String>> forbidden(ActorNotAllowedException error) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(error.getMessage(), error.getCode())); }
    // Fase 5-PERM (additief, 5B-1): de rechtenbron kon niet bevraagd worden -> 503. Fail-closed: nooit
    // doorgaan alsof de gebruiker geen rechten heeft, en nooit alsof hij ze wel heeft.
    // De boodschap is bewust VAST en noemt de bron niet (ontwerp par. 3); de oorzaak staat in de log.
    @ExceptionHandler(PermissionSourceUnavailableException.class) ResponseEntity<Map<String,String>> permissionSourceUnavailable(PermissionSourceUnavailableException error) { LOG.error("{}: {}", PermissionSourceUnavailableException.CODE, error.getMessage(), error); return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body("Your permissions could not be determined right now; try again later", PermissionSourceUnavailableException.CODE)); }
    // Analyse-opvolging stap 3a: de upload overschrijdt de multipartlimiet -> 413 met eigen code (voorheen 413
    // zonder code via Spring). Vaste boodschap; de limiet zelf staat enkel in het logboek.
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<Map<String,String>> uploadTooLarge(MaxUploadSizeExceededException error) { LOG.warn("{}: upload rejected (limit {} bytes)", CODE_UPLOAD_TOO_LARGE, error.getMaxUploadSize()); return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body(UPLOAD_TOO_LARGE_MESSAGE, CODE_UPLOAD_TOO_LARGE)); }
    // Analyse-opvolging stap 3a: de body ontbreekt of is niet te lezen -> 400 met eigen code (voorheen 400 zonder
    // code via Spring). Vaste boodschap: de parsertekst kan klasse- en pakketnamen en stukken van de invoer dragen.
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<Map<String,String>> requestBodyUnreadable(HttpMessageNotReadableException error) { Throwable cause = error.getMostSpecificCause(); LOG.warn("{}: request body could not be read ({})", CODE_REQUEST_BODY_UNREADABLE, cause.getClass().getSimpleName()); LOG.debug("{}: parser detail", CODE_REQUEST_BODY_UNREADABLE, error); return ResponseEntity.badRequest().body(body(REQUEST_BODY_UNREADABLE_MESSAGE, CODE_REQUEST_BODY_UNREADABLE)); }
}
