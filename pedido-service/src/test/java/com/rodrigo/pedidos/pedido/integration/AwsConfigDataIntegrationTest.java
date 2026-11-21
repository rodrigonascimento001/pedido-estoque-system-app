package com.rodrigo.pedidos.pedido.integration;

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
import com.rodrigo.pedidos.pedido.config.AwsDbSecretValidationConfiguration;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Valida os imports de config data do Spring Cloud AWS contra um LocalStack real.
 * As propriedades de endpoint/credenciais são definidas em system properties no
 * bloco estático porque o config data import roda antes de @DynamicPropertySource
 * (issue #1080 do Spring Cloud AWS).
 */
class AwsConfigDataIntegrationTest {

    private static final String COMPLETE_SECRET = """
            {"AWS_DB_URL":"jdbc:postgresql://localhost:5432/pedidos_db","AWS_DB_USER":"pedido","AWS_DB_PASSWORD":"segredo"}
            """;

    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices(SECRETSMANAGER, SSM);

    static {
        LOCALSTACK.start();
        seed();
        System.setProperty("spring.cloud.aws.endpoint", LOCALSTACK.getEndpoint().toString());
        System.setProperty("spring.cloud.aws.region.static", LOCALSTACK.getRegion());
        System.setProperty("spring.cloud.aws.credentials.access-key", "test");
        System.setProperty("spring.cloud.aws.credentials.secret-key", "test");
    }

    private static void seed() {
        createSecret("local", COMPLETE_SECRET);
        createSecret("toggled", COMPLETE_SECRET);
        createSecret("no-param", COMPLETE_SECRET);
        createSecret("broken", """
                {"AWS_DB_URL":"jdbc:postgresql://localhost:5432/pedidos_db","AWS_DB_USER":"pedido"}
                """);
        putParameter("local", "false");
        putParameter("toggled", "true");
    }

    private static void createSecret(String env, String json) {
        exec("secretsmanager", "create-secret",
                "--name", "pedido-estoque/" + env + "/pedido-service/db",
                "--secret-string", json);
    }

    private static void putParameter(String env, String value) {
        exec("ssm", "put-parameter",
                "--name", "/pedido-estoque/" + env + "/common/features.datadog.enabled",
                "--value", value,
                "--type", "String");
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
                    .isEqualTo("jdbc:postgresql://localhost:5432/pedidos_db");
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("pedido");
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

    @Test
    void failsFastWhenSecretDoesNotExist() {
        Throwable thrown = catchThrowable(() -> start("missing", "aws").close());

        assertThat(thrown)
                .hasStackTraceContaining("pedido-estoque/missing/pedido-service/db");
    }

    @Test
    void defaultProfileKeepsStatsdDisabled() {
        try (ConfigurableApplicationContext context = start("local")) {
            assertThat(statsdEnabled(context)).isFalse();
        }
    }

    @Test
    void datadogProfileEnablesStatsdWithoutAws() {
        try (ConfigurableApplicationContext context = start("local", "datadog")) {
            assertThat(statsdEnabled(context)).isTrue();
        }
    }

    @Test
    void awsProfileKeepsStatsdDisabledRegardlessOfParameter() {
        try (ConfigurableApplicationContext context = start("toggled", "aws")) {
            assertThat(statsdEnabled(context)).isFalse();
        }
    }

    @Test
    void awsDatadogProfileUsesParameterValue() {
        try (ConfigurableApplicationContext withTrue = start("toggled", "aws", "datadog");
                ConfigurableApplicationContext withFalse = start("local", "aws", "datadog")) {
            assertThat(statsdEnabled(withTrue)).isTrue();
            assertThat(statsdEnabled(withFalse)).isFalse();
        }
    }

    @Test
    void awsDatadogProfileDefaultsToDisabledWhenParameterIsMissing() {
        try (ConfigurableApplicationContext context = start("no-param", "aws", "datadog")) {
            assertThat(statsdEnabled(context)).isFalse();
        }
    }

    private static boolean statsdEnabled(ConfigurableApplicationContext context) {
        return Boolean.TRUE.equals(context.getEnvironment()
                .getProperty("management.statsd.metrics.export.enabled", Boolean.class));
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
