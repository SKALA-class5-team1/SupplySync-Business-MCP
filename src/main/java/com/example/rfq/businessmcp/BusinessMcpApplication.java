/*
 * [시작점] business-mcp 서버 실행 진입점.
 * Spring Boot가 Tool(@McpTool), DB 연결, API 키 필터를 자동 구성한다. 포트 8101, MCP 경로 /mcp.
 */
package com.example.rfq.businessmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BusinessMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(BusinessMcpApplication.class, args);
    }
}
