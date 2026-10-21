/*
 * [DTO] save_review가 백엔드로 보내는 요청 본문 (SG1 또는 SG2 분석 결과).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;

/**
 * PUT /internal/rfq-reviews 본문. 백엔드는 (rfq_number, thread_id)로 upsert하고 전달한 필드만 갱신하므로
 * 해당 단계에 없는 필드는 null로 두고 전송하지 않는다. stage는 백엔드에 없는 값이라 보내지 않는다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveReviewRequest(
        String rfqNumber,
        String threadId,
        String status,
        String selectedModel,
        List<String> options,
        String fitType,
        List<Map<String, Object>> issues,
        List<String> passedChecks,
        List<Map<String, Object>> assumptions,
        List<String> customerQuestions,
        List<Map<String, Object>> contractRisks,
        List<Map<String, Object>> shortages,
        List<Map<String, Object>> alternatives,
        String recommendedAlt,
        Map<String, Object> finalPlan) {
}
