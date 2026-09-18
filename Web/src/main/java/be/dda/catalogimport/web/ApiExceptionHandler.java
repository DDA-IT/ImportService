package be.dda.catalogimport.web;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class}) ResponseEntity<Map<String,String>> invalid(RuntimeException error) { return ResponseEntity.badRequest().body(Map.of("error", error.getMessage())); }
}
