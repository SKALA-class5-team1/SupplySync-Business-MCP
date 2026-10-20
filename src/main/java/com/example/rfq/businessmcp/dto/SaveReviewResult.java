/*
 * [DTO] save_review 출력 = 백엔드 응답 (review_id, thread_id, status).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SaveReviewResult(
        Long reviewId,
        String threadId,
        String status) {
}
