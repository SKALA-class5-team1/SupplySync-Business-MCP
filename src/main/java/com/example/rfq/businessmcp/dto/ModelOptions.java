/*
 * [DTO] get_options 출력 (모델 기본 단가 + 붙일 수 있는 옵션 목록).
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelOptions(
        String modelId,
        int basePriceKrw,
        List<OptionInfo> options) {
}
