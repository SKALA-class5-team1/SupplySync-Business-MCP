/*
 * [DTO] 옵션 한 개 (바뀌는 사양, 추가 무게·소음·단가, BOM 교체 부품). get_options 출력이자 BOM 전개 입력.
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptionInfo(
        String optionCode,
        String optionName,
        String changesField,
        String newValue,
        BigDecimal addWeightKg,
        int addNoiseDb,
        int addPriceKrw,
        String replacesPartId,
        String usesPartId) {
}
