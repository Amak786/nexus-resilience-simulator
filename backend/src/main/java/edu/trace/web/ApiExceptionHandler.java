package edu.trace.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import edu.trace.config.RequestSizeLimitFilter;
import edu.trace.model.ValidationException;
import edu.trace.model.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.stream.Collectors;

/** Every error is returned as {@code {error, message, issues[]}}. Stack traces never leave the server. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(String error, String message, List<ValidationIssue> issues) {}

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> validation(ValidationException ex) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", ex.getMessage(), ex.getIssues()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        while (cause != null && !(cause instanceof RequestSizeLimitFilter.PayloadTooLargeException)
                && !(cause instanceof JsonMappingException)) {
            cause = cause.getCause();
        }
        if (cause instanceof RequestSizeLimitFilter.PayloadTooLargeException) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new ApiError("PAYLOAD_TOO_LARGE", cause.getMessage(), List.of()));
        }
        String message = "Malformed JSON body or a field of the wrong type.";
        String field = "body";
        if (cause instanceof JsonMappingException jm && !jm.getPath().isEmpty()) {
            field = jm.getPath().stream()
                    .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining(".")).replace(".[", "[");
            message = "Field '" + field + "' is malformed, of the wrong type, or not allowed.";
        }
        return ResponseEntity.badRequest().body(new ApiError("MALFORMED_REQUEST", message,
                List.of(new ValidationIssue(field, message))));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", ex.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "Invalid value for '" + ex.getName() + "'.", List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("METHOD_NOT_ALLOWED", ex.getMessage(), List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError("UNSUPPORTED_MEDIA_TYPE", "Send JSON with Content-Type: application/json.", List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "No such endpoint.", List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex) {
        LOG.error("Unhandled server error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "Unexpected server error. It has been logged.", List.of()));
    }
}
