/*
 * [DTO] save_review 출력 = 백엔드 응답 (review_id, rfq_number, thread_id, status, updated_at).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SaveReviewResult(
        Long reviewId,
        String rfqNumber,
        String threadId,
        String status,
        String updatedAt) {
}
