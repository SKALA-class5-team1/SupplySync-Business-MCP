/*
 * [DTO] get_parts_cost 출력의 부품별 원가 한 줄 (cost_krw = unit_cost_krw × qty_per_unit).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CostLine(
        String partId,
        int qtyPerUnit,
        int unitCostKrw,
        int costKrw) {
}
