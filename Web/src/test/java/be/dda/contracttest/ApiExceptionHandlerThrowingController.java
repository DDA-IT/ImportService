package be.dda.contracttest;

import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.NotFoundException;
import be.dda.catalogimport.web.ActorNotAllowedException;
import be.dda.catalogimport.web.PermissionSourceUnavailableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Dummy controller for ApiExceptionHandlerContractTest. Isolated in be.dda.contracttest package
 * (outside be.dda.catalogimport) to avoid being picked up by the application's component scan
 * or by PermissionCoverageTest's classpath scan of the be.dda.catalogimport.web package.
 */
@RestController
public class ApiExceptionHandlerThrowingController {

    private static final String INTERNAL_DETAIL = "credential of connection profile version 12 is missing";

    /** Een eenvoudige requestbody; een onbekende enumwaarde of kapotte JSON maakt hem onleesbaar. */
    public record Payload(String name, Kind kind) {
    }

    public enum Kind { ALPHA, BETA }

    @GetMapping("/bad-request")
    public String badRequest() {
        throw new BadRequestException("SOME_BAD_REQUEST", "bad input");
    }

    @GetMapping("/not-found")
    public String notFound() {
        throw new NotFoundException("SOME_NOT_FOUND", "nothing here");
    }

    @GetMapping("/conflict")
    public String conflict() {
        throw new ConflictException("SOME_CONFLICT", "clash");
    }

    @GetMapping("/forbidden")
    public String forbidden() {
        throw new ActorNotAllowedException("SYSTEM_ACTOR_FORBIDDEN", "not allowed");
    }

    @GetMapping("/permission-source")
    public String permissionSource() {
        throw new PermissionSourceUnavailableException("secret internal detail about the source");
    }

    @GetMapping("/illegal-state")
    public String illegalState() {
        throw new IllegalStateException(INTERNAL_DETAIL);
    }

    @GetMapping("/illegal-state-null")
    public String illegalStateWithoutMessage() {
        throw new IllegalStateException();
    }

    @GetMapping("/illegal-argument")
    public String illegalArgument() {
        throw new IllegalArgumentException("Missing decidedBy");
    }

    @GetMapping("/illegal-argument-null")
    public String illegalArgumentWithoutMessage() {
        throw new IllegalArgumentException();
    }

    @GetMapping("/number-format")
    public String numberFormat() {
        // Subklasse van IllegalArgumentException: blijft 400 zonder code.
        throw new NumberFormatException("For input string: \"x\"");
    }

    @GetMapping("/bad-request-null")
    public String badRequestWithoutMessageOrCode() {
        throw new BadRequestException(null, null);
    }

    @GetMapping("/conflict-null-message")
    public String conflictWithoutMessage() {
        throw new ConflictException("SOME_CONFLICT", null);
    }

    @GetMapping("/upload-too-large")
    public String uploadTooLarge() {
        throw new MaxUploadSizeExceededException(1024L);
    }

    @PostMapping("/payload")
    public String payload(@RequestBody Payload payload) {
        return payload.name();
    }
}
