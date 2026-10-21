/*
 * [DTO] get_rfq_content 출력 (RFQ 메타데이터 + 원문). 백엔드 API 2개(RFQ 정보, 원문)의 결과를 합친다.
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** content는 GET /internal/rfqs/{rfqNumber}/content의 원문(text/markdown) 그대로. file_name은 rfqs.file_path의 파일 이름. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RfqContent(
        String rfqNumber,
        String item,
        String fileName,
        String content) {
}
