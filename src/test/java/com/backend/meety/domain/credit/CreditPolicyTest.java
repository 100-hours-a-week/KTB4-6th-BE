package com.backend.meety.domain.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreditPolicyTest {

    @Test
    @DisplayName("주차 키는 ISO 연도-주차 형식이다")
    void weekKeyOf() {
        assertThat(CreditPolicy.weekKeyOf(LocalDate.of(2026, 9, 28))).isEqualTo("2026-W40");
    }

    @Test
    @DisplayName("연초 며칠은 전년도 마지막 주에 속할 수 있다")
    void weekKeyOfYearBoundary() {
        assertThat(CreditPolicy.weekKeyOf(LocalDate.of(2027, 1, 1))).isEqualTo("2026-W53");
    }
}
