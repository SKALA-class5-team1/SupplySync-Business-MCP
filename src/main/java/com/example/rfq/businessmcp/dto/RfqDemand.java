/*
 * [DTO] save_review의 demand 입력 = 백엔드 PATCH /internal/rfqs/{rfqNumber} 본문 (SG1이 RFQ에서 추출한 요구값).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 백엔드는 전달한 필드만 rfqs에 갱신한다. 날짜는 YYYY-MM-DD. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RfqDemand(
        Integer eau,
        Integer sampleQty,
        String sampleDueDate,
        String sopDate,
        Integer targetPriceKrw,
        String quoteDueDate) {
}
