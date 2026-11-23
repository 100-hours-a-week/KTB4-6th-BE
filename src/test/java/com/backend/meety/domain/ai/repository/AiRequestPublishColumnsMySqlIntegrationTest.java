package com.backend.meety.domain.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.PublishStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

class AiRequestPublishColumnsMySqlIntegrationTest extends AiRequestMySqlTestSupport {

    static final String ADD_PUBLISH_COLUMNS = """
            ALTER TABLE ai_requests
                ADD COLUMN publish_status ENUM('PUBLISHED','PUBLISHING','UNPUBLISHED') NULL,
                ADD COLUMN message_payload JSON NULL,
                ADD COLUMN publish_routing_key VARCHAR(50) NULL,
                ADD COLUMN next_publish_at DATETIME(6) NULL,
                ADD COLUMN publish_started_at DATETIME(6) NULL,
                ADD COLUMN published_at DATETIME(6) NULL,
                ALGORITHM=INSTANT
            """;

    @Test
    @DisplayName("새 AI 요청은 발행 관리 컬럼이 모두 NULL로 저장된다")
    void newRequestHasNoPublishState() {
        long requestId = createRequest(AiRequestType.SUMMARY);

        AiRequest request = findRequest(requestId);

        assertThat(request.getPublishStatus()).isNull();
        assertThat(request.getMessagePayload()).isNull();
        assertThat(request.getPublishRoutingKey()).isNull();
        assertThat(request.getNextPublishAt()).isNull();
        assertThat(request.getPublishStartedAt()).isNull();
        assertThat(request.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("발행 관리 컬럼 값이 엔티티로 그대로 조회된다")
    void readsPublishColumns() {
        long requestId = createRequest(AiRequestType.SUMMARY);
        LocalDateTime nextPublishAt = LocalDateTime.of(2026, 11, 14, 10, 0, 0, 123_456_000);
        LocalDateTime publishStartedAt = nextPublishAt.plusSeconds(1);
        LocalDateTime publishedAt = nextPublishAt.plusSeconds(2);
        jdbc.update("""
                        update ai_requests
                        set publish_status = 'PUBLISHED', message_payload = ?, publish_routing_key = 'summary',
                            next_publish_at = ?, publish_started_at = ?, published_at = ?
                        where id = ?
                        """,
                "{\"schemaVersion\": 1, \"input\": {\"meetingId\": 7}}",
                nextPublishAt, publishStartedAt, publishedAt, requestId);

        AiRequest request = findRequest(requestId);

        assertThat(request.getPublishStatus()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(request.getMessagePayload()).contains("\"schemaVersion\": 1").contains("\"meetingId\": 7");
        assertThat(request.getPublishRoutingKey()).isEqualTo("summary");
        assertThat(request.getNextPublishAt()).isEqualTo(nextPublishAt);
        assertThat(request.getPublishStartedAt()).isEqualTo(publishStartedAt);
        assertThat(request.getPublishedAt()).isEqualTo(publishedAt);
    }

    @Test
    @DisplayName("HTTP 처리 대상 조회는 발행 상태가 있는 MQ 요청을 제외한다")
    void findsOnlyHttpProcessingTargets() {
        long legacy = createRequest(AiRequestType.SUMMARY);
        long mq = createRequest(AiRequestType.SUMMARY);
        jdbc.update("update ai_requests set publish_status = 'UNPUBLISHED' where id = ?", mq);

        List<Long> targetIds = transaction.execute(status -> aiRequests.findHttpProcessingTargets(
                AiRequestType.SUMMARY, AiRequestStatus.ACCEPTED, Limit.of(10)).stream()
                .map(AiRequest::getId)
                .toList());

        assertThat(targetIds).containsExactly(legacy);
    }

    @Test
    @DisplayName("입력 준비 대기 조회는 발행 메시지가 없는 UNPUBLISHED 요청만 반환한다")
    void findsRequestsAwaitingPublishInput() {
        long awaiting = createRequest(AiRequestType.DIARIZATION);
        long written = createRequest(AiRequestType.DIARIZATION);
        long legacy = createRequest(AiRequestType.DIARIZATION);
        jdbc.update("update ai_requests set publish_status = 'UNPUBLISHED' where id in (?, ?)", awaiting, written);
        jdbc.update("update ai_requests set message_payload = '{}' where id = ?", written);

        List<Long> ids = transaction.execute(status ->
                aiRequests.findIdsAwaitingPublishInput(AiRequestType.DIARIZATION, Limit.of(10)));

        assertThat(ids).containsExactly(awaiting);
    }

    @Test
    @DisplayName("운영 반영 SQL을 적용하면 기존 행은 NULL로 남고 엔티티 스키마 검증을 통과한다")
    void migrationKeepsExistingRowsUnpublishable() {
        long legacyRequestId = createRequest(AiRequestType.SUMMARY);
        jdbc.execute("""
                ALTER TABLE ai_requests
                    DROP COLUMN publish_status, DROP COLUMN message_payload, DROP COLUMN publish_routing_key,
                    DROP COLUMN next_publish_at, DROP COLUMN publish_started_at, DROP COLUMN published_at
                """);

        jdbc.execute(ADD_PUBLISH_COLUMNS);

        assertThat(jdbc.queryForObject(
                "select count(*) from ai_requests where id = ? and publish_status is null and message_payload is null",
                Integer.class, legacyRequestId)).isOne();
        assertThat(findRequest(legacyRequestId).getPublishStatus()).isNull();
        LocalContainerEntityManagerFactoryBean validator = DatabaseConfig.entityManagerFactory(dataSource, "validate");
        assertThatCode(() -> {
            validator.afterPropertiesSet();
            validator.destroy();
        }).doesNotThrowAnyException();
    }
}
