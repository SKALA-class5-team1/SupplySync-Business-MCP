/*
 * [서비스] 모델·옵션 조회, 옵션 적용 BOM 조회, 부품 원가 합계 계산. 데이터는 백엔드 조회 API에서 가져온다.
 * get_models, get_options, get_bom, get_parts_cost가 사용한다. BOM 전개(옵션 부품 교체)는 백엔드가 하고, 이 서버는 다시 계산하지 않는다.
 */
package com.example.rfq.businessmcp.service;

import com.example.rfq.businessmcp.client.CatalogClient;
import com.example.rfq.businessmcp.dto.BackendCatalog.Bom;
import com.example.rfq.businessmcp.dto.BackendCatalog.BomLine;
import com.example.rfq.businessmcp.dto.BackendCatalog.Model;
import com.example.rfq.businessmcp.dto.BomPart;
import com.example.rfq.businessmcp.dto.BomResult;
import com.example.rfq.businessmcp.dto.CostLine;
import com.example.rfq.businessmcp.dto.ModelOptions;
import com.example.rfq.businessmcp.dto.ModelSpec;
import com.example.rfq.businessmcp.dto.PartsCostResult;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

/** 모델·옵션 조회와 옵션 적용 BOM, 부품 원가 계산. */
@Service
public class ProductService {
    private final CatalogClient catalogClient;

    public ProductService(CatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    /** 옵션을 적용한 BOM. lines는 part_id 순. */
    public record BomExpansion(
            String modelId,
            List<String> options,
            List<BomLine> lines) {
    }

    public List<ModelSpec> getModels() {
        return catalogClient.models().stream()
                .sorted(Comparator.comparing(Model::modelId))
                .map(ProductService::toSpec)
                .toList();
    }

    public ModelOptions getOptions(String modelId) {
        Inputs.requireText("model_id", modelId);
        Model model = catalogClient.model(modelId);
        return new ModelOptions(model.modelId(), model.basePriceKrw(), model.options() == null ? List.of() : model.options());
    }

    public BomResult getBom(String modelId, List<String> optionCodes, Integer qty) {
        int productionQty = Inputs.requirePositive("qty", qty);
        BomExpansion bom = expandBom(modelId, optionCodes);
        List<BomPart> parts = bom.lines().stream()
                .map(line -> new BomPart(
                        line.partId(),
                        line.partName(),
                        line.qtyPerUnit(),
                        Math.multiplyExact(line.qtyPerUnit(), productionQty),
                        line.source(),
                        line.replacedPartId()))
                .toList();
        return new BomResult(bom.modelId(), bom.options(), productionQty, parts);
    }

    /** BOM 부품마다 단가를 조회한다 (백엔드 BOM 응답에 단가가 없음). */
    public PartsCostResult getPartsCost(String modelId, List<String> optionCodes) {
        BomExpansion bom = expandBom(modelId, optionCodes);
        List<CostLine> breakdown = bom.lines().stream()
                .map(line -> {
                    int unitCost = catalogClient.part(line.partId()).unitCostKrw();
                    return new CostLine(
                            line.partId(),
                            line.qtyPerUnit(),
                            unitCost,
                            Math.multiplyExact(unitCost, line.qtyPerUnit()));
                })
                .toList();
        int total = breakdown.stream().mapToInt(CostLine::costKrw).reduce(0, Math::addExact);
        return new PartsCostResult(bom.modelId(), bom.options(), total, breakdown);
    }

    /**
     * get_bom, get_parts_cost 공통: 백엔드의 옵션 적용 BOM.
     * 모델 존재·옵션 조합 검사와 부품 교체는 백엔드가 한다. bom에 순서 컬럼이 없어 part_id 순으로 고정한다.
     */
    public BomExpansion expandBom(String modelId, List<String> optionCodes) {
        Inputs.requireText("model_id", modelId);
        List<String> codes = normalizeOptions(optionCodes);
        Bom bom = catalogClient.bom(modelId, codes);
        if (bom.items() == null || bom.items().isEmpty()) {
            throw new IllegalArgumentException("모델 " + modelId + "의 BOM이 없습니다.");
        }
        List<BomLine> lines = bom.items().stream().sorted(Comparator.comparing(BomLine::partId)).toList();
        return new BomExpansion(modelId, codes, lines);
    }

    /** 같은 옵션이 중복으로 오면 하나로 합친다 (입력 순서 유지). 빈 코드는 오류. */
    static List<String> normalizeOptions(List<String> optionCodes) {
        if (optionCodes == null) {
            return List.of();
        }
        optionCodes.forEach(code -> Inputs.requireText("options의 옵션 코드", code));
        return List.copyOf(new LinkedHashSet<>(optionCodes));
    }

    private static ModelSpec toSpec(Model m) {
        return new ModelSpec(
                m.modelId(),
                m.modelName(),
                m.flowLpm(),
                m.headKpa(),
                stripZeros(m.voltageMin()),
                stripZeros(m.voltageMax()),
                m.tempMin(),
                m.tempMax(),
                m.ipRating(),
                m.noiseDb(),
                m.comm(),
                m.sizeLMm(),
                m.sizeWMm(),
                m.sizeHMm(),
                m.weightKg(),
                m.basePriceKrw());
    }

    /** NUMERIC(4,1) 전압(백엔드 응답 9.0)을 명세대로 9로 내보낸다. 13.5처럼 소수가 있으면 유지. */
    static BigDecimal stripZeros(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        // 10.0은 stripTrailingZeros()가 1E+1이 되므로 scale을 0으로 되돌린다
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
