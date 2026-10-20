/*
 * [DTO] get_rfq_content 출력 = 백엔드 응답 (RFQ 메타데이터 + 원문).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** GET /internal/rfqs/{rfqNumber}/content 응답. content는 원문 그대로. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RfqContent(
        String rfqNumber,
        String customer,
        String item,
        String fileName,
        String contentType,
        String receivedAt,
        String content) {
}
