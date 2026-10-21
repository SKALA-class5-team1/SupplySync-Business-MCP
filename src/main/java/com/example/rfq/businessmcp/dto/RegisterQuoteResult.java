/*
 * [DTO] register_quote 출력 = 백엔드 응답 (quote_id, status, rfq_status, outcome).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegisterQuoteResult(
        String quoteId,
        String status,
        String rfqStatus,
        String outcome) {
}
