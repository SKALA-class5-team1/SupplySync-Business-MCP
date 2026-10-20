/*
 * [DTO] check_parts_supply 출력의 대체 부품 한 개 (부족한 부품에만 채운다).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubstituteSupply(
        String substitutePartId,
        String partName,
        String supplier,
        int available,
        boolean coversShortage,
        int unitCostKrw,
        int unitCostDiffKrw,
        int validationDays,
        String usableDate,
        int leadTimeDays,
        boolean pinCompatible,
        String refChunkId) {
}
