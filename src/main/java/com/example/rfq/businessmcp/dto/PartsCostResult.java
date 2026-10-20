/*
 * [DTO] get_parts_cost 출력 (부품 원가 합계 + 부품별 내역). 정답 파일 6_sg3_quote.json과 필드명이 같다.
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PartsCostResult(
        String modelId,
        List<String> options,
        int partsCostKrw,
        List<CostLine> partsCostBreakdown) {
}
