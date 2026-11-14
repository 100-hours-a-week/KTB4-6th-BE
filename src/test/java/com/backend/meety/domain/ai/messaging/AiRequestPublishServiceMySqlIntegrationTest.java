package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.PublishStatus;
import com.backend.meety.domain.ai.repository.AiRequestMySqlTestSupport;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

@ContextConfiguration(classes = AiRequestPublishServiceMySqlIntegrationTest.ClaimConfig.class)
class AiRequestPublishServiceMySqlIntegrationTest extends AiRequestMySqlTestSupport {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);

    @Configuration(proxyBeanMethods = false)
    @Import(AiRequestPublishService.class)
    static class ClaimConfig {

        @Bean
        Clock clock() {
            ZoneId zone = ZoneId.systemDefault();
            return Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        }
    }

    @Autowired
    private AiRequestPublishService publishService;

    @Test
    @DisplayName("발행 준비가 끝난 요청을 선점하면 PUBLISHING과 선점 시각을 기록하고 발행 정보를 반환한다")
    void claimsPublishableRequest() {
        long requestId = createPublishable(NOW.minusSeconds(1));

        Optional<AiRequestOutboxRecord> outboxRecord = publishService.claim(requestId);

        assertThat(outboxRecord).hasValue(new AiRequestOutboxRecord(requestId, "token-1", "summary", "{\"schemaVersion\": 1}"));
        AiRequest request = findRequest(requestId);
        assertThat(request.getPublishStatus()).isEqualTo(PublishStatus.PUBLISHING);
        assertThat(request.getPublishStartedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("발행 예정 시각에 도달한 요청을 후보로 조회한다")
    void findsDueRequest() {
        long due = createPublishable(NOW);
        long early = createPublishable(NOW.minusMinutes(1));

        assertThat(publishService.findPublishableIds(10)).containsExactly(due, early);
    }

    @Test
    @DisplayName("후보 조회는 배치 크기만큼만 반환한다")
    void limitsCandidatesToBatchSize() {
        long first = createPublishable(NOW);
        createPublishable(NOW);

        assertThat(publishService.findPublishableIds(1)).containsExactly(first);
    }

    @Test
    @DisplayName("발행 예정 시각이 미래인 요청은 후보가 아니고 선점되지 않는다")
    void skipsFutureRequest() {
        long requestId = createPublishable(NOW.plusSeconds(1));

        assertNotPublishable(requestId);
    }

    @Test
    @DisplayName("payload가 없는 요청은 후보가 아니고 선점되지 않는다")
    void skipsRequestWithoutPayload() {
        long requestId = createPublishable(NOW);
        jdbc.update("update ai_requests set message_payload = null where id = ?", requestId);

        assertNotPublishable(requestId);
    }

    @Test
    @DisplayName("Routing Key가 없는 요청은 후보가 아니고 선점되지 않는다")
    void skipsRequestWithoutRoutingKey() {
        long requestId = createPublishable(NOW);
        jdbc.update("update ai_requests set publish_routing_key = null where id = ?", requestId);

        assertNotPublishable(requestId);
    }

    @Test
    @DisplayName("processingToken이 없는 요청은 후보가 아니고 선점되지 않는다")
    void skipsRequestWithoutToken() {
        long requestId = createPublishable(NOW);
        jdbc.update("update ai_requests set processing_token = null where id = ?", requestId);

        assertNotPublishable(requestId);
    }

    @Test
    @DisplayName("COMPLETED·FAILED 요청은 후보가 아니고 선점되지 않는다")
    void skipsFinishedRequests() {
        long completed = createPublishable(NOW);
        long failed = createPublishable(NOW);
        jdbc.update("update ai_requests set status = 'COMPLETED' where id = ?", completed);
        jdbc.update("update ai_requests set status = 'FAILED' where id = ?", failed);

        assertNotPublishable(completed);
        assertNotPublishable(failed);
    }

    @Test
    @DisplayName("발행 상태가 NULL인 기존·비MQ 요청은 후보가 아니고 선점되지 않는다")
    void skipsNonMqRequest() {
        long requestId = createRequest(AiRequestType.GLOBAL_CHAT);

        assertNotPublishable(requestId);
    }

    @Test
    @DisplayName("삭제된 요청은 후보가 아니고 선점되지 않는다")
    void skipsDeletedRequest() {
        long requestId = createPublishable(NOW);
        jdbc.update("update ai_requests set deleted_at = ? where id = ?", NOW, requestId);

        assertNotPublishable(requestId);
    }

    @Test
    @DisplayName("이미 PUBLISHING·PUBLISHED인 요청은 다시 선점되지 않는다")
    void skipsAlreadyClaimedRequests() {
        long publishing = createPublishable(NOW);
        long published = createPublishable(NOW);
        jdbc.update("update ai_requests set publish_status = 'PUBLISHING' where id = ?", publishing);
        jdbc.update("update ai_requests set publish_status = 'PUBLISHED' where id = ?", published);

        assertNotPublishable(publishing);
        assertNotPublishable(published);
    }

    @Test
    @DisplayName("같은 요청을 연속으로 선점하면 첫 번째만 성공한다")
    void onlyFirstSequentialClaimSucceeds() {
        long requestId = createPublishable(NOW);

        assertThat(publishService.claim(requestId)).isPresent();
        assertThat(publishService.claim(requestId)).isEmpty();
    }

    @Test
    @DisplayName("여러 폴러가 같은 요청을 동시에 선점하면 한 곳만 성공한다")
    void onlyOneConcurrentClaimSucceeds() throws Exception {
        long requestId = createPublishable(NOW);
        int pollers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(pollers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<AiRequestOutboxRecord>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < pollers; i++) {
                Callable<Optional<AiRequestOutboxRecord>> claim = () -> {
                    start.await();
                    return publishService.claim(requestId);
                };
                results.add(executor.submit(claim));
            }
            start.countDown();

            int succeeded = 0;
            for (Future<Optional<AiRequestOutboxRecord>> result : results) {
                if (result.get(10, TimeUnit.SECONDS).isPresent()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isOne();
        } finally {
            executor.shutdownNow();
        }
        assertThat(findRequest(requestId).getPublishStatus()).isEqualTo(PublishStatus.PUBLISHING);
    }

    private long createPublishable(LocalDateTime nextPublishAt) {
        long requestId = createRequest(AiRequestType.SUMMARY);
        jdbc.update("""
                        update ai_requests
                        set publish_status = 'UNPUBLISHED', processing_token = 'token-1',
                            message_payload = '{"schemaVersion": 1}', publish_routing_key = 'summary',
                            next_publish_at = ?
                        where id = ?
                        """,
                nextPublishAt, requestId);
        return requestId;
    }

    private void assertNotPublishable(long requestId) {
        assertThat(publishService.findPublishableIds(100)).doesNotContain(requestId);
        assertThat(publishService.claim(requestId)).isEmpty();
    }
}
