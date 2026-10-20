/*
 * [서비스] 모델·옵션 조회, BOM 전개(옵션 부품 교체), 부품 원가 합계 계산.
 * get_models, get_options, get_bom, get_parts_cost가 사용한다. BOM 전개(expandBom)는 get_bom과 get_parts_cost가 공유한다.
 */
package com.example.rfq.businessmcp.service;

import com.example.rfq.businessmcp.dto.BomPart;
import com.example.rfq.businessmcp.dto.BomResult;
import com.example.rfq.businessmcp.dto.CostLine;
import com.example.rfq.businessmcp.dto.ModelOptions;
import com.example.rfq.businessmcp.dto.ModelSpec;
import com.example.rfq.businessmcp.dto.OptionInfo;
import com.example.rfq.businessmcp.dto.PartsCostResult;
import com.example.rfq.businessmcp.repository.PartRepository;
import com.example.rfq.businessmcp.repository.PartRepository.Part;
import com.example.rfq.businessmcp.repository.ProductRepository;
import com.example.rfq.businessmcp.repository.ProductRepository.BomRow;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 모델·옵션 조회와 BOM 전개, 부품 원가 계산. */
@Service
public class ProductService {
    static final String BASE = "BASE";
    static final String OPTION_PREFIX = "OPTION:";

    private final ProductRepository productRepository;
    private final PartRepository partRepository;

    public ProductService(ProductRepository productRepository, PartRepository partRepository) {
        this.productRepository = productRepository;
        this.partRepository = partRepository;
    }

    /** 옵션 반영 후 BOM 한 줄. source는 BASE 또는 OPTION:{code}, replacedPartId는 교체되지 않았으면 null. */
    public record ExpandedPart(
            String partId,
            int qtyPerUnit,
            String source,
            String replacedPartId) {
    }

    /** ExpandedPart + parts 테이블의 이름·단가. */
    public record BomLine(
            String partId,
            String partName,
            int qtyPerUnit,
            int unitCostKrw,
            String source,
            String replacedPartId) {
    }

    public record BomExpansion(
            String modelId,
            List<String> options,
            List<BomLine> lines) {
    }

    public List<ModelSpec> getModels() {
        return productRepository.findAllModels();
    }

    public ModelOptions getOptions(String modelId) {
        int basePrice = requireBasePrice(modelId);
        return new ModelOptions(modelId, basePrice, productRepository.findOptions(modelId));
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

    public PartsCostResult getPartsCost(String modelId, List<String> optionCodes) {
        BomExpansion bom = expandBom(modelId, optionCodes);
        List<CostLine> breakdown = bom.lines().stream()
                .map(line -> new CostLine(
                        line.partId(),
                        line.qtyPerUnit(),
                        line.unitCostKrw(),
                        Math.multiplyExact(line.unitCostKrw(), line.qtyPerUnit())))
                .toList();
        int total = breakdown.stream().mapToInt(CostLine::costKrw).reduce(0, Math::addExact);
        return new PartsCostResult(bom.modelId(), bom.options(), total, breakdown);
    }

    /** get_bom, get_parts_cost 공통: 모델·옵션 검증 후 옵션의 교체 부품을 반영한 BOM. */
    public BomExpansion expandBom(String modelId, List<String> optionCodes) {
        requireBasePrice(modelId);
        // 같은 옵션이 중복으로 오면 하나로 합친다 (입력 순서 유지)
        List<String> codes = optionCodes == null ? List.of() : List.copyOf(new LinkedHashSet<>(optionCodes));
        List<OptionInfo> selected = selectAllowedOptions(modelId, codes);

        List<BomRow> base = productRepository.findBom(modelId);
        if (base.isEmpty()) {
            throw new IllegalArgumentException("모델 " + modelId + "의 BOM이 없습니다.");
        }
        List<ExpandedPart> expanded = applyOptions(base, selected);
        return new BomExpansion(modelId, codes, withPartInfo(expanded));
    }

    /** 옵션 코드가 모델에 허용된 조합(model_options)인지 확인하고 옵션 정보를 돌려준다. */
    private List<OptionInfo> selectAllowedOptions(String modelId, List<String> codes) {
        Map<String, OptionInfo> allowed = productRepository.findOptions(modelId).stream()
                .collect(Collectors.toMap(OptionInfo::optionCode, Function.identity()));
        return codes.stream().map(code -> {
            OptionInfo option = allowed.get(code);
            if (option == null) {
                throw new IllegalArgumentException(
                        "모델 " + modelId + "에 허용되지 않은 옵션입니다: " + code + " (허용: " + allowed.keySet() + ")");
            }
            return option;
        }).toList();
    }

    /** 전개된 부품에 parts 테이블의 이름·단가를 붙인다. */
    private List<BomLine> withPartInfo(List<ExpandedPart> expanded) {
        Map<String, Part> parts = partRepository.findParts(expanded.stream().map(ExpandedPart::partId).toList());
        return expanded.stream().map(e -> {
            Part part = parts.get(e.partId());
            if (part == null) {
                throw new IllegalStateException("parts에 없는 부품입니다: " + e.partId());
            }
            return new BomLine(
                    e.partId(),
                    part.partName(),
                    e.qtyPerUnit(),
                    part.unitCostKrw(),
                    e.source(),
                    e.replacedPartId());
        }).toList();
    }

    /** 각 옵션의 replaces_part_id를 uses_part_id로 교체한다. qty_per_unit은 유지. */
    static List<ExpandedPart> applyOptions(List<BomRow> base, List<OptionInfo> options) {
        Map<String, OptionInfo> byReplacedPart = new HashMap<>();
        for (OptionInfo option : options) {
            if (option.replacesPartId() == null && option.usesPartId() == null) {
                continue; // 부품을 바꾸지 않는 옵션
            }
            if (option.replacesPartId() == null || option.usesPartId() == null) {
                throw new IllegalStateException("옵션 " + option.optionCode() + "의 교체 부품 정보가 불완전합니다.");
            }
            OptionInfo previous = byReplacedPart.put(option.replacesPartId(), option);
            if (previous != null) {
                throw new IllegalArgumentException("옵션 " + previous.optionCode() + "와 " + option.optionCode()
                        + "가 같은 부품(" + option.replacesPartId() + ")을 교체하므로 함께 쓸 수 없습니다.");
            }
            if (base.stream().noneMatch(row -> row.partId().equals(option.replacesPartId()))) {
                throw new IllegalStateException("옵션 " + option.optionCode() + "가 교체할 부품 "
                        + option.replacesPartId() + "이 BOM에 없습니다.");
            }
        }
        return base.stream().map(row -> {
            OptionInfo option = byReplacedPart.get(row.partId());
            if (option == null) {
                return new ExpandedPart(row.partId(), row.qtyPerUnit(), BASE, null);
            }
            return new ExpandedPart(
                    option.usesPartId(),
                    row.qtyPerUnit(),
                    OPTION_PREFIX + option.optionCode(),
                    row.partId());
        }).toList();
    }

    private int requireBasePrice(String modelId) {
        Inputs.requireText("model_id", modelId);
        return productRepository.findBasePrice(modelId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 모델입니다: " + modelId));
    }
}
