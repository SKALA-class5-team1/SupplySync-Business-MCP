/*
 * [테스트] 공급 상태·위험 코드·대체 부품 계산 단위 테스트. DB 없이 실행.
 */
package com.example.rfq.businessmcp.service;

import com.example.rfq.businessmcp.dto.PartSupply;
import com.example.rfq.businessmcp.dto.SubstituteSupply;
import com.example.rfq.businessmcp.repository.PartRepository.Inventory;
import com.example.rfq.businessmcp.repository.PartRepository.Part;
import com.example.rfq.businessmcp.repository.PartRepository.Substitute;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyServiceTest {
    private static final LocalDate DUE = LocalDate.parse("2026-12-15");
    private static final LocalDate BASE = LocalDate.parse("2026-10-19");
    private static final Part MCU = new Part("P-MCU-G1", "제어칩 (고온용)", "세온반도체", 5300, 120, true);
    private static final Substitute ALT = new Substitute("P-MCU-G1",
            new Part("P-MCU-G1-ALT", "제어칩 (고온용, 대체)", "누리칩스", 5600, 30, true),
            new Inventory(3000, 0, 0, null), 14, true, "alt-002");

    @Test
    void shortPartGetsAllRisksAndSubstitutes() {
        PartSupply result = SupplyService.assess(MCU, new Inventory(9000, 7500, 5000, LocalDate.parse("2027-01-20")),
                2000, DUE, BASE, List.of(ALT));

        assertThat(result.onHand()).isEqualTo(9000);
        assertThat(result.allocated()).isEqualTo(7500);
        assertThat(result.available()).isEqualTo(1500);
        assertThat(result.shortQty()).isEqualTo(500);
        assertThat(result.status()).isEqualTo("SHORT");
        assertThat(result.incomingBeforeDue()).isFalse();
        assertThat(result.incomingDelayDays()).isEqualTo(36);
        assertThat(result.newOrderArrival()).isEqualTo("2027-02-16");
        assertThat(result.risks()).containsExactly("SHORTAGE", "INCOMING_AFTER_DUE", "NEW_ORDER_AFTER_DUE");
        assertThat(result.substitutes()).containsExactly(new SubstituteSupply("P-MCU-G1-ALT", "제어칩 (고온용, 대체)",
                "누리칩스", 3000, true, 5600, 300, 14, "2026-11-02", 30, true, "alt-002"));
    }

    @Test
    void okPartHasNoRisksOrSubstitutes() {
        PartSupply result = SupplyService.assess(MCU, new Inventory(9000, 1000, 0, null), 2000, DUE, BASE, List.of(ALT));

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
        PartSupply result = SupplyService.assess(fast, new Inventory(100, 0, 500, DUE), 200, DUE, BASE, List.of());

        assertThat(result.incomingBeforeDue()).isTrue();
        assertThat(result.incomingDelayDays()).isZero();
        assertThat(result.risks()).containsExactly("SHORTAGE");
    }

    @Test
    void shortPartWithTooSmallIncomingBeforeDueIsIncomingInsufficient() {
        Part fast = new Part("P-X", "x", "s", 100, 10, false);
        PartSupply result = SupplyService.assess(fast, new Inventory(100, 0, 50, DUE), 200, DUE, BASE, List.of());

        assertThat(result.incomingBeforeDue()).isTrue();
        assertThat(result.risks()).containsExactly("SHORTAGE", "INCOMING_INSUFFICIENT");
    }

    @Test
    void shortPartWithoutIncomingIsIncomingAfterDue() {
        Part fast = new Part("P-X", "x", "s", 100, 10, false);
        PartSupply result = SupplyService.assess(fast, new Inventory(100, 0, 0, null), 200, DUE, BASE, List.of());

        assertThat(result.incomingDelayDays()).isNull();
        assertThat(result.risks()).containsExactly("SHORTAGE", "INCOMING_AFTER_DUE");
    }

    @Test
    void substituteWithoutInventoryRowCountsAsZero() {
        Substitute noStock = new Substitute("P-MCU-G1", ALT.part(), null, 14, true, "alt-002");
        PartSupply result = SupplyService.assess(MCU, new Inventory(1500, 0, 0, null), 2000, DUE, BASE, List.of(noStock));

        assertThat(result.substitutes()).singleElement().satisfies(s -> {
            assertThat(s.available()).isZero();
            assertThat(s.coversShortage()).isFalse();
        });
    }
}
