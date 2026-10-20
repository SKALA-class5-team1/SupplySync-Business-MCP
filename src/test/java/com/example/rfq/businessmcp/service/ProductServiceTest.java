/*
 * [테스트] BOM 전개 계산(옵션 부품 교체) 단위 테스트. DB 없이 실행.
 */
package com.example.rfq.businessmcp.service;

import com.example.rfq.businessmcp.dto.OptionInfo;
import com.example.rfq.businessmcp.repository.ProductRepository.BomRow;
import com.example.rfq.businessmcp.service.ProductService.ExpandedPart;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ProductServiceTest {
    private static final List<BomRow> BASE = List.of(
            new BomRow("P-MCU-STD", 1), new BomRow("P-MTR-BL12", 1), new BomRow("P-SEAL-STD", 2));

    private static OptionInfo option(String code, String replaces, String uses) {
        return new OptionInfo(code, code, "f", "v", BigDecimal.ZERO, 0, 0, replaces, uses);
    }

    @Test
    void replacesPartsAndKeepsQtyPerUnit() {
        var expanded = ProductService.applyOptions(BASE, List.of(
                option("OPT-HT", "P-MCU-STD", "P-MCU-G1"), option("OPT-IP9K", "P-SEAL-STD", "P-SEAL-9K")));

        assertThat(expanded).containsExactly(
                new ExpandedPart("P-MCU-G1", 1, "OPTION:OPT-HT", "P-MCU-STD"),
                new ExpandedPart("P-MTR-BL12", 1, "BASE", null),
                new ExpandedPart("P-SEAL-9K", 2, "OPTION:OPT-IP9K", "P-SEAL-STD"));
    }

    @Test
    void noOptionsKeepsBaseBom() {
        assertThat(ProductService.applyOptions(BASE, List.of()))
                .extracting(ExpandedPart::partId, ExpandedPart::source, ExpandedPart::replacedPartId)
                .containsExactly(
                        tuple("P-MCU-STD", "BASE", null),
                        tuple("P-MTR-BL12", "BASE", null),
                        tuple("P-SEAL-STD", "BASE", null));
    }

    @Test
    void rejectsTwoOptionsReplacingSamePart() {
        assertThatThrownBy(() -> ProductService.applyOptions(BASE, List.of(
                option("OPT-1", "P-MCU-STD", "P-MCU-G1"), option("OPT-2", "P-MCU-STD", "P-MCU-G1-ALT"))))
                .hasMessageContaining("P-MCU-STD");
    }

    @Test
    void rejectsReplacingPartNotInBom() {
        assertThatThrownBy(() -> ProductService.applyOptions(BASE, List.of(option("OPT-X", "P-NONE", "P-MCU-G1"))))
                .hasMessageContaining("P-NONE");
    }
}
