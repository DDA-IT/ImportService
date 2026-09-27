package be.dda.catalogimport.web;
import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class}) ResponseEntity<Map<String,String>> invalid(RuntimeException error) { return ResponseEntity.badRequest().body(Map.of("error", error.getMessage())); }
    // Additief contract: naast "error" bevat het antwoord een stabiele "code".
    // BadRequestException erft van IllegalArgumentException; Spring kiest de meest specifieke handler,
    // dus bestaande 400-antwoorden (zonder code) blijven exact zoals ze waren.
    @ExceptionHandler(BadRequestException.class) ResponseEntity<Map<String,String>> badRequest(BadRequestException error) { return ResponseEntity.badRequest().body(Map.of("error", error.getMessage(), "code", error.getCode())); }
    @ExceptionHandler(NotFoundException.class) ResponseEntity<Map<String,String>> notFound(NotFoundException error) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", error.getMessage(), "code", error.getCode())); }
    @ExceptionHandler(ConflictException.class) ResponseEntity<Map<String,String>> conflict(ConflictException error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage(), "code", error.getCode())); }
    // Fase 5-AUTH (additief): de aangemelde gebruiker mag dit niet -> 403 met ACTOR_IDENTITY_INVALID (5A-1)
    // of SYSTEM_ACTOR_FORBIDDEN (5A-2). ACTOR_FIELD_MISMATCH is bewust GEEN 403 maar een 400 en loopt via
    // BadRequestException hierboven: de aanvraag zelf deugt niet, de gebruiker mag de actie wel (ontwerp A5).
    @ExceptionHandler(ActorNotAllowedException.class) ResponseEntity<Map<String,String>> forbidden(ActorNotAllowedException error) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", error.getMessage(), "code", error.getCode())); }
    // Fase 5-PERM (additief, 5B-1): de rechtenbron kon niet bevraagd worden -> 503. Fail-closed: nooit
    // doorgaan alsof de gebruiker geen rechten heeft, en nooit alsof hij ze wel heeft.
    // De boodschap is bewust VAST en noemt de bron niet (ontwerp par. 3); de oorzaak staat in de log.
    @ExceptionHandler(PermissionSourceUnavailableException.class) ResponseEntity<Map<String,String>> permissionSourceUnavailable(PermissionSourceUnavailableException error) { LOG.error("{}: {}", PermissionSourceUnavailableException.CODE, error.getMessage(), error); return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Your permissions could not be determined right now; try again later", "code", PermissionSourceUnavailableException.CODE)); }
}
