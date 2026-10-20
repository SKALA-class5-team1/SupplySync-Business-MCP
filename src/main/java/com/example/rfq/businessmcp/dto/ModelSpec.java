/*
 * [DTO] get_models 출력의 모델 한 개 (사양 + 기본 단가). JSON 필드는 snake_case.
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelSpec(
        String modelId,
        String modelName,
        BigDecimal flowLpm,
        int headKpa,
        BigDecimal voltageMin,
        BigDecimal voltageMax,
        int tempMin,
        int tempMax,
        String ipRating,
        int noiseDb,
        String comm,
        // SnakeCaseStrategy는 연속 대문자(LMm)를 size_lmm으로 바꾸므로 이름을 직접 지정
        @JsonProperty("size_l_mm") int sizeLMm,
        @JsonProperty("size_w_mm") int sizeWMm,
        @JsonProperty("size_h_mm") int sizeHMm,
        BigDecimal weightKg,
        int basePriceKrw) {
}
