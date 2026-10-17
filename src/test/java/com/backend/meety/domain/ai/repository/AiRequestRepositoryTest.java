package com.backend.meety.domain.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
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
        Method method = AiRequestRepository.class.getMethod("claimAccepted", Long.class, String.class);

        // when
        Modifying modifying = method.getAnnotation(Modifying.class);
        String query = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ").trim();

        // then
        assertThat(method.getReturnType()).isEqualTo(int.class);
        assertThat(modifying.flushAutomatically()).isTrue();
        assertThat(modifying.clearAutomatically()).isTrue();
        assertThat(query).contains("update AiRequest a");
        assertThat(query).contains("set a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING");
        assertThat(query).contains("a.processingToken = :processingToken");
        assertThat(query).contains("a.updatedAt = CURRENT_TIMESTAMP");
        assertThat(query).contains("where a.id = :id");
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.ACCEPTED");
    }

    @Test
    @DisplayName("완료 처리는 PROCESSING 상태와 processingToken을 검사하는 조건부 UPDATE로 선언된다")
    void completionUsesAttemptGuardedBulkUpdate() throws NoSuchMethodException {
        // 테스트 목적:
        // 늦게 도착한 이전 Attempt 성공 응답이 현재 Attempt의 상태와 결과를 변경하지 못하도록
        // 완료 전이가 PROCESSING 상태와 processingToken을 함께 검사하는지 검증한다.

        // given
        Method method = AiRequestRepository.class.getMethod(
                "completeProcessing", Long.class, String.class);

        // when
        Modifying modifying = method.getAnnotation(Modifying.class);
        String query = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ").trim();

        // then
        assertThat(method.getReturnType()).isEqualTo(int.class);
        assertThat(modifying.flushAutomatically()).isTrue();
        assertThat(modifying.clearAutomatically()).isTrue();
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.COMPLETED");
        assertThat(query).contains("a.processingToken = null");
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING");
        assertThat(query).contains("a.processingToken = :processingToken");
    }

    @Test
    @DisplayName("Retry 처리는 현재 processingToken과 재시도 횟수를 검사하는 조건부 UPDATE로 선언된다")
    void retryUsesAttemptGuardedBulkUpdate() throws NoSuchMethodException {
        // 테스트 목적:
        // 이전 Attempt 실패 응답이 retryCount를 올리거나 ACCEPTED로 되돌리지 못하도록
        // Retry 전이가 현재 processingToken과 횟수 조건을 함께 검사하는지 검증한다.

        // given
        Method method = AiRequestRepository.class.getMethod(
                "retryProcessing", Long.class, String.class, long.class);

        // when
        String query = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ").trim();

        // then
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.ACCEPTED");
        assertThat(query).contains("a.retryCount = a.retryCount + 1");
        assertThat(query).contains("a.processingToken = null");
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING");
        assertThat(query).contains("a.processingToken = :processingToken");
        assertThat(query).contains("a.retryCount < :lastRetryCount");
    }

    @Test
    @DisplayName("영구 실패 처리는 현재 PROCESSING Attempt만 FAILED로 전이한다")
    void permanentFailureUsesAttemptGuardedBulkUpdate() throws NoSuchMethodException {
        // 테스트 목적:
        // 늦은 이전 Attempt 실패 응답이 최신 Attempt를 FAILED로 만들지 못하도록
        // 영구 실패 전이가 PROCESSING 상태와 processingToken을 함께 검사하는지 검증한다.

        // given
        Method method = AiRequestRepository.class.getMethod(
                "failProcessing", Long.class, String.class);

        // when
        String query = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ").trim();

        // then
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.FAILED");
        assertThat(query).contains("a.failureReason = com.backend.meety.domain.ai.entity.AiFailureReason.AI_CALL_FAILED");
        assertThat(query).contains("a.processingToken = null");
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING");
        assertThat(query).contains("a.processingToken = :processingToken");
    }

    @Test
    @DisplayName("Retry 한도 도달 실패도 현재 processingToken을 검사하는 조건부 UPDATE로 선언된다")
    void retryExhaustionUsesAttemptGuardedBulkUpdate() throws NoSuchMethodException {
        // 테스트 목적:
        // 이전 Attempt 실패 응답이 재시도 한도 도달 분기로 들어오더라도 최신 Attempt를
        // FAILED로 바꾸거나 retryCount를 증가시키지 못하도록 token 조건을 검증한다.

        // given
        Method method = AiRequestRepository.class.getMethod(
                "failRetryExhaustedProcessing", Long.class, String.class, long.class, long.class);

        // when
        String query = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ").trim();

        // then
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.FAILED");
        assertThat(query).contains("a.processingToken = null");
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING");
        assertThat(query).contains("a.processingToken = :processingToken");
        assertThat(query).contains("a.retryCount >= :lastRetryCount");
    }

    @Test
    @DisplayName("stale PROCESSING 복구는 상태·시간·재시도 횟수를 검사하는 조건부 UPDATE로 선언된다")
    void staleRecoveryUsesConditionalBulkUpdate() throws NoSuchMethodException {
        // 테스트 목적:
        // 여러 인스턴스가 같은 요청을 복구해도 PROCESSING 상태, stale 기준 시각,
        // 재시도 한도를 만족하는 단일 UPDATE만 retryCount를 증가시키는지 Repository 계약을 검증한다.

        // given
        Method method = AiRequestRepository.class.getMethod(
                "recoverStaleProcessing", Long.class, LocalDateTime.class, long.class);

        // when
        Modifying modifying = method.getAnnotation(Modifying.class);
        String query = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ").trim();

        // then
        assertThat(method.getReturnType()).isEqualTo(int.class);
        assertThat(modifying.flushAutomatically()).isTrue();
        assertThat(modifying.clearAutomatically()).isTrue();
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.ACCEPTED");
        assertThat(query).contains("a.retryCount = a.retryCount + 1");
        assertThat(query).contains("a.processingToken = null");
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING");
        assertThat(query).contains("a.updatedAt <= :staleBefore");
        assertThat(query).contains("a.retryCount < :lastRetryCount");
    }

    @Test
    @DisplayName("재시도 한도에 도달한 stale PROCESSING은 조건부 UPDATE로 FAILED 처리된다")
    void staleFailureUsesConditionalBulkUpdate() throws NoSuchMethodException {
        // 테스트 목적:
        // 마지막 재시도에서 멈춘 PROCESSING 요청이 멀티 인스턴스 환경에서도 한 번만
        // retryCount 증가와 FAILED 전이를 수행하도록 Repository 계약을 검증한다.

        // given
        Method method = AiRequestRepository.class.getMethod(
                "failStaleProcessing", Long.class, LocalDateTime.class, long.class, long.class);

        // when
        String query = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ").trim();

        // then
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.FAILED");
        assertThat(query).contains("when a.retryCount < :maxRetryCount then a.retryCount + 1");
        assertThat(query).contains("a.failureReason = com.backend.meety.domain.ai.entity.AiFailureReason.AI_CALL_FAILED");
        assertThat(query).contains("a.processingToken = null");
        assertThat(query).contains("a.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING");
        assertThat(query).contains("a.updatedAt <= :staleBefore");
        assertThat(query).contains("a.retryCount >= :lastRetryCount");
    }
}
