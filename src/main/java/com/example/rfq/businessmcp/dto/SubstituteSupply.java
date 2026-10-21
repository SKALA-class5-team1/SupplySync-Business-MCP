/*
 * [DTO] 대체 부품 한 개. check_parts_supply(부족한 부품에만)와 get_substitutes 출력이 같은 형식을 쓴다.
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** covers_shortage: 대체 부품 available >= short_qty. get_substitutes에서 short_qty를 주지 않으면 null. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubstituteSupply(
        String substitutePartId,
        String partName,
        String supplier,
        int available,
        Boolean coversShortage,
        int unitCostKrw,
        int unitCostDiffKrw,
        int validationDays,
        String usableDate,
        int leadTimeDays,
        boolean pinCompatible,
        String refChunkId) {
}
