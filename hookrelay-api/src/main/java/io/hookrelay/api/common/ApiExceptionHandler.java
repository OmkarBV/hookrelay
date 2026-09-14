package io.hookrelay.api.common;

import io.hookrelay.api.endpoint.EndpointNotFoundException;
import io.hookrelay.api.ingestion.PayloadTooLargeException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorResponse(Instant timestamp, int status, String error, String message) {
        static ErrorResponse of(HttpStatus status, String message) {
            return new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message);
        }
    }

    @ExceptionHandler(EndpointNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EndpointNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(HttpStatus.CONFLICT, e.getMessage()));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePayloadTooLarge(PayloadTooLargeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        // Jackson wraps whatever the underlying stream throws while reading
        // the body; unwrap to find PayloadTooLargeException so an oversized
        // (possibly chunked, header-less) body still yields 413, not 400.
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof PayloadTooLargeException tooLarge) {
                return handlePayloadTooLarge(tooLarge);
            }
            cause = cause.getCause();
        }
        return ResponseEntity.badRequest().body(ErrorResponse.of(HttpStatus.BAD_REQUEST, "Malformed request body"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(ErrorResponse.of(HttpStatus.BAD_REQUEST, message));
    }
}
