package be.dda.catalogimport.web;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.NotFoundException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class}) ResponseEntity<Map<String,String>> invalid(RuntimeException error) { return ResponseEntity.badRequest().body(Map.of("error", error.getMessage())); }
    // Additief contract: naast "error" bevat het antwoord een stabiele "code".
    @ExceptionHandler(NotFoundException.class) ResponseEntity<Map<String,String>> notFound(NotFoundException error) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", error.getMessage(), "code", error.getCode())); }
    @ExceptionHandler(ConflictException.class) ResponseEntity<Map<String,String>> conflict(ConflictException error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage(), "code", error.getCode())); }
}
