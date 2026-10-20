/*
 * [DTO] register_quote가 백엔드로 보내는 요청 본문 (quote_id, thread_id).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** POST /internal/quotes/{quote_id}/register 본문. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegisterQuoteRequest(String quoteId, String threadId) {
}
