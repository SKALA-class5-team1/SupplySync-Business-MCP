/*
 * [테스트] 서버 기본 설정 확인 (DB 연결 없이 실행).
 * API 키 없는/틀린 요청 401, 올바른 키로 MCP 초기화, Tool 9개 등록, 읽기 전용 커넥션 설정.
 */
package com.example.rfq.businessmcp;

import com.example.rfq.businessmcp.config.InternalApiKeyFilter;
import com.zaxxer.hikari.HikariDataSource;
import io.modelcontextprotocol.spec.McpSchema;
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
        "internal.api-key=" + McpTestClient.API_KEY,
        "DB_HOST=127.0.0.1", "DB_PORT=1", "DB_NAME=test",
        "DB_USER=test-reader", "DB_PASSWORD=test-password", "DB_SCHEMA=test_schema", "DB_SSLMODE=prefer"
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
    void acceptsCorrectKeyAndExposesBusinessTools() {
        try (var client = McpTestClient.open(port, McpTestClient.API_KEY)) {
            assertThat(client.initialize().serverInfo().name()).isEqualTo("business-mcp");
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                    "get_models", "get_options", "get_bom", "check_parts_supply", "get_parts_cost",
                    "save_review", "save_quote", "register_quote", "get_rfq_content");
        }
    }

    @Test
    void configuresReadOnlyPostgresConnections() {
        assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:1/test?sslmode=prefer");
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
