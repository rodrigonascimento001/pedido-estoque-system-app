package com.rodrigo.pedidos.estoque.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SECRETSMANAGER;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SSM;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import com.rodrigo.pedidos.estoque.config.AwsDbSecretValidationConfiguration;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Valida que o serviço de estoque resolve o próprio segredo e falha rápido com
 * segredo incompleto, sem cair no fallback postgres/postgres do application.yml.
 */
class AwsConfigDataIntegrationTest {

    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices(SECRETSMANAGER, SSM);

    static {
        LOCALSTACK.start();
        exec("secretsmanager", "create-secret",
                "--name", "pedido-estoque/local/estoque-service/db",
                "--secret-string",
                "{\"AWS_DB_URL\":\"jdbc:postgresql://localhost:5432/estoque_db\","
                        + "\"AWS_DB_USER\":\"estoque\",\"AWS_DB_PASSWORD\":\"segredo\"}");
        exec("secretsmanager", "create-secret",
                "--name", "pedido-estoque/broken/estoque-service/db",
                "--secret-string", "{\"AWS_DB_URL\":\"jdbc:postgresql://localhost:5432/estoque_db\",\"AWS_DB_USER\":\"estoque\"}");
        exec("ssm", "put-parameter",
                "--name", "/pedido-estoque/local/common/features.datadog.enabled",
                "--value", "false",
                "--type", "String");
        System.setProperty("spring.cloud.aws.endpoint", LOCALSTACK.getEndpoint().toString());
        System.setProperty("spring.cloud.aws.region.static", LOCALSTACK.getRegion());
        System.setProperty("spring.cloud.aws.credentials.access-key", "test");
        System.setProperty("spring.cloud.aws.credentials.secret-key", "test");
    }

    private static void exec(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "awslocal";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            var result = LOCALSTACK.execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("awslocal falhou: " + result.getStderr());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao preparar o LocalStack", e);
        }
    }

    private ConfigurableApplicationContext start(String appEnv, String... profiles) {
        return new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(profiles)
                .properties("APP_ENV=" + appEnv)
                .run();
    }

    @Test
    void resolvesDatasourceFromSecret() {
        try (ConfigurableApplicationContext context = start("local", "aws")) {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost:5432/estoque_db");
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("estoque");
            assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("segredo");
        }
    }

    @Test
    void failsFastWhenSecretKeyIsMissing() {
        Throwable thrown = catchThrowable(() -> start("broken", "aws").close());

        assertThat(thrown)
                .hasStackTraceContaining("AWS_DB_PASSWORD")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            KafkaAutoConfiguration.class
    })
    @Import(AwsDbSecretValidationConfiguration.class)
    static class TestApplication {
    }
}
