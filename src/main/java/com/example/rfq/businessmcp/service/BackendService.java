/*
 * [서비스] 백엔드 API Tool 4개의 요청 조립. 경로·Idempotency-Key·상태 코드별 오류 메시지를 정한다.
 * 업무 규칙(상태 전이·검증)은 백엔드 책임이라 여기서는 필수 값만 확인한다.
 */
package com.example.rfq.businessmcp.service;

import com.example.rfq.businessmcp.client.BackendClient;
import com.example.rfq.businessmcp.client.BackendException;
import com.example.rfq.businessmcp.dto.RegisterQuoteRequest;
import com.example.rfq.businessmcp.dto.RegisterQuoteResult;
import com.example.rfq.businessmcp.dto.RfqContent;
import com.example.rfq.businessmcp.dto.RfqDemand;
import com.example.rfq.businessmcp.dto.RfqInfo;
import com.example.rfq.businessmcp.dto.SaveQuoteRequest;
import com.example.rfq.businessmcp.dto.SaveQuoteResult;
import com.example.rfq.businessmcp.dto.SaveReviewRequest;
import com.example.rfq.businessmcp.dto.SaveReviewResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 백엔드 API Tool(6~9) → 백엔드 전달. 상태 전이·내용 검증·파일 보관은 백엔드 책임이므로
 * 여기서는 경로에 필요한 값만 확인한다.
 * save_review(PUT upsert)·save_quote(같은 thread_id+revision이면 기존 견적 반환)는 다시 보내도 결과가 같아
 * Idempotency-Key를 붙이지 않는다.
 */
@Service
public class BackendService {
    private static final String CONFLICT = "현재 상태에서 불가";
    private static final List<String> STAGES = List.of("SG1", "SG2");

    private final BackendClient backendClient;

    public BackendService(BackendClient backendClient) {
        this.backendClient = backendClient;
    }

    /**
     * demand가 있으면 RFQ 요구값(PATCH /internal/rfqs/{rfqNumber})을 먼저 저장한 뒤 분석 결과(PUT /internal/rfq-reviews)를 저장한다.
     * 두 API 모두 다시 보내도 결과가 같으므로, 중간에 실패하면 같은 입력으로 다시 호출하면 된다.
     */
    public SaveReviewResult saveReview(String stage, SaveReviewRequest request, RfqDemand demand) {
        Inputs.requireText("thread_id", request.threadId());
        Inputs.requireText("rfq_number", request.rfqNumber());
        if (!STAGES.contains(Inputs.requireText("stage", stage))) {
            throw new IllegalArgumentException("stage는 SG1 또는 SG2여야 합니다: " + stage);
        }
        if (demand != null) {
            try {
                backendClient.patch(
                        "/internal/rfqs/{rfqNumber}", request.rfqNumber(),
                        demand,
                        RfqInfo.class,
                        Map.of(404, "RFQ를 찾을 수 없음: " + request.rfqNumber()));
            } catch (BackendException e) {
                throw new BackendException("demand(RFQ 요구값) 저장 실패, 분석 결과는 저장하지 않았습니다: " + e.getMessage());
            }
        }
        try {
            return backendClient.put(
                    "/internal/rfq-reviews", null,
                    request,
                    SaveReviewResult.class,
                    Map.of(
                            404, "RFQ를 찾을 수 없음: " + request.rfqNumber(),
                            409, CONFLICT));
        } catch (BackendException e) {
            if (demand == null) {
                throw e;
            }
            throw new BackendException("demand는 저장됐지만 분석 결과 저장 실패 (같은 입력으로 다시 호출해도 됩니다): "
                    + e.getMessage());
        }
    }

    public SaveQuoteResult saveQuote(SaveQuoteRequest request) {
        Inputs.requireText("thread_id", request.threadId());
        Inputs.requireText("rfq_number", request.rfqNumber());
        Inputs.requirePositive("revision", request.revision());
        return backendClient.post(
                "/internal/quotes", null,
                null,
                request,
                SaveQuoteResult.class,
                Map.of(
                        404, "분석 결과 또는 모델을 찾을 수 없음 (save_review를 같은 thread_id·rfq_number로 먼저 호출해야 합니다)",
                        409, CONFLICT));
    }

    public RegisterQuoteResult registerQuote(RegisterQuoteRequest request) {
        Inputs.requireText("quote_id", request.quoteId());
        Inputs.requireText("thread_id", request.threadId());
        return backendClient.post(
                "/internal/quotes/{quoteId}/register", request.quoteId(),
                request.quoteId() + ":register",
                request,
                RegisterQuoteResult.class,
                Map.of(409, CONFLICT + ": 승인되지 않은 견적입니다."));
    }

    /** RFQ 정보(GET /internal/rfqs/{rfqNumber})와 원문(GET .../content, text/markdown)을 합친다. */
    public RfqContent getRfqContent(String rfqNumber) {
        Inputs.requireText("rfq_number", rfqNumber);
        Map<Integer, String> statusMessages = Map.of(
                404, "RFQ를 찾을 수 없음: " + rfqNumber,
                415, "지원하지 않는 형식: 텍스트가 아닌 RFQ 파일입니다.");
        RfqInfo info = backendClient.get(
                "/internal/rfqs/{rfqNumber}", rfqNumber,
                RfqInfo.class,
                statusMessages);
        String content = backendClient.get(
                "/internal/rfqs/{rfqNumber}/content", rfqNumber,
                String.class,
                statusMessages);
        return new RfqContent(
                info.rfqNumber(),
                info.item(),
                StringUtils.getFilename(info.filePath()),
                content);
    }
}
