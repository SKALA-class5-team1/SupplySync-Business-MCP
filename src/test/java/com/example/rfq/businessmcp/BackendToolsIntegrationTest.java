/*
 * [테스트] 백엔드 API Tool 4개 통합 테스트. MockRestServiceServer로 백엔드를 흉내 낸다.
 * 요청 경로·헤더·본문, 응답 전달, 오류 변환(404·409·415·500·타임아웃·200KB 초과), Tool 표시(hint)를 확인한다.
 */
package com.example.rfq.businessmcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.MockServerRestClientCustomizer;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 백엔드 API Tool(6~9): MockRestServiceServer로 백엔드를 흉내 내고 MCP tools/call로 확인한다. DB는 쓰지 않는다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "internal.api-key=" + McpTestClient.API_KEY,
        "DB_HOST=127.0.0.1", "DB_PORT=1", "DB_NAME=test", "DB_USER=test-reader", "DB_PASSWORD=test-password",
        "backend.url=http://backend.test"
})
@AutoConfigureMockRestServiceServer
class BackendToolsIntegrationTest {
    private static final ObjectMapper JSON = McpTestClient.JSON;
    private static final String BACKEND = "http://backend.test";

    @LocalServerPort int port;
    @Autowired MockServerRestClientCustomizer restClientMock;
    MockRestServiceServer backend;
    McpTestClient mcp;

    @BeforeEach
    void connect() {
        // RestTemplate에도 mock customizer가 붙어 있어 통합 MockRestServiceServer 빈 대신 RestClient 쪽 서버를 쓴다
        backend = restClientMock.getServer();
        backend.reset();
        mcp = McpTestClient.connect(port);
    }

    @AfterEach
    void close() {
        mcp.close();
    }

    @Test
    void toolAnnotations() {
        Map<String, McpSchema.ToolAnnotations> hints = new HashMap<>();
        mcp.listTools().forEach(t -> hints.put(t.name(), t.annotations()));

        assertThat(hints.get("register_quote").destructiveHint()).isTrue();
        for (String tool : List.of("save_review", "save_quote")) {
            assertThat(hints.get(tool).idempotentHint()).as(tool).isTrue();
            assertThat(hints.get(tool).destructiveHint()).as(tool).isFalse();
        }
        for (String tool : List.of("get_models", "get_options", "get_bom", "check_parts_supply", "get_parts_cost",
                "get_rfq_content")) {
            assertThat(hints.get(tool).readOnlyHint()).as(tool).isTrue();
            assertThat(hints.get(tool).destructiveHint()).as(tool).isFalse();
        }
    }

    @Test
    void saveReviewSg1() throws IOException {
        backend.expect(requestTo(BACKEND + "/internal/reviews/th_1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Internal-Api-Key", McpTestClient.API_KEY))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andExpect(content().json("""
                        {"thread_id": "th_1", "rfq_number": "HB-EV7-EWP-001", "stage": "SG1",
                         "selected_model": "EWP-12-B", "options": ["OPT-HT", "OPT-IP9K"], "fit_type": "파생 사양",
                         "issues": [{"id": 1, "severity": "HIGH"}], "customer_questions": ["커넥터 사양 확인"]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"review_id": 1, "thread_id": "th_1", "status": "WAITING"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = mcp.callOk("save_review", Map.of(
                "thread_id", "th_1", "rfq_number", "HB-EV7-EWP-001", "stage", "SG1",
                "selected_model", "EWP-12-B", "options", List.of("OPT-HT", "OPT-IP9K"), "fit_type", "파생 사양",
                "issues", List.of(Map.of("id", 1, "severity", "HIGH")),
                "customer_questions", List.of("커넥터 사양 확인")));

        assertThat(result.toString()).isEqualTo("{\"review_id\":1,\"thread_id\":\"th_1\",\"status\":\"WAITING\"}");
        backend.verify();
    }

    /** 5_sg2_supply.json의 shortages·alternatives·recommended_alt·final_plan을 그대로 전달. SG1 필드는 보내지 않는다. */
    @Test
    void saveReviewSg2() throws IOException {
        JsonNode sg2 = McpTestClient.expected("5_sg2_supply.json");
        ObjectNode body = JSON.createObjectNode()
                .put("thread_id", "th_1").put("rfq_number", "HB-EV7-EWP-001").put("stage", "SG2");
        for (String field : List.of("shortages", "alternatives", "recommended_alt", "final_plan")) {
            body.set(field, sg2.get(field));
        }

        backend.expect(requestTo(BACKEND + "/internal/reviews/th_1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andExpect(content().json(body.toString(), JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"review_id": 1, "thread_id": "th_1", "status": "WAITING"}
                        """, MediaType.APPLICATION_JSON));

        mcp.callOk("save_review", JSON.convertValue(body, new TypeReference<Map<String, Object>>() { }));
        backend.verify();
    }

    /** 6_sg3_quote.json의 quote_draft(quote_id·status 제외) + thread_id, review_id를 그대로 전달. */
    @Test
    void saveQuote() throws IOException {
        ObjectNode draft = (ObjectNode) McpTestClient.expected("6_sg3_quote.json").get("quote_draft");
        draft.remove(List.of("quote_id", "status"));
        ObjectNode body = JSON.createObjectNode().put("thread_id", "th_1").put("review_id", 1);
        body.setAll(draft);

        backend.expect(requestTo(BACKEND + "/internal/quotes/th_1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Internal-Api-Key", McpTestClient.API_KEY))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andExpect(content().json(body.toString(), JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"quote_id": "Q-2026-1019-001", "status": "DRAFT"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = mcp.callOk("save_quote", JSON.convertValue(body, new TypeReference<Map<String, Object>>() { }));

        assertThat(result.get("quote_id").asText()).isEqualTo("Q-2026-1019-001");
        assertThat(result.get("status").asText()).isEqualTo("DRAFT");
        backend.verify();
    }

    @Test
    void saveQuoteConflict() {
        backend.expect(requestTo(BACKEND + "/internal/quotes/th_1"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\": \"APPROVED 상태입니다\"}"));

        assertThat(mcp.callError("save_quote", Map.of("thread_id", "th_1", "review_id", 1,
                "rfq_number", "HB-EV7-EWP-001", "unit_price_krw", 37300)))
                .contains("현재 상태에서 불가", "APPROVED 상태입니다");
    }

    @Test
    void registerQuote() throws IOException {
        backend.expect(requestTo(BACKEND + "/internal/quotes/Q-2026-1019-001/register"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", McpTestClient.API_KEY))
                .andExpect(header("Idempotency-Key", "Q-2026-1019-001:register"))
                .andExpect(content().json("""
                        {"quote_id": "Q-2026-1019-001", "thread_id": "th_1"}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"quote_id": "Q-2026-1019-001", "status": "REGISTERED", "rfq_status": "QUOTED",
                         "registered_at": "2026-10-19T15:30:00"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = mcp.callOk("register_quote", Map.of("quote_id", "Q-2026-1019-001", "thread_id", "th_1"));

        assertThat(result.toString()).isEqualTo("{\"quote_id\":\"Q-2026-1019-001\",\"status\":\"REGISTERED\","
                + "\"rfq_status\":\"QUOTED\",\"registered_at\":\"2026-10-19T15:30:00\"}");
        backend.verify();
    }

    @Test
    void registerQuoteErrors() {
        var args = Map.<String, Object>of("quote_id", "Q-1", "thread_id", "th_1");

        backend.expect(requestTo(BACKEND + "/internal/quotes/Q-1/register"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        assertThat(mcp.callError("register_quote", args)).contains("현재 상태에서 불가", "승인되지 않은 견적");

        backend.reset();
        backend.expect(requestTo(BACKEND + "/internal/quotes/Q-1/register"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": \"견적이 없습니다: Q-1\"}"));
        assertThat(mcp.callError("register_quote", args)).contains("404", "견적이 없습니다: Q-1");

        backend.reset();
        backend.expect(requestTo(BACKEND + "/internal/quotes/Q-1/register")).andRespond(withServerError());
        assertThat(mcp.callError("register_quote", args)).contains("백엔드 오류", "500");

        backend.reset();
        backend.expect(requestTo(BACKEND + "/internal/quotes/Q-1/register"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThat(mcp.callError("register_quote", args)).contains("백엔드 오류", "시간 초과");
    }

    /** mock 응답 content에 01_rfq/RFQ_HB-EV7-EWP-001.md 원문을 넣고 그대로 반환되는지 확인한다. */
    @Test
    void getRfqContent() throws IOException {
        String original;
        try (InputStream in = getClass().getResourceAsStream("/expected/RFQ_HB-EV7-EWP-001.md")) {
            original = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ObjectNode response = JSON.createObjectNode()
                .put("rfq_number", "HB-EV7-EWP-001").put("customer", "한별모터스")
                .put("item", "전동식 워터펌프 (EWP, 12V)").put("file_name", "RFQ_HB-EV7-EWP-001.md")
                .put("content_type", "text/markdown").put("received_at", "2026-10-12T09:00:00")
                .put("content", original);

        backend.expect(requestTo(BACKEND + "/internal/rfqs/HB-EV7-EWP-001/content"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Api-Key", McpTestClient.API_KEY))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));

        JsonNode result = mcp.callOk("get_rfq_content", Map.of("rfq_number", "HB-EV7-EWP-001"));

        assertThat(result).isEqualTo(response);
        assertThat(result.get("content").asText()).isEqualTo(original);
        backend.verify();
    }

    @Test
    void getRfqContentErrors() {
        var args = Map.<String, Object>of("rfq_number", "HB-NONE");
        String uri = BACKEND + "/internal/rfqs/HB-NONE/content";

        backend.expect(requestTo(uri)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(mcp.callError("get_rfq_content", args)).contains("RFQ를 찾을 수 없음", "HB-NONE");

        backend.reset();
        backend.expect(requestTo(uri)).andRespond(withStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        assertThat(mcp.callError("get_rfq_content", args)).contains("지원하지 않는 형식");

        backend.reset();
        String huge = "{\"rfq_number\": \"HB-NONE\", \"content\": \"" + "가".repeat(80_000) + "\"}";
        backend.expect(requestTo(uri)).andRespond(withSuccess(huge, MediaType.APPLICATION_JSON));
        assertThat(mcp.callError("get_rfq_content", args)).contains("200KB");
    }

    @Test
    void missingIdsAreRejectedWithoutCallingBackend() {
        assertThat(mcp.callError("register_quote", Map.of("quote_id", " ", "thread_id", "th_1"))).contains("quote_id");
        assertThat(mcp.callError("save_review", Map.of("thread_id", "th_1", "rfq_number", "R", "stage", "")))
                .contains("stage");
        assertThat(mcp.callError("get_rfq_content", Map.of("rfq_number", ""))).contains("rfq_number");
        backend.verify();
    }
}
