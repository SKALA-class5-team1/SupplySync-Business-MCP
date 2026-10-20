/*
 * [DTO] check_parts_supply 출력 (납기, 기준일, 위험 여부, 부품별 결과).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SupplyCheckResult(
        String dueDate,
        String baseDate,
        boolean hasRisk,
        List<PartSupply> parts) {
}
