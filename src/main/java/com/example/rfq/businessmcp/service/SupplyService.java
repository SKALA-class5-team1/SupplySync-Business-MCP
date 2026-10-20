/*
 * [서비스] 부품별 재고·입고·신규 발주 일정과 위험 코드, 대체 부품 계산.
 * check_parts_supply가 사용한다. 계산 본체(assess)는 DB 없이 단위 테스트할 수 있는 순수 메서드다.
 */
package com.example.rfq.businessmcp.service;

import com.example.rfq.businessmcp.dto.PartRequirement;
import com.example.rfq.businessmcp.dto.PartSupply;
import com.example.rfq.businessmcp.dto.SubstituteSupply;
import com.example.rfq.businessmcp.dto.SupplyCheckResult;
import com.example.rfq.businessmcp.repository.PartRepository;
import com.example.rfq.businessmcp.repository.PartRepository.Inventory;
import com.example.rfq.businessmcp.repository.PartRepository.Part;
import com.example.rfq.businessmcp.repository.PartRepository.Substitute;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 부품별 재고·입고·조달 일정과 위험 표시. 대체 부품도 일반 part_id로 조회할 수 있다 (SG4 재확인). */
@Service
public class SupplyService {
    static final String SHORTAGE = "SHORTAGE";
    static final String INCOMING_AFTER_DUE = "INCOMING_AFTER_DUE";
    static final String INCOMING_INSUFFICIENT = "INCOMING_INSUFFICIENT";
    static final String NEW_ORDER_AFTER_DUE = "NEW_ORDER_AFTER_DUE";
    static final String OK = "OK";
    static final String SHORT = "SHORT";

    private final PartRepository partRepository;
    private final Clock clock;

    public SupplyService(PartRepository partRepository, Clock clock) {
        this.partRepository = partRepository;
        this.clock = clock;
    }

    public SupplyCheckResult checkPartsSupply(String dueDate, String baseDate, List<PartRequirement> parts) {
        LocalDate due = Inputs.requireDate("due_date", dueDate);
        LocalDate base = StringUtils.hasText(baseDate) ? Inputs.requireDate("base_date", baseDate) : LocalDate.now(clock);
        validate(parts);

        List<String> partIds = parts.stream().map(PartRequirement::partId).toList();
        Map<String, Part> partInfo = partRepository.findParts(partIds);
        Map<String, Inventory> inventory = partRepository.findInventory(partIds);
        for (String partId : partIds) {
            if (!partInfo.containsKey(partId)) {
                throw new IllegalArgumentException("존재하지 않는 부품입니다: " + partId);
            }
            if (!inventory.containsKey(partId)) {
                throw new IllegalArgumentException("재고(inventory) 정보가 없는 부품입니다: " + partId);
            }
        }

        // 대체 부품은 부족한 부품에 대해서만 조회한다
        List<String> shortPartIds = parts.stream()
                .filter(p -> inventory.get(p.partId()).available() < p.requiredQty())
                .map(PartRequirement::partId)
                .toList();
        Map<String, List<Substitute>> substitutes = shortPartIds.isEmpty()
                ? Map.of()
                : partRepository.findSubstitutes(shortPartIds).stream()
                        .collect(Collectors.groupingBy(Substitute::originalPartId));

        List<PartSupply> results = parts.stream()
                .map(p -> assess(
                        partInfo.get(p.partId()),
                        inventory.get(p.partId()),
                        p.requiredQty(),
                        due,
                        base,
                        substitutes.getOrDefault(p.partId(), List.of())))
                .toList();
        boolean hasRisk = results.stream().anyMatch(r -> !r.risks().isEmpty());
        return new SupplyCheckResult(due.toString(), base.toString(), hasRisk, results);
    }

    /**
     * 부품 하나의 공급 상태. 위험 코드와 대체 부품은 SHORT인 경우에만 채운다.
     * 입고 예정은 날짜와 수량(> 0)이 모두 있어야 있는 것으로 본다.
     */
    static PartSupply assess(Part part, Inventory inventory, int requiredQty, LocalDate due, LocalDate base,
                             List<Substitute> substitutes) {
        int available = inventory.available();
        int shortQty = Math.max(0, requiredQty - available);
        boolean isShort = shortQty > 0;

        LocalDate incomingDate = inventory.incomingDate();
        boolean hasIncoming = incomingDate != null && inventory.incomingQty() > 0;
        boolean incomingBeforeDue = hasIncoming && !incomingDate.isAfter(due);
        Integer incomingDelayDays = hasIncoming
                ? (int) Math.max(0, ChronoUnit.DAYS.between(due, incomingDate))
                : null;
        LocalDate newOrderArrival = base.plusDays(part.leadTimeDays());

        List<String> risks = isShort
                ? risks(inventory.incomingQty(), shortQty, incomingBeforeDue, newOrderArrival, due)
                : List.of();
        List<SubstituteSupply> substituteSupplies = isShort
                ? substitutes.stream().map(s -> toSubstituteSupply(s, part, shortQty, base)).toList()
                : List.of();

        return new PartSupply(
                part.partId(),
                part.partName(),
                part.supplier(),
                part.critical(),
                requiredQty,
                inventory.onHand(),
                inventory.allocated(),
                available,
                shortQty,
                isShort ? SHORT : OK,
                inventory.incomingQty(),
                incomingDate == null ? null : incomingDate.toString(),
                incomingBeforeDue,
                incomingDelayDays,
                part.unitCostKrw(),
                part.leadTimeDays(),
                newOrderArrival.toString(),
                risks,
                substituteSupplies);
    }

    /** 부족한 부품의 위험 코드. INCOMING_AFTER_DUE와 INCOMING_INSUFFICIENT는 함께 붙지 않는다. */
    private static List<String> risks(int incomingQty, int shortQty, boolean incomingBeforeDue,
                                      LocalDate newOrderArrival, LocalDate due) {
        List<String> risks = new ArrayList<>();
        risks.add(SHORTAGE);
        if (!incomingBeforeDue) {
            risks.add(INCOMING_AFTER_DUE);
        } else if (incomingQty < shortQty) {
            // 납기 전 입고는 있지만 부족분을 다 채우지 못함
            risks.add(INCOMING_INSUFFICIENT);
        }
        if (newOrderArrival.isAfter(due)) {
            risks.add(NEW_ORDER_AFTER_DUE);
        }
        return List.copyOf(risks);
    }

    private static SubstituteSupply toSubstituteSupply(Substitute substitute, Part original, int shortQty,
                                                       LocalDate base) {
        Part part = substitute.part();
        int available = substitute.inventory() == null ? 0 : substitute.inventory().available();
        return new SubstituteSupply(
                part.partId(),
                part.partName(),
                part.supplier(),
                available,
                available >= shortQty,
                part.unitCostKrw(),
                part.unitCostKrw() - original.unitCostKrw(),
                substitute.validationDays(),
                base.plusDays(substitute.validationDays()).toString(),
                part.leadTimeDays(),
                substitute.pinCompatible(),
                substitute.refChunkId());
    }

    private static void validate(List<PartRequirement> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("parts가 비어 있습니다.");
        }
        Set<String> seen = new HashSet<>();
        for (PartRequirement p : parts) {
            Inputs.requireText("part_id", p == null ? null : p.partId());
            if (!seen.add(p.partId())) {
                throw new IllegalArgumentException("중복된 part_id입니다: " + p.partId());
            }
            Inputs.requirePositive("required_qty (" + p.partId() + ")", p.requiredQty());
        }
    }
}
