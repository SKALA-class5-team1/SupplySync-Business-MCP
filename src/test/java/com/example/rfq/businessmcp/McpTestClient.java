/*
 * [테스트 공통] 테스트용 MCP 클라이언트. 서버 연결, tools/call 호출, 성공·오류 결과 확인, 정답 파일 읽기를 모아 둔다.
 * 통합 테스트들이 같은 코드를 각자 복사하지 않도록 공유한다.
 */
package com.example.rfq.businessmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

final class McpTestClient implements AutoCloseable {
    /** 테스트 서버의 internal.api-key 값. 각 테스트의 @SpringBootTest properties와 맞춘다. */
    static final String API_KEY = "test-internal-key";
    static final ObjectMapper JSON = new ObjectMapper();

    private final McpSyncClient client;

    private McpTestClient(McpSyncClient client) {
        this.client = client;
    }

    /** 초기화까지 마친 클라이언트. */
    static McpTestClient connect(int port) {
        return connect(port, API_KEY);
    }

    /** 다른 키로 접속 (실제 백엔드 연결 테스트는 .env의 INTERNAL_API_KEY를 쓴다). */
    static McpTestClient connect(int port, String apiKey) {
        McpTestClient mcp = new McpTestClient(open(port, apiKey));
        mcp.client.initialize();
        return mcp;
    }

    /** 초기화하지 않은 원본 클라이언트 (인증 실패 확인 등). */
    static McpSyncClient open(int port, String apiKey) {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .customizeRequest(builder -> builder.header("X-Internal-Api-Key", apiKey))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
    }

    List<McpSchema.Tool> listTools() {
        return client.listTools().tools();
    }

    McpSchema.Tool tool(String name) {
        return listTools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    /** 성공해야 하는 호출. 결과 텍스트(JSON)를 파싱해 돌려준다. */
    JsonNode callOk(String tool, Map<String, Object> args) throws IOException {
        var result = call(tool, args);
        String text = text(result);
        assertThat(result.isError()).as(text).isNotEqualTo(Boolean.TRUE);
        return JSON.readTree(text);
    }

    /** 실패해야 하는 호출. 오류 메시지를 돌려준다. */
    String callError(String tool, Map<String, Object> args) {
        var result = call(tool, args);
        assertThat(result.isError()).as(text(result)).isTrue();
        return text(result);
    }

    @Override
    public void close() {
        client.close();
    }

    private McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
        return client.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    /** src/test/resources/expected/ 아래 정답 파일. */
    static JsonNode expected(String file) throws IOException {
        try (InputStream in = McpTestClient.class.getResourceAsStream("/expected/" + file)) {
            return JSON.readTree(in);
        }
    }

    static JsonNode byPartId(JsonNode parts, String partId) {
        return StreamSupport.stream(parts.spliterator(), false)
                .filter(p -> p.get("part_id").asText().equals(partId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("part_id 없음: " + partId));
    }
}
