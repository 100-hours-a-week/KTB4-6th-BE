package com.backend.meety.domain.team.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvitationCodeGeneratorTest {

    @Test
    @DisplayName("영문 대문자와 숫자로 이루어진 8자 코드를 생성한다")
    void generateMatchesPolicy() {
        for (int i = 0; i < 100; i++) {
            assertThat(InvitationCodeGenerator.generate()).matches("[A-Z0-9]{8}");
        }
    }

    @Test
    @DisplayName("생성되는 코드는 매번 달라진다")
    void generateProducesDistinctCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            codes.add(InvitationCodeGenerator.generate());
        }
        assertThat(codes).hasSize(100);
    }
}
