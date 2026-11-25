package com.rodrigo.pedidos.gateway;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayJwtDecoderTest {

    private static final String ISSUER = "http://localhost:8180/realms/pedido-estoque";
    private static final String AUDIENCE = "pedido-estoque-api";

    private static RSAKey signingKey;
    private static HttpServer jwksServer;
    private static ReactiveJwtDecoder decoder;

    @BeforeAll
    static void startJwksServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("gateway-test-key").generate();
        byte[] jwks = new com.nimbusds.jose.jwk.JWKSet(signingKey.toPublicJWK())
                .toString()
                .getBytes(StandardCharsets.UTF_8);

        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var response = exchange.getResponseBody()) {
                response.write(jwks);
            }
        });
        jwksServer.start();

        String jwkSetUri = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks";
        decoder = new GatewaySecurityConfiguration().jwtDecoder(ISSUER, jwkSetUri, AUDIENCE);
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @Test
    void acceptsCorrectlySignedTokenForConfiguredIssuerAndAudience() throws Exception {
        var decoded = decoder.decode(token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300))).block();

        assertThat(decoded).isNotNull();
        assertThat(decoded.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(decoded.getAudience()).contains(AUDIENCE);
    }

    @Test
    void rejectsTokenWithWrongIssuer() throws Exception {
        assertThatThrownBy(() -> decoder.decode(token("http://wrong-issuer", List.of(AUDIENCE),
                Instant.now().plusSeconds(300))).block())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsTokenWithWrongAudience() throws Exception {
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, List.of("another-api"),
                Instant.now().plusSeconds(300))).block())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, List.of(AUDIENCE),
                Instant.now().minusSeconds(30))).block())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsTokenSignedByAnUnknownKey() throws Exception {
        RSAKey unknownKey = new RSAKeyGenerator(2048).keyID("unknown-key").generate();

        assertThatThrownBy(() -> decoder.decode(token(unknownKey, ISSUER, List.of(AUDIENCE),
                Instant.now().plusSeconds(300))).block())
                .isInstanceOf(RuntimeException.class);
    }

    private String token(String issuer, List<String> audience, Instant expiration) throws Exception {
        return token(signingKey, issuer, audience, expiration);
    }

    private String token(RSAKey key, String issuer, List<String> audience, Instant expiration)
            throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("developer")
                .audience(audience)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(expiration))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        signedJwt.sign(new RSASSASigner(key));
        return signedJwt.serialize();
    }
}
