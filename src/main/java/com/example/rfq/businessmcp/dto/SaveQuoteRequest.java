/*
 * [DTO] save_quote가 백엔드로 보내는 요청 본문 (견적 초안).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.util.List;

/** PUT /internal/quotes/{thread_id} 본문. 6_sg3_quote.json의 quote_draft(quote_id 제외) + thread_id, review_id. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveQuoteRequest(
        String threadId,
        Long reviewId,
        String rfqNumber,
        String recipient,
        String item,
        String modelId,
        List<String> options,
        Integer unitPriceKrw,
        List<Object> priceBreakdown,
        Integer partsCostKrw,
        List<Object> partsCostBreakdown,
        BigDecimal marginPct,
        List<Object> priceByYear,
        Integer eau,
        Integer sampleQty,
        String sampleDueDate,
        String sopDate,
        String incoterms,
        String issueDate,
        String validUntil,
        List<Object> conditions,
        List<Object> checks) {
}
