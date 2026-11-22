package com.rodrigo.pedidos.pedido.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

/**
 * Garante fail-fast no perfil aws. O binder do datasource mantém placeholders
 * não resolvidos como texto literal; @Value, não. Assim um segredo incompleto
 * derruba o startup em vez de tentar conectar com uma senha inválida.
 */
@Configuration(proxyBeanMethods = false)
@Profile("aws")
public class AwsDbSecretValidationConfiguration {

    public AwsDbSecretValidationConfiguration(
            @Value("${AWS_DB_URL}") String url,
            @Value("${AWS_DB_USER}") String username,
            @Value("${AWS_DB_PASSWORD}") String password) {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new IllegalStateException(
                    "Segredo do banco incompleto: AWS_DB_URL, AWS_DB_USER e AWS_DB_PASSWORD são obrigatórios");
        }
    }
}
