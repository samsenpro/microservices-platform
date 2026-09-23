package com.samsenpro.platform.commons.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsenpro.platform.commons.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/** Construye y escribe {@link ApiError}; lo usan el handler global y los handlers de Spring Security. */
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static ApiError build(HttpStatus status, String code, String message, String path,
                                 List<ApiError.FieldViolation> errors) {
        return new ApiError(Instant.now(), status.value(), code, message, path, CorrelationId.current(), errors);
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                build(status, code, message, request.getRequestURI(), List.of()));
    }
}
