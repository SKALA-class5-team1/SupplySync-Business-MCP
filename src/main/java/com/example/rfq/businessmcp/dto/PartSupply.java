/*
 * [DTO] check_parts_supply 출력의 부품 한 개 (재고, 부족, 입고, 신규 발주, 위험, 대체 부품).
 * 정답 파일 5_sg2_supply.json의 supply_check·shortages와 필드명이 같다.
 */
package com.example.rfq.businessmcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** status: OK 또는 SHORT. incoming_delay_days는 입고 예정이 없으면 null. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PartSupply(
        String partId,
        String partName,
        String supplier,
        @JsonProperty("is_critical") boolean isCritical,
        int requiredQty,
        int onHand,
        int allocated,
        int available,
        int shortQty,
        String status,
        int incomingQty,
        String incomingDate,
        boolean incomingBeforeDue,
        Integer incomingDelayDays,
        int unitCostKrw,
        int leadTimeDays,
        String newOrderArrival,
        List<String> risks,
        List<SubstituteSupply> substitutes) {
}
