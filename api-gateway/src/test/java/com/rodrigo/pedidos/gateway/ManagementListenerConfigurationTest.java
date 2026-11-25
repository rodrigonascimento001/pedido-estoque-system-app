package com.rodrigo.pedidos.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

class ManagementListenerConfigurationTest {

    @Test
    void eachApplicationUsesItsOwnManagementPortAndExposesOnlyHealth() {
        assertManagementConfiguration("../api-gateway/src/main/resources/application.yml", "9080");
        assertManagementConfiguration("../pedido-service/src/main/resources/application.yml", "9081");
        assertManagementConfiguration("../estoque-service/src/main/resources/application.yml", "9082");
    }

    @Test
    void composeDoesNotPublishBackendOrManagementPorts() throws IOException {
        String compose = Files.readString(Path.of("..", "docker-compose.yml"));
        String pedidoService = blockBetween(compose, "  pedido-service:", "  estoque-service:");
        String estoqueService = blockBetween(compose, "  estoque-service:", "  api-gateway:");
        String gateway = blockBetween(compose, "  api-gateway:", "networks:");

        assertThat(pedidoService).doesNotContain("    ports:");
        assertThat(estoqueService).doesNotContain("    ports:");
        assertThat(gateway).contains("- \"8080:8080\"")
                .doesNotContain("9080", "9081", "9082");
    }

    private void assertManagementConfiguration(String yamlPath, String expectedPort) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of(yamlPath).toFile()));
        Properties properties = yaml.getObject();

        assertThat(properties.get("management.server.port"))
                .isEqualTo(Integer.parseInt(expectedPort));
        assertThat(properties)
                .containsEntry("management.endpoints.web.exposure.include", "health")
                .containsEntry("management.endpoint.health.show-details", "never");
    }

    private String blockBetween(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        int end = content.indexOf(endMarker, start + startMarker.length());
        if (start < 0 || end < 0) {
            throw new IllegalStateException("Could not find Compose service block " + startMarker);
        }
        return content.substring(start, end);
    }
}
