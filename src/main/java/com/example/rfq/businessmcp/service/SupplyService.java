/*
 * [서비스] 부품별 재고·입고·신규 발주 일정과 위험 코드, 대체 부품 계산. 데이터는 백엔드 조회 API에서 가져온다.
 * check_parts_supply와 get_substitutes가 사용한다. 계산 본체(assess, toSubstituteSupply)는 백엔드 없이 단위 테스트할 수 있는 순수 메서드다.
 */
package com.example.rfq.businessmcp.service;

import com.example.rfq.businessmcp.client.CatalogClient;
import com.example.rfq.businessmcp.dto.BackendCatalog.Inventory;
import com.example.rfq.businessmcp.dto.BackendCatalog.Part;
import com.example.rfq.businessmcp.dto.BackendCatalog.Substitute;
import com.example.rfq.businessmcp.dto.PartRequirement;
import com.example.rfq.businessmcp.dto.PartSupply;
import com.example.rfq.businessmcp.dto.SubstituteResult;
import com.example.rfq.businessmcp.dto.SubstituteSupply;
import com.example.rfq.businessmcp.dto.SupplyCheckResult;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 부품별 재고·입고·조달 일정과 위험 표시. 대체 부품도 일반 part_id로 조회할 수 있다 (SG4 재확인).
 * 가용 재고·단가 차이처럼 백엔드가 계산해 주는 값은 그대로 쓴다.
 */
@Service
public class SupplyService {
    static final String SHORTAGE = "SHORTAGE";
    static final String INCOMING_AFTER_DUE = "INCOMING_AFTER_DUE";
    static final String INCOMING_INSUFFICIENT = "INCOMING_INSUFFICIENT";
    static final String NEW_ORDER_AFTER_DUE = "NEW_ORDER_AFTER_DUE";
    static final String OK = "OK";
    static final String SHORT = "SHORT";

    private final CatalogClient catalogClient;
    private final Clock clock;

    public SupplyService(CatalogClient catalogClient, Clock clock) {
        this.catalogClient = catalogClient;
        this.clock = clock;
    }

    /**
     * 부품마다 부품 마스터·재고를 조회하고, 부족한 부품에만 대체 부품을 조회한다.
     * 부품 마스터는 요청 안에서 한 번만 조회한다 (대체 부품이 parts 목록에도 있을 수 있음).
     */
    public SupplyCheckResult checkPartsSupply(String dueDate, String baseDate, List<PartRequirement> parts) {
        LocalDate due = Inputs.requireDate("due_date", dueDate);
        LocalDate base = baseDate(baseDate);
        validate(parts);

        Map<String, Part> partCache = new HashMap<>();
        List<PartSupply> results = parts.stream().map(p -> {
            Part part = part(p.partId(), partCache);
            Inventory inventory = catalogClient.inventory(p.partId());
            int shortQty = shortQty(p.requiredQty(), inventory.available());
            List<SubstituteSupply> substitutes = shortQty > 0
                    ? substitutes(part, shortQty, base, partCache)
                    : List.of();
            return assess(part, inventory, p.requiredQty(), due, base, substitutes);
        }).toList();
        boolean hasRisk = results.stream().anyMatch(r -> !r.risks().isEmpty());
        return new SupplyCheckResult(due.toString(), base.toString(), hasRisk, results);
    }

    /** 부품 하나의 대체 후보. short_qty를 주면 covers_shortage를 계산하고, 안 주면 null. */
    public SubstituteResult getSubstitutes(String partId, Integer shortQty, String baseDate) {
        Inputs.requireText("part_id", partId);
        if (shortQty != null) {
            Inputs.requirePositive("short_qty", shortQty);
        }
        LocalDate base = baseDate(baseDate);
        Map<String, Part> partCache = new HashMap<>();
        Part original = part(partId, partCache);
        return new SubstituteResult(
                original.partId(),
                original.partName(),
                original.unitCostKrw(),
                substitutes(original, shortQty, base, partCache));
    }

    /** check_parts_supply와 get_substitutes 공통. 후보마다 부품 마스터를 조회해 단가·조달기간을 채운다. */
    private List<SubstituteSupply> substitutes(Part original, Integer shortQty, LocalDate base,
                                               Map<String, Part> partCache) {
        return catalogClient.substitutes(original.partId()).stream()
                .map(s -> toSubstituteSupply(s, part(s.substitutePartId(), partCache), shortQty, base))
                .toList();
    }

    private Part part(String partId, Map<String, Part> partCache) {
        return partCache.computeIfAbsent(partId, catalogClient::part);
    }

    private LocalDate baseDate(String baseDate) {
        return StringUtils.hasText(baseDate) ? Inputs.requireDate("base_date", baseDate) : LocalDate.now(clock);
    }

    static int shortQty(int requiredQty, int available) {
        return Math.max(0, requiredQty - available);
    }

    /**
     * 부품 하나의 공급 상태. 위험 코드와 대체 부품은 SHORT인 경우에만 채운다.
     * 입고 예정은 날짜와 수량(> 0)이 모두 있어야 있는 것으로 본다.
     */
    static PartSupply assess(Part part, Inventory inventory, int requiredQty, LocalDate due, LocalDate base,
                             List<SubstituteSupply> substitutes) {
        int available = inventory.available();
        int shortQty = shortQty(requiredQty, available);
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

        return new PartSupply(
                part.partId(),
                part.partName(),
                part.supplier(),
                part.isCritical(),
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
                isShort ? substitutes : List.of());
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

    /** 가용 재고·단가 차이는 백엔드 값, 단가·조달기간은 대체 부품의 부품 마스터. shortQty가 null이면 covers_shortage도 null. */
    static SubstituteSupply toSubstituteSupply(Substitute substitute, Part substitutePart, Integer shortQty,
                                               LocalDate base) {
        int available = substitute.availableQty();
        return new SubstituteSupply(
                substitute.substitutePartId(),
                substitutePart.partName(),
                substitutePart.supplier(),
                available,
                shortQty == null ? null : available >= shortQty,
                substitutePart.unitCostKrw(),
                substitute.unitCostDiffKrw(),
                substitute.validationDays(),
                base.plusDays(substitute.validationDays()).toString(),
                substitutePart.leadTimeDays(),
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
