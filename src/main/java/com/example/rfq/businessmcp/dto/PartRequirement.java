/*
 * [DTO] check_parts_supply 입력의 부품 한 줄 (part_id, required_qty).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PartRequirement(@JsonPropertyDescription("부품 ID (get_bom 결과의 part_id)") String partId, @JsonPropertyDescription("필요 수량 (get_bom 결과의 required_qty, 1 이상)") Integer requiredQty) {
}
