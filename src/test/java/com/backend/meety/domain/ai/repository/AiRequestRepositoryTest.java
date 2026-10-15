package com.backend.meety.domain.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

class AiRequestRepositoryTest {

    @Test
    @DisplayName("Atomic Claim은 영속성 컨텍스트를 정리하는 단일 조건부 UPDATE로 선언된다")
    void claimAcceptedUsesConditionalBulkUpdate() throws NoSuchMethodException {
        // 테스트 목적:
        // Atomic Claim Repository 계약이 ACCEPTED 요청만 PROCESSING으로 바꾸고
        // bulk update 이후 stale entity가 남지 않도록 자동 flush와 clear를 사용하는지 검증한다.

        // given
        Method method = AiRequestRepository.class.getMethod("claimAccepted", Long.class);

        // when
        Modifying modifying = method.getAnnotation(Modifying.class);
        String query = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ").trim();

        // then
        assertThat(method.getReturnType()).isEqualTo(int.class);
        assertThat(modifying.flushAutomatically()).isTrue();
        assertThat(modifying.clearAutomatically()).isTrue();
        assertThat(query).contains("update AiRequest a");
        assertThat(query).contains("set a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING");
        assertThat(query).contains("where a.id = :id");
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.ACCEPTED");
    }
}
