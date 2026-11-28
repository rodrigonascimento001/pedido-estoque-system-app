package com.rodrigo.pedidos.registration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rodrigo.pedidos.registration.api.RegistrationRequest;
import com.rodrigo.pedidos.registration.api.RegistrationResponse;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class UserRegistrationServiceTest {

    private static final String USERS_URL = "http://keycloak:8080/admin/realms/pedido-estoque/users";
    private static final String ADMIN_BASE_URL = "http://keycloak:8080/admin/realms/pedido-estoque/";
    private static final String PASSWORD = "s3nh4-local";

    private final OAuth2AuthorizedClientManager authorizedClientManager = mock(OAuth2AuthorizedClientManager.class);
    private MockRestServiceServer keycloak;
    private UserRegistrationService service;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        var registration = ClientRegistration.withRegistrationId("keycloak-admin")
                .clientId("pedido-estoque-user-registration")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://keycloak:8080/realms/pedido-estoque/protocol/openid-connect/token")
                .build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "service-token", now, now.plusSeconds(300));
        when(authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
                .thenReturn(new OAuth2AuthorizedClient(registration, "user-registration-service", token));

        RestClient.Builder builder = RestClient.builder();
        keycloak = MockRestServiceServer.bindTo(builder).build();
        service = new UserRegistrationService(authorizedClientManager, builder, ADMIN_BASE_URL);
    }

    @Test
    void createsEnabledUserWithNonTemporaryPasswordAndReturnsItsId() {
        keycloak.expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.credentials[0].type").value("password"))
                .andExpect(jsonPath("$.credentials[0].value").value(PASSWORD))
                .andExpect(jsonPath("$.credentials[0].temporary").value(false))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .location(URI.create(USERS_URL + "/user-123"))
                        .contentType(MediaType.APPLICATION_JSON));

        RegistrationResponse response = service.register(request());

        assertThat(response.id()).isEqualTo("user-123");
        assertThat(response.username()).isEqualTo("alice");
        keycloak.verify();
    }

    @Test
    void mapsDuplicateUsernameOrEmailToConflict() {
        keycloak.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(DuplicateUserException.class);
        keycloak.verify();
    }

    @Test
    void mapsInvalidRegistrationDataToBadRequest() {
        keycloak.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(InvalidUserRegistrationException.class);
        keycloak.verify();
    }

    @Test
    void mapsPasswordPolicyViolationToBadRequest() {
        keycloak.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(InvalidUserRegistrationException.class);
        keycloak.verify();
    }

    @Test
    void mapsAdminApiFailureToControlledUnavailableError() {
        keycloak.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(KeycloakUnavailableException.class);
        keycloak.verify();
    }

    @Test
    void rejectsResponseWithoutUserId() {
        keycloak.expect(requestTo(USERS_URL))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(KeycloakUnavailableException.class);
        keycloak.verify();
    }

    @Test
    void doesNotLeakClientSecretWhenTokenAcquisitionFails() {
        when(authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
                .thenThrow(new IllegalStateException("client-secret"));

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(KeycloakUnavailableException.class)
                .hasMessage(null);
        keycloak.verify();
    }

    @Test
    void failsWhenServiceClientHasNoToken() {
        when(authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(KeycloakUnavailableException.class);
        keycloak.verify();
    }

    private RegistrationRequest request() {
        return new RegistrationRequest("alice", "alice@example.com", PASSWORD);
    }
}
