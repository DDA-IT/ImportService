package be.dda.catalogimport.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legt het HUIDIGE foutcontract van {@link ApiExceptionHandler} vast (status + JSON {@code error} en {@code code}),
 * zonder Spring-context of database. IllegalArgumentException/IllegalStateException bewust niet vastgelegd.
 */
class ApiExceptionHandlerContractTest {

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
}
