/*
 * [테스트] 옵션 입력 정리·전압 표기 변환 단위 테스트. 백엔드 없이 실행.
 * BOM 전개(옵션 부품 교체)는 백엔드가 하므로 이 서버에서는 테스트하지 않는다.
 */
package com.example.rfq.businessmcp.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductServiceTest {
    @Test
    void normalizeOptionsRemovesDuplicatesInInputOrder() {
        assertThat(ProductService.normalizeOptions(List.of("OPT-IP9K", "OPT-HT", "OPT-IP9K")))
                .containsExactly("OPT-IP9K", "OPT-HT");
        assertThat(ProductService.normalizeOptions(null)).isEmpty();
    }

    @Test
    void normalizeOptionsRejectsBlankCode() {
        assertThatThrownBy(() -> ProductService.normalizeOptions(Arrays.asList("OPT-HT", null)))
                .hasMessageContaining("옵션 코드");
        assertThatThrownBy(() -> ProductService.normalizeOptions(List.of(" ")))
                .hasMessageContaining("옵션 코드");
    }

    @Test
    void stripZerosKeepsIntegerVoltageAsInteger() {
        assertThat(ProductService.stripZeros(new BigDecimal("9.0"))).hasToString("9");
        assertThat(ProductService.stripZeros(new BigDecimal("10.0"))).hasToString("10");
        assertThat(ProductService.stripZeros(new BigDecimal("13.5"))).hasToString("13.5");
    }
}
