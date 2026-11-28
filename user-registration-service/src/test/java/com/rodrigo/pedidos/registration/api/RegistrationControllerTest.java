package com.rodrigo.pedidos.registration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rodrigo.pedidos.registration.service.DuplicateUserException;
import com.rodrigo.pedidos.registration.service.InvalidUserRegistrationException;
import com.rodrigo.pedidos.registration.service.KeycloakUnavailableException;
import com.rodrigo.pedidos.registration.service.UserRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class RegistrationControllerTest {

    private static final String PASSWORD = "s3nh4-local";
    private static final String VALID_BODY = """
            {"username":"alice","email":"alice@example.com","password":"%s"}
            """.formatted(PASSWORD);

    private final UserRegistrationService service = mock(UserRegistrationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new RegistrationController(service))
                .setControllerAdvice(new RegistrationExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void returnsCreatedWithoutPasswordAndDoesNotExposeItViaToString() throws Exception {
        when(service.register(any(RegistrationRequest.class)))
                .thenReturn(new RegistrationResponse("user-123", "alice"));

        String body = mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("user-123"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD);
        ArgumentCaptor<RegistrationRequest> captor = ArgumentCaptor.forClass(RegistrationRequest.class);
        verify(service).register(captor.capture());
        assertThat(captor.getValue().toString())
                .doesNotContain(PASSWORD)
                .contains("[REDACTED]");
    }

    @Test
    void rejectsMissingEmailWithFieldGuidanceAndDoesNotCallService() throws Exception {
        mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_registration"))
                .andExpect(jsonPath("$.fields.email").exists());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidEmailWithFieldGuidance() throws Exception {
        mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists());

        verifyNoInteractions(service);
    }

    @Test
    void mapsDuplicateUserToConflict() throws Exception {
        doThrow(new DuplicateUserException()).when(service).register(any(RegistrationRequest.class));

        mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user_already_exists"));
    }

    @Test
    void mapsInvalidRegistrationToBadRequest() throws Exception {
        doThrow(new InvalidUserRegistrationException()).when(service).register(any(RegistrationRequest.class));

        mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_registration"));
    }

    @Test
    void mapsKeycloakFailureToServiceUnavailableWithoutLeakingDetails() throws Exception {
        doThrow(new KeycloakUnavailableException()).when(service).register(any(RegistrationRequest.class));

        String body = mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("identity_provider_unavailable"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD).doesNotContain("client-secret").doesNotContain("Bearer");
    }
}
