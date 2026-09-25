package be.dda.catalogimport.web;
import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.NotFoundException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class}) ResponseEntity<Map<String,String>> invalid(RuntimeException error) { return ResponseEntity.badRequest().body(Map.of("error", error.getMessage())); }
    // Additief contract: naast "error" bevat het antwoord een stabiele "code".
    // BadRequestException erft van IllegalArgumentException; Spring kiest de meest specifieke handler,
    // dus bestaande 400-antwoorden (zonder code) blijven exact zoals ze waren.
    @ExceptionHandler(BadRequestException.class) ResponseEntity<Map<String,String>> badRequest(BadRequestException error) { return ResponseEntity.badRequest().body(Map.of("error", error.getMessage(), "code", error.getCode())); }
    @ExceptionHandler(NotFoundException.class) ResponseEntity<Map<String,String>> notFound(NotFoundException error) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", error.getMessage(), "code", error.getCode())); }
    @ExceptionHandler(ConflictException.class) ResponseEntity<Map<String,String>> conflict(ConflictException error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage(), "code", error.getCode())); }
    // Fase 5-AUTH (additief): de aangemelde gebruiker mag dit niet, bv. ACTOR_IDENTITY_INVALID.
    @ExceptionHandler(ActorNotAllowedException.class) ResponseEntity<Map<String,String>> forbidden(ActorNotAllowedException error) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", error.getMessage(), "code", error.getCode())); }
}
