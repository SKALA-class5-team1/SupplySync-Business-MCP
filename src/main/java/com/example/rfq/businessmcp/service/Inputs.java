/*
 * [서비스 공통] Tool 입력값 검증 (빈 값, 양수, 날짜 형식). 세 서비스가 같은 규칙과 메시지를 쓰도록 모아 둔다.
 * 실패하면 IllegalArgumentException → MCP Tool 오류 결과로 Agent에 전달된다.
 */
package com.example.rfq.businessmcp.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.util.StringUtils;

final class Inputs {
    private Inputs() {
    }

    static String requireText(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + "를 입력해야 합니다.");
        }
        return value;
    }

    static int requirePositive(String field, Integer value) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + "는 1 이상이어야 합니다: " + value);
        }
        return value;
    }

    /** YYYY-MM-DD 날짜. */
    static LocalDate requireDate(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + "를 입력해야 합니다 (YYYY-MM-DD).");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 형식이 잘못되었습니다 (YYYY-MM-DD): " + value);
        }
    }
}
