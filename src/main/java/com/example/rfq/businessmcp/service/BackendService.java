/*
 * [서비스] 백엔드 API Tool 4개의 요청 조립. 경로·Idempotency-Key·상태 코드별 오류 메시지를 정한다.
 * 업무 규칙(상태 전이·검증)은 백엔드 책임이라 여기서는 필수 값만 확인한다.
 */
package com.example.rfq.businessmcp.service;

import com.example.rfq.businessmcp.client.BackendClient;
import com.example.rfq.businessmcp.dto.RegisterQuoteRequest;
import com.example.rfq.businessmcp.dto.RegisterQuoteResult;
import com.example.rfq.businessmcp.dto.RfqContent;
import com.example.rfq.businessmcp.dto.SaveQuoteRequest;
import com.example.rfq.businessmcp.dto.SaveQuoteResult;
import com.example.rfq.businessmcp.dto.SaveReviewRequest;
import com.example.rfq.businessmcp.dto.SaveReviewResult;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 백엔드 API Tool(6~9) → 백엔드 전달. 상태 전이·내용 검증·파일 보관은 백엔드 책임이므로
 * 여기서는 경로에 필요한 값만 확인한다.
 * save_review·save_quote는 PUT upsert라 Idempotency-Key를 붙이지 않는다 (반려 후 재작성이 첫 결과로 막히지 않도록).
 */
@Service
public class BackendService {
    private static final String CONFLICT = "현재 상태에서 불가";

    private final BackendClient backendClient;

    public BackendService(BackendClient backendClient) {
        this.backendClient = backendClient;
    }

    public SaveReviewResult saveReview(SaveReviewRequest request) {
        Inputs.requireText("thread_id", request.threadId());
        Inputs.requireText("stage", request.stage());
        return backendClient.put(
                "/internal/reviews/{threadId}", request.threadId(),
                request,
                SaveReviewResult.class,
                Map.of(409, CONFLICT));
    }

    public SaveQuoteResult saveQuote(SaveQuoteRequest request) {
        Inputs.requireText("thread_id", request.threadId());
        return backendClient.put(
                "/internal/quotes/{threadId}", request.threadId(),
                request,
                SaveQuoteResult.class,
                Map.of(409, CONFLICT + ": 이미 승인 또는 등록된 견적입니다."));
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

    public RfqContent getRfqContent(String rfqNumber) {
        Inputs.requireText("rfq_number", rfqNumber);
        return backendClient.get(
                "/internal/rfqs/{rfqNumber}/content", rfqNumber,
                RfqContent.class,
                Map.of(
                        404, "RFQ를 찾을 수 없음: " + rfqNumber,
                        415, "지원하지 않는 형식: 텍스트가 아닌 RFQ 파일입니다."));
    }
}
