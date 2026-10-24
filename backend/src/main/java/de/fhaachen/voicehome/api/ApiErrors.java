package de.fhaachen.voicehome.api;

import java.io.UncheckedIOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import de.fhaachen.voicehome.control.HomeService;

@RestControllerAdvice
public class ApiErrors {
    public record ErrorMessage(String message) { }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorMessage> invalid(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorMessage> malformed() {
        return error(HttpStatus.BAD_REQUEST, "Requête JSON invalide. Vérifiez les champs, les types et les valeurs.");
    }

    @ExceptionHandler(HomeService.NotFound.class)
    ResponseEntity<ErrorMessage> missing(HomeService.NotFound exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(HomeService.Conflict.class)
    ResponseEntity<ErrorMessage> conflict(HomeService.Conflict exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(UncheckedIOException.class)
    ResponseEntity<ErrorMessage> storage() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "La configuration n’a pas pu être enregistrée. Réessayez après vérification du stockage.");
    }

    private ResponseEntity<ErrorMessage> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorMessage(message));
    }
}
