/*
 * [테스트] 조회 Tool 6개 통합 테스트. MockRestServiceServer로 백엔드 조회 API를 흉내 내고(FakeBackend, v2.2 시드 fixture)
 * MCP tools/call 결과를 정답 파일과 비교한다 (ReadToolsContract). 호출 경로·쿼리·헤더와 호출 횟수도 확인한다.
 */
package com.example.rfq.businessmcp;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.MockServerRestClientCustomizer;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.UnorderedRequestExpectationManager;

/** 백엔드 없이 실행. 같은 검증을 실제 백엔드로 하려면 LiveBackendToolsTest. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "internal.api-key=" + McpTestClient.API_KEY,
        "backend.url=" + FakeBackend.URL
})
class BusinessToolsIntegrationTest extends ReadToolsContract {
    @LocalServerPort int port;
    @Autowired MockServerRestClientCustomizer restClientMock;
    MockRestServiceServer server;
    FakeBackend backend;

    /** 한 Tool 호출이 백엔드를 여러 번 부르므로 요청 순서는 따지지 않는다. */
    @TestConfiguration
    static class UnorderedBackend {
        @Bean
        MockServerRestClientCustomizer restClientMock() {
            return new MockServerRestClientCustomizer(UnorderedRequestExpectationManager::new);
        }
    }

    @Override
    McpTestClient connect() {
        return McpTestClient.connect(port);
    }

    @BeforeEach
    void serveFixtures() throws IOException {
        server = restClientMock.getServer();
        server.reset();
        backend = new FakeBackend(server);
        backend.serveAll();
    }

    /**
     * 부품 6개 × (부품 마스터 + 재고) + 부족한 P-MCU-G1만 (대체 후보 + 후보 부품 마스터) = 14회.
     * 각 경로가 정확히 한 번씩만 호출되고, 그 밖의 요청은 없어야 한다 (대체 부품 재고는 /substitutes 응답 값을 쓴다).
     */
    @Test
    void checkPartsSupplyCallsEachBackendApiOnce() throws IOException {
        server.reset();
        List<String> parts = List.of("P-CON-4P", "P-HSG-AL", "P-IMP-25", "P-MCU-G1", "P-MTR-BL12", "P-SEAL-9K");
        for (String part : parts) {
            backend.serveOnce("parts/" + part + ".json");
            backend.serveOnce("inventory/" + part + ".json");
        }
        backend.serveOnce("substitutes/P-MCU-G1.json");
        backend.serveOnce("parts/P-MCU-G1-ALT.json");

        mcp.callOk("check_parts_supply", Map.of("due_date", "2026-12-15", "base_date", "2026-10-19",
                "parts", parts.stream().map(p -> Map.<String, Object>of("part_id", p, "required_qty", 2000)).toList()));

        server.verify();
    }

    /** 옵션은 options=OPT-HT,OPT-IP9K 쿼리 하나로 보내고, 옵션이 없으면 쿼리를 붙이지 않는다. */
    @Test
    void getBomSendsOptionsQuery() throws IOException {
        server.reset();
        backend.serveOnce("bom/EWP-12-B+OPT-HT+OPT-IP9K.json");
        backend.serveOnce("bom/EWP-12-B.json");

        mcp.callOk("get_bom", Map.of("model_id", "EWP-12-B", "options", B_OPTIONS, "qty", 1));
        mcp.callOk("get_bom", Map.of("model_id", "EWP-12-B", "options", List.of(), "qty", 1));

        server.verify();
    }
}
