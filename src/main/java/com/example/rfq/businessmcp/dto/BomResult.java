/*
 * [DTO] get_bom 출력 (모델, 옵션, 생산 수량, 부품 목록).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BomResult(
        String modelId,
        List<String> options,
        int qty,
        List<BomPart> parts) {
}
