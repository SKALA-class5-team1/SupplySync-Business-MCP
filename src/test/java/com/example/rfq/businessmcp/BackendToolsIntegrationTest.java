/*
 * [테스트] 백엔드 API Tool 4개 통합 테스트. MockRestServiceServer로 백엔드를 흉내 낸다.
 * 요청 경로·헤더·본문·호출 순서, 응답 전달, 오류 변환(400 details·404·409·500·타임아웃·200KB 초과), Tool 표시(hint)를 확인한다.
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

/** 백엔드 API Tool(6~9): MockRestServiceServer로 백엔드를 흉내 내고 MCP tools/call로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "internal.api-key=" + McpTestClient.API_KEY,
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
                "get_rfq_content", "get_substitutes")) {
            assertThat(hints.get(tool).readOnlyHint()).as(tool).isTrue();
            assertThat(hints.get(tool).destructiveHint()).as(tool).isFalse();
        }
    }

    /**
     * 배열 항목 타입이 빠진 스키마({})는 일부 MCP 클라이언트가 거부하거나 잘못 다룬다 (Inspector "Schema portability" 경고).
     * 모든 Tool 입력의 배열 필드는 항목 타입을 선언해야 한다.
     */
    @Test
    void arrayItemsDeclareType() {
        for (McpSchema.Tool tool : mcp.listTools()) {
            JsonNode schema = JSON.valueToTree(tool.inputSchema());
            for (JsonNode items : schema.findValues("items")) {
                assertThat(items.has("type") || items.has("$ref"))
                        .as("%s의 배열 항목 타입 없음: %s", tool.name(), items)
                        .isTrue();
            }
        }
    }

    /** demand의 필드명이 snake_case로 노출되어야 Agent가 그대로 넣을 수 있다. */
    @Test
    void saveReviewDemandSchema() {
        McpSchema.Tool tool = mcp.listTools().stream().filter(t -> t.name().equals("save_review")).findFirst().orElseThrow();
        JsonNode demand = JSON.valueToTree(tool.inputSchema()).at("/properties/demand/properties");

        assertThat(demand.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "eau", "sample_qty", "sample_due_date", "sop_date", "target_price_krw", "quote_due_date");
    }

    /** SG1: demand를 RFQ에 먼저 저장(PATCH)한 뒤 분석 결과를 저장(PUT)한다. stage는 백엔드로 보내지 않는다. */
    @Test
    void saveReviewSg1() throws IOException {
        backend.expect(requestTo(BACKEND + "/internal/rfqs/HB-EV7-EWP-001"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("X-Internal-Api-Key", McpTestClient.API_KEY))
                .andExpect(content().json("""
                        {"eau": 120000, "sample_qty": 2000, "sample_due_date": "2026-12-15", "sop_date": "2027-09-01",
                         "target_price_krw": 38000, "quote_due_date": "2026-10-30"}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"rfq_number\": \"HB-EV7-EWP-001\", \"eau\": 120000}",
                        MediaType.APPLICATION_JSON));
        backend.expect(requestTo(BACKEND + "/internal/rfq-reviews"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Internal-Api-Key", McpTestClient.API_KEY))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andExpect(content().json("""
                        {"rfq_number": "HB-EV7-EWP-001", "thread_id": "th_1", "status": "WAITING",
                         "selected_model": "EWP-12-B", "options": ["OPT-HT", "OPT-IP9K"], "fit_type": "파생 사양",
                         "issues": [{"id": "ISS-01", "severity": "HIGH",
                                     "evidence": [{"chunk_id": "std-002", "section": "§2", "excerpt": "5~60 L/min"}]}],
                         "customer_questions": ["커넥터 사양 확인"]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"review_id": 1, "rfq_number": "HB-EV7-EWP-001", "thread_id": "th_1", "status": "WAITING",
                         "updated_at": "2026-10-21T10:00:00"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = mcp.callOk("save_review", Map.of(
                "thread_id", "th_1", "rfq_number", "HB-EV7-EWP-001", "stage", "SG1", "status", "WAITING",
                "demand", Map.of("eau", 120000, "sample_qty", 2000, "sample_due_date", "2026-12-15",
                        "sop_date", "2027-09-01", "target_price_krw", 38000, "quote_due_date", "2026-10-30"),
                "selected_model", "EWP-12-B", "options", List.of("OPT-HT", "OPT-IP9K"), "fit_type", "파생 사양",
                "issues", List.of(Map.of("id", "ISS-01", "severity", "HIGH", "evidence",
                        List.of(Map.of("chunk_id", "std-002", "section", "§2", "excerpt", "5~60 L/min")))),
                "customer_questions", List.of("커넥터 사양 확인")));

        assertThat(result.toString()).isEqualTo("{\"review_id\":1,\"rfq_number\":\"HB-EV7-EWP-001\","
                + "\"thread_id\":\"th_1\",\"status\":\"WAITING\",\"updated_at\":\"2026-10-21T10:00:00\"}");
        backend.verify();
    }

    /** 5_sg2_supply.json의 shortages·alternatives·recommended_alt·final_plan을 그대로 전달. demand가 없으면 PATCH하지 않는다. */
    @Test
    void saveReviewSg2() throws IOException {
        JsonNode sg2 = McpTestClient.expected("5_sg2_supply.json");
        ObjectNode body = JSON.createObjectNode().put("rfq_number", "HB-EV7-EWP-001").put("thread_id", "th_1");
        for (String field : List.of("shortages", "alternatives", "recommended_alt", "final_plan")) {
            body.set(field, sg2.get(field));
        }

        backend.expect(requestTo(BACKEND + "/internal/rfq-reviews"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andExpect(content().json(body.toString(), JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"review_id": 1, "thread_id": "th_1", "status": "DONE"}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> args = JSON.convertValue(body, new TypeReference<>() { });
        args.put("stage", "SG2");
        mcp.callOk("save_review", args);
        backend.verify();
    }

    @Test
    void saveReviewPartialFailure() {
        Map<String, Object> args = Map.of("thread_id", "th_1", "rfq_number", "HB-EV7-EWP-001", "stage", "SG1",
                "demand", Map.of("eau", 120000));

        backend.expect(requestTo(BACKEND + "/internal/rfqs/HB-EV7-EWP-001")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(mcp.callError("save_review", args))
                .contains("demand(RFQ 요구값) 저장 실패", "분석 결과는 저장하지 않았습니다", "RFQ를 찾을 수 없음");
        backend.verify();

        backend.reset();
        backend.expect(requestTo(BACKEND + "/internal/rfqs/HB-EV7-EWP-001"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        backend.expect(requestTo(BACKEND + "/internal/rfq-reviews")).andRespond(withServerError());
        assertThat(mcp.callError("save_review", args)).contains("demand는 저장됐지만", "백엔드 오류", "500");
        backend.verify();
    }

    /** 6_sg3_quote.json의 quote_draft(quote_id·status 제외) + thread_id, revision을 그대로 전달 (details로 묶지 않음). */
    @Test
    void saveQuote() throws IOException {
        ObjectNode draft = (ObjectNode) McpTestClient.expected("6_sg3_quote.json").get("quote_draft");
        draft.remove(List.of("quote_id", "status"));
        ObjectNode body = JSON.createObjectNode().put("thread_id", "th_1").put("revision", 1);
        body.setAll(draft);

        backend.expect(requestTo(BACKEND + "/internal/quotes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", McpTestClient.API_KEY))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andExpect(content().json(body.toString(), JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"quote_id": "Q-2026-1019-001", "status": "DRAFT", "review_id": 1}
                        """));

        JsonNode result = mcp.callOk("save_quote", JSON.convertValue(body, new TypeReference<Map<String, Object>>() { }));

        assertThat(result.toString()).isEqualTo("{\"quote_id\":\"Q-2026-1019-001\",\"status\":\"DRAFT\",\"review_id\":1}");
        backend.verify();
    }

    @Test
    void saveQuoteErrors() {
        Map<String, Object> args = Map.of("thread_id", "th_1", "revision", 2, "rfq_number", "HB-EV7-EWP-001",
                "model_id", "EWP-12-B", "unit_price_krw", 37300,
                "price_by_year", List.of(Map.of("year", 1, "unit_price_krw", 37300)));

        backend.expect(requestTo(BACKEND + "/internal/quotes"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code": "CONFLICT_STATE", "message": "REVISE 승인 후 다음 revision을 저장할 수 있습니다.",
                         "details": [], "timestamp": "2026-10-21T01:00:00Z"}
                        """));
        assertThat(mcp.callError("save_quote", args)).contains("현재 상태에서 불가", "REVISE 승인 후");

        backend.reset();
        backend.expect(requestTo(BACKEND + "/internal/quotes")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(mcp.callError("save_quote", args)).contains("save_review를 같은 thread_id·rfq_number로 먼저 호출");

        // 400 검증 오류는 필드별 사유(details)를 함께 보여준다
        backend.reset();
        backend.expect(requestTo(BACKEND + "/internal/quotes"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code": "VALIDATION_ERROR", "message": "요청 값이 올바르지 않습니다.",
                         "details": ["price_by_year: 비어 있을 수 없습니다"], "timestamp": "2026-10-21T01:00:00Z"}
                        """));
        assertThat(mcp.callError("save_quote", args))
                .contains("400", "요청 값이 올바르지 않습니다.", "price_by_year: 비어 있을 수 없습니다");
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
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"quote_id": "Q-2026-1019-001", "status": "REGISTERED", "rfq_status": "QUOTED",
                         "outcome": "CREATED"}
                        """));

        JsonNode result = mcp.callOk("register_quote", Map.of("quote_id", "Q-2026-1019-001", "thread_id", "th_1"));

        assertThat(result.toString()).isEqualTo("{\"quote_id\":\"Q-2026-1019-001\",\"status\":\"REGISTERED\","
                + "\"rfq_status\":\"QUOTED\",\"outcome\":\"CREATED\"}");
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
                        .body("{\"code\": \"NOT_FOUND\", \"message\": \"견적이 없습니다: Q-1\"}"));
        assertThat(mcp.callError("register_quote", args)).contains("404", "견적이 없습니다: Q-1");

        backend.reset();
        backend.expect(requestTo(BACKEND + "/internal/quotes/Q-1/register")).andRespond(withServerError());
        assertThat(mcp.callError("register_quote", args)).contains("백엔드 오류", "500");

        backend.reset();
        backend.expect(requestTo(BACKEND + "/internal/quotes/Q-1/register"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThat(mcp.callError("register_quote", args)).contains("백엔드 오류", "시간 초과");
    }

    /** RFQ 정보(JSON)와 원문(text/markdown, 01_rfq/RFQ_HB-EV7-EWP-001.md)을 합쳐 원문을 그대로 반환한다. */
    @Test
    void getRfqContent() throws IOException {
        String original;
        try (InputStream in = getClass().getResourceAsStream("/expected/RFQ_HB-EV7-EWP-001.md")) {
            original = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        backend.expect(requestTo(BACKEND + "/internal/rfqs/HB-EV7-EWP-001"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Api-Key", McpTestClient.API_KEY))
                .andRespond(withSuccess("""
                        {"rfq_id": 1, "rfq_number": "HB-EV7-EWP-001", "customer_company_id": 1,
                         "item": "전동식 워터펌프 (EWP, 12V)", "status": "RECEIVED",
                         "file_path": "01_rfq/RFQ_HB-EV7-EWP-001.md", "eau": null}
                        """, MediaType.APPLICATION_JSON));
        backend.expect(requestTo(BACKEND + "/internal/rfqs/HB-EV7-EWP-001/content"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Api-Key", McpTestClient.API_KEY))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withSuccess(original, MediaType.parseMediaType("text/markdown;charset=UTF-8")));

        JsonNode result = mcp.callOk("get_rfq_content", Map.of("rfq_number", "HB-EV7-EWP-001"));

        assertThat(result.get("rfq_number").asText()).isEqualTo("HB-EV7-EWP-001");
        assertThat(result.get("item").asText()).isEqualTo("전동식 워터펌프 (EWP, 12V)");
        assertThat(result.get("file_name").asText()).isEqualTo("RFQ_HB-EV7-EWP-001.md");
        assertThat(result.get("content").asText()).isEqualTo(original);
        backend.verify();
    }

    @Test
    void getRfqContentErrors() {
        var args = Map.<String, Object>of("rfq_number", "HB-NONE");
        String info = BACKEND + "/internal/rfqs/HB-NONE";
        String infoBody = "{\"rfq_number\": \"HB-NONE\", \"file_path\": \"01_rfq/HB-NONE.md\"}";

        backend.expect(requestTo(info)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(mcp.callError("get_rfq_content", args)).contains("RFQ를 찾을 수 없음", "HB-NONE");

        backend.reset();
        backend.expect(requestTo(info)).andRespond(withSuccess(infoBody, MediaType.APPLICATION_JSON));
        backend.expect(requestTo(info + "/content")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(mcp.callError("get_rfq_content", args)).contains("RFQ 원문이 없거나 읽을 수 없음", "HB-NONE");

        backend.reset();
        backend.expect(requestTo(info)).andRespond(withSuccess(infoBody, MediaType.APPLICATION_JSON));
        backend.expect(requestTo(info + "/content"))
                .andRespond(withSuccess("가".repeat(80_000), MediaType.parseMediaType("text/markdown;charset=UTF-8")));
        assertThat(mcp.callError("get_rfq_content", args)).contains("200KB");
    }

    @Test
    void missingIdsAreRejectedWithoutCallingBackend() {
        assertThat(mcp.callError("register_quote", Map.of("quote_id", " ", "thread_id", "th_1"))).contains("quote_id");
        assertThat(mcp.callError("save_review", Map.of("thread_id", "th_1", "rfq_number", "R", "stage", "")))
                .contains("stage");
        assertThat(mcp.callError("save_review", Map.of("thread_id", "th_1", "rfq_number", "R", "stage", "SG3")))
                .contains("SG1 또는 SG2");
        assertThat(mcp.callError("save_quote", Map.of("thread_id", "th_1", "rfq_number", "R", "model_id", "M",
                "unit_price_krw", 1, "price_by_year", List.of()))).contains("revision");
        assertThat(mcp.callError("get_rfq_content", Map.of("rfq_number", ""))).contains("rfq_number");
        backend.verify();
    }
}
