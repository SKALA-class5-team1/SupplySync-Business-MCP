/*
 * [Tool 진입점] 백엔드 API Tool 4개: save_review, save_quote, register_quote, get_rfq_content.
 * DB에 직접 쓰지 않고 BackendService → BackendClient로 Spring 백엔드 /internal/** API를 호출한다.
 */
package com.example.rfq.businessmcp.controller;

import com.example.rfq.businessmcp.dto.RegisterQuoteRequest;
import com.example.rfq.businessmcp.dto.RegisterQuoteResult;
import com.example.rfq.businessmcp.dto.RfqContent;
import com.example.rfq.businessmcp.dto.RfqDemand;
import com.example.rfq.businessmcp.dto.SaveQuoteRequest;
import com.example.rfq.businessmcp.dto.SaveQuoteResult;
import com.example.rfq.businessmcp.dto.SaveReviewRequest;
import com.example.rfq.businessmcp.dto.SaveReviewResult;
import com.example.rfq.businessmcp.service.BackendService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpTool.McpAnnotations;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 백엔드 API Tool 진입점 (쓰기 3개 + RFQ 원문 조회). DB에 직접 쓰지 않고 Spring 백엔드 /internal/** API를 호출한다.
 * 파라미터 이름이 그대로 MCP 입력 필드명이 되므로 snake_case로 둔다.
 */
@Component
public class BackendTools {
    private final BackendService backendService;

    public BackendTools(BackendService backendService) {
        this.backendService = backendService;
    }

    @McpTool(name = "save_review", description = """
            SG1(RFQ·사양 검토) 또는 SG2(부품 공급 대안) 분석 결과를 저장한다. \
            SG1은 담당자 입력 대기 시 status=WAITING으로 저장한 뒤 끝나면 DONE으로 다시 저장하고, \
            SG2는 shortages~final_plan만 보낸다. 같은 thread_id·rfq_number로 다시 호출하면 보낸 필드만 갱신되므로 \
            재시도해도 안전하다(멱등). SG2 저장은 SG1 결과를 지우지 않는다. \
            demand를 보내면 RFQ 요구값(EAU·납기 등)도 함께 저장한다.""",
            annotations = @McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SaveReviewResult saveReview(
            @McpToolParam(description = "LangGraph 실행 ID (백엔드가 Agent 실행 요청에 실어 보낸 값)") String thread_id,
            @McpToolParam(description = "RFQ 번호 (예: HB-EV7-EWP-001)") String rfq_number,
            @McpToolParam(description = "저장 단계: SG1 또는 SG2") String stage,
            @McpToolParam(description = "분석 상태: WAITING(담당자 입력 대기) / DONE(완료). 생략하면 바꾸지 않음",
                    required = false) String status,
            @McpToolParam(description = "[SG1] RFQ에서 추출한 요구값. RFQ(rfqs)에 저장된다", required = false)
            RfqDemand demand,
            @McpToolParam(description = "[SG1] 선정 모델 ID (예: EWP-12-B)", required = false) String selected_model,
            @McpToolParam(description = "[SG1] 선정 옵션 코드 목록", required = false) List<String> options,
            @McpToolParam(description = "[SG1] 적합 유형 (표준 / 파생 사양 / 신규 개발)", required = false) String fit_type,
            @McpToolParam(description = "[SG1] 검증 이슈 목록. 각 항목에 근거 evidence [{chunk_id, section, excerpt}]를 "
                    + "넣을 수 있다", required = false) List<Map<String, Object>> issues,
            @McpToolParam(description = "[SG1] 통과한 검증 항목", required = false) List<String> passed_checks,
            @McpToolParam(description = "[SG1] 담당자가 승인한 가정값", required = false) List<Map<String, Object>> assumptions,
            @McpToolParam(description = "[SG1] 고객 확인 질의", required = false) List<String> customer_questions,
            @McpToolParam(description = "[SG1] 계약 리스크. 각 항목에 근거 evidence [{chunk_id, section, excerpt}]를 "
                    + "넣을 수 있다", required = false) List<Map<String, Object>> contract_risks,
            @McpToolParam(description = "[SG2] 부족 부품 (check_parts_supply 결과 중 SHORT인 부품: part_id, short_qty, "
                    + "incoming_delay_days 등)", required = false) List<Map<String, Object>> shortages,
            @McpToolParam(description = "[SG2] 대안 비교 결과 (WAIT_INCOMING / PARTIAL_DELIVERY / SUBSTITUTE_PART / NEW_ORDER). "
                    + "각 항목에 근거 evidence [{chunk_id, section, excerpt}]를 넣을 수 있다",
                    required = false) List<Map<String, Object>> alternatives,
            @McpToolParam(description = "[SG2] 추천 대안: WAIT_INCOMING / PARTIAL_DELIVERY / SUBSTITUTE_PART / NEW_ORDER",
                    required = false) String recommended_alt,
            @McpToolParam(description = "[SG2] 최종 부품 사용 계획. part_id → 수량 "
                    + "(예: {\"P-MCU-G1\": 1500, \"P-MCU-G1-ALT\": 500}). SG4 재확인에 사용", required = false)
            Map<String, Object> final_plan) {
        SaveReviewRequest request = new SaveReviewRequest(
                rfq_number,
                thread_id,
                status,
                selected_model,
                options,
                fit_type,
                issues,
                passed_checks,
                assumptions,
                customer_questions,
                contract_risks,
                shortages,
                alternatives,
                recommended_alt,
                final_plan);
        return backendService.saveReview(stage, request, demand);
    }

    @McpTool(name = "save_quote", description = """
            견적 초안을 DRAFT 상태로 새로 저장한다. SG3에서 견적 검증(validate_quote)을 통과한 뒤 호출한다. \
            첫 견적은 revision=1, 담당자가 수정 요청(REVISE)한 뒤 다시 쓴 견적은 revision을 1씩 올린다(최대 4). \
            quote_id는 백엔드가 채번해 반환한다. 같은 thread_id·revision으로 다시 호출하면 새로 만들지 않고 기존 quote_id를 \
            반환하므로 재시도해도 안전하다(멱등). save_review가 먼저 저장되어 있어야 하며, \
            수정 요청 없이 다음 revision을 저장하려 하면 오류를 반환한다.""",
            annotations = @McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SaveQuoteResult saveQuote(
            @McpToolParam(description = "LangGraph 실행 ID (save_review와 같은 값)") String thread_id,
            @McpToolParam(description = "견적 차수. 첫 견적 1, 수정 요청(REVISE)마다 1 증가 (최대 4)") Integer revision,
            @McpToolParam(description = "RFQ 번호") String rfq_number,
            @McpToolParam(description = "견적 수신처 (예: 한별모터스 구매본부)", required = false) String recipient,
            @McpToolParam(description = "품목명", required = false) String item,
            @McpToolParam(description = "모델 ID") String model_id,
            @McpToolParam(description = "옵션 코드 목록", required = false) List<String> options,
            @McpToolParam(description = "견적 단가 (원, 고객 공개)") Integer unit_price_krw,
            @McpToolParam(description = "단가 구성 [{code, amount_krw}] (모델 기본가 + 옵션가)", required = false)
            List<Map<String, Object>> price_breakdown,
            @McpToolParam(description = "부품 원가 (원, 내부용. get_parts_cost 결과)", required = false)
            Integer parts_cost_krw,
            @McpToolParam(description = "부품 원가 내역 [{part_id, cost_krw}] (get_parts_cost의 parts_cost_breakdown)",
                    required = false) List<Map<String, Object>> parts_cost_breakdown,
            @McpToolParam(description = "마진율 % (부품 원가 기준, 예: 42.0)", required = false) BigDecimal margin_pct,
            @McpToolParam(description = "연도별 단가 [{year, cr_applied, unit_price_krw, margin_pct}]")
            List<Map<String, Object>> price_by_year,
            @McpToolParam(description = "연간 예상 물량 (EAU)", required = false) Integer eau,
            @McpToolParam(description = "시작품 수량", required = false) Integer sample_qty,
            @McpToolParam(description = "약속 납기 (YYYY-MM-DD)", required = false) String sample_due_date,
            @McpToolParam(description = "양산 시작일 SOP (YYYY-MM-DD)", required = false) String sop_date,
            @McpToolParam(description = "인도 조건 (예: DAP 한별모터스 울산 2공장)", required = false) String incoterms,
            @McpToolParam(description = "발행일 (YYYY-MM-DD). 견적 번호에 쓰인다. 생략하면 오늘", required = false)
            String issue_date,
            @McpToolParam(description = "견적 유효기한 (YYYY-MM-DD)", required = false) String valid_until,
            @McpToolParam(description = "견적 조건. type: ASSUMPTION / SUBSTITUTE_PART / CONTRACT_PROPOSAL",
                    required = false) List<Map<String, Object>> conditions,
            @McpToolParam(description = "검증 결과. rule: PRICE / ANNUAL_CR / MARGIN / PRICE_RECALC / FORMAT",
                    required = false) List<Map<String, Object>> checks) {
        SaveQuoteRequest request = new SaveQuoteRequest(
                thread_id,
                revision,
                rfq_number,
                recipient,
                item,
                model_id,
                options,
                unit_price_krw,
                price_breakdown,
                parts_cost_krw,
                parts_cost_breakdown,
                margin_pct,
                price_by_year,
                eau,
                sample_qty,
                sample_due_date,
                sop_date,
                incoterms,
                issue_date,
                valid_until,
                conditions,
                checks);
        return backendService.saveQuote(request);
    }

    @McpTool(name = "register_quote", description = """
            사람이 승인한 견적을 등록(REGISTERED) 상태로 바꾸고 RFQ 상태를 QUOTED로 바꾼다. 되돌리기 어려운 작업이다. \
            반드시 사람 승인 후, check_parts_supply 재확인을 통과한 뒤에만 호출한다. \
            승인(APPROVED)되지 않은 견적이면 오류를 반환한다. 같은 quote_id로 다시 호출해도 안전하다 \
            (outcome: 새로 등록 CREATED, 이미 등록됨 ALREADY_PROCESSED).""",
            annotations = @McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public RegisterQuoteResult registerQuote(
            @McpToolParam(description = "등록할 견적 ID (예: Q-2026-1019-001)") String quote_id,
            @McpToolParam(description = "LangGraph 실행 ID") String thread_id) {
        return backendService.registerQuote(new RegisterQuoteRequest(quote_id, thread_id));
    }

    @McpTool(name = "get_rfq_content", description = """
            RFQ 번호로 고객이 업로드한 견적요청서 원문(가공하지 않은 텍스트)과 품목·파일 이름을 가져온다. \
            SG1에서 RFQ를 분석(parse_rfq)하기 전에 가장 먼저 호출한다. 없는 RFQ이거나 텍스트가 아닌 파일(PDF 등), \
            200KB를 넘는 원문이면 오류를 반환한다.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public RfqContent getRfqContent(
            @McpToolParam(description = "RFQ 번호 (예: HB-EV7-EWP-001)") String rfq_number) {
        return backendService.getRfqContent(rfq_number);
    }
}
