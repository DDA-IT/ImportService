package be.dda.catalogimport.web;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Legt het foutcontract van {@link ApiExceptionHandler} vast (status + JSON {@code error} en {@code code}),
 * zonder Spring-context of database. Sinds analyse-opvolging stap 3a (docs/decisions.md 2026-10-01) ook:
 * IllegalStateException is 500 met een vaste boodschap, IllegalArgumentException blijft 400, de body is
 * null-veilig, en de upload- en bodyfouten dragen een eigen code.
 */
class ApiExceptionHandlerContractTest {

    private static final String INTERNAL_DETAIL = "credential of connection profile version 12 is missing";

    /** Een eenvoudige requestbody; een onbekende enumwaarde of kapotte JSON maakt hem onleesbaar. */
    record Payload(String name, Kind kind) {
    }

    enum Kind { ALPHA, BETA }

    @RestController
    static class ThrowingController {
        @GetMapping("/bad-request")
        String badRequest() {
            throw new BadRequestException("SOME_BAD_REQUEST", "bad input");
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new NotFoundException("SOME_NOT_FOUND", "nothing here");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new ConflictException("SOME_CONFLICT", "clash");
        }

        @GetMapping("/forbidden")
        String forbidden() {
            throw new ActorNotAllowedException("SYSTEM_ACTOR_FORBIDDEN", "not allowed");
        }

        @GetMapping("/permission-source")
        String permissionSource() {
            throw new PermissionSourceUnavailableException("secret internal detail about the source");
        }

        @GetMapping("/illegal-state")
        String illegalState() {
            throw new IllegalStateException(INTERNAL_DETAIL);
        }

        @GetMapping("/illegal-state-null")
        String illegalStateWithoutMessage() {
            throw new IllegalStateException();
        }

        @GetMapping("/illegal-argument")
        String illegalArgument() {
            throw new IllegalArgumentException("Missing decidedBy");
        }

        @GetMapping("/illegal-argument-null")
        String illegalArgumentWithoutMessage() {
            throw new IllegalArgumentException();
        }

        @GetMapping("/number-format")
        String numberFormat() {
            // Subklasse van IllegalArgumentException: blijft 400 zonder code.
            throw new NumberFormatException("For input string: \"x\"");
        }

        @GetMapping("/bad-request-null")
        String badRequestWithoutMessageOrCode() {
            throw new BadRequestException(null, null);
        }

        @GetMapping("/conflict-null-message")
        String conflictWithoutMessage() {
            throw new ConflictException("SOME_CONFLICT", null);
        }

        @GetMapping("/upload-too-large")
        String uploadTooLarge() {
            throw new MaxUploadSizeExceededException(1024L);
        }

        @PostMapping("/payload")
        String payload(@RequestBody Payload payload) {
            return payload.name();
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void badRequestIs400WithErrorAndCode() throws Exception {
        mvc.perform(get("/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad input"))
                .andExpect(jsonPath("$.code").value("SOME_BAD_REQUEST"));
    }

    @Test
    void notFoundIs404WithErrorAndCode() throws Exception {
        mvc.perform(get("/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("nothing here"))
                .andExpect(jsonPath("$.code").value("SOME_NOT_FOUND"));
    }

    @Test
    void conflictIs409WithErrorAndCode() throws Exception {
        mvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("clash"))
                .andExpect(jsonPath("$.code").value("SOME_CONFLICT"));
    }

    @Test
    void actorNotAllowedIs403WithErrorAndCode() throws Exception {
        mvc.perform(get("/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("not allowed"))
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
    }

    @Test
    void permissionSourceUnavailableIs503WithFixedMessageAndCode() throws Exception {
        mvc.perform(get("/permission-source"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Your permissions could not be determined right now; try again later"))
                .andExpect(jsonPath("$.code").value("PERMISSION_SOURCE_UNAVAILABLE"));
    }

    // --- Stap 3a: IllegalStateException en IllegalArgumentException ------------------------------------------

    @Test
    void illegalStateIs500WithAFixedMessageAndTheInternalTextNeverLeaks() throws Exception {
        mvc.perform(get("/illegal-state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("credential"))))
                .andExpect(content().string(not(containsString("profile version 12"))));
    }

    @Test
    void illegalStateWithoutMessageIsStillTheSame500() throws Exception {
        mvc.perform(get("/illegal-state-null"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void illegalArgumentStays400WithItsMessageAndWithoutCode() throws Exception {
        mvc.perform(get("/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing decidedBy"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void aSubclassOfIllegalArgumentStays400() throws Exception {
        mvc.perform(get("/number-format"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("For input string: \"x\""))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    // --- Stap 3a: null-veilige body ---------------------------------------------------------------------------

    @Test
    void illegalArgumentWithoutMessageIs400AndNotA500FromTheHandler() throws Exception {
        mvc.perform(get("/illegal-argument-null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void badRequestWithoutMessageOrCodeIs400WithAnEmptyBody() throws Exception {
        mvc.perform(get("/bad-request-null"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{}", true));
    }

    @Test
    void conflictWithoutMessageKeepsItsCode() throws Exception {
        mvc.perform(get("/conflict-null-message"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOME_CONFLICT"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    // --- Stap 3a: eigen codes voor upload- en bodyfouten -----------------------------------------------------

    @Test
    void anUploadAboveTheLimitIs413WithItsOwnCode() throws Exception {
        mvc.perform(get("/upload-too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.error").value("The upload is larger than the maximum allowed upload size"));
    }

    @Test
    void malformedJsonIs400WithItsOwnCodeAndNoParserDetail() throws Exception {
        mvc.perform(post("/payload").contentType(MediaType.APPLICATION_JSON).content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_UNREADABLE"))
                .andExpect(jsonPath("$.error").value("The request body is missing or could not be read"))
                .andExpect(content().string(not(containsString("JSON parse error"))));
    }

    @Test
    void anUnknownEnumValueInTheBodyIs400WithoutLeakingTheJavaType() throws Exception {
        mvc.perform(post("/payload").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"kind\":\"GAMMA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_UNREADABLE"))
                .andExpect(content().string(not(containsString("be.dda"))))
                .andExpect(content().string(not(containsString("GAMMA"))));
    }

    @Test
    void aMissingBodyIs400WithTheSameCode() throws Exception {
        mvc.perform(post("/payload").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_UNREADABLE"));
    }

    @Test
    void aReadableBodyStillReachesTheController() throws Exception {
        mvc.perform(post("/payload").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ok\",\"kind\":\"ALPHA\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }
}
