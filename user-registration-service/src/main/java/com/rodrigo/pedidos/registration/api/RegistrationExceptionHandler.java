package com.rodrigo.pedidos.registration.api;

import com.rodrigo.pedidos.registration.service.DuplicateUserException;
import com.rodrigo.pedidos.registration.service.InvalidUserRegistrationException;
import com.rodrigo.pedidos.registration.service.KeycloakUnavailableException;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RegistrationExceptionHandler {

    @ExceptionHandler(DuplicateUserException.class)
    ResponseEntity<Map<String, String>> duplicateUser() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "user_already_exists", "message", "Username or email is already registered"));
    }

    @ExceptionHandler(InvalidUserRegistrationException.class)
    ResponseEntity<Map<String, String>> invalidRequest() {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "invalid_registration", "message", "Registration data is invalid"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidFields(MethodArgumentNotValidException exception) {
        Map<String, String> fields = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> "invalid",
                        (first, ignored) -> first));
        return ResponseEntity.badRequest().body(Map.of(
                "code", "invalid_registration",
                "message", "Registration data is invalid",
                "fields", fields));
    }

    @ExceptionHandler(KeycloakUnavailableException.class)
    ResponseEntity<Map<String, String>> keycloakUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "identity_provider_unavailable", "message", "Registration is temporarily unavailable"));
    }
}
