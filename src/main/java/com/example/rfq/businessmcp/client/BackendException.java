/*
 * [외부 호출] 백엔드 호출 실패 예외. 메시지가 그대로 MCP Tool 오류 결과로 Agent에 전달된다.
 */
package com.example.rfq.businessmcp.client;

/**
 * 백엔드 호출 실패. MCP Tool 오류 결과에는 예외 체인의 가장 안쪽 메시지가 담기므로,
 * Agent에 보여줄 메시지가 가려지지 않도록 cause를 연결하지 않는다.
 */
public class BackendException extends RuntimeException {
    public BackendException(String message) {
        super(message);
    }
}
