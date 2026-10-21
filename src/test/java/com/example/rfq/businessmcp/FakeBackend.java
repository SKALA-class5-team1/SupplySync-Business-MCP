/*
 * [테스트 공통] 백엔드 조회 API 흉내. src/test/resources/backend/의 응답 fixture(v2.2 시드, 백엔드 응답 형식 그대로)를
 * 요청 URL에 맞춰 돌려준다. MockRestServiceServer(순서 무관)에 기대 요청을 등록한다.
 */
package com.example.rfq.businessmcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * fixture 파일 이름 → 백엔드 경로:
 * models.json → /internal/models, models/{id}.json → /internal/models/{id},
 * bom/{id}.json → /internal/models/{id}/bom, bom/{id}+{opt}+{opt}.json → /internal/models/{id}/bom?options={opt},{opt},
 * parts/{id}.json → /internal/parts/{id}, inventory/{id}.json → .../inventory, substitutes/{id}.json → .../substitutes
 */
final class FakeBackend {
    static final String URL = "http://backend.test";
    private static final ExpectedCount ANY = ExpectedCount.between(0, Integer.MAX_VALUE);

    private final MockRestServiceServer server;

    FakeBackend(MockRestServiceServer server) {
        this.server = server;
    }

    /** fixture 전체와 오류 응답을 횟수 제한 없이 등록한다 (호출하지 않아도 된다). */
    void serveAll() throws IOException {
        Resource[] fixtures = new PathMatchingResourcePatternResolver().getResources("classpath:backend/**/*.json");
        for (Resource fixture : fixtures) {
            String file = fixture.getURL().getPath().replaceFirst(".*/backend/", "");
            serve(ANY, path(file), file);
        }
        fail(ANY, "/internal/models/EWP-99", HttpStatus.NOT_FOUND, "없는 모델입니다: EWP-99");
        fail(ANY, "/internal/models/EWP-99/bom", HttpStatus.NOT_FOUND, "없는 모델입니다: EWP-99");
        fail(ANY, "/internal/models/EWP-12-A/bom?options=OPT-IP9K", HttpStatus.BAD_REQUEST,
                "모델에 적용할 수 없는 옵션입니다: OPT-IP9K");
        fail(ANY, "/internal/parts/P-NONE", HttpStatus.NOT_FOUND, "없는 부품입니다: P-NONE");
    }

    /** fixture 응답 하나를 등록한다. pathAndQuery는 디코딩된 경로·쿼리 (예: /internal/models/EWP-12-B/bom?options=OPT-HT,OPT-IP9K). */
    void serve(ExpectedCount count, String pathAndQuery, String fixture) {
        server.expect(count, requestTo(pathAndQuery))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Api-Key", McpTestClient.API_KEY))
                .andRespond(withSuccess(new ClassPathResource("backend/" + fixture), MediaType.APPLICATION_JSON));
    }

    /** fixture 이름으로 경로를 정해 한 번만 호출되어야 하는 응답을 등록한다. */
    void serveOnce(String fixture) {
        serve(ExpectedCount.once(), path(fixture), fixture);
    }

    /** 백엔드 공통 오류 응답 {code, message, details, timestamp}. */
    void fail(ExpectedCount count, String pathAndQuery, HttpStatus status, String message) {
        server.expect(count, requestTo(pathAndQuery))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code": "%s", "message": "%s", "details": [], "timestamp": "2026-10-21T01:00:00Z"}
                        """.formatted(status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : "VALIDATION_ERROR", message)));
    }

    static String path(String fixture) {
        String name = fixture.replaceFirst("\\.json$", "");
        String[] dirAndId = name.split("/", 2);
        if (dirAndId.length == 1) {
            return "/internal/" + name;
        }
        String id = dirAndId[1];
        return switch (dirAndId[0]) {
            case "models" -> "/internal/models/" + id;
            case "bom" -> {
                String[] modelAndOptions = id.split("\\+", 2);
                yield "/internal/models/" + modelAndOptions[0] + "/bom"
                        + (modelAndOptions.length == 1 ? "" : "?options=" + modelAndOptions[1].replace('+', ','));
            }
            case "parts" -> "/internal/parts/" + id;
            case "inventory" -> "/internal/parts/" + id + "/inventory";
            case "substitutes" -> "/internal/parts/" + id + "/substitutes";
            default -> throw new IllegalArgumentException("알 수 없는 fixture: " + fixture);
        };
    }

    /** 인코딩과 무관하게 디코딩한 경로·쿼리로 비교한다 (쉼표가 %2C로 인코딩돼도 같은 요청). */
    private static RequestMatcher requestTo(String pathAndQuery) {
        return request -> {
            URI uri = request.getURI();
            String actual = decode(uri.getRawPath()) + (uri.getRawQuery() == null ? "" : "?" + decode(uri.getRawQuery()));
            assertThat(uri.getScheme() + "://" + uri.getAuthority()).isEqualTo(URL);
            assertThat(actual).isEqualTo(pathAndQuery);
        };
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
