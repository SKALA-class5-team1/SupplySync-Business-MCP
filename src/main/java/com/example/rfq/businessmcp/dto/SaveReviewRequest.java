/*
 * [DTO] save_review가 백엔드로 보내는 요청 본문 (SG1 또는 SG2 분석 결과).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;

/** PUT /internal/reviews/{thread_id} 본문. 해당 stage에 없는 필드는 null로 두고 전송하지 않는다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveReviewRequest(
        String threadId,
        String rfqNumber,
        String stage,
        String selectedModel,
        List<String> options,
        String fitType,
        List<Object> issues,
        List<Object> passedChecks,
        List<Object> assumptions,
        List<Object> customerQuestions,
        List<Object> contractRisks,
        List<Object> shortages,
        List<Object> alternatives,
        String recommendedAlt,
        Map<String, Object> finalPlan) {
}
