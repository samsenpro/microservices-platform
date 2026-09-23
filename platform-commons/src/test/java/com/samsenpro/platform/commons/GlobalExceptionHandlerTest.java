package com.samsenpro.platform.commons;

import com.samsenpro.platform.commons.error.ApiException;
import com.samsenpro.platform.commons.error.GlobalExceptionHandler;
import com.samsenpro.platform.commons.web.CorrelationId;
import com.samsenpro.platform.commons.web.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    @Test
    void businessErrorsKeepTheirStatusAndCode() throws Exception {
        mvc.perform(get("/business").header(CorrelationId.HEADER, "corr-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("Only 1 unit left"))
                .andExpect(jsonPath("$.path").value("/business"))
                .andExpect(jsonPath("$.correlationId").value("corr-1"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unexpectedErrorsNeverLeakDetailsOrStackTraces() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected internal error"))
                .andExpect(content().string(not(containsString("secret-internal-detail"))))
                .andExpect(content().string(not(containsString("at com.samsenpro"))));
    }

    @Test
    void validationErrorsListTheInvalidFields() throws Exception {
        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void transientDatabaseProblemsAreRetryable503() throws Exception {
        mvc.perform(get("/db"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
    }

    @RestController
    static class FailingController {

        @GetMapping("/business")
        void business() {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Only 1 unit left");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret-internal-detail");
        }

        @GetMapping("/db")
        void db() {
            throw new CannotAcquireLockException("lock timeout");
        }

        @PostMapping("/validated")
        void validated(@Valid @RequestBody Named body) {
        }
    }

    record Named(@NotBlank String name) {
    }
}
