package com.example.rfq.businessmcp;

import com.example.rfq.businessmcp.config.InternalApiKeyFilter;
import com.zaxxer.hikari.HikariDataSource;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "internal.api-key=test-internal-key",
        "DB_HOST=127.0.0.1", "DB_PORT=1", "DB_NAME=test",
        "DB_USER=test-reader", "DB_PASSWORD=test-password", "DB_SCHEMA=test_schema"
})
class BasicConfigurationTest {
    @LocalServerPort int port;
    @Autowired HikariDataSource dataSource;

    @ParameterizedTest
    @CsvSource({"GET,/mcp", "POST,/mcp", "DELETE,/mcp", "GET,/internal/test", "GET,/other"})
    void rejectsMissingAndIncorrectKeys(String method, String path) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            for (String key : new String[]{"", "wrong-key"}) {
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .method(method, HttpRequest.BodyPublishers.noBody());
                if (!key.isEmpty()) {
                    builder.header("X-Internal-Api-Key", key);
                }
                var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.body()).isEqualTo("{\"error\":\"Unauthorized\"}");
            }
        }
    }

    @Test
    void acceptsCorrectKeyAndExposesNoBusinessTools() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .customizeRequest(builder -> builder.header("X-Internal-Api-Key", "test-internal-key"))
                .build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(client.initialize().serverInfo().name()).isEqualTo("business-mcp");
            assertThat(client.listTools().tools()).isEmpty();
        }
    }

    @Test
    void configuresReadOnlyPostgresConnections() {
        assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:1/test");
        assertThat(dataSource.getUsername()).isEqualTo("test-reader");
        assertThat(dataSource.getSchema()).isEqualTo("test_schema");
        assertThat(dataSource.isReadOnly()).isTrue();
        assertThat(dataSource.getDataSourceProperties().getProperty("readOnlyMode")).isEqualTo("always");
    }

    @Test
    void refusesEmptyServerKey() {
        assertThatThrownBy(() -> new InternalApiKeyFilter(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
