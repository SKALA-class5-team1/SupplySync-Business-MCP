/*
 * [DTO] get_substitutes 출력 (원래 부품 + 대체 부품 후보 목록).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** substitutes는 check_parts_supply의 substitutes와 같은 형식. 후보가 없으면 빈 배열. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubstituteResult(
        String partId,
        String partName,
        int unitCostKrw,
        List<SubstituteSupply> substitutes) {
}
