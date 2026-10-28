package de.fhaachen.voicehome.api;

import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import de.fhaachen.voicehome.control.HomeService;
import de.fhaachen.voicehome.storage.JdbcHomeStore;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

@RestControllerAdvice
public class ApiErrors {
    public record ErrorMessage(String message, String code, String field, List<String> allowedValues) { }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorMessage> invalid(IllegalArgumentException exception) {
        if (exception instanceof HomeService.InvalidInput invalid) {
            return ResponseEntity.badRequest().body(new ErrorMessage(invalid.getMessage(), "VALIDATION_ERROR", invalid.field(), List.of()));
        }
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorMessage> malformed(HttpMessageNotReadableException exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof UnrecognizedPropertyException unknown) {
                return ResponseEntity.badRequest().body(new ErrorMessage(
                        "Unknown field: " + unknown.getPropertyName() + ".", "UNKNOWN_FIELD", unknown.getPropertyName(), List.of()));
            }
            if (cause instanceof MismatchedInputException invalid && !invalid.getPath().isEmpty()) {
                String field = invalid.getPath().getLast().getPropertyName();
                if (field == null) { field = "request"; }
                Class<?> target = invalid.getTargetType();
                List<String> allowed = target != null && target.isEnum()
                        ? Arrays.stream(target.getEnumConstants()).map(Object::toString).toList() : List.of();
                String message = "Invalid value for " + field + ".";
                if (!allowed.isEmpty()) { message += " Choose " + String.join(", ", allowed) + "."; }
                return ResponseEntity.badRequest().body(new ErrorMessage(message, "INVALID_FIELD", field, allowed));
            }
        }
        return ResponseEntity.badRequest().body(new ErrorMessage(
                "The request must contain a valid JSON object.", "INVALID_JSON", null, List.of()));
    }

    @ExceptionHandler(HomeService.NotFound.class)
    ResponseEntity<ErrorMessage> missing(HomeService.NotFound exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(HomeService.Conflict.class)
    ResponseEntity<ErrorMessage> conflict(HomeService.Conflict exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({UncheckedIOException.class, JdbcHomeStore.StorageException.class})
    ResponseEntity<ErrorMessage> storage() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Local storage is unavailable. Check the device state before retrying a command.");
    }

    private ResponseEntity<ErrorMessage> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorMessage(message, status.name(), null, List.of()));
    }
}
