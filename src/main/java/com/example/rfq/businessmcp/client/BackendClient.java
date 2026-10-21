/*
 * [외부 호출] Spring 백엔드 /internal/** HTTP 클라이언트 (RestClient).
 * API 키 헤더(INTERNAL_API_KEY, 이 서버가 받는 키와 같은 값), 타임아웃(연결 3초·응답 10초), 응답 200KB 제한, HTTP 상태 → Tool 오류 메시지 변환을 담당한다. 재시도는 하지 않는다.
 * 응답 타입이 String이면 본문을 텍스트 그대로 돌려준다 (RFQ 원문은 text/markdown).
 */
package com.example.rfq.businessmcp.client;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Spring 백엔드 /internal/** 호출. 재시도는 하지 않는다 (재시도 여부는 클라이언트가 판단, 오류 메시지에 HTTP 상태 코드 포함).
 * 타임아웃은 application.yml의 spring.http.client.* (연결 3초, 응답 10초).
 */
@Component
public class BackendClient {
    private static final Logger log = LoggerFactory.getLogger(BackendClient.class);
    /** LLM 컨텍스트를 넘치지 않도록 응답 크기를 제한한다 (RFQ 원문 기준, 데모 RFQ는 수 KB). */
    static final int MAX_RESPONSE_BYTES = 200 * 1024;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public BackendClient(RestClient.Builder builder, ObjectMapper objectMapper,
                         @Value("${backend.url}") String baseUrl, @Value("${internal.api-key}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    // statusMessages: 상태 코드별 Tool 오류 메시지. 없는 코드는 4xx "백엔드 요청 오류", 그 밖은 "백엔드 오류"

    public <T> T get(String uriTemplate, String uriVariable, Class<T> responseType,
                     Map<Integer, String> statusMessages) {
        return send(HttpMethod.GET, uriTemplate, uriVariable, null, null, responseType, statusMessages);
    }

    /** uriVariable이 없는 경로면 null을 넘긴다. */
    public <T> T put(String uriTemplate, String uriVariable, Object body, Class<T> responseType,
                     Map<Integer, String> statusMessages) {
        return send(HttpMethod.PUT, uriTemplate, uriVariable, null, body, responseType, statusMessages);
    }

    public <T> T patch(String uriTemplate, String uriVariable, Object body, Class<T> responseType,
                       Map<Integer, String> statusMessages) {
        return send(HttpMethod.PATCH, uriTemplate, uriVariable, null, body, responseType, statusMessages);
    }

    public <T> T post(String uriTemplate, String uriVariable, String idempotencyKey, Object body,
                      Class<T> responseType, Map<Integer, String> statusMessages) {
        return send(HttpMethod.POST, uriTemplate, uriVariable, idempotencyKey, body, responseType, statusMessages);
    }

    /** idempotencyKey·body가 null이면 해당 헤더·본문을 보내지 않는다. */
    private <T> T send(HttpMethod method, String uriTemplate, String uriVariable, String idempotencyKey,
                       Object body, Class<T> responseType, Map<Integer, String> statusMessages) {
        RestClient.RequestBodySpec request = restClient.method(method)
                .uri(uriTemplate, uriVariable)
                .header("X-Internal-Api-Key", apiKey);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        try {
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                byte[] bytes = readLimited(response.getBody());
                if (bytes == null) {
                    throw new BackendException("백엔드 응답이 " + MAX_RESPONSE_BYTES / 1024 + "KB를 넘어 반환할 수 없습니다.");
                }
                if (!response.getStatusCode().is2xxSuccessful()) {
                    String detail = extractMessage(bytes, response.getStatusText());
                    log.warn("백엔드 {} {} 실패: {} {}", method, uriTemplate, status, detail);
                    String known = statusMessages.get(status);
                    if (known != null) {
                        throw new BackendException(known + " (백엔드: " + detail + ")");
                    }
                    if (response.getStatusCode().is4xxClientError()) {
                        throw new BackendException("백엔드 요청 오류 (" + status + "): " + detail);
                    }
                    throw new BackendException("백엔드 오류 (" + status + "): " + detail);
                }
                if (bytes.length == 0) {
                    throw new BackendException("백엔드 오류: 응답 본문이 비어 있습니다.");
                }
                if (responseType == String.class) {
                    return responseType.cast(new String(bytes, StandardCharsets.UTF_8));
                }
                try {
                    return objectMapper.readValue(bytes, responseType);
                } catch (JacksonException e) {
                    log.warn("백엔드 {} {} 응답 해석 실패", method, uriTemplate, e);
                    throw new BackendException("백엔드 오류: 응답을 해석할 수 없습니다.");
                }
            });
        } catch (ResourceAccessException e) {
            log.warn("백엔드 {} {} 연결 실패", method, uriTemplate, e);
            throw new BackendException("백엔드 오류: 연결 실패 또는 응답 시간 초과");
        } catch (RestClientException e) {
            log.warn("백엔드 {} {} 호출 실패", method, uriTemplate, e);
            throw new BackendException("백엔드 오류: 요청을 처리할 수 없습니다.");
        }
    }

    /** 최대 크기까지만 읽는다. 넘으면 null. */
    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
        return bytes.length > MAX_RESPONSE_BYTES ? null : bytes;
    }

    /**
     * 백엔드 오류 응답({code, message, details, timestamp})에서 message / detail / error 순으로 메시지를 꺼낸다. 없으면 본문 그대로.
     * details(필드별 사유 배열)가 있으면 뒤에 붙인다 (Agent가 입력을 고칠 수 있도록).
     */
    private String extractMessage(byte[] bytes, String statusText) {
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (!StringUtils.hasText(body)) {
            return statusText;
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            for (String field : new String[]{"message", "detail", "error"}) {
                if (json.hasNonNull(field) && StringUtils.hasText(json.get(field).asText())) {
                    return truncate(json.get(field).asText() + details(json.get("details")));
                }
            }
        } catch (JacksonException ignored) {
            // JSON이 아니면 본문 그대로 사용
        }
        return truncate(body);
    }

    private static String details(JsonNode details) {
        if (details == null || !details.isArray() || details.isEmpty()) {
            return "";
        }
        List<String> reasons = new ArrayList<>();
        details.forEach(reason -> reasons.add(reason.asText()));
        return " " + reasons;
    }

    private static String truncate(String text) {
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }
}
