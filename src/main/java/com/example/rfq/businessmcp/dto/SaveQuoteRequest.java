/*
 * [DTO] save_quote가 백엔드로 보내는 요청 본문 (견적 초안).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * POST /internal/quotes 본문. 6_sg3_quote.json의 quote_draft(quote_id·status 제외) + thread_id, revision.
 * 백엔드가 thread_id로 분석 결과(review)를 찾고, recipient·incoterms·price_breakdown·parts_cost_breakdown은 quotes.details에 모은다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveQuoteRequest(
        String threadId,
        Integer revision,
        String rfqNumber,
        String recipient,
        String item,
        String modelId,
        List<String> options,
        Integer unitPriceKrw,
        List<Map<String, Object>> priceBreakdown,
        Integer partsCostKrw,
        List<Map<String, Object>> partsCostBreakdown,
        BigDecimal marginPct,
        List<Map<String, Object>> priceByYear,
        Integer eau,
        Integer sampleQty,
        String sampleDueDate,
        String sopDate,
        String incoterms,
        String issueDate,
        String validUntil,
        List<Map<String, Object>> conditions,
        List<Map<String, Object>> checks) {
}
