package com.rodrigo.pedidos.registration.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RegistrationRequest(
        @NotBlank String username,
        @NotBlank @Email String email,
        @NotBlank String password) {

    @Override
    public String toString() {
        return "RegistrationRequest[username=" + username + ", email=" + email + ", password=[REDACTED]]";
    }
}
