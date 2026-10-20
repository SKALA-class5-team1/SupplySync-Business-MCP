/*
 * [Tool 진입점] DB 읽기 Tool 5개: get_models, get_options, get_bom, check_parts_supply, get_parts_cost.
 * Agent가 MCP로 호출하는 메서드와 한국어 설명을 정의하고, 실제 처리는 ProductService·SupplyService에 맡긴다.
 */
package com.example.rfq.businessmcp.controller;

import com.example.rfq.businessmcp.dto.BomResult;
import com.example.rfq.businessmcp.dto.ModelOptions;
import com.example.rfq.businessmcp.dto.ModelSpec;
import com.example.rfq.businessmcp.dto.PartRequirement;
import com.example.rfq.businessmcp.dto.PartsCostResult;
import com.example.rfq.businessmcp.dto.SupplyCheckResult;
import com.example.rfq.businessmcp.service.ProductService;
import com.example.rfq.businessmcp.service.SupplyService;
import java.util.List;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpTool.McpAnnotations;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 읽기 Tool 진입점. 파라미터 이름이 그대로 MCP 입력 필드명이 되므로 snake_case로 둔다.
 * 잘못된 입력은 예외로 던지고, MCP 오류 결과(isError)로 반환된다.
 */
@Component
public class BusinessTools {
    private final ProductService productService;
    private final SupplyService supplyService;

    public BusinessTools(ProductService productService, SupplyService supplyService) {
        this.productService = productService;
        this.supplyService = supplyService;
    }

    @McpTool(name = "get_models", description = """
            표준 워터펌프 모델 전체 목록과 사양(유량, 압력, 전압, 온도, 방수 등급, 소음, 통신, 크기, 무게)과 \
            기본 판매 단가를 반환한다. 고객 요구 사양을 우리 모델과 비교할 때 가장 먼저 호출한다.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public List<ModelSpec> getModels() {
        return productService.getModels();
    }

    @McpTool(name = "get_options", description = """
            모델의 기본 판매 단가와, 그 모델에 붙일 수 있는 옵션 목록을 반환한다. 옵션마다 바뀌는 사양 \
            (changes_field, new_value), 추가 무게·소음·판매 단가, BOM에서 교체되는 부품을 담는다. \
            표준 모델이 사양을 못 맞출 때 옵션으로 보완 가능한지 볼 때, 견적 단가(기본 단가 + 옵션 단가)를 \
            계산할 때 사용한다. 옵션이 없는 모델은 options가 빈 배열이다.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public ModelOptions getOptions(
            @McpToolParam(description = "모델 ID (예: EWP-12-B). get_models 결과의 model_id") String model_id) {
        return productService.getOptions(model_id);
    }

    @McpTool(name = "get_bom", description = """
            모델과 선택 옵션을 반영해 BOM(부품 목록)을 전개하고 생산 수량 기준 부품별 필요 수량을 반환한다. \
            source가 BASE(기본 부품) 또는 OPTION:{옵션 코드}이고, 옵션으로 교체된 부품은 replaced_part_id에 \
            원래 부품이 표시된다. parts는 part_id 순이며 순서에 의미는 없다. 재고 확인 전에 호출하고, 결과의 \
            part_id와 required_qty를 그대로 check_parts_supply에 넣는다. 허용되지 않은 옵션이나 BOM이 없는 \
            모델이면 오류를 반환한다.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public BomResult getBom(
            @McpToolParam(description = "모델 ID (예: EWP-12-B)") String model_id,
            @McpToolParam(description = "적용할 옵션 코드 목록 (예: [\"OPT-HT\", \"OPT-IP9K\"]). 없으면 빈 배열",
                    required = false) List<String> options,
            @McpToolParam(description = "생산 수량 (1 이상, 예: 시작품 2000)") Integer qty) {
        return productService.getBom(model_id, options, qty);
    }

    @McpTool(name = "check_parts_supply", description = """
            부품별 필요 수량을 받아 재고(on_hand, allocated, available), 부족 수량(short_qty), 상태(OK/SHORT), \
            입고 예정(수량·날짜·납기 전 도착 여부·납기 대비 지연일), 신규 발주 시 도착 예정일, 부품 단가를 반환한다. \
            SHORT인 부품에만 위험 코드(SHORTAGE, INCOMING_AFTER_DUE, INCOMING_INSUFFICIENT, \
            NEW_ORDER_AFTER_DUE)와 대체 부품(가용 수량, 단가 차이, 검증 후 사용 가능일, \
            핀 호환 여부, 근거 청크 ID)을 채운다. 고객 납기 안에 부품을 확보할 수 있는지 판단할 때 \
            get_bom 결과로 호출하고, 승인 후 재확인(SG4) 때는 대체 부품을 포함한 최종 계획(final_plan)의 \
            part_id·수량으로 다시 호출한다. 대안 비교·추천은 하지 않고 사실만 반환한다.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public SupplyCheckResult checkPartsSupply(
            @McpToolParam(description = "고객 납기 (YYYY-MM-DD)") String due_date,
            @McpToolParam(description = "검토 기준일 (YYYY-MM-DD). 신규 발주 도착일·대체 부품 사용 가능일 계산 기준. "
                    + "생략하면 오늘(Asia/Seoul)", required = false) String base_date,
            @McpToolParam(description = "확인할 부품 목록. get_bom 결과 parts의 part_id, required_qty")
            List<PartRequirement> parts) {
        return supplyService.checkPartsSupply(due_date, base_date, parts);
    }

    @McpTool(name = "get_parts_cost", description = """
            모델과 선택 옵션을 반영한 BOM 기준 제품 1개당 부품 원가 합계(parts_cost_krw)와 부품별 내역\
            (parts_cost_breakdown)을 반환한다. \
            가공비·물류비는 포함하지 않는다. 견적 단가 대비 마진을 계산할 때 사용한다. \
            오류 조건은 get_bom과 같다.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public PartsCostResult getPartsCost(
            @McpToolParam(description = "모델 ID (예: EWP-12-B)") String model_id,
            @McpToolParam(description = "적용할 옵션 코드 목록 (예: [\"OPT-HT\", \"OPT-IP9K\"]). 없으면 빈 배열",
                    required = false) List<String> options) {
        return productService.getPartsCost(model_id, options);
    }
}
