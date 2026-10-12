package internal.distilly.web.exception;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
            .body(Map.of("error", Objects.requireNonNullElse(e.getReason(), "request_failed")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> size() {
        return ResponseEntity.status(413).body(Map.of("error", "file_too_large"));
    }

    @ExceptionHandler({ org.springframework.http.converter.HttpMessageNotReadableException.class,
            ClassCastException.class, IllegalArgumentException.class })
    ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    }

}
