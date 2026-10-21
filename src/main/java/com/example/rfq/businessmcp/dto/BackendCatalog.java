/*
 * [DTO] 백엔드 조회 API(/internal/models·/internal/parts) 응답. 백엔드 CatalogDto와 필드가 같다.
 * 조회 Tool 5개와 get_substitutes가 이 응답을 Tool 출력 형식으로 바꾼다.
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 읽지 않는 필드는 무시한다 (ObjectMapper 기본 설정). */
public final class BackendCatalog {
    private BackendCatalog() {
    }

    /** GET /internal/models, /internal/models/{modelId}. options는 모델에 붙일 수 있는 옵션(model_options). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Model(
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
            // 백엔드는 SnakeCaseStrategy로 sizeLMm을 size_lmm으로 내보내므로 두 이름을 모두 받는다
            @JsonProperty("size_l_mm") @JsonAlias("size_lmm") int sizeLMm,
            @JsonProperty("size_w_mm") @JsonAlias("size_wmm") int sizeWMm,
            @JsonProperty("size_h_mm") @JsonAlias("size_hmm") int sizeHMm,
            BigDecimal weightKg,
            int basePriceKrw,
            List<OptionInfo> options) {
    }

    /** GET /internal/models/{modelId}/bom?options=... 옵션 교체는 백엔드가 적용한다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Bom(
            String modelId,
            List<String> options,
            List<BomLine> items) {
    }

    /** source: BASE 또는 OPTION:{option_code}. replaced_part_id는 옵션으로 교체된 경우에만. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BomLine(
            String partId,
            String partName,
            int qtyPerUnit,
            String source,
            String replacedPartId) {
    }

    /** GET /internal/parts/{partId}. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Part(
            String partId,
            String partName,
            String supplier,
            int unitCostKrw,
            int leadTimeDays,
            @JsonProperty("is_critical") boolean isCritical) {
    }

    /** GET /internal/parts/{partId}/inventory. available = on_hand - allocated (백엔드 계산). 입고 예정은 부품당 1건. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Inventory(
            String partId,
            int onHand,
            int allocated,
            int available,
            int incomingQty,
            LocalDate incomingDate) {
    }

    /** GET /internal/parts/{partId}/substitutes 한 건. 단가 차이·가용 재고는 백엔드가 계산해 준다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Substitute(
            String partId,
            String substitutePartId,
            String substituteName,
            String substituteSupplier,
            int validationDays,
            boolean pinCompatible,
            String refChunkId,
            int unitCostDiffKrw,
            int availableQty) {
    }
}
