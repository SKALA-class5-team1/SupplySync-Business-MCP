/*
 * [테스트] ReadToolsContract와 같은 검증을 .env의 실제 백엔드(BACKEND_URL)로 실행 + get_rfq_content 원문 확인.
 * 쓰기 Tool은 데이터를 바꾸므로 제외한다. 기본 mvn test에서는 건너뛴다.
 * 실행: 백엔드를 SPRING_PROFILES_ACTIVE=demo로 띄운 뒤 LIVE_BACKEND=true mvn test -Dtest=LiveBackendToolsTest
 */
package com.example.rfq.businessmcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** .env의 BACKEND_URL·INTERNAL_API_KEY를 쓴다. 백엔드 demo 시드(v2.2)가 기대값과 같아야 한다. */
@EnabledIfEnvironmentVariable(named = "LIVE_BACKEND", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveBackendToolsTest extends ReadToolsContract {
    @LocalServerPort int port;
    @Value("${internal.api-key}") String apiKey;

    @Override
    McpTestClient connect() {
        return McpTestClient.connect(port, apiKey);
    }

    @Test
    void getRfqContentReturnsOriginal() throws IOException {
        String original;
        try (InputStream in = getClass().getResourceAsStream("/expected/RFQ_HB-EV7-EWP-001.md")) {
            original = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonNode result = mcp.callOk("get_rfq_content", Map.of("rfq_number", "HB-EV7-EWP-001"));

        assertThat(result.get("rfq_number").asText()).isEqualTo("HB-EV7-EWP-001");
        assertThat(result.get("file_name").asText()).isEqualTo("RFQ_HB-EV7-EWP-001.md");
        assertThat(result.get("content").asText()).isEqualTo(original);
    }
}
