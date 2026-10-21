/*
 * [DTO] 백엔드 GET /internal/rfqs/{rfqNumber} 응답 중 get_rfq_content가 쓰는 필드.
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 나머지 필드(rfq_id, status, 추출 컬럼 등)는 읽지 않는다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RfqInfo(
        String rfqNumber,
        String item,
        String filePath) {
}
