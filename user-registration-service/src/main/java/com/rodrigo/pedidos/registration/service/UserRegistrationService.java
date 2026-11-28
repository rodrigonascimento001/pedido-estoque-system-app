package com.rodrigo.pedidos.registration.service;

import com.rodrigo.pedidos.registration.api.RegistrationRequest;
import com.rodrigo.pedidos.registration.api.RegistrationResponse;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class UserRegistrationService {

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final RestClient restClient;
    private final String usersEndpoint;

    public UserRegistrationService(
            OAuth2AuthorizedClientManager authorizedClientManager,
            RestClient.Builder restClientBuilder,
            @Value("${keycloak.admin.base-url}") String adminBaseUrl) {
        this.authorizedClientManager = authorizedClientManager;
        this.restClient = restClientBuilder.build();
        this.usersEndpoint = adminBaseUrl.replaceAll("/$", "") + "/users";
    }

    public RegistrationResponse register(RegistrationRequest request) {
        OAuth2AccessToken accessToken = obtainAccessToken();
        KeycloakUserRepresentation user = new KeycloakUserRepresentation(
                request.username(), request.email(), true,
                List.of(new KeycloakCredential("password", request.password(), false)));
        try {
            URI location = restClient.post()
                    .uri(usersEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken.getTokenValue())
                    .body(user)
                    .exchange((clientRequest, clientResponse) -> {
                        int status = clientResponse.getStatusCode().value();
                        if (status == HttpStatus.CONFLICT.value()) {
                            throw new DuplicateUserException();
                        }
                        if (status == HttpStatus.BAD_REQUEST.value() || status == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
                            throw new InvalidUserRegistrationException();
                        }
                        if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                            throw new KeycloakUnavailableException();
                        }
                        return clientResponse.getHeaders().getLocation();
                    });
            return new RegistrationResponse(extractId(location), request.username());
        } catch (DuplicateUserException | InvalidUserRegistrationException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                throw new DuplicateUserException();
            }
            if (exception.getStatusCode().is4xxClientError()) {
                throw new InvalidUserRegistrationException();
            }
            throw new KeycloakUnavailableException();
        } catch (RestClientException exception) {
            throw new KeycloakUnavailableException();
        }
    }

    private OAuth2AccessToken obtainAccessToken() {
        try {
            var authorizedClient = authorizedClientManager.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId("keycloak-admin")
                    .principal("user-registration-service")
                    .build());
            if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
                throw new KeycloakUnavailableException();
            }
            return authorizedClient.getAccessToken();
        } catch (KeycloakUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new KeycloakUnavailableException();
        }
    }

    private String extractId(URI location) {
        if (location == null || location.getPath() == null || location.getPath().isBlank()
                || location.getPath().endsWith("/")) {
            throw new KeycloakUnavailableException();
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private record KeycloakUserRepresentation(
            String username,
            String email,
            boolean enabled,
            List<KeycloakCredential> credentials) {
    }

    private record KeycloakCredential(String type, String value, boolean temporary) {

        @Override
        public String toString() {
            return "KeycloakCredential[type=" + type + ", value=[REDACTED], temporary=" + temporary + "]";
        }
    }
}
