/*
 * [DTO] get_bom 출력의 부품 한 줄. 정답 파일 5_sg2_supply.json의 bom_effective와 필드명이 같다.
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** source: BASE 또는 OPTION:{option_code}. replaced_part_id는 옵션으로 교체된 경우에만. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BomPart(
        String partId,
        String partName,
        int qtyPerUnit,
        int requiredQty,
        String source,
        @JsonInclude(JsonInclude.Include.NON_NULL) String replacedPartId) {
}
