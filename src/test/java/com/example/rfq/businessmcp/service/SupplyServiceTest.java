/*
 * [테스트] 공급 상태·위험 코드·대체 부품 계산 단위 테스트. 백엔드 없이 실행.
 */
package com.example.rfq.businessmcp.service;

import com.example.rfq.businessmcp.dto.BackendCatalog.Inventory;
import com.example.rfq.businessmcp.dto.BackendCatalog.Part;
import com.example.rfq.businessmcp.dto.BackendCatalog.Substitute;
import com.example.rfq.businessmcp.dto.PartSupply;
import com.example.rfq.businessmcp.dto.SubstituteSupply;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyServiceTest {
    private static final LocalDate DUE = LocalDate.parse("2026-12-15");
    private static final LocalDate BASE = LocalDate.parse("2026-10-19");
    private static final Part MCU = new Part("P-MCU-G1", "제어칩 (고온용)", "세온반도체", 5300, 120, true);
    private static final Part ALT_PART = new Part("P-MCU-G1-ALT", "제어칩 (고온용, 대체)", "누리칩스", 5600, 30, true);
    private static final Substitute ALT = new Substitute("P-MCU-G1", "P-MCU-G1-ALT", "제어칩 (고온용, 대체)", "누리칩스",
            14, true, "alt-002", 300, 3000);
    private static final SubstituteSupply ALT_SUPPLY = new SubstituteSupply("P-MCU-G1-ALT", "제어칩 (고온용, 대체)",
            "누리칩스", 3000, true, 5600, 300, 14, "2026-11-02", 30, true, "alt-002");

    private static Inventory inventory(int onHand, int allocated, int incomingQty, LocalDate incomingDate) {
        return new Inventory("P-X", onHand, allocated, onHand - allocated, incomingQty, incomingDate);
    }

    @Test
    void shortPartGetsAllRisksAndSubstitutes() {
        PartSupply result = SupplyService.assess(MCU, inventory(9000, 7500, 5000, LocalDate.parse("2027-01-20")),
                2000, DUE, BASE, List.of(ALT_SUPPLY));

        assertThat(result.onHand()).isEqualTo(9000);
        assertThat(result.allocated()).isEqualTo(7500);
        assertThat(result.available()).isEqualTo(1500);
        assertThat(result.shortQty()).isEqualTo(500);
        assertThat(result.status()).isEqualTo("SHORT");
        assertThat(result.incomingBeforeDue()).isFalse();
        assertThat(result.incomingDelayDays()).isEqualTo(36);
        assertThat(result.newOrderArrival()).isEqualTo("2027-02-16");
        assertThat(result.risks()).containsExactly("SHORTAGE", "INCOMING_AFTER_DUE", "NEW_ORDER_AFTER_DUE");
        assertThat(result.substitutes()).containsExactly(ALT_SUPPLY);
    }

    /** 백엔드가 준 available을 그대로 쓴다 (on_hand - allocated를 다시 계산하지 않음). */
    @Test
    void usesBackendAvailable() {
        Inventory backend = new Inventory("P-MCU-G1", 9000, 7500, 1400, 0, null);
        PartSupply result = SupplyService.assess(MCU, backend, 2000, DUE, BASE, List.of());

        assertThat(result.available()).isEqualTo(1400);
        assertThat(result.shortQty()).isEqualTo(600);
    }

    @Test
    void okPartHasNoRisksOrSubstitutes() {
        PartSupply result = SupplyService.assess(MCU, inventory(9000, 1000, 0, null), 2000, DUE, BASE,
                List.of(ALT_SUPPLY));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.shortQty()).isZero();
        assertThat(result.incomingDate()).isNull();
        assertThat(result.incomingBeforeDue()).isFalse();
        assertThat(result.incomingDelayDays()).isNull();
        assertThat(result.risks()).isEmpty();
        assertThat(result.substitutes()).isEmpty();
    }

    @Test
    void shortPartWithIncomingBeforeDueAndFastReorder() {
        Part fast = new Part("P-X", "x", "s", 100, 10, false);
        PartSupply result = SupplyService.assess(fast, inventory(100, 0, 500, DUE), 200, DUE, BASE, List.of());

        assertThat(result.incomingBeforeDue()).isTrue();
        assertThat(result.incomingDelayDays()).isZero();
        assertThat(result.risks()).containsExactly("SHORTAGE");
    }

    @Test
    void shortPartWithTooSmallIncomingBeforeDueIsIncomingInsufficient() {
        Part fast = new Part("P-X", "x", "s", 100, 10, false);
        PartSupply result = SupplyService.assess(fast, inventory(100, 0, 50, DUE), 200, DUE, BASE, List.of());

        assertThat(result.incomingBeforeDue()).isTrue();
        assertThat(result.risks()).containsExactly("SHORTAGE", "INCOMING_INSUFFICIENT");
    }

    @Test
    void shortPartWithoutIncomingIsIncomingAfterDue() {
        Part fast = new Part("P-X", "x", "s", 100, 10, false);
        PartSupply result = SupplyService.assess(fast, inventory(100, 0, 0, null), 200, DUE, BASE, List.of());

        assertThat(result.incomingDelayDays()).isNull();
        assertThat(result.risks()).containsExactly("SHORTAGE", "INCOMING_AFTER_DUE");
    }

    @Test
    void substituteUsesBackendAvailableAndCostDiff() {
        assertThat(SupplyService.toSubstituteSupply(ALT, ALT_PART, 500, BASE)).isEqualTo(ALT_SUPPLY);

        SubstituteSupply notEnough = SupplyService.toSubstituteSupply(ALT, ALT_PART, 3001, BASE);
        assertThat(notEnough.coversShortage()).isFalse();
    }

    /** get_substitutes에서 short_qty를 주지 않으면 covers_shortage는 null. */
    @Test
    void substituteWithoutShortQtyHasNullCoversShortage() {
        assertThat(SupplyService.toSubstituteSupply(ALT, ALT_PART, null, BASE).coversShortage()).isNull();
    }
}
