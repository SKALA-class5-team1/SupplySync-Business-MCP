/*
 * [DTO] save_quote 출력 = 백엔드 응답 (quote_id, status, review_id).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SaveQuoteResult(
        String quoteId,
        String status,
        Long reviewId) {
}
